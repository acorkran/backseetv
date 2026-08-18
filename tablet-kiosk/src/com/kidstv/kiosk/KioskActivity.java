package com.kidstv.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Kid kiosk. The SHOW itself is the home screen: on boot and on every Home-press we
 * go straight into Kodi (which resumes where it left off / auto-plays the season), and
 * float a transparent, full-screen system overlay ON TOP of Kodi that:
 *   - swallows every touch (tap, swipe, 2-finger playlist menu, long-press) so the kid
 *     can poke the screen freely without reaching any Kodi control, and
 *   - intercepts the hardware VOLUME keys and drives KODI's own volume over JSON-RPC
 *     (the OS otherwise routes them to STREAM_MUSIC, which can't un-silence a Kodi that
 *     boots at volume 0) - so the physical buttons AND the front-seat remote (Yatse)
 *     move one shared volume, and boot stays silent.
 * There is no kid-facing launcher/menu; showLauncher() remains only as a degraded
 * fallback if Kodi's launch intent can't be resolved.
 *
 * Escape hatches:
 *   on-device : 4 four-finger taps within 3s anywhere on screen -> admin PIN
 *               (default 1234, override: adb shell settings put global kiosk_pin <PIN>)
 *               -> full tablet.
 *   over adb  : settings put global kiosk_off 3  -> unlock to full tablet NOW
 *                 (keeps device owner; reopen KidsTV to re-lock). Then set it
 *                 back to 0 so the next launch locks normally.
 *               settings put global kiosk_off 1  (maintenance / pause)
 *               settings put global kiosk_off 2  (remove kiosk + device owner)
 *               settings put global kiosk_off 0  (normal) ; then
 *               am start -n com.kidstv.kiosk/.KioskActivity
 */
public class KioskActivity extends Activity {
    static final String KODI = "org.xbmc.kodi";
    static final String DEFAULT_PIN = "1234";
    static final String ARTHUR_S1 = "special://profile/playlists/video/ArthurS1.m3u";
    static final long AUTO_LAUNCH_MS = 1800;    // cold boot: straight into shows
    static final long HOME_LAUNCH_MS = 8000;    // Home-press: give the admin time to escape
    static boolean sFirstShow = true;           // false after the first (boot) launcher

    final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable mAuto;
    volatile int mNavGen = 0;
    long mLastLaunch = 0;
    boolean mPinShowing = false;   // suppress auto-launch re-arm while the PIN dialog is up

    DevicePolicyManager dpm;
    ComponentName admin;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);
    }

    // singleInstance: a re-launch (e.g. the overlay's escape intent) arrives here, not
    // via a fresh onCreate. Adopt it so onResume sees the "escape" extra.
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    /** Set the Android media stream to a percentage of its max (0 = silent). */
    void setMediaVolumePercent(int pct) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
        } catch (Exception e) {}
    }

    @Override protected void onResume() {
        super.onResume();
        boolean owner = dpm.isDeviceOwnerApp(getPackageName());

        // Waking from a Yatse "nap" (screen was blanked): turn the display back on, then
        // fall through to the normal path (foreground Kodi; the addon resumes playback).
        if (getIntent() != null && getIntent().getBooleanExtra("wake", false)) {
            getIntent().removeExtra("wake");
            try {
                if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {}
        }

        // Admin escape triggered from the touch-block overlay's hidden corner (the
        // service already dropped the overlay before launching us; belt-and-suspenders).
        if (getIntent() != null && getIntent().getBooleanExtra("escape", false)) {
            getIntent().removeExtra("escape");
            bridge(BridgeService.ACTION_REMOVE_OVERLAY);
            promptPin();
            return;
        }

        int off = 0;
        try { off = Settings.Global.getInt(getContentResolver(), "kiosk_off", 0); } catch (Exception e) {}
        if (off == 3) {          // adb escape hatch: unlock to the full tablet now
            unlockToFullTablet(); return;
        }
        if (off == 2 && owner) {
            bridge(BridgeService.ACTION_REMOVE_OVERLAY);
            try { stopLockTask(); } catch (Exception e) {}
            try { dpm.setKeyguardDisabled(admin, false); } catch (Exception e) {}
            try { dpm.clearPackagePersistentPreferredActivities(admin, getPackageName()); } catch (Exception e) {}
            try { dpm.clearDeviceOwnerApp(getPackageName()); } catch (Exception e) {}
            showMessage("Kiosk removed. Device owner cleared."); return;
        }
        if (off == 1) {
            bridge(BridgeService.ACTION_REMOVE_OVERLAY);
            try { stopLockTask(); } catch (Exception e) {}
            showMessage("Maintenance mode (kiosk paused)."); return;
        }

        if (owner) {
            try { dpm.setLockTaskPackages(admin, new String[]{getPackageName(), KODI}); } catch (Exception e) {}
            // Lock down system UI in kid mode: NO Home/Recents/notifications/global-actions.
            // Blocking Home means Kodi never gets backgrounded by a stray Home press (which
            // made it pause/hitch), and the kid can't disrupt playback with the button at all.
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            } catch (Exception e) {}
            try { startLockTask(); } catch (Exception e) {}
            try { dpm.setKeyguardDisabled(admin, true); } catch (Exception e) {}  // no lock screen after boot
            reassertHome();
        }

        // The SHOW is the home screen: go straight to Kodi (it resumes where it was).
        // Start/keep the foreground bridge service, which also raises the transparent
        // touch-block overlay over Kodi (the kid can't reach any control; volume keys are
        // bridged to Kodi's volume). No launcher, no menu. Admin escape = long-press the
        // overlay's top-right corner.
        bridge(null);
        launchKodi();
    }

    /** Start (or signal) the always-on foreground bridge/overlay service. A null action
        keeps the bridge up and ensures the overlay is shown; ACTION_REMOVE_OVERLAY takes
        the overlay down (admin escape / unlock) while leaving the bridge running. */
    void bridge(String action) {
        Intent i = new Intent(this, BridgeService.class);
        if (action != null) i.setAction(action);
        try { startService(i); } catch (Exception e) {}
    }

    @Override protected void onPause() {
        super.onPause();
        cancelAuto();
    }

    void cancelAuto() { if (mAuto != null) mHandler.removeCallbacks(mAuto); }

    void reassertHome() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, f, new ComponentName(this, KioskActivity.class));
        } catch (Exception e) {}
    }

    void launchKodi() {
        cancelAuto();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean recent = (now - mLastLaunch) < 3000;   // debounce double-launch races
        mLastLaunch = now;
        Intent i = getPackageManager().getLaunchIntentForPackage(KODI);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Playback (and re-playing the show whenever it stops) is handled inside Kodi
            // by the service.kidstv.autoplay addon's self-heal monitor - so all we do on a
            // return to Kodi is foreground it and top up the Android media stream headroom.
            try { startActivity(i); if (!recent) navigateKodi(); return; } catch (Exception e) {}
        }
        showLauncher();
    }

    /** Playback (play Arthur S1 from the top, looping) is handled reliably INSIDE Kodi by
        the service.kidstv.autoplay addon — running in-process, it doesn't race Kodi's
        cold-boot startup the way an external JSON-RPC driver did. Silence is handled by
        Kodi's persisted volume being 0 (fvolumelevel=0, survives non-clean shutdowns).
        All this method does is give the Android media stream headroom for Yatse: Android
        drops it to a "safe" ~5/15 when playback starts, so we re-assert it for a while. */
    void navigateKodi() {
        final int gen = ++mNavGen;   // invalidate any earlier run
        new Thread(new Runnable() { public void run() {
            for (int i = 0; i < 20 && gen == mNavGen; i++) {
                try { Thread.sleep(2000); } catch (Exception e) {}
                setMediaVolumePercent(100);  // full headroom; Kodi's own volume (0 at boot) is the control
            }
        }}).start();
    }

    /** Re-lock after a cancelled/failed admin escape: overlay back up, back to the show. */
    void reLock() { bridge(null); launchKodi(); }

    /** Send a JSON-RPC request over Kodi's TCP 9090 and return the id:1 response object. */
    String rpc(String req) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 9090), 1500);
        s.setSoTimeout(3000);
        s.getOutputStream().write(req.getBytes("UTF-8"));
        s.getOutputStream().flush();
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        try {
            while (sb.length() < 1000000) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
                String cur = sb.toString();
                if (cur.contains("\"id\":1") && (cur.contains("\"result\"") || cur.contains("\"error\""))) break;
            }
        } catch (Exception te) { /* timeout: use what we have */ }
        s.close();
        return extractIdObject(sb.toString());
    }

    String extractIdObject(String data) {
        int depth = 0, start = -1; String last = null;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = data.substring(start, i + 1);
                    if (obj.contains("\"id\":1")) return obj;
                    last = obj;
                }
            }
        }
        return last != null ? last : "{}";
    }

    /** Simple kid home: big Watch button + hidden admin corner. */
    void showLauncher() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#101418"));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        root.addView(col, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("KidsTV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(36);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        Button watch = new Button(this);
        watch.setText("▶  Watch shows");
        watch.setTextSize(30);
        watch.setPadding(90, 70, 90, 70);
        watch.setOnClickListener(v -> launchKodi());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 70;
        col.addView(watch, blp);

        // Hidden admin escape: a large top-right hotspot. Long-press -> PIN.
        // While a finger is on it the auto-launch is paused (so it won't jump to
        // Kodi mid-hold); lifting off without escaping re-arms it, so a kid who
        // just brushes the corner still gets bounced back to the show.
        View corner = new View(this);
        corner.setOnLongClickListener(v -> { cancelAuto(); mPinShowing = true; promptPin(); return true; });
        corner.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) cancelAuto();
            else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !mPinShowing) armAuto();
            return false;   // don't consume - let the long-press still fire
        });
        root.addView(corner, new FrameLayout.LayoutParams(360, 360, Gravity.TOP | Gravity.END));

        setContentView(root);

        // Auto-launch Kodi so boot / back-out flows into the shows hands-free.
        // Cold boot goes fast; a Home-press waits longer so the admin has time to escape.
        // (Tapping "Watch shows" resumes immediately either way.)
        armAuto();
    }

    /** (Re)arm the auto-launch: fast on cold boot, slower on a Home-press. */
    void armAuto() {
        cancelAuto();
        mAuto = () -> launchKodi();
        mHandler.postDelayed(mAuto, sFirstShow ? AUTO_LAUNCH_MS : HOME_LAUNCH_MS);
        sFirstShow = false;
    }

    /** Admin PIN entry as an on-screen digit pad (no text field / soft keyboard). Auto-checks
        once the entered length matches the PIN; wrong clears, cancel/back re-locks. */
    void promptPin() {
        mPinShowing = true;
        final String pin = currentPin();
        final StringBuilder entered = new StringBuilder();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.parseColor("#101418"));
        col.setPadding(48, 48, 48, 48);

        final TextView disp = new TextView(this);
        disp.setText("Enter admin PIN");
        disp.setTextColor(Color.WHITE);
        disp.setTextSize(26);
        disp.setGravity(Gravity.CENTER);
        disp.setPadding(0, 24, 0, 48);
        col.addView(disp);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setView(col)
            .setCancelable(true)
            .setOnCancelListener(d -> { mPinShowing = false; reLock(); })
            .create();

        String[][] rows = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"Clear","0","⌫"}};
        for (String[] r : rows) {
            LinearLayout rowv = new LinearLayout(this);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : r) {
                Button b = new Button(this);
                b.setText(key);
                b.setTextSize(26);
                b.setOnClickListener(v -> {
                    if ("Clear".equals(key)) entered.setLength(0);
                    else if ("⌫".equals(key)) { if (entered.length() > 0) entered.setLength(entered.length() - 1); }
                    else entered.append(key);
                    StringBuilder mask = new StringBuilder();
                    for (int i = 0; i < entered.length(); i++) mask.append("● ");
                    disp.setText(mask.length() > 0 ? mask.toString().trim() : "Enter admin PIN");
                    if (entered.length() >= pin.length()) {
                        if (entered.toString().equals(pin)) { mPinShowing = false; dlg.dismiss(); unlockToFullTablet(); }
                        else { entered.setLength(0); disp.setText("Wrong — try again"); }
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(10, 10, 10, 10);
                rowv.addView(b, lp);
            }
            col.addView(rowv);
        }
        dlg.show();
    }

    String currentPin() {
        String p = null;
        try { p = Settings.Global.getString(getContentResolver(), "kiosk_pin"); } catch (Exception e) {}
        return (p == null || p.length() == 0) ? DEFAULT_PIN : p;
    }

    /** Drop kiosk enforcement and hand over to the real launcher. Device owner is KEPT
        so re-opening the KidsTV app re-locks. */
    void unlockToFullTablet() {
        bridge(BridgeService.ACTION_REMOVE_OVERLAY);
        try { stopLockTask(); } catch (Exception e) {}
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            try { dpm.clearPackagePersistentPreferredActivities(admin, getPackageName()); } catch (Exception e) {}
        }
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            List<ResolveInfo> homes = getPackageManager().queryIntentActivities(home, 0);
            for (ResolveInfo ri : homes) {
                if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                    home.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    break;
                }
            }
            startActivity(home);
        } catch (Exception e) {}
        finish();
    }

    void showMessage(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(20);
        tv.setBackgroundColor(Color.BLACK);
        setContentView(tv);
    }
}
