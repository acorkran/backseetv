package com.kidstv.kidodi;

// ============================================================================
//  Kiosk configuration store. The on-device Settings screen writes app prefs;
//  Settings.Global (adb `settings put global kidodi_*`) stays as a fallback for
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

class Cfg {
    static final String PREFS = "kidodi";

    // {key, type("int"|"str"), default} — the full set a remote can read/write.
    static final String[][] KEYS = {
            {"kidodi_name",          "str", ""},     // this player's friendly name ("" = fall back to the model); see Identity
            {"kidodi_flash_ms",      "int", "3000"},
            {"kidodi_loop",          "int", "1"},
            {"kidodi_conn",          "str", "ble"},
            {"kidodi_playmode_sound","str", "muted"},
            {"kidodi_esc_fingers",   "int", "4"},
            {"kidodi_esc_mode",      "str", "taps"},
            {"kidodi_esc_taps",      "int", "4"},
            {"kidodi_esc_window_ms", "int", "3000"},
            {"kidodi_esc_dir",       "str", "up"},
            {"kidodi_kiosk",         "int", "0"},
            {"kidodi_pin",           "str", "1234"},
            {"kidodi_unlock_style",  "str", "challenge"},   // default: solve a random timed code (no PIN to remember); "pin"/"pattern" are options
            {"kidodi_pattern",       "str", ""},
            {"kidodi_chal_len",      "int", "6"},   // Randomized PIN: number of digits shown on screen
            {"kidodi_chal_secs",     "int", "30"},  // Randomized PIN: seconds allowed to enter it
            {"kidodi_chal_tries",    "int", "3"},   // Randomized PIN: wrong attempts allowed before it drops back to locked
            {"kidodi_host",          "str", ""},    // CLIENT mode: host tablet's IP to stream the library from ("" = standalone/host)
            {"kidodi_sync",          "int", "0"},   // FOLLOWER: 1 = mirror the host's item+position (synced viewing); 0 = independent
    };
    private static String[] meta(String key) { for (String[] k : KEYS) if (k[0].equals(key)) return k; return null; }

    static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static int getInt(Context c, String k, int d) {
        try { if (p(c).contains(k)) return p(c).getInt(k, d); } catch (Exception ignored) {}
        try { return Settings.Global.getInt(c.getContentResolver(), k, d); } catch (Exception e) { return d; }
    }
    static String getStr(Context c, String k, String d) {
        try { if (p(c).contains(k)) { String v = p(c).getString(k, d); return (v == null || v.isEmpty()) ? d : v; } } catch (Exception ignored) {}
        try { String x = Settings.Global.getString(c.getContentResolver(), k); return (x == null || x.isEmpty()) ? d : x; } catch (Exception e) { return d; }
    }
    static void put(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }
    static void put(Context c, String k, String v) { p(c).edit().putString(k, v).apply(); }

    // --- config sync -----------------------------------------------------------------------------
    /** {"type":"config","device_owner":bool,"pin_set":bool,"values":{key:val,...}} — PIN masked. */
    static String configJson(Context c, boolean incdo) {
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
                if ("kidodi_pin".equals(name)) {
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
        that don't require PlayerController (conn -> HotspotService). kidodi_loop is applied by the caller
        (ControlServer/BleServer have the PlayerController). */
    static void applyConfig(Context c, String key, String value) {
        String[] m = meta(key);
        if (m == null || value == null) return;   // only known keys — a remote can't write arbitrary prefs
        if ("kidodi_pin".equals(key)) {
            if (!value.matches("\\d{4,}")) return;   // PIN = at least 4 digits
            put(c, key, value); return;
        }
        if ("int".equals(m[1])) { try { put(c, key, Integer.parseInt(value.trim())); } catch (Exception e) { return; } }
        else put(c, key, value);
        if ("kidodi_conn".equals(key)) { try { HotspotService.applyMode(c, value); } catch (Exception ignored) {} }
    }
}
