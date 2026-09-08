package com.kidstv.player;

// ============================================================================
//  The heart of the v2 own-player: it owns the whole media lifecycle in-process.
//  No Kodi, no JSON-RPC, no watchdog -- because WE fire "advance" on completion
//  (and on error), the "stuck at EOF / played past the end" wedge that plagued
//  Kodi simply cannot occur here: onCompletion -> next(), deterministically.
//
//  Prototype engine = platform MediaPlayer (offline-buildable). Production swaps
//  in ExoPlayer/Media3 behind this same surface with no architecture change.
// ============================================================================

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class PlayerEngine {
    static final String TAG = "KidodiPlayer";
    private static PlayerEngine sInst;
    public static synchronized PlayerEngine get(Context c) {
        if (sInst == null) sInst = new PlayerEngine(c.getApplicationContext());
        return sInst;
    }

    private final Context mApp;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<File> mList = new ArrayList<>();
    private int mIdx = 0;
    private MediaPlayer mMp;
    private SurfaceHolder mHolder;
    private boolean mPrepared = false;
    private boolean mWantPlay = true;      // desired play state (survives track changes)
    // Default MUTED. This prototype gets installed alongside the live kiosk, whose volume/power
    // keys only control Kodi -- so a second app at full volume is an ungovernable noise source.
    // Ship it silent; sound only ever happens via a deliberate /vol command (never in a test).
    // See memory: no-audio-or-strobe-when-testing.
    private float mVol = 0.0f;             // 0..1 software volume
    private int mConsecErrors = 0;

    // sleep-timer (fade to silence over N minutes, then pause) -- all in-process
    private boolean mSleeping = false;
    private long mSleepEndMs = 0;
    private float mSleepFromVol = 1.0f;
    private Runnable mSleepTick;

    private PlayerEngine(Context app) { mApp = app; }

    // --- library ------------------------------------------------------------

    /** Scan every /storage/<vol>/KidsTV tree for playable files and shuffle. */
    public synchronized void scanAndShuffle() {
        mList.clear();
        for (File root : libraryRoots()) if (root.isDirectory()) collect(root, mList);
        Collections.shuffle(mList);
        Log.i(TAG, "library: " + mList.size() + " files");
        mIdx = 0;
        maybeStart();
    }

    /** Candidate "KidsTV" roots across primary + removable volumes (portable, no hardcoded UUID). */
    private List<File> libraryRoots() {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        try {
            StorageManager sm = (StorageManager) mApp.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null) roots.add(new File(d, "KidsTV"));
            }
        } catch (Exception ignored) {}
        try { roots.add(new File(Environment.getExternalStorageDirectory(), "KidsTV")); } catch (Exception ignored) {}
        try { File[] vs = new File("/storage").listFiles(); if (vs != null) for (File v : vs) roots.add(new File(v, "KidsTV")); } catch (Exception ignored) {}
        return new ArrayList<>(roots);
    }

    /** Start playback as soon as the library is known (the show IS the home screen). */
    private void maybeStart() { onMain(new Runnable(){ public void run(){ if (mMp == null && size() > 0) load(mIdx); } }); }

    /** Headless bring-up for testing without the foreground activity (audio only, no surface). */
    public void startHeadless() {
        new Thread(new Runnable(){ public void run(){ if (size() == 0) scanAndShuffle(); else maybeStart(); } }).start();
    }

    /** Jump to ~3s before the end to exercise the auto-advance (onCompletion -> next) on demand. */
    public void seekNearEnd() {
        onMain(new Runnable(){ public void run(){ try {
            if (mPrepared && mMp != null) { int d = mMp.getDuration(); mMp.seekTo(Math.max(0, d - 3000)); if (mWantPlay) mMp.start(); }
        } catch (Exception ignored) {} } });
    }

    private void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collect(f, out);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".m4v")) out.add(f);
            }
        }
    }

    public synchronized int size() { return mList.size(); }

    /** Diagnostic: which candidate library roots can THIS app's process actually read? */
    public String diagJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (File root : libraryRoots()) {
                JSONObject ro = new JSONObject();
                ro.put("path", root.getAbsolutePath());
                ro.put("exists", root.exists());
                ro.put("canread", root.canRead());
                File[] kk = root.listFiles();
                ro.put("children", kk == null ? -1 : kk.length);
                arr.put(ro);
            }
            o.put("roots", arr);
        } catch (Exception e) { try { o.put("err", e.toString()); } catch (Exception ignored) {} }
        return o.toString();
    }

    // --- surface lifecycle (from PlayerActivity) ----------------------------

    public void attachSurface(SurfaceHolder h) {
        mHolder = h;
        if (mMp != null) { try { mMp.setDisplay(h); } catch (Exception ignored) {} }
        else maybeStart();
    }
    public void detachSurface() {
        mHolder = null;
        try { if (mMp != null) mMp.setDisplay(null); } catch (Exception ignored) {}
    }

    // --- playback core ------------------------------------------------------

    private void load(final int i) {
        if (size() == 0) return;
        release();
        mIdx = ((i % size()) + size()) % size();
        final File f = mList.get(mIdx);
        try {
            mMp = new MediaPlayer();
            mMp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMp.setDataSource(f.getAbsolutePath());
            if (mHolder != null) mMp.setDisplay(mHolder);
            mMp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    mPrepared = true; mConsecErrors = 0;
                    p.setVolume(mVol, mVol);
                    if (mWantPlay) p.start();
                    Log.i(TAG, "playing [" + mIdx + "/" + size() + "] " + f.getName());
                }
            });
            // THE KEY LINE: we own end-of-media -> advance. No stuck-at-100% possible.
            mMp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) { next(); }
            });
            mMp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    Log.w(TAG, "error what=" + what + " extra=" + extra + " on " + f.getName());
                    mConsecErrors++;
                    if (mConsecErrors <= Math.max(3, size())) mMain.postDelayed(new Runnable() {
                        @Override public void run() { next(); }
                    }, 400);
                    return true;   // handled -> onCompletion won't also fire
                }
            });
            mPrepared = false;
            mMp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "load failed: " + f.getName(), e);
            mConsecErrors++;
            if (mConsecErrors <= Math.max(3, size())) mMain.postDelayed(new Runnable() {
                @Override public void run() { next(); }
            }, 400);
        }
    }

    private void release() {
        mPrepared = false;
        if (mMp != null) { try { mMp.reset(); mMp.release(); } catch (Exception ignored) {} mMp = null; }
    }

    // All public controls hop to the main thread so MediaPlayer stays single-threaded.
    public void next()       { onMain(new Runnable(){ public void run(){ mWantPlay = true; load(mIdx + 1); } }); }
    public void prev()       { onMain(new Runnable(){ public void run(){ mWantPlay = true; load(mIdx - 1); } }); }
    public void play()       { onMain(new Runnable(){ public void run(){ mWantPlay = true; if (mPrepared && mMp != null) mMp.start(); } }); }
    public void pause()      { onMain(new Runnable(){ public void run(){ mWantPlay = false; try { if (mPrepared && mMp != null && mMp.isPlaying()) mMp.pause(); } catch (Exception ignored) {} } }); }
    public void togglePlay() { onMain(new Runnable(){ public void run(){ boolean playing = false; try { playing = mMp != null && mMp.isPlaying(); } catch (Exception ignored) {} if (playing) pause(); else play(); } }); }

    public void nudgeVolume(final float d) { setVolume(mVol + d); }
    public void setVolume(final float v) {
        onMain(new Runnable(){ public void run(){
            mVol = Math.max(0f, Math.min(1f, v));
            try { if (mMp != null) mMp.setVolume(mVol, mVol); } catch (Exception ignored) {}
        }});
    }

    private void onMain(Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else mMain.post(r); }

    // --- sleep timer (in-process fade + pause) ------------------------------

    public void startSleep(final int minutes) {
        onMain(new Runnable(){ public void run(){
            cancelSleepInternal();
            if (minutes <= 0) return;
            mSleeping = true;
            mSleepFromVol = mVol;
            mSleepEndMs = System.currentTimeMillis() + minutes * 60_000L;
            mSleepTick = new Runnable(){ public void run(){
                if (!mSleeping) return;
                long left = mSleepEndMs - System.currentTimeMillis();
                if (left <= 0) { setVolume(0f); pause(); mSleeping = false; return; }
                long total = mSleepEndMs - (mSleepEndMs - minutes * 60_000L);
                float frac = Math.max(0f, Math.min(1f, (float) left / (float) total));
                try { if (mMp != null) mMp.setVolume(mSleepFromVol * frac, mSleepFromVol * frac); } catch (Exception ignored) {}
                mMain.postDelayed(mSleepTick, 3000);
            }};
            mMain.post(mSleepTick);
            Log.i(TAG, "sleep timer: " + minutes + " min");
        }});
    }
    public void cancelSleep() { onMain(new Runnable(){ public void run(){ cancelSleepInternal(); setVolume(mSleepFromVol); } }); }
    private void cancelSleepInternal() {
        mSleeping = false;
        if (mSleepTick != null) mMain.removeCallbacks(mSleepTick);
        mSleepTick = null;
    }

    // --- state for /state ---------------------------------------------------

    public synchronized String stateJson() {
        JSONObject o = new JSONObject();
        try {
            boolean playing = false; int pos = 0, dur = 0;
            try { playing = mMp != null && mMp.isPlaying(); } catch (Exception ignored) {}
            try { if (mPrepared && mMp != null) { pos = mMp.getCurrentPosition(); dur = mMp.getDuration(); } } catch (Exception ignored) {}
            o.put("engine", "MediaPlayer");
            o.put("count", mList.size());
            o.put("index", mIdx);
            o.put("title", size() > 0 ? mList.get(mIdx).getName() : "");
            o.put("playing", playing);
            o.put("position_ms", pos);
            o.put("duration_ms", dur);
            o.put("volume", Math.round(mVol * 100) / 100.0);
            o.put("sleeping", mSleeping);
            o.put("sleep_remaining_ms", mSleeping ? Math.max(0, mSleepEndMs - System.currentTimeMillis()) : 0);
        } catch (Exception ignored) {}
        return o.toString();
    }
}
