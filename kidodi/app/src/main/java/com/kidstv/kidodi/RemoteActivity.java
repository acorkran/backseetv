package com.kidstv.kidodi;

// ============================================================================
//  Remote role of the one app: big glanceable buttons that send commands to the
//  player over BLE, and a live now-playing line from the player's state notify.
// ============================================================================

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class RemoteActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView link, now;
    private BleClient ble;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad(16), pad(24), pad(16), pad(16));
        root.setBackgroundColor(0xFF101418);

        TextView title = new TextView(this);
        title.setText("Kidodi remote"); title.setTextColor(Color.WHITE); title.setTextSize(22);
        root.addView(title);

        link = new TextView(this);
        link.setText("starting BLE..."); link.setTextColor(0xFFC9A227); link.setPadding(0, pad(8), 0, pad(4));
        root.addView(link);

        now = new TextView(this);
        now.setText("—"); now.setTextColor(0xFF8FD694); now.setPadding(0, pad(4), 0, pad(14));
        root.addView(now);

        root.addView(rowOf("Prev", "prev", "Play/Pause", "playpause", "Next", "next"));
        root.addView(rowOf("Play", "play", "Pause", "pause", "Light", "flash"));
        root.addView(rowOf("Vol -", "voldown", "Mute", "mute", "Vol +", "volup"));
        root.addView(rowOf("Sleep 10m", "sleep10", "Wake", "wake", "Reconnect", ""));

        setContentView(root);

        ble = new BleClient(this, new BleClient.Listener() {
            public void onStatus(String s) {
                link.setText(s);
                link.setTextColor(s.startsWith("ready") || s.startsWith("connected") ? 0xFF8FD694 : 0xFFC9A227);
            }
            public void onState(String json) { showState(json); }
        });
        ble.start();
    }

    private LinearLayout rowOf(Object... p) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < p.length; i += 2) {
            final String label = (String) p[i];
            final String cmd = (String) p[i + 1];
            Button btn = new Button(this);
            btn.setText(label);
            btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (cmd.length() > 0) ble.send(cmd); else { ble.stop(); ble.start(); }
            }});
            r.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private void showState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("title", "");
            if (t.length() > 0) now.setText((o.optBoolean("playing", false) ? "▶ " : "❙❙ ") + t
                    + "\n[" + o.optInt("index", -1) + "/" + o.optInt("count", 0) + "]  vol "
                    + Math.round(o.optDouble("volume", 0) * 100) + "%"
                    + (o.optBoolean("sleeping", false) ? "  (sleeping)" : ""));
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() { if (ble != null) ble.stop(); super.onDestroy(); }
    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
