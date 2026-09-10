package com.kidstv.kidodi;

// ============================================================================
//  Hidden admin-unlock gesture recognizer for the kiosk. Defaults to v1's
//  "4 four-finger taps within 3s, anywhere on screen", but every dimension is
//  tunable at runtime via Settings.Global (adb shell settings put global <k> <v>):
//    kidodi_esc_fingers    int     default 4     simultaneous fingers required
//    kidodi_esc_mode       string  default taps  "taps" or "swipe"
//    kidodi_esc_taps       int     default 4     number of taps (taps mode)
//    kidodi_esc_window_ms  int     default 3000  window the taps must fall in
//    kidodi_esc_dir        string  default up    up|down|left|right (swipe mode)
//  This only RECOGNISES the trigger; the PIN pad + unlock live in PlayerActivity.
// ============================================================================

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

class AdminGesture implements View.OnTouchListener {
    interface OnTrigger { void run(); }

    private final Context ctx;
    private final OnTrigger cb;
    AdminGesture(Context c, OnTrigger cb) { this.ctx = c; this.cb = cb; }

    // config latched at the start of each press
    private int fingers, neededTaps; private long window; private String mode, dir;
    // per-press tracking
    private int maxPointers; private long pressStart; private float downX, downY, maxMove;
    // across-taps tracking
    private int tapCount; private long firstTapTime;

    @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fingers = gi("kidodi_esc_fingers", 4);
                mode = gs("kidodi_esc_mode", "taps");
                dir = gs("kidodi_esc_dir", "up");
                neededTaps = gi("kidodi_esc_taps", 4);
                window = gi("kidodi_esc_window_ms", 3000);
                pressStart = e.getEventTime(); maxPointers = 1; downX = e.getX(); downY = e.getY(); maxMove = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                break;
            case MotionEvent.ACTION_MOVE: {
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                maxMove = Math.max(maxMove, (float) Math.hypot(e.getX() - downX, e.getY() - downY));
                break;
            }
            case MotionEvent.ACTION_UP: {
                long dur = e.getEventTime() - pressStart;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if ("swipe".equals(mode)) {
                    if (maxPointers >= fingers && swipeMatches(dir, dx, dy)) cb.run();
                } else {   // taps
                    boolean isTap = dur < 400 && maxMove < dp(24);
                    if (maxPointers >= fingers && isTap) {
                        long now = e.getEventTime();
                        if (now - firstTapTime > window) { firstTapTime = now; tapCount = 1; }
                        else tapCount++;
                        if (tapCount >= neededTaps) { tapCount = 0; cb.run(); }
                    } else tapCount = 0;   // a non-qualifying press breaks the streak
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                maxPointers = 0; break;
        }
        return true;   // consume: the kiosk player has no other touch targets
    }

    private boolean swipeMatches(String d, float dx, float dy) {
        float t = dp(80);
        switch (d) {
            case "down":  return dy >  t && Math.abs(dy) > Math.abs(dx);
            case "left":  return dx < -t && Math.abs(dx) > Math.abs(dy);
            case "right": return dx >  t && Math.abs(dx) > Math.abs(dy);
            default:      return dy < -t && Math.abs(dy) > Math.abs(dx);   // up
        }
    }
    private int gi(String k, int d) { try { return Settings.Global.getInt(ctx.getContentResolver(), k, d); } catch (Exception e) { return d; } }
    private String gs(String k, String d) { try { String x = Settings.Global.getString(ctx.getContentResolver(), k); return (x == null || x.isEmpty()) ? d : x; } catch (Exception e) { return d; } }
    private int dp(int d) { return Math.round(d * ctx.getResources().getDisplayMetrics().density); }
}
