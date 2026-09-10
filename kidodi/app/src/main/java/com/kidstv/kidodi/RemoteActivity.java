package com.kidstv.kidodi;

// ============================================================================
//  Remote role, styled to match v1's polish: dark card theme + Canvas-drawn
//  icons (font glyphs tofu on-device). Now-playing card with progress, big
//  accent play/pause, and coloured Light / Sleep / Power action cards. Drives
//  the tablet through RemoteService (BLE primary, Wi-Fi fallback).
// ============================================================================

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.ImageView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

public class RemoteActivity extends Activity {
    // v1 palette
    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, BTN = 0xFF35516E, TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6;
    static final int ACCENT = 0xFF2F6FEB, GOOD = 0xFF33C05B, WARN = 0xFFE0A63D, PURPLE = 0xFF9B72E0, ORANGE = 0xFFE0673F;

    private final Handler main = new Handler(Looper.getMainLooper());
    private View dot;
    private TextView linkLabel, title, subtitle, timeText, sleepText, plChip, queueChip;
    private ProgressBar progress;
    private IconView playPause;
    private FrameLayout frame;
    private View picker, queueView;
    private View still; private volatile boolean stillOn;
    // digital PTZ on the camera view: client-side viewport (zoom 1-4x, pan center as image fractions)
    private float ptzZoom = 1f, ptzCx = 0.5f, ptzCy = 0.5f;
    private Bitmap ptzBmp;   // last frame shown, for the viewport math
    private ScaleGestureDetector ptzScale;
    private volatile String tabletIp;   // the TV's LAN IP, learned from state (BLE) — for /snapshot.jpg
    private boolean local;              // running ON the TV as its admin remote (loopback, no BLE)
    private String currentPl = "Shuffle All";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        // --- header: title + connection dot + transport label ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText("Kidodi"); h.setTextColor(TXT); h.setTextSize(24);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        linkLabel = new TextView(this);
        linkLabel.setText(""); linkLabel.setTextColor(SUB); linkLabel.setTextSize(12);
        linkLabel.setPadding(0, 0, dp(8), 0);
        header.addView(linkLabel);
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(WARN);
        dot.setBackground(dg);
        header.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        root.addView(header);

        root.addView(spacer(dp(14)));

        // --- now-playing card ---
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cd = new GradientDrawable(); cd.setColor(CARD); cd.setCornerRadius(dp(18));
        card.setBackground(cd);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        IconView art = new IconView(this, IconView.SHUFFLE, withAlpha(TXT, 0x22), SUB);
        titleRow.addView(art, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tcol = new LinearLayout(this); tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(12), 0, 0, 0);
        title = new TextView(this); title.setText("—"); title.setTextColor(TXT); title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = new TextView(this); subtitle.setText(""); subtitle.setTextColor(SUB); subtitle.setTextSize(13); subtitle.setMaxLines(1); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tcol.addView(title); tcol.addView(subtitle);
        titleRow.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(titleRow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(withAlpha(TXT, 0x22)));
        LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pgp.topMargin = dp(12);
        card.addView(progress, pgp);
        timeText = new TextView(this); timeText.setText(""); timeText.setTextColor(SUB); timeText.setTextSize(11);
        timeText.setPadding(0, dp(4), 0, 0);
        card.addView(timeText);
        root.addView(card);

        // playlist chip -> opens the picker
        plChip = new TextView(this);
        plChip.setText("Playlist:  Shuffle All  ▾"); plChip.setTextColor(SUB); plChip.setTextSize(13);
        plChip.setPadding(dp(6), dp(10), dp(6), dp(2)); plChip.setClickable(true);
        plChip.setOnClickListener(v -> openPicker());
        LinearLayout chips = new LinearLayout(this);   // Playlist ▾  |  Up next ▾
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(plChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        queueChip = new TextView(this);
        queueChip.setText("Up next  ▾"); queueChip.setTextColor(SUB); queueChip.setTextSize(13);
        queueChip.setPadding(dp(6), dp(10), dp(6), dp(2)); queueChip.setClickable(true); queueChip.setGravity(Gravity.END);
        queueChip.setOnClickListener(v -> openQueue());
        chips.addView(queueChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(chips);

        root.addView(spacer(dp(12)));

        // --- transport: prev | play/pause | next ---
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER);
        tr.addView(iconBtn(IconView.PREV, BTN, TXT, "prev"), lp(dp(64), dp(64), dp(8)));
        playPause = new IconView(this, IconView.PLAYPAUSE, ACCENT, TXT);
        playPause.setOnClickListener(v -> send("playpause"));
        tr.addView(playPause, lp(dp(84), dp(84), dp(8)));
        tr.addView(iconBtn(IconView.NEXT, BTN, TXT, "next"), lp(dp(64), dp(64), dp(8)));
        root.addView(tr);

        root.addView(spacer(dp(12)));

        // --- volume ---
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        vr.setGravity(Gravity.CENTER);
        vr.addView(iconBtn(IconView.VOLDOWN, BTN, TXT, "voldown"), lp(0, dp(52), dp(6), 1f));
        vr.addView(iconBtn(IconView.VOLUP, BTN, TXT, "volup"), lp(0, dp(52), dp(6), 1f));
        root.addView(vr);

        root.addView(spacer(dp(12)));

        // --- action cards: Light | Sleep | Power ---
        LinearLayout ar = new LinearLayout(this);
        ar.setOrientation(LinearLayout.HORIZONTAL);
        ar.addView(actionCard(IconView.SUN, WARN, "Light", "flash"), lp(0, dp(74), dp(5), 1f));
        ar.addView(actionCard(IconView.SLEEP, PURPLE, "Sleep", "sleep:15"), lp(0, dp(74), dp(5), 1f));
        ar.addView(actionCard(IconView.POWER, ORANGE, "Power", "power"), lp(0, dp(74), dp(5), 1f));
        root.addView(ar);

        sleepText = new TextView(this);
        sleepText.setText(""); sleepText.setTextColor(WARN); sleepText.setTextSize(13);
        sleepText.setGravity(Gravity.CENTER); sleepText.setPadding(0, dp(12), 0, 0);
        root.addView(sleepText);

        TextView importBtn = new TextView(this);
        importBtn.setText("＋  Import a video from this phone");
        importBtn.setTextColor(ACCENT); importBtn.setTextSize(13); importBtn.setGravity(Gravity.CENTER);
        importBtn.setPadding(0, dp(16), 0, 0); importBtn.setClickable(true);
        importBtn.setOnClickListener(v -> pickImport());
        root.addView(importBtn);

        TextView stillBtn = new TextView(this);
        stillBtn.setText("👶  Check-in  ·  still image");
        stillBtn.setTextColor(ACCENT); stillBtn.setTextSize(13); stillBtn.setGravity(Gravity.CENTER);
        stillBtn.setPadding(0, dp(12), 0, 0); stillBtn.setClickable(true);
        stillBtn.setOnClickListener(v -> openStill());
        root.addView(stillBtn);

        TextView scanBtn = new TextView(this);
        scanBtn.setText("⧉  Scan a QR to connect / join Wi-Fi");
        scanBtn.setTextColor(ACCENT); scanBtn.setTextSize(13); scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(0, dp(12), 0, dp(4)); scanBtn.setClickable(true);
        scanBtn.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
        root.addView(scanBtn);

        frame = new FrameLayout(this);
        frame.addView(root);
        setContentView(frame);

        local = getIntent().getBooleanExtra("local", false);   // the TV's own admin Remote
        if (local) { tabletIp = "127.0.0.1"; RemoteService.ensureLocal(this); }
        else RemoteService.ensure(this);
        attach();
    }

    // --- service wiring ------------------------------------------------------
    private final RemoteService.Listener svcListener = new RemoteService.Listener() {
        public void onStatus(String s) {
            boolean ready = s.startsWith("ready");
            ((GradientDrawable) dot.getBackground()).setColor(ready ? GOOD : WARN);
            RemoteService svc = RemoteService.get();
            String ws = WearPusher.watchStatus();
            linkLabel.setText((ready && svc != null ? svc.transport() : "") + (ws.isEmpty() ? "" : "  ·  " + ws));
        }
        public void onState(String json) { showState(json); }
    };
    private void attach() {
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(svcListener); else main.postDelayed(this::attach, 300);
    }
    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }

    private void showState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            boolean playing = o.optBoolean("playing", false);
            playPause.setPlaying(playing);
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            title.setText((i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim());
            subtitle.setText((i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim());
            int pos = o.optInt("position_ms", 0), dur = o.optInt("duration_ms", 0);
            progress.setProgress(dur > 0 ? Math.round(1000f * pos / dur) : 0);
            timeText.setText(dur > 0 ? mmss(pos) + " / " + mmss(dur) + "     vol " + Math.round(o.optDouble("volume", 0) * 100) + "%" : "");
            if (o.optBoolean("sleeping", false)) {
                long s = Math.max(0, o.optLong("sleep_remaining_ms", 0)) / 1000;
                sleepText.setText("Sleeping in " + (s / 60) + ":" + String.format("%02d", s % 60));
            } else if (o.optBoolean("napped", false)) {
                sleepText.setText("TV is off");
            } else sleepText.setText("");
            currentPl = o.optString("playlist", "Shuffle All");
            plChip.setText("Playlist:  " + currentPl + "  ▾");
            String ip = o.optString("ip", "");
            if (!ip.isEmpty() && !local) tabletIp = ip;   // local stays on loopback
        } catch (Exception ignored) {}
    }

    // --- playlist picker overlay --------------------------------------------
    private void openPicker() {
        if (picker != null) return;
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.requestPlaylists();   // ask the player for the list
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(16));
        TextView h = new TextView(this);
        h.setText("Choose a playlist"); h.setTextColor(TXT); h.setTextSize(22);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(10));
        col.addView(h);
        sv.addView(col);
        picker = sv;
        frame.addView(picker);
        populatePicker(col, 0);
    }
    private void populatePicker(final LinearLayout col, final int attempt) {
        RemoteService svc = RemoteService.get();
        List<String> names = svc != null ? svc.playlistNames() : null;
        // keep the header (index 0), rebuild the rows
        while (col.getChildCount() > 1) col.removeViewAt(1);
        if (names != null && !names.isEmpty()) {
            for (final String name : names) {
                Button row = new Button(this);
                row.setAllCaps(false); row.setText(name);
                row.setTextColor(name.equals(currentPl) ? 0xFF8FD694 : TXT); row.setTextSize(16); row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14));
                row.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
                lp.setMargins(0, dp(4), 0, dp(4)); row.setLayoutParams(lp);
                row.setOnClickListener(v -> { send("play:" + name); closePicker(); });
                col.addView(row);
            }
        } else if (attempt < 8) {
            TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0);
            col.addView(t);
            main.postDelayed(() -> { if (picker != null) populatePicker(col, attempt + 1); }, 400);   // list arrives async
        }
    }
    private void closePicker() { if (picker != null) { frame.removeView(picker); picker = null; } }

    // --- "Up next": the current queue in PLAY order (Shuffle All is pre-shuffled), tap a row to jump ---
    private void openQueue() {
        if (queueView != null) return;
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(20), dp(16), dp(16));
        TextView h = new TextView(this);
        h.setText("Up next  ·  " + currentPl); h.setTextColor(TXT); h.setTextSize(20);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(10));
        col.addView(h);
        TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0); col.addView(t);
        sv.addView(col); queueView = sv; frame.addView(queueView);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.requestQueue();   // BLE: a ±4 window around the current item (always available)
        if (tabletIp != null && !tabletIp.isEmpty()) {   // Wi-Fi: a wide window (±80) around the current item
            new Thread(() -> {
                try {
                    java.net.HttpURLConnection c = openOnWifi("http://" + tabletIp + ":8090/queue?center=-1&n=80");
                    c.setConnectTimeout(2000); c.setReadTimeout(4000);
                    if (c.getResponseCode() == 200) { final String json = readAll(c.getInputStream()); main.post(() -> { if (queueView != null) renderQueue(col, json); }); }
                    c.disconnect();
                } catch (Exception ignored) {}   // the BLE window still paints
            }).start();
        }
        populateQueue(col, 0);
    }
    private void populateQueue(final LinearLayout col, final int attempt) {
        RemoteService svc = RemoteService.get();
        String json = svc != null ? svc.queueJson() : null;
        if (json != null) { if (col.getChildCount() <= 2) renderQueue(col, json); return; }   // don't clobber a wider Wi-Fi render
        if (attempt < 12) main.postDelayed(() -> { if (queueView != null) populateQueue(col, attempt + 1); }, 400);
    }
    private void renderQueue(LinearLayout col, String json) {
        try {
            JSONObject o = new JSONObject(json);
            int from = o.optInt("from", 0), index = o.optInt("index", -1), total = o.optInt("total", 0);
            org.json.JSONArray items = o.optJSONArray("items");
            while (col.getChildCount() > 1) col.removeViewAt(1);
            ((TextView) col.getChildAt(0)).setText("Up next  ·  " + o.optString("playlist", currentPl) + "   (" + total + ")");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                final int abs = from + i;
                String name = items.optString(i).replaceAll("\\.(mp4|mkv|m4v)$", "").replace('.', ' ').replace('⧸', '/');
                Button row = new Button(this);
                row.setAllCaps(false); row.setText((abs == index ? "▶  " : "") + (abs + 1) + ".  " + name);
                row.setTextColor(abs == index ? 0xFF8FD694 : TXT); row.setTextSize(14); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMaxLines(2);
                GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(12)); row.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
                lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
                row.setOnClickListener(v -> { send("jump:" + abs); closeQueue(); });
                col.addView(row);
            }
        } catch (Exception ignored) {}
    }
    private void closeQueue() { if (queueView != null) { frame.removeView(queueView); queueView = null; } }
    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close(); return bo.toString("UTF-8");
    }
    @Override public void onBackPressed() {
        if (still != null) closeStill();
        else if (queueView != null) closeQueue();
        else if (picker != null) closePicker();
        else super.onBackPressed();
    }

    // --- check-in: STILL IMAGE over BLE (<1fps, "not a feed") OR LIVE view over Wi-Fi (/live MJPEG) ---
    private void openStill() {
        if (still != null) return;
        boolean wifi = tabletIp != null && !tabletIp.isEmpty();   // TV's LAN IP known -> Wi-Fi live is possible
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView h = new TextView(this);
        h.setText(wifi ? "Live view" : "Check-in — still image, refreshes about once a second");
        h.setTextColor(SUB); h.setTextSize(13); h.setGravity(Gravity.CENTER);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.MATRIX);   // we drive the viewport (digital PTZ)
        img.setBackgroundColor(0xFF000000);
        col.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupPtz(img);   // pinch to zoom (1-4x), single-finger drag to pan/tilt

        final TextView status = new TextView(this);
        status.setText("finding the TV…"); status.setTextColor(SUB); status.setTextSize(12);
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(10), 0, dp(10));
        col.addView(status);

        Button close = new Button(this);
        close.setText("Close"); close.setAllCaps(false);
        close.setOnClickListener(v -> closeStill());
        col.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        still = col; frame.addView(still);
        stillOn = true;
        if (wifi) { send("snap:on"); startLive(tabletIp + ":8090", img, status); }
        else { send("camble:on"); startBleStills(img, status); }
    }

    // Wi-Fi: parse the TV's /live MJPEG (multipart/x-mixed-replace) and show frames as they arrive.
    private void startLive(final String host, final ImageView img, final TextView status) {
        new Thread(() -> {
            int fails = 0;
            while (stillOn) {
                java.net.HttpURLConnection c = null;
                try {
                    c = openOnWifi("http://" + host + "/live");
                    c.setConnectTimeout(2500); c.setReadTimeout(6000);
                    if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                    java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 1 << 16);
                    fails = 0;
                    while (stillOn) {
                        int len = -1; String line;
                        while ((line = readLine(in)) != null) {
                            if (line.isEmpty()) { if (len >= 0) break; else continue; }
                            if (line.toLowerCase().startsWith("content-length:"))
                                try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignore) {}
                        }
                        if (line == null) break;                      // stream ended
                        if (len <= 0 || len > 4_000_000) continue;
                        byte[] jpg = new byte[len]; int r = 0, k;
                        while (r < len && (k = in.read(jpg, r, len - r)) >= 0) r += k;
                        if (r != len) break;
                        final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, len);
                        if (bmp != null) main.post(() -> { showFrame(img, bmp); status.setText(""); });
                    }
                } catch (Exception e) {
                    if (++fails >= 3) {   // give up on live -> fall back to BLE stills
                        main.post(() -> status.setText("no live view — showing stills over Bluetooth"));
                        send("camble:on"); startBleStills(img, status); return;
                    }
                    main.post(() -> status.setText("connecting live…"));
                } finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    // BLE: the TV pushes chunked low-res stills; RemoteService reassembles them, we show the latest.
    private void startBleStills(final ImageView img, final TextView status) {
        new Thread(() -> {
            byte[] lastShown = null;
            while (stillOn) {
                byte[] jpg = RemoteService.latestStill();
                if (jpg != null && jpg != lastShown) {
                    lastShown = jpg;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (bmp != null) main.post(() -> { showFrame(img, bmp); status.setText(""); });
                } else if (jpg == null) main.post(() -> status.setText("waiting for a still over Bluetooth…"));
                try { Thread.sleep(700); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    // --- digital PTZ: a client-side viewport over whatever frames arrive (still or live) ------
    private void setupPtz(final ImageView img) {
        ptzZoom = 1f; ptzCx = 0.5f; ptzCy = 0.5f; ptzBmp = null;   // reset to 1x centered on open
        ptzScale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                ptzZoom = Math.max(1f, Math.min(4f, ptzZoom * d.getScaleFactor()));   // pinch: 1-4x
                applyViewport(img); return true;
            }
        });
        final float[] last = new float[2]; final boolean[] panning = { false };
        img.setOnTouchListener((v, ev) -> {
            ptzScale.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last[0] = ev.getX(); last[1] = ev.getY(); panning[0] = true; break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    panning[0] = false; break;   // a second finger = pinch, not pan
                case MotionEvent.ACTION_MOVE:
                    if (panning[0] && ev.getPointerCount() == 1 && !ptzScale.isInProgress() && ptzBmp != null) {
                        float base = Math.min((float) v.getWidth() / ptzBmp.getWidth(), (float) v.getHeight() / ptzBmp.getHeight());
                        float s = base * ptzZoom;
                        if (s > 0) {   // drag moves the viewport centre the opposite way (drag right -> see left)
                            ptzCx -= (ev.getX() - last[0]) / (ptzBmp.getWidth() * s);
                            ptzCy -= (ev.getY() - last[1]) / (ptzBmp.getHeight() * s);
                        }
                        last[0] = ev.getX(); last[1] = ev.getY();
                        applyViewport(img);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panning[0] = false; break;
            }
            return true;
        });
        img.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyViewport(img));   // apply once the view is sized
    }
    private void showFrame(ImageView img, Bitmap bmp) {
        ptzBmp = bmp;
        img.setImageBitmap(bmp);
        applyViewport(img);   // keep the chosen zoom/pan as frames update
    }
    /** Rebuild the image matrix from (zoom, pan-centre), clamped so you can't pan past the frame edges. */
    private void applyViewport(ImageView img) {
        Bitmap b = ptzBmp;
        if (b == null) return;
        int VW = img.getWidth(), VH = img.getHeight(), BW = b.getWidth(), BH = b.getHeight();
        if (VW == 0 || VH == 0 || BW == 0 || BH == 0) return;
        float base = Math.min((float) VW / BW, (float) VH / BH);   // FIT_CENTER baseline at 1x
        float s = base * ptzZoom;
        float halfW = (VW / (BW * s)) / 2f, halfH = (VH / (BH * s)) / 2f;   // visible half-extent, in image fractions
        ptzCx = halfW >= 0.5f ? 0.5f : Math.max(halfW, Math.min(1f - halfW, ptzCx));   // clamp centre so the viewport stays inside the frame
        ptzCy = halfH >= 0.5f ? 0.5f : Math.max(halfH, Math.min(1f - halfH, ptzCy));
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate(VW / 2f - ptzCx * BW * s, VH / 2f - ptzCy * BH * s);
        img.setImageMatrix(m);
    }

    private void closeStill() {
        stillOn = false;
        send("camble:off"); send("snap:off");   // stop the BLE push + capture
        if (still != null) { frame.removeView(still); still = null; }
        ptzBmp = null;   // release the frame ref; PTZ resets on next open
    }
    /** Open the URL bound to the Wi-Fi network — the TV is on the LAN, but with mobile data up the
        app's default network is cellular, which can't reach a 192.168.x host. Falls back to default. */
    private java.net.HttpURLConnection openOnWifi(String urlStr) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        if (local) return (java.net.HttpURLConnection) url.openConnection();   // loopback: never bind to the Wi-Fi network
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

    private static String mmss(int ms) { int s = ms / 1000; return (s / 60) + ":" + String.format("%02d", s % 60); }

    // --- Wi-Fi file import (phone -> tablet) --------------------------------
    private void pickImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/*");
            startActivityForResult(i, 42);
        } catch (Exception e) { Toast.makeText(this, "No file picker", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 42 && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            Toast.makeText(this, "Finding the TV…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                RemoteService svc = RemoteService.get();
                final String host = svc != null ? svc.playerHttpHost() : null;   // blocks (off-main) for mDNS
                if (host == null) { main.post(() -> Toast.makeText(this, "Join the same Wi-Fi as the TV to import", Toast.LENGTH_LONG).show()); return; }
                main.post(() -> Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show());
                uploadFile(uri, host);
            }).start();
        }
        if (req == 43 && res == RESULT_OK && data != null) {   // QR scan result
            String ip = data.getStringExtra(QrScanActivity.RESULT_KIDODI_IP);
            String ssid = data.getStringExtra(QrScanActivity.RESULT_JOINED_SSID);
            if (ip != null && !ip.isEmpty()) { tabletIp = ip; Toast.makeText(this, "TV at " + ip, Toast.LENGTH_SHORT).show(); }
            else if (ssid != null && !ssid.isEmpty()) Toast.makeText(this, "Joining " + ssid + "…", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Scanned", Toast.LENGTH_SHORT).show();
        }
    }
    private void uploadFile(Uri uri, String host) {
        java.net.HttpURLConnection c = null;
        try {
            String name = queryName(uri); long size = querySize(uri);
            byte[] data = null;
            if (size <= 0) {   // buffer to learn the length (server needs Content-Length)
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) bo.write(b, 0, n); in.close();
                data = bo.toByteArray(); size = data.length;
            }
            java.net.URL url = new java.net.URL("http://" + host + "/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8"));
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(5000);
            c.setFixedLengthStreamingMode(size);
            java.io.OutputStream out = c.getOutputStream();
            if (data != null) out.write(data);
            else { java.io.InputStream in = getContentResolver().openInputStream(uri); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); in.close(); }
            out.flush();
            final int code = c.getResponseCode(); final String nm = name;
            main.post(() -> Toast.makeText(this, code == 200 ? "Imported " + nm : "Import failed (" + code + ")", Toast.LENGTH_LONG).show());
        } catch (Exception e) { main.post(() -> Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show()); }
        finally { if (c != null) c.disconnect(); }
    }
    private String queryName(Uri uri) {
        try (Cursor cu = getContentResolver().query(uri, null, null, null, null)) {
            if (cu != null && cu.moveToFirst()) { int i = cu.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return cu.getString(i); }
        } catch (Exception ignored) {}
        return "import_" + System.currentTimeMillis() + ".mp4";
    }
    private long querySize(Uri uri) {
        try (Cursor cu = getContentResolver().query(uri, null, null, null, null)) {
            if (cu != null && cu.moveToFirst()) { int i = cu.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !cu.isNull(i)) return cu.getLong(i); }
        } catch (Exception ignored) {}
        return -1;
    }

    @Override protected void onResume() { super.onResume(); attach(); }
    @Override protected void onPause() { RemoteService svc = RemoteService.get(); if (svc != null) svc.setListener(null); super.onPause(); }
    @Override protected void onDestroy() { RemoteService svc = RemoteService.get(); if (svc != null) svc.setListener(null); super.onDestroy(); }

    // --- view helpers --------------------------------------------------------
    private IconView iconBtn(int mode, int bg, int icon, final String cmd) {
        IconView v = new IconView(this, mode, bg, icon);
        v.setOnClickListener(x -> send(cmd));
        return v;
    }
    private LinearLayout actionCard(int mode, int accent, String label, final String cmd) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(withAlpha(accent, 0x33)); g.setCornerRadius(dp(16));
        g.setStroke(dp(1), withAlpha(accent, 0x66));
        c.setBackground(g);
        IconView ic = new IconView(this, mode, 0x00000000, accent);
        c.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(12); l.setPadding(0, dp(4), 0, 0);
        c.addView(l);
        c.setClickable(true);
        c.setOnClickListener(x -> send(cmd));
        return c;
    }
    private View spacer(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)); return v; }
    private LinearLayout.LayoutParams lp(int w, int h, int m) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(m, 0, m, 0); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int m, float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight); p.setMargins(m, 0, m, 0); return p; }
    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // --- Canvas-drawn icons (no font glyphs -> no tofu), ported from v1 -------
    class IconView extends View {
        static final int POWER = 1, PREV = 2, PLAYPAUSE = 3, NEXT = 4, VOLUP = 6, VOLDOWN = 7, SHUFFLE = 8, SLEEP = 9, SUN = 10;
        final int mode; int bgCol, iconCol; boolean playing = false;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final GradientDrawable bgd = new GradientDrawable();
        IconView(Context c, int mode, int bgCol, int iconCol) {
            super(c); this.mode = mode; this.bgCol = bgCol; this.iconCol = iconCol; setClickable(true);
            bgd.setCornerRadius(dp(mode == PLAYPAUSE ? 42 : 16)); bgd.setColor(bgCol); setBackground(bgd);
        }
        void setPlaying(boolean pl) { if (pl != playing) { playing = pl; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = Math.min(getWidth(), getHeight()) * 0.30f;
            p.setColor(iconCol); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            RectF r;
            switch (mode) {
                case POWER:
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3f, s * 0.22f));
                    r = new RectF(cx - s, cy - s, cx + s, cy + s);
                    cv.drawArc(r, -63f, 306f, false, p);
                    cv.drawLine(cx, cy - s * 1.15f, cx, cy - s * 0.10f, p); break;
                case PLAYPAUSE:
                    p.setStyle(Paint.Style.FILL);
                    if (playing) { float bw = s * 0.40f, gap = s * 0.34f;
                        cv.drawRoundRect(new RectF(cx - gap - bw, cy - s, cx - gap, cy + s), dp(2), dp(2), p);
                        cv.drawRoundRect(new RectF(cx + gap, cy - s, cx + gap + bw, cy + s), dp(2), dp(2), p);
                    } else { Path tp = new Path(); tp.moveTo(cx - s * 0.60f, cy - s); tp.lineTo(cx + s * 0.95f, cy); tp.lineTo(cx - s * 0.60f, cy + s); tp.close(); cv.drawPath(tp, p); }
                    break;
                case PREV: case NEXT: {
                    p.setStyle(Paint.Style.FILL); int dir = (mode == NEXT) ? 1 : -1; float bw = Math.max(2f, s * 0.16f); float bx = cx + dir * s * 0.9f;
                    cv.drawRoundRect(new RectF(bx - bw, cy - s, bx + bw, cy + s), bw, bw, p);
                    Path tp = new Path(); tp.moveTo(cx + dir * s * 0.85f, cy); tp.lineTo(cx - dir * s * 0.55f, cy - s); tp.lineTo(cx - dir * s * 0.55f, cy + s); tp.close(); cv.drawPath(tp, p); break; }
                case VOLUP: case VOLDOWN:
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(3f, s * 0.24f));
                    cv.drawLine(cx - s, cy, cx + s, cy, p);
                    if (mode == VOLUP) cv.drawLine(cx, cy - s, cx, cy + s, p); break;
                case SLEEP: {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2.5f, s * 0.17f));
                    float bl = cx - s, bt = cy - s * 0.10f, br = cx + s * 0.30f, bb = cy + s * 0.95f;
                    cv.drawLine(bl, bt, br, bt, p); cv.drawLine(br, bt, bl, bb, p); cv.drawLine(bl, bb, br, bb, p);
                    float sl = cx + s * 0.22f, st = cy - s * 0.98f, sr = cx + s, sb = cy - s * 0.25f;
                    cv.drawLine(sl, st, sr, st, p); cv.drawLine(sr, st, sl, sb, p); cv.drawLine(sl, sb, sr, sb, p); break; }
                case SUN: {
                    p.setStyle(Paint.Style.FILL); cv.drawCircle(cx, cy, s * 0.55f, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * 0.16f));
                    for (int k = 0; k < 8; k++) { double a = Math.PI * k / 4; float r0 = s * 0.85f, r1 = s * 1.15f;
                        cv.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0, cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, p); }
                    break; }
                case SHUFFLE:
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * 0.16f)); float t = s;
                    cv.drawLine(cx - t, cy - t * 0.55f, cx + t, cy + t * 0.55f, p);
                    cv.drawLine(cx - t, cy + t * 0.55f, cx + t, cy - t * 0.55f, p); break;
            }
        }
    }
}
