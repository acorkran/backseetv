package com.kidstv.kidodi.wear;

// ============================================================================
//  Kidodi watch remote, Samsung-Music style, fitted to the ROUND face: content
//  is centered and compact so nothing clips on the curved edges. Show/episode up
//  top, Prev · big Play/Pause · Next, bottom row Light · Power · Setup. Rotating
//  bezel = volume, with a transient on-screen volume popup. Setup = sleep-timer
//  picker. Drives the tablet-player directly over BLE.
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
    private View playerView, settingsView, playlistView, current;
    private LinearLayout plHolder;  // playlist rows (repopulated from the tablet)
    private TextView plStatus;
    private GestureDetector gd;
    private TextView status, title, subtitle;
    private Button playPause;

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
        cameraView = buildCamera();
        content.addView(playerView);
        current = playerView;

        // On the player screen: swipe UP -> playlists, swipe LEFT -> camera. We deliberately AVOID
        // swipe-down (watch quick-settings) and swipe-right (WearOS back/dismiss = one level back).
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || current != playerView) return false;
                float dy = e1.getY() - e2.getY();   // >0 = up
                float dx = e2.getX() - e1.getX();   // >0 = right, <0 = left
                if (Math.abs(dy) >= Math.abs(dx)) {                 // vertical-dominant
                    if (dy > pad(40) && Math.abs(vy) > 200) { openPlaylists(); return true; }   // UP -> playlists
                } else {                                            // horizontal-dominant
                    if (dx < -pad(40) && Math.abs(vx) > 200) { openCamera(); return true; }     // LEFT -> camera
                }
                return false;   // down (quick settings) / right (back) left to the system
            }
            @Override public boolean onDown(MotionEvent e) { return current == cameraView; }   // needed so tap/scroll callbacks fire on the camera view
            @Override public boolean onSingleTapUp(MotionEvent e) {   // tap = pan/tilt toward the tapped point
                android.util.Log.i("KidodiCam", "tap cam=" + (current == cameraView) + " x=" + e.getX() + " y=" + e.getY());
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
        BleService svc = BleService.get();
        String cached = svc != null ? svc.playlists() : null;
        if (cached != null) populatePlaylists(cached); else if (plStatus != null) plStatus.setText("loading…");
        send("list");   // ask the tablet to (re)push its playlist names
    }
    private View buildPlaylists() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(20), pad(44), pad(20), pad(44));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("Playlists"); h.setTextColor(Color.WHITE); h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER); h.setPadding(0, 0, 0, pad(6));
        col.addView(h);

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
            JSONArray names = new JSONObject(json).optJSONArray("names");
            plHolder.removeAllViews();
            if (names == null || names.length() == 0) { if (plStatus != null) plStatus.setText("no playlists"); return; }
            if (plStatus != null) plStatus.setText("");
            for (int i = 0; i < names.length(); i++) {
                final String name = names.optString(i);
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
        camStatus.setText("Camera · BLE stills");
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
        if (bmp != null) { paintFrame(bmp); camStatus.setText("Camera · BLE stills"); }
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
                        if (current == cameraView) { paintFrame(bmp); camStatus.setText("Camera · Wi-Fi live"); }
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

    // --- player screen (centered + compact to fit the round face) -----------
    private View buildPlayer() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);   // cluster in the middle where the circle is widest
        col.setFocusableInTouchMode(true);

        status = new TextView(this);
        status.setText("scanning…"); status.setTextColor(0xFFFFC107);
        status.setGravity(Gravity.CENTER); status.setTextSize(11);
        col.addView(status);

        title = new TextView(this);
        title.setTextColor(Color.WHITE); title.setTextSize(16); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(0xFF9AA0A6); subtitle.setTextSize(11); subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, 0, 0, pad(8));
        col.addView(subtitle);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.addView(circle("◀◀", "prev", pad(44), 0xFF2C2C2E, 14));
        playPause = circle("▶", "playpause", pad(62), 0xFF3A3A3C, 24);
        transport.addView(playPause);
        transport.addView(circle("▶▶", "next", pad(44), 0xFF2C2C2E, 14));
        col.addView(transport);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brow.topMargin = pad(8);
        bottom.setLayoutParams(brow);
        bottom.addView(chip("Light", "flash", 0xFF8D6E00));
        bottom.addView(chip("Power", "power", 0xFF5A2A2A));
        bottom.addView(chip("Setup", "settings", 0xFF33363B));
        col.addView(bottom);

        TextView hint = new TextView(this);   // swipe up (or tap) for the playlist library
        hint.setText("▲ Playlists"); hint.setTextColor(0xFF6B7075); hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = pad(6); hint.setLayoutParams(hlp);
        hint.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openPlaylists(); } });
        col.addView(hint);

        TextView camHint = new TextView(this);   // swipe LEFT (or tap) for the camera check-in
        camHint.setText("◀ Camera"); camHint.setTextColor(0xFF6B7075); camHint.setTextSize(11);
        camHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.topMargin = pad(2); camHint.setLayoutParams(chlp);
        camHint.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openCamera(); } });
        col.addView(camHint);
        return col;
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
        volText.setText("Volume  " + volPct + "%");
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) volFill.getLayoutParams();
        fp.width = Math.round(volFillMaxPx * (volPct / 100f));
        volFill.setLayoutParams(fp);
        volOverlay.setVisibility(View.VISIBLE);
        if (hideVol != null) main.removeCallbacks(hideVol);
        hideVol = new Runnable() { public void run() { volOverlay.setVisibility(View.GONE); } };
        main.postDelayed(hideVol, 1400);
    }

    // --- settings: sleep-timer picker --------------------------------------
    private View buildSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(20), pad(44), pad(20), pad(44));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("Sleep timer"); h.setTextColor(Color.WHITE); h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER); h.setPadding(0, 0, 0, pad(8));
        col.addView(h);

        int[] mins = {0, 5, 10, 15, 20, 30, 45};
        for (final int m : mins) {
            Button row = new Button(this);
            row.setAllCaps(false); row.setText(m == 0 ? "Off" : m + " min");
            row.setTextColor(Color.WHITE); row.setTextSize(15);
            GradientDrawable g = new GradientDrawable(); g.setColor(0xFF202225); g.setCornerRadius(pad(14));
            row.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(48));
            lp.setMargins(0, pad(4), 0, pad(4)); row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                send(m == 0 ? "wake" : "sleep:" + m); show(playerView);
            }});
            col.addView(row);
        }
        return sv;
    }

    private void onCmd(String cmd) {
        if ("settings".equals(cmd)) { show(settingsView); return; }
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
            String ip = o.optString("ip", ""); if (!ip.isEmpty()) tabletIp = ip;   // for Wi-Fi /live
        } catch (Exception ignored) {}
    }

    // rotating bezel -> volume (with popup)
    @Override public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && (ev.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (d != 0) {
                boolean up = d < 0;   // clockwise reads negative here; user expects clockwise = louder / zoom-in
                if (current == cameraView) {              // bezel = ZOOM while the camera view is up
                    camZoom = Math.max(1f, Math.min(4f, camZoom + (up ? 0.25f : -0.25f)));
                    if (camZoom <= 1.01f) { camCx = 0.5f; camCy = 0.5f; }   // recentre at 1x
                    clampCentre();
                    if (lastCamBmp != null) paintFrame(lastCamBmp);
                    if (camStatus != null) camStatus.setText("Zoom " + String.format("%.1f", camZoom) + "x");
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

    private Button chip(String label, final String cmd, int bg) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(pad(18)); g.setColor(bg);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(48), pad(38));
        lp.setMargins(pad(3), 0, pad(3), 0); b.setLayoutParams(lp);
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

    // Swipe-right / back = one level back: camera -> player (stop capture), any sub-view -> player,
    // player -> exit the app (the link lingers ~45s in BleService, then shuts down).
    @Override public void onBackPressed() {
        if (current == cameraView) closeCamera();
        else if (current != playerView) show(playerView);
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
