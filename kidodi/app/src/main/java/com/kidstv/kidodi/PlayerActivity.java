package com.kidstv.kidodi;

// ============================================================================
//  Kidodi v2 player core. Own ExoPlayer engine, no Kodi, no bridge. ExoPlayer's
//  native playlist (setMediaItems + shuffle + REPEAT_MODE_ALL) = infinite
//  shuffle-all that auto-advances at each file's real end, so the Kodi
//  "stuck past EOF" wedge cannot occur. Starts MUTED (audio-safety rule).
//  A PlayerController exposes it to the ControlServer (:8090 + mDNS).
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        player.setVolume(0f);   // MUTED by default

        List<MediaItem> items = Library.scan(this);
        player.setMediaItems(items);
        player.setShuffleModeEnabled(true);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_READY) Log.i(TAG, "STATE_READY (playing muted)"); }
        });
        player.prepare();
        player.play();
        Log.i(TAG, "player started: items=" + items.size() + " muted=true shuffle=true repeatAll=true");

        PlayerController.get().attach(player);
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

    // power on/off (like the v1 remote): toggle nap = pause + black the TV / wake = play.
    public static void power() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doPower);
    }
    private void doPower() {
        napped = !napped;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (napped) {
            PlayerController.get().pause();
            napOverlay.setVisibility(View.VISIBLE);
            preNapBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.01f; getWindow().setAttributes(lp);
        } else {
            napOverlay.setVisibility(View.GONE);
            lp.screenBrightness = preNapBrightness; getWindow().setAttributes(lp);
            PlayerController.get().play();
        }
    }

    // screen-as-flashlight, called by ControlServer /flash
    public static void flash(int ms) {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> a.doFlash(ms));
    }
    private void doFlash(int ms) {
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        final float prev = lp.screenBrightness;
        lp.screenBrightness = 1.0f; getWindow().setAttributes(lp);
        flash.setVisibility(View.VISIBLE);
        main.postDelayed(() -> {
            flash.setVisibility(View.GONE);
            lp.screenBrightness = prev; getWindow().setAttributes(lp);
        }, Math.max(200, Math.min(4000, ms)));
    }

    @Override protected void onDestroy() {
        if (ble != null) { ble.stop(); ble = null; }
        if (server != null) { server.stop(); server = null; }
        PlayerController.get().detach();
        if (player != null) { player.release(); player = null; }
        if (sRef.get() == this) sRef = new WeakReference<>(null);
        super.onDestroy();
    }
}
