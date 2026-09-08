package com.kidstv.v2remote;

// ============================================================================
//  Proper Wi-Fi Direct CLIENT join (not a legacy Wi-Fi join). Discovers the
//  tablet's "_kidodi._tcp" service, connects to it as a P2P client via
//  WifiP2pManager.connect (so it shares the p2p L2 with the group owner and is
//  NOT fenced off the way a legacy Settings-joined client is), then hands back
//  the group-owner address (192.168.49.1) + advertised control port (8090).
// ============================================================================

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class P2pConnector {
    public interface Listener {
        void onStatus(String s);
        void onConnected(String host, int port);
        void onError(String why);
    }

    static final String TAG = "KidodiP2P";
    static final String SVC_TYPE = "_kidodi._tcp";
    static final int DEFAULT_PORT = 8090;

    private final Context mCtx;
    private final Listener mL;
    private WifiP2pManager mMgr;
    private WifiP2pManager.Channel mChan;
    private BroadcastReceiver mRx;
    private final Handler mH = new Handler(Looper.getMainLooper());
    private volatile int mPort = DEFAULT_PORT;
    private volatile String mPeer;
    private boolean mDone = false;

    public P2pConnector(Context c, Listener l) { mCtx = c.getApplicationContext(); mL = l; }

    public void start() {
        mDone = false; mPeer = null;
        mMgr = (WifiP2pManager) mCtx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mMgr == null) { fail("no WifiP2pManager"); return; }
        mChan = mMgr.initialize(mCtx, Looper.getMainLooper(), null);
        registerReceiver();
        status("discovering _kidodi service...");

        mMgr.setDnsSdResponseListeners(mChan,
            (instanceName, registrationType, srcDevice) -> {
                if (registrationType != null && registrationType.contains("_kidodi") && mPeer == null) {
                    Log.i(TAG, "found " + instanceName + " @ " + srcDevice.deviceAddress);
                    mPeer = srcDevice.deviceAddress;
                    connectTo(srcDevice);
                }
            },
            (fullDomain, txt, srcDevice) -> {
                try { String p = txt != null ? txt.get("port") : null; if (p != null) mPort = Integer.parseInt(p.trim()); } catch (Exception ignored) {}
            });

        WifiP2pDnsSdServiceRequest req = WifiP2pDnsSdServiceRequest.newInstance(SVC_TYPE);
        mMgr.addServiceRequest(mChan, req, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { mMgr.discoverServices(mChan, log("discoverServices")); }
            @Override public void onFailure(int r) { fail("addServiceRequest failed " + r); }
        });
        mH.postDelayed(() -> { if (!mDone && mPeer == null) fail("no _kidodi anchor found (is the tablet's /p2p on?)"); }, 25000);
    }

    private void connectTo(WifiP2pDevice dev) {
        status("joining as P2P client...");
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = dev.deviceAddress;
        cfg.groupOwnerIntent = 0;   // let the tablet stay owner
        mMgr.connect(mChan, cfg, log("connect"));
    }

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                WifiP2pInfo info = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                if (info != null && info.groupFormed && !info.isGroupOwner
                        && info.groupOwnerAddress != null && !mDone) {
                    mDone = true;
                    String host = info.groupOwnerAddress.getHostAddress();
                    Log.i(TAG, "P2P client connected; GO=" + host + ":" + mPort);
                    mH.post(() -> mL.onConnected(host, mPort));
                }
            }
        };
        mCtx.registerReceiver(mRx, f);
    }

    public void stop() {
        try { if (mRx != null) mCtx.unregisterReceiver(mRx); } catch (Exception ignored) {}
        mRx = null;
        try { if (mMgr != null && mChan != null) mMgr.clearServiceRequests(mChan, null); } catch (Exception ignored) {}
    }

    private void status(String s) { mH.post(() -> mL.onStatus(s)); }
    private void fail(String why) { if (!mDone) { mDone = true; Log.e(TAG, why); mH.post(() -> mL.onError(why)); } }
    private WifiP2pManager.ActionListener log(final String what) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.i(TAG, what + " OK"); }
            @Override public void onFailure(int r) { Log.e(TAG, what + " FAIL " + r); status(what + " failed " + r); }
        };
    }
}
