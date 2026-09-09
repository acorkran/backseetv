package com.kidstv.kidodi;

// ============================================================================
//  Remote role, styled to match v1's polish: dark card theme + Canvas-drawn
//  icons (font glyphs tofu on-device). Now-playing card with progress, big
//  accent play/pause, and coloured Light / Sleep / Power action cards. Drives
//  the tablet through RemoteService (BLE primary, Wi-Fi fallback).
// ============================================================================

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

public class RemoteActivity extends Activity {
    // v1 palette
    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, BTN = 0xFF35516E, TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6;
    static final int ACCENT = 0xFF2F6FEB, GOOD = 0xFF33C05B, WARN = 0xFFE0A63D, PURPLE = 0xFF9B72E0, ORANGE = 0xFFE0673F;

    private final Handler main = new Handler(Looper.getMainLooper());
    private View dot;
    private TextView linkLabel, title, subtitle, timeText, sleepText;
    private ProgressBar progress;
    private IconView playPause;

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

        root.addView(spacer(dp(16)));

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

        setContentView(root);

        RemoteService.ensure(this);
        attach();
    }

    // --- service wiring ------------------------------------------------------
    private final RemoteService.Listener svcListener = new RemoteService.Listener() {
        public void onStatus(String s) {
            boolean ready = s.startsWith("ready");
            ((GradientDrawable) dot.getBackground()).setColor(ready ? GOOD : WARN);
            RemoteService svc = RemoteService.get();
            linkLabel.setText(ready && svc != null ? svc.transport() : "");
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
        } catch (Exception ignored) {}
    }

    private static String mmss(int ms) { int s = ms / 1000; return (s / 60) + ":" + String.format("%02d", s % 60); }

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
