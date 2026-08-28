package com.kidstv.aaremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Minimal launcher: a status / "connect" screen. The real control surface is the
 * MediaBrowserService, which the Android Auto head unit binds to directly - this screen just
 * confirms the app can reach Kodi on the tablet and offers a rescan + a jump to hotspot settings
 * (the tablet joins THIS phone's hotspot, exactly as the phone remote assumes).
 */
public class MainActivity extends Activity {

    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, ACCENT = 0xFF2F6FEB;
    static final int TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6, GOOD = 0xFF33C05B, WARN = 0xFFE0653D;

    private final KodiClient kodi = new KodiClient();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, hint;
    private volatile boolean polling = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kodi.setLast(getSharedPreferences("aaremote", MODE_PRIVATE).getString("lastip", ""));
        setContentView(buildUi());
    }

    @Override protected void onResume() { super.onResume(); startPoll(); }
    @Override protected void onPause()  { super.onPause();  polling = false; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Kidodi Remote");
        title.setTextColor(TXT);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText("Control the car tablet's Kodi from Android Auto. Open \"Kidodi Remote\" from the "
                + "head unit's media apps to browse playlists and control playback.");
        blurb.setTextColor(SUB);
        blurb.setTextSize(14);
        blurb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(12), 0, dp(28));
        root.addView(blurb, bp);

        status = new TextView(this);
        status.setText("● scanning…");
        status.setTextColor(SUB);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        hint = new TextView(this);
        hint.setText("Make sure your phone's hotspot is on and the tablet has joined it.");
        hint.setTextColor(SUB);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(0, dp(10), 0, dp(28));
        root.addView(hint, hp);

        root.addView(button("Rescan for tablet", v -> rescan()));
        root.addView(button("Hotspot settings", v -> openHotspotSettings()));
        return root;
    }

    private Button button(String label, android.view.View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setBackgroundColor(CARD);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(52));
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void startPoll() {
        if (polling) return;
        polling = true;
        new Thread(() -> {
            while (polling) {
                if (!kodi.connected()) kodi.findHost();
                final boolean conn = kodi.connected();
                final String host = kodi.activeHost(), label = kodi.activeLabel();
                ui.post(() -> {
                    if (conn) {
                        status.setText("● Connected  (" + label + " " + host + ")");
                        status.setTextColor(GOOD);
                        hint.setText("The tablet is reachable. Use Android Auto to browse + play.");
                    } else {
                        status.setText("● Not connected");
                        status.setTextColor(WARN);
                        hint.setText("Make sure your phone's hotspot is on and the tablet has joined it.");
                    }
                });
                try { Thread.sleep(1500); } catch (Exception e) { break; }
            }
        }).start();
    }

    private void rescan() {
        Toast.makeText(this, "Searching for the tablet…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean ok = kodi.rescan();
            ui.post(() -> Toast.makeText(this,
                    ok ? ("Found Kodi @ " + kodi.activeHost()) : "No tablet found — is the hotspot on?",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    /** No sideloaded app can flip the real hotspot itself (signature permission) — deep-link the toggle. */
    private void openHotspotSettings() {
        try {
            Intent i = new Intent();
            i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Exception e2) {
                Toast.makeText(this, "Open Settings → Connections → Mobile Hotspot", Toast.LENGTH_LONG).show();
            }
        }
    }
}
