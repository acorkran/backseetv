package com.kidstv.kidodi;

// ============================================================================
//  Kidodi v2 player core. Own ExoPlayer engine, no Kodi, no bridge. ExoPlayer's
//  native playlist (setMediaItems + shuffle + REPEAT_MODE_ALL) = infinite
//  shuffle-all that auto-advances at each file's real end, so the Kodi
//  "stuck past EOF" wedge cannot occur. Starts MUTED (audio-safety rule).
//  A PlayerController exposes it to the ControlServer (:8090 + mDNS).
// ============================================================================

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.lang.ref.WeakReference;
import java.util.List;

public class PlayerActivity extends Activity {
    static final String TAG = "Kidodi";
    private static WeakReference<PlayerActivity> sRef = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private ControlServer server;
    private BleServer ble;
    private View flash;
    private View napOverlay;
    private boolean napped = false;
    private float preNapBrightness = -1f;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        napOverlay = new View(this);   // "power off" = pause + black the TV
        napOverlay.setBackgroundColor(Color.BLACK);
        napOverlay.setVisibility(View.GONE);
        root.addView(napOverlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setVisibility(View.GONE);
        root.addView(flash, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // admin escape: 4 taps in the top-left corner within 2s -> leave kiosk
        View escape = new View(this);
        escape.setOnClickListener(v -> onEscapeTap());
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(dp(72), dp(72));
        elp.gravity = Gravity.TOP | Gravity.START;
        root.addView(escape, elp);

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        player.setVolume(0f);   // MUTED by default
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_READY) Log.i(TAG, "STATE_READY (playing muted)"); }
        });

        PlayerController.get().attach(player);
        PlayerController.get().loadLibrary(this);   // scans, builds playlists (Shuffle All + per-show), starts
        server = new ControlServer(this, PlayerController.get());
        server.start();
        ble = new BleServer(this, PlayerController.get());   // BLE control tier (offline/car/AA)
        ble.start();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // power on/off (like the v1 remote): OFF = pause + black the TV; ON = play + screen back.
    public static void power() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doPower);
    }
    /** Any transport command un-blacks the screen so you never get audio-with-black-screen. */
    public static void wakeScreen() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> { if (a.napped) a.setNapScreen(false); });
    }
    private void doPower() {
        if (!napped) { setNapScreen(true); PlayerController.get().pause(); }
        else { setNapScreen(false); PlayerController.get().play(); }
    }
    private void setNapScreen(boolean nap) {
        napped = nap;
        PlayerController.get().setNapped(nap);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        napOverlay.setVisibility(nap ? View.VISIBLE : View.GONE);
        lp.screenBrightness = nap ? 0.02f : -1.0f;   // -1 = system default -> reliably ON again
        getWindow().setAttributes(lp);
    }

    // screen-as-flashlight, called by ControlServer /flash
    public static void flash(int ms) {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> a.doFlash(ms));
    }
    private void doFlash(int ms) {
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f; getWindow().setAttributes(lp);
        flash.setVisibility(View.VISIBLE);
        main.postDelayed(() -> {
            flash.setVisibility(View.GONE);
            // re-derive from the CURRENT nap state (not a stale snapshot) so a wake during
            // the flash isn't dragged back to black when the flash ends.
            WindowManager.LayoutParams p = getWindow().getAttributes();
            p.screenBrightness = napped ? 0.02f : -1.0f;
            getWindow().setAttributes(p);
        }, Math.max(200, Math.min(4000, ms)));
    }

    @Override protected void onResume() { super.onResume(); enterKiosk(); }

    // --- kid-proof shell: screen-pinning (normal) / inescapable lock (device owner) ---
    private long escFirst = 0; private int escCount = 0;
    private void onEscapeTap() {
        long now = System.currentTimeMillis();
        if (now - escFirst > 2000) { escFirst = now; escCount = 1; }
        else if (++escCount >= 4) { escCount = 0; exitKiosk(); }
    }
    private void enterKiosk() {
        int on = 0;
        try { on = Settings.Global.getInt(getContentResolver(), "kidodi_kiosk", 0); } catch (Exception e) {}
        if (on != 1) return;   // gated so dev builds aren't trapped; `settings put global kidodi_kiosk 1` to lock
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, AdminReceiver.class);
                try { dpm.setLockTaskPackages(admin, new String[]{getPackageName()}); } catch (Exception e) {}
                try { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE); } catch (Exception e) {}
                try { dpm.setStatusBarDisabled(admin, true); } catch (Exception e) {}
                try { dpm.setKeyguardDisabled(admin, true); } catch (Exception e) {}   // no lock screen blocking boot
                // the SHOW is the home screen: pin US as the preferred HOME so boot/Home land here (beats v1)
                try {
                    android.content.IntentFilter home = new android.content.IntentFilter(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addCategory(Intent.CATEGORY_DEFAULT);
                    dpm.addPersistentPreferredActivity(admin, home, new ComponentName(this, RouterActivity.class));
                } catch (Exception e) {}
            }
            startLockTask();
        } catch (Exception e) {}
    }
    private void exitKiosk() {
        try { stopLockTask(); } catch (Exception e) {}
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName()))
                dpm.setStatusBarDisabled(new ComponentName(this, AdminReceiver.class), false);
        } catch (Exception e) {}
        try { startActivity(new Intent(this, RouterActivity.class)); } catch (Exception e) {}
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN)
                PlayerController.get().nudge(k == KeyEvent.KEYCODE_VOLUME_UP ? 0.1f : -0.1f);
            return true;   // consume -> no system volume slider (kid-proof)
        }
        return super.dispatchKeyEvent(e);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (ble != null) { ble.stop(); ble = null; }
        if (server != null) { server.stop(); server = null; }
        PlayerController.get().detach();
        if (player != null) { player.release(); player = null; }
        if (sRef.get() == this) sRef = new WeakReference<>(null);
        super.onDestroy();
    }
}
