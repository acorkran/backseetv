package com.kidstv.player;

// ============================================================================
//  The kiosk surface: fullscreen video that IS the home screen. Holds the
//  SurfaceView the engine renders into, plus the white "flash" overlay (screen-
//  as-flashlight) drawn inside our OWN window -- no SYSTEM_ALERT_WINDOW needed.
//  Starts the in-process control server. One app, no Kodi.
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private static WeakReference<PlayerActivity> sRef = new WeakReference<>(null);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private PlayerEngine mEngine;
    private View mFlash;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mFlash = new View(this);
        mFlash.setBackgroundColor(Color.WHITE);
        mFlash.setVisibility(View.GONE);
        root.addView(mFlash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        mEngine = PlayerEngine.get(this);
        if (mEngine.size() == 0) mEngine.scanAndShuffle();

        // start the in-process control server (foreground)
        android.content.Intent ci = new android.content.Intent(this, ControlService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(ci); else startService(ci);
    }

    // --- surface -> engine --------------------------------------------------
    @Override public void surfaceCreated(SurfaceHolder h)   { mEngine.attachSurface(h); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { mEngine.detachSurface(); }

    // --- screen-as-flashlight (called by ControlService) --------------------
    public static void flash(int ms) {
        final PlayerActivity a = sRef.get();
        if (a == null) return;
        a.mMain.post(new Runnable() { public void run() { a.doFlash(ms); } });
    }
    private void doFlash(int ms) {
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        final float prev = lp.screenBrightness;
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        mFlash.setVisibility(View.VISIBLE);
        mMain.postDelayed(new Runnable() { public void run() {
            mFlash.setVisibility(View.GONE);
            lp.screenBrightness = prev;
            getWindow().setAttributes(lp);
        }}, Math.max(200, Math.min(4000, ms)));
    }

    @Override public void onDestroy() {
        if (sRef.get() == this) sRef = new WeakReference<>(null);
        super.onDestroy();
    }
}
