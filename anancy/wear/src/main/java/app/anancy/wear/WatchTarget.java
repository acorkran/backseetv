package app.anancy.wear;

// What the phone says this watch should drive (the phone's net/WatchLink publishes it):
//   phone is the TV     -> ids = {that phone}: connect to it and nothing else.
//   phone is the remote -> ids = the TVs it's aimed at (empty = any), and relay = true when that's more
//                          than one screen, so commands go through the phone and reach all of them.
// Kept in prefs so a watch that wakes up without its phone nearby still knows the last answer.

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class WatchTarget {
    static final String PATH = "/anancy/watch-target", PATH_CMD = "/anancy/cmd";
    private static final String PREFS = "watch_target";

    private WatchTarget() {}

    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** TV ids we may connect to; null = any. */
    static Set<String> allowed(Context c) {
        String ids = p(c).getString("ids", "");
        if (ids == null || ids.isEmpty()) return null;
        Set<String> s = new HashSet<>();
        for (String id : ids.split(",")) if (!id.isEmpty()) s.add(id);
        return s.isEmpty() ? null : Collections.unmodifiableSet(s);
    }
    static boolean relay(Context c) { return p(c).getBoolean("relay", false); }
    static String role(Context c) { return p(c).getString("role", ""); }

    /** From a Data Layer change (WearUpdateService) or the startup fetch. */
    static void apply(Context c, DataMap m) {
        String role = m.getString("role", ""), ids = m.getString("ids", "");
        boolean relay = m.getBoolean("relay", false);
        boolean changed = !role.equals(role(c)) || !ids.equals(p(c).getString("ids", "")) || relay != relay(c);
        p(c).edit().putString("role", role).putString("ids", ids).putBoolean("relay", relay).apply();
        Log.i(BleClient.TAG, "target from phone: role=" + role + " ids=" + (ids.isEmpty() ? "any" : ids) + " relay=" + relay);
        if (changed) { BleService s = BleService.get(); if (s != null) s.onTargetChanged(); }
    }

    /** Pull the phone's current answer (it may have changed while this app wasn't running). */
    static void refresh(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        try {
            Wearable.getDataClient(c).getDataItems().addOnSuccessListener((DataItemBuffer buf) -> {
                try {
                    for (DataItem it : buf) if (PATH.equals(it.getUri().getPath())) apply(c, DataMapItem.fromDataItem(it).getDataMap());
                } finally { buf.release(); }
            });
        } catch (Exception e) { Log.w(BleClient.TAG, "target refresh: " + e); }
    }
}
