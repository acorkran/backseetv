package com.kidstv.aaremote;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.media.VolumeProviderCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Android Auto MEDIA surface. The head unit binds this MediaBrowserServiceCompat directly:
 * it renders the driving-safe browse list (the tablet's playlists) and the transport controls,
 * and we relay every action to Kodi on the car tablet over raw JSON-RPC (:9090). This app plays
 * NO audio itself - it is a remote control that proxies the tablet's Kodi.
 *
 *   Browse:     root children = the .xsp/.m3u smart playlists under
 *               special://profile/playlists/video/ (fetched live), "Shuffle All Shows" hoisted first.
 *   Select:     onPlayFromMediaId -> Playlist.Clear + Playlist.Add{directory} + Player.Open (the
 *               phone remote's playPlaylist, mirrored), shuffle for the all-shows mixes.
 *   Transport:  onPlay/onPause -> Player.PlayPause, onSkipToNext/Previous -> Player.GoTo,
 *               onStop -> Player.Stop.
 *   Volume:     the session declares REMOTE volume (setPlaybackToRemote) so the head unit's own
 *               volume rocker drives Kodi's Application.SetVolume on the tablet - INDEPENDENT of the
 *               car's audio volume (this app plays no audio of its own).
 *   Power:      a custom action ("Turn off / Wake tablet") posts System.Shutdown/Reboot to the
 *               kiosk's 8080 bridge, which naps (stop + screen off + dpm.lockNow) / wakes the tablet
 *               - a real, STICKY power-off the self-heal addon won't undo (a bare Player.Stop would).
 *   State:      a 1 Hz poll of Player.GetActivePlayers/GetItem/GetProperties publishes
 *               PlaybackStateCompat + MediaMetadataCompat so the head unit shows the right state.
 */
public class KodiMediaService extends MediaBrowserServiceCompat {

    static final String ROOT_ID = "root";
    static final String NOOP_PREFIX = "noop:";                 // placeholder items that do nothing
    static final String PLAYLIST_DIR = "special://profile/playlists/video/";
    static final int VIDEO_PLAYER = 1;                         // Kodi's video playlist/player id
    static final int VOL_STEP = 5;                             // Kodi volume step per rocker press (0-100)
    static final String ACT_POWER = "com.kidstv.aaremote.POWER";   // custom-action id: nap/wake toggle
    // 8080-bridge payloads the kiosk intercepts: Shutdown -> nap (off), Reboot -> wake (on).
    static final String NAP_JSON  = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"System.Shutdown\"}";
    static final String WAKE_JSON = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"System.Reboot\"}";

    private final KodiClient kodi = new KodiClient();
    private MediaSessionCompat session;
    private KodiVolume volume;                                 // remote VolumeProvider -> Kodi volume
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private SharedPreferences prefs;

    // Live cache of the tablet's playlists as {label, file}; null until first successful fetch.
    private volatile String[][] playlists = null;
    private volatile boolean lastConnected = false;            // edge-detect connect to refresh browse
    private volatile boolean napped = false;                   // last power-toggle direction (label swap)
    private volatile long lastVolTouch = 0;                    // suppress poll clobbering a fresh user vol change

    private static final long ACTIONS =
            PlaybackStateCompat.ACTION_PLAY
          | PlaybackStateCompat.ACTION_PAUSE
          | PlaybackStateCompat.ACTION_PLAY_PAUSE
          | PlaybackStateCompat.ACTION_STOP
          | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
          | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
          | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("aaremote", MODE_PRIVATE);
        kodi.setPinned(prefs.getString("wifi", ""), prefs.getString("bt", ""));  // optional overrides
        kodi.setLast(prefs.getString("lastip", ""));                             // fast-path cache

        session = new MediaSessionCompat(this, "KodiMediaService");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new TransportCallback());

        // Tapping the media card opens our status screen.
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, piFlags));

        // Declare REMOTE volume: the head unit's volume rocker now drives Kodi's volume on the tablet
        // (independent of the car's own audio) instead of a local stream this app doesn't even play.
        volume = new KodiVolume();
        session.setPlaybackToRemote(volume);

        setSessionToken(session.getSessionToken());
        publishState(PlaybackStateCompat.STATE_CONNECTING, 0, "Connecting to tablet…", null, 0);
        session.setActive(true);

        main.post(pollTask);   // start the 1 Hz state poll
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(pollTask);
        io.shutdownNow();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    // ---------------- browse tree ----------------

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        // Personal appliance: allow any caller (Android Auto, the local UI) to browse.
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) { result.sendResult(new ArrayList<>()); return; }
        result.detach();   // fetch off the main thread, then sendResult
        io.execute(() -> result.sendResult(buildRootItems()));
    }

    /** Root children: one playable MediaItem per playlist, "Shuffle All Shows" hoisted to the top.
        Not connected yet -> a single non-actionable "Searching…" item; the poll loop refreshes
        the browse list (notifyChildrenChanged) the moment the tablet is found. */
    private List<MediaItem> buildRootItems() {
        String[][] pls = playlists;
        if (pls == null) {                       // never fetched yet - try now
            if (!kodi.connected()) kodi.findHost();
            if (kodi.connected()) pls = fetchPlaylists();
        }
        List<MediaItem> items = new ArrayList<>();
        if (pls == null || pls.length == 0) {
            items.add(placeholder(kodi.connected() ? "No playlists on the tablet"
                                                   : "Searching for tablet…"));
            return items;
        }
        // Hoist the all-shows shuffle as the first tile; the rest follow in device order.
        String[] shuffle = null;
        List<String[]> rest = new ArrayList<>();
        for (String[] pl : pls) {
            String l = pl[0].toLowerCase();
            if (shuffle == null && (l.contains("shuffle") || l.contains("all show"))) shuffle = pl;
            else rest.add(pl);
        }
        if (shuffle != null) items.add(playableItem("Shuffle All Shows", shuffle[1]));
        for (String[] pl : rest) items.add(playableItem(pl[0], pl[1]));
        return items;
    }

    private MediaItem playableItem(String title, String file) {
        MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                .setMediaId(file)          // the .xsp/.m3u path IS the id we replay on select
                .setTitle(title)
                .build();
        return new MediaItem(d, MediaItem.FLAG_PLAYABLE);
    }

    /** A do-nothing browsable row so the list is never blank (loading / empty states). */
    private MediaItem placeholder(String title) {
        MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                .setMediaId(NOOP_PREFIX + title)
                .setTitle(title)
                .build();
        return new MediaItem(d, MediaItem.FLAG_BROWSABLE);
    }

    /** Pull the tablet's real .xsp/.m3u smart playlists live (no hardcoded set). Caches + returns
        {label, file}; null on failure. */
    private String[][] fetchPlaylists() {
        String r = kodi.rpc("Files.GetDirectory",
                "{\"directory\":\"" + PLAYLIST_DIR + "\",\"media\":\"video\"}");
        if (r == null) return null;
        try {
            JSONArray files = new JSONObject(r).getJSONObject("result").getJSONArray("files");
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String file = f.optString("file", "");
                String label = f.optString("label", "");
                String low = file.toLowerCase();
                if ((low.endsWith(".xsp") || low.endsWith(".m3u")) && label.length() > 0)
                    out.add(new String[]{label, file});
            }
            String[][] arr = out.toArray(new String[0][]);
            playlists = arr;
            return arr;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- transport -> JSON-RPC ----------------

    private final class TransportCallback extends MediaSessionCompat.Callback {
        @Override public void onPlay() {
            io.execute(() -> kodi.rpc("Player.PlayPause", "{\"playerid\":" + VIDEO_PLAYER + ",\"play\":true}"));
        }
        @Override public void onPause() {
            io.execute(() -> kodi.rpc("Player.PlayPause", "{\"playerid\":" + VIDEO_PLAYER + ",\"play\":false}"));
        }
        @Override public void onStop() {
            io.execute(() -> kodi.rpc("Player.Stop", "{\"playerid\":" + VIDEO_PLAYER + "}"));
        }
        @Override public void onSkipToNext() {
            io.execute(() -> kodi.rpc("Player.GoTo", "{\"playerid\":" + VIDEO_PLAYER + ",\"to\":\"next\"}"));
        }
        @Override public void onSkipToPrevious() {
            io.execute(() -> kodi.rpc("Player.GoTo", "{\"playerid\":" + VIDEO_PLAYER + ",\"to\":\"previous\"}"));
        }
        @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
            if (mediaId == null) return;
            if (mediaId.startsWith(NOOP_PREFIX)) {   // "Searching…" tapped: force a rescan + refresh
                io.execute(() -> { kodi.rescan(); playlists = null; notifyChildrenChanged(ROOT_ID); });
                return;
            }
            io.execute(() -> playPlaylist(mediaId));
        }
        @Override public void onCustomAction(String action, Bundle extras) {
            if (!ACT_POWER.equals(action)) return;
            io.execute(() -> {
                boolean toNap = !napped;                 // toggle: off if awake, on if napping
                if (kodi.bridge(toNap ? NAP_JSON : WAKE_JSON)) napped = toNap;
                main.postDelayed(KodiMediaService.this::pollOnce, 400);  // republish -> swap the label
            });
        }
    }

    /** Mirror of the phone remote's playPlaylist: clear queue, add the .xsp/.m3u directory, open it,
        then shuffle the all-shows mixes and repeat-all. `file` is the playlist path (the mediaId). */
    private void playPlaylist(String file) {
        String label = labelFor(file);
        String l = label.toLowerCase(), lf = file.toLowerCase();
        boolean shuffle = l.contains("shuffle") || l.contains("all") || lf.contains("shuffleall");
        kodi.rpc("Playlist.Clear", "{\"playlistid\":" + VIDEO_PLAYER + "}");
        kodi.rpc("Playlist.Add", "{\"playlistid\":" + VIDEO_PLAYER + ",\"item\":{\"directory\":\"" + jesc(file) + "\"}}");
        kodi.rpc("Player.Open", "{\"item\":{\"playlistid\":" + VIDEO_PLAYER + "}}");
        try { Thread.sleep(700); } catch (Exception e) {}
        kodi.rpc("Player.SetShuffle", "{\"playerid\":" + VIDEO_PLAYER + ",\"shuffle\":" + shuffle + "}");
        kodi.rpc("Player.SetRepeat", "{\"playerid\":" + VIDEO_PLAYER + ",\"repeat\":\"all\"}");
        main.postDelayed(this::pollOnce, 900);   // reflect the new now-playing quickly
    }

    /** Resolve a playlist file back to its label from the cache; fall back to the file's basename. */
    private String labelFor(String file) {
        String[][] pls = playlists;
        if (pls != null) for (String[] pl : pls) if (pl[1].equals(file)) return pl[0];
        String s = file;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    // ---------------- state poll ----------------

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            io.execute(KodiMediaService.this::pollOnce);
            main.postDelayed(this, 1000);
        }
    };

    /** One poll pass on an io thread: learn Kodi's state and publish it to the session. */
    private void pollOnce() {
        if (!kodi.connected()) kodi.findHost();
        final boolean conn = kodi.connected();
        if (conn && kodi.activeHost() != null)
            prefs.edit().putString("lastip", kodi.activeHost()).apply();

        // First connect (or reconnect) -> (re)load playlists and refresh the browse list.
        if (conn && !lastConnected) {
            playlists = null;
            fetchPlaylists();
            notifyChildrenChanged(ROOT_ID);
        }
        lastConnected = conn;

        if (!conn) {
            publishState(PlaybackStateCompat.STATE_CONNECTING, 0, "Connecting to tablet…", null, 0);
            return;
        }

        // Mirror Kodi's real volume into the head-unit slider, unless the user just changed it (avoid
        // a stale poll fighting a fresh press).
        if (volume != null && System.currentTimeMillis() - lastVolTouch > 2500) {
            try {
                int kv = new JSONObject(kodi.rpc("Application.GetProperties",
                        "{\"properties\":[\"volume\"]}")).getJSONObject("result").optInt("volume", -1);
                if (kv >= 0) volume.setCurrentVolume(kv);
            } catch (Exception e) {}
        }

        int pid = activePlayerId();
        if (pid < 0) {   // connected, nothing playing
            publishState(PlaybackStateCompat.STATE_STOPPED, 0, "Not playing", null, 0);
            return;
        }

        String title = "Playing", subtitle = null;
        long positionMs = 0, durationMs = 0;
        int speed = 1;
        try {
            JSONObject it = new JSONObject(kodi.rpc("Player.GetItem",
                    "{\"playerid\":" + pid + ",\"properties\":[\"showtitle\",\"season\",\"episode\",\"title\"]}"))
                    .getJSONObject("result").getJSONObject("item");
            title = label(it);
            String show = it.optString("showtitle", "");
            if (show.length() > 0 && !show.equals("null")) subtitle = show;
        } catch (Exception e) {}
        try {
            JSONObject pr = new JSONObject(kodi.rpc("Player.GetProperties",
                    "{\"playerid\":" + pid + ",\"properties\":[\"speed\",\"time\",\"totaltime\"]}"))
                    .getJSONObject("result");
            speed = pr.optInt("speed", 1);
            positionMs = ms(pr.optJSONObject("time"));
            durationMs = ms(pr.optJSONObject("totaltime"));
        } catch (Exception e) {}

        int state = speed == 0 ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_PLAYING;
        publishState(state, positionMs, title, subtitle, durationMs);
    }

    /** The id of the active player (0 audio / 1 video), or -1 if nothing is playing. */
    private int activePlayerId() {
        try {
            JSONArray a = new JSONObject(kodi.rpc("Player.GetActivePlayers", null))
                    .getJSONArray("result");
            if (a.length() > 0) return a.getJSONObject(0).optInt("playerid", -1);
        } catch (Exception e) {}
        return -1;
    }

    /** Push a PlaybackStateCompat + matching MediaMetadataCompat to the session (on the main thread). */
    private void publishState(final int state, final long positionMs,
                              final String title, final String subtitle, final long durationMs) {
        main.post(() -> {
            if (session == null) return;
            PlaybackStateCompat.Builder ps = new PlaybackStateCompat.Builder()
                    .setActions(ACTIONS)
                    .setState(state, positionMs, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f);
            // Power button: sticky nap/wake via the 8080 bridge. Label reflects the last toggle so a
            // second press reverses it.
            ps.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                    ACT_POWER, napped ? "Wake tablet" : "Turn off tablet", R.drawable.ic_power).build());
            session.setPlaybackState(ps.build());

            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title);
            if (subtitle != null) {
                md.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle);
                md.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle);
            }
            if (durationMs > 0) md.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
            session.setMetadata(md.build());
        });
    }

    // ---------------- helpers (ported from the phone remote) ----------------

    /** Build a human "Show S01E02 - Title" label from a Kodi item, mirroring the phone remote. */
    private String label(JSONObject it) {
        String show = it.optString("showtitle", "");
        int s = it.optInt("season", -1), e = it.optInt("episode", -1);
        String t = it.optString("title", "");
        if (t.length() == 0 || t.equals("null")) t = it.optString("label", "");
        StringBuilder sb = new StringBuilder();
        if (show.length() > 0 && !show.equals("null")) sb.append(show);
        if (s > 0 && e > 0) sb.append(sb.length() > 0 ? " " : "").append(String.format("S%dE%02d", s, e));
        if (t.length() > 0 && !t.equals("null")) sb.append(sb.length() > 0 ? " – " : "").append(t);
        return sb.length() > 0 ? sb.toString() : "Playing";
    }

    /** Kodi time object {hours,minutes,seconds,milliseconds} -> milliseconds. */
    private long ms(JSONObject t) {
        if (t == null) return 0;
        return ((long) t.optInt("hours", 0) * 3600
              + (long) t.optInt("minutes", 0) * 60
              + t.optInt("seconds", 0)) * 1000L
              + t.optInt("milliseconds", 0);
    }

    private String jesc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------- remote volume -> Kodi ----------------

    /** A REMOTE VolumeProvider: the head unit's volume rocker adjusts THIS (Kodi's volume on the
        tablet), not the car's audio stream. ABSOLUTE so a head unit with a slider works too. We push
        the value to Kodi off the binder thread but update the displayed level immediately for snappy
        feedback; the poll loop reconciles it with Kodi's real volume (see lastVolTouch). */
    private final class KodiVolume extends VolumeProviderCompat {
        KodiVolume() { super(VOLUME_CONTROL_ABSOLUTE, 100, 50); }

        @Override public void onSetVolumeTo(int vol) { apply(Math.max(0, Math.min(100, vol))); }

        @Override public void onAdjustVolume(int direction) {
            if (direction == 0) return;
            apply(Math.max(0, Math.min(100, getCurrentVolume() + (direction > 0 ? VOL_STEP : -VOL_STEP))));
        }

        private void apply(final int v) {
            lastVolTouch = System.currentTimeMillis();
            setCurrentVolume(v);   // optimistic: move the slider now
            io.execute(() -> kodi.rpc("Application.SetVolume", "{\"volume\":" + v + "}"));
        }
    }
}
