package com.kidstv.kidodi;

// ============================================================================
//  Kiosk configuration store. The on-device Settings screen writes app prefs;
//  Settings.Global (adb `settings put global kidodi_*`) stays as a fallback for
//  dev overrides — the app itself can't write Global (WRITE_SECURE_SETTINGS).
//  Precedence: prefs (if the key was ever set on-device) > Settings.Global > default.
// ============================================================================

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

class Cfg {
    static final String PREFS = "kidodi";

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
}
