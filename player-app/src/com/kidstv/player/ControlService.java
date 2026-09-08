package com.kidstv.player;

// ============================================================================
//  In-process control server. Same HTTP shape the remotes/AA already speak, but
//  it calls the PlayerEngine DIRECTLY -- there is no Kodi and no 9090 JSON-RPC
//  behind it. This is the "3 apps -> 1" collapse: the bridge and the player are
//  the same process now. Listens on :8090 (kiosk uses :8080; distinct so both
//  can coexist during testing -- production reclaims :8080).
//    GET/POST /state            -> engine state json
//             /playpause /play /pause /next /prev
//             /vol?d=0.1        -> nudge volume (or /vol?v=0.5 absolute)
//             /sleep?min=10     -> start sleep fade (min<=0 cancels)
//             /flash?ms=3000    -> screen-as-flashlight
//             /p2p?on=1         -> toggle the Wi-Fi Direct anchor
// ============================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class ControlService extends Service {
    static final String TAG = "KidodiPlayer";
    static final int PORT = 8090;
    private volatile ServerSocket mServer;
    private volatile boolean mRun = false;
    private PlayerEngine mEngine;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(51, note());
        mEngine = PlayerEngine.get(this);
        if (!mRun) { mRun = true; new Thread(new Runnable(){ public void run(){ serve(); } }).start(); }
        return START_STICKY;
    }

    private void serve() {
        try {
            mServer = new ServerSocket();
            mServer.setReuseAddress(true);
            mServer.bind(new java.net.InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "control server on :" + PORT);
            while (mRun) {
                final Socket c = mServer.accept();
                new Thread(new Runnable(){ public void run(){ handle(c); } }).start();
            }
        } catch (Exception e) {
            if (mRun) Log.e(TAG, "serve() died", e);
        }
    }

    private void handle(Socket c) {
        try {
            InputStream in = c.getInputStream();
            StringBuilder head = new StringBuilder();
            int b, prevNl = 0;
            while ((b = in.read()) != -1) {          // read request line + headers only
                head.append((char) b);
                if (b == '\n') { if (++prevNl == 2) break; } else if (b != '\r') prevNl = 0;
            }
            String req = head.toString();
            String line = req.length() > 0 ? req.split("\r\n", 2)[0] : "";
            String[] parts = line.split(" ");
            String target = parts.length >= 2 ? parts[1] : "/";
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            String body;
            if (path.equals("/state")) {
                body = mEngine.stateJson();
            } else if (path.equals("/diag"))      { body = mEngine.diagJson();
            } else if (path.equals("/boot"))      { mEngine.startHeadless(); body = ok();
            } else if (path.equals("/seeknear"))  { mEngine.seekNearEnd();   body = ok();
            } else if (path.equals("/playpause")) { mEngine.togglePlay(); body = ok();
            } else if (path.equals("/play"))      { mEngine.play();       body = ok();
            } else if (path.equals("/pause"))     { mEngine.pause();      body = ok();
            } else if (path.equals("/next"))      { mEngine.next();       body = ok();
            } else if (path.equals("/prev"))      { mEngine.prev();       body = ok();
            } else if (path.equals("/vol")) {
                String v = param(query, "v"), d = param(query, "d");
                if (v != null) mEngine.setVolume(parseF(v, 1f));
                else mEngine.nudgeVolume(parseF(d, 0.1f));
                body = ok();
            } else if (path.equals("/sleep")) {
                int min = (int) parseF(param(query, "min"), 0);
                if (min > 0) mEngine.startSleep(min); else mEngine.cancelSleep();
                body = ok();
            } else if (path.equals("/flash")) {
                PlayerActivity.flash((int) parseF(param(query, "ms"), 3000));
                body = ok();
            } else if (path.equals("/p2p")) {
                boolean on = !"0".equals(param(query, "on")) && !"false".equals(param(query, "on"));
                Intent pi = new Intent(this, P2pAnchorService.class);
                pi.setAction(on ? P2pAnchorService.ACTION_START : P2pAnchorService.ACTION_STOP);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(pi); else startService(pi);
                body = "{\"ok\":true,\"p2p\":" + on + "}";
            } else {
                body = "{\"ok\":false,\"error\":\"unknown path\"}";
            }
            write(c, body);
        } catch (Exception e) {
            Log.e(TAG, "handle() error", e);
        } finally {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private static String ok() { return "{\"ok\":true}"; }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1);
        }
        return null;
    }
    private static float parseF(String s, float dflt) {
        if (s == null) return dflt;
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return dflt; }
    }

    private void write(Socket c, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        os.write(hdr.getBytes("UTF-8"));
        os.write(data);
        os.flush();
    }

    private Notification note() {
        String ch = "kidodi_ctrl";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Kidodi control",
                    NotificationManager.IMPORTANCE_MIN));
        Notification.Builder bld = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return bld.setContentTitle("Kidodi player")
                .setContentText("control :" + PORT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).build();
    }

    @Override public void onDestroy() {
        mRun = false;
        try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
