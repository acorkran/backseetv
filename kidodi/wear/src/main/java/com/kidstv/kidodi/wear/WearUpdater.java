package com.kidstv.kidodi.wear;

// ============================================================================
//  Watch SELF-UPDATE over the Wear Data Layer (Bluetooth companion link).
//  The phone build embeds the watch APK and publishes it as a DataItem
//  (/kidodi/wear-update: id + sha + apk asset) whenever the phone app is
//  updated. "id" is the watch module's SOURCE hash (== our BuildConfig.BUILD_ID
//  when we're current); "sha" verifies the transferred bytes. We install the
//  APK on ourselves through PackageInstaller. That is SILENT (no tap) once
//  this app is its own installer of record and holds the install-unknown-apps
//  appop — a one-time adb bootstrap on the watch:
//      adb install -i com.kidstv.kidodi wear-debug.apk
//      adb shell appops set com.kidstv.kidodi REQUEST_INSTALL_PACKAGES allow
//  Otherwise the system's install prompt appears on the watch (one tap).
//  We also publish what's installed (/kidodi/wear-status) so the phone remote
//  can show "watch 0.1-abc1234 ✓".
// ============================================================================

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WearUpdater {
    static final String TAG = "WearUpdater";
    static final String PATH_UPDATE = "/kidodi/wear-update", PATH_STATUS = "/kidodi/wear-status";
    static final String PREFS = "kidodi", KEY_FAILED = "update_failed_id";
    static final AtomicBoolean busy = new AtomicBoolean(false);

    /** App start: tell the phone what's installed, then apply any update we may have missed. */
    public static void onAppStart(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                PutDataMapRequest req = PutDataMapRequest.create(PATH_STATUS);
                req.getDataMap().putString("id", BuildConfig.BUILD_ID);
                req.getDataMap().putString("ver", versionName(app));
                Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent());
                Wearable.getDataClient(app).getDataItems().addOnSuccessListener((DataItemBuffer buf) -> {
                    try {
                        for (DataItem it : buf)
                            if (PATH_UPDATE.equals(it.getUri().getPath())) consider(app, DataMapItem.fromDataItem(it));
                    } finally { buf.release(); }
                });
            } catch (Throwable t) { Log.w(TAG, "onAppStart: " + t); }   // no Play services -> just no self-update
        }, "wear-updater").start();
    }

    /** A published watch build: install it if it isn't what we're running (and hasn't failed before). */
    static void consider(final Context ctx, final DataMapItem item) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            boolean committed = false;
            if (!busy.compareAndSet(false, true)) return;
            try {
                String id = item.getDataMap().getString("id"), sha = item.getDataMap().getString("sha");
                Asset asset = item.getDataMap().getAsset("apk");
                if (id == null || sha == null || asset == null) return;
                if (id.equals(BuildConfig.BUILD_ID)) { Log.i(TAG, "up to date " + id.substring(0, 8)); return; }
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (id.equals(p.getString(KEY_FAILED, ""))) { Log.w(TAG, "skipping build that failed before " + id.substring(0, 8)); return; }

                File apk = new File(app.getCacheDir(), "update.apk");
                DataClient.GetFdForAssetResponse fd = Tasks.await(Wearable.getDataClient(app).getFdForAsset(asset), 120, TimeUnit.SECONDS);
                try (InputStream in = fd.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (!sha256(apk).equals(sha)) { Log.w(TAG, "sha mismatch after transfer, dropping"); apk.delete(); return; }
                install(app, apk, id);
                committed = true;   // busy is released by InstallResultReceiver (or by our own restart)
            } catch (Throwable t) { Log.w(TAG, "consider: " + t); }
            finally { if (!committed) busy.set(false); }
        }, "wear-update-install").start();
    }

    static void install(Context app, File apk, String id) throws Exception {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sid = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(sid)) {
            try (OutputStream out = s.openWrite("wear.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(app, InstallResultReceiver.class).putExtra("id", id);
            PendingIntent pend = PendingIntent.getBroadcast(app, sid, cb, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Log.i(TAG, "installing " + id.substring(0, 8) + " (" + apk.length() + " bytes)");
            s.commit(pend.getIntentSender());
        }
    }

    static String versionName(Context app) {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }
    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
