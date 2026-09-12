package com.kidstv.aatest;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/** Media-surface entry point (the AA1-Media flavor). If this shows in the car, the media path works. */
public class HelloMediaService extends MediaBrowserServiceCompat {
    private static final String ROOT = "root", ITEM = "item";
    private MediaSessionCompat session;

    @Override public void onCreate() {
        super.onCreate();
        session = new MediaSessionCompat(this, "AAtestMedia");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { publish(PlaybackStateCompat.STATE_PLAYING); }
            @Override public void onPause() { publish(PlaybackStateCompat.STATE_PAUSED); }
            @Override public void onStop() { publish(PlaybackStateCompat.STATE_STOPPED); }
            @Override public void onPlayFromMediaId(String id, Bundle extras) { publish(PlaybackStateCompat.STATE_PLAYING); }
        });
        setSessionToken(session.getSessionToken());
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, label + " works")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, label + " works")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "media surface probe").build());
        publish(PlaybackStateCompat.STATE_PAUSED);
    }

    @Override public BrowserRoot onGetRoot(@NonNull String pkg, int uid, @Nullable Bundle hints) { return new BrowserRoot(ROOT, null); }

    @Override public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        List<MediaItem> out = new ArrayList<>();
        if (ROOT.equals(parentId)) {
            CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
            out.add(new MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId(ITEM).setTitle(label + " item").setSubtitle("tap to play").build(), MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(out);
    }

    private void publish(int state) {
        session.setActive(state != PlaybackStateCompat.STATE_STOPPED);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(state, 0, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f).build());
    }

    @Override public void onDestroy() { try { session.release(); } catch (Exception ignored) {} super.onDestroy(); }
}
