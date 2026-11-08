package app.anancy.wear;

// ============================================================================
//  Anancy watch remote, fitted to the ROUND face: content is centered and compact
//  so nothing clips on the curved edges. HOME is glanceable — TV-battery chip +
//  show/episode up top, Prev · big Play/Pause · Next, then a 2×3 action grid where
//  EVERY function has a visible button: Light · Power · Camera · Sleep · Toddler. Swipe UP = the play queue (Up next), whose top button
//  "Choose new playlist" opens the playlists; picking one replaces the queue.
//  Swipe LEFT = camera. The rotating bezel is context-aware: volume on
//  the player, zoom on the camera, and the sleep-timer value on the Sleep page — a
//  big number you spin through Off · 5 · 10 · 15 · 30 · 45 · 60 min, then tap Set.
//  Drives the tablet-player directly over BLE.
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class WearRemoteActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout content;    // holds player or settings/playlists
    private View playerView, settingsView, playlistView, queueView, current;
    private LinearLayout plHolder;  // playlist rows (repopulated from the tablet)
    private TextView plStatus;
    private final java.util.List<String> plNames = new java.util.ArrayList<>();   // pages accumulate here
    private LinearLayout qHolder;   // queue rows
    private TextView qStatus, qMore;
    private ScrollView qScroll;
    private int qNext = -1, qTotal = 0;   // next page start / queue length, from the last queue page
    private GestureDetector gd;
    private TextView status, title, subtitle;
    private Button playPause;
    private RingGauge gauge;           // edge arcs: battery (left) + volume (right)
    // HOME action row: the toddler button is state-aware (tint flips with playmode)
    private TextView toddlerCell;
    private boolean playModeOn;        // last-known toddler-mode state from the tablet
    // Sleep-timer rotary picker (spun with the bezel; tap the value to step as a no-bezel fallback)
    private static final int[] SLEEP_STEPS = {0, 5, 10, 15, 30, 45, 60};
    private int sleepIdx = 3;           // default selection = 15 min
    private TextView sleepValue, sleepUnit;

    // camera view: reduced-res STILLS over BLE, upgraded to Wi-Fi /live MJPEG when the TV IP is reachable
    private View cameraView;
    private android.widget.ImageView camImg;
    private TextView camStatus;
    private volatile String tabletIp;
    private volatile boolean camWifiOn;   // true while Wi-Fi live frames are arriving (suppress BLE stills)
    private volatile boolean liveRun;
    private Thread liveThread;
    // digital pan/tilt/zoom over the received frame — bezel = zoom, tap = pan/tilt
    private float camZoom = 1f;                            // 1.0x – 4.0x
    private float camCx = 0.5f, camCy = 0.5f;              // viewport centre, normalised 0..1
    private volatile android.graphics.Bitmap lastCamBmp;  // last full frame, re-rendered on zoom/pan

    // volume popup
    private View volOverlay;
    private View volFill;
    private TextView volText;
    private int volPct = 0;
    private int volFillMaxPx;
    private Runnable hideVol;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPerms();
        WearUpdater.onAppStart(this);   // publish what's installed; apply a phone-pushed build we may have missed

        content = new FrameLayout(this);
        playerView = buildPlayer();
        settingsView = buildSettings();
        playlistView = buildPlaylists();
        queueView = buildQueue();
        cameraView = buildCamera();
        content.addView(playerView);
        current = playerView;

        // On the player screen: swipe UP -> the play queue, swipe LEFT -> camera. We deliberately AVOID
        // swipe-down (watch quick-settings) and swipe-right (WearOS back/dismiss = one level back).
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e1.getY() - e2.getY();   // >0 = up
                float dx = e2.getX() - e1.getX();   // >0 = right, <0 = left
                boolean horiz = Math.abs(dx) > Math.abs(dy);
                // Swipe RIGHT = universal BACK (the system swipe-to-dismiss is turned off in the theme, so
                // we own it): sub-view -> player, player -> minimise to the watch face. Identical on every
                // page. On a zoomed camera, right-drag pans instead, so don't hijack it there.
                if (horiz && dx > pad(40) && Math.abs(vx) > 200) {
                    if (current == cameraView && camZoom > 1.01f) return false;   // let the pan happen
                    if (current != playerView) { goBack(); return true; }
                    moveTaskToBack(true); return true;                            // player: leave the app
                }
                if (current != playerView) return false;   // the shortcuts below only act on the player
                if (Math.abs(dy) >= Math.abs(dx)) {                 // vertical-dominant
                    if (dy > pad(40) && Math.abs(vy) > 200) { openQueue(); return true; }   // UP -> play queue
                } else {                                            // horizontal-dominant
                    if (dx < -pad(40) && Math.abs(vx) > 200) { openCamera(); return true; }     // LEFT -> camera
                }
                return false;   // down (quick settings) left to the system
            }
            @Override public boolean onDown(MotionEvent e) { return current == cameraView; }   // needed so tap/scroll callbacks fire on the camera view
            @Override public boolean onSingleTapUp(MotionEvent e) {   // tap = pan/tilt toward the tapped point
                android.util.Log.i("AnancyCam", "tap cam=" + (current == cameraView) + " x=" + e.getX() + " y=" + e.getY());
                if (current == cameraView) { panToward(e.getX(), e.getY()); return true; }
                return false;   // player taps fall through to the buttons
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {   // drag = pan (a tap with slight movement lands here)
                if (current != cameraView || camZoom <= 1.01f) return false;
                float vw = Math.max(1, content.getWidth()), vh = Math.max(1, content.getHeight());
                camCx += (dx / vw) / camZoom;   // dx = last.x - now.x: drag right -> reveal left
                camCy += (dy / vh) / camZoom;
                clampCentre();
                if (lastCamBmp != null) paintFrame(lastCamBmp);
                return true;
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildVolOverlay());
        setContentView(root);

        // The BLE link is ON DEMAND: acquired in onResume, released in onPause (BleService lingers
        // briefly, then disconnects and exits). A 24/7 link flattened the watch overnight.
    }

    private final BleService.Listener svcListener = new BleService.Listener() {
        public void onStatus(String s) { status.setText(s.startsWith("ready") ? "" : s); }
        public void onState(String json) {
            if (json != null && json.contains("\"type\":\"playlists\"")) populatePlaylists(json);
            else if (json != null && json.contains("\"type\":\"queue\"")) populateQueue(json);
            else render(json);
        }
        public void onStill(byte[] jpeg) { showStill(jpeg); }
    };
    private void attach() {
        BleService svc = BleService.get();
        if (svc != null) svc.setListener(svcListener);
        else main.postDelayed(this::attach, 300);   // service still starting up
    }
    private void send(String cmd) { BleService svc = BleService.get(); if (svc != null) svc.send(cmd); }

    private void show(View v) { content.removeAllViews(); content.addView(v); current = v; }

    // feed every touch to the gesture detector so swipe-up works over the player's buttons
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gd != null) gd.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // --- playlist library: a swipe-up page of the tablet's playlists ---------
    private void openPlaylists() {
        show(playlistView);
        if (plNames.isEmpty() && plStatus != null) plStatus.setText("loading…");
        send("list");   // ask the tablet to (re)push its playlist names (paged; see populatePlaylists)
    }
    private View buildPlaylists() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(20), pad(44), pad(20), pad(44));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("Choose a playlist"); h.setTextColor(Color.WHITE); h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER); h.setPadding(0, 0, 0, pad(6));
        col.addView(h);

        TextView sub = new TextView(this);
        sub.setText("replaces the queue"); sub.setTextColor(0xFF6B7075); sub.setTextSize(9);
        sub.setGravity(Gravity.CENTER); sub.setPadding(0, 0, 0, pad(4));
        col.addView(sub);

        plStatus = new TextView(this);
        plStatus.setText("loading…"); plStatus.setTextColor(0xFF9AA0A6); plStatus.setTextSize(11);
        plStatus.setGravity(Gravity.CENTER); plStatus.setPadding(0, 0, 0, pad(8));
        col.addView(plStatus);

        plHolder = new LinearLayout(this);
        plHolder.setOrientation(LinearLayout.VERTICAL);
        col.addView(plHolder);
        return sv;
    }
    private void populatePlaylists(String json) {
        if (plHolder == null) return;
        try {
            // One BLE notification holds ~460 bytes, so the TV sends the names a page at a time
            // (from/total). Collect every page; page 0 starts the list over.
            JSONObject o = new JSONObject(json);
            JSONArray names = o.optJSONArray("names");
            int from = o.optInt("from", 0), total = o.optInt("total", 0);
            if (from == 0) plNames.clear();
            if (from != plNames.size()) return;   // out-of-order page: page 0 will come round again
            if (names != null) for (int i = 0; i < names.length(); i++) plNames.add(names.optString(i));
            int got = plNames.size();
            if (names != null && names.length() > 0 && got < total) send("list:" + got);   // fetch the next page
            plHolder.removeAllViews();
            if (plNames.isEmpty()) { if (plStatus != null) plStatus.setText("no playlists"); return; }
            if (plStatus != null) plStatus.setText(got < total ? "loading… " + got + "/" + total : "");
            for (final String name : plNames) {
                Button row = new Button(this);
                row.setAllCaps(false); row.setText(name);
                row.setTextColor(Color.WHITE); row.setTextSize(15);
                row.setMaxLines(1); row.setEllipsize(TextUtils.TruncateAt.END);
                GradientDrawable g = new GradientDrawable(); g.setColor(0xFF202225); g.setCornerRadius(pad(14));
                row.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(48));
                lp.setMargins(0, pad(4), 0, pad(4)); row.setLayoutParams(lp);
                row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    send("play:" + name); show(playerView);
                }});
                plHolder.addView(row);
            }
        } catch (Exception ignored) {}
    }

    // --- play queue ("Up next"): swipe up from the player. The current item first, then what follows;
    //     "Choose new playlist" at the top swaps the whole queue; tap an item to jump to it. -----------
    private void openQueue() {
        qNext = -1; qTotal = 0;
        if (qHolder != null) qHolder.removeAllViews();
        if (qStatus != null) qStatus.setText("loading…");
        if (qMore != null) qMore.setVisibility(View.GONE);
        if (qScroll != null) qScroll.scrollTo(0, 0);
        show(queueView);
        send("queue");   // first page starts at the item playing now
    }
    private View buildQueue() {
        qScroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(20), pad(30), pad(20), pad(44));
        qScroll.addView(col);

        Button choose = new Button(this);
        choose.setAllCaps(false); choose.setText("Choose new playlist");
        choose.setTextColor(Color.WHITE); choose.setTextSize(13); choose.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cg = new GradientDrawable(); cg.setColor(0xFF20303A); cg.setCornerRadius(pad(20));
        choose.setBackground(cg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(44));
        clp.setMargins(pad(10), 0, pad(10), pad(6)); choose.setLayoutParams(clp);
        choose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openPlaylists(); } });
        col.addView(choose);

        qStatus = new TextView(this);
        qStatus.setText("loading…"); qStatus.setTextColor(0xFF9AA0A6); qStatus.setTextSize(10);
        qStatus.setGravity(Gravity.CENTER); qStatus.setPadding(0, 0, 0, pad(4));
        col.addView(qStatus);

        qHolder = new LinearLayout(this);
        qHolder.setOrientation(LinearLayout.VERTICAL);
        col.addView(qHolder);

        qMore = new TextView(this);
        qMore.setText("more…"); qMore.setTextColor(0xFF8FD694); qMore.setTextSize(13);
        qMore.setGravity(Gravity.CENTER); qMore.setPadding(0, pad(10), 0, pad(10));
        qMore.setVisibility(View.GONE);
        qMore.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (qNext >= 0 && qNext < qTotal) { qMore.setText("loading…"); send("queue:" + qNext); }
        }});
        col.addView(qMore);
        return qScroll;
    }
    private void populateQueue(String json) {
        if (qHolder == null || current != queueView) return;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray items = o.optJSONArray("items");
            int from = o.optInt("from", 0), idx = o.optInt("index", -1);
            qTotal = o.optInt("total", 0);
            if (qNext >= 0 && from != qNext) return;   // a stale or repeated page
            if (qNext < 0) {                          // first page: header line
                String pl = o.optString("playlist", "");
                qStatus.setText((pl.isEmpty() ? "Up next" : pl) + (qTotal > 0 ? "  ·  " + (idx + 1) + "/" + qTotal : ""));
            }
            int n = items != null ? items.length() : 0;
            for (int i = 0; i < n; i++) {
                final int at = from + i;
                String t = items.optString(i).replaceAll("\\.(mp4|mkv|m4v)$", "").replace('.', ' ').replace('⧸', '/').trim();
                boolean now = at == idx;
                Button row = new Button(this);
                row.setAllCaps(false); row.setText(now ? "▶  " + t : t);
                row.setTextColor(now ? 0xFF8FD694 : Color.WHITE); row.setTextSize(12);
                row.setMaxLines(2); row.setEllipsize(TextUtils.TruncateAt.END);
                GradientDrawable g = new GradientDrawable(); g.setColor(now ? 0xFF1E2A20 : 0xFF202225); g.setCornerRadius(pad(14));
                row.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, pad(3), 0, pad(3)); row.setLayoutParams(lp); row.setMinHeight(pad(40));
                row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    send("jump:" + at); show(playerView);
                }});
                qHolder.addView(row);
            }
            qNext = from + n;
            if (n == 0 && qHolder.getChildCount() == 0) qStatus.setText("queue is empty");
            qMore.setText("more…");
            qMore.setVisibility(n > 0 && qNext < qTotal ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    // --- camera check-in: reduced-res STILLS over BLE (default), Wi-Fi /live MJPEG when reachable ----
    private View buildCamera() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(pad(8), pad(8), pad(8), pad(8));
        col.setBackgroundColor(Color.BLACK);

        camImg = new android.widget.ImageView(this);
        camImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        camImg.setBackgroundColor(0xFF101010);
        col.addView(camImg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        camStatus = new TextView(this);
        camStatus.setText("connecting…"); camStatus.setTextColor(0xFF9AA0A6); camStatus.setTextSize(10);
        camStatus.setGravity(Gravity.CENTER); camStatus.setPadding(0, pad(4), 0, 0);
        col.addView(camStatus);
        return col;
    }

    private void openCamera() {
        show(cameraView);
        camWifiOn = false;
        camZoom = 1f; camCx = 0.5f; camCy = 0.5f; lastCamBmp = null;   // reset digital PTZ
        camStatus.setText("BackSeat Glance · Bluetooth");
        send("camble:on");   // reduced-res stills over BLE — always available
        BleService svc = BleService.get();
        byte[] cached = svc != null ? svc.latestStill() : null;
        if (cached != null) showStill(cached);   // immediate paint
        if (tabletIp != null && !tabletIp.isEmpty()) startWifiLive();   // upgrade to live if we know the TV IP
    }
    private void closeCamera() {
        send("camble:off");
        stopWifiLive();
        lastCamBmp = null;   // free the last frame
        show(playerView);
    }
    // A BLE still arrived — paint it only while the camera is open and Wi-Fi live isn't driving the view.
    private void showStill(byte[] jpeg) {
        if (current != cameraView || camWifiOn || jpeg == null || camImg == null) return;
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp != null) { paintFrame(bmp); camStatus.setText("BackSeat Glance · Bluetooth"); }
    }

    /** Paint a frame through the digital pan/tilt/zoom viewport (crop to the zoomed sub-rect, centred). */
    private void paintFrame(android.graphics.Bitmap bmp) {
        lastCamBmp = bmp;
        if (bmp == null || camImg == null) return;
        if (camZoom <= 1.01f) { camImg.setImageBitmap(bmp); return; }
        int w = bmp.getWidth(), h = bmp.getHeight();
        int cw = Math.max(1, Math.round(w / camZoom)), ch = Math.max(1, Math.round(h / camZoom));
        int cx = Math.max(0, Math.min(w - cw, Math.round(camCx * w - cw / 2f)));
        int cy = Math.max(0, Math.min(h - ch, Math.round(camCy * h - ch / 2f)));
        try { camImg.setImageBitmap(android.graphics.Bitmap.createBitmap(bmp, cx, cy, cw, ch)); }
        catch (Exception e) { camImg.setImageBitmap(bmp); }
    }
    /** A tap nudges the viewport centre toward the tapped point (repeated taps walk around a zoomed frame). */
    private void panToward(float x, float y) {
        float vw = Math.max(1, content.getWidth()), vh = Math.max(1, content.getHeight());
        camCx += ((x / vw) - 0.5f) * (0.6f / camZoom);
        camCy += ((y / vh) - 0.5f) * (0.6f / camZoom);
        clampCentre();
        if (lastCamBmp != null) paintFrame(lastCamBmp);
    }
    /** Keep the viewport centre so the (0.5/zoom)-radius window stays inside the frame. */
    private void clampCentre() {
        float half = 0.5f / camZoom;
        camCx = Math.max(half, Math.min(1f - half, camCx));
        camCy = Math.max(half, Math.min(1f - half, camCy));
    }

    private void startWifiLive() {
        stopWifiLive();
        final String ip = tabletIp;
        if (ip == null || ip.isEmpty()) return;
        liveRun = true;
        liveThread = new Thread(() -> {
            try {
                java.net.HttpURLConnection c = openOnWifi("http://" + ip + ":8090/live");
                c.setConnectTimeout(2500); c.setReadTimeout(6000);
                if (c.getResponseCode() != 200) { c.disconnect(); return; }
                java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 1 << 15);
                byte[] jpg;
                while (liveRun && (jpg = readMjpegFrame(in)) != null) {
                    final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (bmp != null) main.post(() -> {
                        camWifiOn = true;
                        if (current == cameraView) { paintFrame(bmp); camStatus.setText("BackSeat Glance · Wi-Fi"); }
                    });
                }
                c.disconnect();
            } catch (Exception ignored) {}
            camWifiOn = false;   // stream dropped -> BLE stills resume painting
        });
        liveThread.setDaemon(true); liveThread.start();
    }
    private void stopWifiLive() {
        liveRun = false; camWifiOn = false;
        Thread t = liveThread; liveThread = null;
        if (t != null) t.interrupt();
    }
    // Open bound to the Wi-Fi network (the TV is on the LAN; the watch's default net may not be).
    private java.net.HttpURLConnection openOnWifi(String urlStr) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                    return (java.net.HttpURLConnection) n.openConnection(url);
            }
        } catch (Exception ignored) {}
        return (java.net.HttpURLConnection) url.openConnection();
    }
    // Read one MJPEG part: consume part-headers to the blank line, grab Content-Length, read that many bytes.
    private byte[] readMjpegFrame(java.io.InputStream in) throws Exception {
        int contentLength = -1; String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) { if (contentLength >= 0) break; else continue; }
            String low = line.toLowerCase();
            if (low.startsWith("content-length:")) { try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {} }
        }
        if (contentLength <= 0 || contentLength > 4_000_000) return null;
        byte[] buf = new byte[contentLength]; int off = 0;
        while (off < contentLength) { int n = in.read(buf, off, contentLength - off); if (n < 0) return null; off += n; }
        return buf;
    }
    private String readLine(java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(); int ch = -1;
        while ((ch = in.read()) != -1) { if (ch == '\n') break; if (ch != '\r') sb.append((char) ch); }
        if (sb.length() == 0 && ch == -1) return null;
        return sb.toString();
    }

    // --- player screen: round-native — transport ABOVE, title ACROSS THE CENTRE, actions BELOW, with
    // battery/volume as arc gauges hugging the left/right edges (RingGauge, drawn behind). Nothing is
    // hidden behind a gesture; swipe-up/left stay as bonus shortcuts and swipe-right = back (see onFling).
    private View buildPlayer() {
        FrameLayout wrap = new FrameLayout(this);

        gauge = new RingGauge(this);   // edge arcs: fixed background, so they never eat vertical space
        wrap.addView(gauge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);   // cluster around the centre where the circle is widest
        col.setFocusableInTouchMode(true);
        col.setPadding(pad(22), pad(4), pad(22), pad(4));   // side padding keeps content clear of the arcs

        // Buttons ABOVE the title: prev · big play/pause · next.
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.addView(circle("◀◀", "prev", pad(34), 0xFF2C2C2E, 12));
        playPause = circle("▶", "playpause", pad(48), 0xFF3A3A3C, 19);
        transport.addView(playPause);
        transport.addView(circle("▶▶", "next", pad(34), 0xFF2C2C2E, 12));
        col.addView(transport);

        // Title ACROSS THE CENTRE (up to 2 lines) + episode.
        title = new TextView(this);
        title.setTextColor(Color.WHITE); title.setTextSize(14); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = pad(3); title.setLayoutParams(tlp);
        col.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(0xFF9AA0A6); subtitle.setTextSize(9); subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(subtitle);

        // Buttons BELOW the title: 5 quick actions, icon-only so they fit one row on small round faces.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = pad(5); actions.setLayoutParams(alp);
        actions.addView(iconBtn("💡", 0xFF3A3220, new Runnable() { public void run() { send("flash"); } }));
        actions.addView(powerBtn(0xFF3A2020, new Runnable() { public void run() { send("power"); } }));
        actions.addView(iconBtn("📷", 0xFF203A2C, new Runnable() { public void run() { openCamera(); } }));
        actions.addView(iconBtn("💤", 0xFF2A2A38, new Runnable() { public void run() { openSleep(); } }));
        toddlerCell = iconBtn("🧸", 0xFF2A2233, new Runnable() { public void run() { send(playModeOn ? "playmode:off" : "playmode:on"); } });
        actions.addView(toddlerCell);
        col.addView(actions);
        updateToddlerCell();

        // ScrollView is pure insurance: it won't scroll when the cluster fits (the goal), but guarantees
        // nothing is ever unreachable on an unusually small face.
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Connection status: tiny, top-anchored over the gauges. Empty once ready (the arcs show battery).
        status = new TextView(this);
        status.setText("scanning…"); status.setTextColor(0xFFFFC107);
        status.setGravity(Gravity.CENTER); status.setTextSize(10);
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; stp.topMargin = pad(6);
        status.setLayoutParams(stp);
        wrap.addView(status);

        return wrap;
    }

    // A small round icon-only button for the below-title action row. Sized so five fit across a small
    // round face without colliding with the edge arcs.
    private TextView iconBtn(String emoji, int bg, final Runnable onTap) {
        TextView t = new TextView(this);
        t.setText(emoji); t.setTextSize(15); t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(bg);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(30), pad(30));
        lp.setMargins(pad(2), 0, pad(2), 0); t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onTap.run(); } });
        return t;
    }

    // The power symbol is drawn, not typed: the watch's fonts have no glyph for it and showed a crossed box.
    private TextView powerBtn(int bg, Runnable onTap) {
        TextView t = iconBtn("", bg, onTap);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(bg);
        t.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{circle, new PowerGlyph()}));
        return t;
    }
    private static final class PowerGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        PowerGlyph() { p.setColor(0xFFFF8A80); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeCap(android.graphics.Paint.Cap.ROUND); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height()), r = size * 0.25f, cx = b.exactCenterX(), cy = b.exactCenterY() + size * 0.03f;
            p.setStrokeWidth(size * 0.08f);
            c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), -55, 290, false, p);   // ring, open at the top
            c.drawLine(cx, cy - r * 1.3f, cx, cy - r * 0.2f, p);                                        // the stem through the gap
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // Toddler button reflects the tablet's playmode: filled purple when active, dim when off.
    private void updateToddlerCell() {
        if (toddlerCell == null) return;
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL);
        g.setColor(playModeOn ? 0xFF7A57A6 : 0xFF2A2233);
        toddlerCell.setBackground(g);
    }

    // Note: prev uses "◀" and next uses "▶" (next is same glyph, distinguished by side/position).

    // --- volume popup -------------------------------------------------------
    private View buildVolOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202225); bg.setCornerRadius(pad(16));
        box.setBackground(bg);
        box.setPadding(pad(18), pad(12), pad(18), pad(12));

        volText = new TextView(this);
        volText.setText("Volume"); volText.setTextColor(Color.WHITE); volText.setTextSize(14);
        volText.setTypeface(Typeface.DEFAULT_BOLD); volText.setGravity(Gravity.CENTER);
        volText.setPadding(0, 0, 0, pad(8));
        box.addView(volText);

        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable(); tb.setColor(0xFF44474C); tb.setCornerRadius(pad(4));
        track.setBackground(tb);
        volFillMaxPx = pad(120);
        volFill = new View(this);
        GradientDrawable fb = new GradientDrawable(); fb.setColor(0xFF8FD694); fb.setCornerRadius(pad(4));
        volFill.setBackground(fb);
        track.addView(volFill, new FrameLayout.LayoutParams(1, pad(8)));   // grows on showVol
        box.addView(track, new LinearLayout.LayoutParams(volFillMaxPx, pad(8)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.setLayoutParams(lp);
        volOverlay = box;
        volOverlay.setVisibility(View.GONE);
        return box;
    }

    private void showVol() {
        if (gauge != null) gauge.setVolume(volPct);   // live-update the right-edge arc
        volText.setText("Volume  " + volPct + "%");
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) volFill.getLayoutParams();
        fp.width = Math.round(volFillMaxPx * (volPct / 100f));
        volFill.setLayoutParams(fp);
        volOverlay.setVisibility(View.VISIBLE);
        if (hideVol != null) main.removeCallbacks(hideVol);
        hideVol = new Runnable() { public void run() { volOverlay.setVisibility(View.GONE); } };
        main.postDelayed(hideVol, 1400);
    }

    // --- Sleep page: a bezel-spun rotary picker (Off · 5 · 10 · 15 · 30 · 45 · 60 min) -------
    private View buildSettings() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);   // centred cluster, safe inside the round face
        col.setPadding(pad(16), pad(16), pad(16), pad(16));

        TextView h = new TextView(this);
        h.setText("💤  Sleep timer"); h.setTextColor(Color.WHITE); h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER); h.setPadding(0, 0, 0, pad(2));
        col.addView(h);

        // Big value the bezel steps through; tapping it also steps (fallback with no bezel).
        sleepValue = new TextView(this);
        sleepValue.setTextColor(0xFF8FD694); sleepValue.setTextSize(42);
        sleepValue.setTypeface(Typeface.DEFAULT_BOLD); sleepValue.setGravity(Gravity.CENTER);
        sleepValue.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            sleepIdx = (sleepIdx + 1) % SLEEP_STEPS.length; renderSleep();
        }});
        col.addView(sleepValue);

        sleepUnit = new TextView(this);
        sleepUnit.setTextColor(0xFF9AA0A6); sleepUnit.setTextSize(11); sleepUnit.setGravity(Gravity.CENTER);
        sleepUnit.setPadding(0, 0, 0, pad(6));
        col.addView(sleepUnit);

        TextView hint = new TextView(this);
        hint.setText("Spin the bezel · tap value"); hint.setTextColor(0xFF6B7075); hint.setTextSize(9);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, 0, 0, pad(8));
        col.addView(hint);

        Button set = new Button(this);
        set.setAllCaps(false); set.setText("Set"); set.setTextColor(Color.WHITE); set.setTextSize(15);
        set.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable sg = new GradientDrawable(); sg.setColor(0xFF2E5A34); sg.setCornerRadius(pad(20));
        set.setBackground(sg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(110), pad(44));
        set.setLayoutParams(lp);
        set.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int m = SLEEP_STEPS[sleepIdx];
            send(m == 0 ? "wake" : "sleep:" + m); show(playerView);
        }});
        col.addView(set);

        renderSleep();
        return col;
    }

    // Open the Sleep page fresh (default 15 min), then let the bezel/tap change it.
    private void openSleep() { sleepIdx = 3; renderSleep(); show(settingsView); }

    private void renderSleep() {
        if (sleepValue == null) return;
        int m = SLEEP_STEPS[sleepIdx];
        sleepValue.setText(m == 0 ? "Off" : String.valueOf(m));
        if (sleepUnit != null) sleepUnit.setText(m == 0 ? "no timer" : "minutes");
    }
    // Bezel step on the Sleep page (clamped, doesn't wrap — tap wraps as the fallback).
    private void stepSleep(boolean up) {
        sleepIdx = Math.max(0, Math.min(SLEEP_STEPS.length - 1, sleepIdx + (up ? 1 : -1)));
        renderSleep();
    }

    private void onCmd(String cmd) {
        if ("settings".equals(cmd)) { openSleep(); return; }
        send(cmd);
    }

    private void render(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (playPause != null) playPause.setText(o.optBoolean("playing", false) ? "▮▮" : "▶");
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            String show = i > 0 ? t.substring(0, i) : t;
            String ep = i > 0 ? t.substring(i + 1) : "";
            title.setText(show.replace('.', ' ').trim());
            subtitle.setText(ep.replace('.', ' ').replace('⧸', '/').trim());
            if (volOverlay.getVisibility() != View.VISIBLE)
                volPct = (int) Math.round(o.optDouble("volume", 0) * 100);
            if (gauge != null) gauge.setVolume(volPct);   // right-edge arc
            String ip = o.optString("ip", ""); if (!ip.isEmpty()) tabletIp = ip;   // for Wi-Fi /live
            playModeOn = o.optBoolean("playmode", false);
            updateToddlerCell();
            if (o.has("battery") && gauge != null) {   // TV battery -> left-edge arc (not the status chip)
                int bat = o.optInt("battery", -1);
                if (bat >= 0) gauge.setBattery(bat, o.optBoolean("charging", false));
            }
        } catch (Exception ignored) {}
    }

    // rotating bezel -> volume (with popup)
    @Override public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && (ev.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (d != 0) {
                boolean up = d < 0;   // clockwise reads negative here; user expects clockwise = louder / zoom-in / more
                if (current == cameraView) {              // bezel = ZOOM while the camera view is up
                    camZoom = Math.max(1f, Math.min(4f, camZoom + (up ? 0.25f : -0.25f)));
                    if (camZoom <= 1.01f) { camCx = 0.5f; camCy = 0.5f; }   // recentre at 1x
                    clampCentre();
                    if (lastCamBmp != null) paintFrame(lastCamBmp);
                    if (camStatus != null) camStatus.setText("Zoom " + String.format("%.1f", camZoom) + "x");
                } else if (current == settingsView) {     // bezel = SLEEP-TIMER value on the Sleep page
                    stepSleep(up);
                } else {                                  // bezel = VOLUME on the player (unchanged)
                    send(up ? "volup" : "voldown");
                    volPct = Math.max(0, Math.min(100, volPct + (up ? 10 : -10)));
                    showVol();
                }
            }
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    private Button circle(String glyph, final String cmd, int sizePx, int bg, int textSp) {
        Button b = new Button(this);
        b.setText(glyph); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(textSp); b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(bg);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.setMargins(pad(5), 0, pad(5), 0); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onCmd(cmd); } });
        return b;
    }

    private void requestPerms() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, 1);
            else
                requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 1);
        } catch (Exception ignored) {}
    }

    // One level back: camera -> player (stop capture), any sub-view -> player. Shared by the hardware
    // back key and the swipe-right gesture so BACK behaves identically however it's triggered.
    private void goBack() {
        if (current == cameraView) closeCamera();
        else if (current == playlistView) show(queueView);   // playlists were opened from the queue
        else if (current != playerView) show(playerView);
    }
    // Back key: sub-view -> player; on the player -> exit (the link lingers ~45s in BleService, then shuts down).
    @Override public void onBackPressed() {
        if (current != playerView) goBack();
        else super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); BleService.acquire(this, "ui"); attach(); }
    @Override protected void onPause() {
        if (current == cameraView) closeCamera();   // NEVER keep stills/live streaming in the background (battery)
        BleService svc = BleService.get(); if (svc != null) svc.setListener(null);
        BleService.release(this, "ui");
        super.onPause();
    }
    @Override protected void onDestroy() { BleService svc = BleService.get(); if (svc != null) svc.setListener(null); super.onDestroy(); }
    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
