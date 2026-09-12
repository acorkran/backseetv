package com.kidstv.kidodi;

// ============================================================================
//  Android Auto MEDIA-app probe. Media apps are the surface that actually shows
//  in the car while driving (unlike the IOT template path). This one exists to
//  MEASURE the dash, not to control anything:
//    * it stamps a frame counter + wall-clock time onto the album art AND the
//      title, refreshed every 2s -> in the car, how fast each number ticks is
//      the real art-refresh vs text-refresh cadence (parked and driving);
//    * it advertises 5 custom actions (1..5) -> the car reveals how many the
//      media UI actually renders (we believe ~4);
//    * position advances so the progress bar (the "free" live timer) is visible.
//  No audio is ever produced (nap-safe by construction).
// ============================================================================

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

public class MediaProbeService extends MediaBrowserServiceCompat {
    private static final String ROOT = "probe_root", ITEM = "probe_item";
    private static final long TICK_MS = 2000, FAKE_DURATION_MS = 600_000;   // 10-min fake track so it never "ends"

    private MediaSessionCompat session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int frame = 0;
    private long startedAt = 0, lastTickWall = 0;
    private boolean playing = false;

    @Override public void onCreate() {
        super.onCreate();
        session = new MediaSessionCompat(this, "KidodiMediaProbe");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { start(); }
            @Override public void onPause() { stop(true); }
            @Override public void onStop() { stop(false); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { start(); }
            @Override public void onSkipToNext() { frame += 10; pushNow(); }        // jump the counter so a skip is visible
            @Override public void onSkipToPrevious() { frame = Math.max(0, frame - 10); pushNow(); }
            @Override public void onCustomAction(String action, Bundle extras) { /* markers: no-op, we just want them rendered */ }
        });
        setSessionToken(session.getSessionToken());
        publish(PlaybackStateCompat.STATE_PAUSED);   // paused so tapping the item shows now-playing, then Play starts the ticker
    }

    // Any caller (incl. Android Auto) may browse. This is a local measurement tool, not sensitive.
    @Override public BrowserRoot onGetRoot(@NonNull String pkg, int uid, @Nullable Bundle hints) { return new BrowserRoot(ROOT, null); }

    @Override public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        List<MediaItem> out = new ArrayList<>();
        if (ROOT.equals(parentId)) {
            MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                    .setMediaId(ITEM).setTitle("Refresh probe").setSubtitle("tap → Play to start the counter").build();
            out.add(new MediaItem(d, MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(out);
    }

    private void start() {
        if (!playing) { playing = true; startedAt = SystemClock.elapsedRealtime(); }
        session.setActive(true);
        main.removeCallbacks(tick);
        main.post(tick);
    }
    private void stop(boolean paused) {
        playing = false; main.removeCallbacks(tick);
        publish(paused ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED);
    }
    private final Runnable tick = new Runnable() { public void run() {
        if (!playing) return;
        frame++; pushNow();
        main.postDelayed(this, TICK_MS);
    }};

    private void pushNow() {
        long now = System.currentTimeMillis();
        int sinceLast = lastTickWall == 0 ? 0 : (int) ((now - lastTickWall) / 1000);
        lastTickWall = now;
        String clock = DateFormat.format("HH:mm:ss", now).toString();
        // METADATA: stamped art + title/subtitle carrying the same counter, so art vs text cadence is comparable in-car
        MediaMetadataCompat md = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Probe #" + frame)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Probe #" + frame)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "art stamp " + clock)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "art stamp " + clock)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "+" + sinceLast + "s between pushes")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, FAKE_DURATION_MS)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, stamp(frame, clock))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, stamp(frame, clock))
                .build();
        session.setMetadata(md);
        publish(PlaybackStateCompat.STATE_PLAYING);
    }

    private void publish(int state) {
        long pos = playing ? (SystemClock.elapsedRealtime() - startedAt) % FAKE_DURATION_MS : 0;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(state, pos, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f);
        // 5 custom actions labelled 1..5 -> the car shows however many it allows (we expect ~4)
        for (int i = 1; i <= 5; i++)
            b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder("marker_" + i, "Marker " + i, R.drawable.probe_dot).build());
        session.setPlaybackState(b.build());
    }

    /** 512² tile: giant frame number + wall-clock, so the eye can time the refresh from across the cabin. */
    private Bitmap stamp(int n, String clock) {
        int S = 512;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF101418);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF8FD694); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(300); c.drawText(String.valueOf(n), S / 2f, S / 2f + 90, p);
        p.setColor(Color.WHITE); p.setTextSize(72); c.drawText(clock, S / 2f, S - 60, p);
        p.setColor(0xFF9AA4B2); p.setTextSize(44); c.drawText("art refresh probe", S / 2f, 90, p);
        return bmp;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        try { session.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
