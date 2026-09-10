package com.kidstv.kidodi;

// ============================================================================
//  Wraps the ExoPlayer so anything (HTTP control, BLE, remote) can drive it
//  safely. ExoPlayer is single-threaded (main looper), so every command is
//  marshaled to main; reads come from a volatile snapshot refreshed on main by a
//  ticker + player listener — so the control server never touches ExoPlayer off
//  the main thread. Volume starts at 0 (muted) to match the player default.
// ============================================================================

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlayerController {
    private static PlayerController I;
    public static synchronized PlayerController get() { if (I == null) I = new PlayerController(); return I; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer p;

    // volatile snapshot (written on main, read by the HTTP/BLE threads)
    private volatile int idx, count, posMs, durMs;
    private volatile boolean playing;
    private volatile float vol = 0f;   // muted default
    private volatile String title = "";
    private volatile boolean napped = false;   // "powered off" = paused + TV blacked
    private final LinkedHashMap<String, List<MediaItem>> playlists = new LinkedHashMap<>();
    private volatile String currentPl = "Shuffle All";

    public void setNapped(boolean n) { napped = n; }

    /** Scan the library (off-main), build playlists (Shuffle All + one per show), start Shuffle All. */
    public void loadLibrary(Context ctx) { loadLibrary(ctx, true, 0); }
    /** Rebuild playlists after an import, WITHOUT disrupting current playback. */
    public void rescan(Context ctx) { loadLibrary(ctx, false, 0); }
    private void loadLibrary(final Context ctx, final boolean autoStart, final int attempt) {
        new Thread(() -> {
            LinkedHashMap<String, List<File>> shows = Library.scanShows(ctx);
            final LinkedHashMap<String, List<MediaItem>> built = new LinkedHashMap<>();
            final List<MediaItem> all = new ArrayList<>();
            for (Map.Entry<String, List<File>> e : shows.entrySet()) {
                List<MediaItem> items = new ArrayList<>();
                for (File f : e.getValue()) { MediaItem mi = MediaItem.fromUri(Uri.fromFile(f)); items.add(mi); all.add(mi); }
                built.put(e.getKey(), items);
            }
            // Boot race: the portable SD card can take tens of seconds to mount after boot, so a
            // scan on launch may find nothing. Retry until content appears (~90s) before giving up.
            if (all.isEmpty() && autoStart && attempt < 30) {
                android.util.Log.i("Kidodi", "library empty (attempt " + attempt + "); SD may still be mounting, retry in 3s");
                main.postDelayed(() -> loadLibrary(ctx, true, attempt + 1), 3000);
                return;
            }
            on(() -> {
                playlists.clear();
                playlists.put("Shuffle All", all);
                playlists.putAll(built);
                if (autoStart) selectPlaylist("Shuffle All");
            });
        }).start();
    }

    /** Switch to a playlist by name: Shuffle All -> shuffled; a show -> in episode order. */
    public void selectPlaylist(final String name) {
        on(() -> {
            List<MediaItem> items = playlists.get(name);
            if (items == null || items.isEmpty() || p == null) return;
            currentPl = name;
            boolean shuffle = "Shuffle All".equals(name);
            p.setShuffleModeEnabled(shuffle);
            p.setRepeatMode(Player.REPEAT_MODE_ALL);
            p.setMediaItems(items);
            p.prepare();
            p.play();
        });
    }

    /** Playlist names as a one-shot payload (BLE "list" / HTTP /playlists). */
    public String playlistsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "playlists");
            JSONArray a = new JSONArray();
            for (String n : playlists.keySet()) a.put(n);
            o.put("names", a);
        } catch (Exception ignored) {}
        return o.toString();
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
        count = p.getMediaItemCount();
        p.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean ip) { playing = ip; }
        });
        ticker = new Runnable() { public void run() {
            if (p != null) try {
                playing = p.isPlaying();
                idx = p.getCurrentMediaItemIndex();
                count = p.getMediaItemCount();
                posMs = (int) p.getCurrentPosition();
                long d = p.getDuration();
                durMs = d > 0 ? (int) d : 0;
                title = titleOf(p.getCurrentMediaItem());
            } catch (Exception ignored) {}
            main.postDelayed(this, 500);
        }};
        main.post(ticker);
    }

    private String titleOf(MediaItem m) {
        try { if (m != null && m.localConfiguration != null) {
            String seg = m.localConfiguration.uri.getLastPathSegment();
            return seg != null ? seg : "";
        }} catch (Exception ignored) {}
        return "";
    }

    // --- commands (marshaled to main) --------------------------------------
    public void play()      { on(() -> p.play()); }
    public void pause()     { on(() -> p.pause()); }
    public void toggle()    { on(() -> { if (p.isPlaying()) p.pause(); else p.play(); }); }
    public void next()      { on(() -> p.seekToNextMediaItem()); }
    public void prev()      { on(() -> p.seekToPreviousMediaItem()); }
    public void seekNearEnd(){ on(() -> { long d = p.getDuration(); if (d > 0) p.seekTo(Math.max(0, d - 3000)); }); }
    public void setVolume(float v) { on(() -> { vol = clamp(v); p.setVolume(vol); }); }
    public void nudge(float d)     { setVolume(vol + d); }

    // --- sleep timer (fade volume then pause) ------------------------------
    public void startSleep(final int min) {
        on(() -> {
            cancelSleepInternal();
            if (min <= 0) return;
            sleeping = true; sleepMin = min; sleepFromVol = vol;
            sleepEnd = System.currentTimeMillis() + min * 60000L;
            sleepTick = new Runnable() { public void run() {
                if (!sleeping) return;
                long left = sleepEnd - System.currentTimeMillis();
                if (left <= 0) { setVolume(0f); pause(); sleeping = false; return; }
                float frac = Math.max(0f, Math.min(1f, (float) left / (sleepMin * 60000f)));
                try { if (p != null) p.setVolume(sleepFromVol * frac); } catch (Exception ignored) {}
                main.postDelayed(this, 3000);
            }};
            main.post(sleepTick);
        });
    }
    public void cancelSleep() { on(() -> { cancelSleepInternal(); setVolume(sleepFromVol); }); }
    private void cancelSleepInternal() { sleeping = false; if (sleepTick != null) main.removeCallbacks(sleepTick); sleepTick = null; }

    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("engine", "ExoPlayer");
            o.put("playlist", currentPl);
            o.put("count", count);
            o.put("index", idx);
            o.put("title", title);
            o.put("playing", playing);
            o.put("napped", napped);
            o.put("position_ms", posMs);
            o.put("duration_ms", durMs);
            o.put("volume", Math.round(vol * 100) / 100.0);
            o.put("sleeping", sleeping);
            o.put("sleep_remaining_ms", sleeping ? Math.max(0, sleepEnd - System.currentTimeMillis()) : 0);
            o.put("ip", wifiIp());   // so a remote can fetch /snapshot.jpg directly (BLE carries this; no mDNS needed)
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** This device's site-local IPv4 (e.g. 192.168.x) so a remote can reach the control server
        without relying on mDNS resolution (which proved flaky). "" if not on a LAN. */
    static String wifiIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void on(Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r); }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    void detach() { main.removeCallbacksAndMessages(null); p = null; }
}
