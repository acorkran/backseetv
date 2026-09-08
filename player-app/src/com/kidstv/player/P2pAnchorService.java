package com.kidstv.player;

// ============================================================================
//  Wi-Fi Direct "tablet IS the anchor" -- folded into the one player app.
//  createGroup(config) pins a FIXED networkName ("DIRECT-KidodiTV") + passphrase
//  (which SoftAP can't), tablet becomes group owner at 192.168.49.1, and adverts
//  "_kidodi._tcp" (TXT port = the in-process control port) so remotes find it by
//  name. Started on demand via ControlService /p2p. (Copy of the kiosk spike,
//  repackaged; in the one-app world this replaces the kiosk's Bridge+P2P.)
// ============================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class P2pAnchorService extends Service {
    static final String TAG = "KidodiP2P";
    static final String ACTION_START = "com.kidstv.player.P2P_START";
    static final String ACTION_STOP  = "com.kidstv.player.P2P_STOP";

    static final String NET_NAME   = "DIRECT-KidodiTV";
    static final String PASSPHRASE = "kidodi-remote-2026";   // 8..63 chars
    static final String SVC_NAME   = "kidodi";
    static final int    CTRL_PORT  = 8090;                   // the in-process control server

    private WifiP2pManager mMgr;
    private WifiP2pManager.Channel mChan;
    private BroadcastReceiver mRx;
    private final Handler mH = new Handler(Looper.getMainLooper());
    private boolean mRunning = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        startForeground(52, buildNote(action));
        if (ACTION_STOP.equals(action)) { teardown(); stopSelf(); return START_NOT_STICKY; }
        if (!mRunning) { mRunning = true; startAnchor(); }
        return START_STICKY;
    }

    private void startAnchor() {
        mMgr = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (mMgr == null) { Log.e(TAG, "no WifiP2pManager"); return; }
        mChan = mMgr.initialize(this, getMainLooper(), () -> Log.w(TAG, "p2p channel disconnected"));
        registerReceiver();
        mMgr.removeGroup(mChan, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.i(TAG, "removed stale group"); mH.postDelayed(P2pAnchorService.this::createGroup, 800); }
            @Override public void onFailure(int r) { createGroup(); }
        });
    }

    private void createGroup() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                        .setNetworkName(NET_NAME)
                        .setPassphrase(PASSPHRASE)
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .build();
                mMgr.createGroup(mChan, cfg, actionLog("createGroup(fixed-name)"));
            } else {
                mMgr.createGroup(mChan, actionLog("createGroup(auto-name)"));
            }
        } catch (Exception e) { Log.e(TAG, "createGroup threw", e); }
        addLocalService();
    }

    private void addLocalService() {
        try {
            Map<String, String> txt = new HashMap<>();
            txt.put("port", String.valueOf(CTRL_PORT));
            txt.put("name", "KidodiTV");
            txt.put("ver", "2");
            WifiP2pDnsSdServiceInfo info = WifiP2pDnsSdServiceInfo.newInstance(SVC_NAME, "_kidodi._tcp", txt);
            mMgr.addLocalService(mChan, info, actionLog("addLocalService(_kidodi._tcp)"));
        } catch (Exception e) { Log.e(TAG, "addLocalService threw", e); }
    }

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    WifiP2pInfo info = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (info != null) Log.i(TAG, "conn: formed=" + info.groupFormed + " isGO=" + info.isGroupOwner
                            + " goAddr=" + (info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress() : "null"));
                    logGroupInfo();
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(a)) {
                    WifiP2pDevice me = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (me != null) Log.i(TAG, "thisDevice: " + me.deviceName + " status=" + me.status);
                } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                    int st = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    Log.i(TAG, "p2p enabled=" + (st == WifiP2pManager.WIFI_P2P_STATE_ENABLED));
                }
            }
        };
        registerReceiver(mRx, f);
    }

    private void logGroupInfo() {
        try {
            mMgr.requestGroupInfo(mChan, (WifiP2pGroup g) -> {
                if (g == null) { Log.i(TAG, "group: none yet"); return; }
                Log.i(TAG, "GROUP UP  name=" + g.getNetworkName() + " pass=" + g.getPassphrase()
                        + " iface=" + g.getInterface() + " isGO=" + g.isGroupOwner() + " clients=" + g.getClientList().size());
            });
        } catch (Exception e) { Log.e(TAG, "requestGroupInfo threw", e); }
    }

    private WifiP2pManager.ActionListener actionLog(final String what) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.i(TAG, what + " OK"); logGroupInfo(); }
            @Override public void onFailure(int reason) { Log.e(TAG, what + " FAIL reason=" + reason); }
        };
    }

    private void teardown() {
        mRunning = false;
        try { if (mRx != null) unregisterReceiver(mRx); } catch (Exception ignored) {}
        mRx = null;
        try {
            if (mMgr != null && mChan != null) {
                mMgr.clearLocalServices(mChan, null);
                mMgr.removeGroup(mChan, actionLog("removeGroup(teardown)"));
            }
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() { teardown(); super.onDestroy(); }

    private Notification buildNote(String action) {
        String ch = "kidodi_p2p";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(new NotificationChannel(ch, "Kidodi Wi-Fi Direct", NotificationManager.IMPORTANCE_MIN));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Kidodi anchor")
                .setContentText(ACTION_STOP.equals(action) ? "stopping" : NET_NAME)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
    }
}
