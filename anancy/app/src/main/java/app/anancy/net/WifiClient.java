package app.anancy.net;

// ============================================================================
//  Wi-Fi/mDNS fallback transport (phone side). Discovers the player's
//  _anancy._tcp service on the shared network, resolves host:port, then polls
//  /state and sends commands over HTTP :8090. Same command vocabulary as BLE
//  ("next", "sleep:15", ...), translated to the player's HTTP endpoints.
// ============================================================================

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class WifiClient {
    public interface Listener {
        void onStatus(String s); void onState(String json);
        /** A player resolved on the LAN (id + friendly name), whether or not we poll it. */
        void onSeen(String id, String name);
    }
    static final String TAG = "Anancy";
    static final String SVC = "_anancy._tcp.";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener disco;
    private volatile String host;
    private volatile int port = 8090;
    private volatile boolean stopped = false, connected = false, fixed = false;
    private Runnable poller;

    // WHICH player to poll. Every resolved service used to overwrite `host`, so with two tablets on the
    // network the remote silently flipped to whichever resolved last. Now non-target players are only
    // remembered in `hosts`; null target keeps the old first-one-wins behaviour.
    private volatile String target;
    private volatile String connectedId;
    private final java.util.Map<String, String> hosts = new java.util.concurrent.ConcurrentHashMap<>();
    // NsdManager can't resolve two services at once (the 2nd fails silently) — with two tablets that left
    // only ONE in `hosts`, so "both in unison" reached only one. Resolve strictly one at a time.
    private final java.util.ArrayDeque<NsdServiceInfo> toResolve = new java.util.ArrayDeque<>();
    private boolean resolving = false;
    public void setTarget(String id) { target = (id == null || id.isEmpty()) ? null : id; }
    public String connectedId() { return connectedId; }
    /** id -> "host:port" for every player resolved this session (direct HTTP without re-discovery). */
    public java.util.Map<String, String> hosts() { return new java.util.LinkedHashMap<>(hosts); }

    public WifiClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }
    public boolean isConnected() { return connected; }
    public String hostPort() { return host != null ? host + ":" + port : null; }
    /** Talk to a known host — the TV's own admin Remote uses 127.0.0.1 — no mDNS discovery. */
    public void setFixedHost(String h, int p) { host = h; port = p; fixed = true; }

    public void start() {
        stopped = false;
        if (fixed) { status("local"); startPolling(); return; }
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) { status("no mDNS"); return; }
            disco = new NsdManager.DiscoveryListener() {
                public void onServiceFound(NsdServiceInfo si) { if (si.getServiceType().contains("_anancy")) { synchronized (toResolve) { toResolve.add(si); } pumpResolve(); } }
                public void onServiceLost(NsdServiceInfo si) {}
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t, int e) { status("mDNS discovery failed " + e); }
                public void onStopDiscoveryFailed(String t, int e) {}
            };
            status("looking for player on wifi…");
            nsd.discoverServices("_anancy._tcp", NsdManager.PROTOCOL_DNS_SD, disco);
        } catch (Exception e) { status("wifi err"); }
    }

    /** Resolve queued services strictly one at a time (NsdManager rejects concurrent resolves). */
    private void pumpResolve() {
        final NsdServiceInfo si;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty() || stopped) return;
            resolving = true; si = toResolve.poll();
        }
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) {
                    if (e == NsdManager.FAILURE_ALREADY_ACTIVE) { synchronized (toResolve) { toResolve.add(s); } }   // retry after the current one
                    next();
                }
                public void onServiceResolved(NsdServiceInfo s) { handleResolved(s); next(); }
                private void next() { synchronized (toResolve) { resolving = false; } main.postDelayed(WifiClient.this::pumpResolve, 150); }
            });
        } catch (Exception e) { synchronized (toResolve) { resolving = false; } main.postDelayed(this::pumpResolve, 300); }
    }
    private void handleResolved(NsdServiceInfo s) {
        String h = s.getHost() != null ? s.getHost().getHostAddress() : null;
        int p = s.getPort() > 0 ? s.getPort() : 8090;
        if (h == null) return;
        String id = attr(s, "id");
        String nm = attr(s, "name");
        if (nm == null || nm.isEmpty()) nm = s.getServiceName();   // NSD may have renamed it on a clash
        if (id != null) { final String i2 = id, n2 = nm; hosts.put(id, h + ":" + p); Log.i(TAG, "resolved " + id + " (" + nm + ") @ " + h + ":" + p + " hosts=" + hosts.keySet()); main.post(() -> l.onSeen(i2, n2)); }
        String want = target;
        if (want != null && (id == null || !want.equals(id))) return;   // another tablet: remembered, not switched to
        host = h; port = p; connectedId = id;
        startPolling();
    }

    /** A TXT value from the player's mDNS record ("id" / "name"); null when absent. */
    private static String attr(NsdServiceInfo s, String key) {
        try {
            java.util.Map<String, byte[]> a = s.getAttributes();
            byte[] v = a != null ? a.get(key) : null;
            return v == null ? null : new String(v, "UTF-8");
        } catch (Exception e) { return null; }
    }

    private void startPolling() {
        if (poller != null) return;
        poller = new Runnable() { public void run() {
            if (stopped || host == null) return;
            new Thread(() -> {
                String resp = http("/state");
                if (resp != null) { connected = true; final String r = resp; main.post(() -> { status("ready"); l.onState(r); }); }
                else connected = false;
            }).start();
            main.postDelayed(this, 1000);
        }};
        main.post(poller);
    }

    public void send(String cmd) {
        if (host == null) return;
        final String path = toHttp(cmd);
        if (isQuery(cmd)) {   // queries answer with a typed payload -> deliver via onState, uniform with BLE
            new Thread(() -> { String r = http(path); if (r != null) main.post(() -> l.onState(r)); }).start();
            return;
        }
        new Thread(() -> http(path)).start();
    }
    /** "Both in unison": fan a (non-query) command out to EVERY resolved player. A query has no single
        answer, so it still goes to the one we poll. Falls back to the normal send when nothing resolved. */
    public void sendAll(String cmd) {
        if (isQuery(cmd)) { send(cmd); return; }
        java.util.Collection<String> hp = new java.util.ArrayList<>(hosts.values());
        Log.i(TAG, "sendAll('" + cmd + "') hosts=" + hosts.keySet() + " -> " + hp);
        if (hp.isEmpty()) { send(cmd); return; }
        final String path = toHttp(cmd);
        for (final String h : hp) new Thread(() -> httpTo(h, path)).start();
    }
    /** ids currently resolved on the LAN (for callers to know who Wi-Fi can reach). */
    public java.util.Set<String> hostIds() { return new java.util.HashSet<>(hosts.keySet()); }
    /** Send a (non-query) command to specific players by id over HTTP — for those NOT reachable on BLE
        (e.g. a TV that doesn't advertise BLE). */
    public void sendTo(java.util.Collection<String> ids, String cmd) {
        if (isQuery(cmd) || ids == null || ids.isEmpty()) return;
        final String path = toHttp(cmd);
        for (String id : ids) {
            String hp = hosts.get(id);
            if (hp != null) { final String h = hp; new Thread(() -> httpTo(h, path)).start(); }
        }
    }
    private void httpTo(String hostPort, String path) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("http://" + hostPort + path).openConnection();
            c.setConnectTimeout(2500); c.setReadTimeout(2500);
            c.getInputStream().close();
        } catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
    }
    /** Commands whose HTTP response is a {"type":...} payload the UI waits for. */
    private static boolean isQuery(String cmd) {
        return cmd.equals("list") || cmd.equals("queue") || cmd.equals("conn") || cmd.equals("cfg") || cmd.startsWith("shows") || cmd.startsWith("eps:");
    }

    /** BLE command vocabulary -> the player's HTTP endpoints (must stay in step with ControlServer). */
    private static String toHttp(String cmd) {
        if (cmd.startsWith("sleep:")) return "/sleep?min=" + cmd.substring(6);
        if (cmd.startsWith("play:")) return "/playlist?name=" + enc(cmd.substring(5));
        if (cmd.startsWith("jump:")) return "/jump?i=" + cmd.substring(5);
        if (cmd.startsWith("snap:")) return "/snap?on=" + onOff(cmd.substring(5));
        if (cmd.startsWith("hotspot:")) return "/hotspot?on=" + onOff(cmd.substring(8));
        if (cmd.startsWith("playmode:")) return onOffToggle("/playmode", cmd.substring(9));
        // library / queue editing / playlists
        if (cmd.startsWith("eps:")) {   // eps:SHOW:FROM -> HTTP returns the whole show at once
            String rest = cmd.substring(4); int k = rest.lastIndexOf(':');
            return "/library?show=" + enc(k > 0 ? rest.substring(0, k) : rest);
        }
        if (cmd.startsWith("shows")) return "/library";   // "shows" / "shows:FROM"
        if (cmd.startsWith("enqnext:")) return "/enqueue?id=" + cmd.substring(8) + "&next=1";
        if (cmd.startsWith("enqnow:")) return "/enqueue?id=" + cmd.substring(7) + "&now=1";
        if (cmd.startsWith("enqshow:")) return "/enqueue?show=" + enc(cmd.substring(8));
        if (cmd.startsWith("enq:")) return "/enqueue?id=" + cmd.substring(4);
        if (cmd.equals("clearq")) return "/clearqueue";
        if (cmd.startsWith("deq:")) return "/dequeue?i=" + cmd.substring(4);
        if (cmd.startsWith("shuffle:")) return onOffToggle("/shuffle", cmd.substring(8));
        if (cmd.startsWith("loop:")) return onOffToggle("/loop", cmd.substring(5));
        if (cmd.startsWith("savequeue:")) return "/savequeue?name=" + enc(cmd.substring(10));
        if (cmd.startsWith("delpl:")) return "/delplaylist?name=" + enc(cmd.substring(6));
        // config sync
        if (cmd.equals("cfg")) return "/config";
        if (cmd.startsWith("cfg@")) return "/config";   // HTTP has no notification budget: one page, no "more"
        if (cmd.startsWith("cfg:")) {   // cfg:KEY=VALUE
            String rest = cmd.substring(4); int e = rest.indexOf('=');
            String k = e > 0 ? rest.substring(0, e) : rest, v = e > 0 ? rest.substring(e + 1) : "";
            return "/config?set=" + enc(k) + "&value=" + enc(v);
        }
        // connection level + Wi-Fi hand-off
        if (cmd.startsWith("conn:")) return "/conn?mode=" + enc(cmd.substring(5));
        if (cmd.startsWith("wifi:")) {   // wifi:SSID<TAB>PASS
            String rest = cmd.substring(5); int t = rest.indexOf('\t');
            return "/wifi?ssid=" + enc(t >= 0 ? rest.substring(0, t) : rest) + "&pass=" + enc(t >= 0 ? rest.substring(t + 1) : "");
        }
        switch (cmd) {
            case "list": return "/playlists";
            case "conn": return "/conn";
            case "volup": return "/vol?d=0.1";
            case "voldown": return "/vol?d=-0.1";
            case "mute": return "/mute";               // toggle (never "/vol?v=0": that zeroes the real volume)
            case "unmute": return "/mute?on=0";
            case "wake": return "/sleep?min=0";
            case "queue": return "/queue?center=-1&n=4";   // same ±4 window BLE pushes
            default: return "/" + cmd;   // play, pause, playpause, next, prev, flash (Cfg duration), power
        }
    }
    private static String onOff(String s) { return "on".equals(s.trim()) ? "1" : "0"; }
    private static String onOffToggle(String base, String s) {
        s = s.trim();
        return "on".equals(s) ? base + "?on=1" : "off".equals(s) ? base + "?on=0" : base;   // toggle = no param
    }
    private static String enc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private String http(String path) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("http://" + host + ":" + port + path).openConnection();
            c.setConnectTimeout(2500); c.setReadTimeout(2500);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1024]; int n; while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    public void stop() {
        stopped = true; connected = false;
        try { if (poller != null) main.removeCallbacks(poller); } catch (Exception ignored) {} poller = null;
        try { if (nsd != null && disco != null) nsd.stopServiceDiscovery(disco); } catch (Exception ignored) {}
    }

    private void status(String s) { main.post(() -> l.onStatus(s)); }
}
