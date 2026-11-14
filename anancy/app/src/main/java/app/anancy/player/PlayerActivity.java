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
    // App-scoped on purpose: these must OUTLIVE any single activity instance. Tying them to onDestroy
    // left the tablet with the app running but NO HTTP listener - dead TV-side remote, no Wi-Fi tier.
    private static ControlServer server;
    private static BleServer ble;
    private FrameLayout root;                              // holds the video + overlays (incl. play mode)
    private android.webkit.WebView playWeb;               // toddler play-mode overlay (transparent, over the show)
    private View flash;
    private View napOverlay;
    private LinearLayout emptyState;                      // shown instead of pure black when there is nothing to play
    private boolean napped = false;
    private boolean turnOnArmed = false;                  // setTurnScreenOn is on only while a wake is in flight
    private volatile boolean flashing = false;            // suppress screen-on -> wake during a Light flash
    private android.content.BroadcastReceiver screenRx;   // physical power button sync
    private float preNapBrightness = -1f;
    private boolean adopted = false;                      // running SelfPlayer's engine: never release it here
    private SurfaceView adoptedSurface;
    private SurfaceView videoSurface;
    private Player.Listener sizeListener;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PlayerActivity prior = sRef.get();   // a HOME launch can start a SECOND player in its own task
        if (prior != null && prior != this) try { prior.finish(); } catch (Exception ignored) {}
        sRef = new WeakReference<>(this);
        if (!remoteRole()) { app.anancy.net.WatchLink.publishPlayer(this); app.anancy.net.WatchLink.publishKeys(this); }   // a watch paired to this phone drives this phone only, with its key
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

        // An empty library was indistinguishable from a broken app: both were pure black, forever.
        // Added last so it sits ABOVE the gesture catcher and its button is actually tappable.
        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setBackgroundColor(0xFF101418);
        emptyState.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyState.setVisibility(View.GONE);
        root.addView(emptyState, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        boolean shared = remoteRole();   // "this phone as playback": the running engine is handed over (SelfPlayer)
        if (shared) SelfPlayer.start(this);
        adopted = shared;
        player = shared ? SelfPlayer.player() : new ExoPlayer.Builder(this).setLoadControl(PlayerController.loadControl()).setMediaSourceFactory(PlayerController.mediaSources(this)).build();
        player.setVideoSurfaceView(surface);
        adoptedSurface = shared ? surface : null;
        videoSurface = surface;
        sizeListener = new Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize v) { fitVideo(); }
        };
        player.addListener(sizeListener);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, orr, ob) -> root.post(this::fitVideo));
        if (shared) {
            if (getIntent().getBooleanExtra("wake", false)) { setShowWhenLocked(true); setTurnScreenOn(true); }
            if (getIntent().getBooleanExtra("playmode", false)) main.post(this::doStartPlayMode);
        }
        // volume + mute are managed by PlayerController.setAudio(): system media volume + a persisted mute
        if (!shared) player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_READY) Log.i(TAG, "STATE_READY (playing muted)"); }
        });

        // setAudio FIRST: it loads the persisted mute. Attaching before it meant the engine was built
        // with the default muted=false and sat at volume 1.0 until the next apply - an audible window
        // on every cold boot, since setAudio also floors the system stream at 50%.
        if (!shared) {
            PlayerController.get().setAudio(this);   // system media volume + persisted mute; never boots stuck silent
            PlayerController.get().attach(player);
            PlayerController.get().onLibraryLoaded = () -> showEmpty(PlayerController.get().libraryEmpty());
            PlayerController.get().loadLibrary(this);   // scans, builds playlists (Shuffle All + per-show), starts
            startServers(this);
        }
        if (!remoteRole()) HotspotService.applyMode(this, HotspotService.mode(this));   // connectivity level: keeps the TV hotspot up when chosen

        // Physical POWER button = same as the remote's Power: screen off -> nap (pause), screen on -> wake (play).
        // Apps can't intercept the power key itself, but the screen-state broadcasts give the same effect.
        screenRx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) { if (!napped) { setNapScreen(true); PlayerController.get().pause(); } }
                else if (Intent.ACTION_SCREEN_ON.equals(a)) { disarmTurnOn(); if (napped && !flashing) { setNapScreen(false); PlayerController.get().play(); } }
            }
        };
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        try { registerReceiver(screenRx, sf); } catch (Exception ignored) {}
    }

    /** Draw the video at its real shape. Screens that aren't 16:9 get black bars instead of a stretched
        picture, and on a phone the bars also cover the camera hole so it doesn't look like part of the show. */
    private void fitVideo() {
        if (player == null || videoSurface == null) return;
        androidx.media3.common.VideoSize v = player.getVideoSize();
        int W = root.getWidth(), H = root.getHeight();
        if (v.width <= 0 || v.height <= 0 || W <= 0 || H <= 0) return;
        float r = v.width * v.pixelWidthHeightRatio / v.height;
        int w = W, h = Math.round(W / r);
        if (h > H) { h = H; w = Math.round(H * r); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoSurface.getLayoutParams();
        if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return;
        lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
        videoSurface.setLayoutParams(lp);
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
        else if (SelfPlayer.running()) PlayerController.get().toggle();   // showing small in the remote: nothing to black out
    }

    // --- toddler PLAY MODE: a transparent WebView overlay ON TOP of the running show (the video is the
    //     background). Playback keeps going; the toddler-lock sparkles/letters render over it. Audio is
    //     governed by WebAudioBridge (default gain 0 = silent). Exits via the playground's timed code. --
    /** True while the toddler play-mode overlay is up (so remotes can show an on/off toggle). */
    public static boolean isPlayModeActive() { PlayerActivity a = sRef.get(); return a != null && a.playWeb != null; }
    public static void startPlayMode() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doStartPlayMode);
        else SelfPlayer.openForPlayMode();   // the overlay needs the fullscreen player
    }
    public static void stopPlayMode() {
        final PlayerActivity a = sRef.get();
        if (a != null) a.main.post(a::doStopPlayMode);
    }
    @SuppressWarnings("SetJavaScriptEnabled")
    private void doStartPlayMode() {
        if (playWeb != null || root == null) return;
        // Turned on from the TV's own remote page: stay there (its Toddler button lights up) and have the
        // playground ready over the show for when the grown-up goes back to it. From a phone or watch,
        // bring the show up as before.
        if (!visible && !showOnRemote) try { startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (Exception ignored) {}
        if (napped) { setNapScreen(false); PlayerController.get().play(); }   // can't play over a black nap screen
        // Opt-in, debug builds only: exposes the playground on a WebView devtools socket so it
        // can be driven over CDP from a dev box, which is the only way to test real multi-finger
        // touch (`adb shell input` can't do more than one). OFF unless asked for, because the
        // kiosk runs a debug build every day and the socket is open to anything on the device:
        //   adb shell settings put global anancy_webview_debug 1   (and 0 to close it again)
        try {
            boolean dbg = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (dbg && Cfg.getInt(this, "anancy_webview_debug", 0) == 1)
                android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        } catch (Exception ignored) {}
        android.webkit.WebView w = new android.webkit.WebView(this);
        android.webkit.WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        // A toddler's two-finger grab was pinch-zooming the playground. The page asks for this
        // too (user-scalable=no), but only the WebView setting can't be worked around.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);   // the playground sizes itself; ignore the system font scale
        w.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
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
    /** True while the TV is "off" (napped). Next/Prev from off should land on a PLAYING episode, not a
        paused frame, because napping paused the player. */
    public static boolean isOff() { PlayerActivity a = sRef.get(); return a != null && a.napped; }
    /** Any transport command un-blacks the screen so you never get audio-with-black-screen. */
    public static void wakeScreen() {
        final PlayerActivity a = sRef.get();
        if (a == null) { new Handler(Looper.getMainLooper()).post(SelfPlayer::needScreen); return; }
        a.main.post(() -> {
            if (a.napped) a.setNapScreen(false); else a.wakeDisplay();
            if (a.pausedByLeaving || !a.visible) a.bringToFront();
        });
    }
    /** On a normal install someone can leave the player (Home). A remote Play after that must bring the
        show back, not start it playing unseen behind the launcher or the lock screen. */
    private void bringToFront() {
        try {
            setShowWhenLocked(true);
            startActivity(new Intent(this, PlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) { Log.w(TAG, "could not bring the player back", e); }
    }
    /** Light a physically-off display (physical power button / doze). FLAG_KEEP_SCREEN_ON keeps it on
        afterwards. Samsung cancels this unless the app may post notifications (self-granted in enterKiosk);
        on battery it also needs the doze whitelist (`dumpsys deviceidle whitelist +app.anancy`). */
    private void wakeDisplay() { forceScreenOn("anancy:wake", 3000); }

    /** Turn a physically-off display on, by two routes.

        1. A wake lock with ACQUIRE_CAUSES_WAKEUP. Samsung's PowerManager cancels it whenever the app's
           notifications are being silenced -- and Do Not Disturb counts. With DND on (one tap in the quick
           settings) every remote Play and Light logged "Screen__On : Cancel (notifications are disabled)"
           and the show played to a dark screen. The lock is still taken: it holds the screen bright for
           `holdMs` when it does work.
        2. turnScreenOn on this activity, then re-launch it. The window manager does that wake as the system,
           and DND does not gate it. The flag is dropped again as soon as the screen is on, or a later
           lockNow() (nap, end of a Light flash) could be undone by the next relayout. */
    private void forceScreenOn(String tag, long holdMs) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        try {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, tag).acquire(holdMs);
        } catch (Exception ignored) {}
        if (pm.isInteractive()) return;
        try {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            turnOnArmed = true;
            startActivity(new Intent(this, PlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            main.postDelayed(this::disarmTurnOn, 3000);   // backstop if SCREEN_ON never arrives
        } catch (Exception e) { Log.w(TAG, "turnScreenOn route failed", e); }
    }
    private void disarmTurnOn() {
        if (!turnOnArmed) return;
        turnOnArmed = false;
        try { setTurnScreenOn(false); } catch (Exception ignored) {}
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
        forceScreenOn("anancy:flash", ms + 400L);
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

    /** Leaving the player on a normal (unlocked) install -- Home, Recents, a notification -- pauses the
        show, the way any video app does, instead of carrying on unseen in the background. It picks up
        again on return. The LOCKED kiosk is left alone: nothing there can leave, and its screen-off
        path (nap) already pauses on its own. */
    private boolean pausedByLeaving = false;
    private boolean visible = false;
    @Override protected void onStop() {
        super.onStop();
        visible = false;
        if (showOnRemote) return;   // gone to the TV's own remote page: the show carries on in its card
        if (isFinishing() || isChangingConfigurations() || napped || player == null || !player.getPlayWhenReady()) return;
        if (lockTaskState() == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED) return;
        pausedByLeaving = true;
        Log.i(TAG, "left the player unlocked -> pause");
        PlayerController.get().pause();
    }
    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (showOnRemote) {   // back from the remote page: take the picture back from its card
            showOnRemote = false;
            if (!adopted && player != null && videoSurface != null) player.setVideoSurfaceView(videoSurface);
        }
        if (!pausedByLeaving) return;
        pausedByLeaving = false;
        Log.i(TAG, "back in the player -> resume" + (napped ? " (no: napped)" : ""));
        if (!napped) PlayerController.get().play();
    }
    private int lockTaskState() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            return am.getLockTaskModeState();
        } catch (Exception e) { return android.app.ActivityManager.LOCK_TASK_MODE_NONE; }
    }

    @Override protected void onResume() {
        super.onResume();
        enterKiosk();
        main.removeCallbacks(pinWatch); main.postDelayed(pinWatch, PIN_WATCH_MS);
        // Coming back from granting the permission (or picking a folder) is exactly the moment the
        // library can suddenly exist, and nothing else would notice until the next cold start.
        if (PlayerController.get().libraryEmpty()) PlayerController.get().rescan(this);
        // A TV no remote has paired with yet shows its pairing code, once per start, until one does.
        // That's the moment of setting it up, with an adult in front of it.
        if (!remoteRole() && !pairPromptShown && !app.anancy.Pairing.everPaired(this)
                && !app.anancy.Pairing.promptOff(this)) {
            pairPromptShown = true;
            main.postDelayed(() -> {
                if (isFinishing() || isPlayModeActive() || app.anancy.Pairing.everPaired(this)) return;
                try {
                    startActivity(new Intent(this, app.anancy.net.QrShareActivity.class)
                            .putExtra(app.anancy.net.QrShareActivity.EXTRA_PAIR_KEY, true)
                            .putExtra(app.anancy.net.QrShareActivity.EXTRA_FIRST_RUN, true));
                } catch (Exception e) { android.util.Log.w("Anancy", "pairing code: " + e); }
            }, 2500);
        }
    }
    private static boolean pairPromptShown = false;

    /** Shown when a scan finds nothing. WHY it found nothing matters: a missing permission is a
        different problem from an empty folder, and offering the folder picker for the first one
        sends someone to a picker that cannot read the card either. */
    private void showEmpty(boolean empty) {
        if (emptyState == null) return;
        if (!empty) { emptyState.setVisibility(View.GONE); return; }
        boolean perm = true;
        try { perm = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager(); }
        catch (Exception ignored) {}
        emptyState.removeAllViews();
        TextView h = new TextView(this);
        h.setText(perm ? "Nothing to play yet" : "BackSeeTV can't read your storage");
        h.setTextColor(Color.WHITE); h.setTextSize(26); h.setGravity(Gravity.CENTER);
        emptyState.addView(h);
        TextView d = new TextView(this);
        d.setText(perm
                ? "No videos found. Point BackSeeTV at the folder your shows are in \u2014 one sub-folder per show."
                : "BackSeeTV needs \"All files access\" before it can see your shows, wherever they are "
                  + "(SD card or this device's own storage). Grant that and the library appears straight away.");
        d.setTextColor(0xFF9AA4B2); d.setTextSize(15); d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(12), 0, dp(24));
        emptyState.addView(d);
        Button b = new Button(this);
        b.setText("Open set-up check"); b.setAllCaps(false);
        b.setOnClickListener(v -> {   // set-up check can change the role, so a locked screen asks first
            if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) showUnlock();
            else startActivity(new Intent(this, app.anancy.SetupActivity.class));
        });
        emptyState.addView(b, new LinearLayout.LayoutParams(dp(260), dp(52)));
        emptyState.setVisibility(View.VISIBLE);
        emptyState.bringToFront();
    }

    // --- kid-proof shell: device-owner lock-task + hidden-gesture admin unlock (PIN) ---
    /** Android's unpin shortcut can't be disabled without device owner, but it needn't be final: if the
        show is still on screen and nobody used the grown-up menu, pin again. Only after a pin that was
        accepted, so someone who declined the prompt isn't asked every couple of seconds. */
    private static final long PIN_WATCH_MS = 1500;
    static final String HOME_ALIAS = "app.anancy.HomeAlias";
    /** Offer the show as a home screen, or stop offering it. Off also undoes a "use as home: Always"
        choice, because Android drops a home it can no longer resolve and falls back to the launcher. */
    public static void setHomeAlias(android.content.Context c, boolean on) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            ComponentName alias = new ComponentName(c.getPackageName(), HOME_ALIAS);
            int want = on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                          : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (pm.getComponentEnabledSetting(alias) != want)
                pm.setComponentEnabledSetting(alias, want, android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception e) { Log.w(TAG, "home alias: " + e); }
    }
    private boolean pinSeen = false;
    private final Runnable pinWatch = new Runnable() { @Override public void run() {
        if (isFinishing()) return;
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) pinSeen = true;
        else if (pinSeen && visible) {
            pinSeen = false;
            if (Cfg.getInt(PlayerActivity.this, "anancy_kiosk", 0) == 1) {
                Log.i(TAG, "unpinned without the grown-up menu -> pin again");
                requestPin();
            }
        }
        main.postDelayed(this, PIN_WATCH_MS);
    }};
    /** The grown-up code was typed: a non-owner pin comes off now, or Android blocks Settings, the remote
        and anything else the menu opens. Device owner keeps its lock; its own screens are allowed in it. */
    private void releasePinForAdmin() {
        if (deviceOwner()) return;
        pinSeen = false; main.removeCallbacks(pinRequest); hidePinHint();
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.i(TAG, "grown-up menu -> unpin");
            try { stopLockTask(); } catch (Exception ignored) {}
        }
    }
    /** Back to the show from the grown-up menu: ask to pin again. Settings and the remote need nothing
        here, because coming back to the player resumes it and that asks anyway. */
    private void repinAfterAdmin() {
        if (deviceOwner() || !visible || Cfg.getInt(this, "anancy_kiosk", 0) != 1) return;
        if (lockTaskState() == android.app.ActivityManager.LOCK_TASK_MODE_NONE) requestPin();
    }
    /** The toddler lock ("anancy_kiosk") changed, possibly from a remote: without this it only applied at the next launch.
        Device owner keeps its documented next-launch behaviour. */
    public static void applyPinSetting() {
        final PlayerActivity a = sRef.get();
        if (a == null) return;
        a.runOnUiThread(() -> { if (!a.isFinishing() && a.visible && !a.deviceOwner()) a.enterKiosk(); });
    }
    private boolean deviceOwner() {
        try { DevicePolicyManager d = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE); return d != null && d.isDeviceOwnerApp(getPackageName()); }
        catch (Exception e) { return false; }
    }
    private void enterKiosk() {
        int on = 0;
        on = Cfg.getInt(this, "anancy_kiosk", 0);   // Settings screen (prefs) or adb Settings.Global
        if (on != 1) {   // gated so dev builds aren't trapped; `settings put global anancy_kiosk 1` to lock
            setHomeAlias(this, false);
            if (!deviceOwner() && lockTaskState() == android.app.ActivityManager.LOCK_TASK_MODE_PINNED) {
                pinSeen = false;
                try { stopLockTask(); } catch (Exception ignored) {}
            }
            return;
        }
        setHomeAlias(this, true);   // Home and restarts come back to the show while it's locked
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) return;   // already held; don't re-prompt
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
                    dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());   // drops the pre-alias entry too
                    dpm.addPersistentPreferredActivity(admin, home, new ComponentName(getPackageName(), HOME_ALIAS));
                } catch (Exception e) {}
            }
            if (deviceOwner()) startLockTask(); else requestPin();
        } catch (Exception e) {}
    }

    /** Ordinary install: show the toddler lock screen first, bright, then ask Android to pin. Android dims
        everything behind its prompt, so the parent gets a moment to read ours before that happens. */
    private static final long PIN_PROMPT_DELAY_MS = 1600;
    private final Runnable pinRequest = () -> {
        if (isFinishing() || !visible || Cfg.getInt(this, "anancy_kiosk", 0) != 1) { hidePinHint(); return; }
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) return;
        try { startLockTask(); } catch (Exception ignored) {}
    };
    private void requestPin() {
        main.removeCallbacks(pinRequest);
        showPinHint();
        main.postDelayed(pinRequest, PIN_PROMPT_DELAY_MS);
    }

    // ---- Toddler lock screen ------------------------------------------------------------------------
    // Android's own pin prompt is small and easy to misread. Behind it, say in big letters what to press.
    // Every maker draws that prompt somewhere else (Samsung: a box in the middle; AOSP: a card along the
    // bottom; others unknown) and we cannot ask where, so there is no arrow and one layout: the call to
    // action sits in a strip along the top, clear of the prompts we have seen, and the rest is read in the
    // bright moment before Android's prompt dims and covers it.
    private android.view.View pinHint;
    private long pinHintUntil = 0;
    private static final long PIN_HINT_MAX_MS = 25000;
    private final Runnable pinHintCheck = new Runnable() { @Override public void run() {
        if (pinHint == null) return;
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE
                || android.os.SystemClock.elapsedRealtime() > pinHintUntil || isFinishing()) { hidePinHint(); return; }
        main.postDelayed(this, 250);
    }};
    private void hidePinHint() {
        main.removeCallbacks(pinHintCheck);
        if (pinHint != null && root != null) root.removeView(pinHint);
        pinHint = null;
    }
    private void showPinHint() {
        if (root == null || deviceOwner()) return;
        if (lockTaskState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE) return;
        hidePinHint();
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int h = root.getHeight() > 0 ? root.getHeight() : m.heightPixels;
        // Scale to the screen: a landscape phone is ~320dp tall, a tablet 600dp or more.
        float k = Math.max(0.55f, Math.min(1f, h / m.density / 600f));
        String btn = "samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER) ? "OK" : "GOT IT";
        // Android dims everything behind its prompt, so only the brightest colours still read: white and yellow.
        final int YELLOW = 0xFFFFEE33;

        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xF2070A10);   // full screen: the show stops competing for attention
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        String pre = "Tap ", mid = " " + btn + " ", post = " to switch on the toddler lock";
        android.text.SpannableString head = new android.text.SpannableString(pre + mid + post);
        head.setSpan(new android.text.style.BackgroundColorSpan(YELLOW), pre.length(), pre.length() + mid.length(), 0);
        head.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000), pre.length(), pre.length() + mid.length(), 0);
        TextView headline = hintText("", Math.round(46 * k), Color.WHITE, true);
        headline.setText(head); headline.setMaxLines(1); headline.setShadowLayer(0, 0, 0, 0);
        box.addView(headline);

        TextView what = hintText("Home, Back and Recents stop working until a grown-up unlocks it.",
                Math.round(30 * k), Color.WHITE, false);
        what.setPadding(0, dp(Math.round(40 * k)), 0, 0);
        box.addView(what);
        TextView owner = hintText("Setting up a dedicated kids' device? Install BackSeeTV as a device-owner "
                + "kiosk and it locks by itself, with no prompt.", Math.round(26 * k), YELLOW, true);
        owner.setPadding(0, dp(Math.round(24 * k)), 0, 0);
        box.addView(owner);
        for (int i = 0; i < box.getChildCount(); i++) ((TextView) box.getChildAt(i)).setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        blp.leftMargin = blp.rightMargin = dp(24); blp.topMargin = dp(Math.round(14 * k));
        f.addView(box, blp);
        f.setOnClickListener(v -> hidePinHint());   // after "No thanks", a tap anywhere clears it
        root.addView(f, new FrameLayout.LayoutParams(-1, -1));
        pinHint = f;

        pinHintUntil = android.os.SystemClock.elapsedRealtime() + PIN_HINT_MAX_MS;
        main.postDelayed(pinHintCheck, 400);
    }
    private TextView hintText(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(color); v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
        return v;
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
        final LinearLayout keys = new LinearLayout(this); keys.setOrientation(LinearLayout.VERTICAL);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(padLayout(col, keys)).setCancelable(true)
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
            keys.addView(rowv);
        }
        dlg.setOnDismissListener(d -> main.removeCallbacks(tick[0]));
        status.run(); main.postDelayed(tick[0], 1000); dlg.show(); widenIfShort(dlg);
    }
    /** A phone on its side is only ~360dp tall: stacked under the code, the keypad's bottom rows
        (7 8 9 / Clear 0 ⌫) fell off the dialog, so a code with a 0 could never be typed. On a short
        screen the keypad sits beside the code instead; on a tablet it stays underneath. */
    private View padLayout(LinearLayout header, LinearLayout keys) {
        boolean shortScreen = getResources().getConfiguration().screenHeightDp < 560;
        if (!shortScreen) { header.addView(keys); return header; }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFF101418);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.setGravity(Gravity.CENTER);
        row.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(keys, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
    /** The side-by-side pad needs more than the default dialog width. */
    private void widenIfShort(AlertDialog dlg) {
        if (getResources().getConfiguration().screenHeightDp >= 560 || dlg.getWindow() == null) return;
        int w = Math.min(getResources().getDisplayMetrics().widthPixels * 9 / 10, dp(760));
        dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        final LinearLayout keys = new LinearLayout(this); keys.setOrientation(LinearLayout.VERTICAL);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(padLayout(col, keys)).setCancelable(true)
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
            keys.addView(rowv);
        }
        dlg.show(); widenIfShort(dlg);
    }

    /** After the PIN: the TV's own admin menu. The kiosk has to be fully usable with no phone or
        watch around, so "Remote" is the complete remote UI running locally against this player. */
    /** After the grown-up unlock: straight to the remote page. It already has everything the old
        in-between menu offered (Settings in its menu, the Toddler button, Back to return to the show),
        and Exit to the tablet's own home screen now lives in Settings. On a phone that is also a
        screen, the remote page is its own. */
    private void showAdminMenu(final float restoreBrightness) {
        setBrightness(napped ? 0.02f : restoreBrightness);
        if (remoteRole()) { backToRemote(); return; }
        releasePinForAdmin();
        showOnRemote = true;   // the show keeps playing, in the remote page's card
        startActivity(new Intent(this, RemoteActivity.class).putExtra("local", true));
    }
    /** True while the TV's own remote page is showing the show in its card (don't pause on leaving). */
    private static volatile boolean showOnRemote = false;
    /** The TV's running engine, for its own remote page to draw in a card. Null on a phone remote
        (that one uses SelfPlayer) or when no show is up. */
    public static ExoPlayer tvPlayer() {
        PlayerActivity a = sRef.get();
        return a != null && !a.adopted && !a.isFinishing() ? a.player : null;
    }
    /** Settings' "Exit to the home screen": leave the kiosk for the tablet's own launcher. */
    public static boolean exitToLauncher() {
        PlayerActivity a = sRef.get();
        if (a == null) return false;
        a.main.post(a::unlock);
        return true;
    }

    private String currentPin() { return Cfg.getStr(this, "anancy_pin", "1234"); }
    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b; getWindow().setAttributes(lp);
    }

    /** Drop kiosk enforcement and hand to the real launcher. Device owner is KEPT, and re-opening
        the Anancy player re-locks (enterKiosk). Reaching here already passed the PIN. */
    private void unlock() {
        pinSeen = false; main.removeCallbacks(pinWatch); main.removeCallbacks(pinRequest); hidePinHint();
        setHomeAlias(this, false);   // an adult's own launcher is home again, including any "Always" choice
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
    @Override public void onBackPressed() {
        if (remoteRole() && lockTaskState() == android.app.ActivityManager.LOCK_TASK_MODE_NONE) backToRemote();
    }
    /** "This phone as playback": a device whose role is remote, with this player running as well. */
    private boolean remoteRole() { return "remote".equals(getSharedPreferences("anancy", MODE_PRIVATE).getString("role", "")); }
    /** Leave fullscreen for the remote; the show carries on in this device's card. */
    private void backToRemote() {
        pinSeen = false; main.removeCallbacks(pinWatch); main.removeCallbacks(pinRequest); hidePinHint();
        setHomeAlias(this, false);
        try { stopLockTask(); } catch (Exception ignored) {}
        startActivity(new Intent(this, RemoteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        finish();
    }
    public static boolean isRunning() { PlayerActivity a = sRef.get(); return a != null && !a.isFinishing(); }
    static void finishLarge() { PlayerActivity a = sRef.get(); if (a != null) a.finish(); }
    /** The control servers are app-scoped: created once, whatever is hosting the engine. */
    static void startServers(android.content.Context c) {
        if (server == null) { server = new ControlServer(c.getApplicationContext(), PlayerController.get()); server.start(); }
        if (ble == null) { ble = new BleServer(c.getApplicationContext(), PlayerController.get()); ble.start(); }   // BLE control tier (offline/car/AA)
    }
    /** Just the Bluetooth side of this device's TV servers (the remote drops it when Bluetooth goes off). */
    public static void setBleServer(android.content.Context c, boolean on) {
        if (on) {   // a fresh one: an old one may have given up while Bluetooth was off
            if (server == null) return;
            try { if (ble != null) ble.stop(); } catch (Exception ignored) {}
            ble = new BleServer(c.getApplicationContext(), PlayerController.get()); ble.start();
        }
        else { try { if (ble != null) ble.stop(); } catch (Exception ignored) {} ble = null; }
    }
    static void stopServers() {
        try { if (ble != null) ble.stop(); } catch (Exception ignored) {}
        ble = null;
        try { if (server != null) server.stop(); } catch (Exception ignored) {}
        server = null;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // "Lock volume buttons": a child holding the tablet can't blast it or silence it by
            // accident. Consumed either way, so the system slider never shows; remotes still work.
            // Toddler mode always locks them: mashing volume-up there raises the level and clears the
            // saved mute, so the show would come back louder and with sound after exit.
            if (e.getAction() == KeyEvent.ACTION_DOWN && playWeb == null && !Cfg.volKeysLocked(this))
                PlayerController.get().nudge(k == KeyEvent.KEYCODE_VOLUME_UP ? 0.1f : -0.1f);
            return true;   // consume -> no system volume slider (kid-proof)
        }
        return super.dispatchKeyEvent(e);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Exception ignored) {}
        // servers are app-scoped and deliberately NOT stopped here (see the field comment)
        if (adopted) {   // the engine lives on in SelfPlayer; it only stops drawing here
            if (sRef.get() == this) sRef = new WeakReference<>(null);
            if (player != null) { player.removeListener(sizeListener); player.clearVideoSurfaceView(adoptedSurface); }
            player = null;
            super.onDestroy();
            return;
        }
        if (sRef.get() == this) {   // only the CURRENT instance owns the shared engine
            PlayerController.get().detach();
            sRef = new WeakReference<>(null);
        }
        if (player != null) { player.release(); player = null; }   // each instance still releases its OWN ExoPlayer
        super.onDestroy();
    }
}
