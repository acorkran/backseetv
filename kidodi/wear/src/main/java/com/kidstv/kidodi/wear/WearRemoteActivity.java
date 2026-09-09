package com.kidstv.kidodi.wear;

// ============================================================================
//  Kidodi watch remote. High-contrast, watch-shaped: a big Play/Pause, paired
//  Prev/Next and Sleep/Wake rows, and Light. Drives the tablet-player over BLE
//  (reuses the proven BleClient). Round-screen padding so nothing clips.
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class WearRemoteActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, now;
    private BleClient ble;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPerms();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // round-screen safe padding: keep content off the curved top/bottom edges
        col.setPadding(pad(14), pad(46), pad(14), pad(46));
        scroll.addView(col);

        status = new TextView(this);
        status.setText("scanning…"); status.setTextColor(0xFFFFC107);
        status.setGravity(Gravity.CENTER); status.setTextSize(13);
        col.addView(status);

        now = new TextView(this);
        now.setText(""); now.setTextColor(0xFF8FD694); now.setGravity(Gravity.CENTER); now.setTextSize(12);
        now.setPadding(0, pad(3), 0, pad(10));
        col.addView(now);

        col.addView(big("▶ / ❚❚", "playpause", 0xFF2E7D32, pad(74), 22));  // green, prominent
        col.addView(row(
                big("⏮", "prev", 0xFF37474F, pad(60), 22),
                big("⏭", "next", 0xFF37474F, pad(60), 22)));
        col.addView(row(
                big("😴 Sleep", "sleep10", 0xFF303F9F, pad(58), 15),
                big("☀ Wake", "wake", 0xFF455A64, pad(58), 15)));
        col.addView(big("💡 Light", "flash", 0xFF8D6E00, pad(58), 16));

        setContentView(scroll);

        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) {
                status.setText(s);
                status.setTextColor(s.startsWith("ready") ? 0xFF8FD694 : 0xFFFFC107);
            }
            public void onState(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    String t = o.optString("title", "");
                    if (t.length() > 24) t = t.substring(0, 24) + "…";
                    now.setText((o.optBoolean("playing", false) ? "▶ " : "❚❚ ") + t);
                } catch (Exception ignored) {}
            }
        });
        ble.start();
    }

    private Button big(String label, final String cmd, int bg, int heightPx, int textSp) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(textSp);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackgroundColor(bg);
        btn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx, 1f);
        lp.setMargins(pad(4), pad(4), pad(4), pad(4));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (ble != null) ble.send(cmd); } });
        return btn;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(a); r.addView(b);
        return r;
    }

    private void requestPerms() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, 1);
            else
                requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 1);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() { if (ble != null) ble.stop(); super.onDestroy(); }
    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
