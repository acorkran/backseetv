package app.anancy.wear;

// Two thin arc gauges hugging the round screen's edge:
//   TOP    = volume: a long arc from the left, over the top, to the right. It fills CLOCKWISE, the same
//            way the bezel turns to raise it. (It used to sit on the right edge filling bottom-up, which is
//            anticlockwise there: turn the bezel one way, watch the bar go the other.)
//   BOTTOM = TV battery (green / amber / red by level), a short arc filling left to right
// Non-interactive — volume is driven by the bezel, battery by the tablet's state. Drawn as a fixed
// background layer so it never steals vertical space from the centred controls (that was the whole
// reason the old 2x3 grid overflowed small round faces).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class RingGauge extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint batt  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vol   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private int batteryPct = -1, volumePct = 0;
    private boolean charging = false;
    private final float stroke, inset;
    private static final float VOL_START = 165, VOL_SWEEP = 210, BATT_SWEEP = 70;

    public RingGauge(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        stroke = d * 6f;
        inset = stroke / 2f + d * 3f;   // sit the arc just inside the physical bezel
        for (Paint p : new Paint[]{track, batt, vol}) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(stroke); p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(0x33FFFFFF);
        vol.setColor(0xFF5AA9E6);
    }

    public void setBattery(int pct, boolean chg) {
        if (pct == batteryPct && chg == charging) return;
        batteryPct = pct; charging = chg; invalidate();
    }
    public void setVolume(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        if (pct == volumePct) return;
        volumePct = pct; invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        // Canvas angles: 0=3 o'clock, 90=6 o'clock, 180=9 o'clock, 270=12 o'clock; positive sweep = clockwise.
        // VOLUME: from just below 9 o'clock, clockwise over 12, to just below 3 o'clock.
        cv.drawArc(oval, VOL_START, VOL_SWEEP, false, track);
        if (volumePct > 0) cv.drawArc(oval, VOL_START, VOL_SWEEP * (volumePct / 100f), false, vol);
        // BATTERY: centred on 6 o'clock, filling from the left (125deg) to the right (55deg).
        cv.drawArc(oval, 55, BATT_SWEEP, false, track);
        if (batteryPct >= 0) {
            batt.setColor(charging ? 0xFF7BE0A0 : batteryPct <= 15 ? 0xFFE2554E
                    : batteryPct <= 30 ? 0xFFE0A64E : 0xFF7BE0A0);
            cv.drawArc(oval, 55 + BATT_SWEEP, -BATT_SWEEP * (batteryPct / 100f), false, batt);
        }
    }
}
