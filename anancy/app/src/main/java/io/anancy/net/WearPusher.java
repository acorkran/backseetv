package io.anancy.net;

// ============================================================================
//  Phone -> watch SELF-UPDATE over the Wear Data Layer (Bluetooth companion
//  link). The watch APK is embedded in this APK at build time (assets/wear.apk
//  + wear.sha + wear.id, see app/build.gradle). Whenever THIS app is updated
//  (or the remote service starts) we publish it as a DataItem; the watch's
//  WearUpdater installs it on itself. Keyed on the watch module's SOURCE hash
//  (wear.id == the watch's BuildConfig.BUILD_ID), so an unchanged watch build
//  is a no-op even though APK bytes differ per build. Phone (role=remote)
//  only — never the tablet.
// ============================================================================

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class WearPusher {
    static final String TAG = "WearPusher";
    static final String PATH_UPDATE = "/anancy/wear-update", PATH_STATUS = "/anancy/wear-status";
    private static volatile String embeddedId = null, watchId = null, watchVer = null;

    private static boolean isPhone(Context app) {
        return "remote".equals(app.getSharedPreferences("anancy", Context.MODE_PRIVATE).getString("role", ""));
    }

    /** Fire-and-forget (service start). */
    public static void push(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!isPhone(app)) return;
        new Thread(() -> pushSync(app), "wear-push").start();
    }

    /** Blocking (used from the package-replaced receiver under goAsync so the hand-off completes). */
    public static void pushSync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!isPhone(app)) return;
        try {
            String id = readAsset(app, "wear.id").trim(), sha = readAsset(app, "wear.sha").trim();
            byte[] apk = readAssetBytes(app, "wear.apk");
            if (id.isEmpty() || sha.isEmpty() || apk.length == 0) return;
            embeddedId = id;
            PutDataMapRequest req = PutDataMapRequest.create(PATH_UPDATE);
            req.getDataMap().putString("id", id);
            req.getDataMap().putString("sha", sha);
            req.getDataMap().putAsset("apk", Asset.createFromBytes(apk));
            Tasks.await(Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent()), 30, TimeUnit.SECONDS);
            Log.i(TAG, "published watch build " + id.substring(0, 8) + " (" + apk.length + " bytes)");
            refreshStatus(app);
        } catch (Throwable t) { Log.w(TAG, "push: " + t); }   // no Play services / no watch paired -> ignore
    }

    /** What the watch reports as installed (it publishes /anancy/wear-status on every app start). */
    public static void refreshStatus(Context ctx) {
        try {
            Wearable.getDataClient(ctx.getApplicationContext()).getDataItems().addOnSuccessListener((DataItemBuffer buf) -> {
                try {
                    for (DataItem it : buf) {
                        if (!PATH_STATUS.equals(it.getUri().getPath())) continue;
                        DataMapItem m = DataMapItem.fromDataItem(it);
                        watchId = m.getDataMap().getString("id");
                        watchVer = m.getDataMap().getString("ver");
                    }
                } finally { buf.release(); }
            });
        } catch (Throwable ignored) {}
    }

    /** One short line for the remote UI; "" when nothing is known. */
    public static String watchStatus() {
        if (watchId == null) return "";
        if (embeddedId == null) return "watch " + watchVer;
        return embeddedId.equals(watchId) ? "watch " + watchVer + " ✓" : "watch " + watchVer + " → updating…";
    }

    private static String readAsset(Context c, String name) throws Exception { return new String(readAssetBytes(c, name), "UTF-8"); }
    private static byte[] readAssetBytes(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }
}
