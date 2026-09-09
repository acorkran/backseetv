package com.kidstv.kidodi;

// ============================================================================
//  Android Auto surface. A MediaBrowserService whose MediaSession mirrors the
//  tablet-player's state (title/episode + play/pause) and whose transport +
//  custom actions (Light / Sleep / Power) drive the tablet through RemoteService
//  (BLE primary, Wi-Fi fallback). The head unit renders the now-playing + buttons.
// ============================================================================

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.media.MediaBrowserServiceCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CarMediaService extends MediaBrowserServiceCompat {
    private MediaSessionCompat session;

    @Override public void onCreate() {
        super.onCreate();
        RemoteService.ensure(this);

        session = new MediaSessionCompat(this, "Kidodi");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(callback);
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        applyState("{}");

        // reflect live tablet state onto the session
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(new RemoteService.Listener() {
            public void onStatus(String s) {}
            public void onState(String json) { applyState(json); }
        });
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {
        @Override public void onPlay() { send("play"); }
        @Override public void onPause() { send("pause"); }
        @Override public void onSkipToNext() { send("next"); }
        @Override public void onSkipToPrevious() { send("prev"); }
        @Override public void onStop() { send("power"); }
        @Override public void onCustomAction(String action, Bundle extras) {
            if ("flash".equals(action)) send("flash");
            else if ("sleep".equals(action)) send("sleep:15");
            else if ("power".equals(action)) send("power");
        }
    };

    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }

    private void applyState(String json) {
        boolean playing = false;
        String show = "Kidodi", ep = "";
        try {
            JSONObject o = new JSONObject(json);
            playing = o.optBoolean("playing", false);
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            show = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
            ep = (i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim();
            if (show.isEmpty()) show = "Kidodi";
        } catch (Exception ignored) {}

        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, show)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ep)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, ep)
                .build());

        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .addCustomAction("flash", "Light", android.R.drawable.star_on)
                .addCustomAction("sleep", "Sleep 15m", android.R.drawable.ic_menu_recent_history)
                .addCustomAction("power", "Power", android.R.drawable.ic_lock_power_off)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build();
        session.setPlaybackState(state);
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("root", null);
    }
    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(new ArrayList<>());   // a control surface, not a browse tree
    }

    @Override public void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }
}
