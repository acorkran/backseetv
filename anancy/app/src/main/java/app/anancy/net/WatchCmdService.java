package app.anancy.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.WearableListenerService;

import app.anancy.remote.RemoteService;

/** A watch command relayed through this phone (see WatchLink): sent exactly as the phone remote would.
    Answers "ok" once it's handed over, or "no" so the watch sends it over its own Bluetooth instead. */
public class WatchCmdService extends WearableListenerService {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable releaseWatch = () -> {
        RemoteService s = RemoteService.get();
        if (s != null) RemoteService.release(s, "watch");
    };

    @Override public Task<byte[]> onRequest(String nodeId, String path, byte[] data) {
        if (!WatchLink.PATH_CMD.equals(path) || data == null) return Tasks.forResult("no".getBytes());
        final String cmd = new String(data);
        if (!"remote".equals(getSharedPreferences("anancy", MODE_PRIVATE).getString("role", "")))
            return Tasks.forResult("no".getBytes());
        try {
            RemoteService.acquire(this, "watch");   // starts the links if the phone remote wasn't open
        } catch (Exception e) {                     // Android may refuse a background service start
            Log.w(WatchLink.TAG, "can't start the remote for the watch: " + e);
            if (RemoteService.get() == null) return Tasks.forResult("no".getBytes());
        }
        main.removeCallbacks(releaseWatch);
        main.postDelayed(releaseWatch, 120_000);   // keep the links warm while the watch is in use
        Log.i(WatchLink.TAG, "relayed from watch: " + app.anancy.Diag.redact(cmd));
        sendWhenReady(cmd, 0);
        return Tasks.forResult("ok".getBytes());
    }

    // A cold-started RemoteService needs a moment to link up with the TVs.
    private static void sendWhenReady(final String cmd, final int tries) {
        main.post(() -> {
            RemoteService s = RemoteService.get();
            boolean linked = s != null && (s.bleConnected() || "Wi-Fi".equals(s.transport()));
            if (linked || (s != null && tries >= 16)) { s.send(cmd); return; }
            if (tries >= 16) { Log.w(WatchLink.TAG, "dropped (remote never started): " + cmd); return; }
            main.postDelayed(() -> sendWhenReady(cmd, tries + 1), 500);
        });
    }
}
