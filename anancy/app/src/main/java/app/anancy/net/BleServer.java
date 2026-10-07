package app.anancy.net;

import app.anancy.Ble;
import app.anancy.Cfg;
import app.anancy.Identity;
import app.anancy.player.PlayModeActivity;
import app.anancy.player.PlayerActivity;
import app.anancy.player.PlayerController;

// ============================================================================
//  Player side of the BLE control tier: a GATT peripheral that advertises the
//  Anancy service and exposes two characteristics — CMD (remote writes command
//  strings) and STATE (player notifies now-playing JSON). This is the clean
//  offline/car/AA path: separate radio, no network, no firewall, peer-to-peer.
//  Bench-testable without any Wi-Fi. Runtime perms: BLUETOOTH_ADVERTISE + CONNECT.
// ============================================================================

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

@SuppressLint("MissingPermission")
public class BleServer {
    static final String TAG = "Anancy";
    /** Scan-response tag "<id>:<name>", read by the remote's BleClient to list tablets WITHOUT connecting.
        0xFFFF is the SIG's test/development company id; 27 = the 31-byte payload less len+type+company. */
    public static final int MFG_ID = 0xFFFF;
    public static final int MFG_MAX = 27;

    private final Context ctx;
    private final PlayerController ctrl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<BluetoothDevice> subs = new HashSet<>();

    private BluetoothGattServer gatt;
    private BluetoothLeAdvertiser adv;
    private AdvertiseCallback advCb;
    private BluetoothGattCharacteristic stateChar;
    private BluetoothGattCharacteristic imgChar;
    private Runnable pusher;
    private int cambleWant = 0;                                          // # of clients wanting BLE stills
    private java.util.concurrent.ScheduledExecutorService imgExec;       // pushes chunked stills ~1/sec

    public BleServer(Context c, PlayerController ctrl) { this.ctx = c.getApplicationContext(); this.ctrl = ctrl; }

    public void start() { start(0); }
    private void start(final int attempt) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) {
            // Boot race: the BT adapter can take tens of seconds to come up after boot, so an
            // early start finds it off. Retry until it's enabled (~90s) instead of giving up.
            if (attempt < 30) {
                Log.i(TAG, "BLE: adapter not ready (attempt " + attempt + "), retry in 3s");
                main.postDelayed(() -> start(attempt + 1), 3000);
            } else Log.e(TAG, "BLE: adapter off/absent (gave up)");
            return;
        }

        gatt = bm.openGattServer(ctx, serverCb);
        if (gatt == null) { Log.e(TAG, "BLE: openGattServer null"); return; }

        BluetoothGattService svc = new BluetoothGattService(Ble.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic cmd = new BluetoothGattCharacteristic(Ble.CMD,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        stateChar = new BluetoothGattCharacteristic(Ble.STATE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        stateChar.addDescriptor(new BluetoothGattDescriptor(Ble.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        imgChar = new BluetoothGattCharacteristic(Ble.IMG,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        imgChar.addDescriptor(new BluetoothGattDescriptor(Ble.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(cmd);
        svc.addCharacteristic(stateChar);
        svc.addCharacteristic(imgChar);
        gatt.addService(svc);

        startAdvertising(ad);
        // Notify on CHANGE (+ a 10s heartbeat), not every second: each notification wakes the watch's
        // SoC, and 3600 wakeups/hour all night is what flattened it. position/sleep countdowns are
        // excluded from the change signature — remotes interpolate those locally.
        pusher = new Runnable() { public void run() { pushStateIfChanged(); main.postDelayed(this, 1000); } };
        main.postDelayed(pusher, 1000);
        Log.i(TAG, "BLE server started");
    }

    private void startAdvertising(BluetoothAdapter ad) {
        adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) { Log.e(TAG, "BLE: no advertiser (peripheral unsupported?)"); return; }
        AdvertiseSettings s = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build();
        AdvertiseData d = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(Ble.SERVICE)).build();
        // The 31-byte advert is full (a 128-bit service UUID eats it), so WHICH tablet this is rides in
        // the scan response as "<id>:<name>" — a remote can then list tablets by name without connecting.
        AdvertiseData scan = null;
        try {
            byte[] tag = (Identity.id(ctx) + ":" + Identity.name(ctx)).getBytes("UTF-8");
            if (tag.length > MFG_MAX) tag = java.util.Arrays.copyOf(tag, MFG_MAX);
            scan = new AdvertiseData.Builder().setIncludeDeviceName(false)
                    .addManufacturerData(MFG_ID, tag).build();
        } catch (Exception e) { Log.w(TAG, "BLE scan-response tag: " + e); }
        advCb = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) { Log.i(TAG, "BLE advertising OK"); }
            @Override public void onStartFailure(int e) { Log.e(TAG, "BLE advertising FAIL " + e); }
        };
        if (scan != null) adv.startAdvertising(s, d, scan, advCb);
        else adv.startAdvertising(s, d, advCb);
    }

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice dev, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subs.remove(dev);
                // A remote that vanished mid-"camble:on" can't send camble:off: with nobody subscribed,
                // stop the BLE still pipeline (and the camera behind it) instead of running it forever.
                if (subs.isEmpty() && cambleWant > 0) main.post(() -> { cambleWant = 1; setCamble(false); });
            }
            Log.i(TAG, "BLE conn " + dev.getAddress() + " -> " + newState);
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice dev, int rid, BluetoothGattCharacteristic ch,
                boolean prep, boolean resp, int off, byte[] val) {
            if (Ble.CMD.equals(ch.getUuid()) && val != null) { String cmd = new String(val).trim(); Log.i(TAG, "BLE cmd: " + cmd); exec(cmd); }
            if (resp && gatt != null) gatt.sendResponse(dev, rid, BluetoothGatt.GATT_SUCCESS, off, null);
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice dev, int rid, BluetoothGattDescriptor desc,
                boolean prep, boolean resp, int off, byte[] val) {
            if (Ble.CCCD.equals(desc.getUuid())) {
                if (val != null && val.length > 0 && val[0] != 0) subs.add(dev); else subs.remove(dev);
                Log.i(TAG, "BLE subs=" + subs.size());
            }
            if (resp && gatt != null) gatt.sendResponse(dev, rid, BluetoothGatt.GATT_SUCCESS, off, null);
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice dev, int rid, int off, BluetoothGattCharacteristic ch) {
            if (Ble.STATE.equals(ch.getUuid()) && gatt != null)
                gatt.sendResponse(dev, rid, BluetoothGatt.GATT_SUCCESS, off, ctrl.stateJson(true).getBytes());
        }
    };

    private void exec(String cmd) {
        if (cmd.startsWith("sleep:")) {   // variable sleep timer: "sleep:15"
            try { ctrl.startSleep(Integer.parseInt(cmd.substring(6).trim())); } catch (Exception ignored) {}
            return;
        }
        if (cmd.startsWith("play:")) { PlayerActivity.wakeScreen(); ctrl.selectPlaylist(cmd.substring(5)); return; }
        if (cmd.equals("list")) { pushPlaylists(); return; }                              // one-shot playlist names
        if (cmd.equals("queue")) { pushQueue(); return; }                                 // one-shot queue window (play order)
        if (cmd.startsWith("jump:")) { try { ctrl.jumpTo(Integer.parseInt(cmd.substring(5).trim())); PlayerActivity.wakeScreen(); } catch (Exception ignored) {} return; }
        if (cmd.startsWith("snap:")) { SnapshotService.set(ctx, "on".equals(cmd.substring(5).trim())); return; }  // still-image capture toggle
        if (cmd.startsWith("playmode:")) { if ("on".equals(cmd.substring(9).trim())) PlayModeActivity.begin(ctx); else PlayModeActivity.end(); return; }  // toddler play mode
        if (cmd.startsWith("hotspot:")) { if ("on".equals(cmd.substring(8).trim())) HotspotService.start(ctx); else HotspotService.stop(ctx); return; }  // host hotspot + show QR
        if (cmd.startsWith("camble:")) { setCamble("on".equals(cmd.substring(7).trim())); return; }  // reduced-res stills over BLE
        // --- library / queue editing / user playlists (replies are one-shot typed payloads on STATE) ---
        if (cmd.startsWith("enq:"))     { ctrl.enqueue(pi(cmd.substring(4)), false, false); return; }
        if (cmd.startsWith("enqnext:")) { ctrl.enqueue(pi(cmd.substring(8)), true, false); return; }
        if (cmd.startsWith("enqnow:"))  { PlayerActivity.wakeScreen(); ctrl.enqueue(pi(cmd.substring(7)), true, true); return; }
        if (cmd.startsWith("enqshow:")) { ctrl.enqueueShow(cmd.substring(8).trim()); return; }
        if (cmd.startsWith("deq:"))     { ctrl.dequeue(pi(cmd.substring(4))); return; }
        if (cmd.startsWith("shuffle:")) { String v = cmd.substring(8).trim(); ctrl.setShuffle("toggle".equals(v) ? !ctrl.isShuffle() : "on".equals(v)); return; }
        if (cmd.startsWith("loop:"))    { String v = cmd.substring(5).trim(); ctrl.setLoop("toggle".equals(v) ? !ctrl.isLoop() : "on".equals(v)); return; }
        if (cmd.startsWith("savequeue:")) { ctrl.saveQueue(cmd.substring(10).trim()); pushPlaylists(); return; }
        if (cmd.startsWith("delpl:"))   { ctrl.deletePlaylist(cmd.substring(6).trim()); pushPlaylists(); return; }
        if (cmd.equals("shows") || cmd.startsWith("shows:")) { pushJson(ctrl.libraryJson(cmd.length() > 6 ? pi(cmd.substring(6)) : 0, 430)); return; }
        if (cmd.startsWith("eps:")) {   // eps:SHOW[:FROM] -> a page of up to 6 episodes (titles ≤60 chars)
            String rest = cmd.substring(4); int from = 0; int c = rest.lastIndexOf(':');
            if (c > 0 && rest.substring(c + 1).trim().matches("\\d+")) { from = pi(rest.substring(c + 1)); rest = rest.substring(0, c); }
            pushJson(ctrl.episodesJson(rest.trim(), from, 6, 60)); return;
        }
        // --- config sync (every tablet setting, from a remote) ---
        if (cmd.equals("cfg")) { pushJson(Cfg.configJson(ctx, true)); return; }   // one-shot config payload
        if (cmd.startsWith("cfg:")) {   // cfg:KEY=VALUE
            String rest = cmd.substring(4); int e = rest.indexOf('=');
            if (e > 0) {
                String k = rest.substring(0, e), v = rest.substring(e + 1);
                Cfg.applyConfig(ctx, k, v);
                if ("anancy_loop".equals(k)) ctrl.setLoop("1".equals(v));   // live-apply loop
            }
            return;
        }
        // --- connectivity level ---
        if (cmd.equals("conn")) { pushJson(ctrl.connJson()); return; }
        if (cmd.startsWith("conn:")) { HotspotService.applyMode(ctx, cmd.substring(5).trim()); pushJson(ctrl.connJson()); return; }
        if (cmd.startsWith("wifi:")) {   // wifi:SSID<TAB>PASS -> the TV joins (device owner = silent)
            String[] sp = cmd.substring(5).split("\t", 2);
            WifiJoiner.Creds cr = new WifiJoiner.Creds();
            cr.ssid = sp[0].trim(); cr.pass = sp.length > 1 ? sp[1] : ""; cr.type = cr.pass.isEmpty() ? "nopass" : "WPA";
            WifiJoiner.join(ctx, cr); return;
        }

        switch (cmd) {
            case "play": PlayerActivity.wakeScreen(); ctrl.play(); break;
            case "pause": ctrl.pause(); break;
            case "playpause": PlayerActivity.wakeScreen(); ctrl.toggle(); break;
            case "next": PlayerActivity.wakeScreen(); ctrl.next(); break;
            case "prev": PlayerActivity.wakeScreen(); ctrl.prev(); break;
            case "volup": ctrl.nudge(0.1f); break;
            case "voldown": ctrl.nudge(-0.1f); break;
            case "mute": ctrl.setMuted(!ctrl.isMuted()); break;   // toggle the persisted player mute (never zeroes system volume)
            case "unmute": ctrl.setMuted(false); break;
            case "power": PlayerActivity.power(); break;        // on/off like the v1 remote
            case "sleep10": ctrl.startSleep(10); break;         // back-compat
            case "wake": ctrl.cancelSleep(); break;
            case "flash": PlayerActivity.flash(Cfg.getInt(ctx, "anancy_flash_ms", 3000)); break;   // duration from Settings
        }
    }
    private static int pi(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; } }

    /** One-shot typed payload on the STATE characteristic (library / episodes / conn pages). */
    private void pushJson(String json) {
        if (gatt == null || stateChar == null || subs.isEmpty() || json == null) return;
        stateChar.setValue(json.getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }

    /** Toggle BLE reduced-res still delivery (ref-counted across clients). While ≥1 client wants it,
        a background thread pushes a fresh low-res still on the IMG characteristic ~once/second. */
    private void setCamble(boolean on) {
        if (on) {
            cambleWant++;
            SnapshotService.want(ctx, "ble", true);
            if (imgExec == null) {
                imgExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                imgExec.scheduleWithFixedDelay(this::pushImg, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } else {
            if (cambleWant > 0) cambleWant--;
            if (cambleWant == 0) {
                if (imgExec != null) { imgExec.shutdownNow(); imgExec = null; }
                SnapshotService.want(ctx, "ble", false);
            }
        }
    }

    /** Notify the latest reduced-res still to IMG subscribers as framed chunks:
        START = [0x01][JPEG length int32 big-endian][data...], then CONTINUE = [0x02][data...]. */
    private void pushImg() {
        try {
            if (gatt == null || imgChar == null || subs.isEmpty()) return;
            byte[] jpeg = SnapshotService.latestLowRes();
            if (jpeg == null) return;
            final int CHUNK = 500;   // centrals request MTU 512 -> ~509 usable; keep a margin
            // EVERY chunk carries its byte offset (5-byte header) so the receiver reassembles by position —
            // robust to out-of-order/duplicate BLE notifications (that was corrupting the still). START also
            // carries the total length. Retry a notify that the stack rejects (buffer full) instead of dropping.
            for (int off = 0; off < jpeg.length; ) {
                int n = Math.min(CHUNK - 5, jpeg.length - off);
                byte[] frame = new byte[5 + n];
                int h = off == 0 ? jpeg.length : off;         // START header = total len; DATA header = offset
                frame[0] = (byte) (off == 0 ? 0x01 : 0x02);
                frame[1] = (byte) ((h >> 24) & 0xFF); frame[2] = (byte) ((h >> 16) & 0xFF);
                frame[3] = (byte) ((h >> 8) & 0xFF);  frame[4] = (byte) (h & 0xFF);
                System.arraycopy(jpeg, off, frame, 5, n);
                imgChar.setValue(frame);
                for (BluetoothDevice d : new HashSet<>(subs)) {
                    for (int retry = 0; retry < 12; retry++) {
                        boolean ok = false;
                        try { ok = gatt.notifyCharacteristicChanged(d, imgChar, false); } catch (Exception ignored) {}
                        if (ok) break;
                        try { Thread.sleep(8); } catch (InterruptedException e) { return; }   // stack buffer full -> back off + retry
                    }
                }
                off += n;
                try { Thread.sleep(12); } catch (InterruptedException e) { return; }
            }
        } catch (Exception ignored) {}
    }

    static final long HEARTBEAT_MS = 10_000;
    private String lastSig = null; private long lastPushMs = 0;
    private void pushStateIfChanged() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        String json = ctrl.stateJson(true);
        String sig = json.replaceAll("\"(position_ms|sleep_remaining_ms)\":-?\\d+", "");
        long now = System.currentTimeMillis();
        if (sig.equals(lastSig) && now - lastPushMs < HEARTBEAT_MS) return;
        lastSig = sig; lastPushMs = now;
        stateChar.setValue(json.getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }
    /** Immediate push (after a command) so the remote that sent it sees the result at once. */
    private void pushState() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        String json = ctrl.stateJson(true);
        lastSig = json.replaceAll("\"(position_ms|sleep_remaining_ms)\":-?\\d+", ""); lastPushMs = System.currentTimeMillis();
        stateChar.setValue(json.getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }

    private void pushPlaylists() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        stateChar.setValue(ctrl.playlistsJson().getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }
    /** A window of the queue around the current item (±4 -> fits one MTU); Wi-Fi /queue gives the full list. */
    private void pushQueue() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        stateChar.setValue(ctrl.queueJson(-1, 3, 34).getBytes());   // ±3 items, titles ≤34 -> fits one MTU
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }

    public void stop() {
        try { if (pusher != null) main.removeCallbacks(pusher); } catch (Exception ignored) {}
        try { if (imgExec != null) { imgExec.shutdownNow(); imgExec = null; } } catch (Exception ignored) {}
        try { if (adv != null && advCb != null) adv.stopAdvertising(advCb); } catch (Exception ignored) {}
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }
}
