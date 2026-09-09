package com.kidstv.kidodi;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.media3.common.MediaItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Finds the local kid-show library and turns it into a shuffled ExoPlayer playlist. */
public final class Library {
    static final String TAG = "Kidodi";
    private Library() {}

    public static List<MediaItem> scan(Context ctx) {
        List<MediaItem> items = new ArrayList<>();
        for (File f : scanFiles(ctx)) items.add(MediaItem.fromUri(Uri.fromFile(f)));
        return items;
    }

    public static List<File> scanFiles(Context ctx) {
        List<File> out = new ArrayList<>();
        for (File root : roots(ctx)) if (root.isDirectory()) collect(root, out);
        Collections.shuffle(out);
        Log.i(TAG, "library: " + out.size() + " files");
        return out;
    }

    /** Candidate "KidsTV" roots across primary + removable volumes (portable, no hardcoded UUID). */
    private static List<File> roots(Context ctx) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null) roots.add(new File(d, "KidsTV"));
            }
        } catch (Exception ignored) {}
        try { roots.add(new File(Environment.getExternalStorageDirectory(), "KidsTV")); } catch (Exception ignored) {}
        try { File[] vs = new File("/storage").listFiles(); if (vs != null) for (File v : vs) roots.add(new File(v, "KidsTV")); } catch (Exception ignored) {}
        return new ArrayList<>(roots);
    }

    private static void collect(File dir, List<File> out) {
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
}
