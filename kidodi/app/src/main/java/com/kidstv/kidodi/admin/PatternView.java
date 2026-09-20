package com.kidstv.kidodi.admin;

// ============================================================================
//  A 3x3 connected-dot pattern lock (Samsung/Android style), drawn ourselves.
//  Reports the drawn pattern as a dot-index sequence ("0-1-2-5-8") on finger-up.
//  This is NOT the device's system lock pattern — an app can't read/verify that —
//  it's a visually-identical in-app pattern checked against a Kidodi-stored one.
// ============================================================================

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class PatternView extends View {
    public interface OnPattern { void onPattern(String pat); }
    private OnPattern cb;
    private final List<Integer> sel = new ArrayList<>();
    private float curX, curY; private boolean drawing;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), on = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PatternView(Context c) {
        super(c);
        dot.setColor(0xFF35516E); dot.setStyle(Paint.Style.FILL);
        on.setColor(0xFF2F6FEB); on.setStyle(Paint.Style.FILL);
        line.setColor(0xFF2F6FEB); line.setStrokeWidth(14); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND);
    }
    public void setOnPattern(OnPattern c) { cb = c; }
    public void reset() { sel.clear(); drawing = false; invalidate(); }

    private float cell() { return Math.min(getWidth(), getHeight()) / 3f; }
    private float cx(int i) { return cell() * ((i % 3) + 0.5f); }
    private float cy(int i) { return cell() * ((i / 3) + 0.5f); }
    private int hit(float x, float y) {
        float r = cell() * 0.30f;
        for (int i = 0; i < 9; i++) { float dx = x - cx(i), dy = y - cy(i); if (dx * dx + dy * dy <= r * r) return i; }
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sel.clear(); drawing = true;
                // fall through
            case MotionEvent.ACTION_MOVE: {
                curX = e.getX(); curY = e.getY();
                int h = hit(curX, curY);
                if (h >= 0 && !sel.contains(h)) sel.add(h);
                invalidate(); return true;
            }
            case MotionEvent.ACTION_UP:
                drawing = false; invalidate();
                if (cb != null && !sel.isEmpty()) {
                    StringBuilder s = new StringBuilder();
                    for (int i : sel) { if (s.length() > 0) s.append('-'); s.append(i); }
                    cb.onPattern(s.toString());
                }
                return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas cv) {
        for (int i = 1; i < sel.size(); i++)
            cv.drawLine(cx(sel.get(i - 1)), cy(sel.get(i - 1)), cx(sel.get(i)), cy(sel.get(i)), line);
        if (drawing && !sel.isEmpty())
            cv.drawLine(cx(sel.get(sel.size() - 1)), cy(sel.get(sel.size() - 1)), curX, curY, line);
        for (int i = 0; i < 9; i++)
            cv.drawCircle(cx(i), cy(i), cell() * 0.13f, sel.contains(i) ? on : dot);
    }
}
