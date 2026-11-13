package app.anancy.net;

// ============================================================================
//  Does this phone have a radio to spare?
//
//  Escalating to the TV's Wi-Fi link is FREE on a device that can run two Wi-Fi
//  connections at once (newer phones are usually dual- or tri-band) — it keeps
//  its normal network. On a single-radio device it costs you that network for
//  as long as the link is up. That answer is what decides whether the link is
//  raised eagerly and left up, or only held while a feature actually needs it.
//
//  ANDROID AUTO WIRELESS outranks all of it. AA puts the phone on the head
//  unit's Wi-Fi, and that link carries NO INTERNET — so "is there internet on
//  Wi-Fi" is exactly the wrong question to ask: it answers "no" for a car that
//  is actively projecting, and we would raise a hotspot straight over the top
//  of the dash. We count ANY Wi-Fi link we did not raise ourselves, and ask the
//  Car App Library whether a car is projecting right now.
//
//  Both concurrency queries are API 30/31+; older devices answer "no", which is
//  the safe assumption anyway.
// ============================================================================

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;

public final class WifiRadio {
    private WifiRadio() {}

    private static WifiManager wm(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
    private static ConnectivityManager cm(Context c) {
        return (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Any Wi-Fi link that is not the one we raised for the TV — home Wi-Fi, an office network, or
        an Android Auto head unit. Deliberately NOT filtered on NET_CAPABILITY_INTERNET: an AA head
        unit has none, and that is precisely the link we must not trample. */
    public static boolean onOtherWifi(Context c) {
        try {
            ConnectivityManager m = cm(c);
            if (m == null) return false;
            Network ours = null;
            try { ours = m.getBoundNetworkForProcess(); } catch (Exception ignored) {}
            for (Network n : m.getAllNetworks()) {
                if (ours != null && ours.equals(n)) continue;       // our own escalated link
                NetworkCapabilities caps = m.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Is a car connected to this phone right now, and is it PROJECTING (Android Auto) rather than
        running natively? Queried straight off the Car App Library's connection provider so it stays
        a plain synchronous call — CarConnection itself wraps this same provider in a LiveData. */
    public static boolean carProjecting(Context c) {
        Cursor cur = null;
        try {
            Uri uri = new Uri.Builder().scheme("content").authority("androidx.car.app.connection").build();
            // Name the column: Android Auto's provider throws on a null projection, which read as "no car".
            cur = c.getApplicationContext().getContentResolver().query(uri, new String[]{"CarConnectionState"}, null, null, null);
            if (cur == null || !cur.moveToNext()) return false;
            int col = cur.getColumnIndex("CarConnectionState");
            if (col < 0) return false;
            return cur.getInt(col) == 2;   // CarConnection.CONNECTION_TYPE_PROJECTION
        } catch (Exception e) { return false; }
        finally { try { if (cur != null) cur.close(); } catch (Exception ignored) {} }
    }

    /** Can a local-only (no-internet) link to the TV run ALONGSIDE our normal Wi-Fi? */
    public static boolean canJoinSpare(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { WifiManager w = wm(c); return w != null && w.isStaConcurrencyForLocalOnlyConnectionsSupported(); }
        catch (Throwable t) { return false; }
    }

    /** Can we host a hotspot and stay joined to our normal Wi-Fi? */
    public static boolean canHostSpare(Context c) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try { WifiManager w = wm(c); return w != null && w.isStaApConcurrencySupported(); }
        catch (Throwable t) { return false; }
    }

    /** Hosting for the TV costs us nothing right now: a spare radio, or nothing to give up. While a
        car is projecting the spare radio is the ONLY acceptable answer — we never push AA aside. */
    public static boolean freeToHost(Context c) {
        if (carProjecting(c)) return canHostSpare(c);
        return !onOtherWifi(c) || canHostSpare(c);
    }

    /** Same question for joining the TV's own hotspot. */
    public static boolean freeToJoin(Context c) {
        if (carProjecting(c)) return canJoinSpare(c);
        return !onOtherWifi(c) || canJoinSpare(c);
    }

    /** A hard stop: a car is projecting and this phone cannot do two Wi-Fi links at once, so the
        TV link must be given up entirely — the dash has no fallback, the camera has (BLE stills). */
    public static boolean carVetoesWifi(Context c) {
        return carProjecting(c) && !canHostSpare(c) && !canJoinSpare(c);
    }

    /** One line for the Connection screen, so the behaviour isn't a mystery. */
    public static String describe(Context c) {
        boolean host = canHostSpare(c), join = canJoinSpare(c);
        // Be precise about WHICH way round is free: STA+AP and STA+STA are different capabilities,
        // and a phone that has only the first must not be told it can do "two links at once".
        if (carProjecting(c) && !host && !join) return "busy with Android Auto, so the TV uses Bluetooth";
        if (host && join) return "can host or join and keep its Wi-Fi";
        if (host) return "can host and keep its Wi-Fi";
        if (join) return "can join the TV and keep its Wi-Fi";
        if (!onOtherWifi(c)) return "one Wi-Fi link, free";
        return "one Wi-Fi link; the TV link would take it";
    }
}
