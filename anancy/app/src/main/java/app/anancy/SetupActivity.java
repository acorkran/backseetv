package app.anancy;

// ============================================================================
//  Setup check — the screen that tells you WHY the two devices aren't talking,
//  and offers the fix for each thing that's wrong.
//
//  Deliberately NOT a multi-step wizard: pairing over Bluetooth is genuinely
//  zero-step when it works (the remote scans for our service UUID and connects,
//  no bonding, no picker), so a "next, next, finish" flow would be ceremony
//  around nothing. All the value is in the failure cases, which are currently
//  silent — a denied permission or Bluetooth being off just shows up as "not
//  connected" with nothing to act on.
//
//  Role-aware, because the TV and the remote fail for completely different
//  reasons: the remote needs runtime permissions it has to ASK for, while the
//  device-owner TV is granted them silently and instead needs to be advertising,
//  pointed at media, and (optionally) set up as the kiosk.
// ============================================================================

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SetupActivity extends Activity {
    private static final int BG = 0xFF101418, CARD = 0xFF1B2128, TXT = 0xFFF2F4F7,
            SUB = 0xFF9AA4B2, OK = 0xFF8FD694, WARN = 0xFFE8C06A, ACCENT = 0xFF6FA8FF;
    private LinearLayout col;
    private boolean isPlayer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        isPlayer = "player".equals(getSharedPreferences("anancy", MODE_PRIVATE).getString("role", ""));
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), dp(28));
        sv.addView(col); setContentView(sv);
        SysBars.pad(sv);
        render();
    }

    /** Re-run every check on return: the fixes all happen in OTHER apps (system Bluetooth, app
        permissions), so coming back here IS the moment to re-test rather than make them hunt. */
    @Override protected void onResume() { super.onResume(); if (col != null) render(); }

    private void render() {
        col.removeAllViews();
        head(isPlayer ? "Set-up check — this is the TV" : "Set-up check — this is a remote");
        sub(isPlayer
                ? "What the TV needs so remotes can find it and it has something to play."
                : "What this remote needs to find your TV. Fix anything marked below, then come back.");

        boolean ok = true;
        ok &= checkRole();
        ok &= checkBluetooth();
        ok &= isPlayer ? checkAdvertising() : checkBlePerms();
        // TV only: a remote picks files through the system document picker, so it never needs
        // storage access of its own. Checked BEFORE media, because it decides what "no videos" means.
        boolean files = !isPlayer || checkAllFiles();
        ok &= files;
        ok &= isPlayer ? checkMedia(files) : checkScreensFound();
        pairingRow();                        // the fallback when Bluetooth can't do the job
        if (isPlayer) checkDeviceOwner();   // informational: the kiosk tier is optional, never a failure

        if (ok) {
            TextView t = new TextView(this);
            t.setText(isPlayer ? "✓  All good — remotes should find this TV."
                               : "✓  All good — this remote can see your TV.");
            t.setTextColor(OK); t.setTextSize(15); t.setPadding(dp(4), dp(18), dp(4), dp(4));
            col.addView(t);
        }
        Button back = btn("Back", TXT); back.setOnClickListener(v -> finish()); col.addView(back);
    }

    // --- checks ------------------------------------------------------------------------------

    private boolean checkRole() {
        String role = getSharedPreferences("anancy", MODE_PRIVATE).getString("role", "");
        boolean set = "player".equals(role) || "remote".equals(role);
        row(set, "Role", set ? (isPlayer ? "This device is the TV (player)." : "This device is a remote.")
                             : "No role picked yet.",
                set ? "Change role" : "Pick a role", () -> {
                    getSharedPreferences("anancy", MODE_PRIVATE).edit().remove("role").apply();
                    startActivity(new Intent(this, RouterActivity.class)); finish();
                });
        return set;
    }

    private boolean checkBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        boolean on = ad != null && ad.isEnabled();
        row(on, "Bluetooth", on ? "On. This is the link that works with no network at all."
                        : "OFF. Without it the TV and remote cannot find each other in the car.",
                on ? null : "Turn Bluetooth on", () -> open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        return on;
    }

    /** Remote only. The TV is device owner and gets these silently; a plain phone must be asked,
        and if it was ever denied with "don't ask again" nothing in the app re-requests it — the
        link just never works and every screen says "not connected". */
    private boolean checkBlePerms() {
        if (Build.VERSION.SDK_INT < 31) { row(true, "Bluetooth permission", "Not needed on this Android version.", null, null); return true; }
        String[] need = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
        StringBuilder missing = new StringBuilder();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
            missing.append(missing.length() > 0 ? ", " : "").append(p.substring(p.lastIndexOf('.') + 1));
        boolean ok = missing.length() == 0;
        row(ok, "Bluetooth permission", ok ? "Granted."
                        : "Missing: " + missing + ". Nearby Devices must be allowed or this remote can never see the TV.",
                ok ? null : "Open app permissions", this::openAppSettings);
        return ok;
    }

    /** TV only: not every tablet can act as a BLE peripheral, and if it can't, no remote will ever
        find it over Bluetooth however correct everything else is. */
    private boolean checkAdvertising() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        boolean can = false;
        try { can = ad != null && ad.isEnabled() && ad.getBluetoothLeAdvertiser() != null; } catch (Exception ignored) {}
        row(can, "Bluetooth advertising", can
                        ? "This tablet can advertise itself, so remotes can find it."
                        : "This tablet cannot advertise over Bluetooth, so remotes will not find it. Use a Wi-Fi level instead.",
                null, null);
        return can;
    }

    private boolean checkScreensFound() {
        app.anancy.remote.RemoteService svc = app.anancy.remote.RemoteService.get();
        int n = svc != null ? svc.tablets().size() : 0;
        row(n > 0, "TV found", n > 0 ? (n == 1 ? "Found 1 screen." : "Found " + n + " screens.")
                        : "No screen found yet. Open BackSeat TV on the tablet and make sure it is set as the TV.",
                null, null);
        return n > 0;
    }

    /** TV only. Hardcoded folder names today, so saying WHICH ones is the difference between an
        empty library being a mystery and being a two-minute fix. */
    /** TV only, and the one thing that silently empties the library on a fresh install: the shows
        live on a portable SD card, which scoped storage puts out of reach of ordinary File access.
        Being device owner does NOT cover this one -- all-files access is an appop, not a runtime
        permission, so setPermissionGrantState cannot grant it and even the kiosk must be told once. */
    private boolean checkAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            boolean legacy = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            row(legacy, "Storage access", legacy ? "Granted."
                            : "Missing. The library cannot be read without it.",
                    legacy ? null : "Open app permissions", this::openAppSettings);
            return legacy;
        }
        boolean ok = false;
        try { ok = android.os.Environment.isExternalStorageManager(); } catch (Exception ignored) {}
        row(ok, "All files access", ok
                        ? "Granted \u2014 BackSeat TV can read the SD card."
                        : "Not granted, so the SD card is invisible and the library looks empty however "
                          + "many shows are on it. This is a manual grant even for the kiosk.",
                ok ? null : "Grant all files access", () -> {
                    try { startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { openAppSettings(); }
                });
        return ok;
    }

    private boolean checkMedia(boolean allFiles) {
        int shows = 0, files = 0;
        try {
            java.util.LinkedHashMap<String, java.util.List<java.io.File>> m = app.anancy.player.Library.scanShows(this);
            shows = m.size();
            for (java.util.List<java.io.File> f : m.values()) files += f.size();
        } catch (Exception ignored) {}
        boolean ok = files > 0;
        String chosen = Cfg.getStr(this, "anancy_media_dir", "");
        String where = chosen.isEmpty() ? "the default folders (Anancy, KidsTV, Kidodi)" : chosen;
        // Never send them folder-hunting for a permission problem: the picker cannot read the card
        // either, so "choose the media folder" would only show them an empty tree.
        if (!ok && !allFiles) {
            row(false, "Media", "Can't tell yet \u2014 with no all-files access nothing on the SD card "
                    + "is readable. Grant that above, then come back.", null, null);
            return false;
        }
        row(ok, "Media", ok ? shows + " shows, " + files + " videos — from " + where + "."
                        : "No videos found in " + where + ". Point BackSeat TV at the folder your shows are in "
                          + "— one sub-folder per show.",
                ok ? null : "Choose the media folder",
                () -> startActivity(new Intent(this, FolderPickerActivity.class)));
        return ok;
    }

    /** QR pairing. Bluetooth finds the TV on its own when it can, so this is the fallback for when
        it can't: Bluetooth off or unavailable, a tablet that can't advertise, or a remote that
        simply isn't being found. One scan carries BOTH the TV's network and its address, so it
        works from a standing start with no prior link of any kind. */
    private void pairingRow() {
        if (isPlayer) {
            info("Pair a remote by QR", "Puts a full-screen code on this TV carrying its Wi-Fi details "
                    + "and address. Scan it from the remote's set-up check.",
                    "Show pairing QR", () -> startActivity(new Intent(this, app.anancy.net.QrShareActivity.class)
                            .putExtra(app.anancy.net.QrShareActivity.EXTRA_PAIR, true)));
            return;
        }
        String ip = Cfg.getStr(this, "anancy_paired_ip", "");
        String nm = Cfg.getStr(this, "anancy_paired_name", "");
        info("Pair with a TV by QR", ip.isEmpty()
                        ? "Optional. On the TV: Set-up check → Show pairing QR, then scan it here."
                        : "Paired with " + (nm.isEmpty() ? "a TV" : nm) + " at " + ip + ".",
                "Scan the TV's QR", () -> {
                    try { startActivityForResult(new Intent(this, app.anancy.net.QrScanActivity.class), 9); }
                    catch (Exception e) { toast("Couldn't open the scanner"); }
                });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 9 || res != RESULT_OK || data == null) return;
        String ip = data.getStringExtra(app.anancy.net.QrScanActivity.RESULT_ANANCY_IP);
        String nm = data.getStringExtra(app.anancy.net.QrScanActivity.RESULT_TV_NAME);
        String ssid = data.getStringExtra(app.anancy.net.QrScanActivity.RESULT_JOINED_SSID);
        if (ip == null && ssid == null) { toast("That wasn't an BackSeat TV pairing code"); return; }
        if (ip != null) Cfg.put(this, "anancy_paired_ip", ip);
        if (nm != null) Cfg.put(this, "anancy_paired_name", nm);
        toast(ssid != null ? "Joining " + ssid + "…" : "Paired with " + (nm == null ? ip : nm));
        render();
    }

    /** Informational only — the kiosk tier is optional, so a plain player must not be told it failed. */
    private void checkDeviceOwner() {
        boolean owner = false;
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            owner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());
        } catch (Exception ignored) {}
        if (owner) { row(true, "Kiosk mode", "Set up as device owner: pinned as home, inescapable lock, silent Wi-Fi joins.", null, null); return; }
        info("Kiosk mode (optional)", "BackSeat TV works fine as a normal player. The locked-down appliance is a one-time "
                + "setup from a computer with adb, on a tablet with no Google or Samsung account:\n\n"
                + SettingsCmd() + "\n\nThen reopen BackSeat TV and turn on Kiosk lock in Settings.",
                "Copy command", () -> {
                    try { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("anancy", SettingsCmd())); toast("Copied"); }
                    catch (Exception e) { toast("Couldn't copy"); }
                });
    }
    private static String SettingsCmd() {
        return "adb shell dpm set-device-owner app.anancy/.admin.AdminReceiver";
    }

    // --- tiny view helpers --------------------------------------------------------------------

    private void row(boolean ok, String title, String detail, String action, Runnable onAction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); card.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText((ok ? "✓  " : "✕  ") + title);
        h.setTextColor(ok ? OK : WARN); h.setTextSize(16);
        card.addView(h);

        TextView d = new TextView(this);
        d.setText(detail); d.setTextColor(SUB); d.setTextSize(13); d.setPadding(0, dp(5), 0, 0);
        card.addView(d);

        if (action != null && onAction != null) {
            Button b = btn(action, ACCENT);
            b.setOnClickListener(v -> onAction.run());
            card.addView(b);
        }
        col.addView(card);
    }
    private void info(String title, String detail, String action, Runnable onAction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); card.setLayoutParams(lp);
        TextView h = new TextView(this); h.setText("○  " + title); h.setTextColor(ACCENT); h.setTextSize(16); card.addView(h);
        TextView d = new TextView(this); d.setText(detail); d.setTextColor(SUB); d.setTextSize(13); d.setPadding(0, dp(5), 0, 0); card.addView(d);
        if (action != null && onAction != null) { Button b = btn(action, ACCENT); b.setOnClickListener(v -> onAction.run()); card.addView(b); }
        col.addView(card);
    }

    private Button btn(String label, int colour) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(colour);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8); b.setLayoutParams(lp);
        return b;
    }
    private void head(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(TXT); v.setTextSize(21); col.addView(v); }
    private void sub(String t)  { TextView v = new TextView(this); v.setText(t); v.setTextColor(SUB); v.setTextSize(13); v.setPadding(0, dp(6), 0, dp(6)); col.addView(v); }
    private void open(String action) { try { startActivity(new Intent(action)); } catch (Exception e) { toast("Couldn't open settings"); } }
    private void openAppSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { toast("Couldn't open app settings"); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
