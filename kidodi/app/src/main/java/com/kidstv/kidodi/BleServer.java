package com.kidstv.kidodi;

// ============================================================================
//  Player side of the BLE control tier: a GATT peripheral that advertises the
//  Kidodi service and exposes two characteristics — CMD (remote writes command
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
    static final String TAG = "Kidodi";

    private final Context ctx;
    private final PlayerController ctrl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<BluetoothDevice> subs = new HashSet<>();

    private BluetoothGattServer gatt;
    private BluetoothLeAdvertiser adv;
    private AdvertiseCallback advCb;
    private BluetoothGattCharacteristic stateChar;
    private Runnable pusher;

    public BleServer(Context c, PlayerController ctrl) { this.ctx = c.getApplicationContext(); this.ctrl = ctrl; }

    public void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) { Log.e(TAG, "BLE: adapter off/absent"); return; }

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
        svc.addCharacteristic(cmd);
        svc.addCharacteristic(stateChar);
        gatt.addService(svc);

        startAdvertising(ad);
        pusher = new Runnable() { public void run() { pushState(); main.postDelayed(this, 1000); } };
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
        advCb = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) { Log.i(TAG, "BLE advertising OK"); }
            @Override public void onStartFailure(int e) { Log.e(TAG, "BLE advertising FAIL " + e); }
        };
        adv.startAdvertising(s, d, advCb);
    }

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice dev, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) subs.remove(dev);
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
                gatt.sendResponse(dev, rid, BluetoothGatt.GATT_SUCCESS, off, ctrl.stateJson().getBytes());
        }
    };

    private void exec(String cmd) {
        if (cmd.startsWith("sleep:")) {   // variable sleep timer: "sleep:15"
            try { ctrl.startSleep(Integer.parseInt(cmd.substring(6).trim())); } catch (Exception ignored) {}
            return;
        }
        if (cmd.startsWith("play:")) { ctrl.selectPlaylist(cmd.substring(5)); return; }   // switch playlist
        if (cmd.equals("list")) { pushPlaylists(); return; }                              // one-shot playlist names

        switch (cmd) {
            case "play": ctrl.play(); break;
            case "pause": ctrl.pause(); break;
            case "playpause": ctrl.toggle(); break;
            case "next": ctrl.next(); break;
            case "prev": ctrl.prev(); break;
            case "volup": ctrl.nudge(0.1f); break;
            case "voldown": ctrl.nudge(-0.1f); break;
            case "mute": ctrl.setVolume(0f); break;
            case "power": PlayerActivity.power(); break;        // on/off like the v1 remote
            case "sleep10": ctrl.startSleep(10); break;         // back-compat
            case "wake": ctrl.cancelSleep(); break;
            case "flash": PlayerActivity.flash(3000); break;
        }
    }

    private void pushState() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        stateChar.setValue(ctrl.stateJson().getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }

    private void pushPlaylists() {
        if (gatt == null || stateChar == null || subs.isEmpty()) return;
        stateChar.setValue(ctrl.playlistsJson().getBytes());
        for (BluetoothDevice d : new HashSet<>(subs)) try { gatt.notifyCharacteristicChanged(d, stateChar, false); } catch (Exception ignored) {}
    }

    public void stop() {
        try { if (pusher != null) main.removeCallbacks(pusher); } catch (Exception ignored) {}
        try { if (adv != null && advCb != null) adv.stopAdvertising(advCb); } catch (Exception ignored) {}
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }
}
