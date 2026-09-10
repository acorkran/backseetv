package com.kidstv.kidodi;

// ============================================================================
//  Phone-side connection service (foreground). Owns BOTH transports and picks:
//  BLE is primary; if BLE hasn't connected in ~7s, it also brings up the Wi-Fi/
//  mDNS fallback and uses whichever is live (preferring BLE). send() routes to
//  the active transport; state() is the latest reported state. Shared by the
//  phone RemoteActivity AND the Android Auto media service.
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

public class RemoteService extends Service {
    public interface Listener { void onStatus(String s); void onState(String json); }

    private static RemoteService INSTANCE;
    public static RemoteService get() { return INSTANCE; }
    public static void ensure(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), RemoteService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.getApplicationContext().startForegroundService(i);
        else ctx.getApplicationContext().startService(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private BleClient ble;
    private WifiClient wifi;
    private volatile String lastState = "{}", lastStatus = "starting…", transport = "—";
    private volatile java.util.List<String> playlistNames = new java.util.ArrayList<>();
    private Listener ui;

    private static boolean isPlaylists(String json) { return json != null && json.contains("\"type\":\"playlists\""); }
    private void cachePlaylists(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONObject(json).optJSONArray("names");
            java.util.List<String> out = new java.util.ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            playlistNames = out;
        } catch (Exception ignored) {}
    }
    public java.util.List<String> playlistNames() { return playlistNames; }
    public void requestPlaylists() { send("list"); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        startForeground(72, note());
        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) { lastStatus = s; if (ui != null) ui.onStatus(s); }
            public void onState(String json) {
                if (isPlaylists(json)) { cachePlaylists(json); return; }
                lastState = json; transport = "BLE"; if (ui != null) ui.onState(json);
            }
        });
        ble.start();
        // Wi-Fi/mDNS fallback if BLE hasn't connected shortly
        main.postDelayed(() -> { if (ble == null || !ble.isConnected()) startWifi(); }, 7000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    private void startWifi() {
        if (wifi != null) return;
        wifi = new WifiClient(this, new WifiClient.Listener() {
            public void onStatus(String s) { if (!bleUp()) { lastStatus = s; if (ui != null) ui.onStatus(s); } }
            public void onState(String json) {
                if (isPlaylists(json)) { cachePlaylists(json); return; }
                if (!bleUp()) { lastState = json; transport = "Wi-Fi"; if (ui != null) ui.onState(json); }
            }
        });
        wifi.start();
    }

    private boolean bleUp() { return ble != null && ble.isConnected(); }

    public void send(String cmd) {
        if (bleUp()) ble.send(cmd);
        else if (wifi != null) wifi.send(cmd);
    }
    public String state() { return lastState; }

    // Latest reduced-res camera STILL (BLE-reassembled or Wi-Fi), shared with the AA dash + views.
    // The camera-consumer impl pushes frames via setLatestStill(); AA/others read latestStill().
    private static volatile byte[] sLatestStill = null;
    public static byte[] latestStill() { return sLatestStill; }
    public static void setLatestStill(byte[] jpeg) { sLatestStill = jpeg; }
    /** For Wi-Fi file import: a ONE-SHOT mDNS discovery of the player's host:port that does NOT
     *  leave a polling transport running (that redundant poller alongside BLE caused lag). Blocking,
     *  off-main; up to ~5s. Returns null if not found (not on the same network). */
    public String playerHttpHost() {
        WifiClient tmp = new WifiClient(this, new WifiClient.Listener() { public void onStatus(String s) {} public void onState(String s) {} });
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
    public String transport() { return bleUp() ? "BLE" : (wifi != null && wifi.isConnected() ? "Wi-Fi" : transport); }
    public void setListener(Listener l) { ui = l; if (l != null) { l.onStatus(lastStatus); l.onState(lastState); } }

    @Override public void onDestroy() {
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
