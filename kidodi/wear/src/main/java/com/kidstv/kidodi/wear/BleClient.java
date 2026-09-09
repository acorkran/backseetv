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
    public interface Listener { void onStatus(String s); void onState(String json); }
    static final String TAG = "KidodiWear";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdCh, stateCh;
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
        ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
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
        status("scanning...");
        scanning = true;
        scanner.startScan(filters, ss, scanCb);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true; discovered = false; status("negotiating...");
                g.requestMtu(512);
                // fallback: discover even if onMtuChanged doesn't re-fire on a reconnect
                main.postDelayed(() -> { if (gatt != null && !discovered) gatt.discoverServices(); }, 900);
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; cmdCh = null; stateCh = null;
                try { g.close(); } catch (Exception ignored) {}
                gatt = null;
                if (!stopped) { status("reconnecting…"); main.postDelayed(() -> start(), 2500); }  // auto-reconnect
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { Log.i(TAG, "mtu=" + mtu); if (!discovered) g.discoverServices(); }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            discovered = true;
            BluetoothGattService svc = g.getService(Ble.SERVICE);
            if (svc == null) { status("service not found"); return; }
            cmdCh = svc.getCharacteristic(Ble.CMD);
            stateCh = svc.getCharacteristic(Ble.STATE);
            if (stateCh != null) {
                g.setCharacteristicNotification(stateCh, true);
                BluetoothGattDescriptor cccd = stateCh.getDescriptor(Ble.CCCD);
                if (cccd != null) { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(cccd); }
            }
            lastStateMs = System.currentTimeMillis();   // grace so the watchdog waits for first push
            status("ready");
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { deliver(ch.getUuid(), ch.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) { deliver(ch.getUuid(), value); }
    };

    private void deliver(java.util.UUID uuid, byte[] v) {
        if (Ble.STATE.equals(uuid) && v != null) { lastStateMs = System.currentTimeMillis(); String s = new String(v); main.post(() -> l.onState(s)); }
    }

    // Watchdog: the player pushes state every ~1s. No state for 8s => dead link => force reconnect.
    // Catches all the ugly cases (zombie link after the peer restarts, MTU not re-firing, etc.).
    private void startHealth() {
        if (health != null) return;
        health = new Runnable() { public void run() {
            if (stopped) return;
            if (!connected) {
                scanning = false;   // a BT toggle kills scans with no callback -> flag can stick; clear it
                start();            // persistently retry until we (re)connect
            } else if (connected && lastStateMs > 0 && System.currentTimeMillis() - lastStateMs > 8000) {
                // connected but no state for 8s => dead link => force a full reconnect
                connected = false; discovered = false; cmdCh = null; stateCh = null;
                try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
                gatt = null; lastStateMs = 0;
                status("reconnecting…"); start();
            }
            main.postDelayed(this, 4000);
        }};
        main.postDelayed(health, 4000);
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
