package app.anancy.net;

import app.anancy.Cfg;
import app.anancy.admin.AdminReceiver;

// ============================================================================
//  Local-only Wi-Fi hotspot for the car (no shared network). Android's
//  startLocalOnlyHotspot() gives a RANDOM ssid + passphrase each time — which
//  used to be the dealbreaker. It isn't anymore: we just render those random
//  creds as a standard WIFI: QR (QrShareActivity) and the remotes scan+join.
//  The reservation is held by this service; stop() tears the hotspot down.
//  NOTE: needs NEARBY_WIFI_DEVICES (API 33+) / location permission + location on (pre-33)
//  + CHANGE_WIFI_STATE; as device owner we grant these to ourselves so there's no prompt.
// ============================================================================

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class HotspotService extends Service {
    static final String TAG = "Anancy";
    private static HotspotService INSTANCE;
    private static volatile String ssid, pass;
    public static boolean running() { return INSTANCE != null; }
    public static String currentSsid() { return ssid; }
    public static String currentPass() { return pass; }

    /** Start the hotspot AND pop its QR (the phone-driven "hotspot:on" flow expects the QR on the TV). */
    public static void start(Context ctx) { start(ctx, true); }
    public static void start(Context ctx, boolean showQr) {
        grantNearbyWifi(ctx);
        ctx.getApplicationContext().startService(new Intent(ctx.getApplicationContext(), HotspotService.class).putExtra("qr", showQr));
    }
    public static void stop(Context ctx) {
        ctx.getApplicationContext().stopService(new Intent(ctx.getApplicationContext(), HotspotService.class));
    }

    // --- connectivity level (Cfg anancy_conn): ble | tv_hotspot | remote_hotspot | shared ---------
    static final String[] MODES = {"ble", "tv_hotspot", "remote_hotspot", "shared"};
    public static String mode(Context ctx) {
        String m = Cfg.getStr(ctx, "anancy_conn", "ble");
        for (String k : MODES) if (k.equals(m)) return m;
        return "ble";
    }
    /** Persist + apply a level: tv_hotspot keeps the TV's hotspot up (silently — the creds ride in
        state for remotes to auto-join; Settings has "Show QR"); every other level tears it down. */
    public static void applyMode(Context ctx, String m) {
        boolean known = false;
        for (String k : MODES) if (k.equals(m)) known = true;
        if (!known) return;
        Cfg.put(ctx, "anancy_conn", m);
        if ("tv_hotspot".equals(m)) { if (!running()) start(ctx, false); }
        else if (running()) stop(ctx);
    }

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean showQr = true;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        showQr = intent == null || intent.getBooleanExtra("qr", true);
        if (reservation == null) startHotspot();
        return START_STICKY;
    }
    @Override public void onCreate() { super.onCreate(); INSTANCE = this; }

    private void startHotspot() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { stopSelf(); return; }
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    readCreds(res);
                    Log.i(TAG, "hotspot up: '" + ssid + "'");
                    if (ssid != null && showQr) showQr();
                }
                @Override public void onStopped() { Log.i(TAG, "hotspot stopped"); ssid = pass = null; }
                @Override public void onFailed(int reason) { Log.e(TAG, "hotspot failed: " + reason); stopSelf(); }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) { Log.e(TAG, "startLocalOnlyHotspot: " + e); stopSelf(); }
    }

    @SuppressWarnings("deprecation")
    private void readCreds(WifiManager.LocalOnlyHotspotReservation res) {
        try {
            if (Build.VERSION.SDK_INT >= 30 && res.getSoftApConfiguration() != null) {
                ssid = res.getSoftApConfiguration().getSsid();
                pass = res.getSoftApConfiguration().getPassphrase();
            } else if (res.getWifiConfiguration() != null) {
                WifiConfiguration cfg = res.getWifiConfiguration();
                ssid = cfg.SSID != null ? cfg.SSID.replaceAll("^\"|\"$", "") : null;
                pass = cfg.preSharedKey != null ? cfg.preSharedKey.replaceAll("^\"|\"$", "") : null;
            }
        } catch (Exception e) { Log.w(TAG, "readCreds: " + e); }
    }

    /** Show the random creds as a standard WIFI: QR for a remote to scan + join. */
    private void showQr() {
        String type = (pass == null || pass.isEmpty()) ? "nopass" : "WPA";
        String payload = "WIFI:S:" + esc(ssid) + ";T:" + type + ";P:" + esc(pass == null ? "" : pass) + ";;";
        try {
            startActivity(new Intent(this, QrShareActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(QrShareActivity.EXTRA_PAYLOAD, payload)
                    .putExtra(QrShareActivity.EXTRA_CAPTION, "Scan on your phone/watch to join\n" + ssid));
        } catch (Exception e) { Log.w(TAG, "showQr: " + e); }
    }
    private static String esc(String s) { return s == null ? "" : s.replaceAll("([\\\\;,:\"])", "\\\\$1"); }

    /** Device owner grants itself what startLocalOnlyHotspot needs so there's no prompt: NEARBY_WIFI_DEVICES
        on API 33+; below that ACCESS_FINE_LOCATION, and location must be switched on. */
    private static void grantNearbyWifi(Context ctx) {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(ctx.getPackageName())) return;
            android.content.ComponentName admin = new android.content.ComponentName(ctx, AdminReceiver.class);
            String perm = Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : android.Manifest.permission.ACCESS_FINE_LOCATION;
            dpm.setPermissionGrantState(admin, ctx.getPackageName(), perm,
                    android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            if (Build.VERSION.SDK_INT < 33) {
                if (Build.VERSION.SDK_INT >= 30) dpm.setLocationEnabled(admin, true);
                else dpm.setSecureSetting(admin, android.provider.Settings.Secure.LOCATION_MODE,
                        String.valueOf(android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
            }
        } catch (Exception e) { Log.w(TAG, "grantNearbyWifi: " + e); }
    }

    @Override public void onDestroy() {
        try { if (reservation != null) reservation.close(); } catch (Exception ignored) {}
        reservation = null; ssid = pass = null;
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }
}
