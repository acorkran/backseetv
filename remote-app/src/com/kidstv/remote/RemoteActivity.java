package com.kidstv.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * KidsTV front-seat remote. A fixed, tabbed control surface (Playlists | Controls) that
 * drives Kodi on the car tablet over raw JSON-RPC (9090). Transport-agnostic: it just
 * uses whichever of the tablet's WiFi/BT IPs answers, so Bluetooth behaves exactly like
 * WiFi (no mDNS discovery to break).
 */
public class RemoteActivity extends Activity {

    static final String PLAYLIST_DIR = "special://profile/playlists/video/";  // enumerated live, no hardcoded list

    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, BTN = 0xFF35516E, ACCENT = 0xFF2F6FEB;
    static final int TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6, GOOD = 0xFF33C05B, WARN = 0xFFE0653D;
    // Curated tile accents — each playlist name hashes to one, so the dynamic list reads as distinct
    // VARIABLES (its own colour + initial per show), not identical fixtures. Tuned for the dark theme.
    static final int[] TILE_COLORS = {
        0xFF4E86E0, 0xFF2FB3A2, 0xFFE0673F, 0xFF9B72E0, 0xFF3FBB63, 0xFFE0A63D, 0xFFE05D8C, 0xFF56A6E6
    };

    final KodiClient kodi = new KodiClient();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;

    View queueView;                         // kept so showTab() can refresh the queue when it's shown
    TextView statusDot, nowTitle, timeLabel, upNext, volLabel;
    SeekBar seek;
    IconView powerBtn, hotspotChip, playPause;   // Canvas-drawn — always render (glyphs tofu on-device)
    LinearLayout playlistGrid;              // dynamic playlist tiles live here (rebuilt on fetch)
    int mGridCols = 2;                       // columns the current grid render is using (avatar/text scale)
    // Playlist tile layout (pref "pl_cols"): PL_LIST = full-width rows, else 2/3/4 = grid columns.
    static final int PL_LIST = 0;
    // playlistTile styles.
    static final int STYLE_PRIMARY = 0, STYLE_GRID = 1, STYLE_LIST = 2;
    static final int REQ_SAF = 42;           // system file picker (SAF) request code
    LinearLayout queueList;                 // dynamic queue rows live here
    LinearLayout buildList;                 // Build tab: the working-set rows
    FrameLayout plSub;                       // Playlists tab: swaps tiles / Build / Files sub-views
    View[] plSubViews;                       // [tiles, build, files]
    TextView buildHint;
    final java.util.ArrayList<String[]> mBuildSet = new java.util.ArrayList<>();  // {label, file, episodeid}
    volatile String[][] mPlaylists = null;  // {label, file} per real .xsp on the device; null until loaded
    final java.util.ArrayList<View> mTabViews = new java.util.ArrayList<>();   // generic N-tab plumbing
    final java.util.ArrayList<String> mTabLabels = new java.util.ArrayList<>();
    final java.util.ArrayList<Button> mTabBtns = new java.util.ArrayList<>();
    View filesView;                         // kept so showTab() can refresh files when shown
    LinearLayout fileList;                  // dynamic file rows
    Button srcTablet, srcPhone;
    TextView fileHint;
    int mFileSrc = 0;                        // 0 = tablet uploads (Remote), 1 = phone videos (Local)
    FrameLayout mRoot;                       // top-level: main UI + the Settings page on top
    View mMainUi, mSettingsView;             // mSettingsView non-null = Settings page is open
    EditText mWifiEt, mBtEt;                 // Settings IP overrides (saved on leaving the page)
    String mPhoneBucket = null;              // Files→Phone browser: null = folder list, else this folder
    java.util.LinkedHashMap<String, java.util.ArrayList<String[]>> mPhoneByBucket;   // folder -> {name,uri,size}

    volatile boolean polling = false, seeking = false, napping = false;
    volatile int curVol = 0;
    volatile boolean muted = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        kodi.setPinned(prefs.getString("wifi", ""), prefs.getString("bt", ""));  // optional overrides
        kodi.setLast(prefs.getString("lastip", ""));                             // fast-path cache
        setContentView(buildUi());
        showTab(0);
    }

    @Override protected void onResume() { super.onResume(); startPoll(); }
    @Override protected void onPause()  { super.onPause();  polling = false; }

    // ---------------- UI ----------------

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    View buildUi() {
        mRoot = new FrameLayout(this);
        mMainUi = mainUi();
        mRoot.addView(mMainUi);
        return mRoot;   // the Settings page is added on top of this (a real in-app page, not a dialog)
    }

    View mainUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(topBar());

        FrameLayout content = new FrameLayout(this);
        addTab(content, "Playlists", playlistsTab());
        addTab(content, "Queue", queueView = queueTab());
        addTab(content, "Controls", controlsTab());
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(bottomBar());
        return root;
    }

    void addTab(FrameLayout content, String label, View v) {
        v.setVisibility(View.GONE);   // showTab() reveals the current one
        content.addView(v);
        mTabViews.add(v);
        mTabLabels.add(label);
    }

    LinearLayout topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(14), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("KidsTV");
        title.setTextColor(TXT);
        title.setTextSize(20);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Phone's own hotspot state — the ONE thing the user flips each ride (the tablet joins THIS
        // phone's AP). A red/green WiFi button; tap jumps to the hotspot toggle (an app can't flip it).
        hotspotChip = new IconView(this, IconView.WIFI, WARN, TXT);
        hotspotChip.setOnClickListener(v -> openHotspotSettings());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(46), dp(40));
        hp.rightMargin = dp(6);
        bar.addView(hotspotChip, hp);

        statusDot = new TextView(this);
        statusDot.setText("● …");
        statusDot.setTextColor(SUB);
        statusDot.setTextSize(13);
        statusDot.setPadding(0, 0, dp(10), 0);
        statusDot.setOnClickListener(v -> statusTap());
        bar.addView(statusDot);

        powerBtn = new IconView(this, IconView.POWER, BTN, GOOD);
        powerBtn.setOnClickListener(v -> togglePower());
        bar.addView(powerBtn, new LinearLayout.LayoutParams(dp(48), dp(44)));

        Button gear = plainBtn("⚙", v -> settings());
        gear.setTextSize(22);
        gear.setTextColor(SUB);
        bar.addView(gear, new LinearLayout.LayoutParams(dp(52), dp(48)));
        return bar;
    }

    /** The Playlists tab is now a hub: a FrameLayout that swaps three sub-views — the tile grid
        (default), Build, and Files — so those two no longer clutter the bottom bar. */
    View playlistsTab() {
        plSub = new FrameLayout(this);
        View tiles = tilesView();
        View build = subPage(buildTab());
        View files = subPage(filesView = filesTab());
        plSubViews = new View[]{tiles, build, files};
        for (View v : plSubViews) { v.setVisibility(View.GONE); plSub.addView(v); }
        showPlSub(0);
        return plSub;
    }

    /** Default sub-view: a compact header (New/Import + Browse files) over the tile grid. */
    View tilesView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setPadding(dp(10), dp(10), dp(10), dp(4));
        LinearLayout.LayoutParams eq = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        eq.rightMargin = dp(6);
        hdr.addView(cardBtn("+  New / Import", v -> showPlSub(1)), eq);
        hdr.addView(cardBtn("Browse files", v -> showPlSub(2)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(hdr);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        playlistGrid = new LinearLayout(this);
        playlistGrid.setOrientation(LinearLayout.VERTICAL);
        playlistGrid.setPadding(dp(10), dp(6), dp(10), dp(10));
        sv.addView(playlistGrid);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderPlaylists();   // placeholder until fetchPlaylists() populates mPlaylists
        return col;
    }

    /** The playlist tiles are read from the pref "pl_cols": PL_LIST (0) = full-width list rows, or
        2/3/4 = a grid that many columns wide. Default 2. */
    int plCols() { return prefs.getInt("pl_cols", 2); }

    /** Wrap a sub-page (Build / Files) with a "‹ Playlists" back header that returns to the tiles. */
    View subPage(View inner) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        Button back = plainBtn("‹  Playlists", v -> showPlSub(0));
        back.setTextColor(SUB); back.setTextSize(15); back.setAllCaps(false);
        back.setBackgroundColor(CARD);
        col.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        col.addView(inner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    /** Swap the Playlists-tab sub-view: 0 = tiles, 1 = Build, 2 = Files. */
    void showPlSub(int idx) {
        if (plSubViews == null) return;
        for (int i = 0; i < plSubViews.length; i++)
            plSubViews[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        if (idx == 0) renderPlaylists();          // refresh tiles (e.g. after a Save)
        if (idx == 2) showFileSrc(mFileSrc);      // (re)load the Files browse list
    }

    /** (Re)build the playlist tiles from mPlaylists on the UI thread. Honours the "pl_cols" pref:
        PL_LIST = full-width rows, or 2/3/4 = a grid that many columns wide (tiles shrink as columns
        grow). The all-shows shuffle is always the primary tile on top. Scrollable + any length. */
    void renderPlaylists() {
        if (playlistGrid == null) return;
        playlistGrid.removeAllViews();
        String[][] pls = mPlaylists;
        if (pls == null || pls.length == 0) {
            TextView t = new TextView(this);
            t.setText(pls == null ? "Loading playlists…" : "No playlists on the tablet");
            t.setTextColor(SUB); t.setTextSize(16); t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(40), 0, 0);
            playlistGrid.addView(t);
            return;
        }
        // Pull the all-shows shuffle out as the primary, full-width tile; the rest follow per the layout.
        java.util.ArrayList<String[]> rest = new java.util.ArrayList<>();
        String[] shuffle = null;
        for (String[] pl : pls) {
            String l = pl[0].toLowerCase();
            if (shuffle == null && (l.contains("shuffle") || l.contains("all show"))) shuffle = pl;
            else rest.add(pl);
        }
        if (shuffle != null) {
            final String label = shuffle[0], file = shuffle[1];
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
            pp.setMargins(dp(5), dp(5), dp(5), dp(5));
            playlistGrid.addView(playlistTile(label, () -> playPlaylist(label, file), STYLE_PRIMARY), pp);
        }
        int cols = plCols();
        if (cols == PL_LIST) {                       // full-width, compact rows
            for (String[] pl : rest) {
                final String label = pl[0], file = pl[1];
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
                lp.setMargins(dp(5), dp(4), dp(5), dp(4));
                playlistGrid.addView(playlistTile(label, () -> playPlaylist(label, file), STYLE_LIST), lp);
            }
        } else {                                     // N-column grid; tiles shrink as N grows
            mGridCols = cols;
            int h = cols == 2 ? 100 : cols == 3 ? 88 : 78;
            for (int r = 0; r < rest.size(); r += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < cols; c++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(h), 1f);
                    lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                    int idx = r + c;
                    if (idx < rest.size()) {
                        final String label = rest.get(idx)[0], file = rest.get(idx)[1];
                        row.addView(playlistTile(label, () -> playPlaylist(label, file), STYLE_GRID), lp);
                    } else {
                        row.addView(new View(this), lp);   // keep the final row aligned
                    }
                }
                playlistGrid.addView(row);
            }
        }
    }

    /** Pull the tablet's real smart playlists (the .xsp files) live so the tab mirrors whatever's on
        the device — no hardcoded set. Runs on a bg thread; posts a UI rebuild. */
    void fetchPlaylists() {
        String r = kodi.rpc("Files.GetDirectory", "{\"directory\":\"" + PLAYLIST_DIR + "\",\"media\":\"video\"}");
        if (r == null) return;
        try {
            JSONArray files = new JSONObject(r).getJSONObject("result").getJSONArray("files");
            java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String file = f.optString("file", "");
                String label = f.optString("label", "");
                if (file.toLowerCase().endsWith(".xsp") && label.length() > 0) out.add(new String[]{label, file});
            }
            mPlaylists = out.toArray(new String[0][]);
            ui.post(() -> renderPlaylists());
        } catch (Exception e) {}
    }

    // ---------------- queue tab ----------------

    View queueTab() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        queueList = new LinearLayout(this);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(queueList);
        return sv;
    }

    /** Pull the current play queue (a window from the playing item onward - a shuffle can be ~1000
        items) and render rows: now-playing highlighted, tap a row to jump, ✕ to remove. */
    void fetchQueue() {
        bg(() -> {
            int pos = -1;
            try { pos = new JSONObject(kodi.rpc("Player.GetProperties", "{\"playerid\":1,\"properties\":[\"position\"]}")).getJSONObject("result").optInt("position", -1); } catch (Exception e) {}
            final int start = Math.max(0, pos);
            String r = kodi.rpc("Playlist.GetItems", "{\"playlistid\":1,\"properties\":[\"title\",\"showtitle\",\"season\",\"episode\"],\"limits\":{\"start\":" + start + ",\"end\":" + (start + 150) + "}}");
            final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
            int total = 0;
            try {
                JSONObject res = new JSONObject(r).getJSONObject("result");
                total = res.getJSONObject("limits").optInt("total", 0);
                JSONArray items = res.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) labels.add(label(items.getJSONObject(i)));
            } catch (Exception e) {}
            final int fpos = pos, ftotal = total;
            ui.post(() -> renderQueue(labels, start, fpos, ftotal));
        });
    }

    void renderQueue(java.util.List<String> labels, int start, int pos, int total) {
        if (queueList == null) return;
        queueList.removeAllViews();
        if (labels.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Queue is empty");
            t.setTextColor(SUB); t.setTextSize(16); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(40), 0, 0);
            queueList.addView(t);
            return;
        }
        TextView hdr = new TextView(this);
        hdr.setText("Playing " + (pos >= 0 ? (pos + 1) : "?") + " of " + total + (start + labels.size() < total ? "  ·  showing next " + labels.size() : ""));
        hdr.setTextColor(SUB); hdr.setTextSize(12); hdr.setPadding(dp(4), 0, 0, dp(6));
        queueList.addView(hdr);
        for (int i = 0; i < labels.size(); i++) {
            final int absPos = start + i;
            boolean now = absPos == pos;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(now ? ACCENT : CARD);
            TextView t = new TextView(this);
            t.setText((now ? "▶  " : "") + labels.get(i));
            t.setTextColor(TXT); t.setTextSize(14); t.setPadding(dp(10), dp(12), dp(8), dp(12));
            t.setMaxLines(1);
            t.setOnClickListener(v -> jumpTo(absPos));
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!now) {   // Kodi won't remove the currently-playing item, so don't offer a dead ✕ on it
                Button x = new Button(this);
                x.setText("✕"); x.setAllCaps(false); x.setTextColor(SUB); x.setTextSize(15);
                x.setBackgroundColor(CARD);
                x.setOnClickListener(v -> removeAt(absPos));
                row.addView(x, new LinearLayout.LayoutParams(dp(46), dp(46)));
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(dp(46), dp(46)));   // keep alignment
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, 0);
            queueList.addView(row, lp);
        }
    }

    void jumpTo(final int pos) {
        bg(() -> kodi.rpc("Player.GoTo", "{\"playerid\":1,\"to\":" + pos + "}"));
        ui.postDelayed(this::fetchQueue, 900);
    }

    void removeAt(final int pos) {
        bg(() -> kodi.rpc("Playlist.Remove", "{\"playlistid\":1,\"position\":" + pos + "}"));
        ui.postDelayed(this::fetchQueue, 500);
    }

    // ---------------- files tab (item 5: Local | Remote browser + phone->tablet upload) ----------------

    View filesTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout srcBar = new LinearLayout(this);
        srcBar.setOrientation(LinearLayout.HORIZONTAL);
        srcBar.setPadding(dp(8), dp(8), dp(8), dp(4));
        srcTablet = plainBtn("Tablet", v -> showFileSrc(0));
        srcPhone = plainBtn("Phone", v -> showFileSrc(1));
        srcTablet.setTextColor(TXT); srcPhone.setTextColor(TXT);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(44), 1f); sp.setMargins(0, 0, dp(6), 0);
        srcBar.addView(srcTablet, sp);
        srcBar.addView(srcPhone, new LinearLayout.LayoutParams(0, dp(44), 1f));
        col.addView(srcBar);
        fileHint = new TextView(this);
        fileHint.setTextColor(SUB); fileHint.setTextSize(12); fileHint.setPadding(dp(12), dp(2), dp(12), dp(4));
        col.addView(fileHint);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(dp(8), 0, dp(8), dp(8));
        sv.addView(fileList);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    void showFileSrc(int src) {
        mFileSrc = src;
        if (srcTablet != null) srcTablet.setBackgroundColor(src == 0 ? ACCENT : CARD);
        if (srcPhone != null) srcPhone.setBackgroundColor(src == 1 ? ACCENT : CARD);
        if (src == 0) fetchTabletFiles(); else fetchPhoneFiles();
    }

    /** Remote source: files already uploaded to the tablet (GET /uploads via the bridge). Tap to play. */
    void fetchTabletFiles() {
        ui.post(() -> { fileHint.setText("On the tablet — tap Play"); if (fileList != null) fileList.removeAllViews(); });
        bg(() -> {
            String r = kodi.bridgeGet("/uploads");
            final java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
            try {
                JSONArray files = new JSONObject(r).getJSONObject("result").getJSONArray("files");
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    rows.add(new String[]{ f.optString("name", ""), f.optString("file", ""), String.valueOf(f.optLong("size", 0)) });
                }
            } catch (Exception e) {}
            ui.post(() -> renderFiles(rows, false));
        });
    }

    /** Local source: an in-app BROWSER of the phone's videos — folders first, then the videos inside,
        each with Send. Plus a "Pick a file…" card that opens the system file picker (SAF) for anything
        (even non-media / outside the media folders). Replaces the old flat list + folder-filter setting. */
    void fetchPhoneFiles() {
        mPhoneBucket = null;
        ui.post(() -> { if (fileHint != null) fileHint.setText("Loading phone videos…"); if (fileList != null) fileList.removeAllViews(); });
        final String perm = (android.os.Build.VERSION.SDK_INT >= 33) ? android.Manifest.permission.READ_MEDIA_VIDEO : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // no media perm yet — still offer the SAF picker (it needs no media permission)
            ui.post(() -> { if (fileList != null) { fileList.removeAllViews(); addBrowseCard("📂  Pick a file…  (system file browser)", this::openSafPicker); } });
            requestPermissions(new String[]{perm}, 7);   // onRequestPermissionsResult re-fetches on grant
            return;
        }
        bg(() -> {
            final java.util.LinkedHashMap<String, java.util.ArrayList<String[]>> map = new java.util.LinkedHashMap<>();
            try {
                android.net.Uri coll = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] proj = { android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DISPLAY_NAME, android.provider.MediaStore.Video.Media.SIZE, android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME };
                android.database.Cursor c = getContentResolver().query(coll, proj, null, null,
                        android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " ASC, " + android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID);
                    int iNm = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME);
                    int iSz = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE);
                    int iBk = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    while (c.moveToNext()) {
                        String bk = c.getString(iBk); if (bk == null || bk.length() == 0) bk = "Other";
                        long id = c.getLong(iId), sz = c.getLong(iSz);
                        String nm = c.getString(iNm); if (nm == null) nm = "video_" + id;
                        java.util.ArrayList<String[]> list = map.get(bk);
                        if (list == null) { list = new java.util.ArrayList<>(); map.put(bk, list); }
                        list.add(new String[]{ nm, android.content.ContentUris.withAppendedId(coll, id).toString(), String.valueOf(sz) });
                    }
                    c.close();
                }
            } catch (Exception e) {}
            mPhoneByBucket = map;
            ui.post(() -> renderPhoneFolders());
        });
    }

    /** Top level of the phone browser: a "Pick a file…" card + one card per folder (with a video count). */
    void renderPhoneFolders() {
        if (fileList == null) return;
        fileList.removeAllViews();
        fileHint.setText("Pick a folder — or “Pick a file…” for anything else on the phone");
        addBrowseCard("📂  Pick a file…  (system file browser)", this::openSafPicker);
        if (mPhoneByBucket == null || mPhoneByBucket.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No videos found on the phone"); t.setTextColor(SUB); t.setTextSize(15);
            t.setGravity(Gravity.CENTER); t.setPadding(0, dp(40), 0, 0);
            fileList.addView(t);
            return;
        }
        for (java.util.Map.Entry<String, java.util.ArrayList<String[]>> e : mPhoneByBucket.entrySet()) {
            final String bucket = e.getKey();
            int n = e.getValue().size();
            addBrowseCard("📁  " + bucket + "     " + n + (n == 1 ? " video" : " videos"),
                    () -> { mPhoneBucket = bucket; renderPhoneVideos(); });
        }
    }

    /** Inside a folder: a "‹ Folders" back card + each video with Send. */
    void renderPhoneVideos() {
        if (fileList == null || mPhoneByBucket == null) return;
        fileList.removeAllViews();
        fileHint.setText(mPhoneBucket + " — tap Send to copy to the tablet + play");
        addBrowseCard("‹  Folders", () -> { mPhoneBucket = null; renderPhoneFolders(); });
        java.util.ArrayList<String[]> vids = mPhoneByBucket.get(mPhoneBucket);
        if (vids != null) for (String[] row : vids) {
            final String name = row[0], ref = row[1];
            long sz = 0; try { sz = Long.parseLong(row[2]); } catch (Exception e) {}
            final long size = sz;
            addFileRow(name, size, "Send ▸", () -> uploadPhoneFile(ref, name, size));
        }
    }

    /** A full-width tappable card (folder / pick-file / back) in the Files browser. */
    void addBrowseCard(String label, final Runnable onClick) {
        Button b = cardBtn(label, v -> onClick.run());
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextSize(15);
        b.setPadding(dp(14), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.setMargins(0, dp(6), 0, 0);
        fileList.addView(b, lp);
    }

    /** A file row: name + size on the left, an action button (Send / Play) on the right. */
    void addFileRow(String name, long size, String actLabel, final Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackgroundColor(CARD);
        TextView t = new TextView(this);
        t.setText(name + (size > 0 ? "\n" + humanSize(size) : ""));
        t.setTextColor(TXT); t.setTextSize(14); t.setPadding(dp(10), dp(12), dp(8), dp(12)); t.setMaxLines(2);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button act = new Button(this);
        act.setAllCaps(false); act.setTextColor(TXT); act.setTextSize(13); act.setBackgroundColor(BTN);
        act.setText(actLabel); act.setOnClickListener(v -> onClick.run());
        r.addView(act, new LinearLayout.LayoutParams(dp(96), dp(52)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, 0);
        fileList.addView(r, lp);
    }

    /** The system file picker (SAF) — Android's built-in file browser. Pick any file; we upload it to
        the tablet (making it a local file there) and play it. Needs no media permission. */
    void openSafPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_SAF); }
        catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAF && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            uploadPhoneFile(uri.toString(), safName(uri), safSize(uri));
        }
    }

    String safName(android.net.Uri uri) {
        String n = null;
        try {
            android.database.Cursor c = getContentResolver().query(uri, new String[]{ android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (c != null) { if (c.moveToFirst() && !c.isNull(0)) n = c.getString(0); c.close(); }
        } catch (Exception e) {}
        if (n == null) { n = uri.getLastPathSegment(); if (n != null) { int s = n.lastIndexOf('/'); if (s >= 0) n = n.substring(s + 1); } }
        return (n != null && n.length() > 0) ? n : "file";
    }

    long safSize(android.net.Uri uri) {
        long sz = 0;
        try {
            android.database.Cursor c = getContentResolver().query(uri, new String[]{ android.provider.OpenableColumns.SIZE }, null, null, null);
            if (c != null) { if (c.moveToFirst() && !c.isNull(0)) sz = c.getLong(0); c.close(); }
        } catch (Exception e) {}
        return sz;
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 7 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { if (mFileSrc == 1) fetchPhoneFiles(); }
        else if (req == 7 && fileHint != null) ui.post(() -> fileHint.setText("Phone media permission denied — enable it in Settings"));
    }

    void renderFiles(java.util.List<String[]> rows, final boolean isPhone) {
        if (fileList == null) return;
        fileList.removeAllViews();
        if (rows.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(isPhone ? "No videos found on the phone" : "Nothing uploaded to the tablet yet");
            t.setTextColor(SUB); t.setTextSize(15); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(40), 0, 0);
            fileList.addView(t);
            return;
        }
        for (String[] row : rows) {
            final String name = row[0], ref = row[1];
            long sz = 0; try { sz = Long.parseLong(row[2]); } catch (Exception e) {}
            final long size = sz;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackgroundColor(CARD);
            TextView t = new TextView(this);
            t.setText(name + (size > 0 ? "\n" + humanSize(size) : ""));
            t.setTextColor(TXT); t.setTextSize(14); t.setPadding(dp(10), dp(12), dp(8), dp(12)); t.setMaxLines(2);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button act = new Button(this);
            act.setAllCaps(false); act.setTextColor(TXT); act.setTextSize(13); act.setBackgroundColor(BTN);
            if (isPhone) { act.setText("Send ▸"); act.setOnClickListener(v -> uploadPhoneFile(ref, name, size)); }
            else { act.setText("▶ Play"); act.setOnClickListener(v -> playTabletFile(ref)); }
            r.addView(act, new LinearLayout.LayoutParams(dp(96), dp(52)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, 0);
            fileList.addView(r, lp);
        }
    }

    void playTabletFile(final String path) {
        Toast.makeText(this, "Playing on the tablet…", Toast.LENGTH_SHORT).show();
        bg(() -> kodi.rpc("Addons.ExecuteAddon", "{\"addonid\":\"script.kidstv.play\",\"params\":[\"" + jesc(path) + "\"]}"));
    }

    void uploadPhoneFile(final String uriStr, final String name, final long size) {
        Toast.makeText(this, "Sending " + name + " …", Toast.LENGTH_SHORT).show();
        bg(() -> {
            String resp = null;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uriStr));
                if (in != null) resp = kodi.upload(name, in, size);
            } catch (Exception e) {}
            String path = null;
            try { path = new JSONObject(resp).getJSONObject("result").optString("path", null); } catch (Exception e) {}
            final String fpath = path;
            ui.post(() -> Toast.makeText(this, fpath != null ? (name + " → sent, playing") : ("Couldn't send " + name), Toast.LENGTH_LONG).show());
            if (fpath != null) {
                kodi.rpc("Addons.ExecuteAddon", "{\"addonid\":\"script.kidstv.play\",\"params\":[\"" + jesc(fpath) + "\"]}");
                ui.post(() -> { if (mFileSrc == 0 && filesView != null && filesView.getVisibility() == View.VISIBLE) fetchTabletFiles(); });
            }
        });
    }

    String jesc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    String humanSize(long b) {
        if (b >= 1_000_000_000L) return String.format("%.1f GB", b / 1e9);
        if (b >= 1_000_000L) return String.format("%.0f MB", b / 1e6);
        if (b >= 1000L) return String.format("%.0f KB", b / 1e3);
        return b + " B";
    }

    View controlsTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        nowTitle = new TextView(this);
        nowTitle.setText("Not playing");
        nowTitle.setTextColor(TXT);
        nowTitle.setTextSize(17);
        nowTitle.setMaxLines(2);
        col.addView(nowTitle);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            public void onStopTrackingTouch(SeekBar s) {
                final double pct = s.getProgress() / 10.0;
                bg(() -> kodi.rpc("Player.Seek", "{\"playerid\":1,\"value\":{\"percentage\":" + pct + "}}"));
                seeking = false;
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(6), 0, 0);
        col.addView(seek, sp);

        timeLabel = new TextView(this);
        timeLabel.setText("0:00 / 0:00");
        timeLabel.setTextColor(SUB);
        timeLabel.setTextSize(13);
        timeLabel.setGravity(Gravity.END);
        col.addView(timeLabel);

        // transport cluster — drawn glyphs (always render); play/pause is the accent centrepiece
        LinearLayout tr = row();
        tr.setGravity(Gravity.CENTER);
        marginTop(tr, 14);
        tr.addView(iconBtn(IconView.PREV, BTN, TXT, v -> input("Player.GoTo", "{\"playerid\":1,\"to\":\"previous\"}")), iconLp(58));
        playPause = new IconView(this, IconView.PLAYPAUSE, ACCENT, TXT);
        playPause.setOnClickListener(v -> input("Player.PlayPause", "{\"playerid\":1}"));
        tr.addView(playPause, iconLp(74));
        tr.addView(iconBtn(IconView.NEXT, BTN, TXT, v -> input("Player.GoTo", "{\"playerid\":1,\"to\":\"next\"}")), iconLp(58));
        tr.addView(iconBtn(IconView.STOP, BTN, WARN, v -> input("Player.Stop", "{\"playerid\":1}")), iconLp(58));
        col.addView(tr);

        // D-pad + Back/Home/Info (menu browsing)
        LinearLayout dpad = new LinearLayout(this);
        dpad.setOrientation(LinearLayout.VERTICAL);
        marginTop(dpad, 10);
        LinearLayout d1 = row(); d1.addView(spacer(), eq()); d1.addView(cardBtn("▲", v -> input("Input.Up", null)), eq()); d1.addView(spacer(), eq()); dpad.addView(d1);
        LinearLayout d2 = row(); d2.addView(cardBtn("◀", v -> input("Input.Left", null)), eq()); d2.addView(cardBtn("OK", v -> input("Input.Select", null)), eq()); d2.addView(cardBtn("▶", v -> input("Input.Right", null)), eq()); dpad.addView(d2);
        LinearLayout d3 = row(); d3.addView(spacer(), eq()); d3.addView(cardBtn("▼", v -> input("Input.Down", null)), eq()); d3.addView(spacer(), eq()); dpad.addView(d3);
        LinearLayout d4 = row(); marginTop(d4, 6);
        d4.addView(cardBtn("Back", v -> input("Input.Back", null)), eq());
        d4.addView(cardBtn("Home", v -> input("Input.Home", null)), eq());
        d4.addView(cardBtn("Info", v -> input("Input.Info", null)), eq());
        dpad.addView(d4);
        col.addView(dpad);

        // volume — drawn −/+ glyph buttons with the level between them
        LinearLayout vr = row(); marginTop(vr, 14); vr.setGravity(Gravity.CENTER_VERTICAL);
        vr.addView(iconBtn(IconView.VOLDOWN, BTN, TXT, v -> volDelta(-volStep())), iconLp(54));
        volLabel = new TextView(this);
        volLabel.setText("--");
        volLabel.setTextColor(TXT);
        volLabel.setTextSize(18);
        volLabel.setGravity(Gravity.CENTER);
        vr.addView(volLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        vr.addView(iconBtn(IconView.VOLUP, BTN, TXT, v -> volDelta(volStep())), iconLp(54));
        vr.addView(cardBtn("Mute", v -> toggleMute()), eq());
        col.addView(vr);

        // Go to sleep: zZ button — ramps volume + tablet brightness down, then turns it off (configured
        // in Settings → Sleep timer). A full-width card with the drawn Zz glyph + label.
        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setGravity(Gravity.CENTER);
        sleepRow.setClickable(true);
        sleepRow.setBackground(pressBg(BTN, withAlpha(0xFFFFFFFF, 0x1A), dp(12)));
        sleepRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        sleepRow.setOnClickListener(v -> goToSleep());
        IconView zicon = new IconView(this, IconView.SLEEP, BTN, TXT);
        zicon.setClickable(false);
        sleepRow.addView(zicon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView zlbl = new TextView(this);
        zlbl.setText("Go to sleep");
        zlbl.setTextColor(TXT); zlbl.setTextSize(16);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zlp.leftMargin = dp(8);
        sleepRow.addView(zlbl, zlp);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        srp.setMargins(0, dp(14), 0, 0);
        col.addView(sleepRow, srp);

        upNext = new TextView(this);
        upNext.setText("Up next: —");
        upNext.setTextColor(SUB);
        upNext.setTextSize(13);
        upNext.setMaxLines(2);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        up.setMargins(0, dp(12), 0, 0);
        upNext.setOnClickListener(v -> showQueue());
        col.addView(upNext, up);
        android.widget.ScrollView csv = new android.widget.ScrollView(this);   // tab is now tall (+ sleep) — scroll
        csv.addView(col);
        return csv;
    }

    LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        for (int i = 0; i < mTabLabels.size(); i++) {
            final int idx = i;
            Button b = plainBtn(mTabLabels.get(i), v -> showTab(idx));
            b.setTextColor(TXT);
            mTabBtns.add(b);
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        return bar;
    }

    void showTab(int idx) {
        for (int i = 0; i < mTabViews.size(); i++) {
            mTabViews.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
            if (i < mTabBtns.size()) mTabBtns.get(i).setBackgroundColor(i == idx ? ACCENT : CARD);
        }
        if (queueView != null && mTabViews.get(idx) == queueView) fetchQueue();
        if (filesView != null && mTabViews.get(idx) == filesView) showFileSrc(mFileSrc);
    }

    // small view helpers
    LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    LinearLayout.LayoutParams eq() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f); lp.setMargins(dp(4), dp(4), dp(4), dp(4)); return lp; }
    View spacer() { View v = new View(this); return v; }
    void marginTop(View v, int d) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(d), 0, 0); v.setLayoutParams(lp); }

    Button plainBtn(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setBackgroundColor(CARD);
        b.setOnClickListener(onClick);
        return b;
    }

    Button cardBtn(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(16);
        b.setBackground(pressBg(BTN, withAlpha(0xFFFFFFFF, 0x1A), dp(12)));
        b.setOnClickListener(onClick);
        return b;
    }

    // ---------------- design helpers (rounded cards, press feedback, per-name accent, avatars) ------
    int shade(int c, float f) {   // scale RGB (f<1 darkens) — pressed states
        int a = (c >>> 24) & 0xff;
        int r = Math.min(255, (int) (((c >> 16) & 0xff) * f));
        int g = Math.min(255, (int) (((c >> 8) & 0xff) * f));
        int bl = Math.min(255, (int) ((c & 0xff) * f));
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }
    int withAlpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }
    int tileColorFor(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return TILE_COLORS[Math.abs(h) % TILE_COLORS.length];
    }
    android.graphics.drawable.GradientDrawable roundRect(int fill, int stroke, int strokeW, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }
    /** Card background that darkens on press so buttons/tiles feel tactile. */
    android.graphics.drawable.StateListDrawable pressBg(int fill, int stroke, int radius) {
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, roundRect(shade(fill, 0.80f), stroke, dp(1), radius));
        sl.addState(new int[]{}, roundRect(fill, stroke, dp(1), radius));
        return sl;
    }
    /** A round colour-chip with the playlist's initial — a distinct anchor per show. */
    View letterAvatar(String label, int accent, int sizePx) {
        TextView a = new TextView(this);
        String s = label == null ? "" : label.trim();
        a.setText(s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase());
        a.setTextColor(TXT);
        a.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.44f);
        a.setTypeface(null, android.graphics.Typeface.BOLD);
        a.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable o = new android.graphics.drawable.GradientDrawable();
        o.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        o.setColor(accent);
        a.setBackground(o);
        return a;
    }
    /** One playlist as a content card: per-name colour + initial avatar + name. STYLE_PRIMARY = the
        accent-filled Shuffle-All tile (a drawn shuffle glyph, horizontal). STYLE_LIST = a compact
        horizontal row. STYLE_GRID = a vertical card sized to the current column count (mGridCols).
        Rounded + pressable. */
    View playlistTile(final String label, final Runnable onClick, int style) {
        boolean primary = style == STYLE_PRIMARY, list = style == STYLE_LIST;
        boolean horiz = primary || list;                  // avatar-left rows; grid tiles stack vertically
        int accent = primary ? ACCENT : tileColorFor(label);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(horiz ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setGravity(horiz ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        if (list)         card.setPadding(dp(10), dp(8), dp(10), dp(8));
        else if (primary) card.setPadding(dp(12), dp(11), dp(12), dp(11));
        else              card.setPadding(dp(8), dp(9), dp(8), dp(9));
        card.setClickable(true);
        card.setBackground(pressBg(primary ? accent : CARD, primary ? accent : withAlpha(accent, 0x66), dp(14)));
        card.setOnClickListener(v -> onClick.run());
        int av = dp(primary ? 34 : list ? 34 : (mGridCols >= 4 ? 30 : mGridCols == 3 ? 34 : 40));
        View avatar = primary ? new IconView(this, IconView.SHUFFLE, withAlpha(0xFFFFFFFF, 0x30), TXT)
                              : letterAvatar(label, accent, av);
        avatar.setClickable(false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(av, av);
        if (horiz) ap.rightMargin = dp(10);
        card.addView(avatar, ap);
        TextView t = new TextView(this);
        t.setText(primary ? "Shuffle All Shows" : label);
        t.setTextColor(TXT);
        t.setTextSize(primary ? 17 : list ? 15 : (mGridCols >= 4 ? 12 : mGridCols == 3 ? 13 : 14));
        t.setTypeface(null, primary ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        t.setGravity(horiz ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        t.setMaxLines(2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            horiz ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, horiz ? 1f : 0f);
        if (style == STYLE_GRID) tp.topMargin = dp(7);
        card.addView(t, tp);
        return card;
    }
    IconView iconBtn(int mode, int bg, int icon, View.OnClickListener onClick) {
        IconView v = new IconView(this, mode, bg, icon);
        v.setOnClickListener(onClick);
        return v;
    }
    LinearLayout.LayoutParams iconLp(int sizeDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        return lp;
    }

    // ---------------- actions ----------------

    void bg(Runnable r) { new Thread(r).start(); }

    /** Fire-and-forget button command, then refresh soon. */
    void input(final String method, final String params) {
        bg(() -> kodi.rpc(method, params));
    }

    static String jsonEscR(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** Build tab: assemble a working set from the tablet library, then Play now or Save as a playlist
        tile. (Phone-file + .m3u sources land in a later phase — the button is a disabled placeholder.) */
    View buildTab() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Build a playlist");
        title.setTextColor(TXT); title.setTextSize(19); title.setPadding(0, 0, 0, dp(2));
        root.addView(title);
        buildHint = new TextView(this);
        buildHint.setTextColor(SUB); buildHint.setTextSize(13);
        buildHint.setText("Add episodes, then Play now or Save as a playlist tile.");
        root.addView(buildHint);

        LinearLayout addRow = new LinearLayout(this); addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.addView(cardBtn("＋  Add from library", v -> addFromLibrary()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        Button ph = cardBtn("Phone files (soon)", null);
        ph.setEnabled(false); ph.setAlpha(0.45f);
        addRow.addView(ph, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.setMargins(0, dp(8), 0, dp(4));
        root.addView(addRow, arlp);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        buildList = new LinearLayout(this);
        buildList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(buildList);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actRow = new LinearLayout(this); actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(cardBtn("▶  Play now", v -> buildPlayNow()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button save = cardBtn("💾  Save", v -> buildSave());
        save.setBackgroundColor(ACCENT);
        actRow.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aclp.setMargins(0, dp(6), 0, 0);
        root.addView(actRow, aclp);

        renderBuildSet();
        return root;
    }

    void renderBuildSet() {
        if (buildList == null) return;
        buildList.removeAllViews();
        if (mBuildSet.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Nothing added yet."); t.setTextColor(SUB); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(30), 0, 0);
            buildList.addView(t);
            if (buildHint != null) buildHint.setText("Add episodes, then Play now or Save as a playlist tile.");
            return;
        }
        for (int i = 0; i < mBuildSet.size(); i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(roundRect(CARD, 0x22FFFFFF, dp(1), dp(10)));
            r.setPadding(dp(12), dp(10), dp(6), dp(10));
            TextView lab = new TextView(this);
            lab.setText(mBuildSet.get(idx)[0]); lab.setTextColor(TXT); lab.setTextSize(15);
            r.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button rm = plainBtn("✕", v -> { if (idx < mBuildSet.size()) mBuildSet.remove(idx); renderBuildSet(); });
            rm.setTextColor(SUB);
            r.addView(rm, new LinearLayout.LayoutParams(dp(44), dp(40)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(4), 0, dp(4));
            buildList.addView(r, rlp);
        }
        if (buildHint != null) buildHint.setText(mBuildSet.size() + " item(s) — Play now, or Save as a tile.");
    }

    void addFromLibrary() {
        bg(() -> {
            final java.util.ArrayList<String[]> shows = new java.util.ArrayList<>();
            try {
                JSONArray a = new JSONObject(kodi.rpc("VideoLibrary.GetTVShows", "{\"properties\":[\"title\"],\"sort\":{\"method\":\"title\"}}"))
                    .getJSONObject("result").getJSONArray("tvshows");
                for (int i = 0; i < a.length(); i++) { JSONObject s = a.getJSONObject(i);
                    shows.add(new String[]{ s.optString("label", s.optString("title", "?")), String.valueOf(s.optInt("tvshowid", -1)) }); }
            } catch (Exception e) {}
            ui.post(() -> {
                if (shows.isEmpty()) { Toast.makeText(this, "No shows found — is the tablet connected?", Toast.LENGTH_SHORT).show(); return; }
                final String[] labels = new String[shows.size()];
                for (int i = 0; i < shows.size(); i++) labels[i] = shows.get(i)[0];
                new AlertDialog.Builder(this).setTitle("Add from which show?")
                    .setItems(labels, (d, w) -> pickEpisodes(shows.get(w)[0], Integer.parseInt(shows.get(w)[1])))
                    .setNegativeButton("Cancel", null).show();
            });
        });
    }

    void pickEpisodes(final String showTitle, final int tvshowid) {
        bg(() -> {
            final java.util.ArrayList<String[]> eps = new java.util.ArrayList<>();
            try {
                JSONArray a = new JSONObject(kodi.rpc("VideoLibrary.GetEpisodes",
                        "{\"tvshowid\":" + tvshowid + ",\"properties\":[\"title\",\"season\",\"episode\",\"file\"],\"sort\":{\"method\":\"episode\"}}"))
                    .getJSONObject("result").getJSONArray("episodes");
                for (int i = 0; i < a.length(); i++) { JSONObject e = a.getJSONObject(i);
                    String el = e.optString("label", "");
                    if (el.length() == 0) el = "S" + e.optInt("season") + "E" + e.optInt("episode");
                    eps.add(new String[]{ showTitle + " · " + el, e.optString("file", ""), String.valueOf(e.optInt("episodeid", -1)) }); }
            } catch (Exception e) {}
            ui.post(() -> {
                if (eps.isEmpty()) { Toast.makeText(this, "No episodes", Toast.LENGTH_SHORT).show(); return; }
                final String[] labels = new String[eps.size()];
                final boolean[] checked = new boolean[eps.size()];
                for (int i = 0; i < eps.size(); i++) labels[i] = eps.get(i)[0];
                new AlertDialog.Builder(this).setTitle(showTitle)
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Add", (d, w) -> {
                        int n = 0; for (int i = 0; i < eps.size(); i++) if (checked[i]) { mBuildSet.add(eps.get(i)); n++; }
                        renderBuildSet();
                        if (n > 0) Toast.makeText(this, "Added " + n, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null).show();
            });
        });
    }

    void buildPlayNow() {
        if (mBuildSet.isEmpty()) { Toast.makeText(this, "Add something first", Toast.LENGTH_SHORT).show(); return; }
        final java.util.ArrayList<String[]> items = new java.util.ArrayList<>(mBuildSet);
        Toast.makeText(this, "Playing " + items.size() + "…", Toast.LENGTH_SHORT).show();
        bg(() -> {
            kodi.rpc("Playlist.Clear", "{\"playlistid\":1}");
            for (String[] it : items) {
                int epid = -1; try { epid = Integer.parseInt(it[2]); } catch (Exception e) {}
                if (epid >= 0) kodi.rpc("Playlist.Add", "{\"playlistid\":1,\"item\":{\"episodeid\":" + epid + "}}");
            }
            kodi.rpc("Player.Open", "{\"item\":{\"playlistid\":1}}");
            try { Thread.sleep(500); } catch (Exception e) {}
            kodi.rpc("Player.SetShuffle", "{\"playerid\":1,\"shuffle\":false}");
            kodi.rpc("Player.SetRepeat", "{\"playerid\":1,\"repeat\":\"all\"}");
        });
    }

    void buildSave() {
        if (mBuildSet.isEmpty()) { Toast.makeText(this, "Add something first", Toast.LENGTH_SHORT).show(); return; }
        final EditText name = new EditText(this);
        name.setHint("Playlist name"); name.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Save as playlist").setView(name)
            .setPositiveButton("Save", (d, w) -> {
                final String nm = name.getText().toString().trim();
                if (nm.length() == 0) { Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show(); return; }
                final java.util.ArrayList<String[]> items = new java.util.ArrayList<>(mBuildSet);
                Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
                bg(() -> {
                    StringBuilder arr = new StringBuilder("[");
                    boolean first = true;
                    for (String[] it : items) { String f = it[1]; if (f == null || f.length() == 0) continue; if (!first) arr.append(","); first = false; arr.append("\"").append(jsonEscR(f)).append("\""); }
                    arr.append("]");
                    String body = "{\"name\":\"" + jsonEscR(nm) + "\",\"items\":" + arr + "}";
                    String resp = kodi.bridgePost("/saveplaylist", body);
                    final boolean ok = resp != null && resp.contains("\"ok\":true");
                    ui.post(() -> {
                        Toast.makeText(this, ok ? ("Saved \"" + nm + "\" — see Playlists") : "Save failed", Toast.LENGTH_LONG).show();
                        if (ok) { mBuildSet.clear(); renderBuildSet(); mPlaylists = null; fetchPlaylists(); }
                    });
                });
            })
            .setNegativeButton("Cancel", null).show();
    }

    void playPlaylist(final String label, final String file) {
        final String l = label.toLowerCase();
        // shuffle the all-shows mixes; play a specific show/playlist in order (mirrors the kiosk's rule)
        final boolean shuffle = l.contains("shuffle") || l.contains("all") || file.toLowerCase().contains("shuffleall");
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
        bg(() -> {
            kodi.rpc("Playlist.Clear", "{\"playlistid\":1}");
            kodi.rpc("Playlist.Add", "{\"playlistid\":1,\"item\":{\"directory\":\"" + file + "\"}}");
            kodi.rpc("Player.Open", "{\"item\":{\"playlistid\":1}}");
            try { Thread.sleep(700); } catch (Exception e) {}
            kodi.rpc("Player.SetShuffle", "{\"playerid\":1,\"shuffle\":" + shuffle + "}");
            kodi.rpc("Player.SetRepeat", "{\"playerid\":1,\"repeat\":\"all\"}");
            kodi.rpc("GUI.SetFullscreen", "{\"fullscreen\":true}");   // don't leave Kodi on its menu
        });
    }

    /** zZ "Go to sleep": optionally start the configured playlist, then tell the tablet to ramp its
        volume + screen brightness down over N minutes and turn off (the ramp runs on the tablet). */
    void goToSleep() {
        final int mins = prefs.getInt("sleep_minutes", 20);
        final String plFile = prefs.getString("sleep_playlist", "");        // "" = keep whatever's playing
        final String plLabel = prefs.getString("sleep_playlist_label", "");
        Toast.makeText(this, "Sleep: fading out over " + mins + " min…", Toast.LENGTH_LONG).show();
        bg(() -> {
            if (plFile.length() > 0) {
                String l = plLabel.toLowerCase();
                boolean shuffle = l.contains("shuffle") || l.contains("all") || plFile.toLowerCase().contains("shuffleall");
                kodi.rpc("Playlist.Clear", "{\"playlistid\":1}");
                kodi.rpc("Playlist.Add", "{\"playlistid\":1,\"item\":{\"directory\":\"" + jsonEscR(plFile) + "\"}}");
                kodi.rpc("Player.Open", "{\"item\":{\"playlistid\":1}}");
                try { Thread.sleep(700); } catch (Exception e) {}
                kodi.rpc("Player.SetShuffle", "{\"playerid\":1,\"shuffle\":" + shuffle + "}");
                kodi.rpc("Player.SetRepeat", "{\"playerid\":1,\"repeat\":\"all\"}");
                kodi.rpc("GUI.SetFullscreen", "{\"fullscreen\":true}");
            }
            boolean ok = kodi.bridgePost("/sleep", "{\"minutes\":" + mins + "}") != null;
            if (!ok) ui.post(() -> Toast.makeText(this, "Couldn't reach the tablet", Toast.LENGTH_SHORT).show());
        });
    }

    void togglePower() {
        final boolean toNap = !napping;
        napping = toNap;
        updatePowerBtn();
        // Route to the kiosk's 8080 bridge (NOT Kodi's 9090): the bridge intercepts these to truly
        // nap/wake the tablet - stop playback + blank & LOCK the screen (dpm.lockNow), and back.
        // The old 9090 path never reached that (Kodi can't power off Android), so the button did
        // nothing useful and could even wedge Kodi.
        final String m = toNap ? "System.Shutdown" : "System.Reboot";
        bg(() -> {
            boolean ok = kodi.bridge("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + m + "\"}");
            if (!ok) { napping = !toNap; updatePowerBtn(); ui.post(() -> Toast.makeText(this, "Couldn't reach the tablet", Toast.LENGTH_SHORT).show()); }
        });
    }

    void updatePowerBtn() { ui.post(() -> powerBtn.setColors(BTN, napping ? WARN : GOOD)); }

    static String mmss(int sec) { return String.format("%d:%02d", sec / 60, sec % 60); }

    void volDelta(final int d) {
        final int next = Math.max(0, Math.min(100, curVol + d));
        curVol = next;
        bg(() -> kodi.rpc("Application.SetVolume", "{\"volume\":" + next + "}"));
    }

    void toggleMute() {
        final boolean m = !muted;
        muted = m;
        bg(() -> kodi.rpc("Application.SetMute", "{\"mute\":" + m + "}"));
    }

    void showQueue() {
        bg(() -> {
            String r = kodi.rpc("Playlist.GetItems", "{\"playlistid\":1,\"limits\":{\"start\":0,\"end\":40},\"properties\":[\"title\",\"showtitle\",\"season\",\"episode\"]}");
            final StringBuilder sb = new StringBuilder();
            try {
                JSONArray items = new JSONObject(r).getJSONObject("result").getJSONArray("items");
                for (int i = 0; i < items.length(); i++) sb.append(i + 1).append(". ").append(label(items.getJSONObject(i))).append("\n");
            } catch (Exception e) { sb.append("(queue unavailable)"); }
            ui.post(() -> new AlertDialog.Builder(this).setTitle("Queue").setMessage(sb.toString()).setPositiveButton("Close", null).show());
        });
    }

    // ---------------- polling ----------------

    void startPoll() {
        if (polling) return;
        polling = true;
        bg(() -> {
            while (polling) {
                poll();
                try { Thread.sleep(1000); } catch (Exception e) {}
            }
        });
    }

    void poll() {
        if (!kodi.connected()) {
            ui.post(() -> { statusDot.setText("● scanning…"); statusDot.setTextColor(SUB); });
            kodi.findHost();
        }
        final boolean conn = kodi.connected();
        if (conn && kodi.activeHost() != null) prefs.edit().putString("lastip", kodi.activeHost()).apply();
        if (conn && mPlaylists == null) fetchPlaylists();   // load the device's real playlists once
        final String label = kodi.activeLabel();
        String active = conn ? kodi.rpc("Player.GetActivePlayers", null) : null;
        boolean playing = active != null && active.contains("\"playerid\"");

        String title = "Not playing";
        String time = "0:00 / 0:00";
        int prog = 0;
        String next = "Up next: —";
        int vol = curVol; boolean mut = muted;

        if (playing) {
            try {
                JSONObject it = new JSONObject(kodi.rpc("Player.GetItem", "{\"playerid\":1,\"properties\":[\"showtitle\",\"season\",\"episode\",\"title\"]}"))
                        .getJSONObject("result").getJSONObject("item");
                title = label(it);
            } catch (Exception e) {}
            try {
                JSONObject pr = new JSONObject(kodi.rpc("Player.GetProperties", "{\"playerid\":1,\"properties\":[\"percentage\",\"time\",\"totaltime\"]}")).getJSONObject("result");
                prog = (int) Math.round(pr.optDouble("percentage", 0) * 10);
                time = fmt(pr.optJSONObject("time")) + " / " + fmt(pr.optJSONObject("totaltime"));
            } catch (Exception e) {}
            try {
                JSONArray items = new JSONObject(kodi.rpc("Playlist.GetItems", "{\"playlistid\":1,\"limits\":{\"start\":0,\"end\":3},\"properties\":[\"title\",\"showtitle\",\"season\",\"episode\"]}"))
                        .getJSONObject("result").getJSONArray("items");
                StringBuilder sb = new StringBuilder("Up next: ");
                for (int i = 0; i < items.length(); i++) { if (i > 0) sb.append("  ›  "); sb.append(label(items.getJSONObject(i))); }
                if (items.length() > 0) next = sb.toString();
            } catch (Exception e) {}
        }
        if (conn) {
            try {
                JSONObject ap = new JSONObject(kodi.rpc("Application.GetProperties", "{\"properties\":[\"volume\",\"muted\"]}")).getJSONObject("result");
                vol = ap.optInt("volume", curVol); mut = ap.optBoolean("muted", muted);
            } catch (Exception e) {}
        }

        curVol = vol; muted = mut;
        // Shared power/sleep state from the kiosk (so ALL remotes agree, not a local guess).
        boolean napped = napping; int sleepRem = 0;
        if (conn) {
            try {
                JSONObject st = new JSONObject(kodi.bridgeGet("/state")).getJSONObject("result");
                napped = st.optBoolean("napped", false);
                sleepRem = st.optInt("sleep_remaining", 0);
            } catch (Exception e) {}
        }
        napping = napped;
        final boolean hotOn = kodi.isHotspotOn();   // the phone's own AP that the tablet joins
        final String fTitle = title, fTime = time, fNext = next;
        final int fProg = prog, fVol = vol, fSleepRem = sleepRem;
        final boolean fMut = mut, fPlaying = playing, fNapped = napped;
        ui.post(() -> {
            hotspotChip.setColors(hotOn ? GOOD : WARN, TXT);   // green = on, red = off
            powerBtn.setColors(BTN, fNapped ? WARN : GOOD);    // real nap state drives the colour
            if (fSleepRem > 0) { statusDot.setText("● Sleep " + mmss(fSleepRem)); statusDot.setTextColor(WARN); }
            else { statusDot.setText("● " + (conn ? label : "offline")); statusDot.setTextColor(conn ? GOOD : WARN); }
            nowTitle.setText(fTitle);
            timeLabel.setText(fTime);
            if (!seeking) seek.setProgress(fProg);
            upNext.setText(fNext);
            if (playPause != null) playPause.setPlaying(fPlaying);
            volLabel.setText(fMut ? "Muted" : ("Vol " + fVol));
        });
    }

    String label(JSONObject it) {
        String show = it.optString("showtitle", "");
        int s = it.optInt("season", -1), e = it.optInt("episode", -1);
        String t = it.optString("title", "");
        if (t.length() == 0 || t.equals("null")) t = it.optString("label", "");
        StringBuilder sb = new StringBuilder();
        if (show.length() > 0 && !show.equals("null")) sb.append(show);
        if (s > 0 && e > 0) sb.append(sb.length() > 0 ? " " : "").append(String.format("S%dE%02d", s, e));
        if (t.length() > 0 && !t.equals("null")) sb.append(sb.length() > 0 ? " – " : "").append(t);
        return sb.length() > 0 ? sb.toString() : "Playing";
    }

    String fmt(JSONObject t) {
        if (t == null) return "0:00";
        int h = t.optInt("hours", 0), m = t.optInt("minutes", 0), s = t.optInt("seconds", 0);
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ---------------- settings ----------------

    /** Tap the status chip: show where we're connected, or offer to fix it when we're not. */
    void statusTap() {
        if (kodi.connected()) {
            Toast.makeText(this, "Kodi @ " + kodi.activeHost() + "  (" + kodi.activeLabel() + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Not connected")
            .setMessage("Can't reach the tablet. Make sure your phone's hotspot is on and the tablet has joined it — then it's found automatically.")
            .setPositiveButton("Hotspot settings", (d, w) -> openHotspotSettings())
            .setNeutralButton("Rescan", (d, w) -> rescan())
            .setNegativeButton("Close", null)
            .show();
    }

    void rescan() {
        Toast.makeText(this, "Searching for the tablet…", Toast.LENGTH_SHORT).show();
        bg(() -> {
            final boolean ok = kodi.rescan();
            ui.post(() -> Toast.makeText(this, ok ? ("Found Kodi @ " + kodi.activeHost()) : "No tablet found — is the hotspot on?", Toast.LENGTH_LONG).show());
        });
    }

    /** No sideloaded app can flip the real hotspot itself (signature permission) — deep-link the toggle. */
    void openHotspotSettings() {
        try {
            Intent i = new Intent();
            i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Exception e2) { Toast.makeText(this, "Open Settings → Connections → Mobile Hotspot", Toast.LENGTH_LONG).show(); }
        }
    }

    /** Ask the launcher to pin the home-screen remote widget (system shows an "Add" confirm). Falls
        back to a hint if the launcher doesn't support programmatic pinning. */
    void addWidgetToHome() {
        try {
            android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(this);
            android.content.ComponentName cn = new android.content.ComponentName(this, RemoteWidgetProvider.class);
            if (awm.isRequestPinAppWidgetSupported()) awm.requestPinAppWidget(cn, null, null);
            else Toast.makeText(this, "Long-press the home screen → Widgets → KidsTV Remote", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Long-press the home screen → Widgets → KidsTV Remote", Toast.LENGTH_LONG).show();
        }
    }

    /** Font-independent top-bar icons drawn with Canvas — the ⏻ power glyph and WiFi emoji tofu on
        this device. WIFI = a dot with arcs fanning up; POWER = the IEC mark (near-complete ring with a
        line through the top gap). Rounded pill background so it reads as a button; recolor via setColors. */
    class IconView extends View {
        static final int WIFI = 0, POWER = 1, PREV = 2, PLAYPAUSE = 3, NEXT = 4, STOP = 5, VOLUP = 6, VOLDOWN = 7, SHUFFLE = 8, SLEEP = 9;
        final int mode;
        int bgCol, iconCol;
        boolean playing = false;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        IconView(android.content.Context c, int mode, int bgCol, int iconCol) {
            super(c);
            this.mode = mode; this.bgCol = bgCol; this.iconCol = iconCol;
            setClickable(true);
            bgd.setCornerRadius(dp(mode == SHUFFLE ? 40 : 14));
            bgd.setColor(bgCol);
            setBackground(bgd);
        }
        void setColors(int bg, int icon) {
            boolean ch = false;
            if (bg != bgCol) { bgCol = bg; bgd.setColor(bg); ch = true; }
            if (icon != iconCol) { iconCol = icon; ch = true; }
            if (ch) invalidate();
        }
        void setPlaying(boolean pl) { if (pl != playing) { playing = pl; invalidate(); } }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float s = Math.min(getWidth(), getHeight()) * 0.30f;
            p.setColor(iconCol);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            android.graphics.RectF r;
            switch (mode) {
                case POWER:
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(3f, s * 0.22f));
                    r = new android.graphics.RectF(cx - s, cy - s, cx + s, cy + s);
                    cv.drawArc(r, -63f, 306f, false, p);                    // ring, ~54° gap at top
                    cv.drawLine(cx, cy - s * 1.15f, cx, cy - s * 0.10f, p); // line through the gap
                    break;
                case WIFI: {
                    float baseY = cy + s * 0.70f;
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    cv.drawCircle(cx, baseY, Math.max(2f, s * 0.16f), p);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    for (int i = 1; i <= 3; i++) {
                        float rad = s * 0.42f * i;
                        p.setStrokeWidth(Math.max(2f, s * 0.15f));
                        r = new android.graphics.RectF(cx - rad, baseY - rad, cx + rad, baseY + rad);
                        cv.drawArc(r, 234f, 72f, false, p);
                    }
                    break;
                }
                case PLAYPAUSE:
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    if (playing) {                                          // two bars
                        float bw = s * 0.40f, gap = s * 0.34f;
                        cv.drawRoundRect(new android.graphics.RectF(cx - gap - bw, cy - s, cx - gap, cy + s), dp(2), dp(2), p);
                        cv.drawRoundRect(new android.graphics.RectF(cx + gap, cy - s, cx + gap + bw, cy + s), dp(2), dp(2), p);
                    } else {                                                // play triangle
                        android.graphics.Path tp = new android.graphics.Path();
                        tp.moveTo(cx - s * 0.60f, cy - s);
                        tp.lineTo(cx + s * 0.95f, cy);
                        tp.lineTo(cx - s * 0.60f, cy + s);
                        tp.close();
                        cv.drawPath(tp, p);
                    }
                    break;
                case PREV:
                case NEXT: {                                                // triangle + end bar
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    int dir = (mode == NEXT) ? 1 : -1;
                    float bw = Math.max(2f, s * 0.16f);
                    float bx = cx + dir * s * 0.9f;
                    cv.drawRoundRect(new android.graphics.RectF(bx - bw, cy - s, bx + bw, cy + s), bw, bw, p);
                    android.graphics.Path tp = new android.graphics.Path();
                    tp.moveTo(cx + dir * s * 0.85f, cy);
                    tp.lineTo(cx - dir * s * 0.55f, cy - s);
                    tp.lineTo(cx - dir * s * 0.55f, cy + s);
                    tp.close();
                    cv.drawPath(tp, p);
                    break;
                }
                case STOP:
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    cv.drawRoundRect(new android.graphics.RectF(cx - s * 0.82f, cy - s * 0.82f, cx + s * 0.82f, cy + s * 0.82f), dp(3), dp(3), p);
                    break;
                case VOLUP:
                case VOLDOWN:                                               // − / +
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(3f, s * 0.24f));
                    cv.drawLine(cx - s, cy, cx + s, cy, p);
                    if (mode == VOLUP) cv.drawLine(cx, cy - s, cx, cy + s, p);
                    break;
                case SLEEP: {                                             // "Zz" — a big Z + small z
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2.5f, s * 0.17f));
                    float bl = cx - s, bt = cy - s * 0.10f, br = cx + s * 0.30f, bb = cy + s * 0.95f;
                    cv.drawLine(bl, bt, br, bt, p);                        // big Z: top
                    cv.drawLine(br, bt, bl, bb, p);                        //        diagonal
                    cv.drawLine(bl, bb, br, bb, p);                        //        bottom
                    float sl = cx + s * 0.22f, st = cy - s * 0.98f, sr = cx + s, sb = cy - s * 0.25f;
                    cv.drawLine(sl, st, sr, st, p);                        // small z: top
                    cv.drawLine(sr, st, sl, sb, p);                        //         diagonal
                    cv.drawLine(sl, sb, sr, sb, p);                        //         bottom
                    break;
                }
                case SHUFFLE: {                                            // crossed arrows
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, s * 0.16f));
                    float t = s;
                    cv.drawLine(cx - t, cy - t * 0.55f, cx + t, cy + t * 0.55f, p);
                    cv.drawLine(cx - t, cy + t * 0.55f, cx + t, cy - t * 0.55f, p);
                    cv.drawLine(cx + t, cy + t * 0.55f, cx + t * 0.45f, cy + t * 0.55f, p);
                    cv.drawLine(cx + t, cy + t * 0.55f, cx + t, cy + t * 0.02f, p);
                    cv.drawLine(cx + t, cy - t * 0.55f, cx + t * 0.45f, cy - t * 0.55f, p);
                    cv.drawLine(cx + t, cy - t * 0.55f, cx + t, cy - t * 0.02f, p);
                    break;
                }
            }
        }
    }

    /** Open Settings as a real in-app page (slides over the main UI), not a popup dialog. */
    void settings() {
        if (mSettingsView != null) return;
        mSettingsView = settingsPage();
        mRoot.addView(mSettingsView);
        if (mMainUi != null) mMainUi.setVisibility(View.GONE);
    }

    /** Leave the Settings page: persist the IP overrides + show the main UI again. */
    void closeSettings() {
        if (mSettingsView == null) return;
        if (mWifiEt != null && mBtEt != null) {
            String w1 = mWifiEt.getText().toString().trim(), bb = mBtEt.getText().toString().trim();
            prefs.edit().putString("wifi", w1).putString("bt", bb).apply();
            kodi.setPinned(w1, bb);   // everything else on the page persists as you change it
        }
        mRoot.removeView(mSettingsView);
        mSettingsView = null; mWifiEt = null; mBtEt = null;
        if (mMainUi != null) mMainUi.setVisibility(View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (mSettingsView != null) { closeSettings(); return; }
        super.onBackPressed();
    }

    View settingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        // header with a back arrow (matches the Playlists sub-page back header)
        Button back = plainBtn("‹  Settings", v -> closeSettings());
        back.setTextColor(TXT); back.setTextSize(19); back.setAllCaps(false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setBackgroundColor(CARD);
        back.setPadding(dp(12), 0, dp(12), 0);
        page.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(24));

        // --- Connection ---
        sectionHeader(box, "Connection");
        TextView info = new TextView(this);
        info.setText("Finds the tablet automatically on your hotspot. IPs are optional overrides — blank = auto.");
        info.setTextColor(SUB); info.setTextSize(12);
        box.addView(info);
        mWifiEt = new EditText(this);
        mWifiEt.setHint("auto"); mWifiEt.setInputType(InputType.TYPE_CLASS_TEXT); mWifiEt.setText(prefs.getString("wifi", ""));
        mBtEt = new EditText(this);
        mBtEt.setHint("auto"); mBtEt.setInputType(InputType.TYPE_CLASS_TEXT); mBtEt.setText(prefs.getString("bt", ""));
        TextView l1 = new TextView(this); l1.setText("WiFi IP (optional)"); l1.setTextColor(TXT); box.addView(l1); box.addView(mWifiEt);
        TextView l2 = new TextView(this); l2.setText("Bluetooth IP (optional)"); l2.setTextColor(TXT); box.addView(l2); box.addView(mBtEt);
        LinearLayout.LayoutParams lpf = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)); lpf.setMargins(0, dp(10), 0, 0);
        LinearLayout.LayoutParams lph = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)); lph.setMargins(0, dp(6), 0, 0);
        box.addView(cardBtn("🔍  Find tablet now", v -> rescan()), lpf);
        box.addView(cardBtn("📶  Hotspot settings", v -> openHotspotSettings()), lph);
        box.addView(cardBtn("➕  Add remote widget to home screen", v -> addWidgetToHome()), lph);

        // --- Playback ---
        sectionHeader(box, "Playback");
        TextView vlab = new TextView(this); vlab.setText("Volume step (the ± buttons)"); vlab.setTextColor(TXT); box.addView(vlab);
        LinearLayout vrow = new LinearLayout(this); vrow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] steps = {2, 5, 8, 10};
        final int cur = volStep();
        final Button[] stepBtns = new Button[steps.length];
        for (int i = 0; i < steps.length; i++) {
            final int st = steps[i];
            Button sb = cardBtn(String.valueOf(st), null);
            sb.setBackgroundColor(st == cur ? ACCENT : BTN);
            sb.setOnClickListener(v -> { prefs.edit().putInt("vol_step", st).apply(); for (int j = 0; j < steps.length; j++) stepBtns[j].setBackgroundColor(steps[j] == st ? ACCENT : BTN); });
            stepBtns[i] = sb;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(dp(4), dp(6), dp(4), dp(6));
            vrow.addView(sb, lp);
        }
        box.addView(vrow);

        // --- Sleep timer (the zZ "Go to sleep" button on Controls) ---
        sectionHeader(box, "Sleep timer");
        TextView slpInfo = new TextView(this);
        slpInfo.setText("“Go to sleep” fades the tablet's volume + screen brightness down, then turns it off.");
        slpInfo.setTextColor(SUB); slpInfo.setTextSize(12); box.addView(slpInfo);
        final Button sleepPlBtn = cardBtn(sleepPlBtnLabel(), null);
        sleepPlBtn.setTextSize(15);
        sleepPlBtn.setOnClickListener(v -> chooseSleepPlaylist(sleepPlBtn));
        LinearLayout.LayoutParams lpspl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)); lpspl.setMargins(0, dp(6), 0, 0);
        box.addView(sleepPlBtn, lpspl);
        TextView dlab = new TextView(this); dlab.setText("Fade duration (minutes)"); dlab.setTextColor(TXT); dlab.setPadding(0, dp(8), 0, 0); box.addView(dlab);
        LinearLayout drow = new LinearLayout(this); drow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] mins = {15, 20, 30, 45};
        final int curMin = prefs.getInt("sleep_minutes", 20);
        final Button[] minBtns = new Button[mins.length];
        for (int i = 0; i < mins.length; i++) {
            final int mm = mins[i];
            Button mb = cardBtn(String.valueOf(mm), null);
            mb.setBackgroundColor(mm == curMin ? ACCENT : BTN);
            mb.setOnClickListener(v -> { prefs.edit().putInt("sleep_minutes", mm).apply(); for (int j = 0; j < mins.length; j++) minBtns[j].setBackgroundColor(mins[j] == mm ? ACCENT : BTN); });
            minBtns[i] = mb;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(dp(4), dp(6), dp(4), dp(6));
            drow.addView(mb, lp);
        }
        box.addView(drow);

        // --- Playlist grid layout (moved here from the Playlists tab) ---
        sectionHeader(box, "Playlist grid layout");
        LinearLayout lrow = new LinearLayout(this); lrow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] cvals = {PL_LIST, 2, 3, 4};
        final String[] clabs = {"List", "2", "3", "4"};
        final int curCols = plCols();
        final Button[] colBtns = new Button[cvals.length];
        for (int i = 0; i < cvals.length; i++) {
            final int cvv = cvals[i];
            Button cb2 = cardBtn(clabs[i], null);
            cb2.setBackgroundColor(cvv == curCols ? ACCENT : BTN);
            cb2.setOnClickListener(v -> { prefs.edit().putInt("pl_cols", cvv).apply(); for (int j = 0; j < cvals.length; j++) colBtns[j].setBackgroundColor(cvals[j] == cvv ? ACCENT : BTN); renderPlaylists(); });
            colBtns[i] = cb2;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(dp(4), dp(6), dp(4), dp(6));
            lrow.addView(cb2, lp);
        }
        box.addView(lrow);

        // --- Imports (shared setting on the tablet; where phone->tablet imports land) ---
        sectionHeader(box, "Imports");
        final TextView idl = new TextView(this);
        idl.setTextColor(TXT); idl.setText("Import folder: (loading…)"); idl.setPadding(0, dp(2), 0, dp(4));
        box.addView(idl);
        final LinearLayout ivrow = new LinearLayout(this); ivrow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(ivrow);
        bg(() -> {
            String resp = kodi.bridgeGet("/importdir");
            String curDir = "";
            final java.util.ArrayList<String[]> vols = new java.util.ArrayList<>();
            try {
                JSONObject res = new JSONObject(resp).getJSONObject("result");
                curDir = res.optString("dir", "");
                JSONArray va = res.optJSONArray("volumes");
                if (va != null) for (int i = 0; i < va.length(); i++) { JSONObject v = va.getJSONObject(i); vols.add(new String[]{ v.optString("label", "?"), v.optString("path", "") }); }
            } catch (Exception e) {}
            final String curImp = curDir;
            ui.post(() -> {
                idl.setText("Import folder: " + (curImp.length() > 0 ? curImp : "(unknown — tablet offline?)"));
                for (final String[] v : vols) {
                    final String path = v[1] + "Download/";
                    Button vb = cardBtn(v[0], null);
                    vb.setTextSize(13);
                    vb.setOnClickListener(w -> bg(() -> {
                        String r = kodi.bridgePost("/importdir", "{\"dir\":\"" + jsonEscR(path) + "\"}");
                        final boolean ok = r != null && r.contains("\"ok\":true");
                        ui.post(() -> { if (ok) idl.setText("Import folder: " + path); Toast.makeText(this, ok ? ("Imports → " + path) : "Couldn't set", Toast.LENGTH_SHORT).show(); });
                    }));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(dp(4), dp(6), dp(4), dp(6));
                    ivrow.addView(vb, lp);
                }
            });
        });

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    void sectionHeader(LinearLayout box, String title) {
        TextView h = new TextView(this);
        h.setText(title); h.setTextColor(ACCENT); h.setTextSize(15);
        h.setPadding(0, dp(18), 0, dp(4));
        box.addView(h);
    }

    int volStep() { return prefs.getInt("vol_step", 8); }

    /** Label for the sleep-playlist settings button. */
    String sleepPlBtnLabel() {
        String lbl = prefs.getString("sleep_playlist_label", "");
        return "Sleep playlist:  " + (lbl.length() > 0 ? lbl : "Current (keep playing)");
    }

    /** Pick the playlist the "Go to sleep" button starts (or "Current" = keep whatever's playing). */
    void chooseSleepPlaylist(final Button label) {
        String[][] pls = mPlaylists;
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        final java.util.ArrayList<String[]> map = new java.util.ArrayList<>();   // parallel; index 0 = current
        names.add("Current (keep playing)"); map.add(null);
        if (pls != null) for (String[] pl : pls) { names.add(pl[0]); map.add(pl); }
        final String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Play at sleep")
            .setItems(items, (d, which) -> {
                String[] sel = map.get(which);
                if (sel == null) prefs.edit().putString("sleep_playlist", "").putString("sleep_playlist_label", "").apply();
                else prefs.edit().putString("sleep_playlist", sel[1]).putString("sleep_playlist_label", sel[0]).apply();
                if (label != null) label.setText(sleepPlBtnLabel());
            })
            .show();
    }
}
