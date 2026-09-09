package com.kidstv.kidodi.wear;

// ============================================================================
//  Kidodi watch remote, Samsung-Music style, fitted to the ROUND face: content
//  is centered and compact so nothing clips on the curved edges. Show/episode up
//  top, Prev · big Play/Pause · Next, bottom row Light · Power · Setup. Rotating
//  bezel = volume, with a transient on-screen volume popup. Setup = sleep-timer
//  picker. Drives the tablet-player directly over BLE.
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class WearRemoteActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BleClient ble;

    private FrameLayout content;    // holds player or settings
    private View playerView, settingsView;
    private TextView status, title, subtitle;
    private Button playPause;

    // volume popup
    private View volOverlay;
    private View volFill;
    private TextView volText;
    private int volPct = 0;
    private int volFillMaxPx;
    private Runnable hideVol;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPerms();

        content = new FrameLayout(this);
        playerView = buildPlayer();
        settingsView = buildSettings();
        content.addView(playerView);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildVolOverlay());
        setContentView(root);

        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) { status.setText(s.startsWith("ready") ? "" : s); }
            public void onState(String json) { render(json); }
        });
        ble.start();
    }

    private void show(View v) { content.removeAllViews(); content.addView(v); }

    // --- player screen (centered + compact to fit the round face) -----------
    private View buildPlayer() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);   // cluster in the middle where the circle is widest
        col.setFocusableInTouchMode(true);

        status = new TextView(this);
        status.setText("scanning…"); status.setTextColor(0xFFFFC107);
        status.setGravity(Gravity.CENTER); status.setTextSize(11);
        col.addView(status);

        title = new TextView(this);
        title.setTextColor(Color.WHITE); title.setTextSize(16); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(0xFF9AA0A6); subtitle.setTextSize(11); subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, 0, 0, pad(8));
        col.addView(subtitle);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.addView(circle("◀◀", "prev", pad(44), 0xFF2C2C2E, 14));
        playPause = circle("▶", "playpause", pad(62), 0xFF3A3A3C, 24);
        transport.addView(playPause);
        transport.addView(circle("▶▶", "next", pad(44), 0xFF2C2C2E, 14));
        col.addView(transport);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brow.topMargin = pad(8);
        bottom.setLayoutParams(brow);
        bottom.addView(chip("Light", "flash", 0xFF8D6E00));
        bottom.addView(chip("Power", "power", 0xFF5A2A2A));
        bottom.addView(chip("Setup", "settings", 0xFF33363B));
        col.addView(bottom);
        return col;
    }

    // Note: prev uses "◀" and next uses "▶" (next is same glyph, distinguished by side/position).

    // --- volume popup -------------------------------------------------------
    private View buildVolOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202225); bg.setCornerRadius(pad(16));
        box.setBackground(bg);
        box.setPadding(pad(18), pad(12), pad(18), pad(12));

        volText = new TextView(this);
        volText.setText("Volume"); volText.setTextColor(Color.WHITE); volText.setTextSize(14);
        volText.setTypeface(Typeface.DEFAULT_BOLD); volText.setGravity(Gravity.CENTER);
        volText.setPadding(0, 0, 0, pad(8));
        box.addView(volText);

        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable(); tb.setColor(0xFF44474C); tb.setCornerRadius(pad(4));
        track.setBackground(tb);
        volFillMaxPx = pad(120);
        volFill = new View(this);
        GradientDrawable fb = new GradientDrawable(); fb.setColor(0xFF8FD694); fb.setCornerRadius(pad(4));
        volFill.setBackground(fb);
        track.addView(volFill, new FrameLayout.LayoutParams(1, pad(8)));   // grows on showVol
        box.addView(track, new LinearLayout.LayoutParams(volFillMaxPx, pad(8)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.setLayoutParams(lp);
        volOverlay = box;
        volOverlay.setVisibility(View.GONE);
        return box;
    }

    private void showVol() {
        volText.setText("Volume  " + volPct + "%");
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) volFill.getLayoutParams();
        fp.width = Math.round(volFillMaxPx * (volPct / 100f));
        volFill.setLayoutParams(fp);
        volOverlay.setVisibility(View.VISIBLE);
        if (hideVol != null) main.removeCallbacks(hideVol);
        hideVol = new Runnable() { public void run() { volOverlay.setVisibility(View.GONE); } };
        main.postDelayed(hideVol, 1400);
    }

    // --- settings: sleep-timer picker --------------------------------------
    private View buildSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad(20), pad(44), pad(20), pad(44));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("Sleep timer"); h.setTextColor(Color.WHITE); h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD); h.setGravity(Gravity.CENTER); h.setPadding(0, 0, 0, pad(8));
        col.addView(h);

        int[] mins = {0, 5, 10, 15, 20, 30, 45};
        for (final int m : mins) {
            Button row = new Button(this);
            row.setAllCaps(false); row.setText(m == 0 ? "Off" : m + " min");
            row.setTextColor(Color.WHITE); row.setTextSize(15);
            GradientDrawable g = new GradientDrawable(); g.setColor(0xFF202225); g.setCornerRadius(pad(14));
            row.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(48));
            lp.setMargins(0, pad(4), 0, pad(4)); row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                ble.send(m == 0 ? "wake" : "sleep:" + m); show(playerView);
            }});
            col.addView(row);
        }
        return sv;
    }

    private void onCmd(String cmd) {
        if ("settings".equals(cmd)) { show(settingsView); return; }
        if (ble != null) ble.send(cmd);
    }

    private void render(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (playPause != null) playPause.setText(o.optBoolean("playing", false) ? "▮▮" : "▶");
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            String show = i > 0 ? t.substring(0, i) : t;
            String ep = i > 0 ? t.substring(i + 1) : "";
            title.setText(show.replace('.', ' ').trim());
            subtitle.setText(ep.replace('.', ' ').replace('⧸', '/').trim());
            if (volOverlay.getVisibility() != View.VISIBLE)
                volPct = (int) Math.round(o.optDouble("volume", 0) * 100);
        } catch (Exception ignored) {}
    }

    // rotating bezel -> volume (with popup)
    @Override public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && (ev.getSource() & InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (ble != null && d != 0) {
                boolean up = d > 0;
                ble.send(up ? "volup" : "voldown");
                volPct = Math.max(0, Math.min(100, volPct + (up ? 10 : -10)));
                showVol();
            }
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
        lp.setMargins(pad(5), 0, pad(5), 0); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onCmd(cmd); } });
        return b;
    }

    private Button chip(String label, final String cmd, int bg) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(pad(18)); g.setColor(bg);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(48), pad(38));
        lp.setMargins(pad(3), 0, pad(3), 0); b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onCmd(cmd); } });
        return b;
    }

    private void requestPerms() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, 1);
            else
                requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 1);
        } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() { show(playerView); }
    @Override protected void onDestroy() { if (ble != null) ble.stop(); super.onDestroy(); }
    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
