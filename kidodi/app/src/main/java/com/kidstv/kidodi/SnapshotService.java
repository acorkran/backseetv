package com.kidstv.kidodi;

// ============================================================================
//  Optional camera-monitor plugin (OFF by default). Grabs a downscaled JPEG from
//  the FRONT camera at ~0.75 fps — i.e. periodically-updated STILL IMAGES, not a
//  video feed — so a parent can glance at the kid on the phone/watch/AA dash.
//  Headless: binds CameraX ImageAnalysis (no preview) to this LifecycleService.
//  No ML/inference here, no recording, no network of its own — it just keeps the
//  latest still in memory for ControlServer to serve. Reuses cameramon's proven
//  RGBA_8888 -> ARGB bitmap conversion (the YUV path is unreliable).
// ============================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Build;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SnapshotService extends LifecycleService {
    static final String TAG = "Kidodi";
    static final long MIN_INTERVAL_MS = 1300;   // ~0.75 fps: a fresh STILL roughly every 1.3s
    static final float TARGET_PX = 480f;        // downscale longest side (small still, kind to BLE)

    private static SnapshotService INSTANCE;
    private static volatile byte[] latest = null;    // latest full-res JPEG still (~480px)
    private static volatile byte[] latestLow = null; // latest reduced-res JPEG (~128px q40) for BLE
    private static volatile long intervalMs = MIN_INTERVAL_MS;   // dynamic: drops while a live consumer is open
    private static int liveCount = 0;                            // # of open /live (MJPEG) consumers
    private static final java.util.Set<String> wanters = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    public static boolean running() { return INSTANCE != null; }
    public static byte[] latestSnapshot() { return latest; }      // full-res, for Wi-Fi
    public static byte[] latestLowRes() { return latestLow; }      // reduced-res, for BLE chunks

    public static void begin(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), SnapshotService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.getApplicationContext().startForegroundService(i);
        else ctx.getApplicationContext().startService(i);
    }
    public static void end(Context ctx) {
        latest = null; latestLow = null;
        ctx.getApplicationContext().stopService(new Intent(ctx.getApplicationContext(), SnapshotService.class));
    }
    /** Ref-counted capture: the camera runs while ANY consumer wants it (snap / BLE stills / live).
        When enabling from empty, the device owner grants itself CAMERA first (no user prompt), then
        starts after a short beat — the grant needs a moment before an FGS-camera start or the first
        enable on a fresh device throws. */
    // All start/stop decisions run serialized on the main thread with single guarded pending runnables,
    // so rapid open/close toggling can't queue multiple begins or race a teardown against a startup.
    private static final android.os.Handler H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable pendingStart, pendingStop;
    public static void want(Context ctx, String key, boolean on) {
        final Context ac = ctx.getApplicationContext();
        synchronized (wanters) { if (on) wanters.add(key); else wanters.remove(key); }
        H.post(() -> {
            boolean wanted; synchronized (wanters) { wanted = !wanters.isEmpty(); }
            if (wanted) {
                if (pendingStop != null) { H.removeCallbacks(pendingStop); pendingStop = null; }   // cancel a pending teardown
                if (INSTANCE == null && pendingStart == null) {                                    // start exactly once
                    grantCamera(ac);
                    pendingStart = () -> { pendingStart = null;
                        synchronized (wanters) { if (wanters.isEmpty()) return; }
                        if (INSTANCE == null) begin(ac); };
                    H.postDelayed(pendingStart, 400);
                }
            } else {
                if (pendingStart != null) { H.removeCallbacks(pendingStart); pendingStart = null; } // cancel a pending start
                if (pendingStop == null) {                                                          // debounce the teardown
                    pendingStop = () -> { pendingStop = null;
                        synchronized (wanters) { if (!wanters.isEmpty()) return; }
                        end(ac); };
                    H.postDelayed(pendingStop, 1500);
                }
            }
        });
    }
    /** Back-compat: the /snap toggle. */
    public static void set(Context ctx, boolean on) { want(ctx, "snap", on); }
    /** A live (Wi-Fi MJPEG) consumer: bumps the capture rate to ~8fps and keeps capture alive. */
    public static void setLive(Context ctx, boolean on) {
        synchronized (SnapshotService.class) {
            if (on) liveCount++; else if (liveCount > 0) liveCount--;
            intervalMs = liveCount > 0 ? 120 : MIN_INTERVAL_MS;
        }
        want(ctx, "live", on);
    }
    private static void grantCamera(Context ctx) {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName())) {
                android.content.ComponentName admin = new android.content.ComponentName(ctx, AdminReceiver.class);
                dpm.setPermissionGrantState(admin, ctx.getPackageName(), android.Manifest.permission.CAMERA,
                        android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        } catch (Exception e) { Log.w(TAG, "grantCamera: " + e); }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private Bitmap rgba;
    private long lastProcess = 0;

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(73, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(73, note());
        } catch (Exception e) {   // e.g. CAMERA not granted yet -> fail gracefully, don't crash the process
            Log.e(TAG, "snapshot FGS start failed (CAMERA not granted?): " + e);
            INSTANCE = null; stopSelf(); return;
        }
        bindCamera();
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        super.onStartCommand(i, flags, startId);
        return START_STICKY;
    }

    private void bindCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(exec, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                Log.i(TAG, "snapshot: front camera bound (headless)");
            } catch (Exception e) { Log.e(TAG, "snapshot bind: " + e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy proxy) {
        long now = System.currentTimeMillis();
        if (now - lastProcess < intervalMs) { proxy.close(); return; }   // throttle to the current rate (still/live)
        lastProcess = now;
        try {
            ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            int w = proxy.getWidth(), h = proxy.getHeight();
            int bufW = w + (rowStride - pixelStride * w) / pixelStride;   // absorb row padding
            if (rgba == null || rgba.getWidth() != bufW || rgba.getHeight() != h)
                rgba = Bitmap.createBitmap(bufW, h, Bitmap.Config.ARGB_8888);
            ByteBuffer buf = plane.getBuffer(); buf.rewind();
            rgba.copyPixelsFromBuffer(buf);

            int rot = proxy.getImageInfo().getRotationDegrees();
            float scale = Math.min(1f, TARGET_PX / Math.max(w, h));
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            if (rot != 0) m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(rgba, 0, 0, w, h, m, true);   // crop padding + scale + upright

            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 60, jpg);
            latest = jpg.toByteArray();
            // reduced-res copy for BLE chunks (~128px longest side, q40, target <4KB)
            float ls = Math.min(1f, 128f / Math.max(out.getWidth(), out.getHeight()));
            Bitmap low = Bitmap.createScaledBitmap(out,
                    Math.max(1, Math.round(out.getWidth() * ls)), Math.max(1, Math.round(out.getHeight() * ls)), true);
            ByteArrayOutputStream ljpg = new ByteArrayOutputStream();
            low.compress(Bitmap.CompressFormat.JPEG, 40, ljpg);
            latestLow = ljpg.toByteArray();
            if (low != out) low.recycle();
            if (out != rgba) out.recycle();
        } catch (Exception e) { Log.w(TAG, "snapshot convert: " + e); }
        finally { proxy.close(); }
    }

    @Override public void onDestroy() {
        try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) {}
        try { exec.shutdownNow(); } catch (Exception ignored) {}
        if (INSTANCE == this) INSTANCE = null;
        latest = null; latestLow = null;
        super.onDestroy();
    }

    private Notification note() {
        String ch = "kidodi_snapshot";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Kidodi snapshots", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Kidodi").setContentText("Camera snapshots on")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build();
    }
}
