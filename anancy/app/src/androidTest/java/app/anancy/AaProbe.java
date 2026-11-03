package app.anancy;

// ============================================================================
//  Drives the Android Auto media service the way the car does, without a car.
//
//  The desktop head unit can't complete a projection handshake headlessly, so
//  the only way to check the dash's browse tree and buttons before a drive was
//  to drive. This connects a MediaBrowser to AnancyMediaService from inside the
//  app's own process, walks the tree, and prints every row with its flags --
//  which is everything the head unit uses to draw it (chevron = BROWSABLE).
//
//    adb shell am instrument -w [-e checkin 1] app.anancy.test/app.anancy.AaProbe
//
//  Read-only by default. -e checkin 1 also fires Check-in and reports whether
//  fresh camera stills arrived; it never touches volume, toddler mode or power.
// ============================================================================

import android.app.Instrumentation;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AaProbe extends Instrumentation {
    private Bundle args;
    private final StringBuilder out = new StringBuilder();
    private MediaBrowserCompat browser;
    private Handler h;

    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }

    private void say(String s) { out.append(s).append('\n'); android.util.Log.i("AaProbe", s); }

    @Override public void onStart() {
        try { run(); } catch (Throwable t) { say("FAILED: " + t); }
        Bundle b = new Bundle();
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, out.toString());
        finish(0, b);
    }

    private void run() throws Exception {
        HandlerThread ht = new HandlerThread("probe"); ht.start(); h = new Handler(ht.getLooper());
        CountDownLatch connected = new CountDownLatch(1);
        h.post(() -> {
            browser = new MediaBrowserCompat(getTargetContext(),
                    new ComponentName(getTargetContext(), app.anancy.player.AnancyMediaService.class),
                    new MediaBrowserCompat.ConnectionCallback() {
                        @Override public void onConnected() { connected.countDown(); }
                    }, null);
            browser.connect();
        });
        if (!connected.await(15, TimeUnit.SECONDS)) { say("could not connect to the media service"); return; }
        say("connected, root=" + browser.getRoot());
        walk(browser.getRoot(), 0);

        // A page that came back empty must fill itself in once the TV answers, the way the head
        // unit sees it: stay subscribed and count reloads.
        final int[] loads = {0}, size = {0};
        CountDownLatch filled = new CountDownLatch(1);
        h.post(() -> browser.subscribe("tab_playlists", new MediaBrowserCompat.SubscriptionCallback() {
            @Override public void onChildrenLoaded(String id, List<MediaBrowserCompat.MediaItem> ch) {
                loads[0]++; size[0] = ch.size();
                if (!ch.isEmpty()) filled.countDown();
            }
        }));
        boolean ok = filled.await(25, TimeUnit.SECONDS);
        say("Playlists page: " + (ok ? size[0] + " rows" : "STILL EMPTY") + " after " + loads[0] + " load(s)");
        h.post(() -> browser.unsubscribe("tab_playlists"));

        MediaControllerCompat c = new MediaControllerCompat(getTargetContext(), browser.getSessionToken());
        Thread.sleep(3000);   // let the ticker publish state + queue
        PlaybackStateCompat ps = c.getPlaybackState();
        if (ps != null) {
            StringBuilder a = new StringBuilder();
            for (PlaybackStateCompat.CustomAction ca : ps.getCustomActions()) a.append(" [").append(ca.getName()).append(" -> ").append(ca.getAction()).append(']');
            say("now-playing custom actions:" + a);
            say("queue-item action supported: " + ((ps.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM) != 0));
        }
        List<MediaSessionCompat.QueueItem> q = c.getQueue();
        say("queue (drives the Queue button): " + (q == null ? "none" : q.size() + " items, title=" + c.getQueueTitle()));
        if (q != null && !q.isEmpty()) say("  first: " + q.get(0).getDescription().getTitle());

        if ("1".equals(args.getString("checkin"))) {
            byte[] before = app.anancy.remote.RemoteService.latestStill();
            c.getTransportControls().playFromMediaId("act:checkin", null);
            byte[] now = before; int fresh = 0;
            for (int i = 0; i < 40 && fresh < 3; i++) {
                Thread.sleep(1000);
                byte[] s = app.anancy.remote.RemoteService.latestStill();
                if (s != null && s != now) { fresh++; now = s; }
            }
            say("check-in: " + fresh + " fresh still(s) in the first ~40s"
                    + (now != null ? ", last " + now.length + " bytes" : ""));
            say("check-in subtitle: " + (c.getMetadata() == null ? "-" : c.getMetadata().getDescription().getSubtitle()));
        }
        h.post(() -> browser.disconnect());
        Thread.sleep(500);
    }

    private void walk(String parent, int depth) throws Exception {
        if (depth > 2) return;
        final List<MediaBrowserCompat.MediaItem>[] got = new List[1];
        CountDownLatch done = new CountDownLatch(1);
        h.post(() -> browser.subscribe(parent, new MediaBrowserCompat.SubscriptionCallback() {
            @Override public void onChildrenLoaded(String id, List<MediaBrowserCompat.MediaItem> children) {
                if (got[0] == null) { got[0] = children; done.countDown(); }
            }
        }));
        done.await(10, TimeUnit.SECONDS);
        h.post(() -> browser.unsubscribe(parent));
        if (got[0] == null) { say(pad(depth) + "(no answer for " + parent + ")"); return; }
        int shown = 0;
        for (MediaBrowserCompat.MediaItem m : got[0]) {
            if (++shown > 6) { say(pad(depth) + "... " + (got[0].size() - 6) + " more"); break; }
            CharSequence sub = m.getDescription().getSubtitle();
            say(pad(depth) + (m.isBrowsable() ? "> " : "  ") + m.getDescription().getTitle()
                    + (sub != null ? "  (" + sub + ")" : "") + "   [" + m.getMediaId() + "]");
            if (m.isBrowsable()) walk(m.getMediaId(), depth + 1);
        }
    }
    private static String pad(int d) { return "    ".repeat(d); }
}
