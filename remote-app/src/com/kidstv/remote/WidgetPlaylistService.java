package com.kidstv.remote;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Backs the widget's playlist GridView. onDataSetChanged() (called on a worker thread by the
 * framework) pulls the tablet's real .xsp/.m3u playlists live over JSON-RPC; getViewAt() renders
 * each as a colour+initial tile whose tap carries {file,label} back to RemoteWidgetProvider.PLAY.
 */
public class WidgetPlaylistService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PlaylistFactory(getApplicationContext());
    }
}

class PlaylistFactory implements RemoteViewsService.RemoteViewsFactory {
    private final Context ctx;
    private volatile String[][] pls = new String[0][];   // {label, file}, shuffle-all first

    PlaylistFactory(Context ctx) { this.ctx = ctx; }

    @Override public void onCreate() {}
    @Override public void onDestroy() {}
    @Override public int getViewTypeCount() { return 1; }
    @Override public boolean hasStableIds() { return false; }
    @Override public long getItemId(int i) { return i; }
    @Override public RemoteViews getLoadingView() { return null; }
    @Override public int getCount() { return pls.length; }

    @Override
    public void onDataSetChanged() {
        KodiClient k = RemoteWidgetProvider.client(ctx);
        String r = k.rpc("Files.GetDirectory",
                "{\"directory\":\"" + RemoteWidgetProvider.PLAYLIST_DIR + "\",\"media\":\"video\"}");
        if (k.activeHost() != null) RemoteWidgetProvider.saveLast(ctx, k);
        if (r == null) { pls = new String[0][]; return; }
        try {
            JSONArray files = new JSONObject(r).getJSONObject("result").getJSONArray("files");
            ArrayList<String[]> rest = new ArrayList<>();
            String[] shuffle = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String file = f.optString("file", "");
                String label = f.optString("label", "");
                String low = file.toLowerCase();
                if (!((low.endsWith(".xsp") || low.endsWith(".m3u")) && label.length() > 0)) continue;
                String ll = label.toLowerCase();
                if (shuffle == null && (ll.contains("shuffle") || ll.contains("all show"))) shuffle = new String[]{label, file};
                else rest.add(new String[]{label, file});
            }
            ArrayList<String[]> out = new ArrayList<>();
            if (shuffle != null) out.add(shuffle);   // hoist the all-shows mix first, like the app
            out.addAll(rest);
            pls = out.toArray(new String[0][]);
        } catch (Exception e) {
            pls = new String[0][];
        }
    }

    @Override
    public RemoteViews getViewAt(int i) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_cell);
        if (i < 0 || i >= pls.length) return rv;
        String label = pls[i][0], file = pls[i][1];
        boolean shuffle = i == 0 && (label.toLowerCase().contains("shuffle") || label.toLowerCase().contains("all show"));
        int color = shuffle ? RemoteWidgetProvider.ACCENT : RemoteWidgetProvider.tileColorFor(label);
        rv.setImageViewBitmap(R.id.c_avatar, RemoteWidgetProvider.avatarBitmap(ctx, label, color));
        rv.setTextViewText(R.id.c_name, shuffle ? "Shuffle All" : label);
        Intent fill = new Intent();
        fill.putExtra("file", file);
        fill.putExtra("label", label);
        rv.setOnClickFillInIntent(R.id.c_root, fill);
        return rv;
    }
}
