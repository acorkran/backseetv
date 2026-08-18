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
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    static final String CHAN = "kidstv_kiosk";
    static final int NOTIF_ID = 42;
    static final String ACTION_REMOVE_OVERLAY = "com.kidstv.kiosk.REMOVE_OVERLAY";
    // Hidden admin gesture: ESC_TAPS taps, each with >= ESC_FINGERS fingers, within
    // ESC_WINDOW_MS -> pop the PIN. 4x four-finger taps is trivial for an adult but
    // very unlikely from a toddler slapping the screen.
    static final int ESC_FINGERS = 4;
    static final int ESC_TAPS = 4;
    static final long ESC_WINDOW_MS = 3000;
    static final long ESC_TAP_MAX_MS = 800;   // a "tap" is a quick land+lift, not a hold/drag

    volatile ServerSocket server;
    volatile boolean running = false;

    // The kid-proof touch overlay lives HERE (in the always-foreground service) rather
    // than in KioskActivity, so it's a single process-scoped window that survives the
    // activity being recreated (config changes at boot) or backgrounded behind Kodi.
    WindowManager mWm;
    View mOverlay;
    final Handler mMain = new Handler(Looper.getMainLooper());
    // admin-escape gesture state
    int mSeqMaxFingers = 0;                     // peak finger count in the current touch
    long mSeqStart = 0;                         // when the current touch started
    final ArrayDeque<Long> mTaps = new ArrayDeque<>();   // timestamps of recent valid multi-finger taps

    PowerManager.WakeLock mNapLock;   // keeps CPU/socket alive with the screen off during a nap
    BroadcastReceiver mScreen;        // screen-on clears a nap
    volatile BluetoothProfile mPanProxy;   // BluetoothPan proxy for auto-joining a phone's BT tether
    volatile boolean mPanGaveUp = false;   // BluetoothPan.connect needs BLUETOOTH_PRIVILEGED (system-only)

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
            startDiscovery();
            registerScreen();
            requestPanProxy();
            mMain.postDelayed(mPanTick, 8000);   // start auto-joining a BT tether when no network
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
        try { if (server != null) server.close(); } catch (Exception e) {}
        try { if (mCm != null && mNetCb != null) mCm.unregisterNetworkCallback(mNetCb); } catch (Exception e) {}
        try { if (mNsd != null && mNsdListener != null) mNsd.unregisterService(mNsdListener); } catch (Exception e) {}
        try { if (mScreen != null) unregisterReceiver(mScreen); } catch (Exception e) {}
        try { mMain.removeCallbacks(mPanTick); } catch (Exception e) {}
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
            final View v = new View(this) {
                @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                    int kc = ev.getKeyCode();
                    if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        if (ev.getAction() == KeyEvent.ACTION_DOWN)
                            adjustKodiVolume(kc == KeyEvent.KEYCODE_VOLUME_UP);
                        return true;   // consume - don't let it wander off to STREAM_MUSIC
                    }
                    return super.dispatchKeyEvent(ev);
                }
            };
            v.setBackgroundColor(0x00000000);   // fully transparent
            v.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View view, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:                 // first finger of a new touch
                            mSeqStart = SystemClock.elapsedRealtime();
                            mSeqMaxFingers = 1;
                            break;
                        case MotionEvent.ACTION_POINTER_DOWN:         // more fingers land
                            if (ev.getPointerCount() > mSeqMaxFingers) mSeqMaxFingers = ev.getPointerCount();
                            break;
                        case MotionEvent.ACTION_UP:                  // last finger lifts -> touch ends
                            long dur = SystemClock.elapsedRealtime() - mSeqStart;
                            if (mSeqMaxFingers >= ESC_FINGERS && dur <= ESC_TAP_MAX_MS) registerEscapeTap();
                            break;
                    }
                    return true;   // CONSUME everything - nothing reaches Kodi underneath
                }
            });
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
        new Thread(new Runnable() { public void run() {
            // 1) tell the autoplay addon to STOP self-healing (so playback stays stopped)
            try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.NotifyAll\",\"params\":{\"sender\":\"kidstv\",\"message\":\"kidstvsleep\"}}"); } catch (Exception e) {}
            // 2) stop playback + drop volume to 0 so a later resume is SILENT (a glance/
            //    power-press that wakes the tablet must never blast audio at a sleeping kid)
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
        releaseNapLock();
        try { forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.NotifyAll\",\"params\":{\"sender\":\"kidstv\",\"message\":\"kidstvwake\"}}"); } catch (Exception e) {}
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
