package app.anancy.net;

import app.anancy.Ble;

// BLE central (phone). Scans for the Anancy service and holds a GATT link to EVERY tablet it hears —
// each tablet's id/name comes from its scan response, so we never depend on mDNS to find them. Commands
// go to the single TARGET, or (broadcast = "both in unison") to ALL connected tablets at once. One
// connection drives the displayed state; the rest are kept live purely to receive commands. Robust:
// continuous low-power scan reconnects dropped links; a per-connection watchdog drops a dead one.
// Media streaming still uses Wi-Fi (client -> host by a fixed IP) — this is control only.

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
    public interface Listener {
        void onStatus(String s); void onState(String json); void onStill(byte[] jpeg);
        /** A player was heard advertising (id + friendly name), whether or not we attach to it. */
        void onSeen(String id, String name);
        /** A player's state told us its LAN ip + whether it's a host (holds the library) — so a remote can
            HTTP it directly and pick the media host as the sync leader. */
        void onDeviceState(String id, String ip, boolean isHost);
        /** A TV we have NOT paired with is nearby. We never connect to it; the UI offers "Pair". */
        void onUnpaired(String id, String name);
        /** A paired TV rejected our key (it was reset): it needs its QR scanned again. */
        void onPairingRejected(String id);
    }
    static final String TAG = "Anancy";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());

    // We connect to EVERY tablet we hear (cheap, instant switching). `drive` selects WHICH of them a
    // command actually goes to — a set of ids; null/empty = ALL. `stateId` picks whose state the UI shows.
    private volatile java.util.Set<String> drive = null;
    private volatile String stateId;
    public void setDrive(java.util.Set<String> ids) { drive = (ids == null || ids.isEmpty()) ? null : new java.util.HashSet<>(ids); }
    public void setStateId(String id) { stateId = (id == null || id.isEmpty()) ? null : id; }
    private boolean wanted(String id) { java.util.Set<String> d = drive; return d == null || d.contains(id); }
    /** Every tablet we currently hold a live BLE link to (so callers know which need Wi-Fi instead). */
    public java.util.Set<String> connectedIds() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (Conn c : conns.values()) if (c.connected && c.authed) s.add(c.id);
        return s;
    }
    public String connectedId() { Conn c = stateConn(); return c != null ? c.id : null; }

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private volatile boolean stopped = false, scanning = false;
    private Runnable health;
    private final java.util.HashSet<String> announced = new java.util.HashSet<>();   // de-dupe onSeen spam

    /** One live GATT link to one tablet. Each carries its own chars + still-reassembly + heartbeat clock. */
    private static final class Conn {
        final String id; volatile String name;
        volatile BluetoothGatt gatt;
        volatile BluetoothGattCharacteristic cmdCh, stateCh, imgCh;
        volatile boolean connected = false, discovered = false;
        volatile boolean authed = false, helloSent = false;   // authed = both sides proved they hold the key
        volatile String myNonce, tvNonce; volatile long connectedAt = 0;
        volatile byte[] sk; long ctr = 0;   // session key + command counter, once paired
        volatile long lastStateMs = 0;
        byte[] imgBuf; int imgLen, imgGot; final java.util.HashSet<Integer> imgSeen = new java.util.HashSet<>();
        Conn(String id, String name) { this.id = id; this.name = name; }
    }
    private final java.util.Map<String, Conn> conns = new java.util.concurrent.ConcurrentHashMap<>();

    public BleClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }

    /** Any DRIVEN tablet live? (a selected one we can command). */
    public boolean isConnected() { for (Conn c : conns.values()) if (c.connected && c.authed && wanted(c.id)) return true; return false; }
    /** The connection that drives the displayed state: the chosen stateId, else any driven, else any. */
    private Conn stateConn() {
        String s = stateId;
        if (s != null) { Conn c = conns.get(s); if (c != null && c.connected && c.authed) return c; }
        for (Conn c : conns.values()) if (c.connected && c.authed && wanted(c.id)) return c;
        for (Conn c : conns.values()) if (c.connected && c.authed) return c;
        return null;
    }
    /** Hold a link to EVERY tablet we hear — selection filters commands, not connections (instant switch). */
    private boolean want(String id) { return true; }
    /** TVs that refused our key, with the key they refused: don't hammer them until it changes. */
    // An "authfail" is unauthenticated: anyone can advertise our TV's id and send one. So it never
    // blacklists the TV; it backs off (1, 2, 4... min) and only asks for a re-scan after 3 in a row.
    private final java.util.Map<String, Long> backoffUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> fails = new java.util.concurrent.ConcurrentHashMap<>();

    public void start() {
        stopped = false;
        startHealth();
        if (scanning) return;   // one scanner finds every tablet; don't stack scans
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) { status("Bluetooth off"); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { status("no BLE scanner"); return; }
        try { if (scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Ble.SERVICE)).build());
        // LOW_LATENCY for the first seconds (instant open), then LOW_POWER — but keep SCANNING (we never
        // stop after connecting) so broadcast keeps finding every tablet and dropped links reconnect.
        ScanSettings fast = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        final ScanSettings slow = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        final List<ScanFilter> f = filters;
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int t, ScanResult r) {
                String[] tag = tagOf(r);
                if (tag == null) return;                       // player too old to publish an id -> skip
                final String id = tag[0], name = tag[1];
                // Only TVs we've paired with (scanned their QR). Another family's TV in the same car
                // park is listed as "not paired" and never connected to.
                String key = app.anancy.Pairing.controlKey(ctx, id);
                if (key == null) {
                    if (announced.add("u " + id + " " + name)) main.post(() -> l.onUnpaired(id, name));
                    return;
                }
                Long wait = backoffUntil.get(id);
                if (wait != null && System.currentTimeMillis() < wait) return;
                if (announced.add(id + " " + name)) main.post(() -> l.onSeen(id, name));
                if (!want(id)) return;
                Conn ex = conns.get(id);
                if (ex != null && (ex.connected || ex.gatt != null)) return;   // already connected / connecting
                Conn c = new Conn(id, name); conns.put(id, c);
                status("connecting...");
                try { c.gatt = r.getDevice().connectGatt(ctx, false, gattCb(c), android.bluetooth.BluetoothDevice.TRANSPORT_LE); }
                catch (Exception e) { conns.remove(id); }
            }
            @Override public void onScanFailed(int e) { scanning = false; status("scan failed " + e); }
        };
        final ScanCallback cb = scanCb;
        status("scanning...");
        scanning = true;
        scanner.startScan(filters, fast, scanCb);
        main.postDelayed(() -> {
            if (!stopped && scanCb == cb) {
                try { scanner.stopScan(cb); scanner.startScan(f, slow, cb); } catch (Exception ignored) {}
            }
        }, FAST_SCAN_MS);
    }
    static final long FAST_SCAN_MS = 8000;

    /** "<id>:<name>" from a player's BLE scan response (BleServer.MFG_ID); null when absent. */
    private static String[] tagOf(ScanResult r) {
        try {
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            byte[] d = rec != null ? rec.getManufacturerSpecificData(BleServer.MFG_ID) : null;
            if (d == null || d.length == 0) return null;
            String s = new String(d, "UTF-8");
            int c = s.indexOf(':');
            String id = c > 0 ? s.substring(0, c) : s;
            String name = c > 0 ? s.substring(c + 1) : "";
            return id.isEmpty() ? null : new String[]{id, name};
        } catch (Exception e) { return null; }
    }

    private BluetoothGattCallback gattCb(final Conn c) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    c.connected = true; c.discovered = false; c.authed = false; c.helloSent = false;
                    c.connectedAt = System.currentTimeMillis(); status("negotiating...");
                    g.requestMtu(512);
                    main.postDelayed(() -> { if (c.gatt != null && !c.discovered) c.gatt.discoverServices(); }, 900);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    c.connected = false; c.authed = false; c.cmdCh = null; c.stateCh = null;
                    try { g.close(); } catch (Exception ignored) {}
                    c.gatt = null; conns.remove(c.id);   // the running scan re-finds + reconnects if still wanted
                    if (!stopped) { status("reconnecting…"); if (!scanning) main.postDelayed(BleClient.this::start, 1500); }
                }
            }
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { if (!c.discovered) g.discoverServices(); }
            @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
                c.discovered = true;
                BluetoothGattService svc = g.getService(Ble.SERVICE);
                if (svc == null) { status("service not found"); return; }
                c.cmdCh = svc.getCharacteristic(Ble.CMD);
                c.stateCh = svc.getCharacteristic(Ble.STATE);
                c.imgCh = svc.getCharacteristic(Ble.IMG);
                if (c.imgCh != null) g.setCharacteristicNotification(c.imgCh, true);   // CCCD after STATE's completes
                if (c.stateCh != null) {
                    g.setCharacteristicNotification(c.stateCh, true);
                    BluetoothGattDescriptor cccd = c.stateCh.getDescriptor(Ble.CCCD);
                    if (cccd != null) { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(cccd); }
                }
                c.lastStateMs = System.currentTimeMillis();
                status("pairing…");
                if (c.stateCh == null) return;
                main.postDelayed(() -> sendHello(c), 1500);   // fallback if a CCCD callback never comes
            }
            // Serialize CCCD writes: once STATE's lands, enable IMG's (Android drops back-to-back descriptor writes).
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor desc, int st) {
                boolean stateDone = desc.getCharacteristic() != null && Ble.STATE.equals(desc.getCharacteristic().getUuid());
                if (c.imgCh != null && stateDone) {
                    BluetoothGattDescriptor icccd = c.imgCh.getDescriptor(Ble.CCCD);
                    if (icccd != null) { icccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(icccd); return; }
                }
                main.post(() -> sendHello(c));   // notifications are on: start the pairing handshake
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { deliver(c, ch.getUuid(), ch.getValue()); }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) { deliver(c, ch.getUuid(), value); }
        };
    }

    /** Step 1: say who we are; the TV answers with a challenge. */
    private void sendHello(Conn c) {
        if (c.helloSent || c.authed || !c.connected || c.cmdCh == null) return;
        c.helloSent = true;
        writeRaw(c, ("hello:" + app.anancy.Identity.id(ctx)).getBytes(), 0);
    }
    /** Steps 2 and 3 of the handshake (see BleServer.onAuthCmd). */
    private boolean onAuthMsg(Conn c, String s) {
        if (!s.contains("\"type\":\"auth")) return false;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            String type = o.optString("type"), key = app.anancy.Pairing.controlKey(ctx, c.id);
            if ("auth".equals(type)) {
                if (!c.id.equals(o.optString("id")) || key == null) { drop(c, "wrong TV"); return true; }   // not the TV we paired with
                c.myNonce = PairCrypto.nonce(); c.tvNonce = o.optString("n");
                String proof = PairCrypto.remoteProof(key, c.id, o.optString("n"), app.anancy.Identity.id(ctx));
                writeRaw(c, ("auth:" + proof + ":" + c.myNonce).getBytes(), 0);
            } else if ("authok".equals(type)) {
                if (key == null || c.myNonce == null || !PairCrypto.same(PairCrypto.tvProof(key, c.id, c.myNonce), o.optString("p"))) {
                    drop(c, "TV couldn't prove its key");   // an impostor using a paired TV's id
                    return true;
                }
                c.sk = PairCrypto.sessionKey(key, c.id, c.tvNonce, c.myNonce); c.ctr = 0;
                c.authed = true; c.lastStateMs = System.currentTimeMillis();
                fails.remove(c.id); backoffUntil.remove(c.id);
                Log.i(TAG, "BLE paired link up: " + c.id);
                status("ready");
            } else if ("authfail".equals(type)) {
                int n = fails.merge(c.id, 1, Integer::sum);
                backoffUntil.put(c.id, System.currentTimeMillis() + Math.min(16, 1L << (n - 1)) * 60_000L);
                Log.w(TAG, "BLE " + c.id + " refused our key (" + n + " in a row)");
                if (n == 3) main.post(() -> l.onPairingRejected(c.id));   // likely really reset: tell the user once
                drop(c, "key refused");
            }
        } catch (Exception ignored) {}
        return true;
    }
    /** Drop links to TVs this phone is no longer paired with (Forget). */
    public void dropUnpaired() {
        for (Conn c : new java.util.ArrayList<>(conns.values()))
            if (app.anancy.Pairing.controlKey(ctx, c.id) == null) drop(c, "forgotten");
        announced.clear();   // re-announce: a TV may have moved between paired and not
        fails.clear(); backoffUntil.clear();   // a fresh scan of its code: try again at once
    }
    private void drop(Conn c, String why) {
        Log.w(TAG, "BLE drop " + c.id + ": " + why);
        c.connected = false; c.authed = false;
        try { if (c.gatt != null) { c.gatt.disconnect(); c.gatt.close(); } } catch (Exception ignored) {}
        c.gatt = null; conns.remove(c.id);
    }

    private void deliver(Conn c, java.util.UUID uuid, byte[] v) {
        if (v == null) return;
        if (!c.authed) {   // until both sides have proved themselves, only the handshake gets through
            if (Ble.STATE.equals(uuid)) onAuthMsg(c, new String(v));
            return;
        }
        v = PairCrypto.openBytes(c.sk, v);   // everything after the handshake is sealed with the session key
        if (v == null) return;
        if (Ble.STATE.equals(uuid)) {
            c.lastStateMs = System.currentTimeMillis();
            String s = new String(v);
            try { org.json.JSONObject o = new org.json.JSONObject(s); String ip = o.optString("ip", ""); boolean host = !o.optBoolean("client", false);
                  if (!ip.isEmpty()) main.post(() -> l.onDeviceState(c.id, ip, host)); } catch (Exception ignored) {}   // learn every tablet's ip + host-ness, not just the shown one
            if (isStateSource(c)) main.post(() -> l.onState(s));
        } else if (Ble.IMG.equals(uuid)) {
            if (isStateSource(c)) onImgChunk(c, v);   // camera stills only from the one we're showing
        }
    }
    /** Only ONE connection surfaces state/stills to the UI: the chosen stateId, else the representative. */
    private boolean isStateSource(Conn c) {
        Conn s = stateConn();
        return s != null && s.id.equals(c.id);
    }

    // Reassemble a chunked still (per connection). START=[0x01][total len:4 BE][data@0], DATA=[0x02][offset:4 BE][data].
    private void onImgChunk(Conn c, byte[] v) {
        if (v == null || v.length < 5) return;
        int type = v[0] & 0xFF;
        int hdr = ((v[1] & 0xFF) << 24) | ((v[2] & 0xFF) << 16) | ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
        if (type == 0x01) {
            if (hdr <= 0 || hdr > 2_000_000) { c.imgBuf = null; return; }
            c.imgBuf = new byte[hdr]; c.imgLen = hdr; c.imgGot = 0; c.imgSeen.clear();
            placeImg(c, 0, v, v.length - 5);
        } else if (type == 0x02 && c.imgBuf != null) {
            placeImg(c, hdr, v, v.length - 5);
        } else return;
        if (c.imgBuf != null && c.imgGot >= c.imgLen) {
            byte[] done = c.imgBuf; c.imgBuf = null;
            if (validJpeg(done)) { final byte[] d = done; main.post(() -> l.onStill(d)); }
        }
    }
    private void placeImg(Conn c, int off, byte[] src, int n) {
        if (c.imgBuf == null || off < 0 || n <= 0 || off + n > c.imgBuf.length) return;
        if (c.imgSeen.add(off)) { System.arraycopy(src, 5, c.imgBuf, off, n); c.imgGot += n; }
    }
    private static boolean validJpeg(byte[] b) {
        int L = b.length;
        return L > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[L - 2] & 0xFF) == 0xFF && (b[L - 1] & 0xFF) == 0xD9;
    }

    // Watchdog: each player pushes state on change + a 10s heartbeat. No state for 25s => that link is dead;
    // drop it so the running scan reconnects. If nothing is scanning and we want a link, (re)start.
    private void startHealth() {
        if (health != null) return;
        health = new Runnable() { public void run() {
            if (stopped) return;
            long now = System.currentTimeMillis();
            for (Conn c : conns.values()) {
                if (c.connected && !c.authed && c.connectedAt > 0 && now - c.connectedAt > 20_000) { drop(c, "handshake timed out"); continue; }
                if (c.connected && c.lastStateMs > 0 && now - c.lastStateMs > 25_000) {
                    c.connected = false;
                    try { if (c.gatt != null) c.gatt.close(); } catch (Exception ignored) {}
                    c.gatt = null; conns.remove(c.id);
                }
            }
            if (!scanning) start();
            main.postDelayed(this, 10_000);
        }};
        main.postDelayed(health, 10_000);
    }

    /** Send to every DRIVEN, connected tablet (the selected set; all if none selected). */
    public void send(String cmd) {
        for (Conn c : conns.values()) if (c.authed && wanted(c.id)) write(c, forConn(c, cmd));
    }
    /** A Wi-Fi password is sealed with THAT TV's key before it goes on the air. */
    private byte[] forConn(Conn c, String cmd) {
        if (cmd.startsWith("wifi:")) {
            String sealed = app.anancy.Pairing.sealFor(ctx, c.id, cmd.substring(5));
            if (sealed != null) return ("wifix:" + sealed).getBytes();
        }
        return cmd.getBytes();
    }
    /** Send to a SPECIFIC set of connected tablets regardless of `drive` (e.g. per-screen volume in a group). */
    public void sendToIds(java.util.Set<String> ids, String cmd) {
        if (ids == null) return;
        for (Conn c : conns.values()) if (c.authed && ids.contains(c.id)) write(c, forConn(c, cmd));
    }
    /** Send only to the state-source tablet (queries have a single answer, not a fan-out). */
    public void sendState(String cmd) { Conn c = stateConn(); if (c != null) write(c, forConn(c, cmd)); }
    /** Seal a command with the session key and a fresh counter (the TV refuses replays). */
    private void write(Conn c, byte[] b) {
        if (c == null || !c.authed || c.sk == null) return;
        byte[] sealed;
        synchronized (c) { sealed = PairCrypto.sealCommand(c.sk, ++c.ctr, new String(b)); }
        writeRaw(c, sealed, 0);
    }
    /** GATT runs ONE operation at a time: a tap landing while the link is busy was silently dropped
        (the return value was ignored). Retry briefly so "I pressed pause and nothing happened" stops. */
    private void writeRaw(Conn c, byte[] b, int attempt) {
        try {
            if (c == null || c.gatt == null || c.cmdCh == null) return;
            c.cmdCh.setValue(b);
            c.cmdCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean ok = c.gatt.writeCharacteristic(c.cmdCh);
            if (!ok && attempt < 6) main.postDelayed(() -> writeRaw(c, b, attempt + 1), 120);
        } catch (Exception ignored) {}
    }

    public void stop() {
        stopped = true; scanning = false;
        try { if (health != null) main.removeCallbacks(health); } catch (Exception ignored) {}
        health = null;
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        for (Conn c : conns.values()) { try { if (c.gatt != null) c.gatt.close(); } catch (Exception ignored) {} }
        conns.clear();
        announced.clear();
    }

    private void status(String s) { main.post(() -> l.onStatus(s)); }
}
