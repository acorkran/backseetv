package com.kidstv.kidodi;

// ============================================================================
//  Remote side of the BLE control tier: a GATT central that scans for the
//  Kidodi service, connects, negotiates a big MTU (so the state JSON fits in a
//  notification), subscribes to STATE, and writes commands to CMD. No network.
//  Runtime perms: BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
// ============================================================================

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
@SuppressWarnings("deprecation")   // pre-33 GATT read/write API kept for minSdk 26 compat
public class BleClient {
    public interface Listener { void onStatus(String s); void onState(String json); }
    static final String TAG = "Kidodi";

    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdCh, stateCh;
    private volatile boolean connected = false;

    public BleClient(Context c, Listener l) { this.ctx = c.getApplicationContext(); this.l = l; }

    public void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) { status("Bluetooth is off"); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { status("no BLE scanner"); return; }
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Ble.SERVICE)).build());
        ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int t, ScanResult r) {
                if (connected) return;
                status("found player, connecting...");
                try { scanner.stopScan(this); } catch (Exception ignored) {}
                gatt = r.getDevice().connectGatt(ctx, false, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
            }
            @Override public void onScanFailed(int e) { status("scan failed " + e); }
        };
        status("scanning for player...");
        scanner.startScan(filters, ss, scanCb);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { connected = true; status("connected, negotiating..."); g.requestMtu(512); }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { connected = false; status("disconnected"); }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { Log.i(TAG, "BLE mtu=" + mtu); g.discoverServices(); }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(Ble.SERVICE);
            if (svc == null) { status("service not found"); return; }
            cmdCh = svc.getCharacteristic(Ble.CMD);
            stateCh = svc.getCharacteristic(Ble.STATE);
            if (stateCh != null) {
                g.setCharacteristicNotification(stateCh, true);
                BluetoothGattDescriptor cccd = stateCh.getDescriptor(Ble.CCCD);
                if (cccd != null) { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(cccd); }
            }
            status("ready");
        }
        // pre-33 notify callback
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            deliver(ch.getUuid(), ch.getValue());
        }
        // 33+ notify callback
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            deliver(ch.getUuid(), value);
        }
    };

    private void deliver(java.util.UUID uuid, byte[] v) {
        if (Ble.STATE.equals(uuid) && v != null) { String s = new String(v); main.post(() -> l.onState(s)); }
    }

    public void send(String cmd) {
        if (gatt == null || cmdCh == null) return;
        cmdCh.setValue(cmd.getBytes());
        cmdCh.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        gatt.writeCharacteristic(cmdCh);
    }

    public void stop() {
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }

    private void status(String s) { main.post(() -> l.onStatus(s)); }
}
