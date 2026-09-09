package com.kidstv.kidodi.wear;

// ============================================================================
//  Kidodi watch remote, Samsung-Music style: title/show up top, a Prev · big
//  Play/Pause · Next transport row, and a bottom row of Light · Power · Setup.
//  The rotating bezel controls volume. Setup opens a sleep-timer duration picker.
//  Drives the tablet-player directly over BLE (reuses the proven BleClient).
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class WearRemoteActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BleClient ble;
    private View playerView, settingsView;
    private TextView status, title, subtitle;
    private Button playPause;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPerms();
        playerView = buildPlayer();
        settingsView = buildSettings();
        setContentView(playerView);

        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) {
                status.setText(s.startsWith("ready") ? "" : s);   // hide chrome once ready
                status.setTextColor(s.startsWith("ready") ? 0xFF8FD694 : 0xFFFFC107);
            }
            public void onState(String json) { render(json); }
        });
        ble.start();
    }

    // --- player screen ------------------------------------------------------
    private View buildPlayer() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(Color.BLACK);
        col.setPadding(pad(18), pad(30), pad(18), pad(26));
        col.setFocusableInTouchMode(true);   // receive rotary (bezel) events

        status = new TextView(this);
        status.setText("scanning…"); status.setTextColor(0xFFFFC107);
        status.setGravity(Gravity.CENTER); status.setTextSize(12);
        col.addView(status);

        title = new TextView(this);
        title.setText(""); title.setTextColor(Color.WHITE); title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);

        subtitle = new TextView(this);
        subtitle.setText(""); subtitle.setTextColor(0xFF9AA0A6); subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER); subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(subtitle);

        col.addView(spacer());

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.addView(circle("◀◀", "prev", pad(56), 0xFF2C2C2E, 18));
        playPause = circle("▶", "playpause", pad(84), 0xFF3A3A3C, 26);
        transport.addView(playPause);
        transport.addView(circle("▶▶", "next", pad(56), 0xFF2C2C2E, 18));
        col.addView(transport);

        col.addView(spacer());

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(chip("Light", "flash", 0xFF8D6E00));
        bottom.addView(chip("Power", "power", 0xFF5A2A2A));
        bottom.addView(chip("Setup", "settings", 0xFF2C2C2E));
        col.addView(bottom);

        return col;
    }

    // --- settings screen: sleep-timer duration picker (Library-list style) --
    private View buildSettings() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(16), pad(40), pad(16), pad(40));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("Sleep timer"); h.setTextColor(Color.WHITE); h.setTextSize(18);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER);
        h.setPadding(0, 0, 0, pad(10));
        col.addView(h);

        int[] mins = {0, 5, 10, 15, 20, 30, 45};
        for (final int m : mins) {
            Button row = new Button(this);
            row.setAllCaps(false); row.setText(m == 0 ? "Off" : m + " min");
            row.setTextColor(Color.WHITE); row.setTextSize(16); row.setGravity(Gravity.CENTER);
            row.setBackgroundColor(0xFF202225);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(52));
            lp.setMargins(0, pad(4), 0, pad(4)); row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                ble.send(m == 0 ? "wake" : "sleep:" + m);
                setContentView(playerView);
            }});
            col.addView(row);
        }
        Button back = new Button(this);
        back.setAllCaps(false); back.setText("‹ Back"); back.setTextColor(0xFF8FD694); back.setTextSize(15);
        back.setBackgroundColor(0x00000000);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setContentView(playerView); } });
        col.addView(back);
        return sv;
    }

    private void onCmd(String cmd) {
        if ("settings".equals(cmd)) { setContentView(settingsView); return; }
        if (ble != null) ble.send(cmd);
    }

    private void render(String json) {
        try {
            JSONObject o = new JSONObject(json);
            boolean playing = o.optBoolean("playing", false);
            if (playPause != null) playPause.setText(playing ? "▮▮" : "▶");
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            String show = i > 0 ? t.substring(0, i) : t;
            String ep = i > 0 ? t.substring(i + 1) : "";
            title.setText(show.replace('.', ' ').trim());
            subtitle.setText(ep.replace('.', ' ').replace('⧸', '/').trim());
        } catch (Exception ignored) {}
    }

    // rotating bezel -> volume
    @Override public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && (ev.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (ble != null && d != 0) ble.send(d > 0 ? "volup" : "voldown");
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    private Button circle(String glyph, final String cmd, int sizePx, int bg, int textSp) {
        Button b = new Button(this);
        b.setText(glyph); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(textSp); b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(bg);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.setMargins(pad(6), 0, pad(6), 0); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onCmd(cmd); } });
        return b;
    }

    private Button chip(String label, final String cmd, int bg) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(pad(20)); g.setColor(bg); b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(58), pad(44));
        lp.setMargins(pad(4), 0, pad(4), 0); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onCmd(cmd); } });
        return b;
    }

    private View spacer() {
        View s = new View(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return s;
    }

    private void requestPerms() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, 1);
            else
                requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 1);
        } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        // from settings, Back returns to the player; from player, exit normally
        setContentView(playerView);
    }

    @Override protected void onDestroy() { if (ble != null) ble.stop(); super.onDestroy(); }
    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
