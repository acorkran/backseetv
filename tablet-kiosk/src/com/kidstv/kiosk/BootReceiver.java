package com.kidstv.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Intent launch = new Intent(c, KioskActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(launch); } catch (Exception e) {}
    }
}
