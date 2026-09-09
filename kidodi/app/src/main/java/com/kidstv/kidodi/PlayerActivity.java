package com.kidstv.kidodi;

// ============================================================================
//  Kidodi v2 player core. Own ExoPlayer engine, no Kodi, no bridge. ExoPlayer's
//  native playlist does the work that used to need a watchdog: setMediaItems() +
//  shuffle + REPEAT_MODE_ALL = infinite shuffle-all that auto-advances at each
//  file's real end. The "stuck past EOF / played to 100% and froze" Kodi wedge
//  cannot occur here — end-of-media -> next is the engine's own guarantee.
//  Starts MUTED (audio-safety rule); sound only ever via an explicit change.
// ============================================================================

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;

public class PlayerActivity extends Activity {
    static final String TAG = "Kidodi";
    private ExoPlayer player;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        SurfaceView surface = new SurfaceView(this);
        setContentView(surface);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        player.setVolume(0f);   // MUTED by default — sound only via a later explicit change

        List<MediaItem> items = Library.scan(this);
        player.setMediaItems(items);
        player.setShuffleModeEnabled(true);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);   // shuffle-all, auto-advancing, forever
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                Log.i(TAG, "now playing " + (player.getCurrentMediaItemIndex() + 1)
                        + "/" + player.getMediaItemCount());
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) Log.i(TAG, "STATE_READY (playing muted)");
            }
        });
        player.prepare();
        player.play();
        Log.i(TAG, "player started: items=" + items.size() + " muted=true shuffle=true repeatAll=true");
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
