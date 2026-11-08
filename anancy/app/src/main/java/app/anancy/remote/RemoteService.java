package app.anancy.remote;

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
    public void requestConfig() { cfgAccum = null; requestTyped("cfg", "config"); }
    public String configJson() { return typed.get("config"); }
    /** Write one tablet setting from a remote (mirrors the on-tablet Settings screen). */
    public void setConfig(String key, String val) { send("cfg:" + key + "=" + val); }
    public String libraryJson() { return typed.get("library"); }
    public String episodesJson() { return typed.get("episodes"); }
    public String connJson() { return typed.get("conn"); }
    public java.util.List<String> playlistNames() { return playlistNames; }
    public java.util.List<String> userPlaylistNames() { return userPlaylistNames; }

    private JSONObject cfgAccum;   // config arrives in pages over BLE; publish only once it is whole

    /** Merge a config page. The UI must never see a half-payload: it would render every setting at
        its default behind a padlock, which is indistinguishable from "this TV can't do that". */
    private void mergeConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("from", 0) > 0 && cfgAccum != null) {
                JSONObject av = cfgAccum.optJSONObject("values"), nv = o.optJSONObject("values");
                if (av != null && nv != null)
                    for (java.util.Iterator<String> it = nv.keys(); it.hasNext(); ) { String k = it.next(); av.put(k, nv.get(k)); }
            } else cfgAccum = o;        // first page carries device_owner + pin_set
            int more = o.optInt("more", 0);
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
            playlistTotal = total;
            if (from == 0) playlistNames = page;                  // first page replaces
            else {                                                 // later pages append (BLE sends it in slices)
                java.util.List<String> merged = new java.util.ArrayList<>(playlistNames);
                merged.addAll(page);
                playlistNames = merged;
            }
            if (!usr.isEmpty() || from == 0) userPlaylistNames = usr;
            if (playlistNames.size() < total) send("list:" + playlistNames.size());   // pull the rest, unprompted
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
                if (!ip.isEmpty()) deviceIps.put(id, ip);
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
                try { ok = WifiJoiner.join(this, c); } catch (Exception e) { Log.w(TAG, "join tv hotspot: " + e); }
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
            if (!HotspotService.running()) { try { HotspotService.start(this); } catch (Exception e) { Log.w(TAG, "hotspot: " + e); } }
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
            if (escalated) Log.i(TAG, "auto: Android Auto projecting on a single-radio phone - rescinding Wi-Fi, back to Bluetooth");
            autoSince = 0; dropEscalation(); return;
        }
        // RAISE only on demand -- never spin up a hotspot nobody asked for. KEEP it afterwards when
        // holding it is free (no Wi-Fi to lose, or a spare radio), so in the car the first check-in
        // of the drive pays the few seconds and every one after that is instant.
        // ...and keep it only while it is actually CARRYING. Holding on "escalated" alone would leave
        // a hotspot up forever on any drive where the TV never managed to join it.
        boolean want = !wifiNeeds.isEmpty()
                || (escalated && WifiRadio.freeToHost(this) && wifi != null && wifi.isConnected());
        if (!want) { autoSince = 0; dropEscalation(); return; }
        if (autoSince == 0) autoSince = android.os.SystemClock.elapsedRealtime();

        if (wifi != null && wifi.isConnected()) { escalated = true; return; }   // already reachable: home Wi-Fi, nothing to do
        // Don't start raising radios until mDNS has had a fair go at finding the TV on this network.
        if (android.os.SystemClock.elapsedRealtime() - autoSince < 5000) { poke(1500); return; }

        // Prefer WE host. The TV is device owner, so it joins silently (addNetwork/enableNetwork) --
        // zero taps, and the random local-only SSID stops mattering because the only joiner needs no
        // approval. Joining the TV's hotspot the other way costs one system dialog per SSID, and it
        // re-randomises on every tablet reboot, which is exactly what made it useless in the car.
        if (bleUp() && WifiRadio.freeToHost(this)) {
            if (!HotspotService.running()) {
                try { HotspotService.start(this, false); } catch (Exception e) { Log.w(TAG, "auto host: " + e); }
            }
            String s = HotspotService.currentSsid(), p = HotspotService.currentPass();
            if (s != null && p != null) {
                String key = s + "|" + p;
                if (!key.equals(pushedKey)) {
                    pushedKey = key; send("wifi:" + s + "\t" + p);
                    Log.i(TAG, "auto: hosting for the TV, creds pushed over BLE (" + s + ")");
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
                try { ok = WifiJoiner.joinDirect(this, c, null, null); } catch (Exception e) { Log.w(TAG, "auto join: " + e); }
                joinedKey = ok ? key : null;
                if (ok) { escalated = true; Log.i(TAG, "auto: joining the TV hotspot " + hsSsid); }
            }
        }
    }

    /** Nothing needs Wi-Fi any more and it is not free to hold -- give the phone its network back. */
    private void dropEscalation() {
        if (!escalated) return;
        escalated = false; joinedKey = null;
        if (HotspotService.running()) { try { HotspotService.stop(this); } catch (Exception ignored) {} pushedKey = null; }
        try { WifiJoiner.releaseDirect(this); } catch (Exception ignored) {}
        Log.i(TAG, "auto: Wi-Fi released, Bluetooth only");
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
                    Log.i(TAG, "tv hotspot not bound - auto-rejoin " + s + " (try " + rejoinTries + ")");
                    WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = s; c.type = "WPA"; c.pass = pw;
                    try { WifiJoiner.joinDirect(RemoteService.this, c, null, null); } catch (Exception e) { Log.w(TAG, "auto-rejoin: " + e); }
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
        startForeground(72, note());
        // One-time move to the "auto" default. The old default was a bare "ble" that never escalated,
        // so every existing install would sit on Bluetooth forever and never see the Wi-Fi tier even
        // when a feature needed it. A level picked explicitly AFTER this migration is left alone.
        if (!"1".equals(Cfg.getStr(this, "anancy_conn_auto", null))) {
            Cfg.put(this, "anancy_conn_auto", "1");
            String was = Cfg.getStr(this, "anancy_conn", null);
            if (was == null || "ble".equals(was) || "tv_hotspot".equals(was)) Cfg.put(this, "anancy_conn", "auto");
        }
        connMode = Cfg.getStr(this, "anancy_conn", "auto");
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
        Log.i(TAG, "remote link idle -> stopping");
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
    private java.util.List<String> groupOrder() { return new java.util.ArrayList<>(isAll() ? seenTablets.keySet() : targets()); }
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
    public boolean setSyncGroup(boolean on) {
        if (!on) {
            java.util.HashSet<String> known = new java.util.HashSet<>(seenTablets.keySet()); known.addAll(deviceIps.keySet());
            for (String id : known) httpArm(deviceIp(id), "/sync?on=0");   // everyone stops following
            Cfg.p(this).edit().putString(PREF_SYNCGROUP, "").apply();
            aimLinks();
            return true;
        }
        if (groupOrder().size() < 2) return false;                         // need a leader + at least one follower
        // Arming is HTTP-only (there is no "sync" verb over BLE), so on a Bluetooth-only link the group
        // silently did nothing while send() still routed playback away from the followers. Refuse instead.
        boolean anyReachable = false;
        for (String id : groupOrder()) if (deviceIp(id) != null) { anyReachable = true; break; }
        if (!anyReachable) { Log.w(TAG, "sync group refused: no screen is reachable over Wi-Fi"); return false; }
        Cfg.p(this).edit().putString(PREF_SYNCGROUP, "1").apply();         // set first so syncLeaderId() derives
        armGroup();
        aimLinks();
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
        for (String id : groupOrder()) {
            if (id.equals(leader)) continue;
            String fip = deviceIp(id);
            if (fip != null && !fip.isEmpty()) httpArm(fip, "/sync?on=1&host=" + leaderIp + "&mute=" + mute);
        }
    }
    /** Fire-and-forget GET to a tablet's ControlServer (used to arm/disarm sync). */
    private void httpArm(final String ip, final String path) {
        if (ip == null || ip.isEmpty()) return;
        new Thread(() -> { try {
            java.net.HttpURLConnection cn = (java.net.HttpURLConnection) new java.net.URL("http://" + ip + ":8090" + path).openConnection();
            cn.setConnectTimeout(2500); cn.setReadTimeout(2500); cn.getInputStream().close();
        } catch (Exception ignored) {} }).start();
    }
    /** Who commands actually go to: the leader alone when a group is armed, else the raw selection. */
    private java.util.Set<String> effectiveTargets() {
        if (isSyncGroup()) { String L = syncLeaderId(); if (L != null) { java.util.HashSet<String> s = new java.util.HashSet<>(); s.add(L); return s; } }
        return targets();
    }
    /** id -> friendly name for every player heard on BLE or mDNS this session. */
    public java.util.Map<String, String> tablets() { return new java.util.LinkedHashMap<>(seenTablets); }
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
        if (ble != null) { ble.setDrive(sel); ble.setStateId(rep); ble.start(); }
        if (wifi != null) wifi.setTarget(rep);
        if (wifi == null) startWifi(null, 0);   // Wi-Fi is needed for Wi-Fi-only screens (a TV) + representative state
        publishWatchTarget();
    }
    /** Friendly name of the representative player, or a count when several are selected. */
    public String connectedTablet() {
        if (isSyncGroup()) { String n = syncLeaderName(); return n != null ? "Synced · " + n : "Synced"; }
        java.util.Set<String> sel = targets();
        if (!sel.isEmpty() && sel.size() > 1) return sel.size() + " screens";
        String id = bleUp() ? ble.connectedId() : (wifi != null && wifi.isConnected() ? wifi.connectedId() : null);
        if (id == null) return null;
        String n = seenTablets.get(id);
        return n != null && !n.isEmpty() ? n : id;
    }
    private void noteSeen(String id, String name) {
        if (id == null || id.isEmpty()) return;
        boolean isNew = seenTablets.put(id, name == null || name.isEmpty() ? id : name) == null;
        if (isNew) publishWatchTarget();   // "all screens" just grew: the watch may now need the relay
    }
    /** Aim the paired watch at whatever this remote drives (see WatchLink). */
    private void publishWatchTarget() {
        if (local) return;
        java.util.Set<String> sel = targets();
        int screens = sel.isEmpty() ? seenTablets.size() : sel.size();
        app.anancy.net.WatchLink.publishRemote(this, sel, isSyncGroup() || screens > 1);
    }

    private void startLinks() {
        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) { lastStatus = s; if (ui != null) ui.onStatus(s); }
            public void onState(String json) { onIncoming(json, "BLE"); }
            public void onStill(byte[] jpeg) { setLatestStill(jpeg); }   // reassembled BLE still -> shared for views/AA
            public void onSeen(String id, String name) { noteSeen(id, name); }
            public void onDeviceState(String id, String ip, boolean isHost) {
                if (id == null) return;
                if (ip != null && !ip.isEmpty()) deviceIps.put(id, ip);
                if (isHost) hostIds.add(id); else hostIds.remove(id);
            }
        });
        java.util.Set<String> sel = targets();
        ble.setDrive(sel);
        ble.setStateId(sel.isEmpty() ? null : sel.iterator().next());
        ble.start();
        if (!"ble".equals(connMode)) applyConnMode();   // hotspot coordination for the chosen tier
        if (wifi == null) startWifi(null, 0);            // Wi-Fi always up: Wi-Fi-only screens (a TV) + representative state
        WearPusher.push(this);   // phone only: offer the embedded watch build (no-op if unchanged)
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
        });
        if (fixedHost != null) wifi.setFixedHost(fixedHost, fixedPort);
        else { java.util.Set<String> sel = targets(); wifi.setTarget(sel.isEmpty() ? null : sel.iterator().next()); }   // poll the representative
        wifi.start();
    }

    private boolean bleUp() { return ble != null && ble.isConnected(); }

    private final Map<String, Long> lastToggle = new ConcurrentHashMap<>();
    public void send(String cmd) {
        if (CommandRouter.isQueryCmd(cmd)) {   // a query has ONE answer — ask the representative only
            if (bleUp()) ble.sendState(cmd);
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
            if (ble != null) ble.sendToIds(tgt, cmd);
            if (wifi != null) { java.util.Set<String> w = new java.util.HashSet<>(tgt); w.removeAll(ble2); if (!w.isEmpty()) wifi.sendTo(w, cmd); }
            return;
        }
        // A control command goes to EVERY selected screen: BLE to the tablets it holds links to, Wi-Fi to
        // the rest (e.g. a TV that doesn't advertise BLE). Empty selection = ALL heard screens. A synced
        // group collapses this to the leader alone (the followers mirror it themselves).
        java.util.Set<String> sel = effectiveTargets();
        java.util.Set<String> bleIds = ble != null ? ble.connectedIds() : new java.util.HashSet<String>();
        if (ble != null) ble.send(cmd);
        if (wifi != null) {
            java.util.Set<String> w = sel.isEmpty() ? new java.util.HashSet<>(wifi.hostIds()) : new java.util.HashSet<>(sel);
            w.removeAll(bleIds);
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
    public String transport() { return bleUp() ? "BLE" : (wifi != null && wifi.isConnected() ? (local ? "local" : "Wi-Fi") : transport); }
    public boolean bleConnected() { return bleUp(); }
    public void setListener(Listener l) { ui = l; if (l != null) { l.onStatus(lastStatus); l.onState(lastState); } }

    @Override public void onDestroy() {
        main.removeCallbacks(idle);
        try { if (ble != null) ble.stop(); } catch (Exception ignored) {}
        try { if (wifi != null) wifi.stop(); } catch (Exception ignored) {}
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private Notification note() {
        String ch = "anancy_remote";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "BackSeat TV remote", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("BackSeat TV remote").setContentText("linked to the TV")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }
}
