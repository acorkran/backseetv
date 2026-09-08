package com.kidstv.remote;

// ============================================================================
//  V2 CONNECTIVITY PROTOTYPE (phone side)  --  join the tablet's Wi-Fi Direct
//  "anchor" group and talk to its bridge, WITHOUT the phone hosting a hotspot.
// ----------------------------------------------------------------------------
//  Pairs with tablet-kiosk P2pAnchorService, which brings up a Wi-Fi Direct
//  group owner with a FIXED name (DIRECT-KidodiTV) at 192.168.49.1 and adverts
//  a "_kidodi._tcp" DNS-SD service. This class:
//    1. discovers that service by name (no SSID typing),
//    2. connects to the group owner,
//    3. hands back the GO's IP (192.168.49.1) + the advertised bridge port,
//  after which the remote just points KodiClient at that host:port as usual.
//
//  This is a compiling scaffold: it's not wired into the shipping UI yet. Drop
//  P2pConnector.start(activity, listener) behind an experimental button and set
//  KodiClient's host to result.host when onConnected fires. Needs (manifest):
//  CHANGE_WIFI_STATE, ACCESS_WIFI_STATE, NEARBY_WIFI_DEVICES (API 33+).
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

import java.util.Map;

public class P2pConnector {
    public interface Listener {
        void onConnected(String host, int port);   // e.g. ("192.168.49.1", 8080)
        void onError(String why);
    }

    static final String TAG = "KidodiP2P";
    static final String SVC_TYPE = "_kidodi._tcp";
    static final int DEFAULT_PORT = 8080;

    private final Context mCtx;
    private final Listener mL;
    private WifiP2pManager mMgr;
    private WifiP2pManager.Channel mChan;
    private BroadcastReceiver mRx;
    private final Handler mH = new Handler(Looper.getMainLooper());
    private volatile int mAdvertisedPort = DEFAULT_PORT;
    private volatile String mPeerAddr;
    private boolean mDone = false;

    public P2pConnector(Context c, Listener l) { mCtx = c.getApplicationContext(); mL = l; }

    public void start() {
        mMgr = (WifiP2pManager) mCtx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mMgr == null) { fail("no WifiP2pManager"); return; }
        mChan = mMgr.initialize(mCtx, Looper.getMainLooper(), null);
        registerReceiver();

        // TXT record carries the bridge port the anchor is listening on.
        mMgr.setDnsSdResponseListeners(mChan,
            (instanceName, registrationType, srcDevice) -> {
                if (registrationType != null && registrationType.contains("_kidodi")) {
                    Log.i(TAG, "found service " + instanceName + " on " + srcDevice.deviceAddress);
                    mPeerAddr = srcDevice.deviceAddress;
                    connectTo(srcDevice);
                }
            },
            (fullDomain, txt, srcDevice) -> {
                try {
                    String p = txt != null ? txt.get("port") : null;
                    if (p != null) mAdvertisedPort = Integer.parseInt(p.trim());
                } catch (Exception ignored) {}
            });

        WifiP2pDnsSdServiceRequest req = WifiP2pDnsSdServiceRequest.newInstance(SVC_TYPE);
        mMgr.addServiceRequest(mChan, req, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                mMgr.discoverServices(mChan, log("discoverServices"));
            }
            @Override public void onFailure(int r) { fail("addServiceRequest failed " + r); }
        });
        // Safety net: if nothing is found in 20s, report it.
        mH.postDelayed(() -> { if (!mDone && mPeerAddr == null) fail("no _kidodi anchor found (is it on?)"); }, 20000);
    }

    private void connectTo(WifiP2pDevice dev) {
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = dev.deviceAddress;
        // groupOwnerIntent low -> let the tablet stay the owner (it advertised the service).
        cfg.groupOwnerIntent = 0;
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
                    Log.i(TAG, "connected; GO at " + host + ":" + mAdvertisedPort);
                    mH.post(() -> mL.onConnected(host, mAdvertisedPort));
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

    private void fail(String why) { if (!mDone) { mDone = true; Log.e(TAG, why); mH.post(() -> mL.onError(why)); } }

    private WifiP2pManager.ActionListener log(final String what) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { Log.i(TAG, what + " OK"); }
            @Override public void onFailure(int r) { Log.e(TAG, what + " FAIL " + r); }
        };
    }
}
