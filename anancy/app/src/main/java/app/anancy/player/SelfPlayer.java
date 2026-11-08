package app.anancy.player;

// ============================================================================
//  "This phone as playback": the remote is also one of the screens.
//
//  Here the engine belongs to the app rather than to a fullscreen activity, so
//  ticking the box doesn't take the remote away. The video draws in this
//  device's card in the remote; "make large" hands the same running player to
//  PlayerActivity (locks and all), and leaving fullscreen hands it back.
// ============================================================================

import android.content.Context;
import android.content.Intent;
import android.view.TextureView;

import androidx.media3.exoplayer.ExoPlayer;

import app.anancy.Cfg;

public final class SelfPlayer {
    private static ExoPlayer player;
    private static Context app;
    private static TextureView small;           // the card, while the remote is on screen
    private static boolean pausedByLeaving;

    private SelfPlayer() {}

    public static boolean running() { return player != null; }
    /** Width/height of the current video, or 0 before one has loaded. */
    public static float videoAspect() {
        if (player == null) return 0;
        androidx.media3.common.VideoSize v = player.getVideoSize();
        return v.width > 0 && v.height > 0 ? v.width * v.pixelWidthHeightRatio / v.height : 0;
    }
    static ExoPlayer player() { return player; }

    /** Main thread. Builds the engine and starts answering as a TV. */
    public static void start(Context ctx) {
        if (player != null) return;
        app = ctx.getApplicationContext();
        player = new ExoPlayer.Builder(app).build();
        PlayerController c = PlayerController.get();
        c.setAudio(app);        // before attach: it loads the persisted mute, so there's no audible window
        c.attach(player);
        c.onLibraryLoaded = null;
        // With no synced group armed, a follower setting left over from earlier would tie this device
        // to another screen: Play here would snap back to whatever that screen is doing.
        if ("".equals(Cfg.p(app).getString("anancy_syncgroup", ""))) {
            Cfg.put(app, "anancy_sync", 0);
            Cfg.put(app, "anancy_host", "");
        }
        c.loadLibrary(app);
        PlayerActivity.startServers(app);
        if (small != null) player.setVideoTextureView(small);
    }

    /** Main thread. The box was unticked. */
    public static void stop() {
        if (player == null) return;
        PlayerActivity.finishLarge();
        PlayerActivity.stopServers();
        PlayerController.get().detach();
        player.release();
        player = null;
        pausedByLeaving = false;
    }

    /** The remote came on screen with this card. Picks the show up again if leaving paused it. */
    public static void showIn(TextureView view) {
        small = view;
        if (player == null) return;
        player.setVideoTextureView(view);
        if (pausedByLeaving) { pausedByLeaving = false; PlayerController.get().play(); }
    }

    /** The remote left the screen: pause like any video app, unless it's because the show went large. */
    public static void hideFrom(TextureView view) {
        if (small == view) small = null;
        if (player == null) return;
        player.clearVideoTextureView(view);
        if (!PlayerActivity.isRunning() && player.getPlayWhenReady()) {
            pausedByLeaving = true;
            PlayerController.get().pause();
        }
    }

    public static void openLarge(Context ctx) { open(ctx, false, false); }

    /** A remote or watch pressed Play with nowhere showing the video: bring the show up. */
    static void needScreen() {
        if (player != null && small == null && !PlayerActivity.isRunning()) open(app, true, false);
    }
    static void openForPlayMode() { if (player != null) open(app, true, true); }

    private static void open(Context ctx, boolean wake, boolean playMode) {
        try {
            ctx.startActivity(new Intent(ctx, PlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("wake", wake).putExtra("playmode", playMode));
        } catch (Exception e) { android.util.Log.w("Anancy", "couldn't open this device's player: " + e); }
    }
}
