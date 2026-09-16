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
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
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
    private TextView linkLabel, title, subtitle, timeText, sleepText, plNow;
    private ProgressBar progress;
    private IconView playPause;
    private FrameLayout frame;
    private View picker, queueView, libView, epView, connView, settingsView;
    private org.json.JSONObject cfgVals; private boolean cfgOwner, cfgPinSet;   // mirrored TV settings
    private TextView connStatus;
    // --- tabs: Remote (0) / Playlists (1) / Queue (2) ---
    private LinearLayout tabBar; private FrameLayout content;
    private View remoteTab, playlistsTab, queueTab;
    private final TextView[] tabBtns = new TextView[3]; private int curTab = 0;
    private LinearLayout plCol, qCol;
    private LinearLayout connModes, connActions;
    private boolean lastShuffle, lastLoop = true;
    private String lastConn = "ble", hsSsid, hsPass;   // the TV's connection level + its published hotspot creds
    private View still; private volatile boolean stillOn;
    // digital PTZ on the camera view: client-side viewport (zoom 1-4x, pan center as image fractions)
    private float ptzZoom = 1f, ptzCx = 0.5f, ptzCy = 0.5f;
    private Bitmap ptzBmp;   // last frame shown, for the viewport math
    private ScaleGestureDetector ptzScale;
    private volatile String tabletIp;   // the TV's LAN IP, learned from state (BLE) — for /snapshot.jpg
    private boolean local;              // running ON the TV as its admin remote (loopback, no BLE)
    // Last pushed clock values; the TV pushes on change + a 10s heartbeat (battery), so we tick locally.
    private int lastPos, lastDur; private long lastStateAt, lastSleepMs; private double lastVol;
    private boolean lastPlaying, lastSleeping, lastNapped; private int tickN;
    private int lastBattery = -1; private boolean lastCharging;   // TV battery, shown on the now-playing line
    private final Runnable uiTick = new Runnable() { public void run() {
        renderClock();
        if (++tickN % 20 == 0) WearPusher.refreshStatus(RemoteActivity.this);
        main.postDelayed(this, 1000);
    }};
    private String currentPl = "Shuffle All";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(8));

        // --- header (visible on every tab): title + connection dot + transport label ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText("Kidodi"); h.setTextColor(TXT); h.setTextSize(24);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // linkLabel takes the flexible middle and TRUNCATES if long (a long watch-update status was
        // squeezing the "Kidodi" title into a vertical stack); title + gear + dot keep their natural size.
        linkLabel = new TextView(this);
        linkLabel.setText(""); linkLabel.setTextColor(SUB); linkLabel.setTextSize(12);
        linkLabel.setPadding(dp(8), 0, dp(6), 0);
        linkLabel.setMaxLines(1); linkLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        linkLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        linkLabel.setClickable(true); linkLabel.setOnClickListener(v -> showTargetMenu());   // pick a tablet / both / Connection
        header.addView(linkLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = new TextView(this);
        gear.setText("⚙"); gear.setTextColor(SUB); gear.setTextSize(22); gear.setPadding(dp(4), 0, dp(10), 0);
        gear.setClickable(true); gear.setOnClickListener(v -> openSettings());   // all the TV's settings, from the phone
        header.addView(gear);
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(WARN);
        dot.setBackground(dg);
        header.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        root.addView(header);

        // --- tab bar: Remote | Playlists | Queue ---
        root.addView(spacer(dp(10)));
        tabBar = new LinearLayout(this); tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBtns[0] = pillChip("Remote", v -> showTab(0));
        tabBtns[1] = pillChip("Playlists", v -> showTab(1));
        tabBtns[2] = pillChip("Queue", v -> showTab(2));
        for (int k = 0; k < 3; k++) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(k == 0 ? 0 : dp(4), 0, k == 2 ? 0 : dp(4), 0);
            tabBar.addView(tabBtns[k], tp);
        }
        root.addView(tabBar);
        root.addView(spacer(dp(10)));

        // --- content: three swappable tab views fill the rest ---
        content = new FrameLayout(this);
        remoteTab = buildRemoteTab();
        playlistsTab = buildPlaylistsTab();
        queueTab = buildQueueTab();
        content.addView(remoteTab); content.addView(playlistsTab); content.addView(queueTab);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        showTab(0);

        local = getIntent().getBooleanExtra("local", false);   // the TV's own admin Remote
        if (local) { tabletIp = "127.0.0.1"; RemoteService.ensureLocal(this); }
        else RemoteService.ensure(this);
        attach();
    }

    /** Switch tabs: show one view, highlight its pill, and (re)load that tab's data on entry. */
    private void showTab(int i) {
        curTab = i;
        remoteTab.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        playlistsTab.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        queueTab.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < 3; k++) {
            ((GradientDrawable) tabBtns[k].getBackground()).setColor(k == i ? ACCENT : BTN);
            tabBtns[k].setTextColor(TXT);
        }
        RemoteService svc = RemoteService.get();
        if (i == 1) { if (svc != null) svc.requestPlaylists(); populatePicker(plCol, 0); }
        else if (i == 2) { loadQueue(qCol); }
    }

    // --- TAB 1: Remote — now-playing card + transport + volume + Light/Sleep/Power + Check-in ---
    private View buildRemoteTab() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

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

        root.addView(spacer(dp(14)));
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
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        vr.setGravity(Gravity.CENTER);
        vr.addView(iconBtn(IconView.VOLDOWN, BTN, TXT, "voldown"), lp(0, dp(52), dp(6), 1f));
        vr.addView(iconBtn(IconView.VOLUP, BTN, TXT, "volup"), lp(0, dp(52), dp(6), 1f));
        root.addView(vr);

        root.addView(spacer(dp(12)));
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

        // Check-in + Toddler as proper icon cards (matching the Light/Sleep/Power row), not ad-hoc text.
        // Wi-Fi QR scanning lives in the Connection screen (tap the link label) — it's not a Remote-tab action.
        LinearLayout xr = new LinearLayout(this);
        xr.setPadding(dp(6), dp(14), dp(6), dp(4));
        xr.addView(actionCardRun(IconView.WATCH, GOOD, "Check-in", this::openStill), lp(0, dp(74), dp(5), 1f));
        xr.addView(actionCardRun(IconView.BEAR, PURPLE, "Toddler", () -> { send("playmode:on"); toast("Play mode on the TV"); }),
                lp(0, dp(74), dp(5), 1f));
        root.addView(xr);

        sv.addView(root);
        return sv;
    }

    // --- TAB 2: Playlists — pick a playlist (built-ins + your saved queues) ---
    private View buildPlaylistsTab() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(2), dp(2), dp(2), dp(16));
        plNow = new TextView(this);
        plNow.setText("Now playing:  Shuffle All"); plNow.setTextColor(SUB); plNow.setTextSize(13);
        plNow.setPadding(dp(6), 0, 0, dp(8));
        col.addView(plNow);   // child 0 = header line kept by populatePicker
        plCol = col; picker = sv;
        sv.addView(col);
        return sv;
    }

    // --- TAB 3: Queue — the play order + building/importing (Add from library, Save, Import) ---
    private View buildQueueTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL); wrap.setBackgroundColor(BG);

        // build/import bar (top): Add from library · Import a video from this phone
        LinearLayout build = new LinearLayout(this); build.setOrientation(LinearLayout.HORIZONTAL);
        build.setPadding(0, 0, 0, dp(6));
        Button addLib = smallBtn("＋  Add from library"); addLib.setTextColor(ACCENT);
        addLib.setOnClickListener(v -> openLibrary());
        Button imp = smallBtn("⬆  Import video"); imp.setTextColor(ACCENT);
        imp.setOnClickListener(v -> pickImport());
        build.addView(addLib, wlp(1.4f)); build.addView(imp, wlp(1f));
        wrap.addView(build);

        // scrollable queue list: [0] header, [1] toolbar(shuffle/loop/save), [2+] rows
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, 0, 0, dp(16));
        TextView hh = new TextView(this);
        hh.setText("Up next"); hh.setTextColor(TXT); hh.setTextSize(18);
        hh.setTypeface(hh.getTypeface(), android.graphics.Typeface.BOLD); hh.setPadding(dp(4), 0, 0, dp(6));
        col.addView(hh);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        Button sh = smallBtn(""), lo = smallBtn(""), save = smallBtn("Save as playlist…");
        sh.setOnClickListener(v -> { send("shuffle:toggle"); toast(lastShuffle ? "Shuffle off" : "Shuffle on"); main.postDelayed(() -> loadQueue(col), 800); });
        lo.setOnClickListener(v -> { send("loop:toggle"); toast(lastLoop ? "Will stop at the end (TV off)" : "Looping forever"); main.postDelayed(() -> loadQueue(col), 800); });
        save.setOnClickListener(v -> saveQueueAs());
        bar.addView(sh, wlp(1f)); bar.addView(lo, wlp(1.2f)); bar.addView(save, wlp(1.5f));
        col.addView(bar);
        TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0); col.addView(t);
        qCol = col; queueView = sv;
        sv.addView(col);
        wrap.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderQueueBar(col);
        return wrap;
    }

    // --- service wiring ------------------------------------------------------
    private final RemoteService.Listener svcListener = new RemoteService.Listener() {
        public void onStatus(String s) {
            boolean ready = s.startsWith("ready");
            ((GradientDrawable) dot.getBackground()).setColor(ready ? GOOD : WARN);
            RemoteService svc = RemoteService.get();
            String ws = WearPusher.watchStatus();
            String tgt = svc != null ? svc.target() : null;
            String base;
            if (RemoteService.ALL.equals(tgt)) base = "Both";                              // both in unison
            else if (tgt != null && svc.connectedTablet() != null) base = svc.connectedTablet();
            else base = ready && svc != null ? svc.transport() : "connect";
            linkLabel.setText(base + (ws.isEmpty() ? "" : "  ·  " + ws) + "  ▾");
            if (connView != null) renderConnStatus();
        }
        public void onState(String json) { showState(json); }
    };
    private void attach() {
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(svcListener); else main.postDelayed(this::attach, 300);
    }
    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }

    /** Position / sleep countdown interpolated from the last push (ticks every second while visible). */
    private void renderClock() {
        long el = lastPlaying ? System.currentTimeMillis() - lastStateAt : 0;
        int pos = (int) Math.min(lastDur > 0 ? lastDur : Integer.MAX_VALUE, lastPos + el);
        progress.setProgress(lastDur > 0 ? Math.round(1000f * pos / lastDur) : 0);
        String bat = lastBattery < 0 ? "" : "     " + (lastCharging ? "⚡ " : "🔋 ") + lastBattery + "%";
        timeText.setText((lastDur > 0 ? mmss(pos) + " / " + mmss(lastDur) + "     vol " + Math.round(lastVol * 100) + "%" : "TV") + bat);
        if (lastSleeping) {
            long s = Math.max(0, lastSleepMs - (System.currentTimeMillis() - lastStateAt)) / 1000;
            sleepText.setText("Sleeping in " + (s / 60) + ":" + String.format("%02d", s % 60));
        } else if (lastNapped) sleepText.setText("TV is off");
        else sleepText.setText("");
    }

    private void showState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            boolean playing = o.optBoolean("playing", false);
            playPause.setPlaying(playing);
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            title.setText((i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim());
            subtitle.setText((i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim());
            lastPos = o.optInt("position_ms", 0); lastDur = o.optInt("duration_ms", 0); lastPlaying = playing;
            lastVol = o.optDouble("volume", 0); lastStateAt = System.currentTimeMillis();
            lastSleeping = o.optBoolean("sleeping", false); lastSleepMs = Math.max(0, o.optLong("sleep_remaining_ms", 0));
            lastNapped = o.optBoolean("napped", false);
            lastBattery = o.optInt("battery", -1); lastCharging = o.optBoolean("charging", false);
            renderClock();
            currentPl = o.optString("playlist", "Shuffle All");
            if (plNow != null) plNow.setText("Now playing:  " + currentPl);
            String ip = o.optString("ip", "");
            if (!ip.isEmpty() && !local) tabletIp = ip;   // local stays on loopback
            // queue mode + connection level + the TV's hotspot creds (present only while it runs)
            lastShuffle = o.optBoolean("shuffle", lastShuffle); lastLoop = o.optBoolean("loop", lastLoop);
            if (o.has("conn")) lastConn = o.optString("conn", lastConn);
            JSONObject hs = o.optJSONObject("hotspot");
            hsSsid = hs != null ? hs.optString("ssid", null) : null; hsPass = hs != null ? hs.optString("pass", null) : null;
            if (connView != null) renderConnStatus();
        } catch (Exception ignored) {}
    }

    // --- Playlists tab: fill plCol with the playlist rows (built-ins + your saved queues) ---
    private void populatePicker(final LinearLayout col, final int attempt) {
        final RemoteService svc = RemoteService.get();
        List<String> names = svc != null ? svc.playlistNames() : null;
        List<String> user = svc != null ? svc.userPlaylistNames() : new java.util.ArrayList<>();
        // keep the header (index 0), rebuild the rows
        while (col.getChildCount() > 1) col.removeViewAt(1);
        if (names != null && !names.isEmpty()) {
            boolean userHeader = false;
            for (final String name : names) {
                final boolean mine = user.contains(name);
                if (mine && !userHeader) {   // user-made playlists (saved queues) get their own section
                    userHeader = true;
                    TextView s = noteText("Your playlists  ·  long-press to delete"); s.setTextColor(0xFF8FD694); col.addView(s);
                }
                Button row = rowBtn(name, name.equals(currentPl) ? 0xFF8FD694 : TXT);
                row.setTextSize(16);
                row.setOnClickListener(v -> { send("play:" + name); showTab(0); });
                if (mine) row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle("Delete playlist?").setMessage(name)
                            .setPositiveButton("Delete", (d, w) -> {
                                send("delpl:" + name); toast("Deleted \"" + name + "\"");
                                if (svc != null) main.postDelayed(svc::requestPlaylists, 500);
                                main.postDelayed(() -> { if (picker != null) populatePicker(col, 0); }, 1200);
                            }).setNegativeButton("Cancel", null).show();
                    return true;
                });
                col.addView(row);
            }
        } else if (attempt < 8) {
            TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0);
            col.addView(t);
            main.postDelayed(() -> { if (picker != null) populatePicker(col, attempt + 1); }, 400);   // list arrives async
        }
    }
    private LinearLayout.LayoutParams wlp(float w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), w); p.setMargins(dp(2), 0, dp(2), 0); return p; }
    private Button smallBtn(String text) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(text); b.setTextSize(12); b.setTextColor(TXT); b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable g = new GradientDrawable(); g.setColor(BTN); g.setCornerRadius(dp(10)); b.setBackground(g);
        return b;
    }
    private void renderQueueBar(LinearLayout col) {
        if (col.getChildCount() < 2 || !(col.getChildAt(1) instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) col.getChildAt(1);
        Button sh = (Button) bar.getChildAt(0), lo = (Button) bar.getChildAt(1);
        sh.setText(lastShuffle ? "Shuffle: on" : "Shuffle: off"); sh.setTextColor(lastShuffle ? 0xFF8FD694 : TXT);
        lo.setText(lastLoop ? "Loop forever" : "Stop at end");
    }
    private void loadQueue(final LinearLayout col) {
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
        if (json != null) { if (col.getChildCount() <= 3) renderQueue(col, json); return; }   // don't clobber a wider Wi-Fi render
        if (attempt < 12) main.postDelayed(() -> { if (queueView != null) populateQueue(col, attempt + 1); }, 400);
    }
    private void renderQueue(final LinearLayout col, String json) {
        try {
            JSONObject o = new JSONObject(json);
            int from = o.optInt("from", 0), index = o.optInt("index", -1), total = o.optInt("total", 0);
            org.json.JSONArray items = o.optJSONArray("items");
            if (o.has("shuffle")) lastShuffle = o.optBoolean("shuffle");
            if (o.has("loop")) lastLoop = o.optBoolean("loop");
            renderQueueBar(col);
            while (col.getChildCount() > 2) col.removeViewAt(2);
            ((TextView) col.getChildAt(0)).setText("Up next  ·  " + o.optString("playlist", currentPl) + "   (" + total + ")");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                final int abs = from + i;
                final String name = pretty(items.optString(i));
                Button row = rowBtn((abs == index ? "▶  " : "") + (abs + 1) + ".  " + name, abs == index ? 0xFF8FD694 : TXT);
                row.setOnClickListener(v -> { send("jump:" + abs); showTab(0); });
                final boolean current = abs == index;
                row.setOnLongClickListener(v -> {
                    if (current) { toast("That one's playing now"); return true; }
                    new AlertDialog.Builder(this).setTitle("Remove from the queue?").setMessage(name)
                            .setPositiveButton("Remove", (d, w) -> { send("deq:" + abs); main.postDelayed(() -> loadQueue(col), 700); })
                            .setNegativeButton("Cancel", null).show();
                    return true;
                });
                col.addView(row);
            }
            col.addView(noteText("Tap to jump  ·  long-press to remove"));
        } catch (Exception ignored) {}
    }
    private void saveQueueAs() {
        final EditText e = field("Playlist name", false);
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Save the queue as a playlist").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) { toast("Give it a name"); return; }
                    send("savequeue:" + n); toast("Saved as \"" + n + "\"");
                    RemoteService svc = RemoteService.get(); if (svc != null) main.postDelayed(svc::requestPlaylists, 800);
                }).setNegativeButton("Cancel", null).show();
    }
    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close(); return bo.toString("UTF-8");
    }
    @Override public void onBackPressed() {
        if (still != null) closeStill();
        else if (epView != null) closeEpisodes();
        else if (libView != null) closeLibrary();
        else if (settingsView != null) closeSettings();
        else if (connView != null) closeConnection();
        else if (curTab != 0) showTab(0);   // a sub-tab -> back to the Remote tab
        else super.onBackPressed();
    }

    // =====================================================================================
    //  Shared overlay helpers (library / episodes / connection / queue / picker)
    // =====================================================================================
    private ScrollView overlay(String title) {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(20), dp(16), dp(16));
        TextView h = new TextView(this);
        h.setText(title); h.setTextColor(TXT); h.setTextSize(20);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(10));
        col.addView(h);
        TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0); col.addView(t);
        sv.addView(col); frame.addView(sv);
        return sv;
    }
    private static LinearLayout colOf(View overlay) { return (LinearLayout) ((ScrollView) overlay).getChildAt(0); }
    private static void clearRows(LinearLayout col, int keep) { while (col.getChildCount() > keep) col.removeViewAt(keep); }
    private Button rowBtn(String text, int color) {
        Button row = new Button(this);
        row.setAllCaps(false); row.setText(text); row.setTextColor(color); row.setTextSize(14); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMaxLines(2);
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(12)); row.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        return row;
    }
    private TextView noteText(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(SUB); t.setTextSize(12); t.setPadding(dp(6), dp(6), dp(6), dp(6)); return t; }
    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(TXT); e.setHintTextColor(SUB); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        return e;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String pretty(String t) { return t.replaceAll("\\.(mp4|mkv|m4v)$", "").replace('.', ' ').replace('⧸', '/').trim(); }
    private static String enc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    /** Typed query ({"type":..} payload): Wi-Fi fast path when the TV's IP is reachable (local mode
        always), otherwise Bluetooth (the paged protocol). cb runs on the main thread; null = failure. */
    private void fetchTyped(final String httpPath, final String bleCmd, final String type, final java.util.function.Consumer<JSONObject> cb) {
        new Thread(() -> {
            String ip = tabletIp;
            if (ip != null && !ip.isEmpty()) {
                java.net.HttpURLConnection c = null;
                try {
                    c = openOnWifi("http://" + ip + ":8090" + httpPath);
                    c.setConnectTimeout(1500); c.setReadTimeout(5000);
                    if (c.getResponseCode() == 200) {
                        final JSONObject o = new JSONObject(readAll(c.getInputStream()));
                        if (type.equals(o.optString("type"))) { main.post(() -> cb.accept(o)); return; }
                    }
                } catch (Exception ignored) {} finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
            }
            RemoteService svc = RemoteService.get();
            if (svc == null) { main.post(() -> cb.accept(null)); return; }
            svc.requestTyped(bleCmd, type);
            for (int i = 0; i < 25; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
                String j = svc.typedJson(type);
                if (j != null) {
                    try { final JSONObject o = new JSONObject(j); main.post(() -> cb.accept(o)); }
                    catch (Exception e) { main.post(() -> cb.accept(null)); }
                    return;
                }
            }
            main.post(() -> cb.accept(null));
        }).start();
    }

    // =====================================================================================
    //  LIBRARY: browse the TV's shows / episodes and enqueue. HTTP when the TV is reachable
    //  (loopback on the TV itself), else Bluetooth with the paged "shows" / "eps:" protocol.
    // =====================================================================================
    private final java.util.List<String[]> libShows = new java.util.ArrayList<>();   // [name, count]
    private int libTotal = 0;
    /** A clearly-tappable pill button for the playlist / library / queue chips. */
    private TextView pillChip(String label, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(TXT); t.setTextSize(14); t.setAllCaps(false);
        t.setGravity(Gravity.CENTER); t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(BTN); g.setCornerRadius(dp(22));
        t.setBackground(g);
        t.setClickable(true); t.setOnClickListener(onClick);
        return t;
    }

    private void openLibrary() {
        if (libView != null) return;
        libShows.clear(); libTotal = 0;
        libView = overlay("Library");
        loadShows(colOf(libView), 0);
    }
    private void loadShows(final LinearLayout col, final int from) {
        fetchTyped("/library", from > 0 ? "shows:" + from : "shows", "library", o -> {
            if (libView == null) return;
            if (o == null) { clearRows(col, 1); col.addView(noteText("Couldn't reach the TV's library. Is the Bluetooth link up? A Wi-Fi level is faster — tap the connection label up top.")); return; }
            org.json.JSONArray a = o.optJSONArray("shows");
            libTotal = o.optInt("total", (a != null ? a.length() : 0) + from);
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.optJSONObject(i);
                if (s != null && from + i >= libShows.size()) libShows.add(new String[]{s.optString("name"), String.valueOf(s.optInt("count", 0))});
            }
            renderShows(col);
        });
    }
    private void renderShows(final LinearLayout col) {
        clearRows(col, 1);
        for (final String[] s : libShows) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = rowBtn(s[0] + "   ·   " + s[1] + (s[1].equals("1") ? " episode" : " episodes"), TXT);
            open.setOnClickListener(v -> openEpisodes(s[0]));
            row.addView(open, new LinearLayout.LayoutParams(0, dp(52), 1f));
            Button add = smallBtn("Add all"); add.setTextColor(ACCENT);
            add.setOnClickListener(v -> { send("enqshow:" + s[0]); toast("Added " + s[0] + " to the queue"); });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); alp.setMargins(dp(6), 0, 0, 0);
            row.addView(add, alp);
            col.addView(row);
        }
        if (libShows.size() < libTotal) { Button more = rowBtn("Load more…  (" + libShows.size() + " of " + libTotal + ")", ACCENT); more.setOnClickListener(v -> loadShows(col, libShows.size())); col.addView(more); }
        if (libShows.isEmpty()) col.addView(noteText("No shows found on the TV."));
        else col.addView(noteText("Tap a show for its episodes  ·  Add all queues the whole show"));
    }
    private final java.util.List<String[]> epItems = new java.util.ArrayList<>();   // [id, title]
    private int epTotal = 0; private String epShow;
    private void openEpisodes(String show) {
        if (epView != null) return;
        epShow = show; epItems.clear(); epTotal = 0;
        epView = overlay("‹  " + show);
        colOf(epView).getChildAt(0).setClickable(true);
        colOf(epView).getChildAt(0).setOnClickListener(v -> closeEpisodes());
        loadEpisodes(colOf(epView), 0);
    }
    private void loadEpisodes(final LinearLayout col, final int from) {
        fetchTyped("/library?show=" + enc(epShow), "eps:" + epShow + ":" + from, "episodes", o -> {
            if (epView == null) return;
            if (o == null) { clearRows(col, 1); col.addView(noteText("Couldn't load the episodes. Is the Bluetooth link up?")); return; }
            org.json.JSONArray a = o.optJSONArray("items");
            int f = o.optInt("from", 0);
            epTotal = o.optInt("total", f + (a != null ? a.length() : 0));
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && f + i >= epItems.size()) epItems.add(new String[]{String.valueOf(e.optInt("id", -1)), e.optString("title")});
            }
            renderEpisodes(col);
        });
    }
    private void renderEpisodes(final LinearLayout col) {
        clearRows(col, 1);
        for (final String[] e : epItems) {
            Button row = rowBtn(pretty(e[1]), TXT);
            row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(pretty(e[1]))
                    .setItems(new String[]{"Play now", "Play next", "Add to queue"}, (d, w) -> {
                        send((w == 0 ? "enqnow:" : w == 1 ? "enqnext:" : "enq:") + e[0]);
                        toast(w == 0 ? "Playing" : w == 1 ? "Up next" : "Added to the queue");
                    }).show());
            col.addView(row);
        }
        if (epItems.size() < epTotal) { Button more = rowBtn("Load more…  (" + epItems.size() + " of " + epTotal + ")", ACCENT); more.setOnClickListener(v -> loadEpisodes(col, epItems.size())); col.addView(more); }
        if (epItems.isEmpty()) col.addView(noteText("No episodes."));
        else col.addView(noteText("Tap an episode: Play now · Play next · Add to queue"));
    }
    private void closeEpisodes() { if (epView != null) { frame.removeView(epView); epView = null; } }
    private void closeLibrary() { closeEpisodes(); if (libView != null) { frame.removeView(libView); libView = null; } }

    // =====================================================================================
    //  CONNECTION LEVEL: Bluetooth only / TV hotspot / Remote hotspot / Shared Wi-Fi.
    //  The level is one setting shared by TV and remotes (kidodi_conn); the TV publishes it
    //  in state and RemoteService coordinates the joins/pushes. This screen shows status,
    //  lets you change the level, and offers the manual actions for each.
    // =====================================================================================
    // Top dropdown: pick which tablet this remote drives — one, the other, or BOTH IN UNISON — plus a
    // way into the Connection screen. The tablet choices appear once a second player has been heard.
    private void showTargetMenu() {
        final RemoteService svc = RemoteService.get();
        if (svc == null) { openConnection(); return; }
        final java.util.Map<String, String> tabs = svc.tablets();
        final String cur = svc.target();
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, linkLabel);
        final java.util.HashMap<Integer, String> map = new java.util.HashMap<>();
        int mid = 1;
        if (tabs.size() >= 2) {
            for (java.util.Map.Entry<String, String> e : tabs.entrySet()) {
                pm.getMenu().add(0, mid, mid, (e.getKey().equals(cur) ? "●  " : "○  ") + e.getValue());
                map.put(mid, e.getKey()); mid++;
            }
            pm.getMenu().add(0, mid, mid, (RemoteService.ALL.equals(cur) ? "●  " : "○  ") + "Both in unison");
            map.put(mid, RemoteService.ALL); mid++;
        }
        final int connId = mid;
        pm.getMenu().add(0, connId, connId, "Connection…");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == connId) { openConnection(); return true; }
            final String t = map.get(id);
            if (t != null) {
                svc.setTarget(t);
                toast(RemoteService.ALL.equals(t) ? "Both in unison" : "Using " + tabs.get(t));
                linkLabel.setText((RemoteService.ALL.equals(t) ? "Both" : tabs.get(t)) + "  ▾");
            }
            return true;
        });
        pm.show();
    }

    static final String[][] CONN_MODES = {
            {"ble", "Bluetooth only", "Controls + a still image every second or so. Works anywhere, no Wi-Fi needed."},
            {"tv_hotspot", "TV hotspot", "The TV hosts a Wi-Fi hotspot and remotes join it. Adds live camera + fast library browsing."},
            {"remote_hotspot", "Remote hotspot", "This remote hosts the hotspot and the TV joins it. Adds live camera + fast library browsing."},
            {"shared", "Shared Wi-Fi", "TV and remotes on the same network (home Wi-Fi). Adds live camera + fast library browsing."}};
    private volatile String tvReach = "checking…";
    private void openConnection() {
        if (connView != null) return;
        connView = overlay("Connection");
        LinearLayout col = colOf(connView);
        clearRows(col, 1);
        connStatus = noteText(""); connStatus.setTextColor(TXT); connStatus.setTextSize(13); col.addView(connStatus);
        connModes = new LinearLayout(this); connModes.setOrientation(LinearLayout.VERTICAL); col.addView(connModes);
        connActions = new LinearLayout(this); connActions.setOrientation(LinearLayout.VERTICAL); col.addView(connActions);
        Button back = rowBtn("Back", TXT); back.setOnClickListener(v -> closeConnection()); col.addView(back);
        RemoteService svc = RemoteService.get();
        if (svc != null) lastConn = svc.connMode();
        renderConnStatus(); renderConnModes(); renderConnActions();
        if (svc != null) svc.requestConn();
        probeTv();
    }
    private void probeTv() {   // is the TV actually reachable over Wi-Fi from here?
        new Thread(() -> {
            String ip = tabletIp, r;
            if (ip == null || ip.isEmpty()) r = "unknown (no IP from the TV yet)";
            else {
                java.net.HttpURLConnection c = null;
                try { c = openOnWifi("http://" + ip + ":8090/state"); c.setConnectTimeout(1500); c.setReadTimeout(1500); r = c.getResponseCode() == 200 ? "reachable at " + ip : "not reachable (" + ip + ")"; }
                catch (Exception e) { r = "not reachable (" + ip + ")"; }
                finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
            }
            tvReach = r; main.post(this::renderConnStatus);
        }).start();
    }
    private void renderConnStatus() {
        if (connStatus == null) return;
        RemoteService svc = RemoteService.get();
        StringBuilder sb = new StringBuilder();
        if (local) sb.append("This is the TV's own remote (loopback).\n");
        else sb.append("Bluetooth:  ").append(svc != null && svc.bleConnected() ? "connected" : "not connected").append('\n');
        sb.append("TV over Wi-Fi:  ").append(local ? "yes (this is the TV)" : tvReach).append('\n');
        sb.append("TV hotspot:  ").append(hsSsid != null ? hsSsid : "off").append('\n');
        if (!local) sb.append("This remote's hotspot:  ").append(HotspotService.running() && HotspotService.currentSsid() != null ? HotspotService.currentSsid() : "off");
        connStatus.setText(sb.toString().trim());
    }
    /** Which tablet this remote drives. Hidden until a second player has been heard: with one tablet
        there's nothing to choose and the remote keeps attaching to whatever answers first. */
    private void renderTablets() {
        final RemoteService svc = RemoteService.get();
        if (svc == null || local) return;
        java.util.Map<String, String> found = svc.tablets();
        final String target = svc.target();
        if (found.size() < 2 && target == null) return;
        TextView hdr = noteText("Tablet"); hdr.setTextColor(SUB); connModes.addView(hdr);
        for (final java.util.Map.Entry<String, String> e : found.entrySet()) {
            boolean on = e.getKey().equals(target);
            Button b = rowBtn((on ? "●  " : "○  ") + e.getValue(), on ? 0xFF8FD694 : TXT);
            b.setOnClickListener(v -> {
                svc.setTarget(e.getKey());
                toast("Using " + e.getValue());
                main.postDelayed(() -> { renderConnModes(); renderConnStatus(); }, 600);
            });
            connModes.addView(b);
        }
        if (target != null) {
            Button any = rowBtn("○  Any tablet (first to answer)", TXT);
            any.setOnClickListener(v -> {
                svc.setTarget(null);
                toast("Using any tablet");
                main.postDelayed(() -> { renderConnModes(); renderConnStatus(); }, 600);
            });
            connModes.addView(any);
        }
        connModes.addView(noteText("More than one player is in range — pick the one this remote drives."));
    }

    private void renderConnModes() {
        if (connModes == null) return;
        connModes.removeAllViews();
        renderTablets();
        for (final String[] m : CONN_MODES) {
            boolean on = m[0].equals(lastConn);
            Button b = rowBtn((on ? "●  " : "○  ") + m[1], on ? 0xFF8FD694 : TXT);
            b.setOnClickListener(v -> setConnMode(m[0]));
            connModes.addView(b);
            connModes.addView(noteText(m[2]));
        }
    }
    private void setConnMode(String mode) {
        lastConn = mode;
        Cfg.put(this, "kidodi_conn", mode);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setConnMode(mode); else send("conn:" + mode);
        renderConnModes(); renderConnActions();
        toast("Connection level: " + mode.replace('_', ' '));
        main.postDelayed(this::probeTv, 4000);
    }
    private void renderConnActions() {
        if (connActions == null) return;
        connActions.removeAllViews();
        TextView hdr = noteText("— " + lastConn.replace('_', ' ') + " —"); hdr.setGravity(Gravity.CENTER); connActions.addView(hdr);
        switch (lastConn) {
            case "tv_hotspot":
                if (local) {
                    connActions.addView(noteText("The TV hosts the hotspot at this level (it turns on automatically). Remotes get the details over Bluetooth, or scan this QR."));
                    Button qr = rowBtn("Show hotspot QR for a remote to scan", ACCENT);
                    qr.setOnClickListener(v -> showWifiQr(HotspotService.currentSsid(), HotspotService.currentPass(), "Scan with the phone remote to join the TV"));
                    connActions.addView(qr);
                } else {
                    Button join = rowBtn(hsSsid != null ? "Join TV hotspot  ·  " + hsSsid : "Join TV hotspot  (waiting for the TV to publish it)", ACCENT);
                    join.setOnClickListener(v -> {
                        if (hsSsid == null) { toast("The TV hasn't published a hotspot yet — is it on this level?"); return; }
                        WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = hsSsid; c.type = "WPA"; c.pass = hsPass;
                        toast(WifiJoiner.join(this, c) ? "Joining " + hsSsid + "… (accept the prompt if asked)" : "Couldn't request the join");
                        main.postDelayed(this::probeTv, 6000);
                    });
                    connActions.addView(join);
                    Button scan = rowBtn("Scan the TV's QR instead", TXT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                }
                break;
            case "remote_hotspot":
                if (local) {
                    connActions.addView(noteText("A remote hosts the hotspot and sends the details over Bluetooth; the TV joins by itself. Or scan the remote's QR here."));
                    Button scan = rowBtn("Scan the remote's hotspot QR", ACCENT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                } else {
                    boolean up = HotspotService.running() && HotspotService.currentSsid() != null;
                    Button host = rowBtn(up ? "Hotspot on  ·  " + HotspotService.currentSsid() : "Host hotspot for the TV", ACCENT);
                    host.setOnClickListener(v -> { if (up) toast("Hotspot is running"); else startOwnHotspot(); });
                    connActions.addView(host);
                    if (up) {
                        Button push = rowBtn("Send Wi-Fi details to the TV (Bluetooth)", TXT);
                        push.setOnClickListener(v -> { RemoteService s = RemoteService.get(); if (s != null) s.pushOwnHotspot(); toast("Sent — the TV joins in a few seconds"); main.postDelayed(this::probeTv, 8000); });
                        connActions.addView(push);
                        Button qr = rowBtn("Show QR for the TV to scan", TXT);
                        qr.setOnClickListener(v -> showWifiQr(HotspotService.currentSsid(), HotspotService.currentPass(), "On the TV: Settings → Connection → Scan"));
                        connActions.addView(qr);
                        Button stop = rowBtn("Stop hotspot", TXT);
                        stop.setOnClickListener(v -> { HotspotService.stop(this); main.postDelayed(() -> { renderConnActions(); renderConnStatus(); }, 800); });
                        connActions.addView(stop);
                    } else connActions.addView(noteText("Once it's up, the details go to the TV over Bluetooth automatically."));
                }
                break;
            case "shared":
                if (local) {
                    connActions.addView(noteText("Join the TV to your home Wi-Fi: scan a Wi-Fi QR (the phone remote can show one), or let the remote send the details over Bluetooth."));
                    Button scan = rowBtn("Scan a Wi-Fi QR", ACCENT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                } else {
                    connActions.addView(noteText("Put the TV on the same Wi-Fi as this phone. Enter the network below."));
                    final EditText ssid = field("Wi-Fi name (SSID)", false), pass = field("Password", true);
                    connActions.addView(ssid); connActions.addView(pass);
                    Button sendBtn = rowBtn("Send to TV over Bluetooth", ACCENT);
                    sendBtn.setOnClickListener(v -> {
                        String s = ssid.getText().toString().trim(), p = pass.getText().toString();
                        if (s.isEmpty()) { toast("Enter the Wi-Fi name"); return; }
                        send("wifi:" + s + "\t" + p); toast("Sent — the TV joins in a few seconds"); main.postDelayed(this::probeTv, 8000);
                    });
                    connActions.addView(sendBtn);
                    connActions.addView(noteText("Note: this sends the password over Bluetooth unencrypted. Prefer the QR if strangers are in range."));
                    Button qr = rowBtn("Show as QR for the TV to scan instead", TXT);
                    qr.setOnClickListener(v -> { String s = ssid.getText().toString().trim(); if (s.isEmpty()) { toast("Enter the Wi-Fi name"); return; } showWifiQr(s, pass.getText().toString(), "On the TV: Settings → Connection → Scan"); });
                    connActions.addView(qr);
                }
                break;
            default:
                connActions.addView(noteText("Pure Bluetooth: play/pause, playlists, queue, volume, light, sleep, power, and a still image every second or so. Live camera and fast library browsing need a Wi-Fi level."));
        }
    }
    private void showWifiQr(String ssid, String pass, String caption) {
        if (ssid == null) { toast("No hotspot running yet"); return; }
        startActivity(new Intent(this, QrShareActivity.class)
                .putExtra(QrShareActivity.EXTRA_PAYLOAD, "WIFI:S:" + qrEsc(ssid) + ";T:WPA;P:" + qrEsc(pass == null ? "" : pass) + ";;")
                .putExtra(QrShareActivity.EXTRA_CAPTION, caption));
    }
    private static String qrEsc(String s) { return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\""); }
    private void startOwnHotspot() {
        // a LocalOnlyHotspot needs the nearby-devices permission (13+) / location (older) on a non-owner phone
        String perm = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : "android.permission.ACCESS_FINE_LOCATION";
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{perm}, 44); return; }
        HotspotService.start(this);
        toast("Starting hotspot…");
        main.postDelayed(() -> { renderConnActions(); renderConnStatus(); RemoteService s = RemoteService.get(); if (s != null) s.pushOwnHotspot(); }, 3000);
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != 44) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startOwnHotspot();
        else toast("The hotspot needs the Nearby devices permission");
    }
    private void closeConnection() { if (connView != null) { frame.removeView(connView); connView = null; connStatus = null; connModes = null; connActions = null; } }

    // =====================================================================================
    //  Settings — every TV option, mirrored from the phone (reads the TV's /config, writes cfg:K=V).
    //  Device-owner-only rows are greyed with a "requires device owner" notice on a base-level TV.
    // =====================================================================================
    private void openSettings() {
        if (settingsView != null) return;
        if (local) { startActivity(new Intent(this, SettingsActivity.class)); return; }   // on the TV: the real screen
        settingsView = overlay("TV settings");
        cfgVals = null;
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.requestConfig();
        loadConfig(0);
    }
    private void loadConfig(final int attempt) {
        if (settingsView == null) return;
        RemoteService svc = RemoteService.get();
        String json = svc != null ? svc.configJson() : null;
        if (json != null) { applyConfigJson(json); renderSettings(); return; }
        if (attempt < 15) main.postDelayed(() -> loadConfig(attempt + 1), 400);
        else {
            LinearLayout col = colOf(settingsView); clearRows(col, 1);
            col.addView(noteText("Couldn't read the TV's settings — is it connected?"));
            Button b = rowBtn("Back", TXT); b.setOnClickListener(v -> closeSettings()); col.addView(b);
        }
    }
    private void applyConfigJson(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            cfgOwner = o.optBoolean("device_owner", false);
            cfgPinSet = o.optBoolean("pin_set", false);
            cfgVals = o.optJSONObject("values"); if (cfgVals == null) cfgVals = new org.json.JSONObject();
        } catch (Exception e) { cfgVals = new org.json.JSONObject(); }
    }
    private int cfgI(String k, int d) { return cfgVals != null ? cfgVals.optInt(k, d) : d; }
    private String cfgS(String k, String d) { return cfgVals != null ? cfgVals.optString(k, d) : d; }
    private void setCfg(String k, String val) {
        RemoteService svc = RemoteService.get(); if (svc != null) svc.setConfig(k, val);
        try { if (cfgVals != null) cfgVals.put(k, val.matches("-?\\d+") ? (Object) Integer.valueOf(val) : val); } catch (Exception ignored) {}
    }
    private void renderSettings() {
        if (settingsView == null) return;
        LinearLayout col = colOf(settingsView); clearRows(col, 1);
        col.addView(noteText(cfgOwner ? "The TV's settings — change any of them from here."
                : "The TV's settings. Locked rows need device-owner setup (tap a 🔒 notice to learn more)."));

        sSec(col, "Playback");
        cfgIntCycle(col, "Light duration (seconds)", "kidodi_flash_ms", new int[]{1000, 2000, 3000, 5000, 10000}, 1000);
        { final boolean loop = cfgI("kidodi_loop", 1) == 1;
          Button b = setRow(col, "When the queue ends"); b.setText(loop ? "loop forever" : "stop (TV off)");
          b.setOnClickListener(v -> { setCfg("kidodi_loop", cfgI("kidodi_loop", 1) == 1 ? "0" : "1"); renderSettings(); }); }

        sSec(col, "Connection level");
        for (final String[] m : CONN_MODES) {
            boolean on = m[0].equals(cfgS("kidodi_conn", "ble"));
            Button b = setRow(col, (on ? "●  " : "○  ") + m[1]); b.setText(on ? "current" : "choose"); b.setTextColor(on ? GOOD : TXT);
            b.setOnClickListener(v -> { RemoteService svc = RemoteService.get(); if (svc != null) svc.setConnMode(m[0]);
                try { cfgVals.put("kidodi_conn", m[0]); } catch (Exception ignored) {} renderSettings(); });
        }

        sSec(col, "Toddler play mode");
        cfgStrCycle(col, "Sound", "kidodi_playmode_sound", new String[]{"muted", "wav", "video"}, new String[]{"Muted", "Letter sounds", "Video sound"});

        sSec(col, "Unlock gesture");
        if (cfgOwner) {
            cfgIntCycle(col, "Fingers", "kidodi_esc_fingers", new int[]{2, 3, 4, 5}, 1);
            cfgStrCycle(col, "Type", "kidodi_esc_mode", new String[]{"taps", "swipe"}, new String[]{"taps", "swipe"});
            cfgIntCycle(col, "Taps needed", "kidodi_esc_taps", new int[]{2, 3, 4, 5, 6}, 1);
            cfgIntCycle(col, "Within (seconds)", "kidodi_esc_window_ms", new int[]{2000, 3000, 5000, 8000}, 1000);
            cfgStrCycle(col, "Swipe direction", "kidodi_esc_dir", new String[]{"up", "down", "left", "right"}, new String[]{"up", "down", "left", "right"});
        } else {
            sDim(col, "Fingers", String.valueOf(cfgI("kidodi_esc_fingers", 4)));
            sDim(col, "Type", cfgS("kidodi_esc_mode", "taps"));
            sDim(col, "Taps needed", String.valueOf(cfgI("kidodi_esc_taps", 4)));
            sDim(col, "Within (seconds)", String.valueOf(cfgI("kidodi_esc_window_ms", 3000) / 1000));
            sDim(col, "Swipe direction", cfgS("kidodi_esc_dir", "up"));
            sGated(col);
        }

        sSec(col, "Lock");
        // How the adult unlocks (to the menu / settings / exit). Randomized PIN = a timed code shown on
        // screen you copy (nothing to remember); Preset PIN = your own Admin PIN (not shown); Pattern =
        // a set pattern (drawn on the TV). Vary it to keep a clever toddler guessing.
        final String[] uv = {"challenge", "pin", "pattern"}, ul = {"Randomized PIN", "Preset PIN", "Pattern"};
        if (cfgOwner) {
            final String style = cfgS("kidodi_unlock_style", "challenge");
            Button mrow = setRow(col, "Method"); mrow.setText(sLabel(style, uv, ul));
            mrow.setOnClickListener(v -> {
                String cur = cfgS("kidodi_unlock_style", "challenge"), nx = uv[0];
                for (int i = 0; i < uv.length; i++) if (uv[i].equals(cur)) { nx = uv[(i + 1) % uv.length]; break; }
                setCfg("kidodi_unlock_style", nx); renderSettings();   // re-render so the sub-settings show/hide
            });
            if ("challenge".equals(style)) {   // shown-on-screen random code -> expose its parameters
                cfgIntCycle(col, "PIN length", "kidodi_chal_len", new int[]{4, 5, 6, 7, 8}, 1);
                cfgIntCycle(col, "Time to enter (s)", "kidodi_chal_secs", new int[]{15, 30, 45, 60}, 1);
                cfgIntCycle(col, "Attempts", "kidodi_chal_tries", new int[]{1, 2, 3, 5}, 1);
            }
            Button pin = setRow(col, "Admin PIN"); pin.setText(cfgPinSet ? "change" : "set"); pin.setOnClickListener(v -> changeTvPin());
            final boolean k = cfgI("kidodi_kiosk", 0) == 1;
            Button kb = setRow(col, "Kiosk lock"); kb.setText(k ? "on" : "off");
            kb.setOnClickListener(v -> { setCfg("kidodi_kiosk", cfgI("kidodi_kiosk", 0) == 1 ? "0" : "1"); renderSettings(); });
        } else {
            sDim(col, "Method", sLabel(cfgS("kidodi_unlock_style", "challenge"), uv, ul));
            sDim(col, "Admin PIN", "••••");
            sDim(col, "Kiosk lock", cfgI("kidodi_kiosk", 0) == 1 ? "on" : "off");
            sGated(col);
        }

        Button about = rowBtn("About Kidodi & feature levels", ACCENT); about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class))); col.addView(about);
        Button back = rowBtn("Back", TXT); back.setOnClickListener(v -> closeSettings()); col.addView(back);
    }
    private void closeSettings() { if (settingsView != null) { frame.removeView(settingsView); settingsView = null; } }

    private void changeTvPin() {
        final EditText a = field("New PIN (4+ digits)", true), b2 = field("Repeat new PIN", true);
        a.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        b2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(a); box.addView(b2);
        new AlertDialog.Builder(this).setTitle("Change TV admin PIN").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = a.getText().toString().trim();
                    if (!p.matches("\\d{4,}")) { toast("PIN must be at least 4 digits"); return; }
                    if (!p.equals(b2.getText().toString().trim())) { toast("PINs don't match"); return; }
                    setCfg("kidodi_pin", p); cfgPinSet = true; toast("PIN changed"); renderSettings();
                }).setNegativeButton("Cancel", null).show();
    }

    // --- settings-overlay row helpers ---
    private void sSec(LinearLayout col, String t) {
        TextView s = new TextView(this); s.setText(t); s.setTextColor(GOOD); s.setTextSize(15);
        s.setPadding(0, dp(16), 0, dp(6)); col.addView(s);
    }
    private Button setRow(LinearLayout col, String label) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(12)); row.setBackground(g);
        row.setPadding(dp(14), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(15);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button v = new Button(this); v.setAllCaps(false); v.setTextSize(14); v.setMinWidth(dp(108)); v.setTextColor(TXT);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(BTN); bg.setCornerRadius(dp(10)); v.setBackground(bg);
        row.addView(v); col.addView(row);
        return v;
    }
    private void cfgIntCycle(LinearLayout col, String label, final String key, final int[] opts, final int div) {
        final Button v = setRow(col, label);
        v.setText(String.valueOf(cfgI(key, opts[0]) / div));
        v.setOnClickListener(x -> {
            int cur = cfgI(key, opts[0]), nx = opts[0];
            for (int i = 0; i < opts.length; i++) if (opts[i] == cur) { nx = opts[(i + 1) % opts.length]; break; }
            setCfg(key, String.valueOf(nx)); v.setText(String.valueOf(nx / div));
        });
    }
    private void cfgStrCycle(LinearLayout col, String label, final String key, final String[] vals, final String[] labels) {
        final Button v = setRow(col, label);
        v.setText(sLabel(cfgS(key, vals[0]), vals, labels));
        v.setOnClickListener(x -> {
            String cur = cfgS(key, vals[0]), nx = vals[0];
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) { nx = vals[(i + 1) % vals.length]; break; }
            setCfg(key, nx); v.setText(sLabel(nx, vals, labels));
        });
    }
    private static String sLabel(String v, String[] vals, String[] labels) { for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return labels[i]; return v; }
    private void sDim(LinearLayout col, String label, String value) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFF151A20); g.setCornerRadius(dp(12)); row.setBackground(g);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        TextView l = new TextView(this); l.setText("🔒  " + label); l.setTextColor(0xFF5A6470); l.setTextSize(15);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = new TextView(this); vv.setText(value); vv.setTextColor(0xFF4A525C); vv.setTextSize(14); row.addView(vv);
        col.addView(row);
    }
    private void sGated(LinearLayout col) {
        TextView t = new TextView(this);
        t.setText("🔒  That feature requires device-owner setup.  Tap here to find out more.");
        t.setTextColor(WARN); t.setTextSize(13); t.setPadding(dp(6), dp(6), dp(6), dp(8)); t.setClickable(true);
        t.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        col.addView(t);
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
            if (connView != null) main.postDelayed(this::probeTv, 5000);
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

    @Override protected void onResume() {
        super.onResume();
        RemoteService.acquire(this, "ui");   // the link is on demand: held while this screen is visible
        attach();
        showTab(0);   // always land on the Remote tab
        main.removeCallbacks(uiTick); main.post(uiTick);
    }
    @Override protected void onPause() {
        main.removeCallbacks(uiTick);
        if (still != null) closeStill();   // never keep polling the camera still in the background
        RemoteService svc = RemoteService.get(); if (svc != null) svc.setListener(null);
        RemoteService.release(this, "ui");
        super.onPause();
    }
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
    // Same card, but runs an arbitrary action (e.g. open the check-in view) instead of sending a command.
    private LinearLayout actionCardRun(int mode, int accent, String label, final Runnable onTap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(withAlpha(accent, 0x33)); g.setCornerRadius(dp(16));
        g.setStroke(dp(1), withAlpha(accent, 0x66)); c.setBackground(g);
        IconView ic = new IconView(this, mode, 0x00000000, accent);
        c.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(12); l.setPadding(0, dp(4), 0, 0);
        c.addView(l);
        c.setClickable(true); c.setOnClickListener(x -> onTap.run());
        return c;
    }
    private View spacer(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)); return v; }
    private LinearLayout.LayoutParams lp(int w, int h, int m) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(m, 0, m, 0); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int m, float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight); p.setMargins(m, 0, m, 0); return p; }
    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // --- Canvas-drawn icons (no font glyphs -> no tofu), ported from v1 -------
    class IconView extends View {
        static final int POWER = 1, PREV = 2, PLAYPAUSE = 3, NEXT = 4, VOLUP = 6, VOLDOWN = 7, SHUFFLE = 8, SLEEP = 9, SUN = 10, WATCH = 11, BEAR = 12;
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
                case WATCH: {   // a person watching a screen — camera "check-in"
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * 0.15f));
                    cv.drawRoundRect(new RectF(cx - s * 1.15f, cy - s * 1.3f, cx + s * 1.15f, cy - s * 0.15f), dp(3), dp(3), p);   // screen
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy + s * 0.62f, s * 0.36f, p);                                                              // viewer's head (from behind)
                    cv.drawArc(new RectF(cx - s * 0.82f, cy + s * 0.85f, cx + s * 0.82f, cy + s * 1.55f), 180f, 180f, true, p);    // shoulders
                    break; }
                case BEAR: {    // teddy — toddler play / lock
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx - s * 0.72f, cy - s * 0.72f, s * 0.4f, p);                                                   // ears
                    cv.drawCircle(cx + s * 0.72f, cy - s * 0.72f, s * 0.4f, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * 0.14f));
                    cv.drawCircle(cx, cy, s * 0.98f, p);                                                                          // head
                    cv.drawCircle(cx, cy + s * 0.42f, s * 0.34f, p);                                                              // snout
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx - s * 0.38f, cy - s * 0.12f, s * 0.13f, p);                                                  // eyes
                    cv.drawCircle(cx + s * 0.38f, cy - s * 0.12f, s * 0.13f, p);
                    cv.drawCircle(cx, cy + s * 0.28f, s * 0.14f, p);                                                              // nose
                    break; }
            }
        }
    }
}
