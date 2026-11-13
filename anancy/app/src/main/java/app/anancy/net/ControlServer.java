package app.anancy.net;

import app.anancy.Cfg;
import app.anancy.Identity;
import app.anancy.player.Library;
import app.anancy.player.PlayModeActivity;
import app.anancy.player.PlayerActivity;
import app.anancy.player.PlayerController;

// ============================================================================
//  In-process control server for the player: HTTP on :8090, calling
//  PlayerController directly (no Kodi, no JSON-RPC). Also advertises the player
//  on the LAN via mDNS/NSD (_anancy._tcp) so a remote finds it by name — the
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
    static final String TAG = "Anancy";
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
            while (run) {
                final Socket s = server.accept();
                // Bounded: a flood of half-open connections from anyone on the network used to start a
                // thread each, forever. A full pool turns the newcomer away instead.
                try { s.setSoTimeout(5_000); pool.execute(() -> handle(s)); }   // 5 s to send the headers
                catch (java.util.concurrent.RejectedExecutionException busy) {
                    Log.w(TAG, "HTTP busy (" + pool.getActiveCount() + " running): turned away " + s.getInetAddress().getHostAddress());
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { if (run) Log.e(TAG, "serve died", e); }
    }

    /** Up to 48 requests at once. Some are long-lived: /live, and /media streams to followers, which
        sit blocked in a write while the follower's player has a full buffer. */
    private final java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
            4, 48, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    static final int MAX_HEADERS = 8 * 1024;
    /** The nonce of the request being answered on this thread, so the answer can be tied to it. */
    private static final ThreadLocal<String> REQ_NONCE = new ThreadLocal<>();

    private void handle(Socket c) {
        REQ_NONCE.remove();
        try {
            InputStream in = c.getInputStream();
            StringBuilder h = new StringBuilder(); int b, nl = 0;
            while ((b = in.read()) != -1) {
                h.append((char) b);
                if (h.length() > MAX_HEADERS) return;   // nobody legitimate sends 8 KB of headers
                if (b == '\n') { if (++nl == 2) break; } else if (b != '\r') nl = 0;
            }
            String line = h.length() > 0 ? h.toString().split("\r\n", 2)[0] : "";
            String[] parts = line.split(" ");
            String target = parts.length >= 2 ? parts[1] : "/";
            String path = target, q = "";
            int i = target.indexOf('?'); if (i >= 0) { path = target.substring(0, i); q = target.substring(i + 1); }

            // Nothing is served to anyone who hasn't paired: every request must be signed with this
            // TV's key (net/PairCrypto). Before this, anything on the same Wi-Fi could drive the TV,
            // read its camera, change its Wi-Fi or even hand away device owner.
            String method = parts.length >= 1 ? parts[0] : "GET";
            String auth = header(h.toString(), app.anancy.net.PairCrypto.HEADER);
            REQ_NONCE.set(app.anancy.net.PairCrypto.nonceOf(auth));
            if (!app.anancy.Pairing.verifyRequest(ctx, auth, method, target)) {
                Log.w(TAG, "HTTP " + path + " refused: not paired (" + c.getInetAddress().getHostAddress() + ")");
                write(c, 401, "{\"ok\":false,\"error\":\"not paired\"}");
                return;
            }
            if (!c.getInetAddress().isLoopbackAddress()) app.anancy.Pairing.markPaired(ctx);

            c.setSoTimeout(30_000);   // a paired client's body (an upload) may pause longer between chunks
            if (path.equals("/upload")) { handleUpload(param(q, "name"), param(q, "sha"), h.toString(), in, c); return; }   // wifi file import
            if (path.equals("/snapshot.jpg")) { writeJpeg(c, SnapshotService.latestSnapshot()); return; }   // latest still image
            if (path.equals("/live")) { handleLive(c); return; }   // MJPEG live feed (Wi-Fi tier)
            if (path.equals("/media")) { handleMedia(c, param(q, "id"), h.toString()); return; }   // stream a library file by id (Range-capable) for a client tablet
            if (path.equals("/clock")) { write(c, "{\"t\":" + android.os.SystemClock.elapsedRealtime() + "}"); return; }   // leader's monotonic clock (NTP-lite offset for synced playback)

            String body;
            switch (path) {
                case "/state":     body = ctrl.stateJson(); break;
                case "/play":      PlayerActivity.wakeScreen(); ctrl.play();  body = ok(); break;
                case "/pause":     ctrl.pause(); body = ok(); break;
                case "/playpause": PlayerActivity.wakeScreen(); ctrl.toggle(); body = ok(); break;
                case "/next": { boolean off = PlayerActivity.isOff(); PlayerActivity.wakeScreen(); ctrl.next(); if (off) ctrl.play(); body = ok(); break; }
                case "/prev": { boolean off = PlayerActivity.isOff(); PlayerActivity.wakeScreen(); ctrl.prev(); if (off) ctrl.play(); body = ok(); break; }
                case "/seeknear":  ctrl.seekNearEnd(); body = ok(); break;
                case "/vol": {
                    String v = param(q, "v"), d = param(q, "d");
                    if (v != null) ctrl.setVolume(pf(v, 0)); else ctrl.nudge(pf(d, 0.1f));
                    body = ok(); break;
                }
                case "/mute": {   // persisted player mute (NOT system volume); no ?on= -> toggle (like BLE "mute")
                    String on = param(q, "on");
                    ctrl.setMuted(on == null ? !ctrl.isMuted() : ("1".equals(on) || "true".equals(on)));
                    body = ok(); break;
                }
                case "/queue": body = ctrl.queueJson((int) pf(param(q, "center"), -1), (int) pf(param(q, "n"), 0)); break;      // current play order (+index)
                case "/jump":  ctrl.jumpTo((int) pf(param(q, "i"), -1)); PlayerActivity.wakeScreen(); body = ok(); break;
                // Forget how far through each show she is, so Shuffle All starts every show from
                // episode 1 again. Takes effect on the next playlist select, not mid-queue.
                case "/resetprogress": ctrl.resetProgress(); body = ok(); break;
                case "/sleep": {
                    int m = (int) pf(param(q, "min"), 0);
                    if (m > 0) ctrl.startSleep(m); else ctrl.cancelSleep();
                    body = ok(); break;
                }
                case "/playurl": {   // client: stream+play a host media URL -- another TV's /media, nothing else
                    String u = param(q, "url"); u = u == null ? null : urlDecode(u);
                    if (u != null && u.matches("http://[0-9.]+:8090/media\\?id=\\d+")) { ctrl.playUrl(u); PlayerActivity.wakeScreen(); body = ok(); }
                    else body = "{\"ok\":false,\"error\":\"not a TV's media address\"}";
                    break;
                }
                case "/sync": {   // follower: mirror the host. &host=<ip> joins a group (point at the leader + reload as a client)
                    String on = param(q, "on");
                    boolean want = on == null ? !ctrl.isSync() : ("1".equals(on) || "true".equals(on));
                    String host = param(q, "host");
                    grantHost(host, param(q, "hostid"), param(q, "hostkey"));   // the leader's key, sealed for us
                    if (want && host != null && !host.isEmpty()) {
                        Cfg.applyConfig(ctx, "anancy_host", urlDecode(host));
                        ctrl.reloadForSync(ctx);   // enter client mode so itemFor(cur) resolves the leader's library
                    }
                    String mute = param(q, "mute");   // leader-only audio (1) vs each screen its own sound (0)
                    if (mute != null) { boolean m = "1".equals(mute) || "true".equals(mute); ctrl.setSyncMuteFollower(m); Cfg.applyConfig(ctx, "anancy_sync_mute", m ? "1" : "0"); }
                    boolean left = !want && ctrl.isSync();
                    ctrl.setSync(want);
                    Cfg.applyConfig(ctx, "anancy_sync", ctrl.isSync() ? "1" : "0");
                    if (left) ctrl.loadLibrary(ctx);   // a follower only holds the leader's current item: get a whole queue back
                    PlayerActivity.wakeScreen(); body = ok(); break;
                }
                case "/flash": PlayerActivity.flash((int) pf(param(q, "ms"), Cfg.getInt(ctx, "anancy_flash_ms", 3000))); body = ok(); break;
                case "/power": PlayerActivity.power(); body = ok(); break;
                // --- library / queue editing / user playlists --------------------------------------
                case "/library": { String show = param(q, "show"); body = show == null ? ctrl.libraryJson() : ctrl.episodesJson(urlDecode(show), 0, Integer.MAX_VALUE, 0); break; }
                case "/enqueue": {
                    String show = param(q, "show"), id = param(q, "id");
                    boolean now = "1".equals(param(q, "now")), next = "1".equals(param(q, "next"));
                    boolean okk = show != null ? ctrl.enqueueShow(urlDecode(show)) : ctrl.enqueue((int) pf(id, -1), next, now);
                    if (now) PlayerActivity.wakeScreen();
                    body = okk ? ok() : "{\"ok\":false,\"error\":\"unknown item\"}"; break;
                }
                // Re-read the media folders. Without this the ONLY rescan in the whole app was the one
                // after an upload, so media copied onto the tablet by hand (SD card, adb, USB) stayed
                // invisible -- and on a LOCKED kiosk you cannot even force-stop the app to rebuild it.
                case "/rescan": ctrl.rescan(ctx); body = ok(); break;
                case "/clearqueue": ctrl.clearQueue(); body = ok(); break;
                case "/dequeue": ctrl.dequeue((int) pf(param(q, "i"), -1)); body = ok(); break;
                case "/shuffle": { String on = param(q, "on"); ctrl.setShuffle(on == null ? !ctrl.isShuffle() : ("1".equals(on) || "true".equals(on))); body = ok(); break; }
                case "/loop":    { String on = param(q, "on"); ctrl.setLoop(on == null ? !ctrl.isLoop() : ("1".equals(on) || "true".equals(on))); body = ok(); break; }
                case "/savequeue": { String n = param(q, "name"); body = (n != null && ctrl.saveQueue(urlDecode(n))) ? ok() : "{\"ok\":false,\"error\":\"bad name or empty queue\"}"; break; }
                case "/delplaylist": {
                    String n = param(q, "name");
                    if (n != null && ctrl.deletePlaylist(urlDecode(n))) body = ok();
                    else { write(c, 400, "{\"ok\":false,\"error\":\"not a user playlist\"}"); return; }
                    break;
                }
                // --- connectivity level (ble | tv_hotspot | remote_hotspot | shared) ----------------
                case "/conn": {
                    String m = param(q, "mode");
                    if (m != null) HotspotService.applyMode(ctx, urlDecode(m));
                    body = ctrl.connJson(); break;
                }
                case "/wifi": {   // the TV joins a network (device owner = silent). s = "SSID<TAB>PASS" sealed with our key:
                    // a URL is logged and visible on the network, a password must not be.
                    String sealed = param(q, "s");
                    String plain = sealed == null ? null : app.anancy.Pairing.openMine(ctx, urlDecode(sealed));
                    boolean joined = false;
                    if (plain != null) {
                        String[] sp = plain.split("\t", 2);
                        WifiJoiner.Creds cr = new WifiJoiner.Creds();
                        cr.ssid = sp[0].trim(); cr.pass = sp.length > 1 ? sp[1] : ""; cr.type = cr.pass.isEmpty() ? "nopass" : "WPA";
                        joined = WifiJoiner.join(ctx, cr);
                    }
                    body = "{\"type\":\"wifi\",\"ok\":" + joined + "}"; break;
                }
                case "/snap": SnapshotService.set(ctx, "1".equals(param(q, "on")) || "true".equals(param(q, "on"))); body = ok(); break;
                case "/hotspot": {   // host a LocalOnlyHotspot (random creds) + show its WIFI QR for a remote to scan+join
                    if ("1".equals(param(q, "on")) || "true".equals(param(q, "on"))) HotspotService.start(ctx); else HotspotService.stop(ctx);
                    body = ok(); break;
                }
                case "/playmode": {   // toddler play mode; optional &vol=0..1 sets the (default-muted) audio gain.
                    // No &on at all = TOGGLE (same as /shuffle and /loop), so a remote can turn it off again.
                    String pmOn = param(q, "on");
                    if (pmOn == null ? !PlayerActivity.isPlayModeActive()
                                     : ("1".equals(pmOn) || "true".equals(pmOn))) {
                        String vol = param(q, "vol"); if (vol != null) PlayModeActivity.setGain(pf(vol, 0f));
                        PlayModeActivity.begin(ctx);
                    } else PlayModeActivity.end();
                    body = ok(); break;
                }
                case "/config": {   // read (no set) or write one setting — every tablet option, from a remote
                    String set = param(q, "set");
                    if (set != null) {
                        String val = param(q, "value"); val = (val == null) ? "" : urlDecode(val);
                        Cfg.applyConfig(ctx, set, val);
                        if ("anancy_loop".equals(set)) ctrl.setLoop("1".equals(val));   // live-apply loop (needs the player)
                        body = ok();
                    } else body = Cfg.configJson(ctx, true);
                    break;
                }
                case "/hold": ctrl.groupHold(param(q, "id"), "1".equals(param(q, "on"))); body = ok(); break;   // a synced follower is buffering
                case "/playlists": body = ctrl.playlistsJson(); break;
                // A remote hands this TV another TV's key (sealed with OUR key), so it can stream from it.
                case "/grant": body = grantHost(param(q, "ip"), param(q, "id"), param(q, "key")) ? ok() : "{\"ok\":false}"; break;
                case "/playlist": { String n = param(q, "name"); if (n != null) { PlayerActivity.wakeScreen(); ctrl.selectPlaylist(urlDecode(n)); } body = ok(); break; }
                default: body = "{\"ok\":false,\"error\":\"unknown path\"}";
            }
            write(c, body);
        } catch (Exception e) { Log.e(TAG, "handle err", e); }
        finally { try { c.close(); } catch (Exception ignored) {} }
    }

    // Phone -> tablet file import (Wi-Fi): POST /upload?name=<file>&sha=<sha256 of the body> with the raw
    // bytes as the body. The sha is inside the signed query, so the body can't be swapped in transit.
    // Videos only, never over an existing file, never into the last 1 GB of space.
    static final long KEEP_FREE = 1024L * 1024 * 1024;
    private void handleUpload(String name, String sha, String headers, InputStream in, Socket c) {
        java.io.File tmp = null;
        try {
            if (name == null || name.isEmpty()) name = "upload_" + System.currentTimeMillis() + ".mp4";
            name = urlDecode(name).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
            if (name.startsWith(".")) name = "_" + name;
            if (!name.toLowerCase().matches(".*\\.(mp4|mkv|m4v)$")) { write(c, 400, "{\"ok\":false,\"error\":\"only .mp4, .mkv or .m4v\"}"); return; }
            if (sha == null || !sha.matches("[0-9a-f]{64}")) { write(c, 400, "{\"ok\":false,\"error\":\"no checksum\"}"); return; }
            long len = -1;
            for (String line : headers.split("\r\n"))
                if (line.toLowerCase().startsWith("content-length:")) { try { len = Long.parseLong(line.substring(15).trim()); } catch (Exception ignored) {} }
            java.io.File dir = Library.importDir(ctx);
            if (dir == null) { write(c, "{\"ok\":false,\"error\":\"no library dir\"}"); return; }
            if (len <= 0 || len > dir.getUsableSpace() - KEEP_FREE) { write(c, 400, "{\"ok\":false,\"error\":\"not enough room on the TV\"}"); return; }
            java.io.File out = new java.io.File(dir, name);
            for (int n = 2; out.exists(); n++) {   // never overwrite: "name (2).mp4"
                int dot = name.lastIndexOf('.');
                out = new java.io.File(dir, name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
            }
            tmp = new java.io.File(dir, ".incoming-" + System.nanoTime());
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            long remaining = len;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) { fos.write(buf, 0, n); md.update(buf, 0, n); remaining -= n; }
            }
            StringBuilder got = new StringBuilder();
            for (byte x : md.digest()) got.append(String.format("%02x", x));
            if (remaining > 0 || !PairCrypto.same(got.toString(), sha)) {
                Log.w(TAG, "upload refused: " + (remaining > 0 ? "cut short" : "checksum mismatch"));
                write(c, 400, "{\"ok\":false,\"error\":\"the file arrived damaged\"}");
                return;
            }
            if (!tmp.renameTo(out)) { write(c, "{\"ok\":false,\"error\":\"couldn't save\"}"); return; }
            tmp = null;
            Log.i(TAG, "imported " + out.getName() + " (" + len + " bytes)");
            PlayerController.get().rescan(ctx);   // new file shows up in the Imports playlist
            write(c, "{\"ok\":true,\"saved\":\"" + out.getName().replace("\"", "") + "\",\"bytes\":" + len + "}");
        } catch (Exception e) { Log.e(TAG, "upload err", e); try { write(c, "{\"ok\":false}"); } catch (Exception ignored) {} }
        finally { if (tmp != null) tmp.delete(); }
    }

    // --- camera-monitor plugin: periodically-updated still images (off by default) ---
    /** Wi-Fi LIVE tier: MJPEG (multipart/x-mixed-replace) of the full-res still at ~8fps while open.
        Bumps SnapshotService to the live rate on connect, drops it on disconnect. Long-lived — runs on
        this connection's own handler thread, so it never blocks accept(). */
    private void handleLive(Socket c) {
        SnapshotService.setLive(ctx, true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=anancyframe\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            while (run) {
                byte[] jpeg = SnapshotService.latestSnapshot();
                if (jpeg != null) {
                    os.write(("--anancyframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(jpeg);
                    os.write("\r\n".getBytes("UTF-8"));
                    os.flush();
                }
                Thread.sleep(120);
            }
        } catch (Exception ignored) {   // client disconnected / stream closed
        } finally {
            SnapshotService.setLive(ctx, false);
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    // Stream a library file to a client tablet, honouring a Range request so ExoPlayer can seek/scrub.
    // The id is the library's stable file id (same one /library and /enqueue use).
    private void handleMedia(Socket c, String idStr, String headers) throws Exception {
        OutputStream os = c.getOutputStream();
        java.io.File f = null;
        try { if (idStr != null) f = ctrl.fileForId(Integer.parseInt(idStr.trim())); } catch (Exception ignored) {}
        if (f == null || !f.isFile()) {
            os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            os.flush(); return;
        }
        long len = f.length(), start = 0, end = len - 1;
        boolean partial = false;
        for (String hl : headers.split("\r\n")) {
            if (hl.toLowerCase().startsWith("range:") && hl.indexOf('=') >= 0) {
                String r = hl.substring(hl.indexOf('=') + 1).trim();
                int dash = r.indexOf('-');
                if (dash >= 0) try {
                    String s0 = r.substring(0, dash).trim(), s1 = r.substring(dash + 1).trim();
                    if (!s0.isEmpty()) start = Long.parseLong(s0);
                    if (!s1.isEmpty()) end = Long.parseLong(s1);
                    partial = true;
                } catch (Exception ignored) {}
                break;
            }
        }
        if (start < 0) start = 0;
        if (end >= len) end = len - 1;
        if (start > end) { start = 0; end = len - 1; partial = false; }
        long clen = end - start + 1;
        StringBuilder hdr = new StringBuilder();
        hdr.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        if (partial) hdr.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
        hdr.append("Content-Type: ").append(mediaType(f.getName())).append("\r\n")
           .append("Accept-Ranges: bytes\r\n")
           .append("Content-Length: ").append(clen).append("\r\n")
           .append("Connection: close\r\n\r\n");
        os.write(hdr.toString().getBytes("UTF-8"));
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = clen; int n;
            while (remaining > 0 && (n = raf.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                os.write(buf, 0, n); remaining -= n;
            }
            os.flush();
        } catch (Exception ignored) {   // client seeked/closed mid-stream — normal for video
        } finally { try { raf.close(); } catch (Exception ignored) {} }
    }
    private static String mediaType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        return "application/octet-stream";
    }

    private void writeJpeg(Socket c, byte[] data) throws Exception {
        OutputStream os = c.getOutputStream();
        if (data == null) {   // snapshots off or not ready yet
            os.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            os.flush(); return;
        }
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(data); os.flush();
    }

    /** Store another TV's key that a paired remote sealed for us; true when it opened cleanly. */
    private boolean grantHost(String ip, String id, String sealed) {
        if (id == null || sealed == null) return false;
        String key = app.anancy.Pairing.openMine(ctx, urlDecode(sealed));
        if (key == null) { Log.w(TAG, "grant: couldn't open the key for " + id); return false; }
        id = urlDecode(id);
        app.anancy.Pairing.remember(ctx, id, "", key);
        if (ip != null) app.anancy.Pairing.noteHost(ctx, urlDecode(ip), id);
        return true;
    }

    /** One request header's value (case-insensitive name), or null. */
    private static String header(String headers, String name) {
        String want = name.toLowerCase() + ":";
        for (String line : headers.split("\r\n"))
            if (line.toLowerCase().startsWith(want)) return line.substring(want.length()).trim();
        return null;
    }

    private static String ok() { return "{\"ok\":true}"; }
    private static String param(String q, String key) {
        for (String kv : q.split("&")) { int e = kv.indexOf('='); if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1); }
        return null;
    }
    private static float pf(String s, float dflt) { if (s == null) return dflt; try { return Float.parseFloat(s.trim()); } catch (Exception e) { return dflt; } }
    private static String urlDecode(String s) { try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private void write(Socket c, String body) throws Exception { write(c, 200, body); }
    private void write(Socket c, int status, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        String reason = status == 200 ? " OK" : status == 401 ? " Unauthorized" : " Bad Request";
        long now = System.currentTimeMillis();
        // The answer is MACed against the request's nonce: a client believes a body, an address or a
        // clock offset only from the TV it is paired with (status + time ride in the MAC too).
        String n = REQ_NONCE.get(), mac = "";
        if (n != null) mac = "\r\n" + PairCrypto.RESP_HEADER + ": " + PairCrypto.responseMac(app.anancy.Pairing.myKey(ctx), n,
                status + "|" + now + "|" + PairCrypto.sha256Hex(data));
        os.write(("HTTP/1.1 " + status + reason + "\r\nContent-Type: application/json\r\n" + PairCrypto.TIME_HEADER + ": " + now + mac + "\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(data); os.flush();
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName(Identity.name(ctx));   // was a fixed "Anancy": two tablets looked identical on the LAN
            si.setServiceType("_anancy._tcp");
            si.setPort(PORT);
            // TXT record so a remote can list + pick a tablet before connecting. NSD renames a clashing
            // service name ("Anancy (2)"), so the id — not the name — is what identifies a player.
            try {
                si.setAttribute("id", Identity.id(ctx));
                si.setAttribute("name", Identity.name(ctx));
            } catch (Exception ignored) {}
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
