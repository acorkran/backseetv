package com.kidstv.kidodi;

// ============================================================================
//  TV-side admin settings (behind the unlock gesture + PIN). The kiosk is the
//  standalone core, so everything a remote can configure is configurable HERE
//  too, with no phone around: the unlock gesture, the PIN, the kiosk lock,
//  light duration, playback (loop), the connection level (Bluetooth / TV
//  hotspot / remote hotspot / shared Wi-Fi), the camera stills, toddler play
//  mode, the optional device-owner setup, and a status line. Values persist in
//  app prefs (Cfg); adb `settings put global kidodi_*` remains a dev fallback.
//  Things the running player must apply NOW go over loopback HTTP (:8090).
// ============================================================================

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
    private static final int BG = 0xFF101418, CARD = 0xFF1B2128, TXT = 0xFFF2F4F7, SUB = 0xFF9AA4B2, OK = 0xFF8FD694;
    static final String DO_CMD = "adb shell dpm set-device-owner com.kidstv.kidodi/.AdminReceiver";
    static final String[][] CONN_MODES = {
            {"ble", "Bluetooth only", "Remotes control the TV over Bluetooth: play, playlists, queue, volume, light, sleep, power, and a still image every second or so."},
            {"tv_hotspot", "TV hotspot", "The TV hosts its own Wi-Fi hotspot (on automatically at this level) and remotes join it. Adds live camera + fast library browsing. Works in the car."},
            {"remote_hotspot", "Remote hotspot", "A phone hosts the hotspot and hands the details to the TV over Bluetooth (or you scan its QR here). Adds live camera + fast library browsing."},
            {"shared", "Shared Wi-Fi", "TV and remotes on the same network, e.g. home Wi-Fi. Join the TV by scanning a Wi-Fi QR, or a remote sends the details."}};
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

        DevicePolicyManager dpmTop = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        final boolean owner = dpmTop != null && dpmTop.isDeviceOwnerApp(getPackageName());

        // --- Unlock gesture (DEVICE-OWNER gated) -----------------------------------------------
        section("Unlock gesture");
        if (owner) {
            cycleRow("Fingers", "kidodi_esc_fingers", 4, new int[]{2, 3, 4, 5});
            strCycleRow("Type", "kidodi_esc_mode", "taps", new String[]{"taps", "swipe"});
            cycleRow("Taps needed", "kidodi_esc_taps", 4, new int[]{2, 3, 4, 5, 6});
            cycleRow("Within (seconds)", "kidodi_esc_window_ms", 3000, new int[]{2000, 3000, 5000, 8000}, 1000);
            strCycleRow("Swipe direction", "kidodi_esc_dir", "up", new String[]{"up", "down", "left", "right"});
            note("Default: 4 four-finger taps within 3 seconds, anywhere on screen.");
        } else {
            dimRow("Fingers", String.valueOf(Cfg.getInt(this, "kidodi_esc_fingers", 4)));
            dimRow("Type", Cfg.getStr(this, "kidodi_esc_mode", "taps"));
            dimRow("Taps needed", String.valueOf(Cfg.getInt(this, "kidodi_esc_taps", 4)));
            dimRow("Within (seconds)", String.valueOf(Cfg.getInt(this, "kidodi_esc_window_ms", 3000) / 1000));
            dimRow("Swipe direction", Cfg.getStr(this, "kidodi_esc_dir", "up"));
            gatedNotice();
        }

        // --- PIN + kiosk (DEVICE-OWNER gated) --------------------------------------------------
        section("Lock");
        if (owner) {
            actionRow("Admin PIN", "change", this::changePin);
            boolRow("Kiosk lock (device owner)", "kidodi_kiosk", 0);
            note("Kiosk lock pins Kidodi as the home screen and blocks Home/Back/Recents. Turning it off takes effect on the next launch.");
        } else {
            dimRow("Admin PIN", "•••• ");
            dimRow("Kiosk lock (device owner)", Cfg.getInt(this, "kidodi_kiosk", 0) == 1 ? "on" : "off");
            gatedNotice();
        }

        // --- Playback --------------------------------------------------------------------------
        section("Playback");
        cycleRow("Light duration (seconds)", "kidodi_flash_ms", 3000, new int[]{1000, 2000, 3000, 5000, 10000}, 1000);
        final boolean loop = Cfg.getInt(this, "kidodi_loop", 1) == 1;
        actionRow("When the queue ends", loop ? "loop forever" : "stop (TV off)", () -> {
            int n = loop ? 0 : 1;
            Cfg.put(this, "kidodi_loop", n); loopback("/loop?on=" + n); postRebuild();
        });
        note("Loop forever keeps the queue going round; stop turns the TV off after the last item, like the sleep timer. Shuffle is per queue — toggle it from a remote's Up next screen.");

        // --- Connection ------------------------------------------------------------------------
        section("Connection level");
        final String mode = Cfg.getStr(this, "kidodi_conn", "ble");
        for (final String[] m : CONN_MODES) {
            final boolean on = m[0].equals(mode);
            Button v = rowShell((on ? "●  " : "○  ") + m[1]);
            v.setText(on ? "current" : "choose"); v.setTextColor(on ? OK : TXT);
            v.setOnClickListener(x -> { Cfg.put(this, "kidodi_conn", m[0]); loopback("/conn?mode=" + m[0]); toast("Connection level: " + m[1]); postRebuild(); });
            note(m[2]);
        }
        boolean hsUp = HotspotService.running() && HotspotService.currentSsid() != null;
        if ("tv_hotspot".equals(mode)) {
            if (hsUp) {
                TextView creds = new TextView(this);
                creds.setText("Hotspot:  " + HotspotService.currentSsid() + "\nPassword:  " + HotspotService.currentPass());
                creds.setTextColor(OK); creds.setTextSize(15); creds.setPadding(dp(14), dp(8), dp(14), dp(8)); creds.setBackgroundColor(CARD);
                col.addView(creds);
            } else note("Hotspot starting… (if it never comes up, the player may need a restart at this level)");
        }
        actionRow("Hotspot", hsUp ? "turn off" : "turn on", () -> {
            if (HotspotService.running()) HotspotService.stop(this); else HotspotService.start(this);
            postRebuild();
        });
        if (hsUp) actionRow("Show hotspot QR", "show", () -> startActivity(new Intent(this, QrShareActivity.class)
                .putExtra(QrShareActivity.EXTRA_PAYLOAD, "WIFI:S:" + qrEsc(HotspotService.currentSsid()) + ";T:WPA;P:" + qrEsc(HotspotService.currentPass()) + ";;")
                .putExtra(QrShareActivity.EXTRA_CAPTION, "Scan with the phone remote to join " + HotspotService.currentSsid())));
        actionRow("Join a Wi-Fi network", "scan QR", () -> startActivity(new Intent(this, QrScanActivity.class)));
        note("Join: point the camera at a Wi-Fi QR (phone remote → Connection → Show as QR). Remotes also learn the TV's IP over Bluetooth once it's on a network.");

        // --- Camera ----------------------------------------------------------------------------
        section("Camera (still images)");
        actionRow("Periodic still snapshots", SnapshotService.running() ? "turn off" : "turn on", () -> {
            SnapshotService.set(this, !SnapshotService.running()); postRebuild();
        });
        note("Front camera, updated a few times a second at most. Remotes show the latest still; nothing is recorded.");

        // --- Play mode -------------------------------------------------------------------------
        section("Toddler play mode");
        actionRow("Start play mode", "start", () -> PlayModeActivity.begin(this));
        labelCycleRow("Sound", "kidodi_playmode_sound", "muted",
                new String[]{"muted", "wav", "video"}, new String[]{"Muted", "Letter sounds", "Video sound"});
        note("Runs over the show (the video stays the background). Sound: Muted = silent · Letter sounds = "
                + "the letter voices, show silenced · Video sound = the show keeps playing behind. Exit with the unlock gesture.");

        // --- Device owner ----------------------------------------------------------------------
        section("Kiosk mode (device owner)");
        TextView os = new TextView(this);
        os.setText(owner ? "✓  This tablet is set up as a Kidodi kiosk (the app is the device owner)."
                : "○  Not set up as a kiosk — Kidodi runs as a normal player. The optional setup below gives the locked-down TV.");
        os.setTextColor(owner ? OK : TXT); os.setTextSize(14); os.setPadding(dp(4), dp(4), dp(4), dp(8)); col.addView(os);
        note("The optional full setup is one-time and needs a computer with adb:\n"
                + "1.  Use a tablet with NO Google or Samsung account added (remove them, or set the tablet up fresh and skip sign-in).\n"
                + "2.  Developer options → USB debugging on, plug it in.\n"
                + "3.  Run:   " + DO_CMD + "\n"
                + "4.  Open Kidodi, choose \"This is the TV\", then turn on Kiosk lock above.\n"
                + "What it enables: Kidodi pinned as the home screen, an inescapable lock (Home / Back / Recents blocked), permissions granted silently, "
                + "screen on/off from the remotes, joining Wi-Fi without prompts. Undo it here any time.");
        actionRow("Setup command", "copy", () -> {
            try { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("kidodi", DO_CMD)); toast("Copied"); }
            catch (Exception e) { toast("Couldn't copy"); }
        });
        if (owner) actionRow("Remove device owner", "remove…", this::confirmRemoveOwner);
        actionRow("About Kidodi & feature levels", "open", () -> startActivity(new Intent(this, AboutActivity.class)));

        // --- Exit ------------------------------------------------------------------------------
        Button back = new Button(this); back.setText("Back to the show"); back.setAllCaps(false); back.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(24), 0, 0); back.setLayoutParams(lp);
        back.setOnClickListener(v -> finish());
        col.addView(back);
    }

    private void postRebuild() { col.postDelayed(() -> { build(); refreshStatus(); }, 800); }

    private void confirmRemoveOwner() {
        new AlertDialog.Builder(this).setTitle("Remove device owner?")
                .setMessage("Kidodi stops being the kiosk on this tablet:\n\n• no home-screen pinning or lock (Home/Back/Recents work again)\n"
                        + "• permissions and Wi-Fi joins will prompt\n• remotes can't turn the screen off\n\n"
                        + "The app keeps working as a normal player. Setting it up again needs adb and a tablet with no accounts.")
                .setPositiveButton("Remove", (d, w) -> {
                    try {
                        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                        Cfg.put(this, "kidodi_kiosk", 0);
                        if (dpm != null) dpm.clearDeviceOwnerApp(getPackageName());
                        toast("Device owner removed"); postRebuild();
                    } catch (Exception e) { toast("Couldn't remove: " + e.getMessage()); }
                })
                .setNegativeButton("Keep", null).show();
    }

    // --- rows ------------------------------------------------------------------------------------
    private void section(String t) {
        TextView s = new TextView(this); s.setText(t); s.setTextColor(OK); s.setTextSize(15);
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
    /** Like strCycleRow but shows a friendly label for each raw value. */
    private void labelCycleRow(String label, final String key, final String def, final String[] vals, final String[] labels) {
        final Button v = rowShell(label);
        v.setText(labelFor(Cfg.getStr(this, key, def), vals, labels));
        v.setOnClickListener(x -> {
            String cur = Cfg.getStr(this, key, def), next = vals[0];
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) { next = vals[(i + 1) % vals.length]; break; }
            Cfg.put(this, key, next); v.setText(labelFor(next, vals, labels));
        });
    }
    private static String labelFor(String val, String[] vals, String[] labels) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(val)) return labels[i];
        return val;
    }
    /** A greyed, non-interactive row (a device-owner feature at base level): shows the value, no click. */
    private void dimRow(String label, String value) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setBackgroundColor(0xFF15181C); row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        TextView l = new TextView(this); l.setText("🔒  " + label); l.setTextColor(0xFF5A6470); l.setTextSize(16);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = new TextView(this); vv.setText(value); vv.setTextColor(0xFF4A525C); vv.setTextSize(15); row.addView(vv);
        col.addView(row);
    }
    /** Inline notice under a gated section: tapping opens the About / feature-levels page. */
    private void gatedNotice() {
        TextView t = new TextView(this);
        t.setText("🔒  That feature requires device-owner setup.  Tap here to find out more.");
        t.setTextColor(0xFFE0A63D); t.setTextSize(13); t.setPadding(dp(6), dp(6), dp(6), dp(10)); t.setClickable(true);
        t.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        col.addView(t);
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

    /** Fire-and-forget GET to the running player on loopback (apply a setting now). */
    private void loopback(final String path) {
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try { c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8090" + path).openConnection(); c.setConnectTimeout(1500); c.setReadTimeout(1500); c.getResponseCode(); }
            catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
        }).start();
    }
    private static String qrEsc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\""); }

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
                        + "   ·   volume " + Math.round(o.optDouble("volume", 0) * 100) + "%" + (o.optBoolean("muted", false) ? " (muted)" : "")
                        + (o.has("conn") ? "   ·   " + o.optString("conn").replace('_', ' ') : "");
            } catch (Exception e) { line = "v" + ver + "   ·   player not reachable"; }
            final String s = line;
            runOnUiThread(() -> { if (status != null) status.setText(s); });
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }
}
