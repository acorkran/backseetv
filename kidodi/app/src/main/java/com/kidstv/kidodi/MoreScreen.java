package com.kidstv.kidodi;

// ============================================================================
//  Android Auto: the "More…" page behind the dash. The main PaneTemplate only
//  has room for 4 controls (2 icon actions + 2 body actions), so the sleep
//  timer, TV off/on and the playlist picker live here. Every row does its thing
//  and pops straight back to the dash.
// ============================================================================

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import org.json.JSONObject;

public class MoreScreen extends Screen {
    MoreScreen(CarContext ctx) { super(ctx); }

    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }
    private JSONObject state() {
        try { RemoteService s = RemoteService.get(); return new JSONObject(s != null ? s.state() : "{}"); }
        catch (Exception e) { return new JSONObject(); }
    }
    private Row row(String title, String text, Runnable r) {
        Row.Builder b = new Row.Builder().setTitle(title)
                .setOnClickListener(() -> { r.run(); getScreenManager().pop(); });
        if (text != null) b.addText(text);
        return b.build();
    }

    @NonNull @Override public Template onGetTemplate() {
        JSONObject st = state();
        boolean napped = st.optBoolean("napped", false), sleeping = st.optBoolean("sleeping", false);
        ItemList.Builder list = new ItemList.Builder();
        if (sleeping) {
            long s = Math.max(0, st.optLong("sleep_remaining_ms", 0)) / 1000;
            list.addItem(row("Cancel sleep timer", "TV sleeps in " + (s / 60) + ":" + String.format("%02d", s % 60), () -> send("wake")));
        } else {
            list.addItem(row("Sleep in 15 min", "fade out, then TV off", () -> send("sleep:15")));
            list.addItem(row("Sleep in 30 min", null, () -> send("sleep:30")));
        }
        list.addItem(row(napped ? "TV on" : "TV off",
                napped ? "wake the screen and resume" : "pause and screen off (nap)", () -> send("power")));
        list.addItem(new Row.Builder().setTitle("Playlists…").setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(new PlaylistsScreen(getCarContext()))).build());
        return new ListTemplate.Builder().setTitle("Kidodi").setHeaderAction(Action.BACK)
                .setSingleList(list.build()).build();
    }
}
