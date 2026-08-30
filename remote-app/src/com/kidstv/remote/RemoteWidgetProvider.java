package com.kidstv.remote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home-screen widget: an always-on remote for the tablet. A header (now-playing status + a
 * hotspot button + a nap/wake power button), a volume −/+ row, and a live scrollable grid of the
 * tablet's playlists (served by {@link WidgetPlaylistService}). Every button relays to Kodi with
 * the SAME calls the app uses — playlist play/shuffle over 9090, volume via Application.SetVolume,
 * and power routed to the kiosk's 8080 nap/wake bridge (a real device off, not a bare Player.Stop).
 *
 * All network work runs off the main thread: onReceive uses goAsync() + a worker, and the grid's
 * fetch happens in the RemoteViewsFactory. The widget shares the app's "remote" prefs, so it reuses
 * the last-known tablet IP (instant) and the manual IP/vol-step overrides.
 */
public class RemoteWidgetProvider extends AppWidgetProvider {

    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, BTN = 0xFF35516E, ACCENT = 0xFF2F6FEB;
    static final int TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6, GOOD = 0xFF33C05B, WARN = 0xFFE0653D;
    static final int[] TILE_COLORS = {
        0xFF4E86E0, 0xFF2FB3A2, 0xFFE0673F, 0xFF9B72E0, 0xFF3FBB63, 0xFFE0A63D, 0xFFE05D8C, 0xFF56A6E6
    };
    static final String PLAYLIST_DIR = "special://profile/playlists/video/";
    static final String PREFS = "remote";

    static final String ACT_POWER   = "com.kidstv.remote.widget.POWER";
    static final String ACT_VOLUP   = "com.kidstv.remote.widget.VOLUP";
    static final String ACT_VOLDOWN = "com.kidstv.remote.widget.VOLDOWN";
    static final String ACT_PLAY    = "com.kidstv.remote.widget.PLAY";

    // ---------------- lifecycle ----------------

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        // Synchronous chrome so the buttons + grid adapter are live immediately; the network state
        // refresh rides the same APPWIDGET_UPDATE broadcast via goAsync() in onReceive().
        for (int id : ids) mgr.updateAppWidget(id, buildChrome(ctx, id));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);   // dispatches onUpdate/onDeleted/etc. for framework actions
        final String a = intent.getAction();
        boolean ours = ACT_POWER.equals(a) || ACT_VOLUP.equals(a) || ACT_VOLDOWN.equals(a)
                || ACT_PLAY.equals(a) || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(a);
        if (!ours) return;
        final PendingResult pr = goAsync();     // keep the process alive across the worker thread
        final String file = intent.getStringExtra("file"), label = intent.getStringExtra("label");
        new Thread(() -> {
            try {
                if (ACT_POWER.equals(a))        doPower(ctx);
                else if (ACT_VOLUP.equals(a))   doVol(ctx, +1);
                else if (ACT_VOLDOWN.equals(a)) doVol(ctx, -1);
                else if (ACT_PLAY.equals(a))    doPlay(ctx, file, label);
                refreshState(ctx);              // pull now-playing / volume / connection, push header
            } catch (Exception e) {
                // best-effort widget; never crash the host
            } finally {
                try { pr.finish(); } catch (Exception e) {}
            }
        }).start();
    }

    // ---------------- chrome (static RemoteViews: adapter + click intents + cheap state) ----------------

    static RemoteViews buildChrome(Context ctx, int id) {
        String pkg = ctx.getPackageName();
        RemoteViews rv = new RemoteViews(pkg, R.layout.widget_remote);

        // playlist grid -> RemoteViewsService (unique data per widget id so adapters don't alias)
        Intent svc = new Intent(ctx, WidgetPlaylistService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.w_grid, svc);
        rv.setEmptyView(R.id.w_grid, R.id.w_empty);

        int imm = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        int mut = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;

        // playlist tap: a mutable template the per-cell fill-in fills with {file,label}
        Intent play = new Intent(ctx, RemoteWidgetProvider.class).setAction(ACT_PLAY);
        rv.setPendingIntentTemplate(R.id.w_grid, PendingIntent.getBroadcast(ctx, 100, play, mut));

        rv.setOnClickPendingIntent(R.id.w_status, PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), imm));
        rv.setOnClickPendingIntent(R.id.w_wifi, PendingIntent.getActivity(ctx, 2, hotspotIntent(ctx), imm));
        rv.setOnClickPendingIntent(R.id.w_power,   cast(ctx, ACT_POWER, 3, imm));
        rv.setOnClickPendingIntent(R.id.w_voldown, cast(ctx, ACT_VOLDOWN, 4, imm));
        rv.setOnClickPendingIntent(R.id.w_volup,   cast(ctx, ACT_VOLUP, 5, imm));

        // cheap immediate state (no network): power/hotspot colours, neutral status/vol
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean nap = p.getBoolean("w_nap", false);
        boolean hot = new KodiClient().isHotspotOn();
        rv.setInt(R.id.w_power, "setColorFilter", nap ? WARN : GOOD);
        rv.setInt(R.id.w_wifi,  "setColorFilter", hot ? GOOD : WARN);
        rv.setTextViewText(R.id.w_status, "KidsTV");
        rv.setTextViewText(R.id.w_vol, "VOL");
        return rv;
    }

    static PendingIntent cast(Context ctx, String action, int req, int flags) {
        return PendingIntent.getBroadcast(ctx, req,
                new Intent(ctx, RemoteWidgetProvider.class).setAction(action), flags);
    }

    // ---------------- state refresh (network; partial update so the grid doesn't reload) ----------------

    static void refreshState(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, RemoteWidgetProvider.class));
        if (ids.length == 0) return;

        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        KodiClient k = client(ctx);
        boolean conn = k.findHost();
        if (conn) saveLast(ctx, k);
        boolean hot = k.isHotspotOn();
        boolean nap = p.getBoolean("w_nap", false);

        String status, vol = "VOL";
        if (!conn) {
            status = "Searching for tablet…";
        } else {
            status = "Ready";
            try {
                JSONArray ap = new JSONObject(k.rpc("Player.GetActivePlayers", null)).getJSONArray("result");
                if (ap.length() > 0) {
                    int pid = ap.getJSONObject(0).optInt("playerid", 1);
                    JSONObject it = new JSONObject(k.rpc("Player.GetItem",
                            "{\"playerid\":" + pid + ",\"properties\":[\"showtitle\",\"season\",\"episode\",\"title\"]}"))
                            .getJSONObject("result").getJSONObject("item");
                    status = nowLabel(it);
                }
            } catch (Exception e) {}
            try {
                int v = new JSONObject(k.rpc("Application.GetProperties", "{\"properties\":[\"volume\"]}"))
                        .getJSONObject("result").optInt("volume", -1);
                if (v >= 0) vol = v + "%";
            } catch (Exception e) {}
        }

        for (int id : ids) {
            RemoteViews pv = new RemoteViews(ctx.getPackageName(), R.layout.widget_remote);
            pv.setTextViewText(R.id.w_status, status);
            pv.setInt(R.id.w_power, "setColorFilter", nap ? WARN : GOOD);
            pv.setInt(R.id.w_wifi,  "setColorFilter", hot ? GOOD : WARN);
            pv.setTextViewText(R.id.w_vol, vol);
            mgr.partiallyUpdateAppWidget(id, pv);
        }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_grid);
    }

    /** Compact now-playing label for the header: "Show S1E2" or the item title. */
    static String nowLabel(JSONObject it) {
        String show = it.optString("showtitle", "");
        int s = it.optInt("season", -1), e = it.optInt("episode", -1);
        String t = it.optString("title", "");
        if (t.length() == 0 || t.equals("null")) t = it.optString("label", "");
        StringBuilder sb = new StringBuilder();
        if (show.length() > 0 && !show.equals("null")) sb.append(show);
        if (s > 0 && e > 0) sb.append(sb.length() > 0 ? " " : "").append(String.format("S%dE%02d", s, e));
        if (sb.length() == 0 && t.length() > 0 && !t.equals("null")) sb.append(t);
        return sb.length() > 0 ? sb.toString() : "Playing";
    }

    // ---------------- actions (background) ----------------

    static void doPower(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean toNap = !p.getBoolean("w_nap", false);
        KodiClient k = client(ctx);
        String m = toNap ? "System.Shutdown" : "System.Reboot";   // bridge intercepts -> nap / wake
        if (k.bridge("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + m + "\"}")) {
            saveLast(ctx, k);
            p.edit().putBoolean("w_nap", toNap).apply();
        }
    }

    static void doVol(Context ctx, int dir) {
        KodiClient k = client(ctx);
        if (!k.findHost()) return;
        saveLast(ctx, k);
        int cur = 50;
        try {
            cur = new JSONObject(k.rpc("Application.GetProperties", "{\"properties\":[\"volume\"]}"))
                    .getJSONObject("result").optInt("volume", 50);
        } catch (Exception e) {}
        int step = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("vol_step", 8);
        int next = Math.max(0, Math.min(100, cur + dir * step));
        k.rpc("Application.SetVolume", "{\"volume\":" + next + "}");
    }

    static void doPlay(Context ctx, String file, String label) {
        if (file == null || file.length() == 0) return;
        KodiClient k = client(ctx);
        if (!k.findHost()) return;
        saveLast(ctx, k);
        String l = label == null ? "" : label.toLowerCase();
        boolean shuffle = l.contains("shuffle") || l.contains("all") || file.toLowerCase().contains("shuffleall");
        k.rpc("Playlist.Clear", "{\"playlistid\":1}");
        k.rpc("Playlist.Add", "{\"playlistid\":1,\"item\":{\"directory\":\"" + jesc(file) + "\"}}");
        k.rpc("Player.Open", "{\"item\":{\"playlistid\":1}}");
        try { Thread.sleep(700); } catch (Exception e) {}
        k.rpc("Player.SetShuffle", "{\"playerid\":1,\"shuffle\":" + shuffle + "}");
        k.rpc("Player.SetRepeat", "{\"playerid\":1,\"repeat\":\"all\"}");
    }

    // ---------------- shared helpers ----------------

    /** A KodiClient seeded from the app's prefs: last good IP (fast path) + manual overrides. */
    static KodiClient client(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        KodiClient k = new KodiClient();
        k.setPinned(p.getString("wifi", ""), p.getString("bt", ""));
        k.setLast(p.getString("lastip", ""));
        return k;
    }

    static void saveLast(Context ctx, KodiClient k) {
        if (k.activeHost() != null)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lastip", k.activeHost()).apply();
    }

    /** Deep-link the hotspot toggle (no sideloaded app can flip it) — TetherSettings, else Wireless. */
    static Intent hotspotIntent(Context ctx) {
        Intent i = new Intent();
        i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(ctx.getPackageManager()) != null) return i;
        Intent w = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return w;
    }

    static int tileColorFor(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return TILE_COLORS[Math.abs(h) % TILE_COLORS.length];
    }

    /** A round colour chip with the playlist's initial, drawn to a Bitmap (RemoteViews can't host
        the app's custom avatar View). */
    static Bitmap avatarBitmap(Context ctx, String label, int color) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int px = Math.round(42 * dm.density);
        Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint pc = new Paint(Paint.ANTI_ALIAS_FLAG);
        pc.setColor(color);
        cv.drawCircle(px / 2f, px / 2f, px / 2f, pc);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(TXT);
        pt.setTextAlign(Paint.Align.CENTER);
        pt.setFakeBoldText(true);
        pt.setTextSize(px * 0.46f);
        String s = (label == null || label.trim().isEmpty()) ? "?" : label.trim().substring(0, 1).toUpperCase();
        Paint.FontMetrics fm = pt.getFontMetrics();
        cv.drawText(s, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, pt);
        return bm;
    }

    static String jesc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
