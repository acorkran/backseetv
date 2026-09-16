package com.kidstv.kidodi;

// ============================================================================
//  Who this player IS. Every tablet used to announce itself as a fixed "Kidodi"
//  with no id at all, so a remote that could hear two of them attached to
//  whichever answered first — and could silently swap between them. Each player
//  now carries a stable random id plus a human-readable name, published in
//  /state, in the mDNS record and in the BLE scan response, so a remote can
//  list, name and pick one specific tablet.
// ============================================================================

import android.content.Context;

public final class Identity {
    static final String KEY_ID = "kidodi_device_id";   // generated once; not in Cfg.KEYS (a remote can't rewrite it)
    static final String KEY_NAME = "kidodi_name";      // friendly + editable (Settings screen / remote /config)
    static final int NAME_MAX = 20;                    // keeps state inside one BLE MTU

    private Identity() {}

    /** Stable 8-hex-char id for this install, generated on first use. */
    public static synchronized String id(Context ctx) {
        try {
            String v = Cfg.p(ctx).getString(KEY_ID, null);
            if (v == null || v.length() != 8) {
                v = String.format("%08x", new java.security.SecureRandom().nextInt());
                Cfg.p(ctx).edit().putString(KEY_ID, v).apply();
            }
            return v;
        } catch (Exception e) { return "00000000"; }
    }

    /** Friendly name; defaults to the device model so two tablets already differ out of the box. */
    public static String name(Context ctx) {
        String v = Cfg.getStr(ctx, KEY_NAME, "");
        if (v == null || v.trim().isEmpty()) v = defaultName();
        v = v.trim();
        return v.length() > NAME_MAX ? v.substring(0, NAME_MAX) : v;
    }

    private static String defaultName() {
        String m = android.os.Build.MODEL;
        return (m == null || m.trim().isEmpty()) ? "Kidodi TV" : m.trim();
    }
}
