package com.kidstv.kidodi.wear;

// ============================================================================
//  Foreground service that owns the BLE link to the tablet-player. Living in a
//  service (not the activity) means the connection + auto-reconnect survive the
//  watch screen sleeping, the activity being backgrounded, and lets a Tile use
//  the same live connection. Exposes a singleton + a swappable UI listener +
//  send(); it keeps the latest state so a freshly-opened UI/Tile shows it at once.
// ============================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class BleService extends Service {
    public interface Listener { void onStatus(String s); void onState(String json); }

    private static BleService INSTANCE;
    public static BleService get() { return INSTANCE; }

    /** Convenience: ensure the service is running (starts it if needed). */
    public static void ensure(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), BleService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.getApplicationContext().startForegroundService(i);
        else ctx.getApplicationContext().startService(i);
    }

    private BleClient ble;
    private volatile String lastState = "{}";
    private volatile String lastStatus = "starting…";
    private Listener ui;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        startForeground(71, note());
        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) { lastStatus = s; if (ui != null) ui.onStatus(s); }
            public void onState(String json) { lastState = json; if (ui != null) ui.onState(json); }
        });
        ble.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    public void setListener(Listener l) {
        ui = l;
        if (l != null) { l.onStatus(lastStatus); l.onState(lastState); }   // immediate paint
    }
    public void send(String cmd) { if (ble != null) ble.send(cmd); }
    public String state() { return lastState; }

    @Override public void onDestroy() {
        if (ble != null) { ble.stop(); ble = null; }
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private Notification note() {
        String ch = "kidodi_ble";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Kidodi remote", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Kidodi remote").setContentText("connected to the TV")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    }
}
