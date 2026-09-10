package com.kidstv.kidodi;

// ============================================================================
//  Full-screen QR of a payload string (+ caption), to be SCANNED by another
//  device. Used to hand over wifi credentials (standard WIFI: QR) or the
//  player's LAN IP (KIDODI:ip=...), so pairing needs no typing.
//    startActivity(new Intent(ctx, QrShareActivity.class)
//        .putExtra(QrShareActivity.EXTRA_PAYLOAD, "WIFI:S:...;T:WPA;P:...;;")
//        .putExtra(QrShareActivity.EXTRA_CAPTION, "Scan on the TV to join"));
// ============================================================================

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class QrShareActivity extends Activity {
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_CAPTION = "caption";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String payload = getIntent() != null ? getIntent().getStringExtra(EXTRA_PAYLOAD) : null;
        String caption = getIntent() != null ? getIntent().getStringExtra(EXTRA_CAPTION) : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.WHITE);          // white quiet-zone frame so it scans reliably
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        Bitmap qr = QrCode.encode(payload, dp(260));
        if (qr != null) iv.setImageBitmap(qr);
        root.addView(iv, new LinearLayout.LayoutParams(dp(284), dp(284)));

        TextView cap = new TextView(this);
        cap.setText(caption != null ? caption : "Scan this to connect");
        cap.setTextColor(Color.WHITE);
        cap.setTextSize(15);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(0, dp(20), 0, 0);
        root.addView(cap);

        setContentView(root);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
