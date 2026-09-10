package com.kidstv.kidodi;

// ============================================================================
//  Optional "play mode": a full-screen WebView hosting the bundled toddler-lock
//  playground (touch-native mash -> giant letters/animals). This is a play surface,
//  NOT the show. Audio is NOT played by the WebView (that would ride the raw system
//  media volume, ungovernable by the kiosk) — app.js routes each clip through
//  WebAudioBridge (native SoundPool at an app-controlled gain, default muted).
//  On load we go straight into the locked playground; the playground's own timed
//  6-digit adult code exits play mode via AndroidExit.exit() -> finish().
// ============================================================================

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;

@SuppressLint("SetJavaScriptEnabled")
public class PlayModeActivity extends Activity {
    private static WeakReference<PlayModeActivity> sRef = new WeakReference<>(null);
    private WebView web;

    /** Start play mode. Safe from a non-Activity context (control server / BLE). */
    public static void begin(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), PlayModeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ctx.getApplicationContext().startActivity(i);
    }
    /** Exit play mode (back to the show). */
    public static void end() {
        PlayModeActivity a = sRef.get();
        if (a != null) a.runOnUiThread(a::finish);
    }
    /** Kiosk-governed playground audio gain (0 = muted). */
    public static void setGain(float g) { WebAudioBridge.setGain(g); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new WebAudioBridge(this), "AndroidAudio");
        web.addJavascriptInterface(new ExitBridge(), "AndroidExit");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                // straight into the locked playground (skip the web app's home screen)
                v.evaluateJavascript("window.ToddlerLock && window.ToddlerLock.lock && window.ToddlerLock.lock();", null);
            }
        });
        web.loadUrl("file:///android_asset/toddlerlock/index.html");
        setContentView(web);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override protected void onResume() { super.onResume(); immersive(); }

    /** JS -> native: the playground's timed-code unlock exits play mode. */
    private class ExitBridge {
        @JavascriptInterface public void exit() { runOnUiThread(PlayModeActivity.this::finish); }
    }

    @Override protected void onDestroy() {
        if (sRef.get() == this) sRef = new WeakReference<>(null);
        if (web != null) { try { web.destroy(); } catch (Exception ignored) {} web = null; }
        super.onDestroy();
    }
}
