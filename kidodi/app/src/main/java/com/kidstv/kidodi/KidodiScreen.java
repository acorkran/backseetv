package com.kidstv.kidodi;

// ============================================================================
//  The Android Auto screen (Car App template). Shows now-playing + a status/
//  countdown line (runtime / sleep timer) + a periodically-updated STILL IMAGE
//  from the child camera (a slow ~0.5fps still refresh, NOT a video feed — AA
//  driver-distraction rules), and controls (Play/Pause · Next · Light in the
//  action strip; Sleep · Power as pane actions). Drives the tablet via
//  RemoteService (BLE primary, Wi-Fi fallback). Not a media app -> no media-slot
//  competition with Spotify/Samsung Music.
// ============================================================================

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONObject;

public class KidodiScreen extends Screen {
    private String show = "Kidodi", ep = "", status = "connecting…";
    private boolean playing = false;

    // Slow still-refresh: re-pull the latest camera STILL onto the dash ~every 2s (~0.5fps).
    // This is deliberately slow — a refreshing still, never a video feed (AA distraction rules).
    private static final long STILL_REFRESH_MS = 2000;
    private final Handler refresh = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidate(); refresh.postDelayed(this, STILL_REFRESH_MS); }
    };

    KidodiScreen(CarContext ctx) {
        super(ctx);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(new RemoteService.Listener() {
            public void onStatus(String s) {}
            public void onState(String json) { parse(json); getCarContext().getMainExecutor().execute(KidodiScreen.this::invalidate); }
        });
        // run the still-refresh only while the screen is visible
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner o) { refresh.removeCallbacks(tick); refresh.postDelayed(tick, STILL_REFRESH_MS); }
            @Override public void onStop(@NonNull LifecycleOwner o) { refresh.removeCallbacks(tick); }
        });
    }

    private void parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            playing = o.optBoolean("playing", false);
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            show = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
            if (show.isEmpty()) show = "Kidodi";
            ep = (i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim();
            if (o.optBoolean("sleeping", false)) {
                long s = Math.max(0, o.optLong("sleep_remaining_ms", 0)) / 1000;
                status = "Sleep in " + (s / 60) + ":" + String.format("%02d", s % 60);
            } else if (o.optBoolean("napped", false)) status = "TV is off";
            else status = playing ? "Playing" : "Paused";
        } catch (Exception ignored) {}
    }

    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }

    /** The dash image: the latest camera STILL if one has arrived, else a neutral placeholder. */
    private CarIcon dashImage() {
        try {
            byte[] jpeg = RemoteService.latestStill();   // latest still (null until a consumer supplies one)
            if (jpeg != null && jpeg.length > 0) {
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bmp != null) {
                    int max = Math.max(bmp.getWidth(), bmp.getHeight());
                    if (max > 480) {   // keep within AA image-size limits
                        float s = 480f / max;
                        bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
                    }
                    return new CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build();
                }
            }
        } catch (Exception ignored) {}
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), android.R.drawable.ic_menu_gallery)).build();
    }

    private CarIcon icon(int res) { return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), res)).build(); }

    // HOST LIMITS (androidx.car.app 1.4.0, enforced at build time -> IllegalArgumentException -> the
    // app "doesn't load"): a PaneTemplate's ACTION STRIP takes max 2 actions with at most 1 text
    // title (ACTIONS_CONSTRAINTS_SIMPLE); the pane BODY takes max 2 actions (both may be titled);
    // Pane.setImage needs host API 4. So: strip = 2 icon actions, body = Light + More…, image gated.
    @NonNull @Override public Template onGetTemplate() {
        Pane.Builder pane = new Pane.Builder()
                .addRow(new Row.Builder().setTitle(show).addText(ep.isEmpty() ? " " : ep).build())
                .addRow(new Row.Builder().setTitle(status).build())
                .addAction(new Action.Builder().setTitle("Light")
                        .setOnClickListener(() -> { send("flash"); invalidate(); }).build())
                .addAction(new Action.Builder().setTitle("More…")
                        .setOnClickListener(() -> getScreenManager().push(new MoreScreen(getCarContext()))).build());
        // periodically-updated STILL image from the child camera (slow refresh, not video)
        if (getCarContext().getCarAppApiLevel() >= 4) pane.setImage(dashImage());

        return new PaneTemplate.Builder(pane.build())
                .setTitle("Kidodi")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder()
                                .setIcon(icon(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play))
                                .setOnClickListener(() -> { send("playpause"); invalidate(); }).build())
                        .addAction(new Action.Builder().setIcon(icon(android.R.drawable.ic_media_next))
                                .setOnClickListener(() -> { send("next"); invalidate(); }).build())
                        .build())
                .build();
    }
}
