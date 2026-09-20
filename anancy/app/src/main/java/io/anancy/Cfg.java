package io.anancy;

import io.anancy.net.HotspotService;

// ============================================================================
//  Kiosk configuration store. The on-device Settings screen writes app prefs;
//  Settings.Global (adb `settings put global anancy_*`) stays as a fallback for
//  dev overrides — the app itself can't write Global (WRITE_SECURE_SETTINGS).
//  Precedence: prefs (if the key was ever set on-device) > Settings.Global > default.
//
//  CONFIG SYNC: configJson()/applyConfig() expose EVERY setting so a remote can
//  read+write them the same as the on-tablet Settings screen (over HTTP /config
//  and BLE "cfg"). The PIN is never exposed (masked to "" + pin_set flag).
// ============================================================================

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

public class Cfg {
    static final String PREFS = "anancy";

    // {key, type("int"|"str"), default} — the full set a remote can read/write.
    static final String[][] KEYS = {
            {"anancy_name",          "str", ""},     // this player's friendly name ("" = fall back to the model); see Identity
            {"anancy_flash_ms",      "int", "3000"},
            {"anancy_loop",          "int", "1"},
            {"anancy_conn",          "str", "ble"},
            {"anancy_playmode_sound","str", "muted"},
            {"anancy_esc_fingers",   "int", "4"},
            {"anancy_esc_mode",      "str", "taps"},
            {"anancy_esc_taps",      "int", "4"},
            {"anancy_esc_window_ms", "int", "3000"},
            {"anancy_esc_dir",       "str", "up"},
            {"anancy_kiosk",         "int", "0"},
            {"anancy_pin",           "str", "1234"},
            {"anancy_unlock_style",  "str", "challenge"},   // default: solve a random timed code (no PIN to remember); "pin"/"pattern" are options
            {"anancy_pattern",       "str", ""},
            {"anancy_chal_len",      "int", "6"},   // Randomized PIN: number of digits shown on screen
            {"anancy_chal_secs",     "int", "30"},  // Randomized PIN: seconds allowed to enter it
            {"anancy_chal_tries",    "int", "3"},   // Randomized PIN: wrong attempts allowed before it drops back to locked
            {"anancy_host",          "str", ""},    // CLIENT mode: host tablet's IP to stream the library from ("" = standalone/host)
            {"anancy_sync",          "int", "0"},   // FOLLOWER: 1 = mirror the host's item+position (synced viewing); 0 = independent
            {"anancy_sync_mute",     "int", "0"},   // FOLLOWER: 1 = stay silent while syncing (leader-only audio); 0 = play own audio
    };
    private static String[] meta(String key) { for (String[] k : KEYS) if (k[0].equals(key)) return k; return null; }

    public static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static int getInt(Context c, String k, int d) {
        try { if (p(c).contains(k)) return p(c).getInt(k, d); } catch (Exception ignored) {}
        try { return Settings.Global.getInt(c.getContentResolver(), k, d); } catch (Exception e) { return d; }
    }
    public static String getStr(Context c, String k, String d) {
        try { if (p(c).contains(k)) { String v = p(c).getString(k, d); return (v == null || v.isEmpty()) ? d : v; } } catch (Exception ignored) {}
        try { String x = Settings.Global.getString(c.getContentResolver(), k); return (x == null || x.isEmpty()) ? d : x; } catch (Exception e) { return d; }
    }
    public static void put(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }
    public static void put(Context c, String k, String v) { p(c).edit().putString(k, v).apply(); }

    // --- config sync -----------------------------------------------------------------------------
    /** {"type":"config","device_owner":bool,"pin_set":bool,"values":{key:val,...}} — PIN masked. */
    public static String configJson(Context c, boolean incdo) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "config");
            boolean owner = false;
            if (incdo) {
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    owner = dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
                } catch (Exception ignored) {}
            }
            o.put("device_owner", owner);
            JSONObject vals = new JSONObject();
            boolean pinSet = false;
            for (String[] k : KEYS) {
                String name = k[0], type = k[1], def = k[2];
                if ("anancy_pin".equals(name)) {
                    String pin = getStr(c, name, def);
                    pinSet = pin != null && !pin.isEmpty();
                    vals.put(name, "");   // never expose the real PIN
                } else if ("int".equals(type)) {
                    vals.put(name, getInt(c, name, Integer.parseInt(def)));
                } else {
                    vals.put(name, getStr(c, name, def));
                }
            }
            o.put("pin_set", pinSet);
            o.put("values", vals);
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** Write one setting (typed), ignoring unknown keys, and LIVE-APPLY the ones a running player needs
        that don't require PlayerController (conn -> HotspotService). anancy_loop is applied by the caller
        (ControlServer/BleServer have the PlayerController). */
    public static void applyConfig(Context c, String key, String value) {
        String[] m = meta(key);
        if (m == null || value == null) return;   // only known keys — a remote can't write arbitrary prefs
        if ("anancy_pin".equals(key)) {
            if (!value.matches("\\d{4,}")) return;   // PIN = at least 4 digits
            put(c, key, value); return;
        }
        if ("int".equals(m[1])) { try { put(c, key, Integer.parseInt(value.trim())); } catch (Exception e) { return; } }
        else put(c, key, value);
        if ("anancy_conn".equals(key)) { try { HotspotService.applyMode(c, value); } catch (Exception ignored) {} }
    }
}
