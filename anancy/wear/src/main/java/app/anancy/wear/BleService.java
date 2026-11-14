package app.anancy.wear;

// ============================================================================
//  Foreground service that owns the BLE link to the tablet-player. ON DEMAND:
//  the link (scan / connect / notifications) only runs while something is
//  actually using it — the remote activity is resumed, the Tile is on screen —
//  plus a short linger so flicking between them doesn't reconnect. When the
//  last user releases, the client is stopped and the service exits. A 24/7
//  link on a watch (LOW_LATENCY scans, 1 Hz notifications) flattened a fully
//  charged Galaxy Watch overnight; a remote has no business being connected
//  while it sits on the nightstand.
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

import java.util.HashSet;
import java.util.Set;

public class BleService extends Service {
    public interface Listener { void onStatus(String s); void onState(String json); void onStill(byte[] jpeg); }
    static final String TAG = "AnancyWear";
    static final long LINGER_MS = 45_000;   // stay linked this long after the last user leaves

    private static BleService INSTANCE;
    public static BleService get() { return INSTANCE; }

    /** Something needs the link (tag = "ui", "tile", ...). Starts the service if needed. */
    public static void acquire(Context ctx, String tag) {
        BleService s = INSTANCE;
        if (s != null) { s.doAcquire(tag); return; }
        Intent i = new Intent(ctx.getApplicationContext(), BleService.class).putExtra("acquire", tag);
        if (Build.VERSION.SDK_INT >= 26) ctx.getApplicationContext().startForegroundService(i);
        else ctx.getApplicationContext().startService(i);
    }
    /** That user is done; the link lingers briefly then shuts down if nobody else needs it. */
    public static void release(Context ctx, String tag) { BleService s = INSTANCE; if (s != null) s.doRelease(tag); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> uses = new HashSet<>();
    private BleClient ble;
    private volatile String lastState = "{}";
    private volatile String lastPlaylists = null;   // the one-shot {"type":"playlists",...} payload
    private volatile byte[] lastStill = null;        // latest reassembled reduced-res still (BLE)
    private volatile String lastStatus = "starting…";
    private Listener ui;

    private static boolean isPlaylists(String j) { return j != null && j.contains("\"type\":\"playlists\""); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        startForeground(71, note());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String tag = intent != null ? intent.getStringExtra("acquire") : null;
        if (tag != null) doAcquire(tag);
        else if (uses.isEmpty()) scheduleIdle();   // started with no user (shouldn't happen): don't linger forever
        return START_NOT_STICKY;                   // never resurrect ourselves into an idle 24/7 link
    }

    private void doAcquire(String tag) {
        uses.add(tag);
        main.removeCallbacks(idle);
        if (ble == null) {
            ble = new BleClient(this, new BleClient.Listener() {
                public void onStatus(String s) { lastStatus = s; if (ui != null) ui.onStatus(s); }
                public void onState(String json) {
                    if (isPlaylists(json)) lastPlaylists = json;
                    else if (!json.contains("\"type\":\"queue\"")) lastState = json;   // queue pages aren't now-playing state
                    if (ui != null) ui.onState(json);
                }
                public void onStill(byte[] jpeg) { lastStill = jpeg; if (ui != null) ui.onStill(jpeg); }
            });
        }
        ble.setAllowed(WatchTarget.allowed(this));
        ble.start();   // idempotent while scanning/connected
        WatchTarget.refresh(this);   // the phone may have re-aimed us while we weren't running
        WatchKeys.refresh(this);     // ...or paired with a new TV
    }

    /** The phone changed what this watch should drive (WatchTarget). */
    void onTargetChanged() { main.post(() -> { if (ble != null) ble.setAllowed(WatchTarget.allowed(this)); }); }
    private void doRelease(String tag) {
        uses.remove(tag);
        if (uses.isEmpty()) scheduleIdle();
    }
    private void scheduleIdle() { main.removeCallbacks(idle); main.postDelayed(idle, LINGER_MS); }
    private final Runnable idle = () -> {
        if (!uses.isEmpty()) return;
        Log.i(TAG, "link idle -> stopping");
        if (ble != null) { ble.stop(); ble = null; }
        lastStatus = "idle";
        stopForeground(true);
        stopSelf();
    };

    public void setListener(Listener l) {
        ui = l;
        if (l != null) {   // immediate paint from cache
            l.onStatus(lastStatus);
            l.onState(lastState);
            if (lastPlaylists != null) l.onState(lastPlaylists);
            if (lastStill != null) l.onStill(lastStill);
        }
    }
    public void send(final String cmd) {
        // When the phone remote drives several screens, a watch tap goes through the phone so it reaches
        // all of them, like a tap on the phone. Queries for this watch's own view stay on our own link,
        // and if the phone can't take it the command goes out over Bluetooth as before.
        if (!WatchTarget.relay(this) || isQuery(cmd)) { direct(cmd); return; }
        final android.content.Context c = getApplicationContext();
        final long tapAt = android.os.SystemClock.elapsedRealtime();
        relayPool.execute(() -> {
            boolean ok = false, waitingOnPhone = false;
            try {
                java.util.List<com.google.android.gms.wearable.Node> nodes = com.google.android.gms.tasks.Tasks.await(
                        com.google.android.gms.wearable.Wearable.getNodeClient(c).getConnectedNodes(), 2, java.util.concurrent.TimeUnit.SECONDS);
                for (com.google.android.gms.wearable.Node n : nodes) {
                    waitingOnPhone = true;
                    long t0 = android.os.SystemClock.elapsedRealtime();   // timing only: where a tap's delay goes
                    Log.i(TAG, "timing: relay " + cmd + " sent at " + System.currentTimeMillis() + " (" + (t0 - tapAt) + " ms after the tap)");
                    byte[] r = com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getMessageClient(c)
                            .sendRequest(n.getId(), WatchTarget.PATH_CMD, cmd.getBytes()), 3, java.util.concurrent.TimeUnit.SECONDS);
                    Log.i(TAG, "timing: relay " + cmd + " phone answered in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                    waitingOnPhone = false;
                    if (r != null && "ok".equals(new String(r))) { ok = true; break; }
                }
            } catch (Exception e) {
                Log.w(TAG, "relay failed (" + cmd + "): " + e);
                // A late phone still runs it, so sending it here too would double a next or undo a toggle.
                if (waitingOnPhone && e instanceof java.util.concurrent.TimeoutException) return;
            }
            if (!ok) main.post(() -> direct(cmd));
        });
    }
    /** Asks for this watch's own screens (playlists, queue pages, camera stills): answered on our own link. */
    private static boolean isQuery(String c) {
        return c.equals("list") || c.startsWith("list:") || c.equals("queue") || c.startsWith("queue:") || c.startsWith("camble:");
    }
    private final java.util.concurrent.ExecutorService relayPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    private void direct(String cmd) { if (ble != null) ble.send(cmd); }
    public String state() { return lastState; }
    public String playlists() { return lastPlaylists; }
    public byte[] latestStill() { return lastStill; }

    @Override public void onDestroy() {
        main.removeCallbacks(idle);
        if (ble != null) { ble.stop(); ble = null; }
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private Notification note() {
        String ch = "anancy_ble";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Anancy remote", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Anancy remote").setContentText("linked to the TV")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }
}
