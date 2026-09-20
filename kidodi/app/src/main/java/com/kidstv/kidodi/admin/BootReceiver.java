package com.kidstv.kidodi.admin;

import com.kidstv.kidodi.RouterActivity;

// ============================================================================
//  Boot straight into the show (v1 parity). On BOOT_COMPLETED we launch the
//  RouterActivity, which routes to the player on a TV-role device. Combined
//  with the HOME intent-filter + the device-owner persistent-preferred-home
//  pin (PlayerActivity.enterKiosk), the appliance comes up in the kiosk with
//  no launcher, no menu — the show is the home screen.
// ============================================================================

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        try {
            Intent launch = new Intent(ctx, RouterActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
        } catch (Exception ignored) {}
    }
}
