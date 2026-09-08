package com.kidstv.v2remote;

// ============================================================================
//  v2 phone remote (pull model). Buttons PUBLISH commands to the local CmdServer;
//  the tablet polls that server, executes them, and reports its state back in the
//  same request. The phone never connects to the tablet -- dodges the Wi-Fi Direct
//  group-owner input firewall entirely. The phone only needs to be joined to the
//  tablet's DIRECT-KidodiTV group so the tablet can reach this server.
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

public class MainActivity extends Activity {
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mNow, mLink;
    private CmdServer mServer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad(16), pad(24), pad(16), pad(16));
        root.setBackgroundColor(0xFF101418);

        TextView title = new TextView(this);
        title.setText("Kidodi v2 remote"); title.setTextColor(Color.WHITE); title.setTextSize(22);
        root.addView(title);

        mLink = new TextView(this);
        mLink.setText("waiting for tablet to poll..."); mLink.setTextColor(0xFFC9A227);
        mLink.setPadding(0, pad(8), 0, pad(4));
        root.addView(mLink);

        mNow = new TextView(this);
        mNow.setText("—"); mNow.setTextColor(0xFF8FD694); mNow.setPadding(0, pad(4), 0, pad(14));
        root.addView(mNow);

        root.addView(rowOf("Prev", "prev", "Play/Pause", "playpause", "Next", "next"));
        root.addView(rowOf("Play", "play", "Pause", "pause", "Light", "flash"));
        root.addView(rowOf("Vol -", "voldown", "Mute", "mute", "Vol +", "volup"));
        root.addView(rowOf("Sleep 10m", "sleep10", "Wake", "wake", "Refresh", ""));

        setContentView(root);

        mServer = new CmdServer(new CmdServer.StateListener() {
            public void onState(String s) { /* stored; UI reads on its tick */ }
        });
        mServer.start();
        tick();
    }

    private LinearLayout rowOf(Object... pairs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < pairs.length; i += 2) {
            final String label = (String) pairs[i];
            final String cmd = (String) pairs[i + 1];
            Button btn = new Button(this);
            btn.setText(label);
            btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (cmd.length() > 0) mServer.publish(cmd);   // publish; the tablet will pull it
            }});
            r.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private void tick() {
        long ago = mServer.lastPollAgoMs();
        if (ago < 0) { mLink.setText("waiting for tablet to poll..."); mLink.setTextColor(0xFFC9A227); }
        else if (ago < 3000) { mLink.setText("tablet connected (polled " + (ago / 100 / 10.0) + "s ago)"); mLink.setTextColor(0xFF8FD694); }
        else { mLink.setText("tablet silent for " + (ago / 1000) + "s"); mLink.setTextColor(0xFFE58A8A); }
        try {
            JSONObject o = new JSONObject(mServer.state());
            String t = o.optString("title", "");
            if (t.length() > 0)
                mNow.setText((o.optBoolean("playing", false) ? "▶ " : "❙❙ ") + t
                        + "\n[" + o.optInt("index", -1) + "/" + o.optInt("count", 0) + "]  vol "
                        + Math.round(o.optDouble("volume", 0) * 100) + "%"
                        + (o.optBoolean("sleeping", false) ? "  (sleeping)" : ""));
        } catch (Exception ignored) {}
        mMain.postDelayed(new Runnable(){ public void run(){ tick(); } }, 500);
    }

    @Override public void onDestroy() { if (mServer != null) mServer.stop(); super.onDestroy(); }

    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
