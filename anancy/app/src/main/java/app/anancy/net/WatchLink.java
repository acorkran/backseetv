package app.anancy.net;

// ============================================================================
//  The watch follows its phone.
//
//  The watch remote talks to TVs over its own Bluetooth, so on its own it took
//  whichever TV it heard first. Now the phone tells it what to drive, over the
//  Wear Data Layer (/anancy/watch-target):
//
//    phone is the TV      -> ids = this phone. The watch drives this phone only.
//    phone is the remote  -> ids = the TVs the phone remote is aimed at ("" =
//                            all of them). If that is more than one screen (or a
//                            synced group), relay = 1: the watch sends its
//                            commands through the phone (/anancy/cmd), so they
//                            fan out exactly like a tap on the phone remote. Its
//                            own Bluetooth link stays up for the now-playing view.
//
//  With no phone around the watch keeps working, it just goes back to "first TV
//  heard" (and direct commands if the relay can't be reached).
// ============================================================================

import android.content.Context;
import android.util.Log;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

public final class WatchLink {
    static final String TAG = "WatchLink";
    public static final String PATH_TARGET = "/anancy/watch-target";
    public static final String PATH_CMD = "/anancy/cmd";
    private static volatile String lastSent = null;

    private WatchLink() {}

    /** The phone is the TV: the watch should drive this phone and nothing else. */
    public static void publishPlayer(Context ctx) {
        publish(ctx, "player", app.anancy.Identity.id(ctx), false);
    }

    /** The phone is the remote: aim the watch at the same screens (ids "" = all). */
    public static void publishRemote(Context ctx, java.util.Set<String> ids, boolean relay) {
        publish(ctx, "remote", ids == null ? "" : android.text.TextUtils.join(",", new java.util.TreeSet<>(ids)), relay);
    }

    private static void publish(Context ctx, String role, String ids, boolean relay) {
        final String key = role + "|" + ids + "|" + relay;
        if (key.equals(lastSent)) return;   // the Data Layer syncs every change to the watch; don't churn it
        lastSent = key;
        try {
            PutDataMapRequest req = PutDataMapRequest.create(PATH_TARGET);
            req.getDataMap().putString("role", role);
            req.getDataMap().putString("ids", ids);
            req.getDataMap().putBoolean("relay", relay);
            Wearable.getDataClient(ctx.getApplicationContext()).putDataItem(req.asPutDataRequest().setUrgent())
                    .addOnFailureListener(e -> { lastSent = null; Log.w(TAG, "publish failed: " + e); });
            Log.i(TAG, "watch target: " + key);
        } catch (Exception e) { lastSent = null; Log.w(TAG, "publish: " + e); }   // no Play services (e.g. a kiosk tablet)
    }
}
