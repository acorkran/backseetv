package com.kidstv.kidodi;

// ============================================================================
//  TV-side admin settings (behind the unlock gesture + PIN). The kiosk is the
//  standalone core, so everything a remote can configure is configurable HERE
//  too, with no phone around: the unlock gesture, the PIN, the kiosk lock,
//  Wi-Fi (join via QR / host a hotspot + show its QR), the camera stills,
//  toddler play mode, and a status line. Values persist in app prefs (Cfg);
//  adb `settings put global kidodi_*` remains a dev-only fallback.
// ============================================================================

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private static final int BG = 0xFF101418, CARD = 0xFF1B2128, TXT = 0xFFF2F4F7, SUB = 0xFF9AA4B2;
    private LinearLayout col;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(20), dp(24), dp(24));
        sv.addView(col); setContentView(sv);
        build();
    }
    @Override protected void onResume() { super.onResume(); refreshStatus(); }

    private void build() {
        col.removeAllViews();
        TextView h = new TextView(this); h.setText("Kidodi settings"); h.setTextColor(TXT); h.setTextSize(24);
        h.setPadding(0, 0, 0, dp(12)); col.addView(h);
        status = new TextView(this); status.setTextColor(SUB); status.setTextSize(13); status.setPadding(0, 0, 0, dp(16));
        col.addView(status);

        // --- Unlock gesture --------------------------------------------------------------------
        section("Unlock gesture");
        final int[] fingerOpts = {2, 3, 4, 5};
        cycleRow("Fingers", "kidodi_esc_fingers", 4, fingerOpts);
        strCycleRow("Type", "kidodi_esc_mode", "taps", new String[]{"taps", "swipe"});
        cycleRow("Taps needed", "kidodi_esc_taps", 4, new int[]{2, 3, 4, 5, 6});
        cycleRow("Within (seconds)", "kidodi_esc_window_ms", 3000, new int[]{2000, 3000, 5000, 8000}, 1000);
        strCycleRow("Swipe direction", "kidodi_esc_dir", "up", new String[]{"up", "down", "left", "right"});
        note("Default: 4 four-finger taps within 3 seconds, anywhere on screen.");

        // --- PIN + kiosk -----------------------------------------------------------------------
        section("Lock");
        actionRow("Admin PIN", "change", this::changePin);
        boolRow("Kiosk lock (device owner)", "kidodi_kiosk", 0);
        note("Kiosk lock pins Kidodi as the home screen and blocks Home/Back/Recents. Turning it off takes effect on the next launch.");

        // --- Wi-Fi -----------------------------------------------------------------------------
        section("Wi-Fi");
        actionRow("Join a Wi-Fi network", "scan QR", () -> startActivity(new Intent(this, QrScanActivity.class)));
        actionRow("Host a hotspot for remotes", HotspotService.running() ? "turn off" : "turn on", () -> {
            if (HotspotService.running()) HotspotService.stop(this); else HotspotService.start(this);
            postRebuild();
        });
        if (HotspotService.running()) actionRow("Show hotspot QR", "show", () -> {
            String s = HotspotService.currentSsid(), p = HotspotService.currentPass();
            if (s == null) { toast("Hotspot still starting…"); return; }
            startActivity(new Intent(this, QrShareActivity.class)
                    .putExtra(QrShareActivity.EXTRA_PAYLOAD, "WIFI:S:" + s + ";T:WPA;P:" + p + ";;")
                    .putExtra(QrShareActivity.EXTRA_CAPTION, "Scan with the phone remote to join " + s));
        });
        note("Join: point the camera at a Wi-Fi QR (phone: Kidodi remote → Share Wi-Fi). Hotspot: the TV hosts its own network and shows a QR for remotes; live camera works over it.");

        // --- Camera ----------------------------------------------------------------------------
        section("Camera (still images)");
        actionRow("Periodic still snapshots", SnapshotService.running() ? "turn off" : "turn on", () -> {
            SnapshotService.set(this, !SnapshotService.running()); postRebuild();
        });
        note("Front camera, updated a few times a second at most. Remotes show the latest still; nothing is recorded.");

        // --- Play mode -------------------------------------------------------------------------
        section("Toddler play mode");
        actionRow("Start play mode", "start", () -> PlayModeActivity.begin(this));
        note("Keyboard-mash style play screen with Piper voices. Exit with the same unlock gesture.");

        // --- Exit ------------------------------------------------------------------------------
        Button back = new Button(this); back.setText("Back to the show"); back.setAllCaps(false); back.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(24), 0, 0); back.setLayoutParams(lp);
        back.setOnClickListener(v -> finish());
        col.addView(back);
    }

    private void postRebuild() { col.postDelayed(() -> { build(); refreshStatus(); }, 800); }

    // --- rows ------------------------------------------------------------------------------------
    private void section(String t) {
        TextView s = new TextView(this); s.setText(t); s.setTextColor(0xFF8FD694); s.setTextSize(15);
        s.setPadding(0, dp(18), 0, dp(6)); col.addView(s);
    }
    private void note(String t) {
        TextView s = new TextView(this); s.setText(t); s.setTextColor(SUB); s.setTextSize(12);
        s.setPadding(dp(4), dp(2), dp(4), dp(6)); col.addView(s);
    }
    private Button rowShell(String label) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setBackgroundColor(CARD); row.setPadding(dp(14), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(16);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button v = new Button(this); v.setAllCaps(false); v.setTextSize(15); v.setMinWidth(dp(120));
        row.addView(v); col.addView(row);
        return v;
    }
    private void actionRow(String label, String btn, Runnable r) {
        Button v = rowShell(label); v.setText(btn); v.setOnClickListener(x -> r.run());
    }
    private void cycleRow(String label, String key, int def, int[] opts) { cycleRow(label, key, def, opts, 1); }
    private void cycleRow(final String label, final String key, final int def, final int[] opts, final int div) {
        final Button v = rowShell(label);
        v.setText(String.valueOf(Cfg.getInt(this, key, def) / div));
        v.setOnClickListener(x -> {
            int cur = Cfg.getInt(this, key, def), next = opts[0];
            for (int i = 0; i < opts.length; i++) if (opts[i] == cur) { next = opts[(i + 1) % opts.length]; break; }
            Cfg.put(this, key, next); v.setText(String.valueOf(next / div));
        });
    }
    private void strCycleRow(final String label, final String key, final String def, final String[] opts) {
        final Button v = rowShell(label);
        v.setText(Cfg.getStr(this, key, def));
        v.setOnClickListener(x -> {
            String cur = Cfg.getStr(this, key, def), next = opts[0];
            for (int i = 0; i < opts.length; i++) if (opts[i].equals(cur)) { next = opts[(i + 1) % opts.length]; break; }
            Cfg.put(this, key, next); v.setText(next);
        });
    }
    private void boolRow(final String label, final String key, final int def) {
        final Button v = rowShell(label);
        v.setText(Cfg.getInt(this, key, def) == 1 ? "on" : "off");
        v.setOnClickListener(x -> { int n = Cfg.getInt(this, key, def) == 1 ? 0 : 1; Cfg.put(this, key, n); v.setText(n == 1 ? "on" : "off"); });
    }

    private void changePin() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), 0);
        final EditText a = new EditText(this), b2 = new EditText(this);
        a.setHint("New PIN (digits)"); b2.setHint("Repeat new PIN");
        for (EditText e : new EditText[]{a, b2}) { e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); box.addView(e); }
        new AlertDialog.Builder(this).setTitle("Change admin PIN").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = a.getText().toString().trim();
                    if (p.length() < 4 || !p.matches("\\d+")) { toast("PIN must be at least 4 digits"); return; }
                    if (!p.equals(b2.getText().toString().trim())) { toast("PINs don't match"); return; }
                    Cfg.put(this, "kidodi_pin", p); toast("PIN changed");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void refreshStatus() {
        new Thread(() -> {
            String ver = "?";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
            String line;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8090/state").openConnection();
                c.setConnectTimeout(1500); c.setReadTimeout(1500);
                java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                line = "v" + ver + "   ·   IP " + o.optString("ip", "—") + "   ·   " + o.optString("playlist", "")
                        + "   ·   volume " + Math.round(o.optDouble("volume", 0) * 100) + "%" + (o.optBoolean("muted", false) ? " (muted)" : "");
            } catch (Exception e) { line = "v" + ver + "   ·   player not reachable"; }
            final String s = line;
            runOnUiThread(() -> { if (status != null) status.setText(s); });
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }
}
