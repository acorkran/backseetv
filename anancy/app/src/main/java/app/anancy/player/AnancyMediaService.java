package app.anancy.player;

import app.anancy.R;
import app.anancy.remote.RemoteService;

// ============================================================================
//  Anancy Android Auto MEDIA app — the surface the car actually shows (the IOT
//  template path never appeared; the bake-off proved media is it). This is a
//  REMOTE-CONTROL media app: it makes NO audio itself. It mirrors the tablet's
//  now-playing state onto the dash and forwards controls, all over the existing
//  RemoteService (BLE-primary / Wi-Fi fallback) — the same link the phone remote
//  uses. Nap-safe by construction (no audio here; the tablet stays muted).
//
//  DASH: title/subtitle = show/episode, a live progress bar, prev/play-pause/
//  next, 3 custom actions (Light · Vol − · Vol +) and the play queue behind the
//  now-playing screen's own Queue button, where Samsung Music puts it. Album
//  art is a show placeholder, or the child-camera still during a Check-in.
//
//  BROWSE (the ← from now-playing): 2 tabs.
//    Options    Sleep timer >   Cancel, then 5..60 min in 5s
//               Toddler mode >  Turn on / Turn off
//               Check-in        (acts on tap, no chevron)
//               Power off       (acts on tap, no chevron)
//    Playlists  one row per playlist
//  The queue deliberately has NO tab: the Queue button already shows it, and
//  a tab of the same list was a duplicate. Android Auto draws the chevron from
//  the flag alone -- BROWSABLE rows get one and open a sub-list, PLAYABLE rows
//  don't and fire onPlayFromMediaId -- so a settings menu is just those two.
//
//  mediaId scheme:  pl:<name>  jump:<absIndex>  act:<cmd>
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

public class AnancyMediaService extends MediaBrowserServiceCompat {
    static final String ROOT = "root", ROOT_APP_OPTIONS = "tab_options", TAB_PL = "tab_playlists",
            OPT_SLEEP = "opt_sleep", OPT_TODDLER = "opt_toddler";
    static final long CHECKIN_MS = 60_000;     // a Check-in shows the camera for this long, then stops it
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
        // Hold the BLE/Wi-Fi link while AA is up. Guarded: if AA connects while the app is backgrounded,
        // A12+ can refuse the foreground-service start (ForegroundServiceStartNotAllowedException) — that
        // must not crash the media service (AA browsing still works; the link comes up on next foreground).
        try { RemoteService.acquire(this, "aa"); }
        catch (Exception e) { android.util.Log.w("Anancy", "AA link acquire deferred (FGS background-start): " + e); }
        session = new MediaSessionCompat(this, "Anancy");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(callback);
        setSessionToken(session.getSessionToken());
        // NOT setActive(true) here. Android Auto binds EVERY installed media app's browser service
        // just to enumerate them, so going active on bind made Anancy bid for the car's media focus
        // without the user ever choosing it -- it would surface over Samsung Music mid-drive and,
        // worse, FLAG_HANDLES_MEDIA_BUTTONS meant steering-wheel next/prev could skip the kid's show
        // instead of the music. We go active only when the user actually touches an Anancy control.
        publish();
        main.post(ticker);
        started = true;
    }

    // The camera runs for one of two reasons: the always-on opt-in setting (off by default -- never
    // turn the child camera on just because a car connected), or a Check-in, which shows it for a
    // minute and then turns it back off. Before Check-in existed the opt-in was the ONLY way, so a
    // default install showed album art forever and the camera looked broken.
    //
    // camble:on must land AFTER the BLE link connects -- sending it once races the connect and gets
    // dropped -- so it is re-sent from the ticker until a still newer than the request turns up.
    private int cambleTries = 0;
    private long checkInUntil = 0;              // elapsedRealtime; 0 = no check-in running
    private byte[] stillAtCamOn;                // the still cached when the camera was asked for (stale)
    private boolean camOn = false;
    private boolean cameraWanted() {
        return app.anancy.Cfg.getInt(this, "anancy_aa_camera", 0) == 1
                || android.os.SystemClock.elapsedRealtime() < checkInUntil;
    }
    private void ensureCamera() {
        if (!cameraWanted()) {
            if (camOn) { camOn = false; checkInUntil = 0; send("camble:off"); art = null; lastShow = ""; }   // back to the placeholder
            return;
        }
        if (!camOn) { camOn = true; cambleTries = 0; stillAtCamOn = RemoteService.latestStill(); }
        byte[] st = RemoteService.latestStill();
        if (st != null && st != stillAtCamOn) { cambleTries = 0; return; }   // fresh stills flowing -> done
        if (cambleTries++ % 3 == 0) send("camble:on");   // retry ~every 3 ticks until they do
    }
    private void checkIn() {
        checkInUntil = android.os.SystemClock.elapsedRealtime() + CHECKIN_MS;
        ensureCamera();
    }

    /** The user touched an Anancy control in the car: NOW we are the session they mean. */
    private void activate() {
        if (session != null && !session.isActive()) { session.setActive(true); lastSt = -1; }
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {
        @Override public void onPlay()  { activate(); send("playpause"); }
        @Override public void onPause() { activate(); send("playpause"); }
        @Override public void onStop()  { activate(); send("pause"); }
        @Override public void onSkipToNext()     { activate(); send("next"); }
        @Override public void onSkipToPrevious() { activate(); send("prev"); }
        @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { activate(); play(mediaId); }
        @Override public void onCustomAction(String action, Bundle extras) { activate(); send(action); }   // action string == command (flash, volup, voldown)
        @Override public void onSkipToQueueItem(long id) { activate(); send("jump:" + id); }   // queue item id == absolute index
    };

    /** A browse item was tapped, or a custom "act:" fired. */
    private void play(String mediaId) {
        if (mediaId == null) return;
        if (mediaId.startsWith("pl:"))        send("play:" + mediaId.substring(3));
        else if (mediaId.startsWith("jump:")) send(mediaId);                             // jump:<absIndex>
        else if (mediaId.equals("act:checkin")) checkIn();                               // camera, for a minute
        else if (mediaId.startsWith("act:"))  send(mediaId.substring(4));               // an Options action
        session.setActive(true);
        main.postDelayed(this::publish, 250);   // reflect the change on the now-playing screen
    }

    // --- now-playing: mirror the tablet's state, refresh art on new stills -----------------------
    private final Runnable ticker = new Runnable() { public void run() {
        ensureCamera();
        publish();
        syncQueue();
        syncPlaylists();
        main.postDelayed(this, TICK_MS);
    }};

    private void publish() {
        JSONObject o = state();
        String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
        int i = t.indexOf(".S");
        String show = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
        String ep = (i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim();
        if (show.isEmpty()) show = "BackSeeTV";
        long dur = o.optLong("duration_ms", 0), pos = o.optLong("position_ms", 0);
        boolean playing = o.optBoolean("playing", false) && !o.optBoolean("napped", false);

        // SLEEP TIMER: when engaged, the progress bar BECOMES the sleep countdown (user's request) and
        // the subtitle shows "Sleeping in MM:SS", so the dash clearly indicates it's armed.
        boolean sleeping = o.optBoolean("sleeping", false);
        long sleepRem = Math.max(0, o.optLong("sleep_remaining_ms", 0));
        if (sleeping) { if (sleepTotalMs <= 0 || sleepRem > sleepTotalMs) sleepTotalMs = sleepRem; }
        else sleepTotalMs = 0;
        long barDur = sleeping && sleepTotalMs > 0 ? sleepTotalMs : (dur > 0 ? dur : 1);
        long barPos = sleeping && sleepTotalMs > 0 ? (sleepTotalMs - sleepRem) : pos;
        // Static subtitle label (no per-second text -> no album-art re-push); the LIVE countdown is the
        // progress bar itself (barPos climbs toward barDur = the sleep total, with AA's time readout).
        String sub = sleeping ? "💤 Sleep timer running — see bar" + (ep.isEmpty() ? "" : "  ·  " + ep) : ep;
        if (android.os.SystemClock.elapsedRealtime() < checkInUntil) sub = "📷 Check-in — camera on for a minute";

        boolean artChanged = refreshArt(show);
        if (!show.equals(lastShow) || !sub.equals(lastEp) || barDur != lastDur || artChanged) {
            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "now")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, show)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, show)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, sub)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, barDur);
            if (art != null) {
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
            }
            session.setMetadata(md.build());
            if (!show.equals(lastShow)) {                                   // playlist/queue advanced
                RemoteService svc = RemoteService.get(); if (svc != null) svc.requestQueue();
            }
            lastShow = show; lastEp = sub; lastDur = barDur;
        }

        // Never claim to be PLAYING unless the user actually chose us: an inactive session that keeps
        // announcing playback is what let the kid's show shoulder its way onto the car's now-playing.
        int st = (session.isActive() && (playing || sleeping))
                ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        // Re-publishing an UNCHANGED state every second re-bumps this session up Android's
        // most-recently-active ordering, which is the other half of the same bug. Publish on real
        // change only; controllers extrapolate the progress bar from position + speed, so the bar
        // still moves. A 10s keepalive covers controllers that expect to be fed.
        long nowMs = android.os.SystemClock.elapsedRealtime();
        long expect = lastPubPos + (lastSt == PlaybackStateCompat.STATE_PLAYING ? nowMs - lastPubWall : 0);
        boolean changed = st != lastSt || sleeping != lastSleeping
                || Math.abs(barPos - expect) > 2500        // a seek, or the episode rolled over
                || o.optInt("index", -1) != lastIndex
                || nowMs - lastPubWall > 10000;
        if (!changed) return;
        lastSt = st; lastSleeping = sleeping; lastPubPos = barPos; lastPubWall = nowMs; lastIndex = o.optInt("index", -1);
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM)
                .setState(st, barPos, (playing || sleeping) ? 1f : 0f)
                .setActiveQueueItemId(o.optInt("index", -1));
        // Three quick actions: Light · Vol − · Vol +. Volume earns the front because it is the one
        // control you reach for mid-drive; sleep and power moved to Options, a tap further away.
        // Volume is the TV's own player volume, same as the phone remote's Vol buttons.
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("flash", "Light", R.drawable.ic_aa_light).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("voldown", "Volume down", R.drawable.ic_aa_vol_down).build());
        b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("volup", "Volume up", R.drawable.ic_aa_vol_up).build());
        session.setPlaybackState(b.build());
    }

    // --- the now-playing screen's queue button ----------------------------------------------------
    // Android Auto draws a queue button on the playback screen as soon as the session HAS a queue,
    // which is where Samsung Music's lives. The item id is the absolute play-order index, so tapping
    // one is a plain jump.
    private String lastQueueJson = null;
    private int queueTries = 0;
    private void syncQueue() {
        RemoteService svc = RemoteService.get(); if (svc == null) return;
        String q = svc.queueJson();
        if (q == null) { if (queueTries++ % 5 == 0) svc.requestQueue(); return; }   // link may not be up yet
        if (q.equals(lastQueueJson)) return;
        lastQueueJson = q;
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(q);
            int from = o.optInt("from", 0);
            JSONArray a = o.optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                        .setMediaId("jump:" + (from + i)).setTitle(clean(a.optString(i))).build();
                items.add(new MediaSessionCompat.QueueItem(d, from + i));
            }
        } catch (Exception ignored) {}
        session.setQueue(items);
        session.setQueueTitle("Up next");
    }

    // Opening Playlists before the link to the TV is up (Android Auto starts us on connect, so
    // that is the first few seconds of every drive) gave up after ~5s with an EMPTY page, and the
    // page stayed empty: nothing told the head unit to ask again. So keep asking, and when the
    // names land, notifyChildrenChanged makes the head unit reload the page it is showing.
    private boolean playlistsOwed = false;
    private int playlistTries = 0;
    private void syncPlaylists() {
        if (!playlistsOwed) return;
        RemoteService svc = RemoteService.get(); if (svc == null) return;
        if (!svc.playlistNames().isEmpty()) { playlistsOwed = false; notifyChildrenChanged(TAB_PL); return; }
        if (playlistTries++ % 3 == 0) svc.requestPlaylists();
    }

    private int lastSt = -1;                 // last published playback state, -1 = never
    private int lastIndex = -1;
    private boolean lastSleeping = false;
    private long lastPubPos = 0, lastPubWall = 0;
    private long sleepTotalMs = 0;
    private static String mmss(long ms) { long s = ms / 1000; return (s / 60) + ":" + String.format("%02d", s % 60); }

    /** True if the art bitmap changed (new still arrived, or first placeholder for a new show).
        Stills only count while the camera is wanted, and never the stale one cached from before it
        was asked for -- otherwise a Check-in would open on a minutes-old picture. */
    private boolean showingStill = false;
    private boolean refreshArt(String show) {
        byte[] still = cameraWanted() ? RemoteService.latestStill() : null;
        if (still != null && still != lastStillRef && still != stillAtCamOn) {
            Bitmap bmp = BitmapFactory.decodeByteArray(still, 0, still.length);
            if (bmp != null) { art = scale(bmp, 480); lastStillRef = still; showingStill = true; return true; }
        }
        if (art == null || (!showingStill && !show.equals(lastShow))) {
            art = placeholder(show); showingStill = false; return true;
        }
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
        c.drawText("BackSeeTV", S / 2f, S - 44, p);
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
        // Drilling INTO a tab means a human opened Anancy in the car, so the dash is theirs to have.
        // The root itself is deliberately excluded: head units prefetch it during enumeration, which
        // is the same "active without being chosen" trap that put us over the top of Samsung Music.
        if (!ROOT.equals(parentId)) activate();
        if (ROOT.equals(parentId)) { result.sendResult(rootTabs()); return; }
        RemoteService svc = RemoteService.get();
        if (ROOT_APP_OPTIONS.equals(parentId)) { result.sendResult(options()); return; }
        if (OPT_SLEEP.equals(parentId))        { result.sendResult(sleepOptions()); return; }
        if (OPT_TODDLER.equals(parentId))      { result.sendResult(toddlerOptions()); return; }
        if (svc == null) { result.sendResult(new ArrayList<>()); return; }

        if (TAB_PL.equals(parentId)) {
            svc.requestPlaylists();
            loadAsync(result, () -> !svc.playlistNames().isEmpty(), () -> {
                List<MediaItem> l = playlists();
                if (l.isEmpty()) { playlistsOwed = true; playlistTries = 1; }   // the ticker fetches + refreshes
                return l;
            }, 0);
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
        // Rows, not tiles: a tile with no artwork renders as a broken-image warning triangle on the
        // dash, and Samsung Music lists its playlists as rows anyway.
        out.add(browsable(ROOT_APP_OPTIONS, "Options", null, style(LIST, LIST)));
        out.add(browsable(TAB_PL, "Playlists", null, style(0, LIST)));
        return out;
    }
    /** Two rows that open a sub-list (chevron) and two that act on tap (no chevron). The subtitles
        are read when the page loads, so they say what the TV is doing right now. */
    private List<MediaItem> options() {
        JSONObject o = state();
        List<MediaItem> out = new ArrayList<>();
        boolean sleeping = o.optBoolean("sleeping", false);
        long rem = Math.max(0, o.optLong("sleep_remaining_ms", 0));
        out.add(browsable(OPT_SLEEP, "Sleep timer",
                sleeping ? "Running · " + mmss(rem) + " left" : "Off", style(0, LIST)));
        out.add(browsable(OPT_TODDLER, "Toddler mode",
                o.optBoolean("playmode", false) ? "On" : "Off", style(0, LIST)));
        out.add(playable("act:checkin", "Check-in", "Show the camera for a minute", null));
        out.add(playable("act:power", "Power off", "Turns the TV screen off or back on", null));
        return out;
    }
    private List<MediaItem> playlists() {
        List<MediaItem> out = new ArrayList<>();
        RemoteService svc = RemoteService.get(); if (svc == null) return out;
        for (String n : svc.playlistNames()) out.add(playable("pl:" + n, n, null, null));
        return out;
    }
    private List<MediaItem> sleepOptions() {
        List<MediaItem> out = new ArrayList<>();
        out.add(playable("act:wake", "Cancel sleep timer", null, null));
        for (int m = 5; m <= 60; m += 5) out.add(playable("act:sleep:" + m, m + " minutes", null, null));
        return out;
    }
    private List<MediaItem> toddlerOptions() {
        List<MediaItem> out = new ArrayList<>();
        out.add(playable("act:playmode:on", "Turn on", null, null));
        out.add(playable("act:playmode:off", "Turn off", null, null));
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
