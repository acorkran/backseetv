package com.kidstv.kidodi.wear;

// ============================================================================
//  Kidodi watch remote. Big glanceable buttons that drive the tablet-player
//  directly over BLE (reuses the proven BleClient). Scrollable so it works on a
//  small round screen. (A no-open Tile is the next polish once this is confirmed.)
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
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
        int pad = pad(20);
        col.setPadding(pad(8), pad, pad(8), pad);
        scroll.addView(col);

        status = new TextView(this);
        status.setText("scanning..."); status.setTextColor(0xFFC9A227);
        status.setGravity(Gravity.CENTER); status.setTextSize(12);
        col.addView(status);

        now = new TextView(this);
        now.setText(""); now.setTextColor(0xFF8FD694); now.setGravity(Gravity.CENTER); now.setTextSize(11);
        now.setPadding(0, pad(4), 0, pad(8));
        col.addView(now);

        col.addView(bigBtn("⏯", "playpause"));
        col.addView(bigBtn("⏭ Next", "next"));
        col.addView(bigBtn("⏮ Prev", "prev"));
        col.addView(bigBtn("💡 Light", "flash"));
        col.addView(bigBtn("😴 Sleep", "sleep10"));
        col.addView(bigBtn("Wake", "wake"));

        setContentView(scroll);

        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) {
                status.setText(s);
                status.setTextColor(s.startsWith("ready") ? 0xFF8FD694 : 0xFFC9A227);
            }
            public void onState(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    String t = o.optString("title", "");
                    if (t.length() > 22) t = t.substring(0, 22) + "…";
                    now.setText((o.optBoolean("playing", false) ? "▶ " : "❙❙ ") + t);
                } catch (Exception ignored) {}
            }
        });
        ble.start();
    }

    private Button bigBtn(String label, final String cmd) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ble.send(cmd); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(52));
        lp.topMargin = pad(6);
        btn.setLayoutParams(lp);
        return btn;
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
