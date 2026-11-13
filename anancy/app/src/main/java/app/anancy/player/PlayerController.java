package app.anancy.player;

import app.anancy.Cfg;
import app.anancy.Identity;
import app.anancy.net.HotspotService;

// ============================================================================
//  Wraps the ExoPlayer so anything (HTTP control, BLE, remote) can drive it
//  safely. ExoPlayer is single-threaded (main looper), so every command is
//  marshaled to main; reads come from a volatile snapshot refreshed on main by a
//  ticker + player listener — so the control server never touches ExoPlayer off
//  the main thread.
//
//  Library model: every media file gets a stable int id = its index in the global
//  list sorted by path (rebuilt on each scan). Shows = folders. Playlists =
//  built-ins (Shuffle All + one per show) + USER playlists (a saved queue,
//  persisted in files/playlists.json as {name:[paths]}). The QUEUE is
//  currentItems (play order, mirrored 1:1 in the ExoPlayer timeline);
//  naturalItems is the loaded playlist's canonical order for un-shuffling.
// ============================================================================

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlayerController {
    private static PlayerController I;
    public static synchronized PlayerController get() { if (I == null) I = new PlayerController(); return I; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer p;
    private Context appCtx;

    // volatile snapshot (written on main, read by the HTTP/BLE threads)
    private volatile int idx, count, posMs, durMs, curId = -1;
    private volatile long sampleClock;   // elapsedRealtime() when posMs was last sampled (followers extrapolate the leader from it)
    private volatile boolean playing;
    // Volume model: the user's loudness is the SYSTEM media volume (what actually comes out of the
    // speaker); a separate PERSISTED mute drives the ExoPlayer multiplier (0/1). The bug this fixes:
    // the old code only moved the player multiplier, so remotes "raised" volume while the system
    // stream sat at 0 — and the kiosk swallows the hardware keys, so there was no way out.
    private volatile float vol = 0.5f;        // system media volume fraction 0..1
    private volatile boolean muted = false;   // persisted player mute
    private AudioManager am; private android.content.SharedPreferences prefs;
    private volatile String title = "";
    private volatile boolean napped = false;   // "powered off" = paused + TV blacked
    private final LinkedHashMap<String, List<MediaItem>> playlists = new LinkedHashMap<>();       // built-ins
    private final LinkedHashMap<String, List<MediaItem>> userPlaylists = new LinkedHashMap<>();   // saved queues
    private volatile String currentPl = "Shuffle All";
    private volatile List<MediaItem> currentItems = new ArrayList<>();   // the QUEUE in play order
    private volatile List<MediaItem> naturalItems = new ArrayList<>();   // loaded playlist's canonical order
    private volatile boolean shuffle = false, loop = true;
    // library index: id = index into libFiles (sorted by path); libShows = show -> ids (episode order)
    private volatile List<File> libFiles = new ArrayList<>();
    private volatile LinkedHashMap<String, List<Integer>> libShows = new LinkedHashMap<>();
    // CLIENT mode (anancy_host set): the library lives on another tablet; items are host /media URLs keyed
    // by the SAME id the host uses, so a broadcast "play show/episode X" plays the same content on both.
    private volatile Map<Integer, MediaItem> clientItems = null;   // non-null => this player is a client
    private volatile Map<String, Integer> idOfPath = null;         // host: local file path -> stable id (for "cur")
    private volatile boolean syncEnabled = false;                  // follower: continuously mirror the host's playback
    private volatile boolean syncMuteFollower = false;             // follower: stay silent (leader-only audio) vs play own audio
    public void setSyncMuteFollower(boolean m) { syncMuteFollower = m; if (p != null) on(this::syncMute); }
    private Runnable syncTick;
    private Runnable startPending;                                 // a scheduled seek-ahead start waiting to fire
    // Shared clock (NTP-lite): leaderClock(now) ≈ elapsedRealtime() + clkOffset. Kept fresh by min-RTT probes.
    private volatile long clkOffset = 0;
    private volatile boolean clkValid = false;
    private volatile long clkBestRtt = Long.MAX_VALUE;
    // Group buffering (follower side): see mirror().
    private long alignTarget = -1, alignStarted = 0, alignLead = SyncMath.MIN_LEAD_MS, bufferingSince = 0, settleUntil = 0;
    private boolean alignReadySeen = false, holdSent = false;
    private int alignMisses = 0;
    private float followerSpeed = 1f;
    // Group buffering (leader side): follower id -> when its hold lapses. Main thread only.
    private final java.util.Map<String, Long> holds = new java.util.HashMap<>();
    private boolean heldByGroup = false;
    private long holdCooldownUntil = 0;
    private static final long REBUFFER_HOLD_MS = 1500, ALIGN_SETTLE_MS = 1500, HOLD_MAX_MS = 8000, HOLD_COOLDOWN_MS = 20000;
    // (clock-offset filtering + drift math live in SyncMath — pure + unit-tested; HARD_SEEK_MS is there)

    public void setNapped(boolean n) { napped = n; applyMute(); }   // re-assert volume so a napped ("off") player goes silent immediately

    /** Scan the library (off-main), build playlists (Shuffle All + one per show), start Shuffle All. */
    public void loadLibrary(Context ctx) { loadLibrary(ctx, true, 0); }
    private boolean holdFirstStart = false;
    /** The next library load queues the show but leaves it paused. */
    public void holdNextStart() { holdFirstStart = true; }
    /** Rebuild playlists after an import, WITHOUT disrupting current playback. */
    public void rescan(Context ctx) { loadLibrary(ctx, false, 0); }
    /** Start at the retry cap: a deliberate reload is not the boot race, so it must report an empty
        folder immediately instead of sitting on black for 90 seconds retrying a mounted card. */
    private static final int NO_RETRY = 30;
    /** The media folder CHANGED (picked, or reset to the defaults). The queue still points at files
        that are no longer in the library at all, so rebuild it -- plain rescan() deliberately will
        not, because it must not yank a show that is still perfectly valid. */
    public void rescanAndRestart(Context ctx) { loadLibrary(ctx, true, NO_RETRY); }
    /** Re-read the library in CLIENT mode (anancy_host just set) so a follower can resolve the leader's ids.
        autoStart=false: don't blast playback — the sync loop drives what plays. */
    public void reloadForSync(Context ctx) { gaveUpOnHost = null; loadLibrary(ctx, false, 0); }
    private void loadLibrary(final Context ctx, final boolean autoStart, final int attempt) {
        appCtx = ctx.getApplicationContext();
        final String host = Cfg.getStr(appCtx, "anancy_host", "");
        if (host != null && !host.isEmpty() && !host.equals(gaveUpOnHost)) { new Thread(() -> loadClientLibrary(host, autoStart, 0)).start(); return; }
        clientItems = null;
        new Thread(() -> {
            LinkedHashMap<String, List<File>> shows = Library.scanShows(ctx);
            // stable ids: global list sorted by absolute path
            final List<File> files = new ArrayList<>();
            for (List<File> fs : shows.values()) files.addAll(fs);
            java.util.Collections.sort(files, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
            final Map<String, Integer> idOf = new HashMap<>();
            for (int i = 0; i < files.size(); i++) idOf.put(files.get(i).getAbsolutePath(), i);
            final LinkedHashMap<String, List<Integer>> showIds = new LinkedHashMap<>();
            final LinkedHashMap<String, List<MediaItem>> built = new LinkedHashMap<>();
            final List<MediaItem> all = new ArrayList<>();
            for (Map.Entry<String, List<File>> e : shows.entrySet()) {
                List<MediaItem> items = new ArrayList<>(); List<Integer> ids = new ArrayList<>();
                for (File f : e.getValue()) { MediaItem mi = MediaItem.fromUri(Uri.fromFile(f)); items.add(mi); all.add(mi); ids.add(idOf.get(f.getAbsolutePath())); }
                built.put(e.getKey(), items); showIds.put(e.getKey(), ids);
            }
            // Boot race: the portable SD card can take tens of seconds to mount after boot, so a
            // scan on launch may find nothing. Retry until content appears (~90s) before giving up.
            // Only when waiting can actually help, though: with no all-files access the card stays
            // unreadable no matter how long we sit here, and retrying a permission problem for 90
            // seconds just buys a longer black screen that ends in exactly the same place.
            if (all.isEmpty() && autoStart && attempt < 30 && canWaitForStorage()) {
                android.util.Log.i("Anancy", "library empty (attempt " + attempt + "); SD may still be mounting, retry in 3s");
                main.postDelayed(() -> loadLibrary(ctx, true, attempt + 1), 3000);
                return;
            }
            final LinkedHashMap<String, List<MediaItem>> user = readUserPlaylists();
            on(() -> {
                lastLoadEmpty = all.isEmpty();
                libFiles = files; libShows = showIds; idOfPath = idOf; clientItems = null;
                playlists.clear();
                playlists.put("Shuffle All", all);
                playlists.putAll(built);
                userPlaylists.clear(); userPlaylists.putAll(user);
                loop = Cfg.getInt(appCtx, "anancy_loop", 1) == 1;
                if (all.isEmpty()) {
                    // A deliberate reload that found nothing must not leave the PREVIOUS folder's
                    // show playing on behind the empty state -- that reads as "nothing happened".
                    if (autoStart) {
                        currentItems = new ArrayList<>();
                        try { if (p != null) p.clearMediaItems(); } catch (Exception ignored) {}
                    }
                } else if (autoStart || currentItems == null || currentItems.isEmpty()) {
                    // autoStart = cold start or a deliberate folder change, so (re)build the queue.
                    // Otherwise only when nothing is queued: a routine rescan (files copied on)
                    // must not yank a show that is still playing perfectly well. But leaving it
                    // unselected when nothing is queued is the worst of both worlds -- the library
                    // is no longer empty so the empty state stays hidden, and the TV sits on black
                    // with a full library behind it.
                    selectPlaylist("Shuffle All");
                }
                holdFirstStart = false;   // an empty library must not swallow the first real Play
                Runnable cb = onLibraryLoaded;
                if (cb != null) cb.run();
            });
        }).start();
    }

    /** False when the library is unreadable for a reason that no amount of waiting will fix, so the
        caller reports it instead of retrying into a black screen. */
    private boolean canWaitForStorage() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30 && appCtx != null && appCtx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            return android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
        } catch (Exception e) { return true; }
    }

    /** Set by the player UI so an empty library can be explained on screen rather than shown as
        black. Runs on the main thread after every load, successful or not. */
    public Runnable onLibraryLoaded;
    private volatile boolean lastLoadEmpty = false;
    public boolean libraryEmpty() { return lastLoadEmpty; }

    /** CLIENT: build the library from the HOST's manifest — same shows/ids, items are host /media URLs
        (so id X on the client == id X on the host). Retries until the host is reachable. */
    private void loadClientLibrary(final String host, final boolean autoStart, final int attempt) {
        try {
            String base = "http://" + host + ":8090";
            JSONObject lib = new JSONObject(httpGet(base + "/library"));
            JSONArray shows = lib.optJSONArray("shows");
            final LinkedHashMap<String, List<MediaItem>> built = new LinkedHashMap<>();
            final LinkedHashMap<String, List<Integer>> showIds = new LinkedHashMap<>();
            final List<MediaItem> all = new ArrayList<>();
            final Map<Integer, MediaItem> byId = new HashMap<>();
            if (shows != null) for (int s = 0; s < shows.length(); s++) {
                String name = shows.getJSONObject(s).optString("name");
                if (name == null || name.isEmpty()) continue;
                JSONObject epsO = new JSONObject(httpGet(base + "/library?show=" + enc(name)));
                JSONArray eps = epsO.optJSONArray("items");
                List<MediaItem> list = new ArrayList<>(); List<Integer> ids = new ArrayList<>();
                if (eps != null) for (int i = 0; i < eps.length(); i++) {
                    int id = eps.getJSONObject(i).optInt("id", -1);
                    String title = eps.getJSONObject(i).optString("title", "");
                    if (id < 0) continue;
                    MediaItem mi = new MediaItem.Builder().setUri(base + "/media?id=" + id)
                            .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build()).build();
                    list.add(mi); all.add(mi); ids.add(id); byId.put(id, mi);
                }
                built.put(name, list); showIds.put(name, ids);
            }
            if (all.isEmpty()) { retryClientLibrary(host, autoStart, attempt); return; }
            on(() -> {
                clientItems = byId; idOfPath = null; libFiles = new ArrayList<>(); libShows = showIds;
                playlists.clear();
                playlists.put("Shuffle All", all);
                playlists.putAll(built);
                loop = Cfg.getInt(appCtx, "anancy_loop", 1) == 1;
                android.util.Log.i("Anancy", "client library from " + host + ": " + all.size() + " items, " + built.size() + " shows");
                if (autoStart) selectPlaylist("Shuffle All");
                syncMuteFollower = Cfg.getInt(appCtx, "anancy_sync_mute", 0) == 1;   // restore leader-only vs own-audio
                if (Cfg.getInt(appCtx, "anancy_sync", 0) == 1) setSync(true);   // follower: start mirroring the host
            });
        } catch (Exception e) {
            android.util.Log.w("Anancy", "client library from " + host + " failed: " + e);
            retryClientLibrary(host, autoStart, attempt);
        }
    }
    /** A client tablet used to retry an absent host FOREVER and never fall back, so one sync session
        left it booting to a black screen with no escape from any remote. Give up and play our own SD,
        for this run only: the host may just be slower to start, so the next boot tries it again. */
    private volatile String gaveUpOnHost;
    private void retryClientLibrary(final String host, final boolean autoStart, final int attempt) {
        if (attempt >= 4) {
            android.util.Log.w("Anancy", "host " + host + " unreachable after " + (attempt + 1) + " tries - falling back to the local library");
            gaveUpOnHost = host;
            clientItems = null;
            main.post(() -> loadLibrary(appCtx, true, 0));
            return;
        }
        // off the main thread: the retry used to run there and die instantly with
        // NetworkOnMainThreadException, so client mode could never recover from a blip.
        main.postDelayed(() -> new Thread(() -> loadClientLibrary(host, autoStart, attempt + 1)).start(), 4000);
    }

    private static String enc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    /** GET from another TV (the leader / library host), signed with the key a remote granted us. The
        answer only counts if that TV proves it sent it. */
    private String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(4000);
        try {
            if (appCtx == null) throw new java.io.IOException("no context");
            app.anancy.Pairing.sign(appCtx, c);
            String r = app.anancy.Pairing.fetch(appCtx, c);
            if (r == null) throw new java.io.IOException("no genuine answer from " + c.getURL().getHost() + " (not paired with it?)");
            return r;
        } finally { c.disconnect(); }
    }

    private List<MediaItem> itemsFor(String name) {
        List<MediaItem> items = playlists.get(name);
        return items != null ? items : userPlaylists.get(name);
    }

    /** Switch to a playlist by name: Shuffle All -> shows interleaved; a show / user playlist -> in order. */
    public void selectPlaylist(final String name) {
        on(() -> {
            List<MediaItem> items = itemsFor(name);
            if (items == null || items.isEmpty() || p == null) return;
            currentPl = name;
            // Shuffle All used to be Collections.shuffle over every episode, which put
            // Numberblocks 47 after Numberblocks 3. Now the SHOWS are shuffled and their
            // episodes are not: see ShuffleAll. Either way the LIST is the play order (not
            // ExoPlayer's shuffle mode), so a remote can show "what's next" and jump to any item.
            boolean all = "Shuffle All".equals(name);
            List<MediaItem> order = all ? interleavedAll() : new ArrayList<>(items);
            // Not "shuffled" any more in the sense the remote's toggle means — the queue has a
            // deliberate order now. The toggle stays available as a way to scramble it on purpose.
            shuffle = false;
            naturalItems = new ArrayList<>(order);
            currentItems = order;
            p.setShuffleModeEnabled(false);
            p.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            p.setMediaItems(order);
            p.prepare();
            keepMute();   // audio-safety: a new media list must never start audible while muted
            if (holdFirstStart) holdFirstStart = false; else p.play();
        });
    }
    /** Re-assert the mute/volume multiplier (call after any setMediaItems). Cheap insurance so a
        freshly-built queue can never boot to full volume while muted — the nap-safe invariant. */
    private void keepMute() { if (p != null && !sleeping) p.setVolume(effMult()); }
    private static List<MediaItem> shuffled(List<MediaItem> in) { List<MediaItem> out = new ArrayList<>(in); java.util.Collections.shuffle(out); return out; }

    // --- "Shuffle All": shows shuffled, episodes in order, resumed where she got to -----------
    //
    //  The per-show watermarks are what make the chronology mean anything. There is no resume in
    //  this player: every cold boot calls selectPlaylist("Shuffle All") and starts at index 0.
    //  That was harmless while the order was random, but an ordered queue with no memory would
    //  replay episode 1 of all 28 shows every single boot, so she'd never reach the later
    //  Numberblocks — the exact thing the ordering is for.

    private static final String PROGRESS_KEY = "anancy_progress";   // JSON: { "<show>": <episode index> }
    private volatile int lastProgressId = -1;

    /** The queue for "Shuffle All", built fresh on every select so the rotation varies between
        sessions. Falls back to the flat list if the per-show breakdown is somehow missing — a
        strange order beats a black screen. */
    private List<MediaItem> interleavedAll() {
        LinkedHashMap<String, List<MediaItem>> byShow = new LinkedHashMap<>();
        for (Map.Entry<String, List<MediaItem>> e : playlists.entrySet())
            if (!"Shuffle All".equals(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty())
                byShow.put(e.getKey(), e.getValue());
        List<MediaItem> order = ShuffleAll.interleave(byShow, progress(), new java.util.Random());
        if (!order.isEmpty()) return order;
        List<MediaItem> flat = playlists.get("Shuffle All");
        return flat == null ? new ArrayList<>() : new ArrayList<>(flat);
    }

    /** show -> index of the episode she is up to. Empty on a fresh install (everything starts at 1). */
    private Map<String, Integer> progress() {
        Map<String, Integer> out = new java.util.HashMap<>();
        try {
            if (appCtx == null) return out;
            String s = Cfg.getStr(appCtx, PROGRESS_KEY, "");
            if (s == null || s.isEmpty()) return out;
            JSONObject o = new JSONObject(s);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.optInt(k, 0));
            }
        } catch (Exception e) { android.util.Log.w("Anancy", "progress read: " + e); }
        return out;
    }

    /** Remember which episode of which show is on screen. Called from the ticker, but gated on the
        id actually changing, so this writes once per episode and not twice a second. */
    private void noteProgress(int id) {
        if (id < 0 || id == lastProgressId) return;
        lastProgressId = id;
        try {
            for (Map.Entry<String, List<Integer>> e : libShows.entrySet()) {
                int i = e.getValue().indexOf(id);
                if (i < 0) continue;
                if (appCtx == null) return;
                String s = Cfg.getStr(appCtx, PROGRESS_KEY, "");
                JSONObject o = (s == null || s.isEmpty()) ? new JSONObject() : new JSONObject(s);
                o.put(e.getKey(), i);
                Cfg.put(appCtx, PROGRESS_KEY, o.toString());
                return;
            }
        } catch (Exception e) { android.util.Log.w("Anancy", "progress write: " + e); }
    }

    /** Admin: forget where she is up to, so every show starts from episode 1 again. */
    public void resetProgress() {
        lastProgressId = -1;
        try { if (appCtx != null) Cfg.put(appCtx, PROGRESS_KEY, ""); } catch (Exception ignored) {}
    }

    /** Jump to item i of the current queue and play. */
    public void jumpTo(final int i) { on(() -> { if (p != null && i >= 0 && i < p.getMediaItemCount()) { clearHolds(); p.seekToDefaultPosition(i); p.play(); } }); }
    /** The current queue as JSON: titles in PLAY order + current index. n>0 = a window of center±n (fits BLE); n<=0 = full list. */
    public String queueJson(int center, int n) { return queueJson(center, n, Integer.MAX_VALUE); }
    /** maxTitle bounds each item title so a BLE window fits ONE MTU (~509 bytes); HTTP passes MAX_VALUE. */
    public String queueJson(int center, int n, int maxTitle) {
        JSONObject o = new JSONObject();
        try {
            List<MediaItem> items = currentItems;
            int total = items.size();
            if (center < 0) center = idx;   // default the window around the current item
            int from = n <= 0 ? 0 : Math.max(0, center - n), to = n <= 0 ? total : Math.min(total, center + n + 1);
            org.json.JSONArray a = new org.json.JSONArray();
            for (int i = from; i < to; i++) {
                String t = titleOf(items.get(i));
                if (t.length() > maxTitle) t = t.substring(0, maxTitle);
                a.put(t);
            }
            o.put("type", "queue"); o.put("playlist", currentPl); o.put("index", idx); o.put("total", total); o.put("from", from); o.put("items", a);
            o.put("shuffle", shuffle); o.put("loop", loop);
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** Playlist names as a one-shot payload (BLE "list" / HTTP /playlists): built-ins first, then user. */
    public String playlistsJson() { return playlistsJson(0, Integer.MAX_VALUE); }
    /** Paged for BLE: names from `from` that fit in maxBytes, plus from/total. The whole list is 600+
        bytes - more than one notification - which is why the phone's Playlists tab arrived EMPTY. */
    public String playlistsJson(int from, int maxBytes) {
        JSONObject o = new JSONObject();
        try {
            List<String> all = new ArrayList<>(playlists.keySet());
            all.addAll(userPlaylists.keySet());
            int total = all.size();
            from = Math.max(0, Math.min(from, total));
            JSONArray a = new JSONArray(), u = new JSONArray();
            o.put("type", "playlists"); o.put("from", from); o.put("total", total);
            o.put("names", a); o.put("user", u);
            final int firstUser = playlists.size();   // "user" flags the user playlists on THIS page only
            for (int i = from; i < total; i++) {
                a.put(all.get(i));
                if (i >= firstUser) u.put(all.get(i));
                if (o.toString().length() > maxBytes) {
                    a.remove(a.length() - 1);
                    if (i >= firstUser) u.remove(u.length() - 1);
                    break;
                }
            }
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** Paged queue for BLE: items from `from` that fit in maxBytes. The old fixed +/-3 window is why the
        phone showed ~7 of 1362 items with no way to scroll. from < 0 starts at the current item. */
    public String queuePageJson(int from, int maxBytes, int maxTitle) {
        JSONObject o = new JSONObject();
        try {
            List<MediaItem> items = currentItems;
            int total = items.size();
            if (from < 0) from = idx;
            from = Math.max(0, Math.min(from, total));
            JSONArray a = new JSONArray();
            o.put("type", "queue"); o.put("playlist", currentPl); o.put("index", idx);
            o.put("total", total); o.put("from", from); o.put("items", a);
            o.put("shuffle", shuffle); o.put("loop", loop);
            for (int i = from; i < total; i++) {
                String t = titleOf(items.get(i));
                if (t.length() > maxTitle) t = t.substring(0, maxTitle);
                a.put(t);
                if (o.toString().length() > maxBytes) { a.remove(a.length() - 1); break; }
            }
        } catch (Exception ignored) {}
        return o.toString();
    }


    // --- queue editing ------------------------------------------------------
    private MediaItem itemFor(int id) {
        Map<Integer, MediaItem> c = clientItems;
        if (c != null) return c.get(id);   // client: host /media URL for this id
        List<File> f = libFiles;
        return (id >= 0 && id < f.size()) ? MediaItem.fromUri(Uri.fromFile(f.get(id))) : null;
    }
    /** The library file for a stable id, or null. Lets the host serve it over HTTP (/media) so a client
        tablet can play it by the same id the library manifest advertises. */
    public File fileForId(int id) {
        List<File> f = libFiles;
        return (id >= 0 && id < f.size()) ? f.get(id) : null;
    }
    /** Client playback: play a single item from a URL (a host's /media?id=N). Muted-safe — a client
        streaming from the host must never start audible. Used to verify host/client streaming. */
    public void playUrl(final String url) {
        if (url == null || url.isEmpty()) return;
        on(() -> {
            if (p == null) return;
            p.setMediaItems(java.util.Collections.singletonList(MediaItem.fromUri(url)));
            p.prepare(); keepMute(); p.play();
        });
    }

    /** Stable library id of the CURRENT item — host: from its file path; client: parsed from the
        /media?id URL. Lets a follower know exactly what the leader is playing. -1 if unknown. */
    private int currentLibId() {
        try {
            MediaItem m = p != null ? p.getCurrentMediaItem() : null;
            if (m == null || m.localConfiguration == null) return -1;
            android.net.Uri u = m.localConfiguration.uri;
            String us = u.toString();
            int q = us.indexOf("media?id=");
            if (q >= 0) return Integer.parseInt(us.substring(q + 9).replaceAll("\\D.*", ""));   // client URL
            Map<String, Integer> map = idOfPath;
            if (map != null) { Integer id = map.get(u.getPath()); if (id != null) return id; }   // host file
        } catch (Exception ignored) {}
        return -1;
    }

    // --- SYNCED playback (follower): mirror the host's current item + position; muted, speed-nudged ----
    public boolean isSync() { return syncEnabled; }
    public void setSync(boolean on) { syncEnabled = on; if (on) startSyncLoop(); else stopSyncLoop(); }

    // NTP-lite: one probe of the leader's /clock. Keep the offset from the LOWEST-RTT sample (least jitter).
    private long clockNow() { return android.os.SystemClock.elapsedRealtime(); }
    private long leaderNow() { return clockNow() + clkOffset; }
    private void resetClock() { clkOffset = 0; clkValid = false; clkBestRtt = Long.MAX_VALUE; }
    private void probeClock(String host) {
        try {
            long t0 = clockNow();
            long tL = new JSONObject(httpGet("http://" + host + ":8090/clock")).optLong("t", -1);
            long t2 = clockNow();
            if (tL < 0) return;
            SyncMath.Clock c = SyncMath.fold(new SyncMath.Clock(clkOffset, clkBestRtt, clkValid), t0, tL, t2);
            clkOffset = c.offset; clkBestRtt = c.bestRtt; clkValid = c.valid;
        } catch (Exception ignored) {}
    }

    private void startSyncLoop() {
        if (syncTick != null) return;
        final String host = Cfg.getStr(appCtx, "anancy_host", "");
        if (host == null || host.isEmpty() || host.equals(gaveUpOnHost)) { syncEnabled = false; return; }   // only a client can follow
        resetClock();
        new Thread(() -> { for (int i = 0; i < 6 && syncEnabled; i++) { probeClock(host); try { Thread.sleep(120); } catch (Exception ignored) {} } }).start();
        syncTick = new Runnable() { long lastClk = 0; public void run() {
            if (!syncEnabled) { syncTick = null; return; }
            new Thread(() -> {
                try {
                    long n = clockNow();
                    if (n - lastClk > 5000) { probeClock(host); lastClk = n; }   // keep the offset fresh (drift)
                    JSONObject s = new JSONObject(httpGet("http://" + host + ":8090/state"));
                    final int cur = s.optInt("cur", -1);
                    final boolean lplaying = s.optBoolean("playing", false);
                    final long lpos = (long) s.optDouble("position_ms", 0);
                    final long lclock = s.optLong("clock", -1);   // leader's monotonic clock at that position sample
                    on(() -> mirror(cur, lpos, lclock, lplaying));
                } catch (Exception ignored) {}
            }).start();
            main.postDelayed(this, 350);
        }};
        main.post(syncTick);
    }
    private void stopSyncLoop() {
        if (syncTick != null) { main.removeCallbacks(syncTick); syncTick = null; }
        cancelAlign(); bufferingSince = 0; requestHold(false);
        on(() -> { try { if (p != null) { followerSpeed = 1f; p.setPlaybackParameters(new PlaybackParameters(1f)); keepMute(); } } catch (Exception ignored) {} });   // restore the device's own volume (no longer a forced-silent follower)
    }
    /** The leader's position RIGHT NOW, extrapolated from a timestamped sample on the shared clock. */
    private long expectedPos(long leaderPos, long leaderClock, boolean lplaying) {
        return SyncMath.expectedPos(leaderPos, leaderClock, lplaying, leaderNow(), clkValid);
    }
    /** One mirror step (main thread): match the leader's item + shared-clock position and play/pause.
        A follower must never seek while it's still buffering: the leader moves on meanwhile, the drift
        looks worse, and it seeks again, forever. So a big correction aligns instead: seek AHEAD of the
        leader, buffer there paused, and start the moment the leader reaches that frame. */
    private void mirror(int cur, long leaderPos, long leaderClock, boolean lplaying) {
        if (p == null || cur < 0 || !clkValid) return;
        final long expected = expectedPos(leaderPos, leaderClock, lplaying);
        if (currentLibId() != cur) {                         // wrong item -> switch + align
            MediaItem mi = itemFor(cur);
            if (mi != null) {
                cancelAlign(); bufferingSince = 0; setFollowerSpeed(1f);
                p.setMediaItems(java.util.Collections.singletonList(mi)); p.prepare();
                if (lplaying) beginAlign(expected);
                else { p.seekTo(expected); p.pause(); }
            }
            syncMute(); return;
        }
        if (alignTarget >= 0) {
            if (lplaying) { stepAlign(expected); syncMute(); return; }
            cancelAlign();                                   // leader paused (or is waiting for us): just match it
        }
        if (p.getPlaybackState() == Player.STATE_BUFFERING) {
            if (lplaying && p.getPlayWhenReady()) {          // stalled mid-show: if it lasts, the group waits for us
                long n = clockNow();
                if (bufferingSince == 0) bufferingSince = n;
                else if (n - bufferingSince > REBUFFER_HOLD_MS) requestHold(true);
            }
            syncMute(); return;
        }
        bufferingSince = 0;
        long drift = p.getCurrentPosition() - expected;      // >0 = we're ahead of the leader
        if (holdSent && !SyncMath.shouldHardSeek(drift)) requestHold(false);   // caught up: the group can carry on
        if (SyncMath.shouldHardSeek(drift) && clockNow() >= settleUntil) {
            long d = p.getDuration();
            if (lplaying && d != androidx.media3.common.C.TIME_UNSET && expected + alignLead >= d) { syncMute(); return; }   // about to change item anyway
            setFollowerSpeed(1f);
            if (lplaying) beginAlign(expected);
            else { p.pause(); p.seekTo(expected); }
            syncMute(); return;
        }
        setFollowerSpeed(lplaying ? SyncMath.driftSpeed(drift) : 1f);
        if (lplaying && !p.isPlaying()) p.play();
        else if (!lplaying && p.isPlaying()) p.pause();
        syncMute();
    }
    private void beginAlign(long expected) {
        cancelStart();
        alignTarget = expected + alignLead; alignStarted = clockNow(); alignReadySeen = false;
        p.pause(); p.seekTo(alignTarget);
    }
    private void stepAlign(long expected) {
        if (startPending != null) return;                    // start already timed
        boolean ready = p.getPlaybackState() == Player.STATE_READY;
        if (ready && !alignReadySeen) { alignReadySeen = true; alignLead = SyncMath.leadFromReady(clockNow() - alignStarted); }
        long wait = alignTarget - expected;
        if (wait <= 0) {                                     // the leader got there first
            alignLead = SyncMath.missedLead(alignLead);
            if (++alignMisses >= 2) requestHold(true);       // twice late: ask the group to wait for us
            beginAlign(expected);
            return;
        }
        if (!ready) return;
        startPending = () -> {
            startPending = null; alignTarget = -1; alignMisses = 0;
            settleUntil = clockNow() + ALIGN_SETTLE_MS;
            if (syncEnabled && p != null) { syncMute(); p.play(); }
        };
        main.postDelayed(startPending, wait);
    }
    private void cancelStart() { if (startPending != null) { main.removeCallbacks(startPending); startPending = null; } }
    private void cancelAlign() { cancelStart(); alignTarget = -1; alignMisses = 0; }
    private void setFollowerSpeed(float s) {
        if (p == null || Math.abs(s - followerSpeed) < 0.0005f) return;
        followerSpeed = s; p.setPlaybackParameters(new PlaybackParameters(s));
    }
    /** Follower audio each mirror tick. In LEADER-ONLY mode force gain 0 (audio comes from the leader, no
        echo — and a stray broadcast unmute can't make it sound). In EACH-SCREEN mode play its own volume. */
    private void syncMute() { if (p != null && !sleeping) p.setVolume(syncMuteFollower ? 0f : effMult()); }
    /** Follower -> leader: "wait for me" / "carry on". */
    private void requestHold(final boolean want) {
        if (holdSent == want || appCtx == null) return;
        holdSent = want;
        final String host = Cfg.getStr(appCtx, "anancy_host", "");
        final String id = Identity.id(appCtx);
        if (host == null || host.isEmpty()) return;
        new Thread(() -> { try { httpGet("http://" + host + ":8090/hold?id=" + id + "&on=" + (want ? "1" : "0")); } catch (Exception ignored) {} }).start();
    }
    /** Leader: a follower is buffering, so the group waits for it. Bounded, so a screen that dropped off
        can't leave the show stopped: the hold expires, and that screen is then ignored for a while. */
    public void groupHold(final String id, final boolean want) { on(() -> {
        if (p == null || id == null) return;
        long now = clockNow();
        if (want) {
            if (now < holdCooldownUntil || (!heldByGroup && !p.isPlaying())) return;   // a parent's pause isn't ours to manage
            holds.put(id, now + HOLD_MAX_MS);
            if (!heldByGroup) { heldByGroup = true; p.pause(); android.util.Log.i("Anancy", "group hold: waiting for " + id); }
            main.removeCallbacks(holdExpiry); main.postDelayed(holdExpiry, HOLD_MAX_MS);
        } else {
            holds.remove(id);
            if (holds.isEmpty()) resumeFromHold();
        }
    }); }
    private final Runnable holdExpiry = () -> {
        android.util.Log.i("Anancy", "group hold expired; carrying on");
        holds.clear(); holdCooldownUntil = clockNow() + HOLD_COOLDOWN_MS; resumeFromHold();
    };
    private void resumeFromHold() {
        main.removeCallbacks(holdExpiry);
        if (!heldByGroup) return;
        heldByGroup = false;
        if (p != null && !napped) p.play();
    }
    /** Someone pressed a control: their choice wins over any screen still buffering. True if we were holding. */
    private boolean clearHolds() {
        boolean was = heldByGroup;
        holds.clear(); heldByGroup = false; main.removeCallbacks(holdExpiry);
        return was;
    }
    /** Starts quickly, which group start-up depends on. The defaults wait for 2.5s of video (5s after a stall). */
    /** Media sources whose HTTP requests (a follower streaming from its leader's /media) are signed
        with the key for that TV. Each open, including every seek's range request, gets a fresh nonce. */
    @SuppressLint("UnsafeOptInUsageError")
    public static androidx.media3.exoplayer.source.MediaSource.Factory mediaSources(Context ctx) {
        final Context actx = ctx.getApplicationContext();
        androidx.media3.datasource.DataSource.Factory up = new androidx.media3.datasource.DefaultDataSource.Factory(actx);
        androidx.media3.datasource.DataSource.Factory signed = new androidx.media3.datasource.ResolvingDataSource.Factory(up, spec -> {
            if (!"http".equalsIgnoreCase(spec.uri.getScheme())) return spec;
            String h = app.anancy.Pairing.headerFor(actx, spec.uri.toString(), "GET");
            if (h == null) return spec;
            java.util.Map<String, String> m = new java.util.HashMap<>();
            m.put(app.anancy.net.PairCrypto.HEADER, h);
            return spec.withAdditionalHeaders(m);
        });
        return new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(signed);
    }

    public static androidx.media3.exoplayer.LoadControl loadControl() {
        return new androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(30000, 50000, 1000, 2000).build();
    }
    /** Append (or insert after the current item when next/now); now = also jump to it. False = bad id. */
    public boolean enqueue(final int id, final boolean next, final boolean now) {
        final MediaItem mi = itemFor(id);
        if (mi == null) return false;
        List<MediaItem> one = new ArrayList<>(); one.add(mi);
        enqueueItems(one, next || now, now);
        return true;
    }
    /** Append a whole show in episode order. False = unknown show. */
    public boolean enqueueShow(String show) {
        List<Integer> ids = libShows.get(show);
        if (ids == null) return false;
        List<MediaItem> items = new ArrayList<>();
        for (int id : ids) { MediaItem mi = itemFor(id); if (mi != null) items.add(mi); }
        if (items.isEmpty()) return false;
        enqueueItems(items, false, false);
        return true;
    }
    private void enqueueItems(final List<MediaItem> items, final boolean afterCurrent, final boolean jump) {
        on(() -> {
            if (p == null) return;
            List<MediaItem> q = new ArrayList<>(currentItems);
            if (p.getMediaItemCount() == 0) {   // empty player: this becomes the queue
                q.addAll(items); currentItems = q;
                p.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                p.setMediaItems(q); p.prepare(); keepMute(); p.play(); return;
            }
            int at = afterCurrent ? Math.min(q.size(), p.getCurrentMediaItemIndex() + 1) : q.size();
            q.addAll(at, items); currentItems = q;
            p.addMediaItems(at, items);
            if (jump) { p.seekToDefaultPosition(at); p.play(); }
        });
    }
    /** Remove queue index i (never the item that's playing). */
    /** Empty the queue. Playing: keep the episode that is on and drop everything else, so the screen
        doesn't go black mid-show. Stopped: clear it out entirely. */
    public void clearQueue() {
        on(() -> {
            if (p == null) return;
            int n = p.getMediaItemCount(), cur = p.getCurrentMediaItemIndex();
            if (p.isPlaying() && n > 0) {
                if (cur + 1 < n) p.removeMediaItems(cur + 1, n);
                if (cur > 0) p.removeMediaItems(0, cur);
                List<MediaItem> one = new ArrayList<>();
                if (cur >= 0 && cur < currentItems.size()) one.add(currentItems.get(cur));
                currentItems = one; naturalItems = new ArrayList<>(one);
            } else {
                p.clearMediaItems();
                currentItems = new ArrayList<>(); naturalItems = new ArrayList<>();
            }
            count = p.getMediaItemCount(); idx = p.getCurrentMediaItemIndex();
        });
    }

    public void dequeue(final int i) {
        on(() -> {
            if (p == null || i < 0 || i >= p.getMediaItemCount() || i == p.getCurrentMediaItemIndex()) return;
            List<MediaItem> q = new ArrayList<>(currentItems);
            if (i < q.size()) q.remove(i);
            currentItems = q;
            p.removeMediaItem(i);
        });
    }
    public boolean isShuffle() { return shuffle; }
    public boolean isLoop() { return loop; }
    /** Shuffle ON: random order with the playing item kept first, playback continues where it is.
        OFF: back to the loaded playlist's natural order (enqueued extras keep following). */
    public void setShuffle(final boolean on) {
        on(() -> {
            if (p == null || currentItems.isEmpty()) return;
            int ci = Math.max(0, Math.min(currentItems.size() - 1, p.getCurrentMediaItemIndex()));
            MediaItem cur = currentItems.get(ci);
            long pos = p.getCurrentPosition();
            boolean wasPlaying = p.getPlayWhenReady();
            List<MediaItem> order;
            int start;
            if (on) {
                List<MediaItem> rest = new ArrayList<>(currentItems); rest.remove(ci);
                java.util.Collections.shuffle(rest);
                order = new ArrayList<>(); order.add(cur); order.addAll(rest);
                start = 0;
            } else {
                order = new ArrayList<>(naturalItems);
                for (MediaItem m : currentItems) if (!naturalItems.contains(m)) order.add(m);   // enqueued extras
                start = Math.max(0, order.indexOf(cur));
            }
            shuffle = on; currentItems = order;
            p.setMediaItems(order, start, pos);
            p.prepare();
            keepMute();
            if (wasPlaying) p.play();
        });
    }
    /** loop = REPEAT_MODE_ALL (EOF-proof default). Off = stop at the end of the queue -> TV off (napNow). */
    public void setLoop(final boolean on) {
        loop = on;
        try { if (appCtx != null) Cfg.put(appCtx, "anancy_loop", on ? 1 : 0); } catch (Exception ignored) {}
        on(() -> { if (p != null) p.setRepeatMode(on ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF); });
    }

    // --- user playlists (saved queues) -------------------------------------
    private File playlistsFile() { return new File(appCtx.getFilesDir(), "playlists.json"); }
    /** Persist the current queue under a name (overwrites the same name). */
    public boolean saveQueue(String name) {
        if (name == null || name.trim().isEmpty() || currentItems.isEmpty()) return false;
        name = name.trim();
        if (playlists.containsKey(name)) return false;   // don't shadow a built-in
        userPlaylists.put(name, new ArrayList<>(currentItems));
        writeUserPlaylists();
        return true;
    }
    /** Delete a USER playlist; built-ins can't be deleted (false). */
    public boolean deletePlaylist(String name) {
        if (name == null || !userPlaylists.containsKey(name)) return false;
        userPlaylists.remove(name);
        writeUserPlaylists();
        return true;
    }
    private void writeUserPlaylists() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, List<MediaItem>> e : userPlaylists.entrySet()) {
                JSONArray a = new JSONArray();
                for (MediaItem m : e.getValue()) { String path = pathOf(m); if (path != null) a.put(path); }
                o.put(e.getKey(), a);
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(playlistsFile());
            fo.write(o.toString().getBytes("UTF-8")); fo.close();
        } catch (Exception e) { android.util.Log.w("Anancy", "playlists save: " + e); }
    }
    private LinkedHashMap<String, List<MediaItem>> readUserPlaylists() {
        LinkedHashMap<String, List<MediaItem>> out = new LinkedHashMap<>();
        try {
            File f = playlistsFile();
            if (!f.isFile()) return out;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fi = new java.io.FileInputStream(f); int n = 0, r;
            while (n < b.length && (r = fi.read(b, n, b.length - n)) > 0) n += r;
            fi.close();
            JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String name = it.next();
                JSONArray a = o.optJSONArray(name);
                List<MediaItem> items = new ArrayList<>();
                if (a != null) for (int i = 0; i < a.length(); i++) { File mf = new File(a.optString(i)); if (mf.isFile()) items.add(MediaItem.fromUri(Uri.fromFile(mf))); }
                if (!items.isEmpty()) out.put(name, items);
            }
        } catch (Exception e) { android.util.Log.w("Anancy", "playlists load: " + e); }
        return out;
    }
    private static String pathOf(MediaItem m) {
        try { return (m != null && m.localConfiguration != null) ? m.localConfiguration.uri.getPath() : null; } catch (Exception e) { return null; }
    }

    // --- library browsing ---------------------------------------------------
    /** {"type":"library","shows":[{"name","count"}...]} — HTTP: everything. */
    public String libraryJson() { return libraryJson(0, Integer.MAX_VALUE); }
    /** Paged variant for BLE: as many shows from `from` as fit in maxBytes (+ "from"/"total"). */
    public String libraryJson(int from, int maxBytes) {
        try {
            LinkedHashMap<String, List<Integer>> shows = libShows;
            List<String> names = new ArrayList<>(shows.keySet());
            int total = names.size();
            from = Math.max(0, Math.min(from, total));
            JSONArray a = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("type", "library"); o.put("from", from); o.put("total", total); o.put("shows", a);
            for (int i = from; i < total; i++) {
                JSONObject s = new JSONObject().put("name", names.get(i)).put("count", shows.get(names.get(i)).size());
                a.put(s);
                if (o.toString().length() > maxBytes) { a.remove(a.length() - 1); break; }
            }
            return o.toString();
        } catch (Exception e) { return "{\"type\":\"library\",\"shows\":[]}"; }
    }
    /** {"type":"episodes","show","from","total","items":[{"id","title"}...]}; max items / title length for BLE pages. */
    public String episodesJson(String show, int from, int max, int titleMax) {
        try {
            List<Integer> ids = libShows.get(show);
            JSONObject o = new JSONObject();
            o.put("type", "episodes"); o.put("show", show);
            int total = ids == null ? 0 : ids.size();
            from = Math.max(0, Math.min(from, total));
            JSONArray a = new JSONArray();
            for (int i = from; i < total && a.length() < max; i++) {
                int id = ids.get(i);
                String t = stripExt(libFiles.get(id).getName());
                if (titleMax > 0 && t.length() > titleMax) t = t.substring(0, titleMax);
                a.put(new JSONObject().put("id", id).put("title", t));
            }
            o.put("from", from); o.put("total", total); o.put("items", a);
            return o.toString();
        } catch (Exception e) { return "{\"type\":\"episodes\",\"items\":[]}"; }
    }
    private static String stripExt(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    // --- connectivity level ---------------------------------------------------
    /** {"type":"conn","mode","ip","hotspot":{ssid,pass}|null,"wifi":{ssid}|null} */
    public String connJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "conn");
            o.put("mode", appCtx != null ? HotspotService.mode(appCtx) : "ble");
            o.put("ip", wifiIp());
            if (HotspotService.running() && HotspotService.currentSsid() != null)
                o.put("hotspot", new JSONObject().put("ssid", HotspotService.currentSsid()).put("pass", HotspotService.currentPass() == null ? "" : HotspotService.currentPass()));
            else o.put("hotspot", JSONObject.NULL);
            String ssid = currentWifiSsid();
            o.put("wifi", ssid != null ? new JSONObject().put("ssid", ssid) : JSONObject.NULL);
        } catch (Exception ignored) {}
        return o.toString();
    }
    @SuppressWarnings("deprecation")
    private String currentWifiSsid() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            String s = wi != null ? wi.getSSID() : null;
            if (s == null || s.contains("unknown") || s.isEmpty()) return null;
            return s.replaceAll("^\"|\"$", "");
        } catch (Exception e) { return null; }
    }

    // sleep timer
    private volatile boolean sleeping;
    private volatile long sleepEnd;
    private float sleepFromVol;
    private int sleepMin;
    private Runnable sleepTick;
    private Runnable ticker;

    void attach(ExoPlayer player) {
        p = player;
        // A fresh engine starts at volume 1.0. Apply the persisted mute UNCONDITIONALLY here:
        // keepMute()/applyMute() are gated on !sleeping, so a player recreated while a sleep fade
        // was armed would otherwise come back AUDIBLE despite muted==true.
        try { p.setVolume(effMult()); } catch (Exception ignored) {}
        android.util.Log.i("Anancy", "attach: applied volume=" + effMult() + " (muted=" + muted + " napped=" + napped + " sleeping=" + sleeping + ")");
        count = p.getMediaItemCount();
        p.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean ip) { playing = ip; }
            // loop off: the queue ran out -> TV off, exactly like the sleep-timer end
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_ENDED && !loop) PlayerActivity.napNow(); }
        });
        ticker = new Runnable() { public void run() {
            if (p != null) try {
                playing = p.isPlaying();
                idx = p.getCurrentMediaItemIndex();
                count = p.getMediaItemCount();
                posMs = (int) p.getCurrentPosition();
                sampleClock = android.os.SystemClock.elapsedRealtime();   // stamp the position for followers to extrapolate
                long d = p.getDuration();
                durMs = d > 0 ? (int) d : 0;
                title = titleOf(p.getCurrentMediaItem());
                curId = currentLibId();   // cache on main thread; the control server never touches ExoPlayer
                noteProgress(curId);      // remember her place in this show for the next boot
            } catch (Exception ignored) {}
            main.postDelayed(this, 500);
        }};
        main.post(ticker);
    }

    private String titleOf(MediaItem m) {
        try {
            if (m != null) {
                if (m.mediaMetadata != null && m.mediaMetadata.title != null) return m.mediaMetadata.title.toString();   // client URL items
                if (m.localConfiguration != null) { String seg = m.localConfiguration.uri.getLastPathSegment(); return seg != null ? seg : ""; }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // --- commands (marshaled to main) --------------------------------------
    public void play()      { on(() -> {
        clearHolds();
        if (!sleeping) p.setVolume(effMult());   // restore the multiplier (a sleep fade may have zeroed it)
        if (p.getPlaybackState() == Player.STATE_ENDED) p.seekToDefaultPosition(0);   // loop-off ended: start over
        p.play();
    }); }
    public void pause()     { on(() -> { clearHolds(); p.pause(); }); }
    public void toggle()    { on(() -> { if (p.isPlaying()) p.pause(); else play(); }); }
    public void next()      { on(() -> { boolean h = clearHolds(); p.seekToNextMediaItem(); if (h) p.play(); }); }
    public void prev()      { on(() -> { boolean h = clearHolds(); p.seekToPreviousMediaItem(); if (h) p.play(); }); }
    public void seekNearEnd(){ on(() -> { long d = p.getDuration(); if (d > 0) p.seekTo(Math.max(0, d - 3000)); }); }
    /** Bind system audio: read the current media volume, restore the persisted mute, and make sure the
        appliance never boots stuck silent (system volume 0 -> 50%). */
    public void setAudio(Context ctx) {
        appCtx = ctx.getApplicationContext();
        app.anancy.Diag.init(appCtx);
        am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        prefs = ctx.getApplicationContext().getSharedPreferences("anancy", Context.MODE_PRIVATE);
        muted = prefs.getBoolean("muted", true);   // first run = MUTED (nap-safe default); volume-up unmutes
        vol = readSystemVol();
        // A 0 system stream makes "unmute" do nothing, so a muted TV gets a floor. Never raise it unmuted (that's
        // instant sound), and never on a phone remote: its media volume is also the driver's music.
        if (vol <= 0.001f && muted && !"remote".equals(prefs.getString("role", ""))) writeSystemVol(0.5f);
        applyMute();
    }
    private float readSystemVol() {
        try { int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); return max > 0 ? (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0f; }
        catch (Exception e) { return 0f; }
    }
    private void writeSystemVol(float v) {
        vol = clamp(v);
        try { int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vol * max), 0); }
        catch (Exception ignored) {}
    }
    /** SYSTEM media volume — the real loudness. The player multiplier stays at 1 unless muted.
     *  RAISING it while muted UNMUTES (TV-remote behaviour: the volume UI must never move while the
     *  device stays silent — that was the nap-run confusion). Lowering never unmutes. */
    public void setVolume(float v) {
        boolean up = v > vol + 0.001f;
        if (up) unmuteSystemStream();   // a hard-muted STREAM_MUSIC (some devices force it) stays silent at
                                        // any level; clear it AS WE RAISE — safe, since the level steps up from here
        writeSystemVol(v);
        if (up && muted) setMuted(false);
    }
    @SuppressWarnings("deprecation")
    private void unmuteSystemStream() {
        if (am == null) return;
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) {}
        try { am.setStreamMute(AudioManager.STREAM_MUSIC, false); } catch (Exception ignored) {}   // deprecated but clears a device-forced mute (e.g. Android 10 Lenovo)
    }
    public void nudge(float d)     { setVolume(vol + d); }
    public void setMuted(boolean m) { muted = m; try { if (prefs != null) prefs.edit().putBoolean("muted", m).apply(); } catch (Exception ignored) {} applyMute(); }
    public boolean isMuted() { return muted; }
    // Play mode's "sound source" can force the SHOW audio on/off for its duration WITHOUT touching the
    // persisted mute: null = follow the mute flag, TRUE = audible, FALSE = silent. Restored (null) on exit.
    private volatile Boolean audioOverride = null;
    public void overrideShowAudio(Boolean b) { audioOverride = b; applyMute(); }
    private float effMult() {
        if (napped) return 0f;   // a napped ("off") player is GUARANTEED silent, regardless of mute/override — nap-safe by design
        return audioOverride != null ? (audioOverride ? 1f : 0f) : (muted ? 0f : 1f);
    }
    private void applyMute() { on(() -> { if (p != null && !sleeping) p.setVolume(effMult()); }); }

    // --- sleep timer (fade volume then pause) ------------------------------
    public void startSleep(final int min) {
        on(() -> {
            cancelSleepInternal();
            if (min <= 0) return;
            sleeping = true; sleepMin = min; sleepFromVol = muted ? 0f : 1f;   // fade the PLAYER multiplier, never the system volume
            sleepEnd = System.currentTimeMillis() + min * 60000L;
            sleepTick = new Runnable() { public void run() {
                if (!sleeping) return;
                long left = sleepEnd - System.currentTimeMillis();
                if (left <= 0) {   // faded out: TV off + display off, exactly like the power button
                    try { if (p != null) p.setVolume(0f); } catch (Exception ignored) {}
                    pause(); sleeping = false; PlayerActivity.napNow(); return;
                }
                float frac = Math.max(0f, Math.min(1f, (float) left / (sleepMin * 60000f)));
                try { if (p != null) p.setVolume(sleepFromVol * frac); } catch (Exception ignored) {}
                main.postDelayed(this, 3000);
            }};
            main.post(sleepTick);
        });
    }
    public void cancelSleep() { on(() -> { cancelSleepInternal(); if (p != null) p.setVolume(effMult()); }); }
    private void cancelSleepInternal() { sleeping = false; if (sleepTick != null) main.removeCallbacks(sleepTick); sleepTick = null; }

    /** Kept under ~450 bytes: it travels in one BLE MTU (title truncated to 100 chars). */
    public String stateJson() { return stateJson(false); }

    /** @param ble trim to fit ONE notification. Adding id/name/ip/hotspot pushed this past the
        payload limit (MTU-3 = 514 at our negotiated 517), and the tail - the hotspot creds - was
        being cut, so a remote never saw them and could not auto-join the TV's hotspot. */
    public String stateJson(boolean ble) {
        JSONObject o = new JSONObject();
        try {
            String t = title;
            if (t.length() > 100) t = t.substring(0, 100);
            o.put("engine", "ExoPlayer");
            if (appCtx != null) {   // WHICH tablet this is: a remote that can hear two of them needs to tell them apart
                o.put("id", Identity.id(appCtx));
                o.put("name", Identity.name(appCtx));
            }
            o.put("playlist", currentPl);
            o.put("count", count);
            o.put("index", idx);
            o.put("title", t);
            o.put("playing", playing);
            o.put("napped", napped);
            o.put("position_ms", posMs);
            o.put("duration_ms", durMs);
            o.put("cur", curId);   // stable library id of the current item (cached by the ticker) — followers mirror it
            o.put("clock", sampleClock);   // leader's monotonic clock when position_ms was sampled (synced-playback extrapolation)
            o.put("client", clientItems != null);   // true = streams from a host (never the sync leader); false = holds the library
            o.put("volume", Math.round(vol * 100) / 100.0);
            o.put("muted", muted);
            o.put("sleeping", sleeping);
            o.put("sleep_remaining_ms", sleeping ? Math.max(0, sleepEnd - System.currentTimeMillis()) : 0);
            o.put("ip", wifiIp());   // so a remote can fetch /snapshot.jpg directly (BLE carries this; no mDNS needed)
            o.put("shuffle", shuffle);
            o.put("loop", loop);
            o.put("playmode", PlayerActivity.isPlayModeActive());   // toddler overlay up? (remotes show an on/off toggle)
            if (appCtx != null) {   // TV battery so remotes can show level + whether it's charging
                try {
                    android.os.BatteryManager bm = (android.os.BatteryManager) appCtx.getSystemService(Context.BATTERY_SERVICE);
                    int lvl = bm != null ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                    if (lvl >= 0 && lvl <= 100) o.put("battery", lvl);
                    o.put("charging", bm != null && bm.isCharging());
                } catch (Exception ignored) {}
            }
            o.put("conn", appCtx != null ? HotspotService.mode(appCtx) : "ble");
            o.put("flash_ms", appCtx != null ? Cfg.getInt(appCtx, "anancy_flash_ms", 3000) : 3000);
            if (HotspotService.running() && HotspotService.currentSsid() != null)   // only while the TV hotspot is up
                o.put("hotspot", new JSONObject().put("ssid", HotspotService.currentSsid()).put("pass", HotspotService.currentPass() == null ? "" : HotspotService.currentPass()));
        } catch (Exception ignored) {}
        return ble ? fitOneNotification(o) : o.toString();
    }

    private static final int BLE_STATE_MAX = 460;   // headroom for smaller MTUs (the watch)
    private static int blen(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
    /** Shed cosmetics, then clamp the title, until the state fits one BLE notification. */
    private static String fitOneNotification(JSONObject o) {
        String s = o.toString();
        if (blen(s) <= BLE_STATE_MAX) return s;
        // NOT "playlist": the remote reads it to show which playlist is current, and without it the UI
        // silently falls back to "Shuffle All" - every label then disagrees with what was pressed.
        for (String k : new String[]{"engine", "flash_ms", "charging", "battery"}) {
            o.remove(k);
            s = o.toString();
            if (blen(s) <= BLE_STATE_MAX) return s;
        }
        for (int cap = 48; cap >= 12; cap -= 12) {
            String t = o.optString("title", "");
            if (t.length() > cap) { try { o.put("title", t.substring(0, cap)); } catch (Exception ignored) {} }
            s = o.toString();
            if (blen(s) <= BLE_STATE_MAX) return s;
        }
        o.remove("playlist");   // last resort only, after the title has been clamped right down
        s = o.toString();
        return s;   // still over: a cut tail beats sending nothing
    }

    /** This device's site-local IPv4 (e.g. 192.168.x) so a remote can reach the control server
        without relying on mDNS resolution (which proved flaky). "" if not on a LAN. */
    public static String wifiIp() {
        // Prefer the Wi-Fi / hotspot interfaces. A phone on mobile data can hold a site-local carrier
        // address (10.x on rmnet) that the other screens can't reach, and it may enumerate first.
        String fallback = "", wifi = "";
        boolean hosting = app.anancy.net.HotspotService.running();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("tun") || n.startsWith("dummy")) continue;
                boolean apLike = n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap");
                boolean wifiLike = apLike || n.startsWith("wlan") || n.startsWith("eth");
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        // While this phone hosts, the screens are on ITS hotspot, so that's the address
                        // they can reach. In the car the phone also holds Android Auto's Wi-Fi link
                        // (another wlan address) that the screens can never see.
                        if (apLike && hosting) return a.getHostAddress();
                        if (wifiLike && wifi.isEmpty()) wifi = a.getHostAddress();
                        if (fallback.isEmpty()) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return !wifi.isEmpty() ? wifi : fallback;
    }

    private void on(Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r); }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    void detach() {
        main.removeCallbacksAndMessages(null);   // this kills sleepTick...
        sleeping = false; sleepTick = null;      // ...so the flag must not outlive it - it gates keepMute()/applyMute()
        napped = false;                          // a stale 'napped' pins effMult() to 0 and the next player is silent forever
        p = null;
    }
}
