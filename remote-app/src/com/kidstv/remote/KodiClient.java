package com.kidstv.remote;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal Kodi JSON-RPC client over the raw TCP port 9090, with zero-config discovery.
 *
 * The remote talks to Kodi by IP, and an IP works identically over WiFi or a Bluetooth-PAN
 * tether - so there's no mDNS layer to break over Bluetooth (what makes Yatse's BT unusable).
 * But typing that IP in was the pain point, so we FIND it: the phone is the hotspot AP, so the
 * tablet is simply some host on one of the phone's own local /24s. We fast-path the last IP
 * that worked (instant on relaunch), and only if that misses do we sweep the phone's hotspot +
 * WiFi subnets in parallel for whoever answers Kodi's pong on 9090. No manual IP, no ARP table
 * (blocked to apps since Android 10), no special permissions.
 */
class KodiClient {
    static final int PORT = 9090;
    static final int BRIDGE_PORT = 8080;   // the kiosk's HTTP bridge - nap/wake interception lives here
    static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.Ping\"}";

    private volatile String activeHost;   // IP currently reaching Kodi (null = disconnected)
    private volatile String activeLabel;  // "WiFi" / "BT"
    private volatile String lastGood = "";                 // cached IP that last worked (fast path)
    private final List<String> pinned = new ArrayList<>(); // optional manual overrides
    private volatile long lastSweep = 0;                   // throttle full scans

    /** Optional manual overrides (may be empty strings) - blank = pure auto-discovery. */
    void setPinned(String... ips) {
        pinned.clear();
        for (String ip : ips) if (ip != null && ip.trim().length() > 0) pinned.add(ip.trim());
        activeHost = null; activeLabel = null;
    }
    /** Seed the fast path with the IP that worked last session. */
    void setLast(String ip) { if (ip != null) lastGood = ip.trim(); }

    boolean connected() { return activeHost != null; }
    String activeLabel() { return activeLabel; }
    String activeHost() { return activeHost; }

    private boolean pong(String host, int timeoutMs) {
        String r = call(host, PING, timeoutMs);
        return r != null && r.contains("pong");
    }
    private void lock(String host) { activeHost = host; activeLabel = transportFor(host); lastGood = host; }

    /**
     * Locate Kodi with no manual IP. Fast path (every call, cheap): the last IP that worked +
     * any pinned overrides. If those miss, a throttled full subnet sweep - so a disconnected
     * 1 Hz poll loop retries the cheap path every tick but only scans every ~8 s.
     */
    boolean findHost() {
        Set<String> quick = new LinkedHashSet<>();
        if (lastGood.length() > 0) quick.add(lastGood);
        quick.addAll(pinned);
        for (String h : quick) if (pong(h, 800)) { lock(h); return true; }

        long now = System.currentTimeMillis();
        if (now - lastSweep < 8000) { activeHost = null; activeLabel = null; return false; }
        lastSweep = now;
        String hit = sweep();
        if (hit != null) { lock(hit); return true; }
        activeHost = null; activeLabel = null; return false;
    }

    /** Force an immediate full sweep (the "Find tablet" button), ignoring the throttle. */
    boolean rescan() { lastSweep = 0; return findHost(); }

    /** Probe every host on the phone's own site-local /24s in parallel; first pong wins. */
    private String sweep() {
        List<String> candidates = subnetHosts();
        if (candidates.isEmpty()) return null;
        final AtomicReference<String> found = new AtomicReference<>(null);
        final AtomicInteger done = new AtomicInteger(0);
        final int total = candidates.size();
        ExecutorService pool = Executors.newFixedThreadPool(80);
        for (final String ip : candidates) {
            pool.execute(() -> {
                if (found.get() == null && pong(ip, 500)) found.compareAndSet(null, ip);
                done.incrementAndGet();
            });
        }
        pool.shutdown();
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (found.get() != null || done.get() >= total) break;
            try { Thread.sleep(40); } catch (Exception e) { break; }
        }
        pool.shutdownNow();
        return found.get();
    }

    /** Every host on each of the phone's own site-local /24s (its hotspot + WiFi), self excluded.
     *  Cellular interfaces are skipped so we don't scan a carrier subnet for nothing. */
    private List<String> subnetHosts() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("clat")
                        || n.startsWith("pdp") || n.startsWith("dummy")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] ip = a.getAddress();
                    String base = (ip[0]&0xff) + "." + (ip[1]&0xff) + "." + (ip[2]&0xff) + ".";
                    int self = ip[3]&0xff;
                    for (int h = 1; h <= 254; h++) if (h != self) out.add(base + h);
                }
            }
        } catch (Exception e) {}
        return new ArrayList<>(out);
    }

    /** Label the connection by the local interface whose /24 contains the host. */
    private String transportFor(String ip) {
        try {
            String pref = ip.substring(0, ip.lastIndexOf('.') + 1);
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.getHostAddress().startsWith(pref)) {
                        String n = ni.getName().toLowerCase();
                        if (n.contains("bt") || n.contains("pan")) return "BT";
                        return "WiFi";
                    }
                }
            }
        } catch (Exception e) {}
        return "Kodi";
    }

    /** Send a raw JSON-RPC request; return the id:1 response object (or null). Re-probes once on failure. */
    String rpc(String req) {
        String host = activeHost;
        if (host == null) { if (!findHost()) return null; host = activeHost; }
        String r = call(host, req, 3000);
        if (r == null) {                       // host went away - re-probe and retry once
            if (findHost()) r = call(activeHost, req, 3000);
        }
        return r;
    }

    /** Build + send a method call. paramsJson may be null. */
    String rpc(String method, String paramsJson) {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
            + (paramsJson != null ? ",\"params\":" + paramsJson : "") + "}";
        return rpc(req);
    }

    /** POST a JSON-RPC to the kiosk's HTTP bridge (8080), NOT Kodi's raw 9090. The kiosk
        INTERCEPTS System.Shutdown/Reboot there to nap/wake the tablet - stop playback + blank &
        LOCK the screen (dpm.lockNow), and the reverse - a real device lock Kodi on 9090 can't do.
        Reuses the already-discovered host (works over WiFi or BT alike). Returns true on HTTP 200. */
    boolean bridge(String json) {
        String host = activeHost;
        if (host == null) { if (!findHost()) return false; host = activeHost; }
        java.net.HttpURLConnection con = null;
        try {
            java.net.URL url = new java.net.URL("http://" + host + ":" + BRIDGE_PORT + "/jsonrpc");
            con = (java.net.HttpURLConnection) url.openConnection();
            con.setConnectTimeout(2500); con.setReadTimeout(4000);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.getOutputStream().write(json.getBytes("UTF-8"));
            int code = con.getResponseCode();
            try { con.getInputStream().close(); } catch (Exception e) {}
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception e) {}
        }
    }

    private String call(String host, String req, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PORT), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write(req.getBytes("UTF-8"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            while (sb.length() < 1_000_000) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
                String cur = sb.toString();
                if (cur.contains("\"id\":1") && (cur.contains("\"result\"") || cur.contains("\"error\""))) break;
            }
            return extractIdObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception e) {}
        }
    }

    /** Kodi replies can be preceded by unsolicited notification objects; pull the id:1 one. */
    static String extractIdObject(String data) {
        int depth = 0, start = -1;
        String last = null;
        boolean inStr = false, esc = false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = data.substring(start, i + 1);
                    if (obj.contains("\"id\":1")) return obj;
                    last = obj;
                }
            }
        }
        return last;
    }
}
