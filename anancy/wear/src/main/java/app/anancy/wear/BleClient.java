package app.anancy.wear;

// BLE central for the watch remote — same logic proven on the phone. Scans for
// the Anancy service, connects, negotiates a big MTU, subscribes to STATE, and
// writes commands to CMD. No network, no phone relay.

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.List;

@SuppressLint({"MissingPermission"})
@SuppressWarnings("deprecation")
public class BleClient {
    public interface Listener { void onStatus(String s); void onState(String json); void onStill(byte[] jpeg); }
    static final String TAG = "AnancyWear";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdCh, stateCh, stillCh;
    private byte[] imgBuf; private int imgLen, imgGot;   // offset-addressed reassembly of chunked IMG stills
    private final java.util.HashSet<Integer> imgSeen = new java.util.HashSet<>();
    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile boolean scanning = false;
    private volatile boolean discovered = false;
    private volatile long lastStateMs = 0;
    private Runnable health;

    public BleClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }

    public boolean isConnected() { return connected && authed; }
    // Pairing (see PairCrypto): only TVs whose key the phone gave us, and only once both sides proved it.
    private volatile boolean authed = false, helloSent = false;
    private volatile String myNonce, tvNonce;
    private volatile byte[] sk; private long ctr = 0;   // session key + command counter, once paired
    private volatile long connectedAt = 0;
    private final java.util.Map<String, String> rejected = new java.util.concurrent.ConcurrentHashMap<>();   // id -> refused key

    // Which TVs we may connect to (null = any): set from what the phone says (WatchTarget). Every player
    // puts "<id>:<name>" in its scan response under manufacturer id 0xFFFF (the phone's BleServer.MFG_ID).
    private volatile java.util.Set<String> allowed;
    private volatile String connectedId;
    static final int MFG_ID = 0xFFFF;
    public void setAllowed(java.util.Set<String> ids) {
        allowed = ids;
        String cur = connectedId;
        boolean noKey = gatt != null && WatchKeys.keyFor(ctx, cur) == null;   // the phone forgot this TV
        if (gatt != null && (noKey || (ids != null && (cur == null || !ids.contains(cur))))) {   // linked to the wrong TV now
            Log.i(TAG, "dropping " + cur + ": the phone points at " + ids);
            BluetoothGatt g = gatt;
            gatt = null; connected = false; authed = false; discovered = false; cmdCh = null; stateCh = null;
            try { g.disconnect(); g.close(); } catch (Exception ignored) {}
            status("switching TV…");
            if (!stopped) retryLater(500);
        }
    }
    private static String tvId(ScanResult r) {
        try {
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            byte[] d = rec != null ? rec.getManufacturerSpecificData(MFG_ID) : null;
            if (d == null || d.length == 0) return null;
            String s = new String(d, "UTF-8");
            int c = s.indexOf(':');
            String id = c > 0 ? s.substring(0, c) : s;
            return id.isEmpty() ? null : id;
        } catch (Exception e) { return null; }
    }

    public void start() {
        stopped = false;
        startHealth();
        if (connected || scanning) return;   // idempotent: don't stack scans
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) { status("Bluetooth off"); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { status("no BLE scanner"); return; }
        try { if (scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        long wait = scanBudgetWaitMs();
        if (wait > 0) { status("reconnecting…"); retryLater(wait); return; }
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Ble.SERVICE)).build());
        // LOW_LATENCY (radio ~100% duty) only for the first seconds — that's what makes opening the
        // remote feel instant. If the TV isn't found by then it's probably off or far away: drop to
        // LOW_POWER (~10% duty) rather than burn the battery hunting for it.
        ScanSettings fast = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        final ScanSettings slow = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        final List<ScanFilter> f = filters;
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int t, ScanResult r) {
                if (connected || gatt != null) return;   // already connecting (e.g. a direct 133 retry)
                String id = tvId(r);
                java.util.Set<String> ok = allowed;
                if (ok != null && (id == null || !ok.contains(id))) return;   // not a TV the phone pointed us at
                String key = WatchKeys.keyFor(ctx, id);
                if (key == null || key.equals(rejected.get(id))) return;    // not paired (another family's TV)
                connectedId = id;
                scanning = false;
                status("connecting...");
                try { scanner.stopScan(this); } catch (Exception ignored) {}
                lastDevice = r.getDevice(); quickRetries = 0;
                gatt = lastDevice.connectGatt(ctx, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
            }
            @Override public void onScanFailed(int e) {
                scanning = false; status("reconnecting…");
                Log.w(TAG, "scan failed " + e);
                retryLater(SCAN_WINDOW_MS);   // usually Android's "scanning too frequently": wait the window out
            }
        };
        final ScanCallback cb = scanCb;
        status("scanning...");
        scanning = true;
        noteScanStart();
        scanner.startScan(filters, fast, scanCb);
        main.postDelayed(() -> {
            // Dropping to the low-power mode costs another scan registration; skip it rather than
            // spend the budget a reconnect will need (the fast scan just keeps running instead).
            if (!stopped && scanning && !connected && scanCb == cb && scanBudgetWaitMs() == 0) {
                try { scanner.stopScan(cb); noteScanStart(); scanner.startScan(f, slow, cb); } catch (Exception ignored) {}
            }
        }, FAST_SCAN_MS);
    }

    // Android allows an app 5 scan starts per 30s. A 6th is refused ("app is scanning too frequently")
    // and the refusal keeps the window full, so when the TV app restarted, every disconnect + rescan
    // ran into it and the watch took ~110s to find a TV that was advertising the whole time. Spend at
    // most 4 starts per window and wait for the oldest to age out.
    static final long SCAN_WINDOW_MS = 30_000;
    private final java.util.ArrayDeque<Long> scanStarts = new java.util.ArrayDeque<>();
    private void noteScanStart() { scanStarts.addLast(android.os.SystemClock.elapsedRealtime()); }
    private long scanBudgetWaitMs() {
        long now = android.os.SystemClock.elapsedRealtime();
        while (!scanStarts.isEmpty() && now - scanStarts.peekFirst() > SCAN_WINDOW_MS) scanStarts.removeFirst();
        return scanStarts.size() < 4 ? 0 : SCAN_WINDOW_MS - (now - scanStarts.peekFirst()) + 500;
    }
    private final Runnable retry = () -> { if (!stopped && !connected) start(); };
    private void retryLater(long ms) { main.removeCallbacks(retry); main.postDelayed(retry, ms); }
    static final long FAST_SCAN_MS = 8000;
    private long reconnectDelay = 2500;
    private android.bluetooth.BluetoothDevice lastDevice;
    private int quickRetries = 0;   // backs off to 30s while the TV stays unreachable; resets on connect

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true; authed = false; helloSent = false; connectedAt = System.currentTimeMillis();
                discovered = false; reconnectDelay = 2500; quickRetries = 0; status("negotiating...");
                g.requestMtu(512);
                // fallback: discover even if onMtuChanged doesn't re-fire on a reconnect
                main.postDelayed(() -> { if (gatt != null && !discovered) gatt.discoverServices(); }, 900);
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; authed = false; cmdCh = null; stateCh = null;
                try { g.close(); } catch (Exception ignored) {}
                gatt = null;
                // A phone that is also this watch's companion often answers the first LE connects with
                // GATT_ERROR (133) while it's still advertising fine. Going back through scan + backoff
                // for each one turned that into a 1-2 minute wait, so retry the same device directly.
                if (!stopped && status == 133 && lastDevice != null && quickRetries < 4) {
                    quickRetries++;
                    status("connecting…");
                    final android.bluetooth.BluetoothDevice d = lastDevice;
                    main.postDelayed(() -> {
                        if (stopped || connected || gatt != null) return;
                        gatt = d.connectGatt(ctx, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
                    }, 800);
                    return;
                }
                if (!stopped) {   // auto-reconnect with backoff (only while something is using the link)
                    status("reconnecting…"); retryLater(reconnectDelay);
                    reconnectDelay = Math.min(30_000, reconnectDelay * 2);
                }
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { Log.i(TAG, "mtu=" + mtu); if (!discovered) g.discoverServices(); }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            discovered = true;
            BluetoothGattService svc = g.getService(Ble.SERVICE);
            if (svc == null) { status("service not found"); return; }
            cmdCh = svc.getCharacteristic(Ble.CMD);
            stateCh = svc.getCharacteristic(Ble.STATE);
            stillCh = svc.getCharacteristic(Ble.IMG);
            if (stateCh != null) {
                g.setCharacteristicNotification(stateCh, true);
                BluetoothGattDescriptor cccd = stateCh.getDescriptor(Ble.CCCD);
                if (cccd != null) { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(cccd); }
            }
            lastStateMs = System.currentTimeMillis();   // grace so the watchdog waits for first push
            status("pairing…");
            main.postDelayed(BleClient.this::sendHello, 1500);   // fallback if a CCCD callback never comes
        }
        // The GATT queue does one write at a time: subscribe to IMG only AFTER the STATE CCCD lands.
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            if (d != null && d.getCharacteristic() != null && Ble.STATE.equals(d.getCharacteristic().getUuid()) && stillCh != null) {
                g.setCharacteristicNotification(stillCh, true);
                BluetoothGattDescriptor c = stillCh.getDescriptor(Ble.CCCD);
                if (c != null) { c.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(c); return; }
            }
            main.post(BleClient.this::sendHello);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { deliver(ch.getUuid(), ch.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) { deliver(ch.getUuid(), value); }
    };

    private void sendHello() {
        if (helloSent || authed || !connected || gatt == null || cmdCh == null) return;
        helloSent = true;
        writeRaw("hello:" + WatchKeys.clientId(ctx));
    }
    /** The handshake, same as the phone's (see the app's BleServer.onAuthCmd). */
    private void onAuthMsg(String s) {
        if (!s.contains("\"type\":\"auth")) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            String type = o.optString("type"), id = connectedId, key = WatchKeys.keyFor(ctx, id);
            if ("auth".equals(type)) {
                if (id == null || !id.equals(o.optString("id")) || key == null) { dropLink("wrong TV"); return; }
                myNonce = PairCrypto.nonce(); tvNonce = o.optString("n");
                writeRaw("auth:" + PairCrypto.remoteProof(key, id, o.optString("n"), WatchKeys.clientId(ctx)) + ":" + myNonce);
            } else if ("authok".equals(type)) {
                if (key == null || myNonce == null || !PairCrypto.same(PairCrypto.tvProof(key, id, myNonce), o.optString("p"))) { dropLink("TV couldn't prove its key"); return; }
                sk = PairCrypto.sessionKey(key, id, tvNonce, myNonce); ctr = 0;
                authed = true; lastStateMs = System.currentTimeMillis();
                status("ready");
            } else if ("authfail".equals(type)) {
                if (key != null && id != null) rejected.put(id, key);
                dropLink("key rejected");
            }
        } catch (Exception ignored) {}
    }
    private void dropLink(String why) {
        Log.w(TAG, "drop: " + why);
        BluetoothGatt g = gatt;
        gatt = null; connected = false; authed = false; discovered = false; cmdCh = null; stateCh = null;
        try { if (g != null) { g.disconnect(); g.close(); } } catch (Exception ignored) {}
        status("not paired — pair this TV on the phone");
        if (!stopped) retryLater(reconnectDelay);
    }

    private void deliver(java.util.UUID uuid, byte[] v) {
        if (v == null) return;
        if (!authed) { if (Ble.STATE.equals(uuid)) onAuthMsg(new String(v)); return; }
        v = PairCrypto.openBytes(sk, v);   // sealed with the session key after the handshake
        if (v == null) return;
        if (Ble.STATE.equals(uuid)) { lastStateMs = System.currentTimeMillis(); final String s = new String(v); main.post(() -> l.onState(s)); return; }
        if (Ble.IMG.equals(uuid)) onImgChunk(v);
    }

    // Reassemble a still: 0x01 START = [1][int32 BE len][data...]; 0x02 DATA = [1][data...]; done at len.
    // Framed: START=[0x01][total len:4 BE][data@0], DATA=[0x02][byte offset:4 BE][data]. Offsets make
    // out-of-order / duplicate BLE notifications reassemble correctly; deliver only when EVERY byte has
    // arrived AND it's a valid JPEG (SOI/EOI) — truncated/corrupt frames (the watch's degraded stills) drop.
    private void onImgChunk(byte[] v) {
        if (v == null || v.length < 5) return;
        int type = v[0] & 0xFF;
        int hdr = ((v[1] & 0xFF) << 24) | ((v[2] & 0xFF) << 16) | ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
        if (type == 0x01) {
            if (hdr <= 0 || hdr > 2_000_000) { imgBuf = null; return; }
            imgBuf = new byte[hdr]; imgLen = hdr; imgGot = 0; imgSeen.clear();
            placeImg(0, v, v.length - 5);
        } else if (type == 0x02 && imgBuf != null) {
            placeImg(hdr, v, v.length - 5);
        } else return;
        if (imgBuf != null && imgGot >= imgLen) {
            byte[] done = imgBuf; imgBuf = null;
            if (validJpeg(done)) { final byte[] d = done; main.post(() -> l.onStill(d)); }
        }
    }
    private void placeImg(int off, byte[] src, int n) {
        if (imgBuf == null || off < 0 || n <= 0 || off + n > imgBuf.length) return;
        if (imgSeen.add(off)) { System.arraycopy(src, 5, imgBuf, off, n); imgGot += n; }
    }
    private static boolean validJpeg(byte[] b) {
        int L = b.length;
        return L > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[L - 2] & 0xFF) == 0xFF && (b[L - 1] & 0xFF) == 0xD9;
    }

    // Watchdog: the player pushes state on change + a 10s heartbeat. No state for 25s => dead link =>
    // force reconnect. Catches the ugly cases (zombie link after the peer restarts, MTU not re-firing).
    private void startHealth() {
        if (health != null) return;
        health = new Runnable() { public void run() {
            if (stopped) return;
            if (!connected) {
                if (!scanning) start();   // a BT toggle kills scans with no callback -> retry (scan itself is idempotent)
            } else if (!authed && connectedAt > 0 && System.currentTimeMillis() - connectedAt > 20_000) {
                dropLink("handshake timed out");
            } else if (connected && lastStateMs > 0 && System.currentTimeMillis() - lastStateMs > 25_000) {
                connected = false; discovered = false; cmdCh = null; stateCh = null;
                try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
                gatt = null; lastStateMs = 0;
                status("reconnecting…"); start();
            }
            main.postDelayed(this, 10_000);
        }};
        main.postDelayed(health, 10_000);
    }

    public void send(String cmd) {
        if (!authed || sk == null || gatt == null || cmdCh == null) return;
        byte[] sealed;
        synchronized (this) { sealed = PairCrypto.sealCommand(sk, ++ctr, cmd); }
        writeBytes(sealed);
    }
    private void writeRaw(String cmd) { writeBytes(cmd.getBytes()); }
    private void writeBytes(byte[] b) {
        if (gatt == null || cmdCh == null) return;
        cmdCh.setValue(b);
        cmdCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        gatt.writeCharacteristic(cmdCh);
    }

    public void stop() {
        stopped = true; connected = false; scanning = false;
        try { if (health != null) main.removeCallbacks(health); } catch (Exception ignored) {}
        health = null;
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }

    private void status(String s) { main.post(() -> l.onStatus(s)); }
}
