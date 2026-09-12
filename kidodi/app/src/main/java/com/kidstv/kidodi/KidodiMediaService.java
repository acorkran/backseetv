package com.kidstv.kidodi;

// ============================================================================
//  Kidodi Android Auto MEDIA app — the surface the car actually shows (the IOT
//  template path never appeared; the bake-off proved media is it). This is a
//  REMOTE-CONTROL media app: it makes NO audio itself. It mirrors the tablet's
//  now-playing state onto the dash and forwards controls, all over the existing
//  RemoteService (BLE-primary / Wi-Fi fallback) — the same link the phone remote
//  uses. Nap-safe by construction (no audio here; the tablet stays muted).
//
//  DASH: album art = the child-camera still (camble stream, refreshes ~2s even
//  while driving on the Chery), title/subtitle = show/episode, a live progress
//  bar (position+duration), prev/play-pause/next, and 5 custom actions
//  (Light · Sleep · Shuffle · Mute · Power). BROWSE: 4 top tabs — Playlists,
//  Shows, Up next, Controls — with Samsung-Music-style grid/list content hints.
//
//  mediaId scheme:  pl:<name>  show:<name>  ep:<id>  jump:<absIndex>  act:<cmd>
// ============================================================================

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.utils.MediaConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class KidodiMediaService extends MediaBrowserServiceCompat {
    static final String ROOT = "root", TAB_PL = "tab_playlists", TAB_SHOWS = "tab_shows",
            TAB_QUEUE = "tab_queue", TAB_CTRL = "tab_controls";
    static final long TICK_MS = 1000;
    // no named constant for the root flag in media:1.7.0 -> raw key
    static final String KEY_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    static final int GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM;   // 2
    static final int LIST = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM;   // 1

    private MediaSessionCompat session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private byte[] lastStillRef;                 // identity of the last decoded still
    private Bitmap art;                           // current dash bitmap (still or placeholder)
    private String lastShow = "", lastEp = "";
    private long lastDur = -1;
    private boolean started = false;

    private void send(String cmd) { RemoteService s = RemoteService.get(); if (s != null) s.send(cmd); }
    private JSONObject state() {
        try { RemoteService s = RemoteService.get(); return new JSONObject(s != null ? s.state() : "{}"); }
        catch (Exception e) { return new JSONObject(); }
    }

    @Override public void onCreate() {
        super.onCreate();
        RemoteService.acquire(this, "aa");                 // hold the BLE/Wi-Fi link while AA is up
        session = new MediaSessionCompat(this, "Kidodi");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(callback);
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        send("camble:on");                                 // start the reduced-res camera still stream
        publish();
        main.post(ticker);
        started = true;
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {
        @Override public void onPlay()  { send("playpause"); }
        @Override public void onPause() { send("playpause"); }
        @Override public void onStop()  { send("pause"); }
        @Override public void onSkipToNext()     { send("next"); }
        @Override public void onSkipToPrevious() { send("prev"); }
        @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { play(mediaId); }
        @Override public void onCustomAction(String action, Bundle extras) { send(action); }   // action string == command
    };

    /** A browse item was tapped, or a custom "act:" fired. */
    private void play(String mediaId) {
        if (mediaId == null) return;
        if (mediaId.startsWith("pl:"))        send("play:" + mediaId.substring(3));
        else if (mediaId.startsWith("ep:"))   send("enqnow:" + mediaId.substring(3));   // play this episode next + jump
        else if (mediaId.startsWith("jump:")) send(mediaId);                             // jump:<absIndex>
        else if (mediaId.startsWith("act:"))  send(mediaId.substring(4));               // a Controls-tab action
        session.setActive(true);
        main.postDelayed(this::publish, 250);   // reflect the change on the now-playing screen
    }

    // --- now-playing: mirror the tablet's state, refresh art on new stills -----------------------
    private final Runnable ticker = new Runnable() { public void run() {
        publish();
        main.postDelayed(this, TICK_MS);
    }};

    private void publish() {
        JSONObject o = state();
        String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
        int i = t.indexOf(".S");
        String show = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
        String ep = (i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim();
        if (show.isEmpty()) show = "Kidodi";
        long dur = o.optLong("duration_ms", 0), pos = o.optLong("position_ms", 0);
        boolean playing = o.optBoolean("playing", false) && !o.optBoolean("napped", false);

        boolean artChanged = refreshArt(show);
        if (!show.equals(lastShow) || !ep.equals(lastEp) || dur != lastDur || artChanged) {
            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "now")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, show)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, show)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, ep)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ep)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur > 0 ? dur : 1);
            if (art != null) {
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
            }
            session.setMetadata(md.build());
            if (!show.equals(lastShow)) notifyChildrenChanged(TAB_QUEUE);   // playlist/queue advanced
            lastShow = show; lastEp = ep; lastDur = dur;
        }

        int st = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(st, pos, playing ? 1f : 0f);
        // 5 custom actions (first = most visible on the Chery): Light · Sleep · Shuffle · Mute · Power
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("flash", "Light", R.drawable.ic_aa_light).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("sleep:15", "Sleep 15m", R.drawable.ic_aa_sleep).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("shuffle:toggle", "Shuffle", R.drawable.ic_aa_shuffle).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("mute", "Mute", R.drawable.ic_aa_mute).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("power", "TV power", R.drawable.ic_aa_power).build());
        session.setPlaybackState(b.build());
    }

    /** True if the art bitmap changed (new still arrived, or first placeholder for a new show). */
    private boolean refreshArt(String show) {
        byte[] still = RemoteService.latestStill();
        if (still != null && still != lastStillRef) {
            Bitmap bmp = BitmapFactory.decodeByteArray(still, 0, still.length);
            if (bmp != null) { art = scale(bmp, 480); lastStillRef = still; return true; }
        }
        if (art == null || (still == null && !show.equals(lastShow))) { art = placeholder(show); return true; }
        return false;
    }
    private static Bitmap scale(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight(), m = Math.max(w, h);
        if (m <= max) return b;
        float s = (float) max / m;
        return Bitmap.createScaledBitmap(b, Math.round(w * s), Math.round(h * s), true);
    }
    private Bitmap placeholder(String show) {
        int S = 480;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp); c.drawColor(0xFF101418);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFF8FD694); p.setTextSize(52);
        c.drawText(ellipsize(show, 16), S / 2f, S / 2f + 18, p);
        p.setColor(0xFF6B7075); p.setTextSize(30);
        c.drawText("Kidodi", S / 2f, S - 44, p);
        return bmp;
    }
    private static String ellipsize(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    // --- browse tree -----------------------------------------------------------------------------
    @Override public BrowserRoot onGetRoot(@NonNull String pkg, int uid, @Nullable Bundle hints) {
        Bundle x = new Bundle();
        x.putBoolean(KEY_CONTENT_STYLE_SUPPORTED, true);
        x.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, GRID);   // browsable default: tiles
        x.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST);    // playable default: rows
        return new BrowserRoot(ROOT, x);
    }

    @Override public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        if (ROOT.equals(parentId)) { result.sendResult(rootTabs()); return; }
        RemoteService svc = RemoteService.get();
        if (TAB_CTRL.equals(parentId)) { result.sendResult(controls()); return; }
        if (svc == null) { result.sendResult(new ArrayList<>()); return; }

        if (TAB_PL.equals(parentId)) {
            svc.requestPlaylists();
            loadAsync(result, () -> !svc.playlistNames().isEmpty(), this::playlists, 0);
        } else if (TAB_SHOWS.equals(parentId)) {
            svc.requestShows(0);
            loadAsync(result, () -> svc.libraryJson() != null, this::shows, 0);
        } else if (parentId.startsWith("show:")) {
            final String name = parentId.substring(5);
            svc.requestEpisodes(name, 0);
            loadAsync(result, () -> episodesReady(name), () -> episodes(name), 0);
        } else if (TAB_QUEUE.equals(parentId)) {
            svc.requestQueue();
            loadAsync(result, () -> svc.queueJson() != null, this::queue, 0);
        } else {
            result.sendResult(new ArrayList<>());
        }
    }

    private interface Ready { boolean ok(); }
    private interface Build { List<MediaItem> get(); }
    /** Detach + poll the cache (the typed payload arrives async over BLE), then send whatever we have. */
    private void loadAsync(final Result<List<MediaItem>> result, final Ready ready, final Build build, final int attempt) {
        if (ready.ok()) { result.sendResult(build.get()); return; }
        if (attempt == 0) result.detach();
        if (attempt >= 12) { result.sendResult(build.get()); return; }   // ~5s max, then whatever's cached
        main.postDelayed(() -> loadAsync2(result, ready, build, attempt + 1), 400);
    }
    private void loadAsync2(Result<List<MediaItem>> result, Ready ready, Build build, int attempt) {
        if (ready.ok() || attempt >= 12) { result.sendResult(build.get()); return; }
        main.postDelayed(() -> loadAsync2(result, ready, build, attempt + 1), 400);
    }

    private List<MediaItem> rootTabs() {
        List<MediaItem> out = new ArrayList<>();
        out.add(browsable(TAB_PL, "Playlists", null, style(0, GRID)));      // playlist tiles
        out.add(browsable(TAB_SHOWS, "Shows", null, style(GRID, 0)));       // show tiles
        out.add(browsable(TAB_QUEUE, "Up next", null, style(0, LIST)));     // queue rows
        out.add(browsable(TAB_CTRL, "Controls", null, style(0, LIST)));     // control rows
        return out;
    }
    private List<MediaItem> playlists() {
        List<MediaItem> out = new ArrayList<>();
        RemoteService svc = RemoteService.get(); if (svc == null) return out;
        for (String n : svc.playlistNames()) out.add(playable("pl:" + n, n, null, null));
        return out;
    }
    private List<MediaItem> shows() {
        List<MediaItem> out = new ArrayList<>();
        RemoteService svc = RemoteService.get(); if (svc == null) return out;
        try {
            JSONArray a = new JSONObject(svc.libraryJson()).optJSONArray("shows");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                out.add(browsable("show:" + s.optString("name"), s.optString("name"), s.optInt("count") + " eps", style(0, LIST)));
            }
        } catch (Exception ignored) {}
        return out;
    }
    private boolean episodesReady(String name) {
        RemoteService svc = RemoteService.get(); if (svc == null) return false;
        try { return name.equals(new JSONObject(svc.episodesJson()).optString("show")); } catch (Exception e) { return false; }
    }
    private List<MediaItem> episodes(String name) {
        List<MediaItem> out = new ArrayList<>();
        RemoteService svc = RemoteService.get(); if (svc == null) return out;
        try {
            JSONObject o = new JSONObject(svc.episodesJson());
            if (!name.equals(o.optString("show"))) return out;
            JSONArray items = o.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.getJSONObject(i);
                out.add(playable("ep:" + e.optInt("id"), clean(e.optString("title")), null, null));
            }
        } catch (Exception ignored) {}
        return out;
    }
    private List<MediaItem> queue() {
        List<MediaItem> out = new ArrayList<>();
        RemoteService svc = RemoteService.get(); if (svc == null) return out;
        try {
            JSONObject o = new JSONObject(svc.queueJson());
            int from = o.optInt("from", 0), index = o.optInt("index", -1);
            JSONArray items = o.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                int abs = from + i;
                String title = (abs == index ? "▶ " : "") + clean(items.optString(i));
                out.add(playable("jump:" + abs, title, null, null));
            }
        } catch (Exception ignored) {}
        return out;
    }
    private List<MediaItem> controls() {
        List<MediaItem> out = new ArrayList<>();
        out.add(playable("act:sleep:15", "Sleep in 15 min", null, null));
        out.add(playable("act:sleep:30", "Sleep in 30 min", null, null));
        out.add(playable("act:wake", "Wake / cancel sleep", null, null));
        out.add(playable("act:flash", "Light", null, null));
        out.add(playable("act:power", "TV off / on", null, null));
        out.add(playable("act:shuffle:toggle", "Shuffle on / off", null, null));
        out.add(playable("act:loop:toggle", "Loop: forever / stop", null, null));
        out.add(playable("act:mute", "Mute / unmute", null, null));
        return out;
    }

    private static String clean(String t) {
        t = t.replaceAll("\\.(mp4|mkv|m4v)$", "");
        int i = t.indexOf(".S");
        String show = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
        String ep = (i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim();
        return ep.isEmpty() ? show : show + " · " + ep;
    }

    // content-style hint for a node's children: pass 0 to leave a dimension at the root default.
    private Bundle style(int browsableHint, int playableHint) {
        Bundle b = new Bundle();
        if (browsableHint > 0) b.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsableHint);
        if (playableHint > 0) b.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playableHint);
        return b;
    }
    private MediaItem browsable(String id, String title, String sub, Bundle extras) {
        MediaDescriptionCompat.Builder d = new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title);
        if (sub != null) d.setSubtitle(sub);
        if (extras != null) d.setExtras(extras);
        return new MediaItem(d.build(), MediaItem.FLAG_BROWSABLE);
    }
    private MediaItem playable(String id, String title, String sub, Bundle extras) {
        MediaDescriptionCompat.Builder d = new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title);
        if (sub != null) d.setSubtitle(sub);
        if (extras != null) d.setExtras(extras);
        return new MediaItem(d.build(), MediaItem.FLAG_PLAYABLE);
    }

    @Override public void onDestroy() {
        main.removeCallbacks(ticker);
        if (started) { send("camble:off"); RemoteService.release(this, "aa"); }
        try { session.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
