package com.kidstv.kidodi;

// ============================================================================
//  Point the camera at a QR to pair. Handles two payloads:
//   - WIFI:S:..;T:..;P:..;;  -> WifiJoiner.join() (silent on device-owner, a
//     best-effort suggestion otherwise), returns the ssid in the result.
//   - KIDODI:ip=<ip>         -> returns the player's LAN IP so the caller can
//     talk to :8090 directly (no mDNS).
//  Finishes with RESULT_OK + the raw payload on the first decode. Plain Activity
//  made a LifecycleOwner (LifecycleRegistry) so CameraX can bindToLifecycle().
// ============================================================================

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QrScanActivity extends Activity implements LifecycleOwner {
    static final String TAG = "Kidodi";
    public static final String RESULT_PAYLOAD = "payload";     // raw QR text
    public static final String RESULT_KIDODI_IP = "ip";        // parsed from a KIDODI:ip= QR
    public static final String RESULT_JOINED_SSID = "ssid";    // the ssid we tried to join

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private volatile boolean handled = false;

    @NonNull @Override public Lifecycle getLifecycle() { return lifecycle; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        requestCameraPerm();

        PreviewView pv = new PreviewView(this);
        setContentView(pv);

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());

        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(pv.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(exec, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) { Log.e(TAG, "qr scan bind: " + e); }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy proxy) {
        if (handled) { proxy.close(); return; }
        android.media.Image mi = proxy.getImage();
        if (mi == null) { proxy.close(); return; }
        InputImage img = InputImage.fromMediaImage(mi, proxy.getImageInfo().getRotationDegrees());
        scanner.process(img)
                .addOnSuccessListener(codes -> {
                    for (Barcode bc : codes) {
                        String raw = bc.getRawValue();
                        if (raw != null && !handled) { handled = true; dispatch(raw); break; }
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
    }

    private void dispatch(String raw) {
        Intent result = new Intent();
        result.putExtra(RESULT_PAYLOAD, raw);
        try {
            if (raw.regionMatches(true, 0, "WIFI:", 0, 5)) {
                WifiJoiner.Creds c = WifiJoiner.parseWifiQr(raw);
                if (c != null) { WifiJoiner.join(this, c); result.putExtra(RESULT_JOINED_SSID, c.ssid); }
            } else if (raw.regionMatches(true, 0, "KIDODI:", 0, 7)) {
                String ip = kidodiIp(raw);
                if (ip != null) result.putExtra(RESULT_KIDODI_IP, ip);
            }
        } catch (Exception e) { Log.w(TAG, "qr dispatch: " + e); }
        setResult(RESULT_OK, result);
        runOnUiThread(this::finish);
    }

    /** KIDODI:ip=192.168.1.2[;k=v...] */
    private static String kidodiIp(String raw) {
        for (String kv : raw.substring(7).split(";")) {
            int e = kv.indexOf('=');
            if (e > 0 && "ip".equalsIgnoreCase(kv.substring(0, e).trim())) return kv.substring(e + 1).trim();
        }
        return null;
    }

    private void requestCameraPerm() {
        try { requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 7); } catch (Exception ignored) {}
    }

    @Override protected void onStart()  { super.onStart();  lifecycle.setCurrentState(Lifecycle.State.STARTED); }
    @Override protected void onResume() { super.onResume(); lifecycle.setCurrentState(Lifecycle.State.RESUMED); }
    @Override protected void onPause()  { lifecycle.setCurrentState(Lifecycle.State.STARTED); super.onPause(); }
    @Override protected void onStop()   { lifecycle.setCurrentState(Lifecycle.State.CREATED); super.onStop(); }
    @Override protected void onDestroy() {
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        try { exec.shutdownNow(); } catch (Exception ignored) {}
        try { if (scanner != null) scanner.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
