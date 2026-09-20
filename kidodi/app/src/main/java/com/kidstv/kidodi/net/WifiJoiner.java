package com.kidstv.kidodi.net;

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
    static final String TAG = "Kidodi";
    private WifiJoiner() {}

    public static class Creds { public String ssid, type, pass; }

    /** Parse a standard Wi-Fi QR: WIFI:S:<ssid>;T:<WPA|WEP|nopass>;P:<pass>;; with \-escaping of \ ; , : " */
    static Creds parseWifiQr(String s) {
        if (s == null || !s.regionMatches(true, 0, "WIFI:", 0, 5)) return null;
        Creds c = new Creds();
        for (String field : splitUnescaped(s.substring(5), ';')) {
            if (field.length() < 2 || field.charAt(1) != ':') continue;
            char k = Character.toUpperCase(field.charAt(0));
            String v = unescape(field.substring(2));
            if (k == 'S') c.ssid = v;
            else if (k == 'T') c.type = v;
            else if (k == 'P') c.pass = v;
        }
        return (c.ssid != null && !c.ssid.isEmpty()) ? c : null;
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
                if (id >= 0) { wm.enableNetwork(id, true); wm.reconnect(); Log.i(TAG, "wifi join (DO) '" + c.ssid + "' -> " + id); return true; }
                Log.w(TAG, "wifi join (DO) addNetwork failed for '" + c.ssid + "'");
                return false;
            }
        } catch (Exception e) { Log.w(TAG, "wifi join (DO): " + e); }

        // NON-DEVICE-OWNER: a suggestion (API 29+). System decides / may prompt. Cannot force.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder().setSsid(c.ssid);
                if (!open) b.setWpa2Passphrase(c.pass);
                List<WifiNetworkSuggestion> sug = new ArrayList<>();
                sug.add(b.build());
                wm.removeNetworkSuggestions(sug);   // clear a stale one first
                int status = wm.addNetworkSuggestions(sug);
                Log.i(TAG, "wifi suggestion (non-DO) '" + c.ssid + "' -> status " + status + " (system decides / may prompt)");
                return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
            }
        } catch (Exception e) { Log.w(TAG, "wifi suggestion (non-DO): " + e); }
        return false;
    }

    // --- WIFI-QR escaping helpers (backslash escapes \ ; , : ") ---
    private static List<String> splitUnescaped(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { cur.append(ch).append(s.charAt(++i)); }   // keep escape, unescaped later
            else if (ch == sep) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
            else out.append(ch);
        }
        return out.toString();
    }
}
