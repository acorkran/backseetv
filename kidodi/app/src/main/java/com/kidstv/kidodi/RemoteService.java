package com.kidstv.kidodi;

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
//  CONNECTION LEVELS (kidodi_conn, chosen on either side, published by the TV
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
    static final String TAG = "Kidodi";
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
    public void requestPlaylists() { send("list"); }
    public void requestShows(int from) { requestTyped(from > 0 ? "shows:" + from : "shows", "library"); }
    public void requestEpisodes(String show, int from) { requestTyped("eps:" + show + ":" + from, "episodes"); }
    public void requestConn() { requestTyped("conn", "conn"); }
    public void requestConfig() { requestTyped("cfg", "config"); }
    public String configJson() { return typed.get("config"); }
    /** Write one tablet setting from a remote (mirrors the on-tablet Settings screen). */
    public void setConfig(String key, String val) { send("cfg:" + key + "=" + val); }
    public String libraryJson() { return typed.get("library"); }
    public String episodesJson() { return typed.get("episodes"); }
    public String connJson() { return typed.get("conn"); }
    public java.util.List<String> playlistNames() { return playlistNames; }
    public java.util.List<String> userPlaylistNames() { return userPlaylistNames; }

    private void onTyped(String type, String json) {
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
            java.util.List<String> out = new java.util.ArrayList<>(), usr = new java.util.ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            if (u != null) for (int i = 0; i < u.length(); i++) usr.add(u.optString(i));
            playlistNames = out; userPlaylistNames = usr;
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
    private volatile String connMode = "ble";
    private volatile String hsSsid = null, hsPass = null;   // the TV's hotspot creds, while it runs
    private String joinedKey = null, pushedKey = null;
    public String connMode() { return connMode; }
    /** The TV's published hotspot creds [ssid, pass], or null. */
    public String[] tvHotspot() { String s = hsSsid, p = hsPass; return (s != null && p != null) ? new String[]{s, p} : null; }
    /** Called by the UI when the user picks a level here: persist, tell the TV, apply locally. */
    public void setConnMode(String mode) {
        if (mode == null || mode.isEmpty()) return;
        Cfg.put(this, "kidodi_conn", mode);
        send("conn:" + mode);
        connMode = mode;
        applyConnMode();
    }
    private void noteConn(String mode, JSONObject hotspot) {
        if (hotspot != null) { hsSsid = hotspot.optString("ssid", null); hsPass = hotspot.optString("pass", null); }
        else { hsSsid = null; hsPass = null; }
        if (mode != null && !mode.isEmpty() && !mode.equals(connMode)) { connMode = mode; Cfg.put(this, "kidodi_conn", mode); }
        applyConnMode();
    }
    /** Phone-side coordination for the current level (never on the TV's own remote). */
    private void applyConnMode() {
        if (local) return;
        String mode = connMode;
        if ("ble".equals(mode)) {   // pure Bluetooth: no Wi-Fi client, no hotspot of ours
            if (wifi != null) { try { wifi.stop(); } catch (Exception ignored) {} wifi = null; }
        } else if (wifi == null) startWifi(null, 0);   // a Wi-Fi tier runs mDNS CONCURRENTLY with BLE so we
        // discover + can reach EVERY tablet (multi-target / both-in-unison), not just as a BLE fallback
        if ("tv_hotspot".equals(mode) && hsSsid != null && hsPass != null) {
            String key = hsSsid + "|" + hsPass;
            if (!key.equals(joinedKey)) {   // join the TV's hotspot once per creds (non-owner phone: a system prompt)
                joinedKey = key;
                WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = hsSsid; c.type = "WPA"; c.pass = hsPass;
                try { WifiJoiner.join(this, c); } catch (Exception e) { Log.w(TAG, "join tv hotspot: " + e); }
            }
        }
        if ("remote_hotspot".equals(mode)) {
            if (!HotspotService.running()) { try { HotspotService.start(this); } catch (Exception e) { Log.w(TAG, "hotspot: " + e); } }
            String s = HotspotService.currentSsid(), p = HotspotService.currentPass();
            if (s != null && p != null) {
                String key = s + "|" + p;
                if (!key.equals(pushedKey)) { pushedKey = key; send("wifi:" + s + "\t" + p); }   // the TV joins us (owner = silent)
            } else main.postDelayed(this::applyConnMode, 2000);   // creds arrive once the hotspot is up
        } else if (HotspotService.running()) { try { HotspotService.stop(this); } catch (Exception ignored) {} pushedKey = null; }
    }
    /** Push the phone-hosted hotspot creds to the TV again (UI "Send Wi-Fi to TV"). */
    public void pushOwnHotspot() { pushedKey = null; applyConnMode(); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        startForeground(72, note());
        connMode = Cfg.getStr(this, "kidodi_conn", "ble");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            // On the TV (role=player) this service can only ever be the local admin remote — even on a
            // restart with a null intent it must never start scanning BLE for itself.
            String role = getSharedPreferences("kidodi", MODE_PRIVATE).getString("role", "");
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
    static final String PREF_TARGET = "kidodi_target";   // comma-separated ids to control; "" (or "*") = ALL
    static final String PREF_SYNCGROUP = "kidodi_syncgroup";   // leader id when a synced group is armed; "" = off
    static final String PREF_GROUP_AUDIO = "kidodi_group_audio";   // "each" = every screen its own sound (default); "leader" = leader only
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
    public boolean isSyncGroup() { return !Cfg.p(this).getString(PREF_SYNCGROUP, "").isEmpty(); }
    /** Audio in a synced group: false = each screen plays its own sound (default); true = only the leader. */
    public boolean groupLeaderOnly() { return "leader".equals(Cfg.p(this).getString(PREF_GROUP_AUDIO, "each")); }
    public void setGroupAudio(boolean leaderOnly) {
        Cfg.p(this).edit().putString(PREF_GROUP_AUDIO, leaderOnly ? "leader" : "each").apply();
        if (isSyncGroup()) reArmFollowersMute();   // push the new audio mode to the current followers
    }
    /** The screens in the group: the current selection, or all seen when "All". */
    private java.util.Set<String> groupMembers() {
        java.util.Set<String> t = targets();
        return t.isEmpty() ? new java.util.LinkedHashSet<>(seenTablets.keySet()) : t;
    }
    /** Re-tell the current followers whether to stay silent (leader-only) or play their own audio. */
    private void reArmFollowersMute() {
        String leader = syncLeaderId(); if (leader == null) return;
        String leaderIp = deviceIp(leader); if (leaderIp == null) return;
        int mute = groupLeaderOnly() ? 1 : 0;
        for (String id : groupMembers()) if (!id.equals(leader)) { String ip = deviceIp(id); if (ip != null) httpArm(ip, "/sync?on=1&host=" + leaderIp + "&mute=" + mute); }
    }
    public String syncLeaderId() { String s = Cfg.p(this).getString(PREF_SYNCGROUP, ""); return s.isEmpty() ? null : s; }
    public String syncLeaderName() { String id = syncLeaderId(); if (id == null) return null; String n = seenTablets.get(id); return n != null ? n : id; }
    /** Arm/disarm a synced group over the CURRENT screen selection. Leader = the first selected (its state is
        what the remote shows); the rest become muted followers streaming from + mirroring the leader. */
    public void setSyncGroup(boolean on) {
        if (!on) {   // disarm: tell everyone we can reach to stop following (idempotent, so hit all known ids)
            String leader = syncLeaderId();
            java.util.HashSet<String> all = new java.util.HashSet<>(seenTablets.keySet()); all.addAll(deviceIps.keySet());
            for (String id : all) if (!id.equals(leader)) httpArm(deviceIp(id), "/sync?on=0");
            Cfg.p(this).edit().putString(PREF_SYNCGROUP, "").apply();
            aimLinks();
            return;
        }
        java.util.List<String> order = new java.util.ArrayList<>(isAll() ? seenTablets.keySet() : targets());
        if (order.size() < 2) return;                       // need a leader + at least one follower
        // Leader must be a MEDIA HOST (followers stream from it). Prefer a known host in the group; else the
        // currently-shown representative (what the user drives); else the first with a resolvable ip.
        String leader = null;
        for (String id : order) if (hostIds.contains(id)) { leader = id; break; }
        if (leader == null) { try { String rid = new JSONObject(lastState).optString("id", ""); if (order.contains(rid)) leader = rid; } catch (Exception ignored) {} }
        if (leader == null) for (String id : order) if (deviceIp(id) != null) { leader = id; break; }
        if (leader == null) leader = order.get(0);
        String leaderIp = deviceIp(leader);
        if (leaderIp == null || leaderIp.isEmpty()) return; // can't arm without the leader's ip
        httpArm(leaderIp, "/sync?on=0");                    // the leader plays normally (audio source)
        int mute = groupLeaderOnly() ? 1 : 0;               // each screen its own sound, or leader only
        for (String id : order) {
            if (id.equals(leader)) continue;
            String fip = deviceIp(id);
            if (fip != null && !fip.isEmpty()) httpArm(fip, "/sync?on=1&host=" + leaderIp + "&mute=" + mute);
        }
        Cfg.p(this).edit().putString(PREF_SYNCGROUP, leader).apply();
        aimLinks();                                         // control now drives the leader; followers mirror
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
        seenTablets.put(id, name == null || name.isEmpty() ? id : name);
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

    public void send(String cmd) {
        if (isQueryCmd(cmd)) {   // a query has ONE answer — ask the representative only
            if (bleUp()) ble.sendState(cmd);
            else if (wifi != null) wifi.send(cmd);
            return;
        }
        // Volume in a synced group with PER-SCREEN audio hits every member (each its own sound); everything
        // else (playback; or volume when the group is leader-only) goes to the effective target below.
        if (isSyncGroup() && isVolCmd(cmd) && !groupLeaderOnly()) {
            java.util.Set<String> members = groupMembers();
            java.util.Set<String> ble2 = ble != null ? ble.connectedIds() : new java.util.HashSet<String>();
            if (ble != null) ble.sendToIds(members, cmd);
            if (wifi != null) { java.util.Set<String> w = new java.util.HashSet<>(members); w.removeAll(ble2); if (!w.isEmpty()) wifi.sendTo(w, cmd); }
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
    private static boolean isVolCmd(String c) { return c != null && (c.equals("volup") || c.equals("voldown") || c.equals("mute") || c.startsWith("vol")); }
    private static boolean isQueryCmd(String c) {
        return c.equals("list") || c.equals("queue") || c.equals("conn") || c.equals("cfg") || c.startsWith("shows") || c.startsWith("eps:");
    }
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
        String ch = "kidodi_remote";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Kidodi remote", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Kidodi remote").setContentText("linked to the TV")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }
}
