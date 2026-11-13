package app.anancy.wear;

// The pairing keys this watch may use: the ones its phone holds for the TVs it paired with (plus the
// phone's own, for when the phone is the TV). The phone publishes them over the Wear Data Layer
// (/anancy/keys), which only syncs between this watch and its own phone, within apps signed with the
// same key. The watch never pairs on its own: it can drive exactly what its phone can.

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

final class WatchKeys {
    static final String PATH = "/anancy/keys";
    private static final String PREFS = "watch_keys";

    private WatchKeys() {}

    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** The key for TV `id`, or null if the phone hasn't paired with it. */
    static String keyFor(Context c, String id) {
        if (id == null) return null;
        try {
            String k = new JSONObject(p(c).getString("keys", "{}")).optString(id, null);
            return PairCrypto.validKey(k) ? k : null;
        } catch (Exception e) { return null; }
    }

    /** This watch's own id, sent in the handshake so a TV's log can tell remotes apart. */
    static synchronized String clientId(Context c) {
        String v = p(c).getString("id", null);
        if (v == null || v.length() != 8) {
            v = String.format("%08x", new java.security.SecureRandom().nextInt());
            p(c).edit().putString("id", v).apply();
        }
        return v;
    }

    static void apply(Context c, DataMap m) {
        String json = m.getString("keys", "{}");
        boolean changed = !json.equals(p(c).getString("keys", "{}"));
        p(c).edit().putString("keys", json).apply();
        try { Log.i(BleClient.TAG, "pairing keys from phone: " + new JSONObject(json).length() + " screen(s)"); } catch (Exception ignored) {}
        if (changed) { BleService s = BleService.get(); if (s != null) s.onTargetChanged(); }
    }

    /** Pull the phone's current keys (they may have changed while this app wasn't running). */
    static void refresh(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        try {
            Wearable.getDataClient(c).getDataItems().addOnSuccessListener((DataItemBuffer buf) -> {
                try {
                    for (DataItem it : buf) if (PATH.equals(it.getUri().getPath())) apply(c, DataMapItem.fromDataItem(it).getDataMap());
                } finally { buf.release(); }
            });
        } catch (Exception e) { Log.w(BleClient.TAG, "keys refresh: " + e); }
    }
}
