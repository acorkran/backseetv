package io.anancy.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** PackageInstaller's verdict on a self-update session (see WearUpdater.install). */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String sha = i.getStringExtra("id"), msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // Not (yet) our own installer of record -> the system wants a tap. Show its prompt.
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Exception e) { Log.w(WearUpdater.TAG, "confirm: " + e); }
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Log.i(WearUpdater.TAG, "installed " + sha);   // we're being replaced; the new build restarts us
                break;
            default:
                Log.w(WearUpdater.TAG, "install failed " + status + ": " + msg);
                if (sha != null) ctx.getSharedPreferences(WearUpdater.PREFS, Context.MODE_PRIVATE).edit().putString(WearUpdater.KEY_FAILED, sha).apply();
                WearUpdater.busy.set(false);
        }
    }
}
