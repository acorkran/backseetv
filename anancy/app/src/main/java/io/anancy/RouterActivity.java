package io.anancy;

import io.anancy.player.PlayerActivity;
import io.anancy.remote.RemoteActivity;

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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestBlePerms();

        String role = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY, null);
        if ("player".equals(role)) { route(PlayerActivity.class); return; }
        if ("remote".equals(role)) { route(RemoteActivity.class); return; }

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
    }

    private Button bigBtn(String label, final String role) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY, role).apply();
            route("player".equals(role) ? PlayerActivity.class : RemoteActivity.class);
        }});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pad(72));
        lp.topMargin = pad(12);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void route(Class<?> c) { startActivity(new Intent(this, c)); finish(); }

    /** Only ask for what's missing. Asking for already-granted permissions still spawns a
        GrantPermissionsActivity, and because we route+finish immediately its result never lands —
        it lingers INVISIBLE with input focus above the kiosk, so touches stop reaching the show. */
    private void requestBlePerms() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 31) return;
            java.util.List<String> want = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"));
            // A13+ needs a runtime grant to SHOW the remote's foreground-service notification. On the
            // device-owner TV it's DPM-granted already (so it's filtered out below); this covers the
            // NON-owner remote / Play flavor, where nothing else requests it.
            if (android.os.Build.VERSION.SDK_INT >= 33) want.add("android.permission.POST_NOTIFICATIONS");
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String p : want) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        } catch (Exception ignored) {}
    }

    private int pad(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
