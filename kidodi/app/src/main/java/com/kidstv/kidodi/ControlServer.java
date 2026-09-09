package com.kidstv.kidodi;

// ============================================================================
//  In-process control server for the player: HTTP on :8090, calling
//  PlayerController directly (no Kodi, no JSON-RPC). Also advertises the player
//  on the LAN via mDNS/NSD (_kidodi._tcp) so a remote finds it by name — the
//  default "same-network" discovery tier. (BLE tier + Wi-Fi-Direct pull come
//  in later cooks.)
// ============================================================================

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class ControlServer {
    static final String TAG = "Kidodi";
    static final int PORT = 8090;

    private final Context ctx;
    private final PlayerController ctrl;
    private volatile boolean run = false;
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdL;

    public ControlServer(Context c, PlayerController ctrl) { this.ctx = c.getApplicationContext(); this.ctrl = ctrl; }

    public void start() { if (run) return; run = true; new Thread(this::serve).start(); }
    public void stop() { run = false; try { if (server != null) server.close(); } catch (Exception ignored) {} unregisterNsd(); }

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "control server on :" + PORT);
            registerNsd();
            while (run) { final Socket s = server.accept(); new Thread(() -> handle(s)).start(); }
        } catch (Exception e) { if (run) Log.e(TAG, "serve died", e); }
    }

    private void handle(Socket c) {
        try {
            InputStream in = c.getInputStream();
            StringBuilder h = new StringBuilder(); int b, nl = 0;
            while ((b = in.read()) != -1) { h.append((char) b); if (b == '\n') { if (++nl == 2) break; } else if (b != '\r') nl = 0; }
            String line = h.length() > 0 ? h.toString().split("\r\n", 2)[0] : "";
            String[] parts = line.split(" ");
            String target = parts.length >= 2 ? parts[1] : "/";
            String path = target, q = "";
            int i = target.indexOf('?'); if (i >= 0) { path = target.substring(0, i); q = target.substring(i + 1); }

            if (path.equals("/upload")) { handleUpload(param(q, "name"), h.toString(), in, c); return; }   // wifi file import

            String body;
            switch (path) {
                case "/state":     body = ctrl.stateJson(); break;
                case "/play":      PlayerActivity.wakeScreen(); ctrl.play();  body = ok(); break;
                case "/pause":     ctrl.pause(); body = ok(); break;
                case "/playpause": PlayerActivity.wakeScreen(); ctrl.toggle(); body = ok(); break;
                case "/next":      PlayerActivity.wakeScreen(); ctrl.next();  body = ok(); break;
                case "/prev":      PlayerActivity.wakeScreen(); ctrl.prev();  body = ok(); break;
                case "/seeknear":  ctrl.seekNearEnd(); body = ok(); break;
                case "/vol": {
                    String v = param(q, "v"), d = param(q, "d");
                    if (v != null) ctrl.setVolume(pf(v, 0)); else ctrl.nudge(pf(d, 0.1f));
                    body = ok(); break;
                }
                case "/sleep": {
                    int m = (int) pf(param(q, "min"), 0);
                    if (m > 0) ctrl.startSleep(m); else ctrl.cancelSleep();
                    body = ok(); break;
                }
                case "/flash": PlayerActivity.flash((int) pf(param(q, "ms"), 3000)); body = ok(); break;
                case "/power": PlayerActivity.power(); body = ok(); break;
                case "/playlists": body = ctrl.playlistsJson(); break;
                case "/playlist": { String n = param(q, "name"); if (n != null) { PlayerActivity.wakeScreen(); ctrl.selectPlaylist(urlDecode(n)); } body = ok(); break; }
                default: body = "{\"ok\":false,\"error\":\"unknown path\"}";
            }
            write(c, body);
        } catch (Exception e) { Log.e(TAG, "handle err", e); }
        finally { try { c.close(); } catch (Exception ignored) {} }
    }

    // Phone -> tablet file import (Wi-Fi): POST /upload?name=<file> with the raw bytes as the body.
    private void handleUpload(String name, String headers, InputStream in, Socket c) {
        try {
            if (name == null || name.isEmpty()) name = "upload_" + System.currentTimeMillis() + ".mp4";
            name = urlDecode(name).replaceAll("[\\\\/]", "_");
            int len = 0;
            for (String line : headers.split("\r\n"))
                if (line.toLowerCase().startsWith("content-length:")) { try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {} }
            java.io.File dir = Library.importDir(ctx);
            if (dir == null) { write(c, "{\"ok\":false,\"error\":\"no library dir\"}"); return; }
            java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192]; int remaining = len, n;
            while (remaining > 0 && (n = in.read(buf, 0, Math.min(buf.length, remaining))) != -1) { fos.write(buf, 0, n); remaining -= n; }
            fos.close();
            Log.i(TAG, "imported " + out.getName() + " (" + (len - remaining) + " bytes)");
            PlayerController.get().rescan(ctx);   // new file shows up in the Imports playlist
            write(c, "{\"ok\":true,\"saved\":\"" + out.getName() + "\",\"bytes\":" + (len - remaining) + "}");
        } catch (Exception e) { Log.e(TAG, "upload err", e); try { write(c, "{\"ok\":false}"); } catch (Exception ignored) {} }
    }

    private static String ok() { return "{\"ok\":true}"; }
    private static String param(String q, String key) {
        for (String kv : q.split("&")) { int e = kv.indexOf('='); if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1); }
        return null;
    }
    private static float pf(String s, float dflt) { if (s == null) return dflt; try { return Float.parseFloat(s.trim()); } catch (Exception e) { return dflt; } }
    private static String urlDecode(String s) { try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private void write(Socket c, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(data); os.flush();
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("Kidodi");
            si.setServiceType("_kidodi._tcp");
            si.setPort(PORT);
            nsdL = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "mDNS registered " + s.getServiceName()); }
                public void onRegistrationFailed(NsdServiceInfo s, int e) { Log.e(TAG, "mDNS reg failed " + e); }
                public void onServiceUnregistered(NsdServiceInfo s) {}
                public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            };
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdL);
        } catch (Exception e) { Log.e(TAG, "nsd err", e); }
    }
    private void unregisterNsd() { try { if (nsd != null && nsdL != null) nsd.unregisterService(nsdL); } catch (Exception ignored) {} nsdL = null; }
}
