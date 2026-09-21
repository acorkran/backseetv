package app.anancy.player;

import app.anancy.Cfg;
import app.anancy.RouterActivity;
import app.anancy.admin.AdminGesture;
import app.anancy.admin.AdminReceiver;
import app.anancy.admin.PatternView;
import app.anancy.net.BleServer;
import app.anancy.net.ControlServer;
import app.anancy.net.HotspotService;
import app.anancy.remote.RemoteActivity;
import app.anancy.remote.SettingsActivity;

// ============================================================================
//  Anancy v2 player core. Own ExoPlayer engine, no Kodi, no bridge. ExoPlayer's
//  native playlist (setMediaItems + shuffle + REPEAT_MODE_ALL) = infinite
//  shuffle-all that auto-advances at each file's real end, so the Kodi
//  "stuck past EOF" wedge cannot occur. Starts MUTED (audio-safety rule).
//  A PlayerController exposes it to the ControlServer (:8090 + mDNS).
// ============================================================================

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.lang.ref.WeakReference;
import java.util.List;

public class PlayerActivity extends Activity {
    static final String TAG = "Anancy";
    private static WeakReference<PlayerActivity> sRef = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private ControlServer server;
    private BleServer ble;
    private FrameLayout root;                              // holds the video + overlays (incl. play mode)
    private android.webkit.WebView playWeb;               // toddler play-mode overlay (transparent, over the show)
    private View flash;
    private View napOverlay;
    private boolean napped = false;
    private volatile boolean flashing = false;            // suppress screen-on -> wake during a Light flash
    private android.content.BroadcastReceiver screenRx;   // physical power button sync
    private float preNapBrightness = -1f;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sRef = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        root = new FrameLayout(this);
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

        // admin unlock: a hidden, configurable gesture ANYWHERE on screen (default: 4 four-finger
        // taps in 3s) -> PIN pad. No "guess the corner" nonsense; the multi-finger press is what
        // keeps a toddler from tripping it. Tunable via Settings.Global (see AdminGesture).
        View gestureCatcher = new View(this);
        gestureCatcher.setOnTouchListener(new AdminGesture(this, this::showUnlock));
        root.addView(gestureCatcher, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        // volume + mute are managed by PlayerController.setAudio(): system media volume + a persisted mute
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_READY) Log.i(TAG, "STATE_READY (playing muted)"); }
        });

        PlayerController.get().attach(player);
        PlayerController.get().setAudio(this);   // system media volume + persisted mute; never boots stuck silent
        PlayerController.get().loadLibrary(this);   // scans, builds playlists (Shuffle All + per-show), starts
        server = new ControlServer(this, PlayerController.get());
        server.start();
        ble = new BleServer(this, PlayerController.get());   // BLE control tier (offline/car/AA)
        ble.start();
        HotspotService.applyMode(this, HotspotService.mode(this));   // connectivity level: keeps the TV hotspot up when chosen

        // Physical POWER button = same as the remote's Power: screen off -> nap (pause), screen on -> wake (play).
        // Apps can't intercept the power key itself, but the screen-state broadcasts give the same effect.
        screenRx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) { if (!napped) { setNapScreen(true); PlayerController.get().pause(); } }
                else if (Intent.ACTION_SCREEN_ON.equals(a)) { if (napped && !flashing) { setNapScreen(false); PlayerController.get().play(); } }
            }
        };
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        try { registerReceiver(screenRx, sf); } catch (Exception ignored) {}
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

    // --- toddler PLAY MODE: a transparent WebView overlay ON TOP of the running show (the video is the
    //     background). Playback keeps going; the toddler-lock sparkles/letters render over it. Audio is
    //     governed by WebAudioBridge (default gain 0 = silent). Exits via the playground's timed code. --
    /** True while the toddler play-mode overlay is up (so remotes can show an on/off toggle). */
    public static boolean isPlayModeActive() { PlayerActivity a = sRef.get(); return a != null && a.playWeb != null; }
    public static void startPlayMode() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doStartPlayMode);
    }
    public static void stopPlayMode() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doStopPlayMode);
    }
    @SuppressWarnings("SetJavaScriptEnabled")
    private void doStartPlayMode() {
        if (playWeb != null || root == null) return;
        if (napped) { setNapScreen(false); PlayerController.get().play(); }   // can't play over a black nap screen
        android.webkit.WebView w = new android.webkit.WebView(this);
        android.webkit.WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        w.setBackgroundColor(Color.TRANSPARENT);   // let the video show through
        w.addJavascriptInterface(new WebAudioBridge(this), "AndroidAudio");
        w.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void exit() { main.post(PlayerActivity.this::doStopPlayMode); }
        }, "AndroidExit");
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView v, String url) {
                // straight into the locked playground + make the playground chrome transparent so the show
                // is the background (sparkles/letters stay; the adult unlock + settings panels stay opaque).
                v.evaluateJavascript(
                    "(function(){var st=document.createElement('style');" +
                    "st.textContent='html,body{background:transparent!important} .lock-overlay{background:transparent!important}';" +
                    "document.head.appendChild(st);" +
                    "window.ToddlerLock&&window.ToddlerLock.lock&&window.ToddlerLock.lock();})();", null);
            }
        });
        w.loadUrl("file:///android_asset/toddlerlock/index.html");
        root.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        playWeb = w;
        applyPlayModeSound();
    }
    /** Sound source for play mode (anancy_playmode_sound, default "muted"): "wav" = the letter clips
        (show silenced), "video" = the show's audio behind (no clips), "muted" = both silent. The show
        override is temporary — it never changes the persisted mute; doStopPlayMode restores it. */
    private void applyPlayModeSound() {
        String snd = Cfg.getStr(this, "anancy_playmode_sound", "muted");
        if ("wav".equals(snd))        { WebAudioBridge.setGain(1f); PlayerController.get().overrideShowAudio(false); }
        else if ("video".equals(snd)) { WebAudioBridge.setGain(0f); PlayerController.get().overrideShowAudio(true); }
        else                          { WebAudioBridge.setGain(0f); PlayerController.get().overrideShowAudio(false); }
    }
    private void doStopPlayMode() {
        if (playWeb == null) return;
        try { root.removeView(playWeb); } catch (Exception ignored) {}
        try { playWeb.destroy(); } catch (Exception ignored) {}
        playWeb = null;
        WebAudioBridge.setGain(0f);                       // silence the playground clips
        PlayerController.get().overrideShowAudio(null);   // restore the persisted mute
    }
    /** Any transport command un-blacks the screen so you never get audio-with-black-screen. */
    public static void wakeScreen() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> { if (a.napped) a.setNapScreen(false); else a.wakeDisplay(); });
    }
    /** Light a physically-off display (physical power button / doze). FLAG_KEEP_SCREEN_ON keeps it on
        afterwards. Samsung cancels this unless the app may post notifications (self-granted in enterKiosk);
        on battery it also needs the doze whitelist (`dumpsys deviceidle whitelist +app.anancy`). */
    private void wakeDisplay() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive())
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "anancy:wake").acquire(3000);
        } catch (Exception ignored) {}
    }
    private void doPower() {
        if (!napped) { setNapScreen(true); PlayerController.get().pause(); sleepDisplay(); }
        else { setNapScreen(false); PlayerController.get().play(); }
    }
    /** Sleep-timer end: same as the power button — TV off, display off. */
    public static void napNow() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> { if (!a.napped) { a.setNapScreen(true); a.sleepDisplay(); } });
    }
    /** Physical-power-button parity: actually turn the display OFF (device-owner lockNow; the keyguard is
        disabled so it wakes straight back into the show). Without owner rights we keep the dim black overlay. */
    private void sleepDisplay() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) dpm.lockNow();
        } catch (Exception ignored) {}
    }
    private void setNapScreen(boolean nap) {
        napped = nap;
        PlayerController.get().setNapped(nap);
        // Audio-safety: powering a screen OFF PERSISTS mute, so a later resume (including a spurious
        // wake on a flaky device) stays silent until the user deliberately raises the volume. Root cause
        // of the past "napped tablet blasted audio" incidents: nap paused but never muted.
        if (nap) PlayerController.get().setMuted(true);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        napOverlay.setVisibility(nap ? View.VISIBLE : View.GONE);
        lp.screenBrightness = nap ? 0.02f : -1.0f;   // -1 = system default -> reliably ON again
        getWindow().setAttributes(lp);
        // FLAG_KEEP_SCREEN_ON fights lockNow() while the show is playing -> the power button was hit-and-
        // miss (you had to pause first). Drop it when napping so the display can actually sleep; restore
        // it on wake. Un-napping also lights a physically-off display (else it's audio on a dark screen).
        if (nap) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); wakeDisplay(); }
    }

    // screen-as-flashlight, called by ControlServer /flash
    public static void flash(int ms) {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(() -> a.doFlash(ms));
    }
    private void doFlash(int ms) {
        ms = Math.max(200, Math.min(15000, ms));   // Settings offers up to 10s
        flashing = true;   // the wake lock fires SCREEN_ON — don't let that count as a "wake" (un-nap)
        // If napped, the display has actually gone to sleep — a brightness change alone shows nothing.
        // Force the screen ON (brief wake lock, auto-released) so the white flash is visible.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null)
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "anancy:flash")
                  .acquire(ms + 400L);
        } catch (Exception e) {}
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f; getWindow().setAttributes(lp);
        flash.setVisibility(View.VISIBLE);
        final int dur = ms;
        main.postDelayed(() -> {
            flash.setVisibility(View.GONE);
            // re-derive from the CURRENT nap state (not a stale snapshot) so a wake during
            // the flash isn't dragged back to black when the flash ends.
            WindowManager.LayoutParams p = getWindow().getAttributes();
            p.screenBrightness = napped ? 0.02f : -1.0f;
            getWindow().setAttributes(p);
            main.postDelayed(() -> flashing = false, 600);   // outlast the wake lock's trailing SCREEN_ON
            if (napped) main.postDelayed(this::sleepDisplay, 700);   // "3s of white, then off again"
        }, dur);
    }

    @Override protected void onResume() { super.onResume(); enterKiosk(); }

    // --- kid-proof shell: device-owner lock-task + hidden-gesture admin unlock (PIN) ---
    private void enterKiosk() {
        int on = 0;
        on = Cfg.getInt(this, "anancy_kiosk", 0);   // Settings screen (prefs) or adb Settings.Global
        if (on != 1) return;   // gated so dev builds aren't trapped; `settings put global anancy_kiosk 1` to lock
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, AdminReceiver.class);
                try { dpm.setLockTaskPackages(admin, new String[]{getPackageName()}); } catch (Exception e) {}
                try { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE); } catch (Exception e) {}
                try { dpm.setStatusBarDisabled(admin, true); } catch (Exception e) {}
                try { dpm.setKeyguardDisabled(admin, true); } catch (Exception e) {}   // no lock screen blocking boot
                // Samsung's PowerManager silently cancels an app's screen-wake when the app's notifications
                // are disabled ("Screen__On : Cancel (notifications are disabled)"), which broke a remote
                // Play / Light from a physically-off display. The kiosk grants itself POST_NOTIFICATIONS.
                // API 33+ only: it doesn't exist earlier (notifications are on by default), and on Android 10 the
                // grant of the unknown permission blocks in DevicePolicyManager -> onResume froze -> boot ANR.
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    try { dpm.setPermissionGrantState(admin, getPackageName(), "android.permission.POST_NOTIFICATIONS",
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED); } catch (Exception e) {}
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
    /** Admin PIN pad (on-screen digit grid, no soft keyboard). Auto-checks when the entered length
        matches; wrong clears; cancel/back keeps the kiosk locked. Default PIN 1234, override with
        `adb shell settings put global anancy_pin <PIN>`. */
    private boolean pinShowing = false;
    /** Which unlock the hidden gesture opens: the plain PIN pad, or the toddler-proof timed-code
        challenge (type a random 6-digit code shown on screen within 30s). anancy_unlock_style. */
    private void showUnlock() {
        String st = Cfg.getStr(this, "anancy_unlock_style", "challenge");
        if ("challenge".equals(st)) showChallenge();
        else if ("pattern".equals(st) && !Cfg.getStr(this, "anancy_pattern", "").isEmpty()) showPattern();
        else showPinPad();   // fall back to PIN if pattern is selected but none set yet (never lock out)
    }

    /** (Experimental) our own 3x3 swipe-pattern unlock — checked against the Anancy-stored pattern. */
    private void showPattern() {
        if (pinShowing) return;
        pinShowing = true;
        final float restore = getWindow().getAttributes().screenBrightness;
        setBrightness(-1f);
        final String stored = Cfg.getStr(this, "anancy_pattern", "");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setBackgroundColor(0xFF101418); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(20), dp(24), dp(20));
        final TextView disp = new TextView(this);
        disp.setText("Draw your unlock pattern"); disp.setTextColor(Color.WHITE); disp.setTextSize(20);
        disp.setGravity(Gravity.CENTER); disp.setPadding(0, 0, 0, dp(16)); col.addView(disp);
        final PatternView pv = new PatternView(this);
        col.addView(pv, new LinearLayout.LayoutParams(dp(280), dp(280)));
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(col).setCancelable(true)
                .setOnCancelListener(d -> { pinShowing = false; setBrightness(napped ? 0.02f : restore); }).create();
        pv.setOnPattern(p -> {
            if (p.equals(stored)) { pinShowing = false; dlg.dismiss(); showAdminMenu(restore); }
            else { disp.setText("Wrong — try again"); pv.postDelayed(pv::reset, 350); }
        });
        dlg.show();
    }

    /** Randomized-PIN unlock: a random code of anancy_chal_len digits is shown; copy it within
        anancy_chal_secs seconds and within anancy_chal_tries wrong attempts. Nothing to remember —
        a toddler can't read+copy it in time. Out of tries OR time drops back to the locked screen. */
    private void showChallenge() {
        if (pinShowing) return;
        pinShowing = true;
        final int len   = clampInt(Cfg.getInt(this, "anancy_chal_len", 6), 4, 8);
        final int secs  = clampInt(Cfg.getInt(this, "anancy_chal_secs", 30), 10, 120);
        final int[] tries = { clampInt(Cfg.getInt(this, "anancy_chal_tries", 3), 1, 9) };
        final float restore = getWindow().getAttributes().screenBrightness;
        setBrightness(-1f);
        final StringBuilder code = new StringBuilder(), entered = new StringBuilder();
        for (int i = 0; i < len; i++) code.append((char) ('0' + (int) (Math.random() * 10)));
        final int[] remaining = { secs };

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setBackgroundColor(0xFF101418);
        col.setPadding(dp(24), dp(20), dp(24), dp(20));
        final TextView instr = new TextView(this);
        instr.setText("Grown-up unlock — type this code"); instr.setTextColor(0xFF9AA4B2); instr.setTextSize(15);
        instr.setGravity(Gravity.CENTER); instr.setPadding(0, 0, 0, dp(8)); col.addView(instr);
        final TextView codeView = new TextView(this);
        codeView.setTextColor(0xFF8FD694); codeView.setTextSize(34); codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.25f); col.addView(codeView);
        StringBuilder sp = new StringBuilder(); for (int i = 0; i < len; i++) sp.append(code.charAt(i)).append(' ');
        codeView.setText(sp.toString().trim());
        final TextView disp = new TextView(this);
        disp.setTextColor(Color.WHITE); disp.setTextSize(22); disp.setGravity(Gravity.CENTER);
        disp.setPadding(0, dp(6), 0, dp(14)); col.addView(disp);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(col).setCancelable(true)
                .setOnCancelListener(d -> { pinShowing = false; setBrightness(napped ? 0.02f : restore); })
                .create();

        final Runnable[] tick = new Runnable[1], fail = new Runnable[1];
        final Runnable status = () -> disp.setText(remaining[0] + "s  ·  " + tries[0] + (tries[0] == 1 ? " try" : " tries")
                + (entered.length() > 0 ? "  ·  " + entered : ""));
        fail[0] = () -> { pinShowing = false; main.removeCallbacks(tick[0]);
            try { dlg.dismiss(); } catch (Exception ignored) {} setBrightness(napped ? 0.02f : restore); };
        tick[0] = () -> {
            if (!dlg.isShowing()) return;
            remaining[0]--;
            if (remaining[0] <= 0) { disp.setText("time up"); main.postDelayed(fail[0], 700); return; }
            status.run(); main.postDelayed(tick[0], 1000);
        };

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"Clear", "0", "⌫"}};
        for (String[] r : rows) {
            LinearLayout rowv = new LinearLayout(this); rowv.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : r) {
                Button b = new Button(this); b.setText(key); b.setTextSize(20);
                b.setOnClickListener(v -> {
                    if (!dlg.isShowing()) return;
                    if ("Clear".equals(key)) entered.setLength(0);
                    else if ("⌫".equals(key)) { if (entered.length() > 0) entered.setLength(entered.length() - 1); }
                    else if (entered.length() < len) entered.append(key);
                    if (entered.length() == len) {                                 // a full attempt
                        if (entered.toString().contentEquals(code)) { pinShowing = false; main.removeCallbacks(tick[0]); dlg.dismiss(); showAdminMenu(restore); return; }
                        tries[0]--; entered.setLength(0);
                        if (tries[0] <= 0) { disp.setText("locked — try again"); main.postDelayed(fail[0], 700); return; }
                        disp.setText("wrong — " + tries[0] + (tries[0] == 1 ? " try" : " tries") + " left"); return;
                    }
                    status.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4)); rowv.addView(b, lp);
            }
            col.addView(rowv);
        }
        dlg.setOnDismissListener(d -> main.removeCallbacks(tick[0]));
        status.run(); main.postDelayed(tick[0], 1000); dlg.show();
    }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void showPinPad() {
        if (pinShowing) return;
        pinShowing = true;
        final float restore = getWindow().getAttributes().screenBrightness;
        setBrightness(-1f);   // ensure the pad is visible even if the screen was napped/dim
        final String pin = currentPin();
        final StringBuilder entered = new StringBuilder();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF101418);
        col.setPadding(dp(24), dp(24), dp(24), dp(24));

        final TextView disp = new TextView(this);
        disp.setText("Enter admin PIN");
        disp.setTextColor(Color.WHITE); disp.setTextSize(24); disp.setGravity(Gravity.CENTER);
        disp.setPadding(0, dp(12), 0, dp(24));
        col.addView(disp);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(col).setCancelable(true)
                .setOnCancelListener(d -> { pinShowing = false; setBrightness(napped ? 0.02f : restore); })
                .create();

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"Clear", "0", "⌫"}};
        for (String[] r : rows) {
            LinearLayout rowv = new LinearLayout(this);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : r) {
                Button b = new Button(this);
                b.setText(key); b.setTextSize(22);
                b.setOnClickListener(v -> {
                    if ("Clear".equals(key)) entered.setLength(0);
                    else if ("⌫".equals(key)) { if (entered.length() > 0) entered.setLength(entered.length() - 1); }
                    else entered.append(key);
                    StringBuilder mask = new StringBuilder();
                    for (int i = 0; i < entered.length(); i++) mask.append("● ");
                    disp.setText(mask.length() > 0 ? mask.toString().trim() : "Enter admin PIN");
                    if (entered.length() >= pin.length()) {
                        if (entered.toString().equals(pin)) { pinShowing = false; dlg.dismiss(); showAdminMenu(restore); }
                        else { entered.setLength(0); disp.setText("Wrong — try again"); }
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                rowv.addView(b, lp);
            }
            col.addView(rowv);
        }
        dlg.show();
    }

    /** After the PIN: the TV's own admin menu. The kiosk has to be fully usable with no phone or
        watch around, so "Remote" is the complete remote UI running locally against this player. */
    private void showAdminMenu(final float restoreBrightness) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setBackgroundColor(0xFF101418);
        col.setPadding(dp(24), dp(16), dp(24), dp(16));
        TextView h = new TextView(this);
        h.setText("Anancy admin"); h.setTextColor(Color.WHITE); h.setTextSize(22);
        h.setGravity(Gravity.CENTER); h.setPadding(0, dp(8), 0, dp(16));
        col.addView(h);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(col).setCancelable(true)
                .setOnCancelListener(d -> setBrightness(napped ? 0.02f : restoreBrightness)).create();
        String[][] items = {
                {"Remote  ·  playlist, up next, controls", "remote"},
                {"Settings  ·  unlock gesture, PIN, Wi-Fi, camera", "settings"},
                {"Toddler play mode", "playmode"},
                {"Exit to launcher", "exit"},
                {"Back to the show", "back"}};
        for (final String[] it : items) {
            Button b = new Button(this);
            b.setText(it[0]); b.setTextSize(18); b.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
            lp.setMargins(0, dp(4), 0, dp(4));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                dlg.dismiss();
                switch (it[1]) {
                    case "remote":   startActivity(new Intent(this, RemoteActivity.class).putExtra("local", true)); break;
                    case "settings": startActivity(new Intent(this, SettingsActivity.class)); break;
                    case "playmode": startPlayMode(); return;
                    case "exit":     unlock(); return;
                    default: break;
                }
                setBrightness(napped ? 0.02f : restoreBrightness);
            });
            col.addView(b);
        }
        dlg.show();
    }

    private String currentPin() { return Cfg.getStr(this, "anancy_pin", "1234"); }
    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b; getWindow().setAttributes(lp);
    }

    /** Drop kiosk enforcement and hand to the real launcher. Device owner is KEPT, and re-opening
        the Anancy player re-locks (enterKiosk). Reaching here already passed the PIN. */
    private void unlock() {
        try { stopLockTask(); } catch (Exception e) {}
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, AdminReceiver.class);
                try { dpm.setStatusBarDisabled(admin, false); } catch (Exception e) {}
                try { dpm.clearPackagePersistentPreferredActivities(admin, getPackageName()); } catch (Exception e) {}
            }
        } catch (Exception e) {}
        // hand off to a real (non-Anancy) home so we don't just bounce back to ourselves
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                    home.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    break;
                }
            }
            startActivity(home);
        } catch (Exception e) {}
        finish();
    }

    /** Kiosk: Back never leaves the show. (Lock-task blocks Home/Recents but delivers Back; finishing
        here would bounce through the launcher-router and leave a focus-stealing permission activity.) */
    @Override public void onBackPressed() { }

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
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Exception ignored) {}
        if (ble != null) { ble.stop(); ble = null; }
        if (server != null) { server.stop(); server = null; }
        PlayerController.get().detach();
        if (player != null) { player.release(); player = null; }
        if (sRef.get() == this) sRef = new WeakReference<>(null);
        super.onDestroy();
    }
}
