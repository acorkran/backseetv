package com.kidstv.v2remote;

// ============================================================================
//  Pull-model command bulletin (the user's idea): the phone is a PASSIVE
//  publisher. Buttons publish "last command + seq" here; the tablet POLLS this
//  server (tablet->phone is the firewall-allowed direction), executes new
//  commands, and posts its live state back in the same request. The phone never
//  reaches into the tablet -- so the Wi-Fi Direct group-owner input firewall
//  (which blocks phone->tablet) is never in the way.
//    GET /sync?state=<urlenc json>  -> stores state, returns {"seq":N,"cmd":".."}
//    GET /press?cmd=next            -> publish a command (buttons + testing)
//    GET /state                     -> last state the tablet reported
// ============================================================================

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class CmdServer {
    public interface StateListener { void onState(String stateJson); }

    static final String TAG = "KidodiV2";
    static final int PORT = 8091;

    private volatile int mSeq = 0;
    private volatile String mCmd = "";
    private volatile String mState = "{}";
    private volatile long mLastPoll = 0;
    private volatile boolean mRun = false;
    private ServerSocket mServer;
    private final StateListener mL;

    public CmdServer(StateListener l) { mL = l; }

    public void publish(String cmd) { mCmd = cmd; mSeq++; Log.i(TAG, "publish #" + mSeq + " " + cmd); }
    public long lastPollAgoMs() { return mLastPoll == 0 ? -1 : (System.currentTimeMillis() - mLastPoll); }
    public String state() { return mState; }

    public void start() {
        if (mRun) return; mRun = true;
        new Thread(new Runnable(){ public void run(){ serve(); } }).start();
    }
    public void stop() { mRun = false; try { if (mServer != null) mServer.close(); } catch (Exception ignored) {} }

    private void serve() {
        try {
            mServer = new ServerSocket();
            mServer.setReuseAddress(true);
            mServer.bind(new java.net.InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "cmd bulletin on :" + PORT);
            while (mRun) { final Socket c = mServer.accept();
                new Thread(new Runnable(){ public void run(){ handle(c); } }).start(); }
        } catch (Exception e) { if (mRun) Log.e(TAG, "serve died", e); }
    }

    private void handle(Socket c) {
        try {
            InputStream in = c.getInputStream();
            StringBuilder head = new StringBuilder(); int b, nl = 0;
            while ((b = in.read()) != -1) { head.append((char) b); if (b == '\n') { if (++nl == 2) break; } else if (b != '\r') nl = 0; }
            String line = head.length() > 0 ? head.toString().split("\r\n", 2)[0] : "";
            String[] parts = line.split(" ");
            String target = parts.length >= 2 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?'); if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            String body;
            if (path.equals("/sync")) {
                mLastPoll = System.currentTimeMillis();
                String st = param(query, "state");
                if (st != null) { mState = urlDecode(st); if (mL != null) mL.onState(mState); }
                body = "{\"seq\":" + mSeq + ",\"cmd\":\"" + mCmd + "\"}";
            } else if (path.equals("/press")) {
                String cmd = param(query, "cmd");
                if (cmd != null && cmd.length() > 0) publish(cmd);
                body = "{\"ok\":true,\"seq\":" + mSeq + "}";
            } else if (path.equals("/state")) {
                body = mState;
            } else {
                body = "{\"ok\":false}";
            }
            byte[] data = body.getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: "
                    + data.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(data); os.flush();
        } catch (Exception e) { Log.e(TAG, "handle err", e); }
        finally { try { c.close(); } catch (Exception ignored) {} }
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) { int e = kv.indexOf('='); if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1); }
        return null;
    }
    private static String urlDecode(String s) { try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }
}
