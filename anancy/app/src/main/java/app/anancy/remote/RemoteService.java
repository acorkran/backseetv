package app.anancy.remote;

import app.anancy.Diag;
import app.anancy.Cfg;
import app.anancy.net.BleClient;
import app.anancy.net.HotspotService;
import app.anancy.net.WearPusher;
import app.anancy.net.WifiClient;
import app.anancy.net.WifiJoiner;
import app.anancy.net.WifiRadio;

// ============================================================================
//  Phone-side connection service (foreground). Owns BOTH transports and picks:
//  BLE is primary; if BLE hasn't connected in ~7s, it also brings up the Wi-Fi/
//  mDNS fallback and uses whichever is live (preferring BLE). send() routes to
//  the active transport; state() is the latest reported state. Shared by the
//  phone RemoteActivity AND the Android Auto session.
//
//  ON DEMAND: the link only runs while something holds it — the remote activity
//  is resumed, an AA session is alive, the TV's own admin Remote is open — plus
//  a short linger. When the last user releases, both transports stop and the
//  service exits. (A 24/7 link is a battery drain: LOW_LATENCY scanning when
//  the TV is away, 1 Hz wakeups when it's near.)
//
//  CONNECTION LEVELS (anancy_conn, chosen on either side, published by the TV
//  in state): ble = pure Bluetooth (no Wi-Fi client at all); tv_hotspot = the
//  TV hosts a hotspot and publishes its creds -> we join it; remote_hotspot =
//  THIS phone hosts a hotspot and pushes its creds to the TV over BLE ("wifi:"),
//  the TV joins; shared = both on the same network (nothing automatic).
// ============================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteService extends Service {
    public interface Listener { void onStatus(String s); void onState(String json); }
    static final String TAG = "Anancy";
    static final long LINGER_MS = 45_000;

    private static RemoteService INSTANCE;
    public static RemoteService get() { return INSTANCE; }

    /** Start without a user (package-replaced push, legacy callers): idles out after the linger. */
    public static void ensure(Context ctx) { start(ctx, null, false); }
    /** The TV's own admin Remote: loopback HTTP to this very player, no BLE. */
    public static void ensureLocal(Context ctx) { start(ctx, null, true); }
    /** Something needs the link (tag = "ui", "aa", ...). Starts the service if needed. */
    public static void acquire(Context ctx, String tag) {
        RemoteService s = INSTANCE;
        if (s != null) { s.doAcquire(tag); return; }
        start(ctx, tag, false);
    }
    /** That user is done; the link lingers briefly, then shuts down if nobody else needs it. */
    public static void release(Context ctx, String tag) { RemoteService s = INSTANCE; if (s != null) s.doRelease(tag); }
    private static void start(Context ctx, String tag, boolean local) {
        Intent i = new Intent(ctx.getApplicationContext(), RemoteService.class).putExtra("local", local);
        if (tag != null) i.putExtra("acquire", tag);
        if (Build.VERSION.SDK_INT >= 26) ctx.getApplicationContext().startForegroundService(i);
        else ctx.getApplicationContext().startService(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> uses = new HashSet<>();
    private BleClient ble;
    private WifiClient wifi;
    private volatile String lastState = "{}", lastStatus = "starting…", transport = "—";
    private volatile java.util.List<String> playlistNames = new java.util.ArrayList<>();
    private volatile java.util.List<String> userPlaylistNames = new java.util.ArrayList<>();
    private Listener ui;
    private boolean started = false;
    private volatile boolean local = false;   // TV-side admin Remote: loopback HTTP to ourselves, no BLE

    // --- typed one-shot payloads (playlists / queue / library / episodes / conn) --------------------
    // The TV answers query commands on the STATE channel with {"type":...} payloads; they're cached
    // here by type (never treated as state) so a UI can request + poll. The plain state has no "type".
    private final Map<String, String> typed = new ConcurrentHashMap<>();
    private static String typeOf(String json) {
        if (json == null) return null;
        int i = json.indexOf("\"type\":\"");
        if (i < 0) return null;
        int e = json.indexOf('"', i + 8);
        return e > 0 ? json.substring(i + 8, e) : null;
    }
    /** Clear the cached payload of `type` and send the query; poll typedJson(type) for the answer. */
    public void requestTyped(String cmd, String type) { typed.remove(type); send(cmd); }
    public String typedJson(String type) { return typed.get(type); }
    public String queueJson() { return typed.get("queue"); }
    public void requestQueue() { requestTyped("queue", "queue"); }
    public void requestPlaylists() { playlistTotal = -1; requestTyped("list", "playlists"); }   // clears the cache: a saved/deleted playlist used to never show
    public void requestQueueFrom(int from) { requestTyped("queue:" + from, "queue"); }
    private volatile int playlistTotal = -1;
    public int playlistTotal() { return playlistTotal; }
    public void requestShows(int from) { requestTyped(from > 0 ? "shows:" + from : "shows", "library"); }
    public void requestEpisodes(String show, int from) { requestTyped("eps:" + show + ":" + from, "episodes"); }
    public void requestConn() { requestTyped("conn", "conn"); }
    public void requestConfig() { cfgAccum = null; cfgNext = 0; requestTyped("cfg", "config"); }
    public String configJson() { return typed.get("config"); }
    /** Write one tablet setting from a remote (mirrors the on-tablet Settings screen). */
    public void setConfig(String key, String val) {
        if ("anancy_host".equals(key) && val != null && !val.isEmpty()) grantHostTo(val);
        send("cfg:" + key + "=" + val);
    }
    /** A TV told to stream from the TV at `hostIp` needs that TV's key: send it, sealed per TV. */
    private void grantHostTo(String hostIp) {
        String hostId = null;
        for (Map.Entry<String, String> e : deviceIps.entrySet()) if (hostIp.equals(e.getValue())) hostId = e.getKey();
        String fullHost = hostId == null ? null : app.anancy.Pairing.controlKey(this, hostId);
        String hostKey = fullHost == null ? null : app.anancy.net.PairCrypto.mediaKey(fullHost);   // streaming only
        if (hostKey == null) { toastMain("This phone isn't paired with the screen at " + hostIp + ", so it can't share its library."); return; }
        for (String id : effectiveTargets().isEmpty() ? seenTablets.keySet() : effectiveTargets()) {
            if (id.equals(hostId)) continue;
            String sealed = app.anancy.Pairing.sealFor(this, id, hostKey);
            if (sealed == null) continue;
            String cmd = "grant:" + hostId + ":" + hostIp + ":" + sealed;
            if (ble != null && ble.connectedIds().contains(id)) ble.sendToIds(java.util.Collections.singleton(id), cmd);
            else httpArm(deviceIp(id), "/grant?id=" + hostId + "&ip=" + hostIp + "&key=" + sealed);
        }
    }
    public String libraryJson() { return typed.get("library"); }
    public String episodesJson() { return typed.get("episodes"); }
    public String connJson() { return typed.get("conn"); }
    public java.util.List<String> playlistNames() { return playlistNames; }
    public java.util.List<String> userPlaylistNames() { return userPlaylistNames; }

    private JSONObject cfgAccum;   // config arrives in pages over BLE; publish only once it is whole
    private int cfgNext;           // the offset the next page must start at

    /** Merge a config page. The UI must never see a half-payload: it would render every setting at
        its default behind a padlock, which is indistinguishable from "this TV can't do that". */
    private void mergeConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int from = o.optInt("from", 0);
            if (from > 0 && (cfgAccum == null || from != cfgNext)) return;   // another client's page, or a repeat
            if (from > 0) {
                JSONObject av = cfgAccum.optJSONObject("values"), nv = o.optJSONObject("values");
                if (av != null && nv != null)
                    for (java.util.Iterator<String> it = nv.keys(); it.hasNext(); ) { String k = it.next(); av.put(k, nv.get(k)); }
            } else cfgAccum = o;        // first page carries device_owner + pin_set
            int more = o.optInt("more", 0);
            cfgNext = more;
            if (more > 0) { typed.remove("config"); send("cfg@" + more); }
            else typed.put("config", cfgAccum.toString());
        } catch (Exception ignored) {}
    }

    private void onTyped(String type, String json) {
        if ("config".equals(type)) { mergeConfig(json); return; }
        typed.put(type, json);
        if ("playlists".equals(type)) cachePlaylists(json);
        else if ("conn".equals(type)) {
            try { JSONObject o = new JSONObject(json); noteConn(o.optString("mode", ""), o.optJSONObject("hotspot")); } catch (Exception ignored) {}
        }
    }
    private void cachePlaylists(String json) {
        try {
            JSONObject o = new JSONObject(json);
            org.json.JSONArray a = o.optJSONArray("names"), u = o.optJSONArray("user");
            java.util.List<String> page = new java.util.ArrayList<>(), usr = new java.util.ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) page.add(a.optString(i));
            if (u != null) for (int i = 0; i < u.length(); i++) usr.add(u.optString(i));
            int from = o.optInt("from", 0), total = o.optInt("total", page.size());
            if (from > 0 && from != playlistNames.size()) return;  // the TV notifies every client: skip others' pages and repeats
            playlistTotal = total;
            if (from == 0) { playlistNames = page; userPlaylistNames = usr; }   // first page replaces
            else {                                                 // later pages append (BLE sends it in slices)
                java.util.List<String> merged = new java.util.ArrayList<>(playlistNames), mergedUser = new java.util.ArrayList<>(userPlaylistNames);
                merged.addAll(page); mergedUser.addAll(usr);
                playlistNames = merged; userPlaylistNames = mergedUser;
            }
            if (!page.isEmpty() && playlistNames.size() < total) send("list:" + playlistNames.size());   // pull the rest, unprompted
        } catch (Exception ignored) {}
    }
    /** Route one incoming payload: typed answers go to the cache, everything else is state. */
    private void onIncoming(String json, String via) {
        String t = typeOf(json);
        if (t != null) { onTyped(t, json); return; }
        lastState = json; transport = via;
        try {   // the TV publishes its connection level + hotspot creds in state
            JSONObject o = new JSONObject(json);
            if (o.has("conn")) noteConn(o.optString("conn", ""), o.optJSONObject("hotspot"));
            String id = o.optString("id", ""), ip = o.optString("ip", "");   // learn the shown device's ip + host-ness (for sync-group arming)
            if (!id.isEmpty()) {
                if (!ip.isEmpty()) { deviceIps.put(id, ip); app.anancy.Pairing.noteHost(this, ip, id); }
                if (o.has("client")) { if (o.optBoolean("client")) hostIds.remove(id); else hostIds.add(id); }
            }
        } catch (Exception ignored) {}
        if (ui != null) ui.onState(json);
    }

    // --- connection level -------------------------------------------------------------------------
    private volatile String connMode = "auto";
    private volatile String hsSsid = null, hsPass = null;   // the TV's hotspot creds, while it runs
    private String joinedKey = null, pushedKey = null;
    public String connMode() { return connMode; }
    /** The TV's published hotspot creds [ssid, pass], or null. */
    public String[] tvHotspot() { String s = hsSsid, p = hsPass; return (s != null && p != null) ? new String[]{s, p} : null; }
    /** Called by the UI when the user picks a level here: persist, tell the TV, apply locally. */
    public void setConnMode(String mode) {
        if (mode == null || mode.isEmpty()) return;
        Cfg.put(this, "anancy_conn", mode);
        send("conn:" + mode);
        connMode = mode;
        applyConnMode();
    }
    private void noteConn(String mode, JSONObject hotspot) {
        String wasSsid = hsSsid, wasPass = hsPass;
        if (hotspot != null) { hsSsid = hotspot.optString("ssid", null); hsPass = hotspot.optString("pass", null); }
        else { hsSsid = null; hsPass = null; }
        // New creds (the TV rebooted and its hotspot picked a new random SSID) -> a fresh rejoin budget,
        // otherwise the tries spent on the stale pair leave nothing for the one that would work.
        if (hsSsid != null && (!hsSsid.equals(wasSsid) || !String.valueOf(hsPass).equals(String.valueOf(wasPass)))) rejoinTries = 0;
        // "auto" is a decision about THIS phone's radio, not about the TV, so the TV's own level
        // must not drag us off it -- otherwise a TV sitting on "ble" pins every remote to Bluetooth.
        if (!"auto".equals(connMode) && mode != null && !mode.isEmpty() && !mode.equals(connMode)) {
            connMode = mode; Cfg.put(this, "anancy_conn", mode);
        }
        applyConnMode();
    }
    /** Phone-side coordination for the current level (never on the TV's own remote). */
    private void applyConnMode() {
        if (local) return;
        String mode = connMode;
        if ("auto".equals(mode)) { autoEscalate(); return; }
        if ("ble".equals(mode)) {   // pure Bluetooth: no Wi-Fi client, no hotspot of ours
            if (wifi != null) { try { wifi.stop(); } catch (Exception ignored) {} wifi = null; }
            try { WifiJoiner.releaseDirect(this); } catch (Exception ignored) {}   // unbind: give the phone its normal network back
            joinedKey = null;
        } else if (wifi == null) startWifi(null, 0);   // a Wi-Fi tier runs mDNS CONCURRENTLY with BLE so we
        // discover + can reach EVERY tablet (multi-target / both-in-unison), not just as a BLE fallback
        if ("tv_hotspot".equals(mode) && hsSsid != null && hsPass != null) {
            String key = hsSsid + "|" + hsPass;
            if (!key.equals(joinedKey)) {   // join the TV's hotspot once per creds (non-owner phone: a system prompt)
                WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = hsSsid; c.type = "WPA"; c.pass = hsPass;
                boolean ok = false;
                try { ok = WifiJoiner.join(this, c); } catch (Exception e) { Diag.warn(TAG, "join tv hotspot: " + e); }
                // Only remember it as attempted if the request was accepted. Marking the key BEFORE the
                // attempt meant one refusal (the usual case) stopped it ever trying that network again.
                joinedKey = ok ? key : null;
            }
        }
        // ARM ONCE. applyConnMode runs on every state update, so posting here unconditionally either
        // stacked copies (whole retry budget gone in seconds) or, with removeCallbacks, pushed the delay
        // out on every update so it never fired at all. The runnable re-posts itself.
        if ("tv_hotspot".equals(mode)) { if (!rejoinArmed) { rejoinArmed = true; main.postDelayed(rejoin, 15000); } }
        else { rejoinArmed = false; main.removeCallbacks(rejoin); }
        if ("remote_hotspot".equals(mode)) {
            if (!HotspotService.running()) { try { HotspotService.start(this); } catch (Exception e) { Diag.warn(TAG, "hotspot: " + e); } }
            String s = HotspotService.currentSsid(), p = HotspotService.currentPass();
            if (s != null && p != null) {
                String key = s + "|" + p;
                if (!key.equals(pushedKey)) { pushedKey = key; send("wifi:" + s + "\t" + p); }   // the TV joins us (owner = silent)
            } else main.postDelayed(this::applyConnMode, 2000);   // creds arrive once the hotspot is up
        } else if (HotspotService.running()) { try { HotspotService.stop(this); } catch (Exception ignored) {} pushedKey = null; }
    }
    // --- "auto": Bluetooth for control, Wi-Fi only where it buys something -----------------------
    //  Control always rides BLE. It needs no network, costs no radio, and ran a three-hour drive
    //  without a hiccup. Wi-Fi exists for the things BLE physically cannot carry: the LIVE check-in
    //  camera (MJPEG at ~8fps vs a still every 1.3s), importing files to the TV, and the AA dash.
    //  So the link goes up when one of those asks for it -- and stays up afterwards only if this
    //  phone has a radio to spare, because on a single-radio phone holding it means losing the
    //  Wi-Fi you were using. Nothing in here ever moves control off BLE.
    private final Set<String> wifiNeeds = java.util.Collections.synchronizedSet(new HashSet<>());
    private volatile boolean escalated = false;
    private boolean pokePending = false;
    private long autoSince = 0;

    /** A feature that cannot work over Bluetooth wants the Wi-Fi link ("live", "import", "aa"). */
    public void acquireWifi(String tag) { wifiNeeds.add(tag); main.post(this::applyConnMode); }
    /** ...and is done with it. The link drops only if it is not free to keep. */
    public void releaseWifi(String tag) { wifiNeeds.remove(tag); main.post(this::applyConnMode); }
    /** True once the Wi-Fi tier is actually carrying (or ready to carry) the heavy traffic. */
    public boolean wifiEscalated() { return escalated; }

    private void poke(long ms) {   // re-run ourselves once; applyConnMode fires on every state tick,
        if (pokePending) return;   // so an unguarded postDelayed stacks dozens of copies in seconds
        pokePending = true;
        main.postDelayed(() -> { pokePending = false; applyConnMode(); }, ms);
    }

    private void autoEscalate() {
        main.removeCallbacks(rejoin); rejoinArmed = false;   // auto owns its own joins
        // mDNS is how we learn we're ALREADY reachable (home Wi-Fi: nothing to raise). Only worth
        // running when there is a network for it to search, or a link of ours already up -- in the
        // car with no Wi-Fi at all it would just be discovery burning power on an empty radio.
        if (wifi == null && (WifiRadio.onOtherWifi(this) || escalated || !wifiNeeds.isEmpty())) startWifi(null, 0);
        // Android Auto wireless runs over THIS phone's Wi-Fi radio, and the dash outranks the camera:
        // the check-in degrades to Bluetooth stills, AA has nothing to fall back to. So while a car
        // is projecting and this phone can only hold one Wi-Fi link, rescind to Bluetooth outright --
        // even with the live view open. This overrides the demand below on purpose.
        if (WifiRadio.carVetoesWifi(this)) {
            if (escalated) Diag.log(TAG, "auto: Android Auto projecting on a single-radio phone - rescinding Wi-Fi, back to Bluetooth");
            autoSince = 0; dropEscalation(); return;
        }
        // RAISE only on demand -- never spin up a hotspot nobody asked for. KEEP it afterwards when
        // holding it is free (no Wi-Fi to lose, or a spare radio), so in the car the first check-in
        // of the drive pays the few seconds and every one after that is instant.
        // ...and keep it only while it is actually CARRYING. Holding on "escalated" alone would leave
        // a hotspot up forever on any drive where the TV never managed to join it.
        boolean want = !wifiNeeds.isEmpty()
                || (escalated && WifiRadio.freeToHost(this) && wifi != null && wifi.isConnected() && !selfId().equals(wifi.connectedId()));
        if (!want) { autoSince = 0; dropEscalation(); return; }
        if (autoSince == 0) autoSince = android.os.SystemClock.elapsedRealtime();

        // Already reachable over Wi-Fi: nothing to raise. But "reachable" means a TV we need, not this
        // phone's own player: with "this phone as playback" ticked the Wi-Fi client could be happily
        // polling ourselves on the home network while the tablet sat on no network at all, and the
        // hotspot that would have joined them never came up. A pending sync needs its members answering.
        String polled = wifi != null && wifi.isConnected() ? wifi.connectedId() : null;
        boolean reachesTv = polled != null && !polled.equals(selfId());
        if (reachesTv && !(syncPending && reachableMembers() < 2)) { escalated = true; return; }
        // Don't start raising radios until mDNS has had a fair go at finding the TV on this network.
        if (android.os.SystemClock.elapsedRealtime() - autoSince < 5000) { poke(1500); return; }

        // Prefer WE host. The TV is device owner, so it joins silently (addNetwork/enableNetwork) --
        // zero taps, and the random local-only SSID stops mattering because the only joiner needs no
        // approval. Joining the TV's hotspot the other way costs one system dialog per SSID, and it
        // re-randomises on every tablet reboot, which is exactly what made it useless in the car.
        if (bleUp() && WifiRadio.freeToHost(this)) {
            if (!HotspotService.running()) {
                try { HotspotService.start(this, false); } catch (Exception e) { Diag.warn(TAG, "auto host: " + e); }
            }
            String s = HotspotService.currentSsid(), p = HotspotService.currentPass();
            if (s != null && p != null) {
                String key = s + "|" + p;
                if (!key.equals(pushedKey)) {
                    pushedKey = key; send("wifi:" + s + "\t" + p);
                    Diag.log(TAG, "auto: hosting for the TV, creds pushed over BLE (" + s + ")");
                }
                escalated = true;
            } else poke(2000);   // the creds only exist once the hotspot is actually up
            return;
        }

        // Fallback: the TV is already advertising a hotspot and we can afford to join it.
        if (hsSsid != null && hsPass != null && WifiRadio.freeToJoin(this) && !WifiJoiner.directPending()) {
            String key = hsSsid + "|" + hsPass;
            if (!key.equals(joinedKey)) {
                WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = hsSsid; c.type = "WPA"; c.pass = hsPass;
                boolean ok = false;
                try { ok = WifiJoiner.joinDirect(this, c, null, null); } catch (Exception e) { Diag.warn(TAG, "auto join: " + e); }
                joinedKey = ok ? key : null;
                if (ok) { escalated = true; Diag.log(TAG, "auto: joining the TV hotspot " + hsSsid); }
            }
        }
    }

    /** Nothing needs Wi-Fi any more and it is not free to hold -- give the phone its network back. */
    private void dropEscalation() {
        if (!escalated) return;
        Diag.log(TAG, "auto: dropping Wi-Fi (needs " + wifiNeeds + ", carProjecting=" + WifiRadio.carProjecting(this)
                + ", vetoed=" + WifiRadio.carVetoesWifi(this) + ", tvLink=" + (wifi != null && wifi.isConnected()) + ")");
        escalated = false; joinedKey = null;
        if (HotspotService.running()) { try { HotspotService.stop(this); } catch (Exception ignored) {} pushedKey = null; }
        try { WifiJoiner.releaseDirect(this); } catch (Exception ignored) {}
        Diag.log(TAG, "auto: Wi-Fi released, Bluetooth only");
    }

    // A TV hotspot restarts with the SAME creds but a NEW subnet whenever the tablet reboots, which
    // silently drops us back to Bluetooth until someone taps Join again - no good in a moving car. Once a
    // direct join has worked on an SSID we re-issue it ourselves, a few times, spaced out.
    private int rejoinTries = 0;
    private boolean rejoinArmed = false;
    private final Runnable rejoin = new Runnable() {
        @Override public void run() {
            if (local || !"tv_hotspot".equals(connMode)) { rejoinTries = 0; rejoinArmed = false; return; }
            // A join already in flight owns the screen: re-issuing releases it, and the system prompt
            // disappears from under the finger that was about to tap Connect.
            if (WifiJoiner.directPending()) { main.postDelayed(this, 30000); return; }
            String s = hsSsid, pw = hsPass;
            if (s != null && pw != null && !s.equals(WifiJoiner.boundSsid())) {
                if (WifiJoiner.directJoinKnown(RemoteService.this) && rejoinTries < 5) {
                    rejoinTries++;
                    Diag.log(TAG, "tv hotspot not bound - auto-rejoin " + s + " (try " + rejoinTries + ")");
                    WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = s; c.type = "WPA"; c.pass = pw;
                    try { WifiJoiner.joinDirect(RemoteService.this, c, null, null); } catch (Exception e) { Diag.warn(TAG, "auto-rejoin: " + e); }
                }
            } else rejoinTries = 0;   // bound: forget the failures, so a later restart gets a fresh budget
            main.postDelayed(this, 30000);
        }
    };

    /** Push the phone-hosted hotspot creds to the TV again (UI "Send Wi-Fi to TV"). */
    public void pushOwnHotspot() { pushedKey = null; applyConnMode(); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        Diag.init(this);
        startForeground(72, note());
        // One-time move to the "auto" default. The old default was a bare "ble" that never escalated,
        // so every existing install would sit on Bluetooth forever and never see the Wi-Fi tier even
        // when a feature needed it. A level picked explicitly AFTER this migration is left alone.
        // Remote side only: a TV shares this key as its own hosting level, and its admin remote runs here too.
        boolean tv = "player".equals(getSharedPreferences("anancy", MODE_PRIVATE).getString("role", ""));
        if (!tv && !"1".equals(Cfg.getStr(this, "anancy_conn_auto", null))) {
            Cfg.put(this, "anancy_conn_auto", "1");
            String was = Cfg.getStr(this, "anancy_conn", null);
            if (was == null || "ble".equals(was) || "tv_hotspot".equals(was)) Cfg.put(this, "anancy_conn", "auto");
        }
        connMode = Cfg.getStr(this, "anancy_conn", "auto");
        app.anancy.Pairing.onPeersChanged = this::onPairingChanged;
        if ("1".equals(Cfg.p(this).getString(PREF_SYNCGROUP, ""))) wifiNeeds.add("sync");   // a group survives a restart, so does its link
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            // On the TV (role=player) this service can only ever be the local admin remote — even on a
            // restart with a null intent it must never start scanning BLE for itself.
            String role = getSharedPreferences("anancy", MODE_PRIVATE).getString("role", "");
            local = "player".equals(role) || (intent != null && intent.getBooleanExtra("local", false));
            if (local) startWifi("127.0.0.1", 8090);
            else startLinks();
        }
        String tag = intent != null ? intent.getStringExtra("acquire") : null;
        if (tag != null) doAcquire(tag);
        else if (uses.isEmpty()) scheduleIdle();
        return START_NOT_STICKY;   // never resurrect ourselves into an idle 24/7 link
    }

    private void doAcquire(String tag) { uses.add(tag); main.removeCallbacks(idle); }
    private void doRelease(String tag) { uses.remove(tag); if (uses.isEmpty()) scheduleIdle(); }
    private void scheduleIdle() { main.removeCallbacks(idle); main.postDelayed(idle, LINGER_MS); }
    private final Runnable idle = () -> {
        if (!uses.isEmpty()) return;
        Diag.log(TAG, "remote link idle -> stopping");
        try { if (ble != null) ble.stop(); } catch (Exception ignored) {} ble = null;
        try { if (wifi != null) wifi.stop(); } catch (Exception ignored) {} wifi = null;
        stopForeground(true);
        stopSelf();
    };

    // --- WHICH tablet (multi-player) ---------------------------------------------------------------
    // Both links used to take whatever player answered first, so a remote in range of two of them could
    // attach to either and swap between them. Every player heard is remembered here; the pick is sticky.
    static final String PREF_TARGET = "anancy_target";   // comma-separated ids to control; "" (or "*") = ALL
    static final String PREF_SYNCGROUP = "anancy_syncgroup";   // leader id when a synced group is armed; "" = off
    static final String PREF_GROUP_AUDIO = "anancy_group_audio";   // "each" = every screen its own sound (default); "leader" = leader only
    public static final String ALL = "*";
    private final Map<String, String> seenTablets = new ConcurrentHashMap<>();
    private final Map<String, String> deviceIps = new ConcurrentHashMap<>();   // id -> LAN ip (from BLE state); merged with wifi hosts
    private final Set<String> hostIds = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());   // devices that hold the library (candidate sync leaders)

    /** A device's LAN ip so we can HTTP its ControlServer directly (arm/disarm a sync group). */
    public String deviceIp(String id) {
        if (id == null) return null;
        if (selfPlay() && id.equals(selfId())) { String me = selfIp(); app.anancy.Pairing.noteHost(this, me, id); return me; }
        String ip = deviceIps.get(id);
        if (ip != null && !ip.isEmpty()) return ip;
        if (wifi != null) { String hp = wifi.hosts().get(id); if (hp != null) { int c = hp.indexOf(':'); return c > 0 ? hp.substring(0, c) : hp; } }
        return null;
    }
    // ---- Synced group: one leader plays with audio, the rest mirror it muted in lock-step ----------------
    // A synced group is ON only when armed AND ≥2 screens are selected (you can't sync one). The leader is
    // always DERIVED from the current selection (a host in it, else the first), so it can never drift out
    // of the group — the selection is the single source of truth.
    public boolean isSyncGroup() { return "1".equals(Cfg.p(this).getString(PREF_SYNCGROUP, "")) && groupOrder().size() >= 2; }
    /** The screens in the group, in order: the current selection, or all seen when "All". */
    private java.util.List<String> groupOrder() {
        java.util.List<String> order = new java.util.ArrayList<>(isAll() ? seenTablets.keySet() : targets());
        if (!local && order.remove(selfId())) order.add(selfId());   // last, so another screen's library leads when there is one
        return order;
    }
    public String syncLeaderId() {
        if (!isSyncGroup()) return null;
        java.util.List<String> order = groupOrder();
        java.util.Set<String> reachable = new java.util.HashSet<>();
        for (String id : order) if (deviceIp(id) != null) reachable.add(id);   // screens we can currently HTTP
        return CommandRouter.deriveLeader(order, hostIds, reachable);          // host > first-reachable > first
    }
    public String syncLeaderName() { String id = syncLeaderId(); if (id == null) return null; String n = seenTablets.get(id); return n != null ? n : id; }
    /** Audio in a synced group: false = each screen plays its own sound (default); true = only the leader. */
    public boolean groupLeaderOnly() { return "leader".equals(Cfg.p(this).getString(PREF_GROUP_AUDIO, "each")); }
    public void setGroupAudio(boolean leaderOnly) {
        Cfg.p(this).edit().putString(PREF_GROUP_AUDIO, leaderOnly ? "leader" : "each").apply();
        if (isSyncGroup()) armGroup();   // push the new audio mode to the current followers
    }
    /** Arm/disarm a synced group over the CURRENT selection. */
    // Sync is HTTP between the screens, so they need a shared network. At home that's the house Wi-Fi.
    // Anywhere else (the car, a waiting room) there is none, so the group used to refuse with "needs the
    // same Wi-Fi" even though the phone can make one: ask for the Wi-Fi tier (this phone hosts, the
    // device-owner TV joins silently) and arm once at least two members are reachable.
    private boolean syncPending = false;
    private long syncPendingSince = 0;
    private static final long SYNC_LINK_TIMEOUT_MS = 45000;
    /** True while a group is waiting for its screens to come up on a shared network. */
    public boolean isSyncPending() { return syncPending && groupOrder().size() >= 2; }
    // A screen's reported address is not proof we can reach it: a tablet on the house Wi-Fi still
    // reports its house address over Bluetooth while this phone is out on mobile data. So count only
    // screens that actually answered an HTTP probe at their CURRENT address.
    private final java.util.Map<String, String> probedOk = new java.util.concurrent.ConcurrentHashMap<>();   // id -> ip that answered
    private volatile boolean probing = false;
    private void probeMembers() {
        if (probing) return;
        final java.util.Map<String, String> ips = new java.util.HashMap<>();
        for (String id : groupOrder()) { String ip = deviceIp(id); if (ip != null && !ip.isEmpty()) ips.put(id, ip); }
        final String self = selfPlay() ? selfId() : null;
        probing = true;
        new Thread(() -> { try {
            for (java.util.Map.Entry<String, String> e : ips.entrySet()) {
                if (e.getKey().equals(self)) { probedOk.put(e.getKey(), e.getValue()); continue; }   // this phone: local
                boolean ok = false;
                try {
                    java.net.HttpURLConnection cn = (java.net.HttpURLConnection) new java.net.URL("http://" + e.getValue() + ":8090/clock").openConnection();
                    cn.setConnectTimeout(1500); cn.setReadTimeout(1500);
                    app.anancy.Pairing.noteHost(this, e.getValue(), e.getKey());
                    app.anancy.Pairing.sign(this, cn);
                    ok = app.anancy.Pairing.fetch(this, cn) != null; cn.disconnect();
                } catch (Exception ignored) {}
                if (ok) probedOk.put(e.getKey(), e.getValue());
                else { if (probedOk.remove(e.getKey()) != null || !syncLinkToasted) Diag.log(TAG, "sync probe: " + e.getKey() + " @ " + e.getValue() + " not answering"); }
            }
        } finally { probing = false; } }, "sync-probe").start();
    }
    private int reachableMembers() {
        int n = 0;
        for (String id : groupOrder()) { String ip = deviceIp(id); if (ip != null && ip.equals(probedOk.get(id))) n++; }
        return n;
    }
    private final Runnable syncWait = new Runnable() { public void run() {
        if (!syncPending) return;
        if (groupOrder().size() < 2) { syncPending = false; releaseWifi("sync"); return; }
        if (reachableMembers() >= 2) {
            syncPending = false;
            Diag.log(TAG, "sync group: screens linked after " + (android.os.SystemClock.elapsedRealtime() - syncPendingSince) + " ms, arming");
            Cfg.p(RemoteService.this).edit().putString(PREF_SYNCGROUP, "1").apply();
            java.util.Map<String, String> ips = new java.util.LinkedHashMap<>();
            for (String id : groupOrder()) ips.put(id, deviceIp(id));
            Diag.log(TAG, "sync group: leader " + syncLeaderId() + ", ips " + ips + ", hosts " + hostIds + ", self " + (selfPlay() ? selfId() : "-"));
            armGroup(); aimLinks();
            if (android.os.SystemClock.elapsedRealtime() - syncPendingSince > 2500) toastMain("Screens synced");
            return;
        }
        if (android.os.SystemClock.elapsedRealtime() - syncPendingSince > SYNC_LINK_TIMEOUT_MS) {
            syncPending = false; releaseWifi("sync");
            Diag.warn(TAG, "sync group: screens never became reachable over Wi-Fi");
            toastMain("Couldn't link the screens over Wi-Fi. Put them on the same Wi-Fi and try again.");
            return;
        }
        if (!syncLinkToasted && android.os.SystemClock.elapsedRealtime() - syncPendingSince > 2500) {
            syncLinkToasted = true;
            Diag.log(TAG, "sync group: only " + reachableMembers() + " screen(s) answer over HTTP, raising Wi-Fi");
            toastMain("Linking the screens over Wi-Fi…");
        }
        probeMembers();
        main.postDelayed(this, 1000);
    }};
    private boolean syncLinkToasted = false;
    private void toastMain(final String msg) {
        main.post(() -> { try { android.widget.Toast.makeText(RemoteService.this, msg, android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) {} });
    }
    public boolean setSyncGroup(boolean on) {
        Diag.log(TAG, "sync group: " + (on ? "ON" : "OFF") + " requested, members " + groupOrder() + ", carProjecting=" + WifiRadio.carProjecting(this));
        if (!on) {
            syncPending = false; main.removeCallbacks(syncWait);
            releaseWifi("sync");
            if (selfPlay()) Cfg.put(this, "anancy_host", "");   // leaving the group: this device goes back to its own library
            java.util.HashSet<String> known = new java.util.HashSet<>(seenTablets.keySet()); known.addAll(deviceIps.keySet());
            for (String id : known) httpArm(deviceIp(id), "/sync?on=0");   // everyone stops following
            Cfg.p(this).edit().putString(PREF_SYNCGROUP, "").apply();
            aimLinks();
            return true;
        }
        if (groupOrder().size() < 2) return false;                         // need a leader + at least one follower
        acquireWifi("sync");   // hold the link for as long as the group lasts (a no-op on house Wi-Fi)
        // Arming is HTTP-only (there is no "sync" verb over BLE). Wait until the leader AND a follower
        // answer over HTTP (usually under a second at home); this phone's own address alone used to
        // pass, and the group then did nothing.
        probedOk.clear(); probeMembers();
        syncPending = true; syncLinkToasted = false; syncPendingSince = android.os.SystemClock.elapsedRealtime();
        main.removeCallbacks(syncWait); main.postDelayed(syncWait, 800);
        return true;
    }
    /** (Re)arm the current group: leader plays, non-member screens stop, follower members mirror the leader. */
    private void armGroup() {
        String leader = syncLeaderId(); if (leader == null) return;
        String leaderIp = deviceIp(leader); if (leaderIp == null || leaderIp.isEmpty()) return;
        java.util.Set<String> members = new java.util.HashSet<>(groupOrder());
        java.util.HashSet<String> known = new java.util.HashSet<>(seenTablets.keySet()); known.addAll(deviceIps.keySet());
        for (String id : known) if (!members.contains(id)) httpArm(deviceIp(id), "/sync?on=0");   // dropped screens leave the group
        httpArm(leaderIp, "/sync?on=0");                                   // the leader plays normally (audio source)
        int mute = groupLeaderOnly() ? 1 : 0;
        // Only the leader's MEDIA key: enough to stream from it, never to drive it or see its camera.
        String full = app.anancy.Pairing.controlKey(this, leader);
        String leaderKey = full == null ? null : app.anancy.net.PairCrypto.mediaKey(full);
        for (String id : groupOrder()) {
            if (id.equals(leader)) continue;
            String fip = deviceIp(id);
            if (fip == null || fip.isEmpty()) continue;
            // A follower streams from the leader, so it needs the leader's key: sealed with the
            // follower's OWN key, so it is only ever readable by that follower.
            String sealed = leaderKey == null ? null : app.anancy.Pairing.sealFor(this, id, leaderKey);
            String grant = sealed == null ? "" : "&hostid=" + leader + "&hostkey=" + sealed;
            httpArm(fip, "/sync?on=1&host=" + leaderIp + "&mute=" + mute + grant);
        }
    }
    /** Fire-and-forget GET to a tablet's ControlServer (used to arm/disarm sync). */
    private void httpArm(final String ip, final String path) {
        if (ip == null || ip.isEmpty()) return;
        new Thread(() -> { try {
            java.net.HttpURLConnection cn = (java.net.HttpURLConnection) new java.net.URL("http://" + ip + ":8090" + path).openConnection();
            cn.setConnectTimeout(2500); cn.setReadTimeout(2500);
            app.anancy.Pairing.sign(this, cn);
            if (app.anancy.Pairing.fetch(this, cn) == null)
                Diag.warn(TAG, "arm " + path.replaceAll("(hostkey|key)=[^&]*", "$1=…") + " @ " + ip + " -> no genuine answer");
        } catch (Exception ignored) {} }).start();
    }
    /** Who commands actually go to: the leader alone when a group is armed, else the raw selection. */
    private java.util.Set<String> effectiveTargets() {
        if (isSyncGroup()) { String L = syncLeaderId(); if (L != null) { java.util.HashSet<String> s = new java.util.HashSet<>(); s.add(L); return s; } }
        return targets();
    }
    /** id -> friendly name for every player heard on BLE or mDNS this session. */
    public java.util.Map<String, String> tablets() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (!local && seenTablets.containsKey(selfId())) m.put(selfId(), seenTablets.get(selfId()));   // this device is always first
        m.putAll(seenTablets);
        return m;
    }
    /** The SELECTED players to control (checkbox multi-select). Empty = ALL heard. */
    public java.util.Set<String> targets() {
        String t = Cfg.p(this).getString(PREF_TARGET, "");
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        if (t != null && !t.isEmpty() && !ALL.equals(t)) for (String id : t.split(",")) if (!id.isEmpty()) s.add(id);
        return s;
    }
    public boolean isAll() { return targets().isEmpty(); }
    /** Persist the selection, then re-aim the links. Empty/full set = ALL. */
    public void setTargets(java.util.Set<String> ids) {
        // Treat "all of them selected" as ALL (empty), so adding a new screen later is auto-included.
        java.util.Set<String> sel = (ids == null) ? new java.util.HashSet<>() : new java.util.HashSet<>(ids);
        if (!seenTablets.isEmpty() && sel.containsAll(seenTablets.keySet())) sel.clear();
        Cfg.p(this).edit().putString(PREF_TARGET, sel.isEmpty() ? "" : android.text.TextUtils.join(",", sel)).apply();
        aimLinks();
    }
    /** Back-compat: set a single target (or ALL). */
    public void setTarget(String id) {
        if (id == null || ALL.equals(id)) setTargets(java.util.Collections.<String>emptySet());
        else { java.util.HashSet<String> s = new java.util.HashSet<>(); s.add(id); setTargets(s); }
    }
    /** Re-aim BLE (drives the selected set) + Wi-Fi (polls the representative + reaches Wi-Fi-only screens). */
    private void aimLinks() {
        java.util.Set<String> sel = effectiveTargets();   // a synced group aims everything at the leader
        String rep = sel.isEmpty() ? null : sel.iterator().next();   // whose state the UI shows
        if (sel.size() > 1 && selfPlay()) for (String id : sel) if (!id.equals(selfId())) { rep = id; break; }   // prefer a real link
        if (ble != null) { ble.setDrive(sel); ble.setStateId(rep); ble.start(); }
        if (wifi != null) wifi.setTarget(rep);
        if (wifi == null) startWifi(null, 0);   // Wi-Fi is needed for Wi-Fi-only screens (a TV) + representative state
        publishWatchTarget();
    }
    /** Friendly name of the representative player, or a count when several are selected. */
    public String connectedTablet() {
        if (isSyncGroup()) { String n = syncLeaderName(); return n != null ? "Synced · " + n : "Synced"; }
        if (showingSelf()) return app.anancy.Identity.name(this);
        java.util.Set<String> sel = targets();
        if (!sel.isEmpty() && sel.size() > 1) return sel.size() + " screens";
        String id = bleUp() ? ble.connectedId() : (wifi != null && wifi.isConnected() ? wifi.connectedId() : null);
        if (id == null) return null;
        String n = seenTablets.get(id);
        return n != null && !n.isEmpty() ? n : id;
    }
    // --- pairing --------------------------------------------------------------------------------------
    private final Map<String, String> unpaired = new ConcurrentHashMap<>();   // nearby TVs we may NOT talk to
    private final Map<String, String> rejectedIds = new ConcurrentHashMap<>();   // id -> the key that TV refused
    /** Screens nearby that this phone hasn't paired with (id -> name), for the "Pair" offer. */
    public Map<String, String> unpairedNearby() {
        Map<String, String> m = new java.util.LinkedHashMap<>(unpaired);
        for (String id : rejectedIds.keySet()) { String n = seenTablets.get(id); m.put(id, n != null ? n : id); }
        return m;
    }
    private void noteUnpaired(String id, String name) {
        if (id == null || id.isEmpty() || app.anancy.Pairing.isPaired(this, id)) return;
        unpaired.put(id, name == null || name.isEmpty() ? id : name);
    }
    /** A TV was just paired or forgotten: reconnect accordingly and give the watch the keys. */
    private void onPairingChanged() {
        main.post(() -> {
            for (String id : new java.util.ArrayList<>(unpaired.keySet())) if (app.anancy.Pairing.isPaired(this, id)) unpaired.remove(id);
            for (String id : new java.util.ArrayList<>(rejectedIds.keySet()))   // re-scanned: a new key, try again
                if (!String.valueOf(app.anancy.Pairing.controlKey(this, id)).equals(rejectedIds.get(id))) rejectedIds.remove(id);
            for (String id : new java.util.ArrayList<>(seenTablets.keySet()))
                if (!id.equals(selfId()) && !app.anancy.Pairing.isPaired(this, id)) seenTablets.remove(id);
            if (!local) {
                // Bluetooth keeps its links: the running scan connects a newly paired TV by itself, and
                // a forgotten one is dropped here. (Restarting it all made every live link re-pair.)
                if (ble != null) { ble.dropUnpaired(); ble.start(); }
                try { if (wifi != null) wifi.stop(); } catch (Exception ignored) {}   // re-discover with the new keys
                wifi = null;
                startWifi(null, 0);
            }
            app.anancy.net.WatchLink.publishKeys(this);
        });
    }

    private void noteSeen(String id, String name) {
        if (id == null || id.isEmpty()) return;
        if (!local && id.equals(selfId()) && !selfPlay()) return;   // our own player only counts when the box is ticked
        boolean isNew = seenTablets.put(id, name == null || name.isEmpty() ? id : name) == null;
        if (isNew) publishWatchTarget();   // "all screens" just grew: the watch may now need the relay
    }
    /** Aim the paired watch at whatever this remote drives (see WatchLink). */
    private void publishWatchTarget() {
        if (local) return;
        java.util.Set<String> sel = targets();
        int screens = sel.isEmpty() ? seenTablets.size() : sel.size();
        if (showingSelf()) sel = java.util.Collections.singleton(selfId());   // only screen there is: the watch drives this device
        app.anancy.net.WatchLink.publishRemote(this, sel, isSyncGroup() || screens > 1);
    }

    // --- "This phone as playback": this remote is also one of the screens --------------------------------
    // The player here is reached over loopback, the same way the TV's own admin Remote does it. It draws
    // in this device's card (SelfPlayer); "make large" takes it fullscreen, and the watch is what lets
    // you drive it from there.
    private static final String PREF_SELF_PLAY = "anancy_self_play";
    private WifiClient selfLink;
    public String selfId() { return app.anancy.Identity.id(this); }
    public boolean selfPlay() { return !local && Cfg.p(this).getBoolean(PREF_SELF_PLAY, false); }
    /** True when this device is the only screen selected, so the UI should show its state. */
    public boolean showingSelf() {
        if (!selfPlay()) return false;
        java.util.Set<String> sel = targets();
        if (!sel.isEmpty()) return sel.size() == 1 && sel.contains(selfId());
        return seenTablets.size() == 1 && seenTablets.containsKey(selfId());
    }
    public void setSelfPlay(boolean on) {
        Cfg.p(this).edit().putBoolean(PREF_SELF_PLAY, on).apply();
        applySelfPlay();
        if (on) {
            app.anancy.player.SelfPlayer.start(this);
            if (isSyncGroup()) main.postDelayed(this::armGroup, 1500);   // once its control server is listening
        } else {
            java.util.Set<String> left = new java.util.LinkedHashSet<>(targets());
            left.remove(selfId());
            app.anancy.player.SelfPlayer.stop();
            setTargets(left);
            if (!"".equals(Cfg.p(this).getString(PREF_SYNCGROUP, "")) && groupOrder().size() < 2) setSyncGroup(false);
            return;
        }
        aimLinks();
    }
    private void applySelfPlay() {
        if (selfPlay()) {
            noteSeen(selfId(), app.anancy.Identity.name(this));
            if (selfLink != null) return;
            selfLink = new WifiClient(this, new WifiClient.Listener() {
                public void onStatus(String s) { if (showingSelf()) { lastStatus = s; if (ui != null) ui.onStatus(s); } }
                public void onState(String json) { if (showingSelf()) onIncoming(json, "local"); }
                public void onSeen(String id, String name) {}
            });
            selfLink.setFixedHost("127.0.0.1", 8090);
            selfLink.start();
        } else {
            if (selfLink != null) { selfLink.stop(); selfLink = null; }
            seenTablets.remove(selfId());
        }
    }
    /** This device's address on the network, which the other screens in a synced group use to reach it. */
    private String selfIp() {
        try {
            String ip = new JSONObject(app.anancy.player.PlayerController.get().stateJson()).optString("ip", "");
            return ip.isEmpty() ? null : ip;
        } catch (Exception e) { return null; }
    }
    // Never starts the player. Android Auto binds on every car connection and its background queries come
    // through send(), so starting here played the library out loud over the car's music with the app closed.
    // Only ticking the box or the card coming on screen starts it.
    private void sendToSelf(final String cmd) {
        // Network commands are for OTHER screens: this phone's own player was being told to join the
        // hotspot this phone is hosting, which raised Android's "Allow suggested Wi-Fi networks?" prompt.
        if (cmd == null || cmd.startsWith("wifi:") || cmd.startsWith("conn:")) return;
        main.post(() -> {
            if (app.anancy.player.SelfPlayer.running() && selfLink != null) selfLink.send(cmd);
        });
    }

    private void startLinks() {
        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) { lastStatus = s; if (ui != null) ui.onStatus(s); }
            public void onState(String json) { onIncoming(json, "BLE"); }
            public void onStill(byte[] jpeg) { setLatestStill(jpeg); }   // reassembled BLE still -> shared for views/AA
            public void onSeen(String id, String name) { noteSeen(id, name); }
            public void onDeviceState(String id, String ip, boolean isHost) {
                if (id == null) return;
                if (ip != null && !ip.isEmpty()) { deviceIps.put(id, ip); app.anancy.Pairing.noteHost(RemoteService.this, ip, id); }
                if (isHost) hostIds.add(id); else hostIds.remove(id);
            }
            public void onUnpaired(String id, String name) { noteUnpaired(id, name); }
            public void onPairingRejected(String id) {
                String k = app.anancy.Pairing.controlKey(RemoteService.this, id);
                if (k != null) rejectedIds.put(id, k);
                toastMain("A screen no longer accepts this phone. Scan its pairing QR again.");
            }
        });
        java.util.Set<String> sel = targets();
        ble.setDrive(sel);
        ble.setStateId(sel.isEmpty() ? null : sel.iterator().next());
        ble.start();
        if (!"ble".equals(connMode)) applyConnMode();   // hotspot coordination for the chosen tier
        if (wifi == null) startWifi(null, 0);            // Wi-Fi always up: Wi-Fi-only screens (a TV) + representative state
        WearPusher.push(this);   // phone only: offer the embedded watch build (no-op if unchanged)
        app.anancy.net.WatchLink.publishKeys(this);   // the watch pairs through this phone
        applySelfPlay();
        publishWatchTarget();
        // Wi-Fi/mDNS fallback if BLE hasn't connected shortly — only at a Wi-Fi connection level
        main.postDelayed(() -> { if (ble != null && !ble.isConnected() && !"ble".equals(connMode)) startWifi(null, 0); }, 7000);
    }

    private void startWifi(String fixedHost, int fixedPort) {
        if (wifi != null) return;
        wifi = new WifiClient(this, new WifiClient.Listener() {
            public void onStatus(String s) { if (!bleUp()) { lastStatus = s; if (ui != null) ui.onStatus(s); } }
            public void onState(String json) {
                if (typeOf(json) != null) { onIncoming(json, local ? "local" : "Wi-Fi"); return; }
                if (!bleUp()) onIncoming(json, local ? "local" : "Wi-Fi");
            }
            public void onSeen(String id, String name) { noteSeen(id, name); }
            public void onUnpaired(String id, String name) { noteUnpaired(id, name); }
        });
        if (fixedHost != null) wifi.setFixedHost(fixedHost, fixedPort);
        else { java.util.Set<String> sel = targets(); wifi.setTarget(sel.isEmpty() ? null : sel.iterator().next()); }   // poll the representative
        wifi.start();
    }

    private boolean bleUp() { return ble != null && ble.isConnected(); }

    private final Map<String, Long> lastToggle = new ConcurrentHashMap<>();
    public void send(String cmd) {
        if (CommandRouter.isQueryCmd(cmd)) {   // a query has ONE answer — ask the representative only
            if (showingSelf() && selfLink != null) selfLink.send(cmd);
            else if (bleUp()) ble.sendState(cmd);
            else if (wifi != null) wifi.send(cmd);
            return;
        }
        if (CommandRouter.isToggleCmd(cmd)) {   // debounce spam: rapid toggles could flip screens into different states
            long now = android.os.SystemClock.elapsedRealtime();
            Long t = lastToggle.get(cmd);
            if (t != null && now - t < 350) return;
            lastToggle.put(cmd, now);
        }
        // In a synced group, PLAYBACK (play/pause/next/seek…) drives the leader alone and the followers mirror
        // it. Everything else is a PER-SCREEN action (power/light/sleep/toddler/volume/mute) and must hit
        // EVERY member — otherwise e.g. Power naps only the leader and leaves the followers' screens on.
        // Volume/mute in leader-only audio mode still go to the leader alone.
        if (isSyncGroup() && !CommandRouter.isPlaybackCmd(cmd)) {
            String leader = syncLeaderId();
            boolean leaderOnly = CommandRouter.isVolCmd(cmd) && groupLeaderOnly();
            java.util.Set<String> tgt = new java.util.HashSet<>();
            if (leaderOnly) { if (leader != null) tgt.add(leader); } else tgt.addAll(groupOrder());
            java.util.Set<String> ble2 = ble != null ? ble.connectedIds() : new java.util.HashSet<String>();
            if (selfPlay() && tgt.contains(selfId())) sendToSelf(cmd);
            if (ble != null) ble.sendToIds(tgt, cmd);
            if (wifi != null) { java.util.Set<String> w = new java.util.HashSet<>(tgt); w.removeAll(ble2); if (!local) w.remove(selfId()); if (!w.isEmpty()) wifi.sendTo(w, cmd); }
            return;
        }
        // A control command goes to EVERY selected screen: BLE to the tablets it holds links to, Wi-Fi to
        // the rest (e.g. a TV that doesn't advertise BLE). Empty selection = ALL heard screens. A synced
        // group collapses this to the leader alone (the followers mirror it themselves).
        java.util.Set<String> sel = effectiveTargets();
        java.util.Set<String> bleIds = ble != null ? ble.connectedIds() : new java.util.HashSet<String>();
        if (selfPlay() && (sel.isEmpty() || sel.contains(selfId()))) sendToSelf(cmd);
        if (ble != null) ble.send(cmd);
        if (wifi != null) {
            java.util.Set<String> w = sel.isEmpty() ? new java.util.HashSet<>(wifi.hostIds()) : new java.util.HashSet<>(sel);
            w.removeAll(bleIds);
            if (!local) w.remove(selfId());   // already delivered over loopback
            if (!w.isEmpty()) wifi.sendTo(w, cmd);
        }
    }
    // (command classification predicates now live in CommandRouter — pure + unit-tested)
    public String state() { return lastState; }
    public boolean isLocal() { return local; }

    // Latest reduced-res camera STILL (BLE-reassembled or Wi-Fi), shared with the AA dash + views.
    private static volatile byte[] sLatestStill = null;
    public static byte[] latestStill() { return sLatestStill; }
    public static void setLatestStill(byte[] jpeg) { sLatestStill = jpeg; }
    /** For Wi-Fi file import: a ONE-SHOT mDNS discovery of the player's host:port that does NOT
     *  leave a polling transport running. Blocking, off-main; up to ~5s. Returns null if not found. */
    public String playerHttpHost() {
        WifiClient tmp = new WifiClient(this, new WifiClient.Listener() {
            public void onStatus(String s) {} public void onState(String s) {}
            public void onSeen(String id, String name) { noteSeen(id, name); }
        });
        { java.util.Set<String> sel = targets(); tmp.setTarget(sel.isEmpty() ? null : sel.iterator().next()); }   // upload to the representative
        tmp.start();
        try {
            for (int i = 0; i < 50; i++) {
                String h = tmp.hostPort();
                if (h != null) return h;
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            return tmp.hostPort();
        } finally { tmp.stop(); }   // don't leave it polling
    }
    public String transport() { if (showingSelf()) return "local"; return bleUp() ? "BLE" : (wifi != null && wifi.isConnected() ? (local ? "local" : "Wi-Fi") : transport); }
    public boolean bleConnected() { return bleUp(); }
    public void setListener(Listener l) { ui = l; if (l != null) { l.onStatus(lastStatus); l.onState(lastState); } }

    @Override public void onDestroy() {
        main.removeCallbacks(idle);
        try { if (ble != null) ble.stop(); } catch (Exception ignored) {}
        try { if (wifi != null) wifi.stop(); } catch (Exception ignored) {}
        try { if (selfLink != null) selfLink.stop(); } catch (Exception ignored) {}
        if (INSTANCE == this) INSTANCE = null;
        if (app.anancy.Pairing.onPeersChanged != null) app.anancy.Pairing.onPeersChanged = null;
        super.onDestroy();
    }

    private Notification note() {
        String ch = "anancy_remote";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "BackSeeTV remote", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("BackSeeTV remote").setContentText("linked to the TV")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }
}
