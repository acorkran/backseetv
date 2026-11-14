package app.anancy.player;

import app.anancy.admin.AdminReceiver;

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
            // Videos sitting directly in the chosen folder are a show in their own right, named
            // after the folder. Honouring only sub-folders made the folder picker's own advice a
            // lie -- it says "No sub-folders ... if the videos are in here directly, this is a fine
            // choice" and then handed back an empty library.
            List<File> loose = new ArrayList<>();
            for (File f : dirs) if (!f.isDirectory() && isVideo(f)) loose.add(f);
            if (!loose.isEmpty()) {
                Collections.sort(loose, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                List<File> cur = sorted.get(root.getName());
                if (cur == null) sorted.put(root.getName(), loose); else cur.addAll(loose);
            }
        }
        int n = 0; for (List<File> v : sorted.values()) n += v.size();
        Log.i(TAG, "library: " + sorted.size() + " shows, " + n + " files");
        return new java.util.LinkedHashMap<>(sorted);
    }

    /** Where phone->tablet uploads land: <BackSeeTV-Media>/Imports (created). Becomes an "Imports" playlist. */
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

    // The media folder: "BackSeeTV-Media", on internal storage or any SD card. New folders (Imports)
    // are made under it. Earlier builds also scanned the project's older names, which meant nothing to
    // anyone else; the setup script now finds videos in any folder and offers to move them in.
    // The names, oldest first: KidsTV, the first kiosk, built around Kodi. Kidodi, kids + Kodi.
    // Anancy, after it left Kodi for its own player: Anansi, the trickster and keeper of stories,
    // spinning one web across many screens. BackSeeTV: TV for the back seat, and a way to see the
    // kids through the camera on the Android Auto dash without turning round.
    static final String[] MEDIA_DIRS = { "BackSeeTV-Media" };
    /** Candidate media roots across primary + removable volumes (portable, no hardcoded UUID). */
    /** The same test the scanner uses, so a folder picker can show counts that match reality. */
    public static boolean isVideo(File f) {
        if (f == null || f.isDirectory()) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".m4v");
    }

    private static List<File> roots(Context ctx) {
        grantStorage(ctx);
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        // A folder the user picked WINS outright. Falling back to the hardcoded names as well would
        // silently merge some other folder's videos into their library, which is worse than useless.
        try {
            String chosen = app.anancy.Cfg.getStr(ctx, "anancy_media_dir", "");
            if (chosen != null && !chosen.isEmpty()) {
                File d = new File(chosen);
                if (d.isDirectory() && d.canRead()) {
                    Log.i(TAG, "roots: using chosen media folder " + d.getAbsolutePath());
                    return new ArrayList<>(java.util.Collections.singletonList(d));
                }
                Log.w(TAG, "roots: chosen media folder unreadable, falling back to defaults: " + chosen);
            }
        } catch (Exception ignored) {}
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            // StorageVolume.getDirectory() is API 30 (NoSuchMethodError on 29, which catch(Exception) misses);
            // older releases are covered by the external-storage + /storage/* sweep below
            if (sm != null && android.os.Build.VERSION.SDK_INT >= 30) for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null) for (String n : MEDIA_DIRS) roots.add(new File(d, n));
            }
        } catch (Exception ignored) {}
        try { for (String n : MEDIA_DIRS) roots.add(new File(Environment.getExternalStorageDirectory(), n)); } catch (Exception ignored) {}
        try { File[] vs = new File("/storage").listFiles(); if (vs != null) for (File v : vs) for (String n : MEDIA_DIRS) roots.add(new File(v, n)); } catch (Exception ignored) {}
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
