package io.anancy.player;

import io.anancy.admin.AdminReceiver;

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
    static final String TAG = "Anancy";
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

    /** Group the library by SHOW (the top-level folder under Anancy) -> each show is a natural
     *  playlist. Files within a show are sorted (episode order). Ordered map, shows A→Z. */
    public static java.util.LinkedHashMap<String, List<File>> scanShows(Context ctx) {
        java.util.TreeMap<String, List<File>> sorted = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (File root : roots(ctx)) {
            if (!root.isDirectory()) continue;
            File[] dirs = root.listFiles();
            if (dirs == null) continue;
            for (File show : dirs) {
                if (!show.isDirectory()) continue;
                List<File> files = new ArrayList<>();
                collect(show, files);
                if (files.isEmpty()) continue;
                Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                List<File> cur = sorted.get(show.getName());
                if (cur == null) sorted.put(show.getName(), files); else cur.addAll(files);
            }
        }
        int n = 0; for (List<File> v : sorted.values()) n += v.size();
        Log.i(TAG, "library: " + sorted.size() + " shows, " + n + " files");
        return new java.util.LinkedHashMap<>(sorted);
    }

    /** Where phone->tablet uploads land: <Anancy>/Imports (created). Becomes an "Imports" playlist. */
    public static File importDir(Context ctx) {
        for (File root : roots(ctx)) if (root.isDirectory()) { File imp = new File(root, "Imports"); imp.mkdirs(); if (imp.isDirectory()) return imp; }
        for (File root : roots(ctx)) { File imp = new File(root, "Imports"); if (imp.mkdirs() || imp.isDirectory()) return imp; }
        return null;
    }

    /** API 29 has no all-files access: reading the library needs legacy READ/WRITE_EXTERNAL_STORAGE
        (requestLegacyExternalStorage in the manifest). The device-owner kiosk grants them to itself. */
    private static void grantStorage(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 30) return;
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName())) {
                android.content.ComponentName admin = new android.content.ComponentName(ctx, AdminReceiver.class);
                for (String p : new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE})
                    dpm.setPermissionGrantState(admin, ctx.getPackageName(), p,
                            android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        } catch (Exception e) { Log.w(TAG, "grantStorage: " + e); }
    }

    /** Candidate "Anancy" roots across primary + removable volumes (portable, no hardcoded UUID). */
    private static List<File> roots(Context ctx) {
        grantStorage(ctx);
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            // StorageVolume.getDirectory() is API 30 (NoSuchMethodError on 29, which catch(Exception) misses);
            // older releases are covered by the external-storage + /storage/* sweep below
            if (sm != null && android.os.Build.VERSION.SDK_INT >= 30) for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null) roots.add(new File(d, "Anancy"));
            }
        } catch (Exception ignored) {}
        try { roots.add(new File(Environment.getExternalStorageDirectory(), "Anancy")); } catch (Exception ignored) {}
        try { File[] vs = new File("/storage").listFiles(); if (vs != null) for (File v : vs) roots.add(new File(v, "Anancy")); } catch (Exception ignored) {}
        boolean afm = false;
        try { if (android.os.Build.VERSION.SDK_INT >= 30) afm = Environment.isExternalStorageManager(); } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder("roots(allFilesAccess=" + afm + "):");
        for (File r : roots) sb.append(" [").append(r.getAbsolutePath()).append(" dir=").append(r.isDirectory()).append(" read=").append(r.canRead()).append("]");
        Log.i(TAG, sb.toString());
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
