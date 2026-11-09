package app.anancy.net;

// ============================================================================
//  Full-screen QR of a payload string (+ caption), to be SCANNED by another
//  device. Used to hand over wifi credentials (standard WIFI: QR) or the
//  player's LAN IP (ANANCY:ip=...), so pairing needs no typing.
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
    /** TV side: build the pairing payload ourselves, raising the hotspot if it isn't up yet.
        `adb shell am start -n app.anancy/.net.QrShareActivity --ez pair true` */
    public static final String EXTRA_PAIR = "pair";

    private ImageView iv; private TextView cap; private int side;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int tries = 0;

    /** Everything a remote needs in ONE scan: which network to join, and where to talk once on it.
        Without the credentials a QR only helps when you are already on the same network — which is
        the case that needed no help. Null until the hotspot has actually produced its creds. */
    static String pairingPayload(android.content.Context ctx) {
        String ssid = HotspotService.currentSsid(), pass = HotspotService.currentPass();
        String ip = app.anancy.player.PlayerController.wifiIp();
        StringBuilder sb = new StringBuilder("ANANCY:");
        if (ip != null && !ip.isEmpty()) sb.append("ip=").append(QrParse.enc(ip)).append(';');
        if (ssid != null && !ssid.isEmpty()) {
            sb.append("ssid=").append(QrParse.enc(ssid)).append(';');
            sb.append("pass=").append(QrParse.enc(pass == null ? "" : pass)).append(';');
        }
        sb.append("name=").append(QrParse.enc(app.anancy.Identity.name(ctx))).append(';');
        sb.append("id=").append(QrParse.enc(app.anancy.Identity.id(ctx)));
        boolean usable = (ip != null && !ip.isEmpty()) || (ssid != null && !ssid.isEmpty());
        return usable ? sb.toString() : null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String payload = getIntent() != null ? getIntent().getStringExtra(EXTRA_PAYLOAD) : null;
        String caption = getIntent() != null ? getIntent().getStringExtra(EXTRA_CAPTION) : null;
        final boolean pair = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PAIR, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        // FILL THE SCREEN. A fixed 260dp code is fine at arm's length and useless from across a
        // room, which is exactly where a TV is: the phone is metres away, not centimetres. Size it
        // to the short edge, leaving room for the caption and a white quiet zone.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // The caption sits BELOW the code, so only the height needs a caption's worth reserved; the
        // width just loses the root padding. Reserving only 72dp off the short edge pushed the
        // caption clean off the bottom in landscape, so a TV that could not start its hotspot
        // showed a blank white square and not one word explaining why.
        int side = Math.min(dm.widthPixels - dp(48), dm.heightPixels - dp(150));
        if (side < dp(200)) side = dp(200);
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.WHITE);          // white quiet-zone frame so it scans reliably
        int quiet = Math.max(dp(10), side / 24);
        iv.setPadding(quiet, quiet, quiet, quiet);
        Bitmap qr = QrCode.encode(payload, side - quiet * 2);
        if (qr != null) { iv.setImageBitmap(qr); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); }
        root.addView(iv, new LinearLayout.LayoutParams(side, side));
        this.iv = iv; this.side = side - quiet * 2;

        TextView cap = new TextView(this);
        cap.setText(caption != null ? caption : "Scan this to connect");
        cap.setTextColor(Color.WHITE);
        cap.setTextSize(15);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(0, dp(20), 0, 0);
        root.addView(cap);

        this.cap = cap;
        setContentView(root);
        app.anancy.SysBars.pad(root);   // keep the nav bar off the content (edge-to-edge)
        if (pair) { try { HotspotService.start(this, false); } catch (Exception ignored) {} awaitPairing(); }
    }

    /** The hotspot's creds only exist once it is actually up, so poll rather than render an empty
        code and leave someone pointing a camera at nothing. */
    private void awaitPairing() {
        String ssid = HotspotService.currentSsid();
        boolean creds = ssid != null && !ssid.isEmpty(), gaveUp = tries > 20;
        String p = pairingPayload(this);
        if (p != null && (creds || gaveUp)) {   // an address alone only helps a phone already on this network
            Bitmap qr = QrCode.encode(p, side);
            if (qr != null) iv.setImageBitmap(qr);
            cap.setText(creds ? "Scan this with the BackSeat TV remote:  Set-up check → Scan the TV's QR"
                              : "Couldn't start the TV's hotspot. This code only works with the phone on the same Wi-Fi as the TV.");
            return;
        }
        tries++;
        if (gaveUp) { cap.setText("Couldn't start the TV's hotspot. Use Bluetooth, or put both on the same Wi-Fi."); return; }
        cap.setText("Starting the TV's hotspot…");
        main.postDelayed(this::awaitPairing, 700);
    }

    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
