package com.kidstv.kidodi;

// ============================================================================
//  The Android Auto screen (Car App template). Shows now-playing + a status/
//  countdown line (runtime / sleep timer) and controls (Play/Pause · Next · Light
//  in the action strip; Sleep · Power as pane actions). Drives the tablet via
//  RemoteService (BLE primary, Wi-Fi fallback). Not a media app -> no media-slot
//  competition with Spotify/Samsung Music.
// ============================================================================

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import org.json.JSONObject;

public class KidodiScreen extends Screen {
    private String show = "Kidodi", ep = "", status = "connecting…";
    private boolean playing = false;

    KidodiScreen(CarContext ctx) {
        super(ctx);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(new RemoteService.Listener() {
            public void onStatus(String s) {}
            public void onState(String json) { parse(json); getCarContext().getMainExecutor().execute(KidodiScreen.this::invalidate); }
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

    @NonNull @Override public Template onGetTemplate() {
        Pane pane = new Pane.Builder()
                .addRow(new Row.Builder().setTitle(show).addText(ep.isEmpty() ? " " : ep).build())
                .addRow(new Row.Builder().setTitle(status).build())
                .addAction(new Action.Builder().setTitle("Sleep 15m")
                        .setOnClickListener(() -> { send("sleep:15"); invalidate(); }).build())
                .addAction(new Action.Builder().setTitle("Power")
                        .setOnClickListener(() -> { send("power"); invalidate(); }).build())
                .build();

        return new PaneTemplate.Builder(pane)
                .setTitle("Kidodi")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder().setTitle(playing ? "Pause" : "Play")
                                .setOnClickListener(() -> { send("playpause"); invalidate(); }).build())
                        .addAction(new Action.Builder().setTitle("Next")
                                .setOnClickListener(() -> { send("next"); invalidate(); }).build())
                        .addAction(new Action.Builder().setTitle("Light")
                                .setOnClickListener(() -> { send("flash"); invalidate(); }).build())
                        .build())
                .build();
    }
}
