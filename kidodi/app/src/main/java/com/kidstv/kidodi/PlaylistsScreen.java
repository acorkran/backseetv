package com.kidstv.kidodi;

// ============================================================================
//  Android Auto: pick a playlist from the dash. Names come from the tablet
//  ("list" -> playlists payload, cached in RemoteService) and arrive async over
//  BLE, so this re-renders a few times until they're in. The host caps list
//  length (ConstraintManager); Shuffle All / Naptime are floated to the top so
//  they always make the cut.
// ============================================================================

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private int polls = 0;

    PlaylistsScreen(CarContext ctx) {
        super(ctx);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.requestPlaylists();
        main.postDelayed(this::poll, 700);
    }
    private void poll() { invalidate(); if (++polls < 6 && names().isEmpty()) main.postDelayed(this::poll, 700); }

    private List<String> names() {
        RemoteService s = RemoteService.get();
        List<String> raw = s != null ? s.playlistNames() : new ArrayList<>(), out = new ArrayList<>();
        for (String n : raw) if (n.toLowerCase().contains("shuffle") || n.toLowerCase().contains("nap")) out.add(n);
        for (String n : raw) if (!out.contains(n)) out.add(n);
        return out;
    }
    private int limit() {
        try {
            if (getCarContext().getCarAppApiLevel() >= 2)
                return getCarContext().getCarService(ConstraintManager.class).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        } catch (Exception ignored) {}
        return 6;
    }

    @NonNull @Override public Template onGetTemplate() {
        List<String> names = names();
        ItemList.Builder list = new ItemList.Builder();
        if (names.isEmpty()) list.setNoItemsMessage("Loading playlists…");
        int n = Math.min(names.size(), limit());
        for (int i = 0; i < n; i++) {
            final String name = names.get(i);
            list.addItem(new Row.Builder().setTitle(name).setOnClickListener(() -> {
                RemoteService s = RemoteService.get();
                if (s != null) s.send("play:" + name);
                getScreenManager().popToRoot();
            }).build());
        }
        return new ListTemplate.Builder().setTitle("Playlists").setHeaderAction(Action.BACK)
                .setSingleList(list.build()).build();
    }
}
