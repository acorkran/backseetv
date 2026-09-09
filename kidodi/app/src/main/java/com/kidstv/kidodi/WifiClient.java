package com.kidstv.kidodi;

// ============================================================================
//  Wi-Fi/mDNS fallback transport (phone side). Discovers the player's
//  _kidodi._tcp service on the shared network, resolves host:port, then polls
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
    public interface Listener { void onStatus(String s); void onState(String json); }
    static final String TAG = "Kidodi";
    static final String SVC = "_kidodi._tcp.";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener disco;
    private volatile String host;
    private volatile int port = 8090;
    private volatile boolean stopped = false, connected = false;
    private Runnable poller;

    public WifiClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }
    public boolean isConnected() { return connected; }

    public void start() {
        stopped = false;
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) { status("no mDNS"); return; }
            disco = new NsdManager.DiscoveryListener() {
                public void onServiceFound(NsdServiceInfo si) { if (si.getServiceType().contains("_kidodi")) resolve(si); }
                public void onServiceLost(NsdServiceInfo si) {}
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t, int e) { status("mDNS discovery failed " + e); }
                public void onStopDiscoveryFailed(String t, int e) {}
            };
            status("looking for player on wifi…");
            nsd.discoverServices("_kidodi._tcp", NsdManager.PROTOCOL_DNS_SD, disco);
        } catch (Exception e) { status("wifi err"); }
    }

    private void resolve(NsdServiceInfo si) {
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) {}
                public void onServiceResolved(NsdServiceInfo s) {
                    host = s.getHost() != null ? s.getHost().getHostAddress() : null;
                    port = s.getPort() > 0 ? s.getPort() : 8090;
                    if (host != null) { startPolling(); }
                }
            });
        } catch (Exception ignored) {}
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
        if (cmd.equals("list")) {   // deliver playlist names back via onState (uniform with BLE)
            new Thread(() -> { String r = http("/playlists"); if (r != null) main.post(() -> l.onState(r)); }).start();
            return;
        }
        final String path = toHttp(cmd);
        new Thread(() -> http(path)).start();
    }

    private static String toHttp(String cmd) {
        if (cmd.startsWith("sleep:")) return "/sleep?min=" + cmd.substring(6);
        if (cmd.startsWith("play:")) return "/playlist?name=" + enc(cmd.substring(5));
        switch (cmd) {
            case "volup": return "/vol?d=0.1";
            case "voldown": return "/vol?d=-0.1";
            case "mute": return "/vol?v=0";
            case "wake": return "/sleep?min=0";
            default: return "/" + cmd;   // play, pause, playpause, next, prev, flash, power
        }
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
