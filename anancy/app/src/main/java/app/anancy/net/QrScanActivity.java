package app.anancy.net;

// ============================================================================
//  Point the camera at a QR to pair. Handles two payloads:
//   - WIFI:S:..;T:..;P:..;;  -> WifiJoiner.join() (silent on device-owner, a
//     best-effort suggestion otherwise), returns the ssid in the result.
//   - ANANCY:ip=<ip>         -> returns the player's LAN IP so the caller can
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
    static final String TAG = "Anancy";
    public static final String RESULT_PAYLOAD = "payload";     // raw QR text
    public static final String RESULT_ANANCY_IP = "ip";        // parsed from a ANANCY:ip= QR
    public static final String RESULT_JOINED_SSID = "ssid";    // the ssid we tried to join
    public static final String RESULT_TV_NAME = "tvname";      // friendly name from an ANANCY: pairing QR
    public static final String RESULT_PAIRED = "paired";        // true when the QR carried a pairing key and it was stored
    public static final String RESULT_TV_ID = "tvid";

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
            } else if (raw.regionMatches(true, 0, "ANANCY:", 0, 7)) {
                QrParse.Pair p = QrParse.anancyPair(raw);
                if (p != null) {
                    if (p.ip != null && !p.ip.isEmpty()) result.putExtra(RESULT_ANANCY_IP, p.ip);
                    if (p.name != null) result.putExtra(RESULT_TV_NAME, p.name);
                    if (p.id != null) result.putExtra(RESULT_TV_ID, p.id);
                    // Seeing the TV's screen is what authorises this phone: its QR holds the key.
                    if (p.id != null && p.key != null) {
                        String had = app.anancy.Pairing.keyFor(this, p.id);
                        if (had != null && !had.equals(p.key)) {   // a code claiming to be a TV we already know
                            confirmReplace(p, result);
                            return;
                        }
                        if (app.anancy.Pairing.remember(this, p.id, p.name, p.key)) result.putExtra(RESULT_PAIRED, true);
                    }
                    // One scan does the whole job: join the TV's network, then we know where it is.
                    // joinDirect (not join) because the TV's hotspot has no internet, so a plain
                    // suggestion is ignored by the system -- the same trap the car tier fell into.
                    if (p.ssid != null && !p.ssid.isEmpty()) {
                        WifiJoiner.Creds c = new WifiJoiner.Creds();
                        c.ssid = p.ssid; c.type = "WPA"; c.pass = p.pass;
                        try { WifiJoiner.joinDirect(this, c, null, null); } catch (Exception ignored) {}
                        result.putExtra(RESULT_JOINED_SSID, p.ssid);
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "qr dispatch: " + e); }
        setResult(RESULT_OK, result);
        runOnUiThread(this::finish);
    }

    /** A new code for a screen we're already paired with. Legitimate after "Unpair all remotes" on
        the TV; otherwise it could be a fake code standing in for that TV, so ask first. */
    private void confirmReplace(final QrParse.Pair p, final Intent result) {
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("Already paired with " + (p.name == null || p.name.isEmpty() ? "this screen" : p.name))
                .setMessage("This code has a different key from the one this phone has. That's expected if the "
                        + "TV was reset or used Unpair all remotes. If you didn't do that, cancel: it may not be your TV.")
                .setPositiveButton("Replace", (d, w) -> {
                    if (app.anancy.Pairing.remember(this, p.id, p.name, p.key)) result.putExtra(RESULT_PAIRED, true);
                    setResult(RESULT_OK, result); finish();
                })
                .setNegativeButton("Cancel", (d, w) -> { setResult(RESULT_CANCELED); finish(); })
                .setCancelable(false).show());
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
