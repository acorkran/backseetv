package app.anancy;

// ============================================================================
//  Media folder picker — a plain file explorer, deliberately NOT the system
//  document picker. SAF hands back an opaque tree Uri, but the library scanner
//  walks real java.io.Files across internal storage AND a removable card, so a
//  content:// tree would have to be translated back into a path anyway. And the
//  system picker is a poor fit for the job: you cannot see which folder actually
//  holds the videos until you are inside it.
//
//  So: volumes at the top, folders below, each showing how many videos are in
//  it, and a button to take the one you are standing in.
// ============================================================================

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FolderPickerActivity extends Activity {
    private static final int BG = 0xFF101418, CARD = 0xFF1B2128, TXT = 0xFFF2F4F7,
            SUB = 0xFF9AA4B2, OK = 0xFF8FD694, ACCENT = 0xFF6FA8FF;
    /** Counting is bounded so a huge tree can't stall the list: deep enough for root/show/season. */
    private static final int MAX_DEPTH = 3, MAX_COUNT = 4000;

    private File cur;                 // null = showing the storage-volume list
    private LinearLayout list;
    private TextView crumb;
    // Counting videos walks the card -- 1362 files here -- so it CANNOT happen on the main thread:
    // doing it inline froze the list for seconds on every tap, which is the opposite of browsable.
    // Rows are drawn instantly with "counting…" and filled in as the numbers land; `gen` invalidates
    // the results of a folder you have already navigated away from.
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int gen = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String start = Cfg.getStr(this, "anancy_media_dir", "");
        if (start != null && !start.isEmpty()) { File f = new File(start); if (f.isDirectory()) cur = f; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(18), dp(16), dp(12));

        TextView h = new TextView(this);
        h.setText("Choose the media folder"); h.setTextColor(TXT); h.setTextSize(20);
        root.addView(h);
        crumb = new TextView(this); crumb.setTextColor(SUB); crumb.setTextSize(12);
        crumb.setPadding(0, dp(4), 0, dp(10)); root.addView(crumb);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = flat("Cancel", SUB); cancel.setOnClickListener(v -> finish());
        Button use = flat("Use this folder", OK); use.setOnClickListener(v -> choose());
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(50), 1f));
        bar.addView(use, new LinearLayout.LayoutParams(0, dp(50), 1.4f));
        root.addView(bar);

        setContentView(root);
        SysBars.pad(root);
        render();
    }

    @Override protected void onDestroy() { gen++; io.shutdownNow(); super.onDestroy(); }

    @Override public void onBackPressed() {
        if (cur != null) { cur = parentOrVolumes(cur); render(); return; }   // up a level, not out of the screen
        super.onBackPressed();
    }

    private void render() {
        list.removeAllViews();
        if (cur == null) {
            crumb.setText("Pick a storage device, then the folder your shows are in.");
            for (File v : volumes()) row(v.getName().isEmpty() ? v.getAbsolutePath() : labelFor(v), v.getAbsolutePath(), true, () -> { cur = v; render(); });
            rowPlain("Use the default folders instead", "Scan any folder named BackSeeTV-Media.", () -> {
                Cfg.applyConfig(this, "anancy_media_dir", "");
                rescan(); toast("Using the default folders"); finish();
            });
            return;
        }
        final int myGen = ++gen;
        crumb.setText(cur.getAbsolutePath() + "\ncounting…");
        final File counting = cur;
        io.execute(() -> {
            final int n = countVideos(counting, 0);
            main.post(() -> { if (myGen == gen) crumb.setText(counting.getAbsolutePath() + "\n"
                    + n + (n == MAX_COUNT ? "+" : "") + " videos in here (including sub-folders)"); });
        });

        row("‹  Up", parentLabel(), true, () -> { cur = parentOrVolumes(cur); render(); });
        File[] kids = cur.listFiles();
        List<File> dirs = new ArrayList<>();
        if (kids != null) for (File f : kids) if (f.isDirectory() && !f.getName().startsWith(".")) dirs.add(f);
        Collections.sort(dirs, (a, c) -> a.getName().compareToIgnoreCase(c.getName()));
        if (dirs.isEmpty()) {
            rowPlain("No sub-folders", "If the videos are in here directly, this is a fine choice — "
                    + "the count above tells you.", null);
            return;
        }
        for (final File d : dirs) {
            final TextView sub = row(d.getName(), "counting…", true, () -> { cur = d; render(); });
            io.execute(() -> {
                final int dn = countVideos(d, 0);
                main.post(() -> {
                    if (myGen != gen) return;
                    sub.setText(dn > 0 ? dn + (dn == MAX_COUNT ? "+" : "") + " videos" : "no videos");
                    sub.setTextColor(dn > 0 ? OK : SUB);
                });
            });
        }
    }

    private void choose() {
        if (cur == null) { toast("Open a folder first"); return; }
        if (!cur.canRead()) { toast("Can't read that folder"); return; }
        Cfg.applyConfig(this, "anancy_media_dir", cur.getAbsolutePath());
        rescan();
        toast("Media folder set — rescanning");
        finish();
    }

    private void rescan() {
        try { app.anancy.player.PlayerController.get().rescanAndRestart(this); } catch (Exception ignored) {}
    }

    // --- filesystem helpers --------------------------------------------------------------------

    /** Bounded recursive count, so the list stays responsive on a card with thousands of episodes. */
    private int countVideos(File dir, int depth) {
        if (dir == null || depth > MAX_DEPTH) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isDirectory()) { if (!f.getName().startsWith(".")) n += countVideos(f, depth + 1); }
            else if (app.anancy.player.Library.isVideo(f)) n++;
            if (n >= MAX_COUNT) return MAX_COUNT;
        }
        return n;
    }

    private List<File> volumes() {
        java.util.LinkedHashSet<File> out = new java.util.LinkedHashSet<>();
        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (sm != null && android.os.Build.VERSION.SDK_INT >= 30)
                for (StorageVolume v : sm.getStorageVolumes()) { File d = v.getDirectory(); if (d != null && d.isDirectory()) out.add(d); }
        } catch (Exception ignored) {}
        try { File e = Environment.getExternalStorageDirectory(); if (e != null && e.isDirectory()) out.add(e); } catch (Exception ignored) {}
        try { File[] vs = new File("/storage").listFiles(); if (vs != null) for (File v : vs) if (v.isDirectory() && v.canRead() && !"self".equals(v.getName())) out.add(v); } catch (Exception ignored) {}
        return new ArrayList<>(out);
    }

    private String labelFor(File v) {
        String p = v.getAbsolutePath();
        if (p.contains("emulated")) return "Internal storage";
        return "SD card / USB  (" + v.getName() + ")";
    }
    /** Going "up" out of a volume root lands back on the volume list, not /storage's innards. */
    private File parentOrVolumes(File f) {
        File p = f.getParentFile();
        if (p == null) return null;
        for (File v : volumes()) if (v.equals(f)) return null;
        if ("/storage".equals(p.getAbsolutePath()) || "/".equals(p.getAbsolutePath())) return null;
        return p;
    }
    private String parentLabel() {
        File p = parentOrVolumes(cur);
        return p == null ? "Storage devices" : p.getAbsolutePath();
    }

    // --- views ---------------------------------------------------------------------------------

    /** Returns the detail line so a background count can fill it in once it knows the answer. */
    private TextView row(String title, String detail, boolean strong, final Runnable onTap) {
        LinearLayout c = card();
        TextView t = new TextView(this); t.setText(title); t.setTextColor(strong ? TXT : SUB); t.setTextSize(16); c.addView(t);
        TextView d = new TextView(this);
        d.setText(detail == null ? "" : detail); d.setTextColor(SUB); d.setTextSize(12); d.setPadding(0, dp(3), 0, 0);
        c.addView(d);
        if (onTap != null) { c.setClickable(true); c.setOnClickListener(v -> onTap.run()); }
        list.addView(c);
        return d;
    }
    private void rowPlain(String title, String detail, Runnable onTap) { row(title, detail, true, onTap); }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(12));
        c.setBackground(bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);
        return c;
    }
    private Button flat(String label, int colour) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(colour); b.setTextSize(15);
        return b;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
