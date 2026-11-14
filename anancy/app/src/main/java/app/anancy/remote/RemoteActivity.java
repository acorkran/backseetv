package app.anancy.remote;

import app.anancy.Cfg;
import app.anancy.net.HotspotService;
import app.anancy.net.QrScanActivity;
import app.anancy.net.QrShareActivity;
import app.anancy.net.WearPusher;
import app.anancy.net.WifiJoiner;

// ============================================================================
//  Remote role, styled to match v1's polish: dark card theme + Canvas-drawn
//  icons (font glyphs tofu on-device). Now-playing card with progress, big
//  accent play/pause, and coloured Light / Sleep / Power action cards. Drives
//  the tablet through RemoteService (BLE primary, Wi-Fi fallback).
// ============================================================================

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.ImageView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

public class RemoteActivity extends Activity {
    // v1 palette
    static final int BG = 0xFF10151C, CARD = 0xFF1B2735, BTN = 0xFF35516E, TXT = 0xFFFFFFFF, SUB = 0xFF9DB0C6;
    static final int ACCENT = 0xFF2F6FEB, GOOD = 0xFF33C05B, WARN = 0xFFE0A63D, PURPLE = 0xFF9B72E0, ORANGE = 0xFFE0673F;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteUi ui = new RemoteUi(this);   // stateless view builders (extracted; see RemoteUi)
    private View titleDot;   // the app icon (menu button + popup anchor)
    private View dot;            // the connection light (top-right corner); tap = ephemeral status text
    private TextView linkLabel, title, subtitle, timeText, sleepText, plNow;
    private LinearLayout deviceCards;   // per-screen cards with checkboxes on the Remote tab
    private Runnable cardPoller;        // polls each screen's /state to fill the cards
    private final java.util.Map<String, String> devState = new java.util.concurrent.ConcurrentHashMap<>();   // id -> latest /state json

    private ProgressBar progress;
    private IconView playPause;
    private FrameLayout frame;
    private View picker, queueView, libView, epView, connView, settingsView;
    private org.json.JSONObject cfgVals; private boolean cfgOwner, cfgPinSet;   // mirrored TV settings
    private TextView connStatus;
    // --- tabs: Remote (0) / Playlists (1) / Queue (2) ---
    private LinearLayout tabBar; private FrameLayout content;
    private View remoteTab, playlistsTab, queueTab;
    private final TextView[] tabBtns = new TextView[3]; private int curTab = 0;
    private LinearLayout plCol, qCol;
    private LinearLayout connModes, connActions;
    private boolean lastShuffle, lastLoop = true;
    private String lastConn = "ble", hsSsid, hsPass;   // the TV's connection level + its published hotspot creds
    private View still; private volatile boolean stillOn;
    // digital PTZ on the camera view: client-side viewport (zoom 1-4x, pan center as image fractions)
    private float ptzZoom = 1f, ptzCx = 0.5f, ptzCy = 0.5f;
    private Bitmap ptzBmp;   // last frame shown, for the viewport math
    private ScaleGestureDetector ptzScale;
    private volatile String tabletIp;   // the TV's LAN IP, learned from state (BLE) — for /snapshot.jpg

    /** The TV's address: from the live BLE state when we have it, else whatever a QR pairing left
        behind. Without the fallback, scanning a pairing code achieved nothing the moment Bluetooth
        wasn't also up — which is precisely the situation the QR exists for. */
    private String tvIp() {
        String ip = tabletIp;
        if (ip != null && !ip.isEmpty()) return ip;
        try { return app.anancy.Cfg.getStr(this, "anancy_paired_ip", ""); } catch (Exception e) { return ""; }
    }
    private boolean local;              // running ON the TV as its admin remote (loopback, no BLE)
    // Last pushed clock values; the TV pushes on change + a 10s heartbeat (battery), so we tick locally.
    private int lastPos, lastDur; private long lastStateAt, lastSleepMs; private double lastVol;
    private boolean lastPlaying, lastSleeping, lastNapped, lastPlaymode; private int tickN;
    private int lastBattery = -1; private boolean lastCharging;   // TV battery, shown on the now-playing line
    private final Runnable uiTick = new Runnable() { public void run() {
        renderClock();
        refreshLinkIcons();
        if (++tickN % 20 == 0) WearPusher.refreshStatus(RemoteActivity.this);
        main.postDelayed(this, 1000);
    }};
    private String currentPl = "Shuffle All";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(8));

        // --- header: "Anancy ⌄" opens the menu; the connection light sits in the corner (tap = status) ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setClickable(true); titleRow.setPadding(0, dp(2), dp(10), dp(2));
        titleRow.setOnClickListener(v -> showTargetMenu());
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        logo.setContentDescription("Menu");
        titleDot = logo;
        titleRow.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView chev = new TextView(this); chev.setText(" ⌄"); chev.setTextColor(TXT); chev.setTextSize(20);
        chev.setPadding(dp(4), 0, 0, dp(6));
        titleRow.addView(chev);
        header.addView(titleRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // "All screens" and "Leader-only audio" live up here now, not in the menu: the screens' own
        // checkboxes are on their rows, so the menu's list of them was the same thing twice.
        headerChecks = new LinearLayout(this); headerChecks.setOrientation(LinearLayout.VERTICAL);
        header.addView(headerChecks, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Connection at a glance: Bluetooth, hotspot and shared Wi-Fi, each lit while it carries a link
        // to a screen (it used to be one dot that only said "something is connected"). Tap = details.
        LinearLayout links = new LinearLayout(this); links.setOrientation(LinearLayout.HORIZONTAL); links.setGravity(Gravity.CENTER_VERTICAL);
        links.setClickable(true); links.setPadding(dp(4), dp(6), 0, dp(6));
        links.setOnClickListener(v -> showConnBlurb());
        for (int k = 0; k < 3; k++) {
            linkIcons[k] = new LinkIcon(this, k);
            int sz = k == 1 ? 22 : 18;   // the hotspot glyph has the most detail
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(sz), dp(sz)); ilp.leftMargin = dp(k == 0 ? 0 : 3);
            links.addView(linkIcons[k], ilp);
        }
        dot = links;
        header.addView(links, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        // --- content: three swappable tab views fill the middle (each scrolls) ---
        root.addView(spacer(dp(8)));
        content = new FrameLayout(this);
        remoteTab = buildRemoteTab();
        playlistsTab = buildPlaylistsTab();
        queueTab = buildQueueTab();
        content.addView(remoteTab); content.addView(playlistsTab); content.addView(queueTab);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- tab bar pinned at the BOTTOM: Remote | Playlists | Queue (in the header row in landscape) ---
        tabSpacer = spacer(dp(8)); root.addView(tabSpacer);
        tabBar = new LinearLayout(this); tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBtns[0] = ui.pillChip("Remote", v -> showTab(0));
        tabBtns[1] = ui.pillChip("Playlists", v -> showTab(1));
        tabBtns[2] = ui.pillChip("Queue", v -> showTab(2));
        for (int k = 0; k < 3; k++) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(k == 0 ? 0 : dp(4), 0, k == 2 ? 0 : dp(4), 0);
            tabBar.addView(tabBtns[k], tp);
        }
        root.addView(tabBar);
        rootCol = root; headerRow = header; headerDot = dot;
        layoutChrome(getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);

        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        app.anancy.SysBars.pad(frame);   // keep the nav bar off the content (edge-to-edge)
        showTab(0);

        local = getIntent().getBooleanExtra("local", false);   // the TV's own admin Remote
        if (local) refreshDeviceCards();   // the TV's own remote: its card holds the show itself
        if (local) { tabletIp = "127.0.0.1"; RemoteService.ensureLocal(this); }
        else RemoteService.ensure(this);
        attach();
    }

    /** Switch tabs: show one view, highlight its pill, and (re)load that tab's data on entry. */
    private void showTab(int i) {
        curTab = i;
        remoteTab.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        playlistsTab.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        queueTab.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < 3; k++) {
            ((GradientDrawable) tabBtns[k].getBackground()).setColor(k == i ? ACCENT : BTN);
            tabBtns[k].setTextColor(TXT);
        }
        RemoteService svc = RemoteService.get();
        if (i == 1) { if (svc != null) svc.requestPlaylists(); populatePicker(plCol, 0); }
        else if (i == 2) { loadQueue(qCol); }
    }

    // --- TAB 1: Remote — now-playing card + transport + volume + Light/Sleep/Power + Check-in ---
    private View buildRemoteTab() {
        LinearLayout tabRoot = new LinearLayout(this); tabRoot.setOrientation(LinearLayout.VERTICAL); tabRoot.setBackgroundColor(BG);
        // Two columns: the screens + now playing, then the controls. Portrait stacks them; landscape puts
        // them side by side (layoutRemoteTab) so nothing falls off the bottom of a short, wide screen.
        remoteRoot = tabRoot;
        LinearLayout leftCol = new LinearLayout(this); leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this); rightCol.setOrientation(LinearLayout.VERTICAL); rightCol.setGravity(Gravity.CENTER_VERTICAL);
        remoteLeft = leftCol; remoteRight = rightCol;
        // off-screen holders so showState (representative path) keeps working without NPEs
        title = new TextView(this); subtitle = new TextView(this);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        timeText = new TextView(this);

        // Framed, scrollable panel of neat per-screen cards: fills the space, scrolls when there are many.
        ScrollView cardsScroll = new ScrollView(this);
        GradientDrawable frameBg = new GradientDrawable(); frameBg.setColor(0x10FFFFFF); frameBg.setCornerRadius(dp(16)); frameBg.setStroke(dp(1), 0x22FFFFFF);
        cardsScroll.setBackground(frameBg); cardsScroll.setClipToOutline(true); cardsScroll.setPadding(dp(3), dp(3), dp(3), dp(3));
        // Always showing whenever there's more to scroll to (Android hides it when everything fits), or
        // nobody would know there are more screens below the first card.
        cardsScroll.setVerticalScrollBarEnabled(true);
        cardsScroll.setScrollbarFadingEnabled(false);
        cardsScroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        cardsScroll.setScrollBarSize(dp(4));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable thumb = new GradientDrawable(); thumb.setColor(ACCENT); thumb.setCornerRadius(dp(2));
            cardsScroll.setVerticalScrollbarThumbDrawable(new android.graphics.drawable.InsetDrawable(thumb, 0, dp(12), 0, dp(12)));
        }
        deviceCards = new LinearLayout(this); deviceCards.setOrientation(LinearLayout.VERTICAL);
        cardsScroll.addView(deviceCards);
        refreshDeviceCards();
        leftCol.addView(cardsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        cardsPanel = cardsScroll;
        playbackRow = new LinearLayout(this); playbackRow.setOrientation(LinearLayout.HORIZONTAL); playbackRow.setGravity(Gravity.CENTER_VERTICAL);
        leftCol.addView(playbackRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Now playing. These views existed but were never added to the layout, so over BLE (where the
        // per-device cards have no HTTP data) the remote showed NO title, position or progress at all.
        title.setTextColor(TXT); title.setTextSize(16); title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setGravity(Gravity.CENTER);
        subtitle.setTextColor(SUB); subtitle.setTextSize(12); subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); subtitle.setGravity(Gravity.CENTER);
        timeText.setTextColor(SUB); timeText.setTextSize(11); timeText.setGravity(Gravity.CENTER);
        LinearLayout np = new LinearLayout(this); np.setOrientation(LinearLayout.VERTICAL);
        np.setPadding(dp(6), dp(4), dp(6), 0);
        np.addView(title); np.addView(subtitle);
        np.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        np.addView(timeText);
        leftCol.addView(np, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Slim transport above the grid: back / play-pause / forward.
        LinearLayout tr = new LinearLayout(this); tr.setOrientation(LinearLayout.HORIZONTAL); tr.setGravity(Gravity.CENTER);
        tr.addView(iconBtn(IconView.PREV, BTN, TXT, "prev"), lp(dp(54), dp(48), dp(12)));
        playPause = new IconView(this, IconView.PLAYPAUSE, ACCENT, TXT);
        playPause.setOnClickListener(v -> send("playpause")); ui.pressFx(playPause);
        tr.addView(playPause, lp(dp(62), dp(56), dp(12)));
        tr.addView(iconBtn(IconView.NEXT, BTN, TXT, "next"), lp(dp(54), dp(48), dp(12)));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); trp.topMargin = dp(4);
        rightCol.addView(tr, trp);

        sleepText = new TextView(this); sleepText.setText(""); sleepText.setTextColor(WARN); sleepText.setTextSize(12);
        sleepText.setGravity(Gravity.CENTER); sleepText.setPadding(0, dp(3), 0, 0); sleepText.setVisibility(View.GONE); rightCol.addView(sleepText);

        // 4x2 function grid: Light Sleep Mute Vol+ / Toddler Power Check-in Vol-  (volume cells 10% wider)
        LinearLayout g1 = new LinearLayout(this); g1.setOrientation(LinearLayout.HORIZONTAL);
        g1.addView(ui.gridCell(IconView.SUN, WARN, "Light", () -> send("flash"), false), gcLp(1f));
        g1.addView(ui.gridCell(IconView.SLEEP, PURPLE, "Sleep", () -> send("sleep:15"), false), gcLp(1f));
        g1.addView(ui.gridCell(IconView.MUTE, 0xFF6EA0FF, "Mute", () -> send("mute"), false), gcLp(1f));
        g1.addView(ui.gridCell(IconView.VOLUP, 0xFF8390A6, "Vol +", () -> send("volup"), true), gcLp(1.1f));
        LinearLayout.LayoutParams g1p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); g1p.topMargin = dp(6); rightCol.addView(g1, g1p);
        LinearLayout g2 = new LinearLayout(this); g2.setOrientation(LinearLayout.HORIZONTAL);
        // Toddler was send("playmode:on") with no way back: the front seat could start the playground
        // and then had no way to stop it (the escape gesture is on the tablet, in the back seat).
        toddlerCell = ui.gridCell(IconView.BEAR, PURPLE, "Toddler", () -> {
            send("playmode:toggle"); toast(lastPlaymode ? "Toddler lock off" : "Toddler lock on");
        }, false);
        g2.addView(toddlerCell, gcLp(1f));
        g2.addView(ui.gridCell(IconView.POWER, ORANGE, "Power", () -> send("power"), false), gcLp(1f));
        g2.addView(ui.gridCell(IconView.WATCH, GOOD, "Check-in", this::openStill, false), gcLp(1f));
        g2.addView(ui.gridCell(IconView.VOLDOWN, 0xFF8390A6, "Vol −", () -> send("voldown"), true), gcLp(1.1f));
        LinearLayout.LayoutParams g2p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); g2p.topMargin = dp(6); rightCol.addView(g2, g2p);

        layoutRemoteTab(getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        return tabRoot;
    }
    private LinearLayout remoteRoot, remoteLeft, remoteRight, rootCol, headerRow;
    private View cardsPanel;
    private View tabSpacer, headerDot;
    /** A short wide screen can't spare a whole row for the tabs: they move up beside the title. */
    private void layoutChrome(boolean land) {
        if (rootCol == null || tabBar == null) return;
        ((ViewGroup) tabBar.getParent()).removeView(tabBar);
        tabSpacer.setVisibility(land ? View.GONE : View.VISIBLE);
        if (land) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
            tp.setMargins(dp(12), 0, dp(14), 0);
            headerRow.addView(tabBar, headerRow.indexOfChild(headerDot), tp);
            rootCol.setPadding(dp(16), dp(8), dp(16), dp(8));
        } else {
            rootCol.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootCol.setPadding(dp(16), dp(20), dp(16), dp(8));
        }
    }
    /** Portrait: screens above, controls below. Landscape: side by side, both fully on screen. */
    private void layoutRemoteTab(boolean land) {
        if (remoteRoot == null) return;
        remoteRoot.removeAllViews();
        remoteRoot.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (land) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); l.rightMargin = dp(10);
            LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); r.leftMargin = dp(10);
            remoteRoot.addView(remoteLeft, l); remoteRoot.addView(remoteRight, r);
        } else {
            remoteRoot.addView(remoteLeft, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            remoteRoot.addView(remoteRight, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        boolean land = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        layoutChrome(land); layoutRemoteTab(land);
    }
    private LinearLayout.LayoutParams gcLp(float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), weight); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private void refreshAll() { refreshDeviceCards(); }

    // Ephemeral connection blurb near the corner light: "Bluetooth connected" / "Wi-Fi connected" / "down".
    private final LinkIcon[] linkIcons = new LinkIcon[3];
    private void refreshLinkIcons() {
        RemoteService svc = RemoteService.get();
        boolean[] on = svc != null && !local ? svc.linkLevels() : new boolean[3];
        for (int k = 0; k < 3; k++) if (linkIcons[k] != null) linkIcons[k].setLit(on[k]);
    }
    /** Bluetooth rune, hotspot (a point broadcasting both ways) or Wi-Fi fan; lit green or greyed. */
    private static final class LinkIcon extends View {
        private final int kind; private boolean lit;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        LinkIcon(android.content.Context c, int kind) {
            super(c); this.kind = kind;
            p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeCap(android.graphics.Paint.Cap.ROUND); p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            setContentDescription(kind == 0 ? "Bluetooth" : kind == 1 ? "Hotspot" : "Shared Wi-Fi");
        }
        void setLit(boolean l) { if (l != lit) { lit = l; invalidate(); } }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(lit ? GOOD : 0x55FFFFFF); p.setStrokeWidth(w * 0.09f); p.setStyle(android.graphics.Paint.Style.STROKE);
            if (kind == 0) {          // Bluetooth
                android.graphics.Path b = new android.graphics.Path();
                b.moveTo(w * 0.30f, h * 0.30f); b.lineTo(w * 0.70f, h * 0.68f); b.lineTo(w * 0.50f, h * 0.88f); b.lineTo(w * 0.50f, h * 0.12f);
                b.lineTo(w * 0.70f, h * 0.32f); b.lineTo(w * 0.30f, h * 0.70f);
                cv.drawPath(b, p);
            } else if (kind == 1) {   // hotspot: a phone broadcasting (mast + waves), with a Wi-Fi badge
                float sw = w * 0.07f; p.setStrokeWidth(sw);
                cv.drawRoundRect(new android.graphics.RectF(w * 0.16f, h * 0.04f, w * 0.74f, h * 0.96f), w * 0.10f, w * 0.10f, p);
                float cx = w * 0.45f, top = h * 0.40f;
                cv.drawLine(cx, top, cx, h * 0.80f, p);                       // the mast
                cv.drawLine(cx, h * 0.80f, cx - w * 0.07f, h * 0.84f, p); cv.drawLine(cx, h * 0.80f, cx + w * 0.07f, h * 0.84f, p);
                p.setStrokeWidth(sw * 0.8f);
                for (int i = 1; i <= 2; i++) {                                // waves both sides of its tip
                    float r = w * 0.09f * i;
                    android.graphics.RectF o = new android.graphics.RectF(cx - r, top - r, cx + r, top + r);
                    cv.drawArc(o, -50, 100, false, p); cv.drawArc(o, 130, 100, false, p);
                }
                p.setStyle(android.graphics.Paint.Style.FILL); cv.drawCircle(cx, top, w * 0.045f, p);
                // the badge: a filled square, Wi-Fi fan cut out of it in the background colour
                android.graphics.RectF bg = new android.graphics.RectF(w * 0.56f, h * 0.60f, w * 0.98f, h * 0.98f);
                cv.drawRoundRect(bg, w * 0.07f, w * 0.07f, p);
                int keep = p.getColor(); p.setColor(BG); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(w * 0.045f);
                float bx = bg.centerX(), by = bg.bottom - h * 0.07f;
                for (int i = 1; i <= 2; i++) { float r = w * 0.10f * i; cv.drawArc(new android.graphics.RectF(bx - r, by - r, bx + r, by + r), 225, 90, false, p); }
                p.setStyle(android.graphics.Paint.Style.FILL); cv.drawCircle(bx, by, w * 0.025f, p); p.setColor(keep);
            } else {                  // Wi-Fi fan
                float cx = w / 2f, cy = h * 0.82f;
                for (int i = 1; i <= 3; i++) {
                    float r = w * 0.22f * i;
                    cv.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 225, 90, false, p);
                }
                p.setStyle(android.graphics.Paint.Style.FILL); cv.drawCircle(cx, cy, w * 0.07f, p);
            }
        }
    }
    private void showConnBlurb() {
        RemoteService svc = RemoteService.get();
        boolean[] on = svc != null ? svc.linkLevels() : new boolean[3];
        String msg = "Bluetooth: " + (on[0] ? "connected" : "off") + "\nHotspot: " + (on[1] ? "connected" : "off")
                + "\nShared Wi-Fi: " + (on[2] ? "connected" : "off");
        TextView t = new TextView(this); t.setText(msg); t.setTextColor(TXT); t.setTextSize(13);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), 0x33FFFFFF);
        t.setBackground(bg);
        final android.widget.PopupWindow pop = new android.widget.PopupWindow(t, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        pop.setElevation(dp(10));
        try { pop.showAsDropDown(dot, -dp(120), dp(4), Gravity.END); } catch (Exception ignored) {}
        main.postDelayed(() -> { try { pop.dismiss(); } catch (Exception ignored) {} }, 1900);
    }

    // A card per connected screen: checkbox + name + placeholder media art + its own show/progress. 2 per row.
    /** Ids the cards were last drawn for: discovery finishes AFTER the tab is built, so state
        arriving with a new screen has to trigger a re-render or the list stays empty forever. */
    private java.util.Set<String> renderedDevIds = new java.util.HashSet<>();

    private void refreshDeviceCards() {
        if (deviceCards == null) return;
        if (local) { showTvCard(); return; }
        refreshHeaderChecks(); refreshPlaybackRow();   // they follow the same state as the rows
        RemoteService svc = RemoteService.get();
        syncSelfCard(svc);   // this device's card holds live video, so it's kept, never rebuilt
        while (deviceCards.getChildCount() > (selfCard != null ? 1 : 0)) deviceCards.removeViewAt(deviceCards.getChildCount() - 1);
        java.util.List<java.util.Map.Entry<String, String>> devs = svc != null ? new java.util.ArrayList<>(svc.tablets().entrySet()) : new java.util.ArrayList<>();
        renderedDevIds = svc != null ? new java.util.HashSet<>(svc.tablets().keySet()) : new java.util.HashSet<>();
        if (devs.isEmpty()) {
            // "Not connected" with nothing to act on is the single most useless state in the app:
            // every real cause (Bluetooth off, a denied permission, the tablet not set as the TV)
            // is invisible from here. Send them somewhere that names it and offers the fix.
            deviceCards.addView(ui.noteText(app.anancy.Pairing.paired(this).isEmpty()
                    ? "No screens paired yet. This phone only controls screens it has paired with."
                    : "No screens connected yet."));
            Button pairB = ui.rowBtn("Pair a screen", ACCENT);
            pairB.setOnClickListener(v -> scanPairingCode());
            deviceCards.addView(pairB);
            Button chk = ui.rowBtn("Run the set-up check", TXT);
            chk.setOnClickListener(v -> startActivity(new Intent(this, app.anancy.SetupActivity.class)));
            deviceCards.addView(chk);
            return;
        }
        boolean all = allSelected(svc);
        for (java.util.Map.Entry<String, String> e : devs) {   // one 16:9 card per screen, in a scrolling list
            if (selfCard != null && e.getKey().equals(svc.selfId())) continue;   // already on top
            boolean sel = all || svc.targets().contains(e.getKey());
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (deviceCards.getChildCount() > 0) cp.topMargin = dp(6);
            deviceCards.addView(deviceCard(e.getKey(), e.getValue(), sel), cp);
        }
    }

    // --- The TV's own remote page: the show keeps playing in a card; expand goes back to it --------------
    private View tvCard;
    private android.view.TextureView tvVideo;
    private void showTvCard() {
        if (tvCard != null) return;
        deviceCards.removeAllViews();
        // One screen only, this TV: no framed scrolling panel around a list of one.
        if (cardsPanel != null) {
            cardsPanel.setBackground(null); cardsPanel.setPadding(0, 0, 0, 0);
            cardsPanel.setVerticalScrollBarEnabled(false);
        }
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); bg.setStroke(dp(2), ACCENT);
        c.setBackground(bg);
        Frame169 art = new Frame169(this); art.setBackgroundColor(0xFF000000);
        tvVideo = new android.view.TextureView(this);
        art.addView(tvVideo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LargeIcon large = new LargeIcon(this);
        GradientDrawable lb = new GradientDrawable(); lb.setShape(GradientDrawable.OVAL); lb.setColor(0xAA000000); large.setBackground(lb);
        large.setContentDescription("Back to the show");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);   // centred: the whole card goes back
        art.addView(large, lp);
        art.setOnClickListener(v -> finish());   // the show is right underneath: back to it, locked again
        large.setOnClickListener(v -> finish());
        c.addView(art);
        TextView nm = new TextView(this); nm.setText(app.anancy.Identity.name(this) + " (this TV)  ·  tap to go back to the show");
        nm.setTextColor(TXT); nm.setTextSize(13); nm.setPadding(dp(2), dp(7), 0, 0); nm.setMaxLines(1); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(nm);
        deviceCards.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvCard = c;
    }
    /** Draw the TV's show in the card while this page is up, at its real shape. */
    private void attachTvVideo(boolean on) {
        androidx.media3.exoplayer.ExoPlayer p = app.anancy.player.PlayerActivity.tvPlayer();
        if (p == null || tvVideo == null) return;
        if (!on) { p.clearVideoTextureView(tvVideo); return; }
        p.setVideoTextureView(tvVideo);
        androidx.media3.common.VideoSize vs = p.getVideoSize();
        View art = (View) tvVideo.getParent();
        art.post(() -> {
            int W = art.getWidth(), H = art.getHeight();
            if (vs.width <= 0 || vs.height <= 0 || W <= 0 || H <= 0) return;
            float r = vs.width * vs.pixelWidthHeightRatio / vs.height;
            int w = W, h = Math.round(W / r);
            if (h > H) { h = H; w = Math.round(H * r); }
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) tvVideo.getLayoutParams();
            flp.width = w; flp.height = h; flp.gravity = Gravity.CENTER; tvVideo.setLayoutParams(flp);
        });
    }

    // --- "This phone as playback": this device's own card, with the video playing in it -------------------
    private View selfCard;
    private android.view.TextureView selfVideo;
    private TextView selfBox, selfTitle;
    private ProgressBar selfProgress;
    private GradientDrawable selfCardBg;
    /** Width-driven 16:9 box. */
    private static final class Frame169 extends FrameLayout {
        Frame169(android.content.Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 9 / 16, MeasureSpec.EXACTLY));
        }
    }
    /** The "make large" corners. Drawn, because fonts on these devices can't be trusted for symbols. */
    private static final class LargeIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        LargeIcon(android.content.Context c) { super(c); p.setColor(0xFFFFFFFF); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeCap(android.graphics.Paint.Cap.ROUND); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), a = w * 0.28f, b = w * 0.72f, l = w * 0.15f;
            p.setStrokeWidth(w * 0.07f);
            c.drawLine(a, a, a + l, a, p); c.drawLine(a, a, a, a + l, p);
            c.drawLine(b, a, b - l, a, p); c.drawLine(b, a, b, a + l, p);
            c.drawLine(a, b, a + l, b, p); c.drawLine(a, b, a, b - l, p);
            c.drawLine(b, b, b - l, b, p); c.drawLine(b, b, b, b - l, p);
        }
    }
    private void syncSelfCard(RemoteService svc) {
        boolean on = svc != null && svc.selfPlay();
        if (!on) {
            if (selfCard != null) {
                app.anancy.player.SelfPlayer.hideFrom(selfVideo);
                deviceCards.removeView(selfCard);
                selfCard = null; selfVideo = null;
            }
            return;
        }
        if (selfCard == null) {
            selfCard = buildSelfCard(svc);
            deviceCards.addView(selfCard, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            app.anancy.player.SelfPlayer.startPaused(this);   // it was ticked before: the card comes back, silent until Play
            app.anancy.player.SelfPlayer.showIn(selfVideo);
        }
        boolean sel = allSelected(svc) || svc.targets().contains(svc.selfId());
        selfCardBg.setStroke(dp(2), sel ? ACCENT : 0x22FFFFFF);
        if (selfBadge != null) selfBadge.setVisibility(svc.groupLive() && svc.selfId().equals(svc.syncLeaderId()) ? View.VISIBLE : View.GONE);
        selfBox.setText(sel ? "☑" : "☐"); selfBox.setTextColor(sel ? GOOD : 0xFFCFD6E4);
        try {
            org.json.JSONObject o = new org.json.JSONObject(app.anancy.player.PlayerController.get().stateJson());
            String show = o.optString("title", "").replace('.', ' ').trim();
            int dur = o.optInt("duration_ms", 0);
            selfTitle.setText(show.isEmpty() ? "—" : show);
            selfProgress.setProgress(dur > 0 ? Math.round(1000f * o.optInt("position_ms", 0) / dur) : 0);
        } catch (Exception ignored) {}
        fitSelfVideo();
    }
    /** Draw the video at its real shape inside the 16:9 card, rather than stretched to it. */
    private void fitSelfVideo() {
        float r = app.anancy.player.SelfPlayer.videoAspect();
        View art = (View) selfVideo.getParent();
        int W = art.getWidth(), H = art.getHeight();
        if (r <= 0 || W <= 0 || H <= 0) return;
        int w = W, h = Math.round(W / r);
        if (h > H) { h = H; w = Math.round(H * r); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selfVideo.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
        selfVideo.setLayoutParams(lp);
    }
    private View selfBadge;
    /** One screen as a row: preview on the left, name / show / progress on the right. */
    private LinearLayout screenRow(GradientDrawable bg) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(3), dp(3), dp(6), dp(3));
        bg.setColor(CARD); bg.setCornerRadius(dp(12)); c.setBackground(bg);
        return c;
    }
    private LinearLayout.LayoutParams previewLp() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.40f); }
    private LinearLayout infoCol(String name, TextView titleView, ProgressBar pb) {
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(10), 0, 0, 0);
        TextView nm = new TextView(this); nm.setText(name); nm.setTextColor(TXT); nm.setTextSize(14); nm.setMaxLines(1); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setTypeface(nm.getTypeface(), android.graphics.Typeface.BOLD); info.addView(nm);
        titleView.setTextColor(SUB); titleView.setTextSize(11); titleView.setMaxLines(1); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(0, dp(2), 0, 0); info.addView(titleView);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(withAlpha(TXT, 0x22)));
        LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)); pgp.topMargin = dp(6); info.addView(pb, pgp);
        return info;
    }
    private View leaderBadge() {
        LeaderBadge b = new LeaderBadge(this); b.setContentDescription("Group leader");
        return b;
    }
    private FrameLayout.LayoutParams badgeLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.END | Gravity.BOTTOM); lp.setMargins(0, 0, dp(4), dp(4));
        return lp;
    }
    private View buildSelfCard(RemoteService svc) {
        final String id = svc.selfId();
        selfCardBg = new GradientDrawable();
        LinearLayout c = screenRow(selfCardBg);
        Frame169 art = new Frame169(this); art.setBackgroundColor(0xFF000000);
        selfVideo = new android.view.TextureView(this);
        art.addView(selfVideo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The preview in two halves: the left ticks this screen, the right makes it large. One card used
        // to do both, and a tap just off the small "large" button quietly dropped this phone (and with
        // it the whole synced group).
        LinearLayout halves = new LinearLayout(this); halves.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout tickHalf = new FrameLayout(this); tickHalf.setClickable(true); tickHalf.setContentDescription("Choose this screen");
        tickHalf.setOnClickListener(v -> toggleScreen(id));
        selfBox = new TextView(this); selfBox.setTextSize(24);
        tickHalf.addView(selfBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));   // centred in its half
        FrameLayout largeHalf = new FrameLayout(this); largeHalf.setClickable(true); largeHalf.setContentDescription("Make large");
        largeHalf.setOnClickListener(v -> app.anancy.player.SelfPlayer.openLarge(this));
        LargeIcon large = new LargeIcon(this);
        GradientDrawable lb = new GradientDrawable(); lb.setShape(GradientDrawable.OVAL); lb.setColor(0xAA000000); large.setBackground(lb);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER);   // centred in its half
        largeHalf.addView(large, llp);
        selfBadge = leaderBadge(); selfBadge.setVisibility(View.GONE);
        largeHalf.addView(selfBadge, badgeLp());
        halves.addView(tickHalf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        halves.addView(largeHalf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        art.addView(halves, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        c.addView(art, previewLp());
        selfTitle = new TextView(this);
        selfProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); selfProgress.setMax(1000);
        LinearLayout info = infoCol(svc.tablets().get(id) + " (this device)", selfTitle, selfProgress);
        info.setClickable(true); info.setOnClickListener(v -> toggleScreen(id));
        c.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.60f));
        return c;
    }
    private View deviceCard(final String id, String name, final boolean selected) {
        GradientDrawable bg = new GradientDrawable(); bg.setStroke(dp(2), selected ? ACCENT : 0x22FFFFFF);
        LinearLayout c = screenRow(bg);
        c.setClickable(true); c.setOnClickListener(v -> toggleScreen(id)); ui.pressFx(c);   // the whole row ticks it
        FrameLayout artBox = new FrameLayout(this);
        GradientDrawable ab = new GradientDrawable(); ab.setColor(0x33000000); ab.setCornerRadius(dp(8)); artBox.setBackground(ab);
        TextView box = new TextView(this); box.setText(selected ? "☑" : "☐"); box.setTextColor(selected ? GOOD : 0xFFCFD6E4); box.setTextSize(24);
        artBox.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));   // centred in the preview
        RemoteService svc = RemoteService.get();
        if (svc != null && svc.groupLive() && id.equals(svc.syncLeaderId())) artBox.addView(leaderBadge(), badgeLp());
        Frame169 art169 = new Frame169(this);
        art169.addView(artBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        c.addView(art169, previewLp());
        String st = devState.get(id); String show = ""; int posMs = 0, durMs = 0;
        if (st != null) try { org.json.JSONObject o = new org.json.JSONObject(st); show = o.optString("title", "").replace('.', ' ').trim(); posMs = o.optInt("position_ms", 0); durMs = o.optInt("duration_ms", 0); } catch (Exception ignored) {}
        TextView tt = new TextView(this); tt.setText(show.isEmpty() ? "—" : show);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); pb.setProgress(durMs > 0 ? Math.round(1000f * posMs / durMs) : 0);
        c.addView(infoCol(name, tt, pb), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.60f));
        return c;
    }
    /** Gold badge: an L wearing a crown. Marks the screen a synced group follows. */
    private static final class LeaderBadge extends View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        LeaderBadge(android.content.Context c) {
            super(c);
            fill.setColor(0xFFE2B33C);
            ink.setColor(0xFF2A1E05); ink.setStyle(android.graphics.Paint.Style.STROKE);
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND); ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            cv.drawCircle(w / 2f, h / 2f, r, fill);
            ink.setStrokeWidth(r * 0.16f);
            // the L
            float lx = w * 0.40f, top = h * 0.44f, bot = h * 0.76f, right = w * 0.64f;
            cv.drawLine(lx, top, lx, bot, ink); cv.drawLine(lx, bot, right, bot, ink);
            // its crown: three points over the top of the L
            ink.setStrokeWidth(r * 0.11f);
            android.graphics.Path crown = new android.graphics.Path();
            float cy = h * 0.34f, ch = h * 0.13f, cl = w * 0.30f, cr = w * 0.56f;
            crown.moveTo(cl, cy); crown.lineTo(cl, cy - ch); crown.lineTo((cl + cr) / 2f - w * 0.06f, cy - ch * 0.4f);
            crown.lineTo((cl + cr) / 2f, cy - ch * 1.2f); crown.lineTo((cl + cr) / 2f + w * 0.06f, cy - ch * 0.4f);
            crown.lineTo(cr, cy - ch); crown.lineTo(cr, cy); crown.close();
            cv.drawPath(crown, ink);
        }
    }
    private void toggleScreen(String id) {
        RemoteService svc = RemoteService.get(); if (svc == null) return;
        java.util.List<String> ids = new java.util.ArrayList<>(svc.tablets().keySet());
        if (ids.isEmpty()) return;
        boolean all = allSelected(svc);
        java.util.LinkedHashSet<String> ns = new java.util.LinkedHashSet<>(all ? ids : svc.targets());
        if (ns.contains(id)) {
            if (ns.size() > 1) ns.remove(id);                                    // one of several -> just drop it
            else { String next = ids.get((ids.indexOf(id) + 1) % ids.size()); ns.clear(); ns.add(next); }   // last one -> hop the check to the next device
        } else ns.add(id);
        if (ns.isEmpty()) ns.add(id);
        applySelection(ns);
    }
    /** True when every connected screen is selected (targets empty, or contains them all). */
    private boolean allSelected(RemoteService svc) {
        java.util.Set<String> t = svc.targets();
        return t.isEmpty() || (!svc.tablets().isEmpty() && t.containsAll(svc.tablets().keySet()));
    }
    /** Apply a new screen selection, keeping any armed sync group consistent (re-arm ≥2, else disarm). */
    private void applySelection(java.util.Set<String> ns) {
        RemoteService svc = RemoteService.get(); if (svc == null) return;
        boolean wasSync = svc.isSyncGroup();
        svc.setTargets(ns);
        if (wasSync) {
            int n = svc.targets().isEmpty() ? svc.tablets().size() : svc.targets().size();
            if (n >= 2) svc.setSyncGroup(true); else svc.setSyncGroup(false);
        }
        refreshAll();
    }
    private void startCardPolling() {
        if (cardPoller != null) return;
        cardPoller = new Runnable() { public void run() {
            RemoteService svc = RemoteService.get();
            if (svc != null && curTab == 0) {
                for (final java.util.Map.Entry<String, String> e : new java.util.ArrayList<>(svc.tablets().entrySet())) {
                    final String id = e.getKey(); final String ip = svc.deviceIp(id);
                    if (ip == null || ip.isEmpty()) continue;
                    new Thread(() -> {
                        String s = httpGetShort("http://" + ip + ":8090/state");
                        if (s != null && s.startsWith("{")) { devState.put(id, s); main.post(() -> { if (curTab == 0) refreshDeviceCards(); }); }
                    }).start();
                }
            }
            main.postDelayed(this, 1600);
        }};
        main.post(cardPoller);
    }
    private void stopCardPolling() { if (cardPoller != null) { main.removeCallbacks(cardPoller); cardPoller = null; } }
    private String httpGetShort(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = openOnWifi(url); c.setConnectTimeout(1500); c.setReadTimeout(1500);
            return app.anancy.Pairing.fetch(this, c);   // only an answer the TV proves it sent
        } catch (Exception e) { return null; }
        finally { if (c != null) try { c.disconnect(); } catch (Exception ignored) {} }
    }

    // --- TAB 2: Playlists — pick a playlist (built-ins + your saved queues) ---
    private View buildPlaylistsTab() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(2), dp(2), dp(2), dp(16));
        plNow = new TextView(this);
        plNow.setText("Now playing:  Shuffle All"); plNow.setTextColor(SUB); plNow.setTextSize(13);
        plNow.setPadding(dp(6), 0, 0, dp(8));
        col.addView(plNow);   // child 0 = header line kept by populatePicker
        plCol = col; picker = sv;
        sv.addView(col);
        return sv;
    }

    // --- TAB 3: Queue — the play order + building/importing (Add from library, Save, Import) ---
    private View buildQueueTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL); wrap.setBackgroundColor(BG);

        // build/import bar (top): Add from library · Import a video from this phone
        LinearLayout build = new LinearLayout(this); build.setOrientation(LinearLayout.HORIZONTAL);
        build.setPadding(0, 0, 0, dp(6));
        Button addLib = ui.smallBtn("＋  Add from library"); addLib.setTextColor(ACCENT);
        addLib.setOnClickListener(v -> openLibrary());
        Button imp = ui.smallBtn("⬆  Import video"); imp.setTextColor(ACCENT);
        imp.setOnClickListener(v -> pickImport());
        build.addView(addLib, wlp(1.4f)); build.addView(imp, wlp(1f));
        wrap.addView(build);

        // scrollable queue list: [0] header, [1] toolbar(shuffle/loop/save), [2+] rows
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, 0, 0, dp(16));
        TextView hh = new TextView(this);
        hh.setText("Up next"); hh.setTextColor(TXT); hh.setTextSize(18);
        hh.setTypeface(hh.getTypeface(), android.graphics.Typeface.BOLD); hh.setPadding(dp(4), 0, 0, dp(6));
        col.addView(hh);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        Button sh = ui.smallBtn(""), lo = ui.smallBtn(""), save = ui.smallBtn("Save as playlist…");
        sh.setOnClickListener(v -> { send("shuffle:toggle"); toast(lastShuffle ? "Shuffle off" : "Shuffle on"); main.postDelayed(() -> loadQueue(col), 800); });
        lo.setOnClickListener(v -> { send("loop:toggle"); toast(lastLoop ? "Will stop at the end (TV off)" : "Looping forever"); main.postDelayed(() -> loadQueue(col), 800); });
        save.setOnClickListener(v -> saveQueueAs());
        Button clr = ui.smallBtn("Clear");
        clr.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear the queue?")
                .setMessage(lastPlaying ? "Keeps the episode that's playing and drops the rest."
                                        : "Nothing is playing, so the queue will be emptied.")
                .setPositiveButton("Clear", (d, w) -> { send("clearq"); main.postDelayed(() -> loadQueue(col), 900); })
                .setNegativeButton("Cancel", null).show());
        bar.addView(sh, wlp(1f)); bar.addView(lo, wlp(1.2f)); bar.addView(save, wlp(1.5f)); bar.addView(clr, wlp(0.9f));
        col.addView(bar);
        TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0); col.addView(t);
        qCol = col; queueView = sv;
        sv.addView(col);
        wrap.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderQueueBar(col);
        return wrap;
    }

    // --- service wiring ------------------------------------------------------
    private final RemoteService.Listener svcListener = new RemoteService.Listener() {
        public void onStatus(String s) {
            boolean ready = s.startsWith("ready");
            refreshLinkIcons();
            if (connView != null) renderConnStatus();
        }
        public void onState(String json) { showState(json); }
        public void onOtherState(String json) {   // keeps the other screens' rows showing their show
            try { String id = new JSONObject(json).optString("id", ""); if (!id.isEmpty()) devState.put(id, json); } catch (Exception ignored) {}
        }
    };
    private void attach() {
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setListener(svcListener); else main.postDelayed(this::attach, 300);
    }
    private void send(String cmd) { RemoteService svc = RemoteService.get(); if (svc != null) svc.send(cmd); }
    private View toddlerCell;
    /** The Toddler button lights up while the screen's toddler lock is on. */
    private void showToddler(boolean on) {
        if (toddlerCell == null || !(toddlerCell.getBackground() instanceof GradientDrawable)) return;
        GradientDrawable g = (GradientDrawable) toddlerCell.getBackground();
        g.setColor(withAlpha(PURPLE, on ? 0x77 : 0x22));
        g.setStroke(dp(on ? 2 : 1), withAlpha(PURPLE, on ? 0xFF : 0x55));
        if (toddlerCell instanceof LinearLayout && ((LinearLayout) toddlerCell).getChildAt(1) instanceof TextView)
            ((TextView) ((LinearLayout) toddlerCell).getChildAt(1)).setText(on ? "Toddler: on" : "Toddler");
    }

    /** Position / sleep countdown interpolated from the last push (ticks every second while visible). */
    private void renderClock() {
        long el = lastPlaying ? System.currentTimeMillis() - lastStateAt : 0;
        int pos = (int) Math.min(lastDur > 0 ? lastDur : Integer.MAX_VALUE, lastPos + el);
        progress.setProgress(lastDur > 0 ? Math.round(1000f * pos / lastDur) : 0);
        String bat = lastBattery < 0 ? "" : "     " + (lastCharging ? "⚡ " : "🔋 ") + lastBattery + "%";
        timeText.setText((lastDur > 0 ? mmss(pos) + " / " + mmss(lastDur) + "     vol " + Math.round(lastVol * 100) + "%" : "TV") + bat);
        if (lastSleeping) {
            long s = Math.max(0, lastSleepMs - (System.currentTimeMillis() - lastStateAt)) / 1000;
            sleepText.setText("Sleeping in " + (s / 60) + ":" + String.format("%02d", s % 60));
        } else if (lastNapped) sleepText.setText("TV is off");
        else sleepText.setText("");
        sleepText.setVisibility(sleepText.getText().length() > 0 ? View.VISIBLE : View.GONE);   // no empty line holding space
    }

    private void showState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            // Feed the per-screen card from THIS state too. It used to be filled only by the HTTP
            // poller, so on the Bluetooth tier the card sat on "-" forever, and after a Wi-Fi session
            // it kept showing whatever episode was playing when the hotspot went away - the card and
            // the line under it disagreed. The BLE state carries id/title/position, so just use it.
            String devId = o.optString("id", "");
            if (!devId.isEmpty()) devState.put(devId, json);
            boolean playing = o.optBoolean("playing", false);
            playPause.setPlaying(playing);
            String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
            int i = t.indexOf(".S");
            title.setText((i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim());
            subtitle.setText((i > 0 ? t.substring(i + 1) : "").replace('.', ' ').replace('⧸', '/').trim());
            lastPos = o.optInt("position_ms", 0); lastDur = o.optInt("duration_ms", 0); lastPlaying = playing;
            lastVol = o.optDouble("volume", 0); lastStateAt = System.currentTimeMillis();
            lastSleeping = o.optBoolean("sleeping", false); lastSleepMs = Math.max(0, o.optLong("sleep_remaining_ms", 0));
            lastNapped = o.optBoolean("napped", false);
            lastBattery = o.optInt("battery", -1); lastCharging = o.optBoolean("charging", false);
            renderClock();
            currentPl = o.optString("playlist", "Shuffle All");
            if (plNow != null) plNow.setText("Now playing:  " + currentPl);
            String ip = o.optString("ip", "");
            if (!ip.isEmpty() && !local) tabletIp = ip;   // local stays on loopback
            // queue mode + connection level + the TV's hotspot creds (present only while it runs)
            lastShuffle = o.optBoolean("shuffle", lastShuffle); lastLoop = o.optBoolean("loop", lastLoop);
            lastPlaymode = o.optBoolean("playmode", lastPlaymode);
            showToddler(lastPlaymode);
            if (o.has("conn")) lastConn = o.optString("conn", lastConn);
            JSONObject hs = o.optJSONObject("hotspot");
            String wasHs = hsSsid;
            hsSsid = hs != null ? hs.optString("ssid", null) : null; hsPass = hs != null ? hs.optString("pass", null) : null;
            if (connView != null) {
                renderConnStatus();
                // The TV needs a few seconds to raise its hotspot, so the Join button was built before
                // the creds arrived and then sat on "waiting for the TV to publish it" until you left
                // the screen and came back -- the one tier that needs a tap looked dead. Rebuild it when
                // the creds actually change, and only then: rebuilding every tick cancels a tap mid-press.
                if (!String.valueOf(wasHs).equals(String.valueOf(hsSsid))) { renderConnActions(); probeTv(); }
            }
            RemoteService sv2 = RemoteService.get();   // ...and the per-screen CARDS, which were built once with an empty list
            if (sv2 != null && curTab == 0
                    && (!sv2.tablets().keySet().equals(renderedDevIds) || !devId.isEmpty())) refreshDeviceCards();
        } catch (Exception ignored) {}
    }

    // --- Playlists tab: fill plCol with the playlist rows (built-ins + your saved queues) ---
    private void populatePicker(final LinearLayout col, final int attempt) {
        final RemoteService svc = RemoteService.get();
        List<String> names = svc != null ? svc.playlistNames() : null;
        List<String> user = svc != null ? svc.userPlaylistNames() : new java.util.ArrayList<>();
        // keep the header (index 0), rebuild the rows
        while (col.getChildCount() > 1) col.removeViewAt(1);
        if (names != null && !names.isEmpty()) {
            boolean userHeader = false;
            for (final String name : names) {
                final boolean mine = user.contains(name);
                if (mine && !userHeader) {   // user-made playlists (saved queues) get their own section
                    userHeader = true;
                    TextView s = ui.noteText("Your playlists  ·  long-press to delete"); s.setTextColor(0xFF8FD694); col.addView(s);
                }
                Button row = ui.rowBtn(name, name.equals(currentPl) ? 0xFF8FD694 : TXT);
                row.setTextSize(16);
                row.setOnClickListener(v -> { send("play:" + name); showTab(0); });
                if (mine) row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle("Delete playlist?").setMessage(name)
                            .setPositiveButton("Delete", (d, w) -> {
                                send("delpl:" + name); toast("Deleted \"" + name + "\"");
                                if (svc != null) main.postDelayed(svc::requestPlaylists, 500);
                                main.postDelayed(() -> { if (picker != null) populatePicker(col, 0); }, 1200);
                            }).setNegativeButton("Cancel", null).show();
                    return true;
                });
                col.addView(row);
            }
        }
        int total = svc != null ? svc.playlistTotal() : 0;
        boolean incomplete = names == null || names.isEmpty() || (total > 0 && names.size() < total);
        if (incomplete && attempt < 20) {   // BLE delivers the list in pages; keep redrawing until they have all arrived
            if (names == null || names.isEmpty()) {
                TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0);
                col.addView(t);
            }
            main.postDelayed(() -> { if (picker != null) populatePicker(col, attempt + 1); }, 400);
        }
    }
    private LinearLayout.LayoutParams wlp(float w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), w); p.setMargins(dp(2), 0, dp(2), 0); return p; }
    private void renderQueueBar(LinearLayout col) {
        if (col.getChildCount() < 2 || !(col.getChildAt(1) instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) col.getChildAt(1);
        Button sh = (Button) bar.getChildAt(0), lo = (Button) bar.getChildAt(1);
        sh.setText(lastShuffle ? "Shuffle: on" : "Shuffle: off"); sh.setTextColor(lastShuffle ? 0xFF8FD694 : TXT);
        lo.setText(lastLoop ? "Loop forever" : "Stop at end");
    }
    private void loadQueue(final LinearLayout col) {
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.requestQueue();   // BLE: a ±4 window around the current item (always available)
        if (tabletIp != null && !tabletIp.isEmpty()) {   // Wi-Fi: a wide window (±80) around the current item
            new Thread(() -> {
                try {
                    java.net.HttpURLConnection c = openOnWifi("http://" + tabletIp + ":8090/queue?center=-1&n=80");
                    c.setConnectTimeout(2000); c.setReadTimeout(4000);
                    final String json = app.anancy.Pairing.fetch(this, c);
                    if (json != null) main.post(() -> { if (queueView != null) renderQueue(col, json); });
                    c.disconnect();
                } catch (Exception ignored) {}   // the BLE window still paints
            }).start();
        }
        populateQueue(col, 0);
    }
    private void populateQueue(final LinearLayout col, final int attempt) {
        RemoteService svc = RemoteService.get();
        String json = svc != null ? svc.queueJson() : null;
        if (json != null) { renderQueue(col, json); return; }   // always render: the old childCount guard froze this list forever
        if (attempt < 12) main.postDelayed(() -> { if (queueView != null) populateQueue(col, attempt + 1); }, 400);
    }
    private void renderQueue(final LinearLayout col, String json) {
        try {
            JSONObject o = new JSONObject(json);
            int from = o.optInt("from", 0), index = o.optInt("index", -1), total = o.optInt("total", 0);
            org.json.JSONArray items = o.optJSONArray("items");
            if (o.has("shuffle")) lastShuffle = o.optBoolean("shuffle");
            if (o.has("loop")) lastLoop = o.optBoolean("loop");
            renderQueueBar(col);
            boolean append = from > 0 && from == queueFrom + queueShown && col.getChildCount() > 2;
            if (append) while (col.getChildCount() > 2 + queueShown) col.removeViewAt(col.getChildCount() - 1);
            else { while (col.getChildCount() > 2) col.removeViewAt(2); queueFrom = from; queueShown = 0; }
            ((TextView) col.getChildAt(0)).setText("Up next  ·  " + o.optString("playlist", currentPl) + "   (" + total + ")");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                final int abs = from + i;
                final String name = pretty(items.optString(i));
                Button row = ui.rowBtn((abs == index ? "▶  " : "") + (abs + 1) + ".  " + name, abs == index ? 0xFF8FD694 : TXT);
                row.setOnClickListener(v -> { send("jump:" + abs); showTab(0); });
                final boolean current = abs == index;
                row.setOnLongClickListener(v -> {
                    if (current) { toast("That one's playing now"); return true; }
                    new AlertDialog.Builder(this).setTitle("Remove from the queue?").setMessage(name)
                            .setPositiveButton("Remove", (d, w) -> { send("deq:" + abs); main.postDelayed(() -> loadQueue(col), 700); })
                            .setNegativeButton("Cancel", null).show();
                    return true;
                });
                col.addView(row);
            }
            queueShown += items.length();
            final int end = queueFrom + queueShown;
            if (end < total) {   // the queue arrives in pages over BLE - let them pull the next one
                Button more = ui.rowBtn("Load more…   (" + queueShown + " of " + total + ")", ACCENT);
                more.setOnClickListener(v -> {
                    RemoteService s2 = RemoteService.get();
                    if (s2 != null) { s2.requestQueueFrom(end); populateQueue(col, 0); }
                });
                col.addView(more);
            }
            col.addView(ui.noteText("Tap to jump  ·  long-press to remove"));
        } catch (Exception ignored) {}
    }
    private int queueFrom = 0, queueShown = 0;   // absolute range currently rendered, so pages append instead of replacing
    private void saveQueueAs() {
        final EditText e = ui.field("Playlist name", false);
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Save the queue as a playlist").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) { toast("Give it a name"); return; }
                    send("savequeue:" + n); toast("Saved as \"" + n + "\"");
                    RemoteService svc = RemoteService.get(); if (svc != null) main.postDelayed(svc::requestPlaylists, 800);
                }).setNegativeButton("Cancel", null).show();
    }
    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close(); return bo.toString("UTF-8");
    }
    @Override public void onBackPressed() {
        if (still != null) closeStill();
        else if (epView != null) closeEpisodes();
        else if (libView != null) closeLibrary();
        else if (settingsView != null) closeSettings();
        else if (connView != null) closeConnection();
        else if (curTab != 0) showTab(0);   // a sub-tab -> back to the Remote tab
        else super.onBackPressed();
    }

    // =====================================================================================
    //  Shared overlay helpers (library / episodes / connection / queue / picker)
    // =====================================================================================
    private ScrollView overlay(String title) {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(20), dp(16), dp(16));
        // Header row with a VISIBLE way out: these full-screen overlays used to rely on the system
        // Back gesture alone, which left the Library looking like a dead end with no tab bar.
        LinearLayout hrow = new LinearLayout(this); hrow.setOrientation(LinearLayout.HORIZONTAL);
        hrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = ui.smallBtn("‹  Back"); back.setTextColor(ACCENT);
        back.setOnClickListener(v -> onBackPressed());
        hrow.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = new TextView(this);
        h.setText(title); h.setTextColor(TXT); h.setTextSize(20);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(10));
        h.setPadding(dp(10), 0, 0, 0);
        hrow.addView(h);
        col.addView(hrow);
        TextView t = new TextView(this); t.setText("loading…"); t.setTextColor(SUB); t.setPadding(dp(6), dp(10), 0, 0); col.addView(t);
        sv.addView(col); frame.addView(sv);
        return sv;
    }
    /** Make an overlay's title a dropdown, the same control as the main page's "BackSeeTV ⌄". A title
        plus an "applying to" line plus a row to get at your own settings is three things saying what
        one dropdown says; this returns the title view so a popup can hang off it. */
    private TextView overlayTitleMenu(View ov, String text, final Runnable onTap) {
        LinearLayout hrow = (LinearLayout) colOf(ov).getChildAt(0);
        TextView h = (TextView) hrow.getChildAt(1);
        h.setText(text + "  ⌄");
        h.setClickable(true);
        h.setOnClickListener(v -> onTap.run());
        return h;
    }
    private static LinearLayout colOf(View overlay) { return (LinearLayout) ((ScrollView) overlay).getChildAt(0); }
    private static void clearRows(LinearLayout col, int keep) { while (col.getChildCount() > keep) col.removeViewAt(keep); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String pretty(String t) { return Fmt.pretty(t); }   // pure impl extracted to Fmt (unit-tested)
    private static String enc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    /** Typed query ({"type":..} payload): Wi-Fi fast path when the TV's IP is reachable (local mode
        always), otherwise Bluetooth (the paged protocol). cb runs on the main thread; null = failure. */
    private void fetchTyped(final String httpPath, final String bleCmd, final String type, final java.util.function.Consumer<JSONObject> cb) {
        new Thread(() -> {
            String ip = tabletIp;
            if (ip != null && !ip.isEmpty()) {
                java.net.HttpURLConnection c = null;
                try {
                    c = openOnWifi("http://" + ip + ":8090" + httpPath);
                    c.setConnectTimeout(1500); c.setReadTimeout(5000);
                    String body = app.anancy.Pairing.fetch(this, c);   // only an answer the TV proves it sent
                    if (body != null) {
                        final JSONObject o = new JSONObject(body);
                        if (type.equals(o.optString("type"))) { main.post(() -> cb.accept(o)); return; }
                    }
                } catch (Exception ignored) {} finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
            }
            RemoteService svc = RemoteService.get();
            if (svc == null) { main.post(() -> cb.accept(null)); return; }
            svc.requestTyped(bleCmd, type);
            for (int i = 0; i < 25; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
                String j = svc.typedJson(type);
                if (j != null) {
                    try { final JSONObject o = new JSONObject(j); main.post(() -> cb.accept(o)); }
                    catch (Exception e) { main.post(() -> cb.accept(null)); }
                    return;
                }
            }
            main.post(() -> cb.accept(null));
        }).start();
    }

    // =====================================================================================
    //  LIBRARY: browse the TV's shows / episodes and enqueue. HTTP when the TV is reachable
    //  (loopback on the TV itself), else Bluetooth with the paged "shows" / "eps:" protocol.
    // =====================================================================================
    private final java.util.List<String[]> libShows = new java.util.ArrayList<>();   // [name, count]
    private int libTotal = 0;
    private void openLibrary() {
        if (libView != null) return;
        libShows.clear(); libTotal = 0;
        libView = overlay("Library");
        loadShows(colOf(libView), 0);
    }
    private void loadShows(final LinearLayout col, final int from) {
        fetchTyped("/library", from > 0 ? "shows:" + from : "shows", "library", o -> {
            if (libView == null) return;
            if (o == null) { clearRows(col, 1); col.addView(ui.noteText("Couldn't reach the TV's library. Is the Bluetooth link up? A Wi-Fi level is faster — tap the connection label up top.")); return; }
            org.json.JSONArray a = o.optJSONArray("shows");
            libTotal = o.optInt("total", (a != null ? a.length() : 0) + from);
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.optJSONObject(i);
                if (s != null && from + i >= libShows.size()) libShows.add(new String[]{s.optString("name"), String.valueOf(s.optInt("count", 0))});
            }
            renderShows(col);
        });
    }
    private void renderShows(final LinearLayout col) {
        clearRows(col, 1);
        for (final String[] s : libShows) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = ui.rowBtn(s[0] + "   ·   " + s[1] + (s[1].equals("1") ? " episode" : " episodes"), TXT);
            open.setOnClickListener(v -> openEpisodes(s[0]));
            row.addView(open, new LinearLayout.LayoutParams(0, dp(52), 1f));
            Button add = ui.smallBtn("Add all"); add.setTextColor(ACCENT);
            add.setOnClickListener(v -> { send("enqshow:" + s[0]); toast("Added " + s[0] + " to the queue"); });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); alp.setMargins(dp(6), 0, 0, 0);
            row.addView(add, alp);
            col.addView(row);
        }
        if (libShows.size() < libTotal) { Button more = ui.rowBtn("Load more…  (" + libShows.size() + " of " + libTotal + ")", ACCENT); more.setOnClickListener(v -> loadShows(col, libShows.size())); col.addView(more); }
        if (libShows.isEmpty()) col.addView(ui.noteText("No shows found on the TV."));
        else col.addView(ui.noteText("Tap a show for its episodes  ·  Add all queues the whole show"));
    }
    private final java.util.List<String[]> epItems = new java.util.ArrayList<>();   // [id, title]
    private int epTotal = 0; private String epShow;
    private void openEpisodes(String show) {
        if (epView != null) return;
        epShow = show; epItems.clear(); epTotal = 0;
        epView = overlay("‹  " + show);
        colOf(epView).getChildAt(0).setClickable(true);
        colOf(epView).getChildAt(0).setOnClickListener(v -> closeEpisodes());
        loadEpisodes(colOf(epView), 0);
    }
    private void loadEpisodes(final LinearLayout col, final int from) {
        fetchTyped("/library?show=" + enc(epShow), "eps:" + epShow + ":" + from, "episodes", o -> {
            if (epView == null) return;
            if (o == null) { clearRows(col, 1); col.addView(ui.noteText("Couldn't load the episodes. Is the Bluetooth link up?")); return; }
            org.json.JSONArray a = o.optJSONArray("items");
            int f = o.optInt("from", 0);
            epTotal = o.optInt("total", f + (a != null ? a.length() : 0));
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && f + i >= epItems.size()) epItems.add(new String[]{String.valueOf(e.optInt("id", -1)), e.optString("title")});
            }
            renderEpisodes(col);
        });
    }
    private void renderEpisodes(final LinearLayout col) {
        clearRows(col, 1);
        for (final String[] e : epItems) {
            Button row = ui.rowBtn(pretty(e[1]), TXT);
            row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(pretty(e[1]))
                    .setItems(new String[]{"Play now", "Play next", "Add to queue"}, (d, w) -> {
                        send((w == 0 ? "enqnow:" : w == 1 ? "enqnext:" : "enq:") + e[0]);
                        toast(w == 0 ? "Playing" : w == 1 ? "Up next" : "Added to the queue");
                    }).show());
            col.addView(row);
        }
        if (epItems.size() < epTotal) { Button more = ui.rowBtn("Load more…  (" + epItems.size() + " of " + epTotal + ")", ACCENT); more.setOnClickListener(v -> loadEpisodes(col, epItems.size())); col.addView(more); }
        if (epItems.isEmpty()) col.addView(ui.noteText("No episodes."));
        else col.addView(ui.noteText("Tap an episode: Play now · Play next · Add to queue"));
    }
    private void closeEpisodes() { if (epView != null) { frame.removeView(epView); epView = null; } }
    private void closeLibrary() { closeEpisodes(); if (libView != null) { frame.removeView(libView); libView = null; } }

    // =====================================================================================
    //  CONNECTION LEVEL: Bluetooth only / TV hotspot / Remote hotspot / Shared Wi-Fi.
    //  The level is one setting shared by TV and remotes (anancy_conn); the TV publishes it
    //  in state and RemoteService coordinates the joins/pushes. This screen shows status,
    //  lets you change the level, and offers the manual actions for each.
    // The header label: which screens are selected — a name, "N screens", or "All".
    private String targetLabel(RemoteService svc, boolean ready) {
        if (svc == null) return "connect";
        if (svc.isSyncGroup()) return "Synced";
        if (svc.isSyncPending()) return "Linking…";
        java.util.Set<String> sel = svc.targets();
        if (sel.isEmpty()) return "All";
        if (sel.size() == 1) { String n = svc.tablets().get(sel.iterator().next()); return n != null ? n : (ready ? svc.transport() : "connect"); }
        return sel.size() + " screens";
    }

    // Top dropdown: a CUSTOM in-app popup (dark card, styled rows) — check any subset of screens to control
    // (1, some, or all). Toggling applies live. "All screens" (or checking everything) = All.
    private android.widget.PopupWindow targetPopup;
    private void showTargetMenu() {
        final RemoteService svc = RemoteService.get();
        if (svc == null) { openConnection(); return; }
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(6));
        // Scroll when the menu is taller than the screen (several screens + lock-step rows).
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        MaxScroll sv = new MaxScroll(this, maxH);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), 0x33FFFFFF);
        sv.setBackground(bg); sv.setClipToOutline(true);
        sv.addView(col);
        targetPopup = new android.widget.PopupWindow(sv, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        targetPopup.setElevation(dp(12));
        buildTargetRows(col, svc);
        targetPopup.showAsDropDown(titleDot, 0, dp(6), Gravity.START);   // drops below the app icon
    }
    /** A ScrollView that never grows past maxH — the popup menu scrolls instead of running off-screen. */
    private static class MaxScroll extends android.widget.ScrollView {
        private final int maxH;
        MaxScroll(Context c, int maxH) { super(c); this.maxH = maxH; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }
    /** One tappable cell for the top Settings | Connection row. */
    private View menuCell(String label, int color, final Runnable onTap) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(18), dp(13), dp(18), dp(13)); c.setClickable(true);
        TextView t = new TextView(this); t.setText(label); t.setTextColor(color); t.setTextSize(14);
        c.addView(t);
        c.setOnClickListener(v -> {
            if (targetPopup != null) targetPopup.dismiss();
            if (settingsPopup != null) { settingsPopup.dismiss(); settingsPopup = null; }
            onTap.run();
        });
        return c;
    }
    private void buildTargetRows(final LinearLayout col, final RemoteService svc) {
        col.removeAllViews();
        // Just the three places to go. The screens are chosen on their own rows and the playback
        // options sit under them, so the menu no longer repeats them.
        col.addView(menuCell("⚙  Settings", TXT, this::openSettings));
        col.addView(menuCell("Connection…", ACCENT, this::openConnection));
        if (local) {   // the TV's own remote only ever drives this TV
            TextView note = new TextView(this);
            note.setText("This remote controls this TV only. To choose screens or sync several, use a phone set up as the remote.");
            note.setTextColor(SUB); note.setTextSize(13); note.setPadding(dp(14), dp(10), dp(14), dp(12));
            col.addView(note);
            // The TV's way out, last in its menu like a phone's Shut down: off the locked show to the
            // tablet's own home screen.
            View sep = new View(this); sep.setBackgroundColor(0x22FFFFFF);
            col.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            col.addView(menuCell("Exit to the tablet's home screen", WARN, () -> {
                if (app.anancy.player.PlayerActivity.exitToLauncher()) finish();
                else toast("The show isn't running");
            }));
            return;
        }
        addPairRows(col, svc);
    }
    /** The header's two checkboxes. Leader-only shows with this device's row, greyed until Sync is on. */
    private LinearLayout headerChecks, playbackRow;
    private void refreshHeaderChecks() {
        RemoteService svc = RemoteService.get();
        if (headerChecks == null) return;
        headerChecks.removeAllViews();
        if (svc == null || local || svc.tablets().isEmpty()) return;
        View allRow = ui.checkRow("All screens", svc.targets().isEmpty(), () -> applySelection(java.util.Collections.<String>emptySet()));
        allRow.setPadding(dp(6), dp(3), dp(2), dp(3)); compact(allRow); shrinkLabel(allRow, 13f);
        headerChecks.addView(allRow);
        if (svc.selfPlay()) {
            final boolean lo = svc.groupLeaderOnly(), live = svc.isSyncGroup() || svc.isSyncPending();
            View row = ui.checkRow("Leader-only audio", lo, () -> {
                if (!live) { toast("Turn on Sync Playback first"); return; }
                svc.setGroupAudio(!lo); refreshHeaderChecks();
            });
            row.setPadding(dp(6), dp(3), dp(2), dp(3)); compact(row); shrinkLabel(row, 13f);
            if (!live) row.setAlpha(0.4f);
            headerChecks.addView(row);
        }
    }
    /** Under the screens: this phone as a screen too, and Sync Playback. */
    private void refreshPlaybackRow() {
        RemoteService svc = RemoteService.get();
        if (playbackRow == null) return;
        playbackRow.removeAllViews();
        if (svc == null || local) { playbackRow.setVisibility(View.GONE); return; }
        playbackRow.setVisibility(View.VISIBLE);
        final boolean self = svc.selfPlay();
        View selfRow = ui.checkRow("This phone as playback", self, () -> { svc.setSelfPlay(!self); refreshAll(); });
        selfRow.setPadding(dp(6), dp(9), dp(2), dp(9)); compact(selfRow);
        playbackRow.addView(selfRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!svc.tablets().isEmpty()) {
            final boolean sg = svc.isSyncGroup() || svc.isSyncPending();   // ticked while it links, so a tap cancels
            View syncRow = ui.checkRow("Sync Playback", sg, () -> {
                if (!svc.setSyncGroup(!sg)) toast("Pick two or more screens to sync");
                refreshAll();
            });
            syncRow.setPadding(dp(10), dp(9), dp(2), dp(9)); compact(syncRow);
            playbackRow.addView(syncRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
    private void shrinkLabel(View row, float sp) {
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 1 && ((LinearLayout) row).getChildAt(1) instanceof TextView)
            ((TextView) ((LinearLayout) row).getChildAt(1)).setTextSize(sp);
    }
    /** Two checkboxes side by side: a size that keeps each label on one line. */
    private void compact(View row) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout r = (LinearLayout) row;
        for (int i = 0; i < r.getChildCount(); i++) if (r.getChildAt(i) instanceof TextView) {
            TextView t = (TextView) r.getChildAt(i);
            t.setTextSize(i == 0 ? 18 : 13.5f); t.setMaxLines(1);
            if (i == 0) t.setPadding(0, 0, dp(6), 0);
        }
    }
    /** Nearby screens this phone may not control yet, and the way to pair one: scan its code. */
    private void addPairRows(LinearLayout col, RemoteService svc) {
        TextView pair = new TextView(this);
        pair.setText("＋  Pair a screen");
        pair.setTextColor(ACCENT); pair.setTextSize(15); pair.setPadding(dp(16), dp(11), dp(16), dp(11));
        pair.setOnClickListener(v -> scanPairingCode());
        col.addView(pair);
        // Under it, screens nearby that this phone isn't paired with (new, or a pairing that was lost):
        // a shortcut to the same scanner.
        for (java.util.Map.Entry<String, String> e : svc.unpairedNearby().entrySet()) {
            TextView t = new TextView(this);
            t.setText(e.getValue() + "  ·  not paired");
            t.setTextColor(SUB); t.setTextSize(14); t.setPadding(dp(28), dp(7), dp(16), dp(9));
            t.setOnClickListener(v -> { if (targetPopup != null) targetPopup.dismiss(); scanPairingCode(); });
            col.addView(t);
        }
        // Last in the menu: closing or leaving the app only minimizes it; this is the real off switch.
        View sep = new View(this); sep.setBackgroundColor(0x22FFFFFF);
        col.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        col.addView(menuCell("Shut down BackSeeTV", WARN, () -> confirmShutdown(this)));
    }
    /** Pairing needs a way to reach the screen afterwards: Bluetooth (always), or shared Wi-Fi. Ask for
        Bluetooth first (apps can't switch radios on themselves, Android shows its own prompt); if that's
        declined, Wi-Fi will do; with both off, say so and offer the Wi-Fi switch. */
    private void scanPairingCode() {
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad != null && !ad.isEnabled()) {
            try { startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 44); return; }
            catch (Exception e) { /* no permission to ask: fall through to the Wi-Fi check */ }
        }
        openPairingScanner(ad != null && ad.isEnabled());
    }
    private void openPairingScanner(boolean btOn) {
        if (!btOn && app.anancy.net.HotspotService.wifiSwitchedOff(this)) {
            new AlertDialog.Builder(this).setTitle("Turn on Bluetooth or Wi-Fi")
                    .setMessage("This phone reaches the screen over Bluetooth, or over Wi-Fi when they share a network. "
                            + "With both off, it can pair but can't connect.")
                    .setPositiveButton("Wi-Fi settings", (d, w) -> {
                        try { startActivity(new Intent(android.provider.Settings.Panel.ACTION_WIFI)); }
                        catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); }
                    })
                    .setNeutralButton("Pair anyway", (d, w) -> launchPairingScanner())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        launchPairingScanner();
    }
    private void launchPairingScanner() {
        toast("Scan the code on the TV: grown-up unlock, Settings, Pair a remote");
        try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) { toast("Couldn't open the scanner"); }
    }
    private void refreshTargets(LinearLayout col, RemoteService svc) {
        buildTargetRows(col, svc);
    }
    static final String[][] CONN_MODES = {
            {"auto", "Automatic (recommended)", "Bluetooth, plus Wi-Fi when something needs it."},
            {"ble", "Bluetooth only", "Remote control only, works anywhere. Synced playback needs Wi-Fi."},
            {"tv_hotspot", "TV hotspot", "The TV makes a Wi-Fi network; remotes join it."},
            {"remote_hotspot", "Remote hotspot", "This phone makes a Wi-Fi network; the TV joins it."},
            {"shared", "Shared Wi-Fi", "Everything on the same Wi-Fi, e.g. at home."}};
    private volatile String tvReach = "checking…";
    private void openConnection() {
        if (connView != null) return;
        connView = overlay("Connection");
        LinearLayout col = colOf(connView);
        clearRows(col, 1);
        connStatus = ui.noteText(""); connStatus.setTextColor(TXT); connStatus.setTextSize(13); col.addView(connStatus);
        connModes = new LinearLayout(this); connModes.setOrientation(LinearLayout.VERTICAL); col.addView(connModes);
        connActions = new LinearLayout(this); connActions.setOrientation(LinearLayout.VERTICAL); col.addView(connActions);
        if (!local) { connPaired = new LinearLayout(this); connPaired.setOrientation(LinearLayout.VERTICAL); col.addView(connPaired); renderPaired(); }
        RemoteService svc = RemoteService.get();
        if (!local && svc != null) {   // whether the app follows this phone's own Bluetooth / Wi-Fi switches
            col.addView(ui.noteText("This phone's radios"));
            connRadios = new LinearLayout(this); connRadios.setOrientation(LinearLayout.VERTICAL); col.addView(connRadios);
            renderRadios();
        }
        Button back = ui.rowBtn("Back", TXT); back.setOnClickListener(v -> closeConnection()); col.addView(back);
        if (svc != null) lastConn = svc.connMode();
        renderConnStatus(); renderConnModes(); renderConnActions();
        if (svc != null) svc.requestConn();
        probeTv();
    }
    private LinearLayout connPaired, connRadios;
    private void renderRadios() {
        RemoteService svc = RemoteService.get();
        if (connRadios == null || svc == null) return;
        connRadios.removeAllViews();
        final boolean bt = svc.followsBtToggle(), wf = svc.followsWifiToggle();
        connRadios.addView(ui.checkRow("Switching Bluetooth off also cuts the remote's open Bluetooth links", bt, () -> { svc.setFollowsBtToggle(!bt); renderRadios(); }));
        connRadios.addView(ui.checkRow("Switching Wi-Fi off also stops the remote's own hotspot", wf, () -> { svc.setFollowsWifiToggle(!wf); renderRadios(); }));
        connRadios.addView(ui.noteText("Android can leave a link that's already open running after you switch a radio off "
                + "(Bluetooth scanning, the app's own hotspot). Ticked, BackSeeTV closes them too. Unticked, an open link carries on, "
                + "but no new connection can start until Bluetooth is back on."));
    }
    /** The screens this phone may control, each with Forget. */
    private void renderPaired() {
        if (connPaired == null) return;
        connPaired.removeAllViews();
        java.util.Map<String, String> paired = app.anancy.Pairing.paired(this);
        TextView hdr = ui.noteText(paired.isEmpty() ? "Paired screens: none yet." : "Paired screens");
        connPaired.addView(hdr);
        for (final java.util.Map.Entry<String, String> e : paired.entrySet()) {
            Button b = ui.rowBtn(e.getValue() + "   ·   Forget", TXT);
            b.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Forget " + e.getValue() + "?")
                    .setMessage("This phone stops controlling it until you scan its pairing code again.")
                    .setPositiveButton("Forget", (d, w) -> { app.anancy.Pairing.forget(this, e.getKey()); renderPaired(); })
                    .setNegativeButton("Cancel", null).show());
            connPaired.addView(b);
        }
        Button add = ui.rowBtn("Pair a screen", ACCENT);
        add.setOnClickListener(v -> scanPairingCode());
        connPaired.addView(add);
    }
    private void probeTv() {   // is the TV actually reachable over Wi-Fi from here?
        new Thread(() -> {
            String ip = tvIp(), r;
            if (ip.isEmpty()) r = "unknown (no IP from the TV yet)";
            else {
                java.net.HttpURLConnection c = null;
                try { c = openOnWifi("http://" + ip + ":8090/state"); c.setConnectTimeout(1500); c.setReadTimeout(1500); r = app.anancy.Pairing.fetch(this, c) != null ? "reachable at " + ip : "not reachable (" + ip + ")"; }
                catch (Exception e) { r = "not reachable (" + ip + ")"; }
                finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
            }
            tvReach = r; main.post(this::renderConnStatus);
        }).start();
    }
    private void renderConnStatus() {
        if (connStatus == null) return;
        RemoteService svc = RemoteService.get();
        StringBuilder sb = new StringBuilder();
        if (local) {
            sb.append("Hotspot:  ").append(hsSsid != null ? hsSsid : "off");
            connStatus.setText(sb.toString()); return;
        }
        sb.append("Bluetooth:  ").append(svc != null && svc.bleConnected() ? "connected" : "not connected").append('\n');
        sb.append("Wi-Fi to TV:  ").append(tvReach).append('\n');
        String mine = HotspotService.running() ? HotspotService.currentSsid() : null;
        sb.append("Hotspot:  ").append(mine != null ? mine + " (this phone)" : hsSsid != null ? hsSsid + " (TV)" : "off").append('\n');
        if (app.anancy.net.WifiRadio.carProjecting(this)) sb.append("Android Auto:  connected\n");
        // A property of the phone, not a setting: say it, so the behaviour doesn't look arbitrary.
        sb.append("This phone:  ").append(app.anancy.net.WifiRadio.describe(this));
        connStatus.setText(sb.toString().trim());
    }
    /** Screen selection now lives in the header dropdown (checkbox multi-select), not here. */
    private void renderTablets() { }

    private void renderConnModes() {
        if (connModes == null) return;
        connModes.removeAllViews();
        renderTablets();
        for (final String[] m : CONN_MODES) {
            boolean on = m[0].equals(lastConn);
            Button b = ui.rowBtn((on ? "●  " : "○  ") + m[1], on ? 0xFF8FD694 : TXT);
            b.setOnClickListener(v -> setConnMode(m[0]));
            connModes.addView(b);
            connModes.addView(ui.noteText(m[2]));
        }
    }
    private void setConnMode(String mode) {
        lastConn = mode;
        Cfg.put(this, "anancy_conn", mode);
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.setConnMode(mode); else send("conn:" + mode);
        renderConnModes(); renderConnActions();
        toast("Connection level: " + mode.replace('_', ' '));
        main.postDelayed(this::probeTv, 4000);
    }
    private void renderConnActions() {
        if (connActions == null) return;
        connActions.removeAllViews();
        switch (lastConn) {
            case "tv_hotspot":
                if (local) {
                    Button qr = ui.rowBtn("Show hotspot QR for a remote to scan", ACCENT);
                    qr.setOnClickListener(v -> showWifiQr(HotspotService.currentSsid(), HotspotService.currentPass(), "Scan with the phone remote to join the TV"));
                    connActions.addView(qr);
                } else {
                    Button join = ui.rowBtn(hsSsid != null ? "Join TV hotspot  ·  " + hsSsid : "Join TV hotspot  (waiting for the TV to publish it)", ACCENT);
                    join.setOnClickListener(v -> {
                        if (hsSsid == null) { toast("The TV hasn't published a hotspot yet — is it on this level?"); return; }
                        WifiJoiner.Creds c = new WifiJoiner.Creds(); c.ssid = hsSsid; c.type = "WPA"; c.pass = hsPass;
                        // joinDirect binds our sockets to the TV's hotspot even though it has no internet;
                        // the old suggestion-only path left Android free to ignore it entirely.
                        boolean asked = WifiJoiner.joinDirect(this, c,
                                () -> main.post(() -> { toast("Joined " + c.ssid); probeTv(); }),
                                () -> main.post(() -> toast("Couldn't join " + c.ssid)));
                        if (!asked) toast(WifiJoiner.join(this, c) ? "Asked Android to join " + hsSsid + "…" : "Couldn't request the join");
                        main.postDelayed(this::probeTv, 6000);
                    });
                    connActions.addView(join);
                    Button scan = ui.rowBtn("Scan the TV's QR instead", TXT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                }
                break;
            case "remote_hotspot":
                if (local) {
                    Button scan = ui.rowBtn("Scan the remote's hotspot QR", ACCENT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                } else {
                    boolean up = HotspotService.running() && HotspotService.currentSsid() != null;
                    Button host = ui.rowBtn(up ? "Hotspot on  ·  " + HotspotService.currentSsid() : "Host hotspot for the TV", ACCENT);
                    host.setOnClickListener(v -> { if (up) toast("Hotspot is running"); else startOwnHotspot(); });
                    connActions.addView(host);
                    if (up) {
                        Button push = ui.rowBtn("Send Wi-Fi details to the TV (Bluetooth)", TXT);
                        push.setOnClickListener(v -> { RemoteService s = RemoteService.get(); if (s != null) s.pushOwnHotspot(); toast("Sent — the TV joins in a few seconds"); main.postDelayed(this::probeTv, 8000); });
                        connActions.addView(push);
                        Button qr = ui.rowBtn("Show QR for the TV to scan", TXT);
                        qr.setOnClickListener(v -> showWifiQr(HotspotService.currentSsid(), HotspotService.currentPass(), "On the TV: Settings → Connection → Scan"));
                        connActions.addView(qr);
                        Button stop = ui.rowBtn("Stop hotspot", TXT);
                        stop.setOnClickListener(v -> { HotspotService.stop(this); main.postDelayed(() -> { renderConnActions(); renderConnStatus(); }, 800); });
                        connActions.addView(stop);
                    }
                }
                break;
            case "shared":
                if (local) {
                    Button scan = ui.rowBtn("Scan a Wi-Fi QR", ACCENT);
                    scan.setOnClickListener(v -> { try { startActivityForResult(new Intent(this, QrScanActivity.class), 43); } catch (Exception e) {} });
                    connActions.addView(scan);
                } else {
                    final EditText ssid = ui.field("Wi-Fi name (SSID)", false), pass = ui.field("Password", true);
                    connActions.addView(ssid); connActions.addView(pass);
                    Button sendBtn = ui.rowBtn("Send to TV over Bluetooth", ACCENT);
                    sendBtn.setOnClickListener(v -> {
                        String s = ssid.getText().toString().trim(), p = pass.getText().toString();
                        if (s.isEmpty()) { toast("Enter the Wi-Fi name"); return; }
                        send("wifi:" + s + "\t" + p); toast("Sent — the TV joins in a few seconds"); main.postDelayed(this::probeTv, 8000);
                    });
                    connActions.addView(sendBtn);
                    Button qr = ui.rowBtn("Show as QR for the TV to scan instead", TXT);
                    qr.setOnClickListener(v -> { String s = ssid.getText().toString().trim(); if (s.isEmpty()) { toast("Enter the Wi-Fi name"); return; } showWifiQr(s, pass.getText().toString(), "On the TV: Settings → Connection → Scan"); });
                    connActions.addView(qr);
                }
                break;
            default: {   // ble — real status + a jump to the system Bluetooth settings (the app can't toggle BT itself)
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                android.bluetooth.BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
                boolean btOn = ad != null && ad.isEnabled();
                RemoteService svc = RemoteService.get();
                boolean linked = svc != null && svc.bleConnected();
                Button bt = ui.rowBtn(btOn ? "Open Bluetooth settings" : "Turn Bluetooth on (system settings)", btOn ? TXT : ACCENT);
                bt.setOnClickListener(v -> { try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception e) { toast("Couldn't open Bluetooth settings"); } });
                connActions.addView(bt);
                if (!linked) {
                    Button chk = ui.rowBtn("Run the set-up check", ACCENT);
                    chk.setOnClickListener(v -> startActivity(new Intent(this, app.anancy.SetupActivity.class)));
                    connActions.addView(chk);
                }
            }
        }
    }
    private void showWifiQr(String ssid, String pass, String caption) {
        if (ssid == null) { toast("No hotspot running yet"); return; }
        startActivity(new Intent(this, QrShareActivity.class)
                .putExtra(QrShareActivity.EXTRA_PAYLOAD, "WIFI:S:" + qrEsc(ssid) + ";T:WPA;P:" + qrEsc(pass == null ? "" : pass) + ";;")
                .putExtra(QrShareActivity.EXTRA_CAPTION, caption));
    }
    private static String qrEsc(String s) { return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\""); }
    private void startOwnHotspot() {
        // a LocalOnlyHotspot needs the nearby-devices permission (13+) / location (older) on a non-owner phone
        String perm = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : "android.permission.ACCESS_FINE_LOCATION";
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{perm}, 44); return; }
        HotspotService.start(this);
        toast("Starting hotspot…");
        main.postDelayed(() -> { renderConnActions(); renderConnStatus(); RemoteService s = RemoteService.get(); if (s != null) s.pushOwnHotspot(); }, 3000);
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != 44) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startOwnHotspot();
        else toast("The hotspot needs the Nearby devices permission");
    }
    private void closeConnection() { if (connView != null) { frame.removeView(connView); connView = null; connStatus = null; connModes = null; connActions = null; connPaired = null; connRadios = null; } }

    // =====================================================================================
    //  Settings — every TV option, mirrored from the phone (reads the TV's /config, writes cfg:K=V).
    //  Device-owner-only rows are greyed with a "requires device owner" notice on a base-level TV.
    // =====================================================================================
    /** Settings > Shut down: stops every link and the hotspot, then closes the app. Just leaving the app
        only minimizes it (a remote sits idle while the TV plays, and that's fine). */
    static void confirmShutdown(android.app.Activity a) {
        new android.app.AlertDialog.Builder(a).setTitle("Shut down BackSeeTV?")
                .setMessage("Stops this phone's Bluetooth links, its hotspot and a synced group, and closes the app. "
                        + "The screens keep playing on their own. Opening the app starts it all again.")
                .setPositiveButton("Shut down", (d, w) -> {
                    RemoteService svc = RemoteService.get();
                    if (svc != null) svc.shutdown();
                    else { try { app.anancy.net.HotspotService.stop(a); } catch (Exception ignored) {} }
                    a.finishAffinity();
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void openSettings() {
        if (settingsView != null) return;
        { RemoteService s0 = RemoteService.get();
          if (local || (s0 != null && s0.showingSelf())) { startActivity(new Intent(this, SettingsActivity.class)); return; } }   // on the TV: the real screen
        settingsView = overlay("Settings");
        cfgVals = null;
        RemoteService svc = RemoteService.get();
        if (svc == null || svc.tablets().isEmpty()) {   // nothing to read from: say so now, not after 6s of "loading"
            LinearLayout col = colOf(settingsView); clearRows(col, 1);
            noTvSettings(col, "No TV connected, so there are no TV settings to show yet.");
            return;
        }
        if (svc != null) svc.requestConfig();
        loadConfig(0);
    }
    private void loadConfig(final int attempt) {
        if (settingsView == null) return;
        RemoteService svc = RemoteService.get();
        String json = svc != null ? svc.configJson() : null;
        if (json != null) { applyConfigJson(json); renderSettings(); return; }
        if (attempt < 15) main.postDelayed(() -> loadConfig(attempt + 1), 400);
        else {
            LinearLayout col = colOf(settingsView); clearRows(col, 1);
            noTvSettings(col, "Couldn't read the TV's settings — is it connected?");
        }
    }
    /** Settings with no TV to read from used to be a dead end. These are THIS device's own options, so they
        must always be reachable: the set-up check (which also changes the role) and the Connection page. */
    private void noTvSettings(LinearLayout col, String why) {
        col.addView(ui.noteText(why));
        Button conn = ui.rowBtn("Connection… (find a TV)", ACCENT);
        conn.setOnClickListener(v -> { closeSettings(); openConnection(); }); col.addView(conn);
        Button chk = ui.rowBtn("This phone: set-up check", TXT);
        chk.setOnClickListener(v -> startActivity(new Intent(this, app.anancy.SetupActivity.class))); col.addView(chk);
        Button about = ui.rowBtn("About BackSeeTV & feature levels", TXT);
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class))); col.addView(about);
        Button b = ui.rowBtn("Back", TXT); b.setOnClickListener(v -> closeSettings()); col.addView(b);
    }
    private void applyConfigJson(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            cfgOwner = o.optBoolean("device_owner", false);
            cfgPinSet = o.optBoolean("pin_set", false);
            cfgVals = o.optJSONObject("values"); if (cfgVals == null) cfgVals = new org.json.JSONObject();
        } catch (Exception e) { cfgVals = new org.json.JSONObject(); }
    }
    private int cfgI(String k, int d) { return cfgVals != null ? cfgVals.optInt(k, d) : d; }
    private String cfgS(String k, String d) { return cfgVals != null ? cfgVals.optString(k, d) : d; }
    private void setCfg(String k, String val) {
        RemoteService svc = RemoteService.get(); if (svc != null) svc.setConfig(k, val);
        try { if (cfgVals != null) cfgVals.put(k, val.matches("-?\\d+") ? (Object) Integer.valueOf(val) : val); } catch (Exception ignored) {}
    }
    private TextView settingsTitle;

    /** The settings-page half of the main page's dropdown: pick which screen you are configuring,
        or step out to this remote's OWN settings — which are a different thing entirely, since a
        non-owner phone can't hold the kiosk options at all. */
    private void showSettingsTargetMenu() {
        final RemoteService svc = RemoteService.get();
        if (svc == null) return;
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(6));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), 0x33FFFFFF);
        MaxScroll sv = new MaxScroll(this, (int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
        sv.setBackground(bg); sv.setClipToOutline(true); sv.addView(col);
        final android.widget.PopupWindow pop = new android.widget.PopupWindow(sv, dp(260), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setElevation(dp(12));

        TextView hdr = new TextView(this); hdr.setText("Settings for"); hdr.setTextColor(SUB); hdr.setTextSize(11);
        hdr.setPadding(dp(16), dp(8), dp(16), dp(8)); col.addView(hdr);
        if (svc.tablets().isEmpty()) {
            TextView none = new TextView(this); none.setText("No screens found.");
            none.setTextColor(SUB); none.setTextSize(13); none.setPadding(dp(16), dp(2), dp(16), dp(10)); col.addView(none);
        }
        for (final java.util.Map.Entry<String, String> e : new java.util.ArrayList<>(svc.tablets().entrySet())) {
            final boolean on = svc.targets().isEmpty() || svc.targets().contains(e.getKey());
            col.addView(menuCell((on ? "●  " : "○  ") + e.getValue(), on ? GOOD : TXT, () -> {
                svc.setTargets(new java.util.LinkedHashSet<>(java.util.Collections.singletonList(e.getKey())));
                cfgVals = null; svc.requestConfig(); loadConfig(0);
            }));
        }
        ui.hSep(col);
        col.addView(menuCell("This remote's own settings  ›", ACCENT,
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        // menuCell dismisses the shared targetPopup; this one is its own window, so close it too.
        pop.showAsDropDown(settingsTitle != null ? settingsTitle : titleDot, 0, dp(6), Gravity.START);
        settingsPopup = pop;
    }
    private android.widget.PopupWindow settingsPopup;

    /** Which screens these settings get written to, named. "All screens" is resolved to the actual
        names: a list of one is still worth printing, because the whole confusion was not knowing. */
    private String settingsTargetNames() {
        RemoteService svc = RemoteService.get();
        if (svc == null) return "—";
        java.util.Set<String> sel = svc.targets();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : svc.tablets().entrySet())
            if (sel.isEmpty() || sel.contains(e.getKey())) names.add(e.getValue());
        if (names.isEmpty()) return "no screen connected";
        return android.text.TextUtils.join(", ", names);
    }

    private void renderSettings() {
        if (settingsView == null) return;
        LinearLayout col = colOf(settingsView); clearRows(col, 1);
        // The title IS the device picker, so nothing here has to restate whose settings these are.
        settingsTitle = overlayTitleMenu(settingsView, settingsTargetNames() + " settings", this::showSettingsTargetMenu);
        // Only worth a line when it changes what you can DO: a padlock on a screen that isn't set up
        // as a kiosk. Device-owner options belong to the SCREEN, never to the remote holding it.

        sSec(col, "Library");
        // The only rescan in the app used to be the one after an upload, so media copied onto the
        // tablet by hand never appeared -- and you cannot force-stop a LOCKED kiosk to rebuild it.
        { Button b = setRow(col, "Media folders"); b.setText("rescan now");
          b.setOnClickListener(v -> { send("rescan"); toast("Rescanning the library…"); }); }

        sSec(col, "Playback");
        cfgIntCycle(col, "Light duration (seconds)", "anancy_flash_ms", new int[]{1000, 2000, 3000, 5000, 10000}, 1000);
        { final boolean loop = cfgI("anancy_loop", 1) == 1;
          Button b = setRow(col, "When the queue ends"); b.setText(loop ? "loop forever" : "stop (TV off)");
          b.setOnClickListener(v -> { setCfg("anancy_loop", cfgI("anancy_loop", 1) == 1 ? "0" : "1"); renderSettings(); }); }
        { final boolean lk = cfgI("anancy_lock_volkeys", 0) == 1;
          Button b = setRow(col, "Lock the TV's volume buttons"); b.setText(lk ? "☑ locked" : "☐ off");
          b.setOnClickListener(v -> { setCfg("anancy_lock_volkeys", lk ? "0" : "1"); renderSettings(); }); }

        sSec(col, "Connection");
        { String cur = cfgS("anancy_conn", "ble"); String label = cur;
          for (String[] m : CONN_MODES) if (m[0].equals(cur)) label = m[1];
          Button b = setRow(col, "Connection level"); b.setText(label); b.setTextColor(GOOD);
          b.setOnClickListener(v -> { closeSettings(); openConnection(); }); }   // one home for it: the Connection page

        sSec(col, "Toddler play mode");
        cfgStrCycle(col, "Sound", "anancy_playmode_sound", new String[]{"muted", "wav", "video"}, new String[]{"Muted", "Letter sounds", "Video sound"});

        sSec(col, "Unlock gesture");
        // a non-owner screen can pin itself (Lock below), so its way out matters as much
        cfgIntCycle(col, "Fingers", "anancy_esc_fingers", new int[]{2, 3, 4, 5}, 1);
        cfgStrCycle(col, "Type", "anancy_esc_mode", new String[]{"taps", "swipe"}, new String[]{"taps", "swipe"});
        cfgIntCycle(col, "Taps needed", "anancy_esc_taps", new int[]{2, 3, 4, 5, 6}, 1);
        cfgIntCycle(col, "Within (seconds)", "anancy_esc_window_ms", new int[]{2000, 3000, 5000, 8000}, 1000);
        cfgStrCycle(col, "Swipe direction", "anancy_esc_dir", new String[]{"up", "down", "left", "right"}, new String[]{"up", "down", "left", "right"});

        sSec(col, "Lock");
        // How the adult unlocks (to the menu / settings / exit). Randomized PIN = a timed code shown on
        // screen you copy (nothing to remember); Preset PIN = your own Admin PIN (not shown); Pattern =
        // a set pattern (drawn on the TV). Vary it to keep a clever toddler guessing.
        final String[] uv = {"challenge", "pin", "pattern"}, ul = {"Randomized PIN", "Preset PIN", "Pattern"};
        final String style = cfgS("anancy_unlock_style", "challenge");
        Button mrow = setRow(col, "Method"); mrow.setText(sLabel(style, uv, ul));
        mrow.setOnClickListener(v -> {
            String cur = cfgS("anancy_unlock_style", "challenge"), nx = uv[0];
            for (int i = 0; i < uv.length; i++) if (uv[i].equals(cur)) { nx = uv[(i + 1) % uv.length]; break; }
            setCfg("anancy_unlock_style", nx); renderSettings();   // re-render so the sub-settings show/hide
        });
        if ("challenge".equals(style)) {   // shown-on-screen random code -> expose its parameters
            cfgIntCycle(col, "PIN length", "anancy_chal_len", new int[]{4, 5, 6, 7, 8}, 1);
            cfgIntCycle(col, "Time to enter (s)", "anancy_chal_secs", new int[]{15, 30, 45, 60}, 1);
            cfgIntCycle(col, "Attempts", "anancy_chal_tries", new int[]{1, 2, 3, 5}, 1);
        }
        Button pin = setRow(col, "Admin PIN"); pin.setText(cfgPinSet ? "change" : "set"); pin.setOnClickListener(v -> changeTvPin());
        final boolean k = cfgI("anancy_kiosk", 0) == 1;
        Button kb = setRow(col, cfgOwner ? "Kiosk lock" : "Toddler lock"); kb.setText(k ? "on" : "off");
        kb.setOnClickListener(v -> { setCfg("anancy_kiosk", cfgI("anancy_kiosk", 0) == 1 ? "0" : "1"); renderSettings(); });

        Button setup = ui.rowBtn("This phone: set-up check", TXT);   // includes Change role
        setup.setOnClickListener(v -> startActivity(new Intent(this, app.anancy.SetupActivity.class))); col.addView(setup);
        Button about = ui.rowBtn("About BackSeeTV & feature levels", ACCENT); about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class))); col.addView(about);
        Button back = ui.rowBtn("Back", TXT); back.setOnClickListener(v -> closeSettings()); col.addView(back);
    }
    private void closeSettings() { if (settingsView != null) { frame.removeView(settingsView); settingsView = null; } }

    private void changeTvPin() {
        final EditText a = ui.field("New PIN (4+ digits)", true), b2 = ui.field("Repeat new PIN", true);
        a.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        b2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(a); box.addView(b2);
        new AlertDialog.Builder(this).setTitle("Change TV admin PIN").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = a.getText().toString().trim();
                    if (!p.matches("\\d{4,}")) { toast("PIN must be at least 4 digits"); return; }
                    if (!p.equals(b2.getText().toString().trim())) { toast("PINs don't match"); return; }
                    setCfg("anancy_pin", p); cfgPinSet = true; toast("PIN changed"); renderSettings();
                }).setNegativeButton("Cancel", null).show();
    }

    // --- settings-overlay row helpers ---
    private void sSec(LinearLayout col, String t) {
        TextView s = new TextView(this); s.setText(t); s.setTextColor(GOOD); s.setTextSize(15);
        s.setPadding(0, dp(16), 0, dp(6)); col.addView(s);
    }
    private Button setRow(LinearLayout col, String label) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(12)); row.setBackground(g);
        row.setPadding(dp(14), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(15);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button v = new Button(this); v.setAllCaps(false); v.setTextSize(14); v.setMinWidth(dp(108)); v.setTextColor(TXT);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(BTN); bg.setCornerRadius(dp(10)); v.setBackground(bg);
        row.addView(v); col.addView(row);
        return v;
    }
    private void cfgIntCycle(LinearLayout col, String label, final String key, final int[] opts, final int div) {
        final Button v = setRow(col, label);
        v.setText(String.valueOf(cfgI(key, opts[0]) / div));
        v.setOnClickListener(x -> {
            int cur = cfgI(key, opts[0]), nx = opts[0];
            for (int i = 0; i < opts.length; i++) if (opts[i] == cur) { nx = opts[(i + 1) % opts.length]; break; }
            setCfg(key, String.valueOf(nx)); v.setText(String.valueOf(nx / div));
        });
    }
    private void cfgStrCycle(LinearLayout col, String label, final String key, final String[] vals, final String[] labels) {
        final Button v = setRow(col, label);
        v.setText(sLabel(cfgS(key, vals[0]), vals, labels));
        v.setOnClickListener(x -> {
            String cur = cfgS(key, vals[0]), nx = vals[0];
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) { nx = vals[(i + 1) % vals.length]; break; }
            setCfg(key, nx); v.setText(sLabel(nx, vals, labels));
        });
    }
    private static String sLabel(String v, String[] vals, String[] labels) { for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return labels[i]; return v; }

    // --- check-in: STILL IMAGE over BLE (<1fps, "not a feed") OR LIVE view over Wi-Fi (/live MJPEG) ---
    private volatile boolean liveUp = false;   // the MJPEG loop has taken over from the Bluetooth stills

    private void openStill() {
        if (still != null) return;
        boolean wifi = local && !tvIp().isEmpty();   // the TV's own remote: loopback, live at once
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView h = new TextView(this);
        final TextView head = h;
        h.setText(wifi ? "BackSee Glance" : "BackSee Glance — still image, refreshes about once a second");
        h.setTextColor(SUB); h.setTextSize(13); h.setGravity(Gravity.CENTER);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.MATRIX);   // we drive the viewport (digital PTZ)
        img.setBackgroundColor(0xFF000000);
        col.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupPtz(img);   // pinch to zoom (1-4x), single-finger drag to pan/tilt

        final TextView status = new TextView(this);
        status.setText("finding the TV…"); status.setTextColor(SUB); status.setTextSize(12);
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(10), 0, dp(10));
        col.addView(status);

        Button close = new Button(this);
        close.setText("Close"); close.setAllCaps(false);
        close.setOnClickListener(v -> closeStill());
        col.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        still = col; frame.addView(still);
        stillOn = true; liveUp = false;
        if (wifi) { send("snap:on"); startLive(tvIp() + ":8090", img, status); return; }
        // Live view is the whole reason the Wi-Fi tier exists, so ask for it -- but show a Bluetooth
        // still IMMEDIATELY rather than staring at a black rectangle while the link comes up, and
        // swap to live the moment the TV answers over Wi-Fi.
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.acquireWifi("live");
        send("camble:on"); startBleStills(img, status);
        startLiveWhenReady(img, status, head);
    }

    /** Wait for the Wi-Fi link to actually carry, then upgrade the stills to the MJPEG live view. */
    private void startLiveWhenReady(final ImageView img, final TextView status, final TextView head) {
        new Thread(() -> {
            while (stillOn && !liveUp) {
                String ip = tvIp();
                if (!ip.isEmpty() && reachable(ip)) {
                    liveUp = true;                       // ends the Bluetooth still loop
                    send("camble:off"); send("snap:on");   // drop the BLE push, keep the camera running
                    main.post(() -> { head.setText("BackSee Glance"); status.setText("switching to Wi-Fi…"); });
                    startLive(ip + ":8090", img, status);
                    return;
                }
                try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    private boolean reachable(String ip) {
        java.net.HttpURLConnection c = null;
        try { c = openOnWifi("http://" + ip + ":8090/state"); c.setConnectTimeout(1200); c.setReadTimeout(1200); return app.anancy.Pairing.fetch(this, c) != null; }
        catch (Exception e) { return false; }
        finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
    }

    // Wi-Fi: parse the TV's /live MJPEG (multipart/x-mixed-replace) and show frames as they arrive.
    private void startLive(final String host, final ImageView img, final TextView status) {
        new Thread(() -> {
            int fails = 0;
            while (stillOn) {
                java.net.HttpURLConnection c = null;
                try {
                    c = openOnWifi("http://" + host + "/live");
                    c.setConnectTimeout(2500); c.setReadTimeout(6000);
                    final byte[] sk = app.anancy.Pairing.streamKey(this, c);   // frames are sealed for this request
                    if (sk == null) throw new Exception("not paired with that TV");
                    if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                    java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 1 << 16);
                    fails = 0;
                    while (stillOn) {
                        int len = -1; String line;
                        while ((line = readLine(in)) != null) {
                            if (line.isEmpty()) { if (len >= 0) break; else continue; }
                            if (line.toLowerCase().startsWith("content-length:"))
                                try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignore) {}
                        }
                        if (line == null) break;                      // stream ended
                        if (len <= 0 || len > 4_000_000) continue;
                        byte[] sealed = new byte[len]; int r = 0, k;
                        while (r < len && (k = in.read(sealed, r, len - r)) >= 0) r += k;
                        if (r != len) break;
                        byte[] jpg = app.anancy.net.PairCrypto.openBytes(sk, sealed);
                        if (jpg == null) continue;   // not from our TV, or damaged: never shown
                        final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                        if (bmp != null) main.post(() -> { showFrame(img, bmp); status.setText(""); });
                    }
                } catch (Exception e) {
                    if (++fails >= 3) {   // give up on live -> fall back to BLE stills
                        main.post(() -> status.setText("no Wi-Fi — showing stills over Bluetooth"));
                        liveUp = false; send("camble:on"); startBleStills(img, status); return;
                    }
                    main.post(() -> status.setText("connecting live…"));
                } finally { try { if (c != null) c.disconnect(); } catch (Exception ignore) {} }
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    // BLE: the TV pushes chunked low-res stills; RemoteService reassembles them, we show the latest.
    private void startBleStills(final ImageView img, final TextView status) {
        new Thread(() -> {
            byte[] lastShown = null;
            while (stillOn && !liveUp) {
                byte[] jpg = RemoteService.latestStill();
                if (jpg != null && jpg != lastShown) {
                    lastShown = jpg;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (bmp != null) main.post(() -> { showFrame(img, bmp); status.setText(""); });
                } else if (jpg == null) main.post(() -> status.setText("waiting for a still over Bluetooth…"));
                try { Thread.sleep(700); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    // --- digital PTZ: a client-side viewport over whatever frames arrive (still or live) ------
    private void setupPtz(final ImageView img) {
        ptzZoom = 1f; ptzCx = 0.5f; ptzCy = 0.5f; ptzBmp = null;   // reset to 1x centered on open
        ptzScale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                ptzZoom = Math.max(1f, Math.min(4f, ptzZoom * d.getScaleFactor()));   // pinch: 1-4x
                applyViewport(img); return true;
            }
        });
        final float[] last = new float[2]; final boolean[] panning = { false };
        img.setOnTouchListener((v, ev) -> {
            ptzScale.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last[0] = ev.getX(); last[1] = ev.getY(); panning[0] = true; break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    panning[0] = false; break;   // a second finger = pinch, not pan
                case MotionEvent.ACTION_MOVE:
                    if (panning[0] && ev.getPointerCount() == 1 && !ptzScale.isInProgress() && ptzBmp != null) {
                        float base = Math.min((float) v.getWidth() / ptzBmp.getWidth(), (float) v.getHeight() / ptzBmp.getHeight());
                        float s = base * ptzZoom;
                        if (s > 0) {   // drag moves the viewport centre the opposite way (drag right -> see left)
                            ptzCx -= (ev.getX() - last[0]) / (ptzBmp.getWidth() * s);
                            ptzCy -= (ev.getY() - last[1]) / (ptzBmp.getHeight() * s);
                        }
                        last[0] = ev.getX(); last[1] = ev.getY();
                        applyViewport(img);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panning[0] = false; break;
            }
            return true;
        });
        img.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyViewport(img));   // apply once the view is sized
    }
    private void showFrame(ImageView img, Bitmap bmp) {
        ptzBmp = bmp;
        img.setImageBitmap(bmp);
        applyViewport(img);   // keep the chosen zoom/pan as frames update
    }
    /** Rebuild the image matrix from (zoom, pan-centre), clamped so you can't pan past the frame edges. */
    private void applyViewport(ImageView img) {
        Bitmap b = ptzBmp;
        if (b == null) return;
        int VW = img.getWidth(), VH = img.getHeight(), BW = b.getWidth(), BH = b.getHeight();
        if (VW == 0 || VH == 0 || BW == 0 || BH == 0) return;
        float base = Math.min((float) VW / BW, (float) VH / BH);   // FIT_CENTER baseline at 1x
        float s = base * ptzZoom;
        float halfW = (VW / (BW * s)) / 2f, halfH = (VH / (BH * s)) / 2f;   // visible half-extent, in image fractions
        ptzCx = halfW >= 0.5f ? 0.5f : Math.max(halfW, Math.min(1f - halfW, ptzCx));   // clamp centre so the viewport stays inside the frame
        ptzCy = halfH >= 0.5f ? 0.5f : Math.max(halfH, Math.min(1f - halfH, ptzCy));
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate(VW / 2f - ptzCx * BW * s, VH / 2f - ptzCy * BH * s);
        img.setImageMatrix(m);
    }

    private void closeStill() {
        stillOn = false; liveUp = false;
        send("camble:off"); send("snap:off");   // stop the BLE push + capture
        RemoteService svc = RemoteService.get();
        if (svc != null) svc.releaseWifi("live");   // the link drops unless it is free to keep
        if (still != null) { frame.removeView(still); still = null; }
        ptzBmp = null;   // release the frame ref; PTZ resets on next open
    }
    /** Open the URL bound to the Wi-Fi network — the TV is on the LAN, but with mobile data up the
        app's default network is cellular, which can't reach a 192.168.x host. Falls back to default. */
    /** Every request to a TV is signed with that TV's pairing key; unsigned ones get a 401. */
    private java.net.HttpURLConnection openOnWifi(String urlStr) throws Exception {
        return app.anancy.Pairing.sign(this, openOnWifiRaw(urlStr));
    }
    private java.net.HttpURLConnection openOnWifiRaw(String urlStr) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        if (local) return (java.net.HttpURLConnection) url.openConnection();   // loopback: never bind to the Wi-Fi network
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            // When WE host the link the TV sits on our soft-AP interface (swlan0), which is NOT a
            // Network we can bind to -- and binding to the Wi-Fi we are a CLIENT of (home Wi-Fi)
            // sends the request out wlan0, where the TV's address simply does not exist. The plain
            // socket gets it right off the route table. Same once joinDirect has bound the process.
            if (HotspotService.running() || (cm != null && cm.getBoundNetworkForProcess() != null))
                return (java.net.HttpURLConnection) url.openConnection();
            if (cm != null) for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                    return (java.net.HttpURLConnection) n.openConnection(url);
            }
        } catch (Exception ignored) {}
        return (java.net.HttpURLConnection) url.openConnection();
    }

    private static String mmss(int ms) { return Fmt.mmss(ms); }   // pure impl extracted to Fmt (unit-tested)

    // --- Wi-Fi file import (phone -> tablet) --------------------------------
    private void pickImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/*");
            startActivityForResult(i, 42);
        } catch (Exception e) { Toast.makeText(this, "No file picker", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 44) { openPairingScanner(res == RESULT_OK); return; }   // after the Bluetooth prompt
        if (req == 42 && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            Toast.makeText(this, "Finding the TV…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                // A file import is bulk traffic: it cannot ride Bluetooth, so take the same Wi-Fi
                // lease the live view uses and let "auto" raise the link, rather than telling the
                // user to go and join a network by hand.
                RemoteService svc = RemoteService.get();
                if (svc != null) svc.acquireWifi("import");
                try {
                    String host = null;
                    long end = android.os.SystemClock.elapsedRealtime() + 25000;
                    while (host == null && android.os.SystemClock.elapsedRealtime() < end) {
                        String ip = tvIp();
                        if (!ip.isEmpty() && reachable(ip)) { host = ip + ":8090"; break; }
                        try { Thread.sleep(800); } catch (InterruptedException e) { break; }
                    }
                    if (host == null && svc != null) host = svc.playerHttpHost();   // mDNS sweep as a fallback
                    if (host == null) {
                        main.post(() -> Toast.makeText(this, "Couldn't reach the TV to import — is it on and nearby?", Toast.LENGTH_LONG).show());
                        return;
                    }
                    main.post(() -> Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show());
                    uploadFile(uri, host);
                } finally { if (svc != null) svc.releaseWifi("import"); }
            }).start();
        }
        if (req == 43 && res == RESULT_OK && data != null) {   // QR scan result
            if (data.getBooleanExtra(QrScanActivity.RESULT_PAIRED, false)) {
                String nm = data.getStringExtra(QrScanActivity.RESULT_TV_NAME);
                Toast.makeText(this, "Paired with " + (nm == null || nm.isEmpty() ? "the screen" : nm) + ". Connecting…", Toast.LENGTH_LONG).show();
                main.postDelayed(this::refreshAll, 3000);
                return;
            }
            String ip = data.getStringExtra(QrScanActivity.RESULT_ANANCY_IP);
            String ssid = data.getStringExtra(QrScanActivity.RESULT_JOINED_SSID);
            if (ip != null && !ip.isEmpty()) { tabletIp = ip; Toast.makeText(this, "TV at " + ip, Toast.LENGTH_SHORT).show(); }
            else if (ssid != null && !ssid.isEmpty()) Toast.makeText(this, "Joining " + ssid + "…", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Scanned", Toast.LENGTH_SHORT).show();
            if (connView != null) main.postDelayed(this::probeTv, 5000);
        }
    }
    private void uploadFile(Uri uri, String host) {
        java.net.HttpURLConnection c = null;
        try {
            String name = queryName(uri); long size = querySize(uri);
            byte[] data = null;
            if (size <= 0) {   // buffer to learn the length (server needs Content-Length)
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) bo.write(b, 0, n); in.close();
                data = bo.toByteArray(); size = data.length;
            }
            // The checksum rides in the signed query, so the TV can tell the file wasn't swapped on the way.
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            if (data != null) md.update(data);
            else { java.io.InputStream in = getContentResolver().openInputStream(uri); byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); in.close(); }
            StringBuilder sha = new StringBuilder(); for (byte x : md.digest()) sha.append(String.format("%02x", x));
            java.net.URL url = new java.net.URL("http://" + host + "/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&sha=" + sha);
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(5000);
            app.anancy.Pairing.sign(this, c);
            c.setFixedLengthStreamingMode(size);
            java.io.OutputStream out = c.getOutputStream();
            if (data != null) out.write(data);
            else { java.io.InputStream in = getContentResolver().openInputStream(uri); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); in.close(); }
            out.flush();
            final int code = c.getResponseCode(); final String nm = name;
            String why = null;
            if (code != 200) try { java.io.InputStream es = c.getErrorStream(); if (es != null) { why = new JSONObject(new java.util.Scanner(es).useDelimiter("\\A").next()).optString("error", null); } } catch (Exception ignored) {}
            final String reason = why;
            main.post(() -> Toast.makeText(this, code == 200 ? "Imported " + nm : "Import failed" + (reason != null ? ": " + reason : " (" + code + ")"), Toast.LENGTH_LONG).show());
        } catch (Exception e) { main.post(() -> Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show()); }
        finally { if (c != null) c.disconnect(); }
    }
    private String queryName(Uri uri) {
        try (Cursor cu = getContentResolver().query(uri, null, null, null, null)) {
            if (cu != null && cu.moveToFirst()) { int i = cu.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return cu.getString(i); }
        } catch (Exception ignored) {}
        return "import_" + System.currentTimeMillis() + ".mp4";
    }
    private long querySize(Uri uri) {
        try (Cursor cu = getContentResolver().query(uri, null, null, null, null)) {
            if (cu != null && cu.moveToFirst()) { int i = cu.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !cu.isNull(i)) return cu.getLong(i); }
        } catch (Exception ignored) {}
        return -1;
    }

    @Override protected void onStart() { super.onStart(); if (selfVideo != null) app.anancy.player.SelfPlayer.showIn(selfVideo); }
    @Override protected void onStop() { if (selfVideo != null) app.anancy.player.SelfPlayer.hideFrom(selfVideo); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        RemoteService.acquire(this, "ui");   // the link is on demand: held while this screen is visible
        attach();
        showTab(0);   // always land on the Remote tab
        refreshAll(); startCardPolling();   // per-screen cards + their live state
        if (local) { attachTvVideo(true); lastTouch = android.os.SystemClock.elapsedRealtime(); main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, 5_000); }
        main.removeCallbacks(uiTick); main.post(uiTick);
    }
    // The TV's own remote page sits behind the grown-up unlock. Left alone for a minute (handed back
    // to a child, say), it returns to the show, locked again, rather than staying open.
    static final long LOCAL_IDLE_MS = 60_000;
    private long lastTouch = 0;
    private final Runnable idleCheck = new Runnable() { public void run() {
        if (!local) return;
        if (android.os.SystemClock.elapsedRealtime() - lastTouch >= LOCAL_IDLE_MS) { finish(); return; }
        main.postDelayed(this, 5_000);
    }};
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        lastTouch = android.os.SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(e);
    }
    @Override protected void onPause() {
        main.removeCallbacks(idleCheck);
        if (local) attachTvVideo(false);
        main.removeCallbacks(uiTick);
        stopCardPolling();
        if (still != null) closeStill();   // never keep polling the camera still in the background
        RemoteService svc = RemoteService.get(); if (svc != null) svc.setListener(null);
        RemoteService.release(this, "ui");
        super.onPause();
    }
    @Override protected void onDestroy() { RemoteService svc = RemoteService.get(); if (svc != null) svc.setListener(null); super.onDestroy(); }

    // --- view helpers --------------------------------------------------------
    private IconView iconBtn(int mode, int bg, int icon, final String cmd) {
        IconView v = new IconView(this, mode, bg, icon);
        v.setOnClickListener(x -> send(cmd));
        ui.pressFx(v);
        return v;
    }
    private LinearLayout actionCard(int mode, int accent, String label, final String cmd) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(withAlpha(accent, 0x33)); g.setCornerRadius(dp(16));
        g.setStroke(dp(1), withAlpha(accent, 0x66));
        c.setBackground(g);
        IconView ic = new IconView(this, mode, 0x00000000, accent);
        c.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(12); l.setPadding(0, dp(4), 0, 0);
        c.addView(l);
        c.setClickable(true);
        c.setOnClickListener(x -> send(cmd));
        return c;
    }
    // Same card, but runs an arbitrary action (e.g. open the check-in view) instead of sending a command.
    private LinearLayout actionCardRun(int mode, int accent, String label, final Runnable onTap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(withAlpha(accent, 0x33)); g.setCornerRadius(dp(16));
        g.setStroke(dp(1), withAlpha(accent, 0x66)); c.setBackground(g);
        IconView ic = new IconView(this, mode, 0x00000000, accent);
        c.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView l = new TextView(this); l.setText(label); l.setTextColor(TXT); l.setTextSize(12); l.setPadding(0, dp(4), 0, 0);
        c.addView(l);
        c.setClickable(true); c.setOnClickListener(x -> onTap.run());
        return c;
    }
    private View spacer(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)); return v; }
    private LinearLayout.LayoutParams lp(int w, int h, int m) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(m, 0, m, 0); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int m, float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight); p.setMargins(m, 0, m, 0); return p; }
    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

}
