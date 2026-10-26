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
//  Both concurrency queries are API 30/31+; older devices answer "no", which is
//  the safe assumption anyway.
// ============================================================================

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;

public final class WifiRadio {
    private WifiRadio() {}

    private static WifiManager wm(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** A Wi-Fi network we'd actually lose by escalating: one carrying INTERNET. Our own link to the
        TV has no internet by construction, so it never counts as something worth keeping. */
    public static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
            }
        } catch (Exception ignored) {}
        return false;
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

    /** Hosting for the TV right now costs us nothing: either we have a spare radio, or there is no
        Wi-Fi network to give up in the first place (the car). */
    public static boolean freeToHost(Context c) { return !onWifi(c) || canHostSpare(c); }

    /** Same question for joining the TV's own hotspot. */
    public static boolean freeToJoin(Context c) { return !onWifi(c) || canJoinSpare(c); }

    /** One line for the Connection screen, so the behaviour isn't a mystery. */
    public static String describe(Context c) {
        boolean host = canHostSpare(c), join = canJoinSpare(c);
        if (host || join) return "two Wi-Fi links at once — the TV link is free to keep up";
        if (!onWifi(c)) return "one Wi-Fi radio, nothing else using it";
        return "one Wi-Fi radio — the TV link costs you your current Wi-Fi, so it's raised only when needed";
    }
}
