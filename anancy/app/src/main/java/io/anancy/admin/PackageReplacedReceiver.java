package io.anancy.admin;

import io.anancy.net.WearPusher;
import io.anancy.remote.RemoteService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires right after THIS app is updated (adb install -r / Play): push the embedded watch build to the watch. */
public class PackageReplacedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        if (i == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        // Same APK runs on the tablet (role=player): there it must do nothing — BootReceiver owns that side.
        if (!"remote".equals(ctx.getSharedPreferences("anancy", Context.MODE_PRIVATE).getString("role", ""))) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { WearPusher.pushSync(ctx); } finally { pr.finish(); }
        }, "wear-push-replaced").start();
        try { RemoteService.ensure(ctx); } catch (Exception ignored) {}   // bring the link back up after the update
    }
}
