package com.kidstv.kidodi.wear;

// BLE central for the watch remote — same logic proven on the phone. Scans for
// the Kidodi service, connects, negotiates a big MTU, subscribes to STATE, and
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
    static final String TAG = "KidodiWear";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdCh, stateCh, stillCh;
    private byte[] imgBuf; private int imgPos;   // reassembly of chunked IMG still frames
    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile boolean scanning = false;
    private volatile boolean discovered = false;
    private volatile long lastStateMs = 0;
    private Runnable health;

    public BleClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }

    public boolean isConnected() { return connected; }

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
                if (connected) return;
                scanning = false;
                status("connecting...");
                try { scanner.stopScan(this); } catch (Exception ignored) {}
                gatt = r.getDevice().connectGatt(ctx, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
            }
            @Override public void onScanFailed(int e) { scanning = false; status("scan failed " + e); }
        };
        final ScanCallback cb = scanCb;
        status("scanning...");
        scanning = true;
        scanner.startScan(filters, fast, scanCb);
        main.postDelayed(() -> {
            if (!stopped && scanning && !connected && scanCb == cb) {
                try { scanner.stopScan(cb); scanner.startScan(f, slow, cb); } catch (Exception ignored) {}
            }
        }, FAST_SCAN_MS);
    }
    static final long FAST_SCAN_MS = 8000;
    private long reconnectDelay = 2500;   // backs off to 30s while the TV stays unreachable; resets on connect

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true; discovered = false; reconnectDelay = 2500; status("negotiating...");
                g.requestMtu(512);
                // fallback: discover even if onMtuChanged doesn't re-fire on a reconnect
                main.postDelayed(() -> { if (gatt != null && !discovered) gatt.discoverServices(); }, 900);
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; cmdCh = null; stateCh = null;
                try { g.close(); } catch (Exception ignored) {}
                gatt = null;
                if (!stopped) {   // auto-reconnect with backoff (only while something is using the link)
                    status("reconnecting…"); main.postDelayed(() -> start(), reconnectDelay);
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
            status("ready");
        }
        // The GATT queue does one write at a time: subscribe to IMG only AFTER the STATE CCCD lands.
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            if (d != null && d.getCharacteristic() != null && Ble.STATE.equals(d.getCharacteristic().getUuid()) && stillCh != null) {
                g.setCharacteristicNotification(stillCh, true);
                BluetoothGattDescriptor c = stillCh.getDescriptor(Ble.CCCD);
                if (c != null) { c.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(c); }
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { deliver(ch.getUuid(), ch.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) { deliver(ch.getUuid(), value); }
    };

    private void deliver(java.util.UUID uuid, byte[] v) {
        if (v == null) return;
        if (Ble.STATE.equals(uuid)) { lastStateMs = System.currentTimeMillis(); final String s = new String(v); main.post(() -> l.onState(s)); return; }
        if (Ble.IMG.equals(uuid)) onImgChunk(v);
    }

    // Reassemble a still: 0x01 START = [1][int32 BE len][data...]; 0x02 DATA = [1][data...]; done at len.
    private void onImgChunk(byte[] v) {
        if (v.length < 1) return;
        if (v[0] == 0x01) {
            if (v.length < 5) { imgBuf = null; return; }
            int len = ((v[1] & 0xFF) << 24) | ((v[2] & 0xFF) << 16) | ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
            if (len <= 0 || len > 2_000_000) { imgBuf = null; return; }
            imgBuf = new byte[len]; imgPos = 0;
            int n = Math.min(v.length - 5, len);
            System.arraycopy(v, 5, imgBuf, 0, n); imgPos = n;
        } else if (v[0] == 0x02 && imgBuf != null) {
            int n = Math.min(v.length - 1, imgBuf.length - imgPos);
            if (n > 0) { System.arraycopy(v, 1, imgBuf, imgPos, n); imgPos += n; }
        } else return;
        if (imgBuf != null && imgPos >= imgBuf.length) {
            final byte[] done = imgBuf; imgBuf = null; imgPos = 0;
            main.post(() -> l.onStill(done));
        }
    }

    // Watchdog: the player pushes state on change + a 10s heartbeat. No state for 25s => dead link =>
    // force reconnect. Catches the ugly cases (zombie link after the peer restarts, MTU not re-firing).
    private void startHealth() {
        if (health != null) return;
        health = new Runnable() { public void run() {
            if (stopped) return;
            if (!connected) {
                if (!scanning) start();   // a BT toggle kills scans with no callback -> retry (scan itself is idempotent)
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
        if (gatt == null || cmdCh == null) return;
        cmdCh.setValue(cmd.getBytes());
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
