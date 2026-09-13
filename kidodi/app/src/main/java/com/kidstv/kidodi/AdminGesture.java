package com.kidstv.kidodi;

// ============================================================================
//  Hidden admin-unlock gesture recognizer for the kiosk. Ported from v1's proven
//  detector: N taps, each with >= FINGERS fingers held <= TAP_MS, landing within
//  WINDOW_MS (a sliding window — stray touches don't reset the streak). Default is
//  v1's "4 four-finger taps within 3s, anywhere on screen". Every dimension is
//  tunable at runtime via Settings.Global (adb shell settings put global <k> <v>):
//    kidodi_esc_fingers    int     default 4     simultaneous fingers required
//    kidodi_esc_mode       string  default taps  "taps" or "swipe"
//    kidodi_esc_taps       int     default 4     number of taps (taps mode)
//    kidodi_esc_window_ms  int     default 3000  window the taps must fall in
//    kidodi_esc_tap_ms     int     default 800   max land->lift of one tap (vs a hold)
//    kidodi_esc_dir        string  default up    up|down|left|right (swipe mode)
//  This only RECOGNISES the trigger; the PIN pad + unlock live in PlayerActivity.
// ============================================================================

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;

class AdminGesture implements View.OnTouchListener {
    interface OnTrigger { void run(); }

    private final Context ctx;
    private final OnTrigger cb;
    AdminGesture(Context c, OnTrigger cb) { this.ctx = c; this.cb = cb; }

    // config latched at the start of each press
    private int fingers, neededTaps; private long window, tapMs; private String mode, dir;
    // per-press tracking
    private int maxPointers; private long pressStart; private float downX, downY;
    // sliding window of recent valid multi-finger taps (like v1 — a stray touch won't reset it)
    private final ArrayDeque<Long> taps = new ArrayDeque<>();

    @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fingers = gi("kidodi_esc_fingers", 4);
                mode = gs("kidodi_esc_mode", "taps");
                dir = gs("kidodi_esc_dir", "none");
                neededTaps = gi("kidodi_esc_taps", 4);
                window = gi("kidodi_esc_window_ms", 3000);
                tapMs = gi("kidodi_esc_tap_ms", 800);
                pressStart = e.getEventTime(); maxPointers = 1; downX = e.getX(); downY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() > maxPointers) maxPointers = e.getPointerCount();
                break;
            case MotionEvent.ACTION_UP: {
                long dur = e.getEventTime() - pressStart;
                if ("swipe".equals(mode)) {
                    if (maxPointers >= fingers && swipeMatches(dir, e.getX() - downX, e.getY() - downY)) cb.run();
                } else if (maxPointers >= fingers && dur <= tapMs) {   // a quick multi-finger tap
                    long now = e.getEventTime();
                    taps.addLast(now);
                    while (!taps.isEmpty() && now - taps.peekFirst() > window) taps.removeFirst();
                    if (taps.size() >= neededTaps) { taps.clear(); cb.run(); }
                }
                break;
            }
        }
        return true;   // consume: the kiosk player has no other touch targets
    }

    private boolean swipeMatches(String d, float dx, float dy) {
        float t = dp(80);
        switch (d) {
            case "none":  return Math.abs(dx) > t || Math.abs(dy) > t;   // any direction
            case "down":  return dy >  t && Math.abs(dy) > Math.abs(dx);
            case "left":  return dx < -t && Math.abs(dx) > Math.abs(dy);
            case "right": return dx >  t && Math.abs(dx) > Math.abs(dy);
            default:      return dy < -t && Math.abs(dy) > Math.abs(dx);   // up
        }
    }
    // on-device Settings screen (prefs) first, adb Settings.Global as the dev fallback
    private int gi(String k, int d) { return Cfg.getInt(ctx, k, d); }
    private String gs(String k, String d) { return Cfg.getStr(ctx, k, d); }
    private int dp(int d) { return Math.round(d * ctx.getResources().getDisplayMetrics().density); }
}
