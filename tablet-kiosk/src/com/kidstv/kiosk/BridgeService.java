package com.kidstv.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;

/**
 * HTTP(8080) -> Kodi TCP JSON-RPC(9090) bridge.
 *
 * Kodi's own web server never binds on this tablet, so Yatse / Kore (which require
 * Kodi's HTTP JSON-RPC on 8080) have nothing to talk to. This tiny always-on proxy
 * listens on 0.0.0.0:8080, accepts the HTTP JSON-RPC that Yatse sends, relays each
 * request verbatim to Kodi's working raw-TCP server on 127.0.0.1:9090, and returns
 * Kodi's reply as an HTTP response.
 *
 * Started from KioskActivity (the persistent HOME app) so it lives as long as the
 * appliance is on; START_STICKY re-arms it if the process is reaped.
 *
 * Caveat: image/artwork endpoints (/image, /vfs) are NOT proxied — Yatse gets full
 * control but no cover-art thumbnails (served only by Kodi's dead web server).
 */
public class BridgeService extends Service {
    static final String TAG = "KidsBridge";
    static final int HTTP_PORT = 8080;
    static final int KODI_TCP = 9090;
    static final String UPLOAD_DIR = "/storage/emulated/0/KidodiUploads";  // phone->tablet uploads land here (played by path)
    static final String IMPORT_DIR_KEY = "kidodi_import_dir";              // Settings.Global; shared with KioskActivity + adb
    static final String IMPORT_DIR_DEFAULT = "/storage/emulated/0/Download/";
    static final String CHAN = "kidstv_kiosk";
    static final int NOTIF_ID = 42;
    static final String ACTION_REMOVE_OVERLAY = "com.kidstv.kiosk.REMOVE_OVERLAY";
    static final String ACTION_SLEEP_NOW = "com.kidstv.kiosk.SLEEP_NOW";   // admin menu: start the fade now
    static final String ACTION_NAP = "com.kidstv.kiosk.NAP";               // admin menu: sleep the tablet now
    static final String ACTION_ADD_WIFI = "com.kidstv.kiosk.ADD_WIFI";     // admin menu: add a wifi network (extras ssid/pass)
    // Hidden admin gesture: ESC_TAPS taps, each with >= ESC_FINGERS fingers, within
    // ESC_WINDOW_MS -> pop the PIN. 4x four-finger taps is trivial for an adult but
    // very unlikely from a toddler slapping the screen.
    static final int ESC_FINGERS = 4;
    static final int ESC_TAPS = 4;
    static final long ESC_WINDOW_MS = 3000;
    static final long ESC_TAP_MAX_MS = 800;   // a "tap" is a quick land+lift, not a hold/drag
    // ---- Kodi-GUI watchdog (escalation ABOVE the in-Kodi self-heal) ----
    // service.py (inside Kodi) restarts PLAYBACK when it drops, but can't fix a wedged Kodi GUI
    // (stuck on Splash / black) because its own Player.Open silently no-ops inside the broken Kodi.
    // This runs in the separate kiosk process and escalates only when service.py has clearly failed.
    static final long WATCH_POLL_MS = 8000;        // how often to verify Kodi is actually playing
    static final long CAM_STALE_MS   = 45000;      // no camera heartbeat this long -> presume it died
    static final long CAM_KICK_MIN_MS = 60000;     // min gap between camera relaunch attempts (anti-spam)
    static final long CAM_ON_STALE_MS = 45000;     // no /camon ping this long -> Android Auto is gone, camera off
    static final int  WATCH_WEDGE_DEFAULT = 45;    // seconds of no-player -> restart Kodi. Override:
                                                   // settings put global kiosk_watchdog_sec <N>  (0 = off)

    volatile ServerSocket server;
    volatile boolean running = false;

    // The kid-proof touch overlay lives HERE (in the always-foreground service) rather
    // than in KioskActivity, so it's a single process-scoped window that survives the
    // activity being recreated (config changes at boot) or backgrounded behind Kodi.
    WindowManager mWm;
    View mOverlay;
    volatile SplashOverlay mOverlayView;   // same view as mOverlay, typed so we can toggle the splash
    final Handler mMain = new Handler(Looper.getMainLooper());
    // admin-escape gesture state
    int mSeqMaxFingers = 0;                     // peak finger count in the current touch
    long mSeqStart = 0;                         // when the current touch started
    final ArrayDeque<Long> mTaps = new ArrayDeque<>();   // timestamps of recent valid multi-finger taps

    PowerManager.WakeLock mNapLock;   // keeps CPU/socket alive with the screen off during a nap
    volatile Thread mSleepThread;     // the "go to sleep" volume+brightness ramp (null = not sleeping)
    volatile boolean mNapped = false; // true = tablet is napped (screen off/locked) — the shared power state
    volatile long mSleepEndsAt = 0;   // elapsedRealtime when the sleep ramp finishes (0 = not sleeping)
    volatile int mPreQuietVol = -1;   // Kodi volume before a nap/sleep dropped it to 0, restored on wake (-1 = none)
    WifiManager.WifiLock mWifiLock;   // keeps Wi-Fi associated with the screen off (remote reachability)
    BroadcastReceiver mScreen;        // screen-on clears a nap
    volatile BluetoothProfile mPanProxy;   // BluetoothPan proxy for auto-joining a phone's BT tether
    volatile boolean mPanGaveUp = false;   // BluetoothPan.connect needs BLUETOOTH_PRIVILEGED (system-only)

    volatile long mLastPlaying = 0;   // watchdog: last time Kodi had an active player
    int mWatchFails = 0;              // watchdog: consecutive failed recoveries (drives escalation)
    int mEofStuck = 0;               // watchdog: consecutive polls a player was frozen at end-of-file
    volatile long mLastCamBeat = 0;   // last /camalive heartbeat from the camera monitor (0 = never)
    volatile long mLastCamKick = 0;   // last time we relaunched the camera (rate-limit)
    volatile long mCamOn = 0;         // last /camon ping from the AA app (fresh = Android Auto active)

    // Zeroconf/mDNS so Yatse can auto-discover the tablet as "KidsTV" on any
    // network (Wi-Fi or Bluetooth) instead of needing a typed IP. We advertise
    // the HTTP JSON-RPC service type Yatse/Kore look for, pointed at our bridge.
    NsdManager mNsd;
    NsdManager.RegistrationListener mNsdListener;
    ConnectivityManager mCm;
    ConnectivityManager.NetworkCallback mNetCb;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHAN, "KidsTV", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** A minimal ongoing notification so we can run as a foreground service. */
    Notification buildNotif() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(this, CHAN) : new Notification.Builder(this);
        return b.setContentTitle("KidsTV")
                .setContentText("Kiosk running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        // Run foreground so the low-memory killer can't reap this process (and with it
        // the kid-proof touch overlay) during Kodi's heavy boot-time startup. Without
        // this, the kiosk was a cached HOME app (oom_adj 200) and got LMK-reclaimed
        // ~1.6s into boot, leaving Kodi playing with NO touch block.
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, buildNotif(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else
                startForeground(NOTIF_ID, buildNotif());
        } catch (Exception e) { Log.w(TAG, "startForeground: " + e); }
        if (!running) {
            running = true;
            Thread t = new Thread(new Runnable() { public void run() { serve(); } });
            t.setDaemon(true);
            t.start();
            acquireWifiLock();
            startDiscovery();
            registerScreen();
            requestPanProxy();
            mMain.postDelayed(mPanTick, 8000);   // start auto-joining a BT tether when no network
            Thread w = new Thread(new Runnable() { public void run() { watchdog(); } });
            w.setDaemon(true);
            w.start();
            Thread sp = new Thread(new Runnable() { public void run() { splashLoop(); } });
            sp.setDaemon(true);
            sp.start();
            // NB: no camera kick on boot — the camera now starts only when Android Auto is active
            // (the AA app pings /camon), so it isn't a 24/7 battery drain. See camWanted().
        }
        if (i != null && ACTION_SLEEP_NOW.equals(i.getAction())) { startSleep(sleepMinutes()); return START_STICKY; }
        if (i != null && ACTION_NAP.equals(i.getAction())) { nap(); return START_STICKY; }
        if (i != null && ACTION_ADD_WIFI.equals(i.getAction())) {
            final String ss = i.getStringExtra("ssid"), pw = i.getStringExtra("pass");
            new Thread(() -> addWifiNetwork(ss, pw)).start();
            return START_STICKY;
        }
        // Overlay: default (incl. a null-intent START_STICKY restart) ensures it's up;
        // an explicit REMOVE_OVERLAY (admin escape / unlock) takes it down.
        if (i != null && ACTION_REMOVE_OVERLAY.equals(i.getAction())) removeOverlay();
        else addOverlay();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false;
        removeOverlay();
        try { if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release(); } catch (Exception e) {}
        try { if (server != null) server.close(); } catch (Exception e) {}
        try { if (mCm != null && mNetCb != null) mCm.unregisterNetworkCallback(mNetCb); } catch (Exception e) {}
        try { if (mNsd != null && mNsdListener != null) mNsd.unregisterService(mNsdListener); } catch (Exception e) {}
        try { if (mScreen != null) unregisterReceiver(mScreen); } catch (Exception e) {}
        try { mMain.removeCallbacks(mPanTick); } catch (Exception e) {}
    }

    /** Hold the Wi-Fi radio fully associated even when the screen is off, so the front-seat remote can
        always reach this tablet. Android power-saves Wi-Fi with the screen asleep, which left the tablet
        un-findable until it was physically woken. Held for the life of this always-on service. */
    void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kidodi:wifi");
            mWifiLock.setReferenceCounted(false);
            mWifiLock.acquire();
            Log.i(TAG, "Wi-Fi lock held (tablet stays reachable with the screen off)");
        } catch (Exception e) { Log.w(TAG, "wifi lock: " + e); }
    }

    /** SCREEN_ON: clear a nap. A nap always coincides with screen-off, so ANY wake - the
        power button OR a Yatse "Reboot" - ends it and lets the addon resume playback; this
        is what stops a nap getting stuck 'asleep' and freezing playback. The power button is
        left to sleep/wake the screen NORMALLY (no re-wake trickery - that path was flaky and
        could strand a black screen); pressing it again just wakes + resumes via this. */
    void registerScreen() {
        if (mScreen != null) return;
        mScreen = new BroadcastReceiver() {
            public void onReceive(Context c, Intent it) {
                if (!Intent.ACTION_SCREEN_ON.equals(it.getAction())) return;
                new Thread(new Runnable() { public void run() { clearNap(); } }).start();
            }
        };
        try { registerReceiver(mScreen, new IntentFilter(Intent.ACTION_SCREEN_ON)); } catch (Exception e) {}
    }

    // ---- Kid-proof touch overlay + hardware-volume bridge ----
    // A transparent, focusable system window floating ABOVE Kodi that (a) swallows every
    // touch so the kid can't reach any Kodi control/gesture, and (b) intercepts the
    // hardware VOLUME keys and drives KODI's own volume over JSON-RPC (the OS otherwise
    // routes them to STREAM_MUSIC, which can't un-silence a Kodi that boots at volume 0),
    // so physical buttons AND the front-seat remote move one shared volume. Admin escape =
    // 4 four-finger taps within 3s anywhere on screen -> the PIN.

    void addOverlay() {
        if (mOverlay != null) return;   // single process-scoped overlay
        try {
            mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
            final SplashOverlay v = new SplashOverlay(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // focusable (no FLAG_NOT_FOCUSABLE) so the window receives the volume keys;
                // Kodi keeps playing underneath - losing key-focus doesn't pause a visible video.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            mWm.addView(v, lp);
            mOverlay = v;
            mOverlayView = v;
        } catch (Exception e) {
            Log.w(TAG, "addOverlay: " + e);
            mOverlay = null;
        }
    }

    void removeOverlay() {
        mTaps.clear();
        if (mOverlay != null && mWm != null) {
            try { mWm.removeView(mOverlay); } catch (Exception e) {}
        }
        mOverlay = null;
        mOverlayView = null;
    }

    /** Record a valid multi-finger tap; if ESC_TAPS land within ESC_WINDOW_MS, escape. */
    void registerEscapeTap() {
        long now = SystemClock.elapsedRealtime();
        mTaps.addLast(now);
        while (!mTaps.isEmpty() && now - mTaps.peekFirst() > ESC_WINDOW_MS) mTaps.removeFirst();
        if (mTaps.size() >= ESC_TAPS) { mTaps.clear(); triggerEscape(); }
    }

    /** Admin gesture matched: drop the overlay and bring KioskActivity up for the PIN.
        Removing it here (synchronously) means the PIN dialog isn't sitting under a
        touch-eating overlay. */
    void triggerEscape() {
        removeOverlay();
        Intent i = new Intent(this, KioskActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                 | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra("escape", true);
        try { startActivity(i); } catch (Exception e) {}
    }

    /** The kid-proof overlay ABOVE Kodi: swallows every touch (admin escape = 4 four-finger taps in
        3s), bridges the hardware VOLUME keys into Kodi's own volume, and — the SPLASH — paints a
        cheerful full-screen scene whenever Kodi ISN'T showing fullscreen video, so the kid never sees
        Kodi's menu / its own splash / a wedge. Transparent while the show plays. */
    class SplashOverlay extends View {
        volatile boolean splash = false;
        private Paint bg, shape, text;

        SplashOverlay(Context c) {
            super(c);
            setWillNotDraw(false);
            setOnTouchListener(new OnTouchListener() {
                public boolean onTouch(View view, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:    mSeqStart = SystemClock.elapsedRealtime(); mSeqMaxFingers = 1; break;
                        case MotionEvent.ACTION_POINTER_DOWN: if (ev.getPointerCount() > mSeqMaxFingers) mSeqMaxFingers = ev.getPointerCount(); break;
                        case MotionEvent.ACTION_UP:
                            long dur = SystemClock.elapsedRealtime() - mSeqStart;
                            if (mSeqMaxFingers >= ESC_FINGERS && dur <= ESC_TAP_MAX_MS) registerEscapeTap();
                            break;
                    }
                    return true;   // CONSUME everything — nothing reaches Kodi underneath
                }
            });
        }

        @Override public boolean dispatchKeyEvent(KeyEvent ev) {
            int kc = ev.getKeyCode();
            if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN) adjustKodiVolume(kc == KeyEvent.KEYCODE_VOLUME_UP);
                return true;   // consume — don't let it wander off to STREAM_MUSIC
            }
            return super.dispatchKeyEvent(ev);
        }

        void setSplash(boolean b) { if (splash != b) { splash = b; postInvalidate(); } }

        @Override protected void onDraw(Canvas cv) {
            if (!splash) return;   // transparent -> the video shows through
            int w = getWidth(), h = getHeight();
            if (bg == null) { bg = new Paint(); shape = new Paint(Paint.ANTI_ALIAS_FLAG); text = new Paint(Paint.ANTI_ALIAS_FLAG); }
            bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF5FC5F0, 0xFFBDE88F, Shader.TileMode.CLAMP));  // sky -> grass
            cv.drawRect(0, 0, w, h, bg);
            float cx = w / 2f, cy = h * 0.38f, r = Math.min(w, h) * 0.14f;
            shape.setColor(0xFFFFC93C); shape.setStrokeCap(Paint.Cap.ROUND);
            shape.setStyle(Paint.Style.STROKE); shape.setStrokeWidth(r * 0.16f);
            for (int i = 0; i < 12; i++) {                       // sun rays
                double a = Math.PI * 2 * i / 12;
                cv.drawLine(cx + (float)Math.cos(a)*r*1.35f, cy + (float)Math.sin(a)*r*1.35f,
                            cx + (float)Math.cos(a)*r*1.8f,  cy + (float)Math.sin(a)*r*1.8f, shape);
            }
            shape.setStyle(Paint.Style.FILL); cv.drawCircle(cx, cy, r, shape);   // sun disc
            shape.setColor(0xFF4A3A00);                           // eyes
            cv.drawCircle(cx - r*0.34f, cy - r*0.15f, r*0.11f, shape);
            cv.drawCircle(cx + r*0.34f, cy - r*0.15f, r*0.11f, shape);
            shape.setStyle(Paint.Style.STROKE); shape.setStrokeWidth(r*0.12f);   // smile
            cv.drawArc(new RectF(cx - r*0.5f, cy - r*0.25f, cx + r*0.5f, cy + r*0.5f), 20, 140, false, shape);
            text.setColor(0xFF12414F); text.setTextAlign(Paint.Align.CENTER); text.setFakeBoldText(true);
            text.setTextSize(Math.min(w, h) * 0.08f);
            cv.drawText("back in a sec!", cx, h * 0.72f, text);
        }
    }

    // ---- Splash toggle: show the friendly splash whenever Kodi isn't on fullscreen video ----
    void splashLoop() {
        while (running) {
            try { Thread.sleep(1500); } catch (Exception e) {}
            if (!running) break;
            try {
                SplashOverlay ov = mOverlayView;
                if (ov == null) continue;                                    // overlay down (admin escape)
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) continue;             // screen off (nap)
                ov.setSplash(!isFullscreenVideo());
            } catch (Throwable t) { /* best-effort */ }
        }
    }

    /** True if Kodi is showing fullscreen video (the show). False on the menu / home / its own splash. */
    boolean isFullscreenVideo() {
        try {
            String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GUI.GetProperties\",\"params\":{\"properties\":[\"fullscreen\"]}}");
            return new JSONObject(r).getJSONObject("result").optBoolean("fullscreen", false);
        } catch (Exception e) { return true; }   // on error assume OK — don't flash the splash spuriously
    }

    /** Nudge KODI's own volume (physical volume key -> the same volume Yatse controls).
        Off the main thread (forward() does socket I/O). Step 6 so a couple presses are
        clearly audible from a silent (0) boot without being a single-percent crawl. */
    void adjustKodiVolume(final boolean up) {
        new Thread(new Runnable() { public void run() {
            try {
                int cur = 0;
                try {
                    String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.GetProperties\",\"params\":{\"properties\":[\"volume\"]}}");
                    cur = new JSONObject(r).getJSONObject("result").getInt("volume");
                } catch (Exception e) {}
                int next = Math.max(0, Math.min(100, cur + (up ? 6 : -6)));
                forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.SetVolume\",\"params\":{\"volume\":" + next + "}}");
            } catch (Exception e) {}
        }}).start();
    }

    // ---- Nap mode (Yatse "Power off" -> stop + screen off; "Reboot" -> wake) ----
    // Yatse's power commands come through this bridge as JSON-RPC; instead of forwarding
    // them to Kodi (which can't shut down Android anyway) we use them to stop playback and
    // blank the screen for a nap, and to wake it back up - so the front seat can kill all
    // playback and light while the kid sleeps, and bring it back with one tap.

    void nap() {
        stopRamp();                    // a manual power-off ALSO cancels any running sleep timer (keep the
                                       // pre-sleep volume in mPreQuietVol for wake; don't restore it now)
        mNapped = true;                // shared power state — flips instantly so remotes reflect it
        new Thread(new Runnable() { public void run() {
            // 0) remember the volume so wake() can restore it (unless the sleep ramp already saved the
            //    pre-ramp level; then mPreQuietVol is set and we must not overwrite it with ~0)
            if (mPreQuietVol < 0) {
                try {
                    String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.GetProperties\",\"params\":{\"properties\":[\"volume\"]}}");
                    int v = new JSONObject(r).getJSONObject("result").getInt("volume");
                    if (v > 0) mPreQuietVol = v;
                } catch (Exception e) {}
            }
            // 1) tell the autoplay addon to STOP self-healing (so playback stays stopped)
            try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.NotifyAll\",\"params\":{\"sender\":\"kidstv\",\"message\":\"kidstvsleep\"}}"); } catch (Exception e) {}
            // 2) stop playback + drop volume to 0 so the napped tablet is silent (wake() restores it)
            try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.Stop\",\"params\":{\"playerid\":1}}"); } catch (Exception e) {}
            try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.SetVolume\",\"params\":{\"volume\":0}}"); } catch (Exception e) {}
            // 3) keep CPU/socket alive with the screen off so the wake command still lands
            acquireNapLock();
            // 4) blank + lock the screen (device owner)
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                dpm.lockNow();
            } catch (Exception e) { Log.w(TAG, "lockNow: " + e); }
        }}).start();
    }

    /** End a nap: release the lock + tell the addon to resume self-healing. Called by an
        explicit Yatse "Reboot" AND by any screen-on (registerScreen) so it never sticks. */
    void clearNap() {
        mNapped = false;               // shared power state
        cancelSleep();                 // waking/screen-on aborts a ramp + restores brightness AND volume to pre-sleep
        releaseNapLock();
        // wake = play: addon resumes self-healing (restarts a STOPPED player)
        try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.NotifyAll\",\"params\":{\"sender\":\"kidstv\",\"message\":\"kidstvwake\"}}"); } catch (Exception e) {}
        ensurePlayingResume();         // and un-stick a merely-PAUSED player so wake never lands on a frozen frame
    }

    /** Wake = play: if a player is currently PAUSED (speed 0), resume it. A STOPPED player is left to the
        autoplay addon (kidstvwake) which restarts the shuffle; here we only un-stick a pause so a wake
        never lands on a frozen paused frame. */
    void ensurePlayingResume() {
        try {
            String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.GetProperties\",\"params\":{\"playerid\":1,\"properties\":[\"speed\"]}}");
            int speed = new JSONObject(r).getJSONObject("result").getInt("speed");
            if (speed == 0)
                forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.PlayPause\",\"params\":{\"playerid\":1,\"play\":true}}");
        } catch (Exception e) { /* no active player -> the addon starts one */ }
    }

    void wake() {
        new Thread(new Runnable() { public void run() {
            clearNap();
            // wake the screen + bring the kiosk/Kodi back to the front (KioskActivity turns
            // the display on via its wake flag; the addon then resumes playback)
            Intent i = new Intent(BridgeService.this, KioskActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                     | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra("wake", true);
            try { startActivity(i); } catch (Exception e) {}
        }}).start();
    }

    // ---- Go to sleep: ramp volume + screen brightness down over N minutes, then nap ----
    // The front seat taps "sleep" on the remote; the RAMP runs HERE on the tablet so it finishes
    // even if the phone leaves range/sleeps mid-way. Volume rides Kodi (Application.SetVolume);
    // brightness rides the kid-proof overlay's own screenBrightness override (no WRITE_SETTINGS
    // needed - the overlay is always the top window). At the end we nap() (stop + screen off + lock).
    // Any wake/screen-on cancels it via clearNap()->cancelSleep() and restores normal brightness.

    /** Start (or restart) the sleep ramp. `minutes` may be fractional (for a quick test). */
    void startSleep(final double minutes) {
        cancelSleep();                       // supersede any ramp in progress
        final double mins = minutes <= 0 ? 20 : minutes;
        final long totalMs = (long) (mins * 60_000);
        mSleepEndsAt = SystemClock.elapsedRealtime() + totalMs;   // for the remotes' countdown
        acquireNapLock();                    // keep the CPU alive through the (screen-dimming) ramp
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                int startVol = 30;
                try {
                    String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.GetProperties\",\"params\":{\"properties\":[\"volume\"]}}");
                    startVol = new JSONObject(r).getJSONObject("result").getInt("volume");
                } catch (Exception e) {}
                if (startVol > 0) mPreQuietVol = startVol;   // remember pre-sleep level so wake/cancel restores it
                float startBr;
                try {
                    int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
                    startBr = Math.max(0.06f, Math.min(1f, b / 255f));
                } catch (Exception e) { startBr = 0.7f; }

                long stepMs = 15_000;                          // adjust ~every 15s
                int steps = (int) Math.max(1, totalMs / stepMs);
                for (int i = 1; i <= steps; i++) {
                    Thread.sleep(stepMs);                      // throws InterruptedException on cancel
                    float frac = 1f - (float) i / steps;       // 1 -> 0
                    int vol = Math.max(0, Math.round(startVol * frac));
                    setOverlayBrightness(Math.max(0f, startBr * frac));
                    try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.SetVolume\",\"params\":{\"volume\":" + vol + "}}"); } catch (Exception e) {}
                }
                mSleepThread = null; mSleepEndsAt = 0;
                nap();                                         // lights out: stop + screen off + lock
            } catch (InterruptedException ie) {
                // cancelled (woken / superseded) - cancelSleep() already restored brightness
            } catch (Exception e) {
                Log.w(TAG, "sleep ramp: " + e);
            }
        }});
        mSleepThread = t;
        t.start();
    }

    /** Stop a running ramp (if any) and drop the overlay's brightness override back to system default. */
    /** Just end the ramp thread + clear the countdown state (no brightness/volume restore). Used by nap(),
        which is about to blank the screen and zero the volume anyway — restoring them first would flash
        the screen bright and blip the audio right before lights-out. */
    void stopRamp() {
        Thread t = mSleepThread;
        mSleepThread = null;
        mSleepEndsAt = 0;
        if (t != null) t.interrupt();
    }

    /** Cancel a running ramp AND put things back to normal: brightness to system/adaptive and volume
        to the pre-sleep level. This is the "wake / cancel the timer" path — it fully undoes the ramp,
        not just the dimming. (The dropped volume is remembered in mPreQuietVol.) */
    void cancelSleep() {
        stopRamp();
        setOverlayBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);   // -1 = use system
        if (mPreQuietVol > 0) {
            final int v = mPreQuietVol; mPreQuietVol = -1;
            try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.SetVolume\",\"params\":{\"volume\":" + v + "}}"); } catch (Exception e) {}
        }
    }

    /** Override the actual screen brightness via the always-on overlay window (0..1, or -1 = none). */
    void setOverlayBrightness(final float f) {
        mMain.post(new Runnable() { public void run() {
            try {
                if (mOverlay == null || mWm == null) return;
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mOverlay.getLayoutParams();
                if (lp == null) return;
                lp.screenBrightness = f;
                mWm.updateViewLayout(mOverlay, lp);
            } catch (Exception e) { Log.w(TAG, "setOverlayBrightness: " + e); }
        }});
    }

    void acquireNapLock() {
        try {
            if (mNapLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                mNapLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidstv:nap");
                mNapLock.setReferenceCounted(false);
            }
            if (!mNapLock.isHeld()) mNapLock.acquire();
        } catch (Exception e) { Log.w(TAG, "napLock acquire: " + e); }
    }

    void releaseNapLock() {
        try { if (mNapLock != null && mNapLock.isHeld()) mNapLock.release(); } catch (Exception e) {}
    }

    /** Start the child-monitor camera capture (separate Gradle app com.kidstv.cameramon). A camera FGS
        can't be started from the background, so we can't just startForegroundService it - we briefly
        launch its MainActivity in "autostart" mode (lock-task-whitelisted), which starts the FGS from
        its own foreground then finishes back to Kodi. The FGS then survives into naps/screen-off until
        it dies. The watchdog relaunches it via here if its /camalive heartbeat goes stale. */
    void startCameraMonitor() {
        try {
            mLastCamKick = SystemClock.elapsedRealtime();
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.kidstv.cameramon", "com.kidstv.cameramon.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("autostart", true);
            startActivity(i);
        } catch (Throwable t) { Log.w(TAG, "cameramon start: " + t); }
    }

    /** Recover the child-monitor camera if its /camalive heartbeat has gone stale. Only while the
        screen is on + not napping (a camera FGS can't be relaunched without a brief foreground, which
        would wake a sleeping screen) and rate-limited so we don't bounce it repeatedly. Once running it
        survives into naps on its own; the common failure it fixes is not-started-at-boot. */
    /** Should the child-monitor camera be capturing right now? Only while Android Auto is active (the
        AA app pings /camon). This is the fix for the camera being ~87% of the battery drain: it used to
        run 24/7 (incl. overnight on the desk); now it's off unless a drive is in progress. */
    boolean camWanted() { return mCamOn != 0 && SystemClock.elapsedRealtime() - mCamOn < CAM_ON_STALE_MS; }

    void ensureCameraMonitor(long now) {
        try {
            if (!camWanted()) return;                                            // Android Auto not active — leave it off
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;                       // screen off — don't wake it
            if (mNapLock != null && mNapLock.isHeld()) return;                   // napping
            if (mLastCamBeat != 0 && now - mLastCamBeat < CAM_STALE_MS) return;  // heartbeat fresh — alive
            if (now - mLastCamKick < CAM_KICK_MIN_MS) return;                    // relaunched recently — wait
            Log.w(TAG, "watchdog: camera monitor "
                    + (mLastCamBeat == 0 ? "never checked in" : "stale " + ((now - mLastCamBeat) / 1000) + "s")
                    + " - relaunching");
            startCameraMonitor();
        } catch (Throwable t) { Log.w(TAG, "ensureCameraMonitor: " + t); }
    }

    // ---- Kodi-GUI watchdog: the tier ABOVE service.py's in-Kodi self-heal ----
    // Two-tier recovery: (1) service.py, running INSIDE Kodi, restarts playback fast (~6-12s) when
    // it drops but Kodi's GUI is healthy - handles the common case. (2) THIS, running in the separate
    // kiosk process, catches what tier 1 can't: a wedged Kodi GUI (stuck on Splash / black), where
    // service.py's own Player.Open silently no-ops inside the broken Kodi. We only escalate when a
    // show SHOULD be playing (kid-mode overlay up), we're not napping, the screen's on, and there's
    // been NO active player for well past the time tier 1 needs - so the two never fight. Recovery is
    // Kodi's own Application.Quit; because this app is the device-owner HOME, Kodi quitting drops to
    // KioskActivity, which relaunches Kodi fresh (on .Main, not the wedged .Splash) and the addon
    // resumes playback. Verified: Quit -> new Kodi pid on .Main -> playing, fully hands-free.
    /** True if the current player is "playing" but pinned at the very end of the file — the frozen-at-EOF
        state some files get into instead of advancing (speed!=0 and percentage ~100). */
    boolean isStuckAtEof() {
        try {
            String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.GetProperties\",\"params\":{\"playerid\":1,\"properties\":[\"percentage\",\"speed\"]}}");
            org.json.JSONObject res = new org.json.JSONObject(r).getJSONObject("result");
            return res.optInt("speed", 0) != 0 && res.optDouble("percentage", 0) >= 99.5;
        } catch (Exception e) { return false; }
    }

    void watchdog() {
        mLastPlaying = SystemClock.elapsedRealtime();   // grace: let Kodi boot before judging it
        while (running) {
            try { Thread.sleep(WATCH_POLL_MS); } catch (Exception e) {}
            if (!running) break;
            try {
                long now = SystemClock.elapsedRealtime();
                ensureCameraMonitor(now);   // recover the child-monitor camera if its heartbeat went stale
                // Guard ONLY the kid experience; stay out of the way otherwise.
                if (mOverlay == null) { mLastPlaying = now; mWatchFails = 0; continue; }   // escaped / unlocked / maintenance
                if (mNapLock != null && mNapLock.isHeld()) { mLastPlaying = now; mWatchFails = 0; continue; }  // napping
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) { mLastPlaying = now; continue; }   // screen off (can't start video anyway)
                int off = 0;
                try { off = android.provider.Settings.Global.getInt(getContentResolver(), "kiosk_off", 0); } catch (Exception e) {}
                if (off != 0) { mLastPlaying = now; mWatchFails = 0; continue; }            // admin / maintenance hatch active

                // A paused player is still "active" (returns a playerid), so a long remote-paused
                // spell never trips this - only a genuinely STOPPED player (no active player) does.
                String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.GetActivePlayers\"}");
                if (r != null && r.contains("\"playerid\"")) {
                    // Active player — but some files play PAST their end without advancing the playlist
                    // (percentage pinned at 100, speed 1, frozen last frame). The no-player check can't
                    // see that, so detect it and nudge the playlist forward; escalate to a Kodi restart
                    // if GoTo-next doesn't clear it. Needs ~2 polls (~16s) so a normal clip ending — which
                    // advances within one poll — never trips it.
                    if (isStuckAtEof()) {
                        mEofStuck++;
                        if (mEofStuck == 2 || mEofStuck == 3) {
                            Log.w(TAG, "watchdog: player stuck at end-of-file (" + mEofStuck + " polls) -> GoTo next");
                            forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.GoTo\",\"params\":{\"playerid\":1,\"to\":\"next\"}}");
                        } else if (mEofStuck >= 4) {
                            Log.w(TAG, "watchdog: stuck at EOF persists -> restart Kodi");
                            recoverKodi();
                            mEofStuck = 0;
                        }
                    } else { mEofStuck = 0; }
                    mLastPlaying = now; mWatchFails = 0; continue;
                }

                int wedgeSec = WATCH_WEDGE_DEFAULT;
                try { wedgeSec = android.provider.Settings.Global.getInt(getContentResolver(), "kiosk_watchdog_sec", WATCH_WEDGE_DEFAULT); } catch (Exception e) {}
                if (wedgeSec <= 0) { mLastPlaying = now; continue; }   // 0 = watchdog disabled
                if (now - mLastPlaying >= wedgeSec * 1000L) {
                    mWatchFails++;
                    Log.w(TAG, "watchdog: no player for " + ((now - mLastPlaying) / 1000) + "s (fail #" + mWatchFails + ") - restarting Kodi");
                    recoverKodi();
                    mLastPlaying = now;   // fresh window: Kodi needs ~15s to relaunch + resume
                }
            } catch (Throwable t) { Log.w(TAG, "watchdog: " + t); }
        }
    }

    /** Restart a wedged Kodi with no shell/root/kill privilege: ask Kodi to quit (its core answers
        JSON-RPC even while the GUI is stuck on Splash), and the device-owner HOME relaunches it. If
        the core won't even accept the quit (9090 dead/hung), or this is a repeat failure, force
        KioskActivity forward so the HOME path relaunches Kodi regardless. */
    void recoverKodi() {
        String r = forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Application.Quit\"}");
        boolean accepted = r != null && r.contains("\"result\":\"OK\"");
        if (!accepted || mWatchFails >= 2) {
            try {
                Intent i = new Intent(this, KioskActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                         | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            } catch (Exception e) { Log.w(TAG, "recoverKodi relaunch: " + e); }
        }
    }

    // ---- Auto-join the phone's Bluetooth tether (so nobody has to flip "Internet access") ----
    // The tablet is a PANU client that must INITIATE the connection to a paired phone's NAP;
    // Android won't auto-do it and there's no public API. We reflect into the hidden
    // BluetoothPan.connect(device) - BUT it enforces BLUETOOTH_PRIVILEGED, a system-only
    // permission a sideloaded/device-owner app CANNOT hold, so it fails and `mPanGaveUp`
    // disables the retry. Left as scaffolding: it works only if the app gains privilege
    // (Samsung Knox SDK w/ a KPE license, or a platform-signed/rooted build). Until then the
    // BT tether needs the manual "Internet access" toggle.

    final Runnable mPanTick = new Runnable() {
        public void run() {
            if (mPanGaveUp) return;
            new Thread(new Runnable() { public void run() { tryConnectPan(); } }).start();
            mMain.postDelayed(mPanTick, 20000);
        }
    };

    void requestPanProxy() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (ad == null) return;
            ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mPanProxy = proxy; Log.i(TAG, "PAN proxy ready");
                }
                public void onServiceDisconnected(int profile) { mPanProxy = null; }
            }, 5 /* BluetoothProfile.PAN */);
        } catch (Throwable t) { Log.w(TAG, "requestPanProxy: " + t); }
    }

    void tryConnectPan() {
        try {
            // already have a network (WiFi hotspot, or BT already up)? leave it alone
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network active = (cm != null) ? cm.getActiveNetwork() : null;
            if (active != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
            }
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter ad = (bm != null) ? bm.getAdapter() : null;
            if (ad == null || !ad.isEnabled()) return;
            if (mPanProxy == null) { requestPanProxy(); return; }
            java.util.Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null) return;
            java.lang.reflect.Method connect = mPanProxy.getClass().getMethod("connect", BluetoothDevice.class);
            for (BluetoothDevice dev : bonded) {
                if (!supportsNap(dev)) continue;   // only paired phones that offer tethering
                try {
                    Object r = connect.invoke(mPanProxy, dev);
                    Log.i(TAG, "PAN connect -> " + dev.getAddress() + " = " + r);
                } catch (Throwable t) {
                    Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
                    if (c instanceof SecurityException) mPanGaveUp = true;   // privileged - can't ever work
                    Log.w(TAG, "PAN connect " + dev.getAddress() + ": " + c);
                }
            }
        } catch (Throwable t) { Log.w(TAG, "tryConnectPan: " + t); }
    }

    boolean supportsNap(BluetoothDevice dev) {
        try {
            android.os.ParcelUuid[] us = dev.getUuids();
            if (us == null) return true;   // unknown -> worth a try
            for (android.os.ParcelUuid u : us)
                if (u.toString().toLowerCase().startsWith("00001116")) return true;   // NAP UUID
            return false;
        } catch (Throwable t) { return true; }
    }

    // ---- Zeroconf advertising ----

    void startDiscovery() {
        try {
            mNsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            mCm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            // re-advertise whenever the active network changes (Wi-Fi <-> Bluetooth)
            mNetCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { reRegisterNsd(); }
                @Override public void onLost(Network n) { reRegisterNsd(); }
            };
            mCm.registerNetworkCallback(new NetworkRequest.Builder().build(), mNetCb);
            registerNsd();
        } catch (Throwable t) { Log.w(TAG, "nsd init: " + t); }
    }

    void registerNsd() {
        if (mNsd == null) return;
        try {
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("KidsTV");
            si.setServiceType("_xbmc-jsonrpc-h._tcp");
            si.setPort(HTTP_PORT);
            mNsdListener = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "NSD registered: " + s.getServiceName()); }
                public void onRegistrationFailed(NsdServiceInfo s, int err) { Log.w(TAG, "NSD reg failed: " + err); }
                public void onServiceUnregistered(NsdServiceInfo s) {}
                public void onUnregistrationFailed(NsdServiceInfo s, int err) {}
            };
            mNsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, mNsdListener);
        } catch (Throwable t) { Log.w(TAG, "nsd register: " + t); }
    }

    void reRegisterNsd() {
        new Thread(new Runnable() { public void run() {
            try { if (mNsdListener != null) { mNsd.unregisterService(mNsdListener); mNsdListener = null; } } catch (Throwable t) {}
            try { Thread.sleep(2000); } catch (Exception e) {}
            if (running) registerNsd();
        }}).start();
    }

    void serve() {
        while (running) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
                server = s;
                Log.i(TAG, "bridge listening on 0.0.0.0:" + HTTP_PORT);
                while (running) {
                    final Socket c = s.accept();
                    Thread h = new Thread(new Runnable() { public void run() { handle(c); } });
                    h.setDaemon(true);
                    h.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "accept loop error: " + e);
                try { Thread.sleep(2000); } catch (Exception ie) {}
            }
        }
    }

    void handle(Socket c) {
        try {
            c.setSoTimeout(10000);
            InputStream in = c.getInputStream();

            String requestLine = readLine(in);
            if (requestLine == null) { c.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(idx + 1).trim()); } catch (Exception e) {}
                }
            }

            if (method.equalsIgnoreCase("OPTIONS")) { writeResponse(c, ""); c.close(); return; }
            if (target.startsWith("/camon")) {   // AA app: "Android Auto is active" -> the camera should capture
                boolean was = camWanted();
                mCamOn = SystemClock.elapsedRealtime();
                if (!was) startCameraMonitor();   // just became active -> kick the camera to life
                writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}"); c.close(); return;
            }
            if (target.startsWith("/camalive")) {   // camera's liveness ping; we answer whether it should capture
                mLastCamBeat = SystemClock.elapsedRealtime();
                writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capture\":" + camWanted() + "}}"); c.close(); return;
            }
            if (target.startsWith("/state")) {                                     // shared power/sleep/camera state
                boolean sleeping = mSleepThread != null;
                long rem = (sleeping && mSleepEndsAt > 0)
                        ? Math.max(0, (mSleepEndsAt - SystemClock.elapsedRealtime()) / 1000) : 0;
                boolean camera = mLastCamBeat != 0 && SystemClock.elapsedRealtime() - mLastCamBeat < CAM_STALE_MS;
                writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"napped\":" + mNapped
                        + ",\"sleeping\":" + sleeping + ",\"sleep_remaining\":" + rem
                        + ",\"camera\":" + camera + ",\"sleep_minutes\":" + sleepMinutes() + "}}");
                c.close(); return;
            }
            if (target.startsWith("/uploads")) { handleListUploads(c); return; }   // GET: list uploaded files (check before /upload)
            if (target.startsWith("/upload")) { handleUpload(target, contentLength, in, c); return; }

            String json = null;
            if (method.equalsIgnoreCase("POST") && contentLength > 0) {
                byte[] body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(body, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
                json = new String(body, 0, off, "UTF-8");
            } else if (method.equalsIgnoreCase("GET")) {
                int q = target.indexOf("request=");
                if (q >= 0) json = urlDecode(target.substring(q + 8));
            }

            // Build/import featureset endpoints (need the body read, so handled after it):
            if (target.startsWith("/importdir"))   { handleImportDir(method, json, c); return; }
            if (target.startsWith("/saveplaylist")) { handleSavePlaylist(json, c); return; }
            if (target.startsWith("/setsleep"))     { handleSetSleep(json, c); return; }  // set the stored fade duration
            if (target.startsWith("/sleep"))        { handleSleep(json, c); return; }
            if (target.startsWith("/addwifi"))      { handleAddWifi(json, c); return; }

            String reply;
            if (json == null || json.trim().length() == 0) {
                // Yatse's initial reachability probe / bare GET
                reply = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"pong\"}";
            } else if (json.contains("\"System.Reboot\"")) {
                wake();                                          // Yatse "Reboot" -> wake from nap
                reply = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}";
            } else if (json.contains("\"System.Shutdown\"") || json.contains("\"System.Suspend\"")
                    || json.contains("\"System.Hibernate\"")) {
                nap();                                           // Yatse "Power off/Suspend" -> nap
                reply = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}";
            } else {
                reply = forward(json);
            }
            writeResponse(c, reply);
            c.close();
        } catch (Exception e) {
            try { c.close(); } catch (Exception ce) {}
        }
    }

    /** Receive a phone->tablet file upload (POST /upload?name=X, raw bytes in the body) over the same
        8080 bridge and stream it to shared storage where Kodi can play it by path. Enables the remote
        to send a file it downloaded on the phone to the playback device over WiFi - no cloud. Writing
        to shared storage needs the MANAGE_EXTERNAL_STORAGE app-op (granted once via adb appops). */
    void handleUpload(String target, int contentLength, InputStream in, Socket c) {
        try {
            c.setSoTimeout(60000);   // a video body can take a while; keep the socket alive
            String name = "upload_" + System.currentTimeMillis() + ".bin";
            int q = target.indexOf("name=");
            if (q >= 0) {
                String n = urlDecode(target.substring(q + 5));
                int amp = n.indexOf('&'); if (amp >= 0) n = n.substring(0, amp);
                n = sanitizeName(n);
                if (n.length() > 0) name = n;
            }
            java.io.File dir = new java.io.File(UPLOAD_DIR);
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, name);
            long written = 0;
            java.io.OutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                long remaining = (contentLength > 0) ? contentLength : Long.MAX_VALUE;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, want);
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    written += n; remaining -= n;
                }
                fos.flush();
            } finally { try { fos.close(); } catch (Exception e) {} }
            String path = out.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"");
            writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true,\"path\":\"" + path + "\",\"bytes\":" + written + "}}");
            Log.i(TAG, "upload: " + written + " bytes -> " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "upload: " + e);
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,\"message\":\"upload failed\"}}"); } catch (Exception e2) {}
        } finally {
            try { c.close(); } catch (Exception e) {}
        }
    }

    /** GET /uploads -> JSON list of files in UPLOAD_DIR. Kodi can't browse this raw path (not a
        source), but the bridge (which owns the dir) can, so the remote lists uploads through here. */
    void handleListUploads(Socket c) {
        try {
            StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"files\":[");
            java.io.File[] fs = new java.io.File(UPLOAD_DIR).listFiles();
            boolean first = true;
            if (fs != null) {
                java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
                    public int compare(java.io.File a, java.io.File b) { return a.getName().compareToIgnoreCase(b.getName()); }
                });
                for (java.io.File f : fs) {
                    if (!f.isFile()) continue;
                    if (!first) sb.append(",");
                    first = false;
                    String nm = f.getName().replace("\\", "\\\\").replace("\"", "\\\"");
                    String pt = f.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"");
                    sb.append("{\"name\":\"").append(nm).append("\",\"file\":\"").append(pt).append("\",\"size\":").append(f.length()).append("}");
                }
            }
            sb.append("]}}");
            writeResponse(c, sb.toString());
        } catch (Exception e) {
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"files\":[]}}"); } catch (Exception e2) {}
        } finally { try { c.close(); } catch (Exception e) {} }
    }

    static String jsonEsc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** Import destination = where phone->tablet imports land. Shared setting (Settings.Global), so the
        kiosk admin menu, the remote (via the endpoints below), and adb can all set it. Default: Download. */
    String importDir() {
        try {
            String v = android.provider.Settings.Global.getString(getContentResolver(), IMPORT_DIR_KEY);
            if (v != null && v.length() > 0) return v;
        } catch (Exception e) {}
        return IMPORT_DIR_DEFAULT;
    }
    void setImportDir(String p) {
        try { android.provider.Settings.Global.putString(getContentResolver(), IMPORT_DIR_KEY, p); } catch (Exception e) {}
    }

    /** Storage volumes for the import-destination picker: Internal + any mounted SD/USB card. */
    String volumesJson() {
        // {label, destPath, statfsRoot}. getExternalFilesDirs() returns an app dir on EVERY mounted
        // volume (incl. a portable SD) this app can reach — the raw /storage listing + canRead() are
        // both blocked for a portable card, but this API isn't. Strip the /Android/... suffix to get
        // each volume root, then offer a sensible import subfolder on it.
        java.util.ArrayList<String[]> vols = new java.util.ArrayList<>();
        vols.add(new String[]{"Internal storage", IMPORT_DIR_DEFAULT, "/storage/emulated/0/"});
        try {
            for (java.io.File d : getApplicationContext().getExternalFilesDirs(null)) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int cut = p.indexOf("/Android/");
                if (cut <= 0) continue;
                String root = p.substring(0, cut + 1);                 // e.g. /storage/3762-3632/
                if (root.startsWith("/storage/emulated")) continue;    // Internal, already added
                String nm = root.replace("/storage/", "").replace("/", "");
                vols.add(new String[]{"SD card (" + nm + ")", root + "Imports/", root});
            }
        } catch (Exception e) {}
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vols.size(); i++) {
            if (i > 0) sb.append(",");
            long free = 0;
            try { android.os.StatFs st = new android.os.StatFs(vols.get(i)[2]); free = st.getAvailableBytes(); } catch (Exception e) {}
            sb.append("{\"label\":\"").append(jsonEsc(vols.get(i)[0]))
              .append("\",\"path\":\"").append(jsonEsc(vols.get(i)[1]))
              .append("\",\"freeGB\":").append(free / (1024L * 1024L * 1024L)).append("}");
        }
        return sb.append("]").toString();
    }

    /** GET /importdir -> {dir, volumes[]} ; POST /importdir {"dir":"..."} -> set it (must be under /storage). */
    void handleImportDir(String method, String body, Socket c) {
        try {
            if (method.equalsIgnoreCase("POST") && body != null && body.length() > 0) {
                String dir = new JSONObject(body).optString("dir", "");
                boolean ok = false;
                if (dir.startsWith("/storage/") && !dir.contains("..")) {
                    java.io.File d = new java.io.File(dir);
                    if (d.isDirectory() || d.mkdirs()) { setImportDir(dir.endsWith("/") ? dir : dir + "/"); ok = true; }
                }
                writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":" + ok + "}}");
            } else {
                writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"dir\":\"" + jsonEsc(importDir()) + "\",\"volumes\":" + volumesJson() + "}}");
            }
        } catch (Exception e) {
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32003,\"message\":\"importdir failed\"}}"); } catch (Exception e2) {}
        } finally { try { c.close(); } catch (Exception e) {} }
    }

    /** POST /addwifi {"ssid":"...","psk":"..."} -> add + join a Wi-Fi network. A device owner (us) can
        configure Wi-Fi via WifiManager.addNetwork, which adb (uid 2000) can't. Used once to teach the
        appliance the home network so it auto-joins at home (for the data sync); it keeps the hotspot
        save too, so it still joins that in the car. Empty psk = open network. */
    void handleAddWifi(String body, Socket c) {
        boolean ok = false;
        try {
            org.json.JSONObject o = new org.json.JSONObject(body == null ? "{}" : body);
            ok = addWifiNetwork(o.optString("ssid", ""), o.optString("psk", ""));
        } catch (Exception e) { Log.w(TAG, "addwifi: " + e); }
        try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":" + ok + "}}"); } catch (Exception e) {}
        try { c.close(); } catch (Exception e) {}
    }

    /** Device-owner Wi-Fi add+join (adb uid can't). Shared by /addwifi and the admin menu. Empty psk =
        open network. Keeps other saved networks (so the tablet still joins the car hotspot + home). */
    boolean addWifiNetwork(String ssid, String psk) {
        try {
            if (ssid == null || ssid.length() == 0) return false;
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiConfiguration cfg = new android.net.wifi.WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            if (psk != null && psk.length() > 0) cfg.preSharedKey = "\"" + psk + "\"";
            else cfg.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE);
            int id = wm.addNetwork(cfg);
            boolean ok = false;
            if (id >= 0) { wm.enableNetwork(id, true); wm.reconnect(); ok = true; }
            Log.i(TAG, "addwifi '" + ssid + "' -> netId " + id);
            return ok;
        } catch (Exception e) { Log.w(TAG, "addWifiNetwork: " + e); return false; }
    }

    /** Current Wi-Fi SSID for the admin display, or "" if unknown/not connected. */
    String currentSsid() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return "";
            String s = wi.getSSID();
            if (s == null || s.contains("unknown") || s.equals("<unknown ssid>")) return "";
            return s.replaceAll("^\"|\"$", "");
        } catch (Exception e) { return ""; }
    }

    /** POST /sleep {"minutes":20} -> start the volume+brightness ramp then nap. minutes<=0 cancels a
        running ramp. (The remote starts any chosen sleep playlist itself over 9090 before calling this;
        with none chosen, whatever is already playing keeps going as it fades.) */
    /** The tablet is the source of truth for the fade DURATION: a remote's /sleep just signals start
        (any positive minutes) or cancel (<=0); the length is always the kiosk's stored setting. So the
        AA app's hardcoded 20 (and any other remote value) is irrelevant — set it once here or via
        /setsleep, and every remote's zZ obeys it. */
    void handleSleep(String body, Socket c) {
        try {
            double minutes = 1;
            try { if (body != null && body.length() > 0) minutes = new JSONObject(body).optDouble("minutes", 1); } catch (Exception e) {}
            int used = sleepMinutes();
            if (minutes <= 0) cancelSleep(); else startSleep(used);
            writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true,\"minutes\":" + (minutes <= 0 ? 0 : used) + "}}");
        } catch (Exception e) {
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32004,\"message\":\"sleep failed\"}}"); } catch (Exception e2) {}
        } finally { try { c.close(); } catch (Exception e) {} }
    }

    /** POST /setsleep {"minutes":N} — persist the fade duration (the kiosk-owned setting, 1..60 min). */
    void handleSetSleep(String body, Socket c) {
        try {
            int m = 10;
            try { if (body != null && body.length() > 0) m = new JSONObject(body).optInt("minutes", 10); } catch (Exception e) {}
            m = Math.max(1, Math.min(60, m));
            try { android.provider.Settings.Global.putInt(getContentResolver(), "kidodi_sleep_min", m); } catch (Exception e) {}
            writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true,\"minutes\":" + m + "}}");
        } catch (Exception e) {
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32004,\"message\":\"setsleep failed\"}}"); } catch (Exception e2) {}
        } finally { try { c.close(); } catch (Exception e) {} }
    }

    /** The kiosk-owned sleep-fade duration in minutes (1..60), default 10. Shared via Settings.Global
        so the admin menu and the bridge agree; every remote's zZ resolves to this. */
    int sleepMinutes() {
        try { return Math.max(1, Math.min(60, android.provider.Settings.Global.getInt(getContentResolver(), "kidodi_sleep_min", 10))); }
        catch (Exception e) { return 10; }
    }

    /** POST /saveplaylist {"name":"...","items":["<tablet file path>",...]} -> build an .m3u and hand it
        to the Kodi addon (which runs inside Kodi and CAN write the playlists dir) to save as a tile. */
    void handleSavePlaylist(String body, Socket c) {
        try {
            JSONObject o = new JSONObject(body);
            String name = o.optString("name", "Playlist");
            org.json.JSONArray items = o.optJSONArray("items");
            StringBuilder m3u = new StringBuilder("#EXTM3U\n");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                String p = items.optString(i, "");
                if (p.length() > 0) m3u.append(p).append("\n");
            }
            java.io.File dir = new java.io.File(UPLOAD_DIR); dir.mkdirs();
            java.io.File tmp = new java.io.File(dir, "kidodi_playlist_" + System.currentTimeMillis() + ".m3u");
            java.io.OutputStream fos = new java.io.FileOutputStream(tmp);
            try { fos.write(m3u.toString().getBytes("UTF-8")); fos.flush(); } finally { try { fos.close(); } catch (Exception e) {} }
            forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Addons.ExecuteAddon\",\"params\":{\"addonid\":\"script.kidstv.play\",\"params\":[\"savem3u\",\"" + jsonEsc(name) + "\",\"" + jsonEsc(tmp.getAbsolutePath()) + "\"]}}");
            writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
        } catch (Exception e) {
            Log.w(TAG, "saveplaylist: " + e);
            try { writeResponse(c, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32002,\"message\":\"save failed\"}}"); } catch (Exception e2) {}
        } finally { try { c.close(); } catch (Exception e) {} }
    }

    /** Filename hygiene: drop any path/traversal, keep a safe basename so an upload can only land in
        UPLOAD_DIR (never write outside it). */
    String sanitizeName(String n) {
        n = n.replace("\\", "/");
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[^A-Za-z0-9._ ()\\-]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        return n;
    }

    /** Relay one JSON-RPC string to Kodi's 9090 and return the first result/error object. */
    String forward(String json) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", KODI_TCP), 2000);
            s.setSoTimeout(8000);
            s.getOutputStream().write(json.getBytes("UTF-8"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            // Track container depth on raw bytes (brace/bracket/quote are single UTF-8 bytes,
            // so this never mis-parses a multibyte char split across reads).
            ByteArrayOutputStream obj = new ByteArrayOutputStream();
            int depth = 0;
            boolean started = false, inStr = false, esc = false;
            byte[] buf = new byte[8192];
            long total = 0;
            while (total < 8_000_000) {
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    obj.write(b);
                    if (inStr) {
                        if (esc) esc = false;
                        else if (b == '\\') esc = true;
                        else if (b == '"') inStr = false;
                    } else if (b == '"') {
                        inStr = true;
                    } else if (b == '{' || b == '[') {
                        depth++; started = true;
                    } else if (b == '}' || b == ']') {
                        depth--;
                        if (started && depth == 0) {
                            String os = new String(obj.toByteArray(), "UTF-8").trim();
                            if (os.contains("\"result\"") || os.contains("\"error\"")) return os;
                            obj.reset(); started = false;   // skip a leading notification, keep reading
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fall through to error reply
        } finally {
            try { s.close(); } catch (Exception e) {}
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"bridge: no reply from Kodi\"}}";
    }

    void writeResponse(Socket c, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: application/json\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n");
        h.append("Connection: close\r\n");
        h.append("\r\n");
        OutputStream out = c.getOutputStream();
        out.write(h.toString().getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) ch);
            if (sb.length() > 65536) break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
