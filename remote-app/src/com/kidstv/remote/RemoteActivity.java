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

    final KodiClient kodi = new KodiClient();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;

    View playlistsView, controlsView;
    TextView statusDot, nowTitle, timeLabel, upNext, volLabel;
    SeekBar seek;
    Button powerBtn, tabPlaylists, tabControls;
    LinearLayout playlistGrid;              // dynamic playlist tiles live here (rebuilt on fetch)
    volatile String[][] mPlaylists = null;  // {label, file} per real .xsp on the device; null until loaded

    volatile boolean polling = false, seeking = false, napping = false;
    volatile int curVol = 0;
    volatile boolean muted = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        kodi.setPinned(prefs.getString("wifi", ""), prefs.getString("bt", ""));  // optional overrides
        kodi.setLast(prefs.getString("lastip", ""));                             // fast-path cache
        setContentView(buildUi());
        showTab(true);
    }

    @Override protected void onResume() { super.onResume(); startPoll(); }
    @Override protected void onPause()  { super.onPause();  polling = false; }

    // ---------------- UI ----------------

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(topBar());

        FrameLayout content = new FrameLayout(this);
        playlistsView = playlistsTab();
        controlsView = controlsTab();
        content.addView(playlistsView);
        content.addView(controlsView);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(bottomBar());
        return root;
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

        statusDot = new TextView(this);
        statusDot.setText("● …");
        statusDot.setTextColor(SUB);
        statusDot.setTextSize(13);
        statusDot.setPadding(0, 0, dp(10), 0);
        statusDot.setOnClickListener(v -> statusTap());
        bar.addView(statusDot);

        powerBtn = plainBtn("⏻", v -> togglePower());
        powerBtn.setTextSize(22);
        powerBtn.setTextColor(GOOD);
        bar.addView(powerBtn, new LinearLayout.LayoutParams(dp(52), dp(48)));

        Button gear = plainBtn("⚙", v -> settings());
        gear.setTextSize(22);
        gear.setTextColor(SUB);
        bar.addView(gear, new LinearLayout.LayoutParams(dp(52), dp(48)));
        return bar;
    }

    View playlistsTab() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        playlistGrid = new LinearLayout(this);
        playlistGrid.setOrientation(LinearLayout.VERTICAL);
        playlistGrid.setPadding(dp(10), dp(10), dp(10), dp(10));
        sv.addView(playlistGrid);
        renderPlaylists();   // placeholder until fetchPlaylists() populates mPlaylists
        return sv;
    }

    /** (Re)build the playlist tiles from mPlaylists on the UI thread — two per row, scrollable, since
        the list is now the device's ACTUAL playlists and can be any length. */
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
        for (int r = 0; r < pls.length; r += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1f);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                int idx = r + c;
                if (idx < pls.length) {
                    final String label = pls[idx][0], file = pls[idx][1];
                    row.addView(cardBtn(label, v -> playPlaylist(label, file)), lp);
                } else {
                    row.addView(new View(this), lp);   // keep the final row aligned
                }
            }
            playlistGrid.addView(row);
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

        // transport row
        LinearLayout tr = row();
        tr.addView(cardBtn("⏮", v -> input("Player.GoTo", "{\"playerid\":1,\"to\":\"previous\"}")), eq());
        tr.addView(cardBtn("⏯", v -> input("Player.PlayPause", "{\"playerid\":1}")), eq());
        tr.addView(cardBtn("⏭", v -> input("Player.GoTo", "{\"playerid\":1,\"to\":\"next\"}")), eq());
        tr.addView(cardBtn("⏹", v -> input("Player.Stop", "{\"playerid\":1}")), eq());
        marginTop(tr, 10);
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

        // volume row
        LinearLayout vr = row(); marginTop(vr, 10);
        vr.addView(cardBtn("−", v -> volDelta(-8)), eq());
        volLabel = new TextView(this);
        volLabel.setText("🔊 --");
        volLabel.setTextColor(TXT);
        volLabel.setTextSize(16);
        volLabel.setGravity(Gravity.CENTER);
        vr.addView(volLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        vr.addView(cardBtn("+", v -> volDelta(8)), eq());
        vr.addView(cardBtn("Mute", v -> toggleMute()), eq());
        col.addView(vr);

        upNext = new TextView(this);
        upNext.setText("Up next: —");
        upNext.setTextColor(SUB);
        upNext.setTextSize(13);
        upNext.setMaxLines(2);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        up.setMargins(0, dp(12), 0, 0);
        upNext.setOnClickListener(v -> showQueue());
        col.addView(upNext, up);
        return col;
    }

    LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        tabPlaylists = plainBtn("Playlists", v -> showTab(true));
        tabControls = plainBtn("Controls", v -> showTab(false));
        tabPlaylists.setTextColor(TXT); tabControls.setTextColor(TXT);
        bar.addView(tabPlaylists, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bar.addView(tabControls, new LinearLayout.LayoutParams(0, dp(52), 1f));
        return bar;
    }

    void showTab(boolean playlists) {
        playlistsView.setVisibility(playlists ? View.VISIBLE : View.GONE);
        controlsView.setVisibility(playlists ? View.GONE : View.VISIBLE);
        tabPlaylists.setBackgroundColor(playlists ? ACCENT : CARD);
        tabControls.setBackgroundColor(playlists ? CARD : ACCENT);
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
        b.setBackgroundColor(BTN);
        b.setOnClickListener(onClick);
        return b;
    }

    // ---------------- actions ----------------

    void bg(Runnable r) { new Thread(r).start(); }

    /** Fire-and-forget button command, then refresh soon. */
    void input(final String method, final String params) {
        bg(() -> kodi.rpc(method, params));
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

    void updatePowerBtn() { ui.post(() -> powerBtn.setTextColor(napping ? WARN : GOOD)); }

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
        final String fTitle = title, fTime = time, fNext = next;
        final int fProg = prog, fVol = vol; final boolean fMut = mut;
        ui.post(() -> {
            statusDot.setText("● " + (conn ? label : "offline"));
            statusDot.setTextColor(conn ? GOOD : WARN);
            nowTitle.setText(fTitle);
            timeLabel.setText(fTime);
            if (!seeking) seek.setProgress(fProg);
            upNext.setText(fNext);
            volLabel.setText((fMut ? "🔇 " : "🔊 ") + fVol);
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

    void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(0));

        TextView info = new TextView(this);
        info.setText("The remote finds the tablet automatically on your hotspot. The IPs below are optional overrides — leave blank for auto.");
        info.setTextColor(SUB); info.setTextSize(12);
        box.addView(info);

        final EditText wifi = new EditText(this);
        wifi.setHint("auto");
        wifi.setInputType(InputType.TYPE_CLASS_TEXT);
        wifi.setText(prefs.getString("wifi", ""));
        final EditText bt = new EditText(this);
        bt.setHint("auto");
        bt.setInputType(InputType.TYPE_CLASS_TEXT);
        bt.setText(prefs.getString("bt", ""));
        TextView l1 = new TextView(this); l1.setText("WiFi IP (optional)"); l1.setTextColor(TXT); box.addView(l1); box.addView(wifi);
        TextView l2 = new TextView(this); l2.setText("Bluetooth IP (optional)"); l2.setTextColor(TXT); box.addView(l2); box.addView(bt);

        Button find = cardBtn("🔍  Find tablet now", v -> rescan());
        Button hot = cardBtn("📶  Hotspot settings", v -> openHotspotSettings());
        LinearLayout.LayoutParams lpf = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)); lpf.setMargins(0, dp(12), 0, 0);
        LinearLayout.LayoutParams lph = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)); lph.setMargins(0, dp(8), 0, 0);
        box.addView(find, lpf);
        box.addView(hot, lph);

        new AlertDialog.Builder(this)
            .setTitle("Connection")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String w1 = wifi.getText().toString().trim(), bb = bt.getText().toString().trim();
                prefs.edit().putString("wifi", w1).putString("bt", bb).apply();
                kodi.setPinned(w1, bb);
            })
            .setNegativeButton("Close", null)
            .show();
    }
}
