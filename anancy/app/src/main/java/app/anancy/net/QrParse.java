package app.anancy.net;

// ============================================================================
//  Pure QR-payload parsing for pairing, factored out of QrScanActivity and
//  WifiJoiner so the parsing rules can be unit-tested without Android. Two
//  schemes:
//    - WIFI:S:<ssid>;T:<WPA|WEP|nopass>;P:<pass>;;  (backslash-escaped \ ; , : ")
//    - ANANCY:ip=<ip>[;k=v...]
//  Every method here is deterministic and side-effect free. WifiJoiner and
//  QrScanActivity delegate to these; the join / dispatch side effects stay there.
// ============================================================================

import java.util.ArrayList;
import java.util.List;

final class QrParse {
    private QrParse() {}

    /** Credentials parsed from a standard Wi-Fi QR (holder only; no Android types). */
    static final class Wifi {
        String ssid, type, pass;
    }

    /**
     * Parse a standard Wi-Fi QR: WIFI:S:<ssid>;T:<WPA|WEP|nopass>;P:<pass>;; with
     * \-escaping of \ ; , : ". Returns null for a non-WIFI payload or a missing ssid.
     */
    static Wifi parseWifi(String s) {
        if (s == null || !s.regionMatches(true, 0, "WIFI:", 0, 5)) return null;
        Wifi w = new Wifi();
        for (String field : splitUnescaped(s.substring(5), ';')) {
            if (field.length() < 2 || field.charAt(1) != ':') continue;
            char k = Character.toUpperCase(field.charAt(0));
            String v = unescape(field.substring(2));
            if (k == 'S') w.ssid = v;
            else if (k == 'T') w.type = v;
            else if (k == 'P') w.pass = v;
        }
        return (w.ssid != null && !w.ssid.isEmpty()) ? w : null;
    }

    /** ANANCY:ip=192.168.1.50[;k=v...] -> the ip value, or null if absent. Caller passes an ANANCY: payload. */
    static String anancyIp(String raw) {
        for (String kv : raw.substring(7).split(";")) {
            int e = kv.indexOf('=');
            if (e > 0 && "ip".equalsIgnoreCase(kv.substring(0, e).trim())) return kv.substring(e + 1).trim();
        }
        return null;
    }

    // --- WIFI-QR escaping helpers (backslash escapes \ ; , : ") ---
    private static List<String> splitUnescaped(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { cur.append(ch).append(s.charAt(++i)); }   // keep escape, unescaped later
            else if (ch == sep) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
            else out.append(ch);
        }
        return out.toString();
    }
}
