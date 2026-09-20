package app.anancy.player;

// ============================================================================
//  Toddler "play mode" entry points. The playground itself is now a TRANSPARENT
//  WebView overlay hosted INSIDE PlayerActivity (the running show is the visible
//  background) — see PlayerActivity.startPlayMode()/stopPlayMode(). This class is
//  kept only as the stable API the control server / BLE / Settings already call.
//  (Named *Activity for back-compat; it is no longer an Activity.)
//  Audio is native via WebAudioBridge at an app-controlled gain (default 0 = muted).
// ============================================================================

import android.content.Context;

public final class PlayModeActivity {
    private PlayModeActivity() {}

    /** Start play mode (transparent overlay over the show). Safe from any context. */
    public static void begin(Context ctx) { PlayerActivity.startPlayMode(); }
    /** Exit play mode, back to the plain show. */
    public static void end() { PlayerActivity.stopPlayMode(); }
    /** Kiosk-governed playground audio gain (0 = muted). */
    public static void setGain(float g) { WebAudioBridge.setGain(g); }
}
