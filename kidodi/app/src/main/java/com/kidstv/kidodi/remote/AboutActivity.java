package com.kidstv.kidodi.remote;

// ============================================================================
//  About / feature levels. Explains the three install tiers and what each
//  unlocks. Opened from the device-owner "requires setup" notices (tablet) and
//  the phone remote's settings screen. Read-only, scrollable, no special perms.
// ============================================================================

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AboutActivity extends Activity {
    private static final int BG = 0xFF101418, CARD = 0xFF1B2128, TXT = 0xFFF2F4F7, SUB = 0xFF9AA4B2, OK = 0xFF8FD694, ACCENT = 0xFF6FA8FF;
    private LinearLayout col;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(22), dp(24), dp(28));
        sv.addView(col); setContentView(sv);

        head("Kidodi — feature levels");
        sub("Kidodi works at three levels. Each one builds on the last. Here's what you get, and how to unlock it.");

        tier("1 · Kidodi Player", OK, "The base install. No special setup — just install and open, pick \"This is the TV\".", new String[]{
                "Plays your local shows straight off the device (no internet, no server)",
                "Shuffle & loop, save the queue as a playlist, load playlists, per-show playlists",
                "Browse the whole library and add anything to the play queue",
                "Camera check-in stills (front camera, nothing recorded)",
                "Toddler play mode — a tap-to-play letters screen over the show",
                "Light, sleep timer, volume, power",
                "Connection levels (Bluetooth / Wi-Fi) so phone & watch remotes and the car dash can control it",
        });

        tier("2 · Kidodi Kiosk   (requires: device owner)", ACCENT, "Everything in Player, PLUS the locked-down appliance. One-time setup with a computer:", new String[]{
                "The show IS the home screen — boots straight into it",
                "Inescapable lock: Home / Back / Recents are blocked, kids can't get out",
                "A hidden admin gesture + PIN to get in for a grown-up",
                "Wi-Fi setup and permissions without prompts (granted silently)",
                "Turn the screen on/off from the remotes",
        });
        note("To unlock: use a tablet with NO Google or Samsung account added, turn on USB debugging, plug it into a computer, and run:");
        codeBlock("adb shell dpm set-device-owner com.kidstv.kidodi/.AdminReceiver");
        note("Then open Kidodi, choose \"This is the TV\", and turn on Kiosk lock in Settings. It's reversible any time.");

        tier("3 · Remote & Android Auto", 0xFFD8A657, "Install Kidodi on a phone or watch and pick \"Remote\".", new String[]{
                "A full phone remote: now-playing, playlists, the play queue, library browsing, camera check-in, all the settings",
                "A Galaxy Watch remote",
                "The in-car Android Auto dash — camera on the album art, live progress, and the key controls",
                "Controls any Kidodi TV over Bluetooth (works anywhere) or Wi-Fi (adds live camera + fast browsing)",
        });

        Button back = new Button(this); back.setAllCaps(false); back.setText("Back"); back.setTextSize(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.setMargins(0, dp(24), 0, 0); back.setLayoutParams(lp);
        back.setOnClickListener(v -> finish());
        col.addView(back);
    }

    private void head(String t) {
        TextView v = new TextView(this); v.setText(t); v.setTextColor(TXT); v.setTextSize(24);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); v.setPadding(0, 0, 0, dp(8)); col.addView(v);
    }
    private void sub(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(SUB); v.setTextSize(14); v.setPadding(0, 0, 0, dp(14)); col.addView(v); }
    private void tier(String title, int color, String blurb, String[] bullets) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(6)); card.setLayoutParams(lp);
        TextView h = new TextView(this); h.setText(title); h.setTextColor(color); h.setTextSize(18);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); card.addView(h);
        TextView bl = new TextView(this); bl.setText(blurb); bl.setTextColor(SUB); bl.setTextSize(13); bl.setPadding(0, dp(4), 0, dp(8)); card.addView(bl);
        for (String b : bullets) {
            TextView t = new TextView(this); t.setText("•  " + b); t.setTextColor(TXT); t.setTextSize(14); t.setPadding(0, dp(3), 0, dp(3)); card.addView(t);
        }
        col.addView(card);
    }
    private void note(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(SUB); v.setTextSize(13); v.setPadding(dp(4), dp(6), dp(4), dp(4)); col.addView(v); }
    private void codeBlock(String t) {
        TextView v = new TextView(this); v.setText(t); v.setTextColor(OK); v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.MONOSPACE); v.setBackgroundColor(0xFF0B0E12);
        v.setPadding(dp(12), dp(10), dp(12), dp(10)); v.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6)); v.setLayoutParams(lp); col.addView(v);
    }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }
}
