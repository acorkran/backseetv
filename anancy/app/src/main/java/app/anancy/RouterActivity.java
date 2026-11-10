package app.anancy;

import app.anancy.player.PlayerActivity;
import app.anancy.remote.RemoteActivity;

// ============================================================================
//  Launcher. One APK, two roles. First run asks "TV (player)" or "Remote";
//  the choice is remembered and every later launch routes straight through.
//  (Reset by clearing app data.) Also requests the BLE runtime perms up front.
// ============================================================================

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class RouterActivity extends Activity {
    static final String PREF = "anancy", KEY = "role";
    private Class<?> pending;   // where to route once the permission dialog closes

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean asked = requestBlePerms();

        // Provisioning a kiosk already happens from a computer (dpm set-device-owner), so the role
        // must be settable the same way instead of forcing someone to walk over and tap a tablet:
        //   adb shell am start -n app.anancy/.AdbSetRole --es role player
        // Only through that alias, which other apps can't start; this launcher entry is open to all.
        try {
            boolean fromAdb = getComponentName().getClassName().endsWith(".AdbSetRole");
            String forced = fromAdb && getIntent() != null ? getIntent().getStringExtra("role") : null;
            if ("player".equals(forced) || "remote".equals(forced)) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY, forced).apply();
                pinByDefault();
            }
        } catch (Exception ignored) {}

        String role = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY, null);
        Class<?> dest = "player".equals(role) ? PlayerActivity.class
                      : "remote".equals(role) ? RemoteActivity.class : null;
        if (dest != null) {
            // Routing out from under a LIVE permission dialog strands it: it keeps input focus,
            // invisible, above whatever we routed to, and touches stop reaching the screen. Only
            // reachable on a first run whose role was pre-set (adb --es role), where the role is
            // known but nothing is granted yet -- so wait for the answer, then route.
            if (asked) { pending = dest; setContentView(blank()); return; }
            route(dest); return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad(24), pad(24), pad(24), pad(24));
        root.setBackgroundColor(0xFF101418);

        TextView t = new TextView(this);
        t.setText("Set this device's role"); t.setTextColor(Color.WHITE); t.setTextSize(22);
        t.setGravity(Gravity.CENTER); t.setPadding(0, 0, 0, pad(24));
        root.addView(t);

        root.addView(bigBtn("This is the TV (player)", "player"));
        root.addView(bigBtn("This is a Remote", "remote"));
        setContentView(root);
        SysBars.pad(root);   // keep the nav bar off the content (edge-to-edge)
    }

    /** A fresh ordinary install pins the show every time it goes full screen (Android asks for one tap).
        Only when nobody has chosen yet: an explicit off in Settings stays off. A device-owner kiosk has
        its own Kiosk lock switch. */
    private void pinByDefault() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) return;
            if (!Cfg.p(this).contains("anancy_kiosk")) Cfg.put(this, "anancy_kiosk", 1);
        } catch (Exception ignored) {}
    }

    private Button bigBtn(String label, final String role) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY, role).apply();
            pinByDefault();
            route("player".equals(role) ? PlayerActivity.class : RemoteActivity.class);
        }});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(72));
        lp.topMargin = pad(12);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void route(Class<?> c) { startActivity(new Intent(this, c)); finish(); }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        // Route regardless of the answer: every permission here is optional to STARTING, and a
        // denial is reported properly by the set-up check rather than by refusing to open.
        if (req == 1 && pending != null) { Class<?> c = pending; pending = null; route(c); }
    }

    /** Something plain and dark to sit under the permission dialog, so deferring the route doesn't
        flash the default theme in the half-second before the answer comes back. */
    private View blank() {
        LinearLayout v = new LinearLayout(this);
        v.setBackgroundColor(0xFF101418);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Only ask for what's missing. Asking for already-granted permissions still spawns a
        GrantPermissionsActivity, and because we route+finish immediately its result never lands —
        it lingers INVISIBLE with input focus above the kiosk, so touches stop reaching the show. */
    /** @return true if a dialog was actually spawned, so the caller knows not to route out from
        under it. */
    private boolean requestBlePerms() {
        try {
            java.util.List<String> want = new java.util.ArrayList<>();
            if (android.os.Build.VERSION.SDK_INT >= 31) want.addAll(java.util.Arrays.asList(
                    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"));
            // A13+ needs a runtime grant to SHOW the remote's foreground-service notification. On the
            // device-owner TV it's DPM-granted already (so it's filtered out below); this covers the
            // NON-owner remote / Play flavor, where nothing else requests it.
            if (android.os.Build.VERSION.SDK_INT >= 33) want.add("android.permission.POST_NOTIFICATIONS");
            // Android 10 and older read the shows through this. Only a device-owner kiosk grants it to itself.
            if (android.os.Build.VERSION.SDK_INT < 30) want.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            // QR pairing puts up a local-only hotspot, and startLocalOnlyHotspot throws
            // SecurityException without this. The device-owner TV grants it to itself, so the gap
            // only ever showed on a plain install -- where "Show pairing QR" sat on a blank white
            // square until it gave up. Both roles need it: the remote is the one that HOSTS.
            // Below 33 the same call is gated on fine location instead, so ask for whichever this
            // SDK actually checks -- outside the BLE gate, since 26-30 hit it too.
            want.add(android.os.Build.VERSION.SDK_INT >= 33
                    ? "android.permission.NEARBY_WIFI_DEVICES" : "android.permission.ACCESS_FINE_LOCATION");
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String p : want) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
            if (need.isEmpty()) return false;
            requestPermissions(need.toArray(new String[0]), 1);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
