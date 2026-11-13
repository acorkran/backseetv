package app.anancy.net;

// ============================================================================
//  Join a Wi-Fi network from a scanned standard WIFI: QR. Two realities:
//   - DEVICE OWNER (the locked kiosk): we can add + enable + reconnect the
//     network directly and it JUST JOINS, silently — same trick as v1 /addwifi.
//   - NON-DEVICE-OWNER (the plain phone/remote app): we CANNOT force a silent
//     join. The best a normal app can do (API 29+) is post a WifiNetwork
//     SUGGESTION; the SYSTEM then decides whether/when to connect and may show
//     the user a prompt/notification. So on a non-owner device this is
//     best-effort — expect a one-tap confirm, not an instant join.
// ============================================================================

import app.anancy.Diag;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class WifiJoiner {
    static final String TAG = "Anancy";

    private static android.net.ConnectivityManager.NetworkCallback directCb;
    private static android.net.ConnectivityManager directCm;

    /** Join a hotspot that has NO internet, and route our sockets over it.
        A WifiNetworkSuggestion is only advisory: Android keeps the internet-bearing network and ignores
        it, which is why the car tier never engaged. A NetworkSpecifier request connects to THIS network
        and hands back a Network we bind the process to, so HTTP stops leaking to cellular. The system
        shows a one-time picker, so it must be driven from the foreground (the Connection screen button). */
    private static volatile String boundSsid = null;   // the hotspot this process is currently bound to
    private static volatile boolean directPending = false;   // a request is live and its prompt may be on screen
    /** True while a direct join is outstanding: re-issuing one now would cancel the prompt mid-tap. */
    public static boolean directPending() { return directPending; }
    /** The hotspot we are bound to right now, or null - the Wi-Fi tier is only live while this is set. */
    public static String boundSsid() { return boundSsid; }
    /** True once a direct join has actually worked on this phone - the signal that rejoining is wanted.
        It is NOT keyed to the SSID: a Samsung TV hotspot picks a new random SSID (and pass) every start,
        so an SSID match would never fire after the tablet reboots, which is exactly when we need it. */
    public static boolean directJoinKnown(Context ctx) {
        try { return app.anancy.Cfg.p(ctx).getString("direct_ssid", null) != null; } catch (Exception e) { return false; }
    }

    public static boolean joinDirect(Context ctx, Creds c, final Runnable onOk, final Runnable onFail) {
        if (c == null || c.ssid == null || Build.VERSION.SDK_INT < 29) return false;
        try {
            releaseDirect(ctx);
            android.net.wifi.WifiNetworkSpecifier.Builder b =
                    new android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(c.ssid);
            if (c.pass != null && !c.pass.isEmpty()) b.setWpa2Passphrase(c.pass);
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)   // the TV hotspot has none
                    .setNetworkSpecifier(b.build()).build();
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            directCm = cm;
            directCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    try { cm.bindProcessToNetwork(n); } catch (Exception ignored) {}
                    boundSsid = c.ssid; directPending = false;
                    try { app.anancy.Cfg.p(ctx).edit().putString("direct_ssid", c.ssid).apply(); } catch (Exception ignored) {}
                    Diag.log(TAG, "wifi joinDirect: bound to " + n);
                    if (onOk != null) onOk.run();
                }
                @Override public void onUnavailable() { boundSsid = null; directPending = false; Diag.warn(TAG, "wifi joinDirect: unavailable"); if (onFail != null) onFail.run(); }
                @Override public void onLost(android.net.Network n) {
                    boundSsid = null; directPending = false;
                    try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
                    Diag.log(TAG, "wifi joinDirect: lost, unbound");
                }
            };
            // NO timeout: the system dialog waits for a human, and a 30s request expired before the
            // user could tap Connect ("joinDirect: unavailable"). releaseDirect() ends it instead.
            cm.requestNetwork(req, directCb);
            directPending = true;
            return true;
        } catch (Exception e) { Diag.warn(TAG, "joinDirect: " + e); return false; }
    }

    /** Drop the request and unbind, so the phone goes back to its normal network. */
    public static void releaseDirect(Context ctx) {
        boundSsid = null; directPending = false;
        try { if (directCm != null) directCm.bindProcessToNetwork(null); } catch (Exception ignored) {}
        try { if (directCm != null && directCb != null) directCm.unregisterNetworkCallback(directCb); } catch (Exception ignored) {}
        directCb = null;
    }
    private WifiJoiner() {}

    public static class Creds { public String ssid, type, pass; }

    /** Parse a standard Wi-Fi QR: WIFI:S:<ssid>;T:<WPA|WEP|nopass>;P:<pass>;; with \-escaping of \ ; , : "
        (pure parsing lives in QrParse; this adapts the holder to Creds for join()). */
    static Creds parseWifiQr(String s) {
        QrParse.Wifi w = QrParse.parseWifi(s);
        if (w == null) return null;
        Creds c = new Creds();
        c.ssid = w.ssid; c.type = w.type; c.pass = w.pass;
        return c;
    }

    /** Join (or best-effort suggest) the network. Returns true if the request was accepted. */
    public static boolean join(Context ctx, Creds c) {
        if (c == null || c.ssid == null) return false;
        Context app = ctx.getApplicationContext();
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        boolean open = (c.pass == null || c.pass.isEmpty() || "nopass".equalsIgnoreCase(c.type));

        // DEVICE-OWNER: direct add + enable + connect (silent).
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(app.getPackageName())) {
                WifiConfiguration cfg = new WifiConfiguration();
                cfg.SSID = "\"" + c.ssid + "\"";
                if (open) cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                else cfg.preSharedKey = "\"" + c.pass + "\"";
                int id = wm.addNetwork(cfg);
                if (id >= 0) { wm.enableNetwork(id, true); wm.reconnect(); Diag.log(TAG, "wifi join (DO) '" + c.ssid + "' -> " + id); return true; }
                Diag.warn(TAG, "wifi join (DO) addNetwork failed for '" + c.ssid + "'");
                return false;
            }
        } catch (Exception e) { Diag.warn(TAG, "wifi join (DO): " + e); }

        // NON-DEVICE-OWNER: a suggestion (API 29+). System decides / may prompt. Cannot force.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder().setSsid(c.ssid);
                if (!open) b.setWpa2Passphrase(c.pass);
                List<WifiNetworkSuggestion> sug = new ArrayList<>();
                sug.add(b.build());
                wm.removeNetworkSuggestions(sug);   // clear a stale one first
                int status = wm.addNetworkSuggestions(sug);
                Diag.log(TAG, "wifi suggestion (non-DO) '" + c.ssid + "' -> status " + status + " (system decides / may prompt)");
                return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
            }
        } catch (Exception e) { Diag.warn(TAG, "wifi suggestion (non-DO): " + e); }
        return false;
    }
}
