package com.kidstv.kidodi.player;

// ============================================================================
//  JS -> native audio for the play-mode WebView. The bundled toddler-lock web app
//  would otherwise play its clips through a WebView <audio> element, which rides
//  the raw SYSTEM media volume — ungovernable by the kiosk (breaks our audio rule).
//  So app.js routes every clip here instead: we play the bundled Piper WAV via
//  SoundPool at an APP-CONTROLLED gain. Gain DEFAULTS TO 0 (muted): nothing can
//  blast until the kiosk explicitly raises it. Never the OS TTS voice.
//  Clips: assets/toddlerlock/audio/<voice>/<key>.wav.
// ============================================================================

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WebAudioBridge {
    static final String TAG = "Kidodi";

    private static volatile float gain = 0f;   // 0..1, DEFAULT MUTED
    /** Kiosk-governed gain for the playground clips (0 = silent). */
    public static void setGain(float g) { gain = Math.max(0f, Math.min(1f, g)); }
    public static float getGain() { return gain; }

    private final Context ctx;
    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();   // asset path -> soundId
    private final Set<Integer> ready = new HashSet<>();          // loaded soundIds
    private final Set<Integer> pending = new HashSet<>();        // asked to play before load finished

    WebAudioBridge(Context c) {
        ctx = c.getApplicationContext();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status != 0) return;
            synchronized (WebAudioBridge.this) {
                ready.add(sampleId);
                if (pending.remove(sampleId) && gain > 0f) sp.play(sampleId, gain, gain, 1, 0, 1f);
            }
        });
    }

    @JavascriptInterface public void play(String voice, String key) {
        if (gain <= 0f) return;                        // muted -> truly silent
        if (voice == null || key == null) return;
        String path = "toddlerlock/audio/" + safe(voice) + "/" + safe(key) + ".wav";
        synchronized (this) {
            Integer id = ids.get(path);
            if (id != null) {
                if (ready.contains(id)) pool.play(id, gain, gain, 1, 0, 1f);
                else pending.add(id);                  // still loading -> play on complete
                return;
            }
            try {
                AssetFileDescriptor afd = ctx.getAssets().openFd(path);
                int sid = pool.load(afd, 1);
                afd.close();
                ids.put(path, sid);
                pending.add(sid);                      // play as soon as it's loaded
            } catch (Exception e) {
                if (!"cori".equals(voice)) play("cori", key);   // fall back to the HQ default voice
                else Log.w(TAG, "playmode clip missing: " + path);
            }
        }
    }

    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9_]", ""); }
}
