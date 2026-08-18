package com.kidstv.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override public void onEnabled(Context context, Intent intent) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, AdminReceiver.class);
        if (dpm.isDeviceOwnerApp(context.getPackageName())) {
            try {
                dpm.setLockTaskPackages(admin, new String[]{context.getPackageName(), "org.xbmc.kodi"});
            } catch (Exception e) {}
            try {
                // allow the Home button (returns to our launcher where the admin
                // corner lives) + the power menu, but nothing else
                dpm.setLockTaskFeatures(admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                    | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
            } catch (Exception e) {}
            try { dpm.setKeyguardDisabled(admin, true); } catch (Exception e) {}  // no lock screen after boot
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            try {
                dpm.addPersistentPreferredActivity(admin, home,
                    new ComponentName(context, KioskActivity.class));
            } catch (Exception e) {}
        }
    }
}
