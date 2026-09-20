package app.anancy.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

// --- Canvas-drawn icons (no font glyphs -> no tofu), ported from v1 -------
// Extracted verbatim from RemoteActivity's inner class. The only enclosing-class
// dependency was dp(); it is reproduced here from this View's own resources
// (same display density -> identical values).
class IconView extends View {
    static final int POWER = 1, PREV = 2, PLAYPAUSE = 3, NEXT = 4, VOLUP = 6, VOLDOWN = 7, SHUFFLE = 8, SLEEP = 9, SUN = 10, WATCH = 11, BEAR = 12, MUTE = 13;
    final int mode; int bgCol, iconCol; boolean playing = false;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final GradientDrawable bgd = new GradientDrawable();
    IconView(Context c, int mode, int bgCol, int iconCol) {
        super(c); this.mode = mode; this.bgCol = bgCol; this.iconCol = iconCol; setClickable(true);
        bgd.setCornerRadius(dp(mode == PLAYPAUSE ? 42 : 16)); bgd.setColor(bgCol); setBackground(bgd);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
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
            case MUTE: {   // a speaker with a mute slash
                p.setStyle(Paint.Style.FILL);
                Path sp = new Path();
                sp.moveTo(cx - s * 0.95f, cy - s * 0.34f); sp.lineTo(cx - s * 0.45f, cy - s * 0.34f);
                sp.lineTo(cx + s * 0.12f, cy - s * 0.85f); sp.lineTo(cx + s * 0.12f, cy + s * 0.85f);
                sp.lineTo(cx - s * 0.45f, cy + s * 0.34f); sp.lineTo(cx - s * 0.95f, cy + s * 0.34f); sp.close();
                cv.drawPath(sp, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2.5f, s * 0.2f));
                cv.drawLine(cx + s * 0.42f, cy - s * 0.55f, cx + s * 1.05f, cy + s * 0.6f, p); break; }
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
