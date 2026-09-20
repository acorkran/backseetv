package app.anancy.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the pure QR-payload parsing extracted from QrScanActivity + WifiJoiner.
 * Covers the two pairing schemes (WIFI: and ANANCY:), missing/extra fields, escaping,
 * and empty/garbage input — the Activity side effects (join / setResult) aren't tested.
 */
public class QrParseTest {

    // ---- parseWifi: standard WIFI:S:..;T:..;P:..;; QR ----

    @Test public void parseWifi_wellFormed() {
        QrParse.Wifi w = QrParse.parseWifi("WIFI:S:MyNet;T:WPA;P:secret;;");
        assertEquals("MyNet", w.ssid);
        assertEquals("WPA", w.type);
        assertEquals("secret", w.pass);
    }

    @Test public void parseWifi_schemePrefixIsCaseInsensitive() {
        QrParse.Wifi w = QrParse.parseWifi("wifi:S:Net;T:nopass;;");
        assertEquals("Net", w.ssid);
        assertEquals("nopass", w.type);
        assertNull(w.pass);
    }

    @Test public void parseWifi_missingOptionalFields() {
        // only the ssid present -> type/pass stay null (an open network)
        QrParse.Wifi w = QrParse.parseWifi("WIFI:S:OpenNet;;");
        assertEquals("OpenNet", w.ssid);
        assertNull(w.type);
        assertNull(w.pass);
    }

    @Test public void parseWifi_missingSsidIsNull() {
        // no ssid -> whole parse rejected (nothing to join)
        assertNull(QrParse.parseWifi("WIFI:T:WPA;P:secret;;"));
        assertNull(QrParse.parseWifi("WIFI:S:;T:WPA;;"));   // empty ssid also rejected
    }

    @Test public void parseWifi_extraFieldsIgnored() {
        QrParse.Wifi w = QrParse.parseWifi("WIFI:S:Net;T:WPA;P:pw;H:true;;");
        assertEquals("Net", w.ssid);
        assertEquals("WPA", w.type);
        assertEquals("pw", w.pass);
    }

    @Test public void parseWifi_unescapesReservedChars() {
        // an ssid with an escaped semicolon: WIFI:S:My\;Net;T:WPA;;
        QrParse.Wifi w = QrParse.parseWifi("WIFI:S:My\\;Net;T:WPA;;");
        assertEquals("My;Net", w.ssid);
        assertEquals("WPA", w.type);
    }

    @Test public void parseWifi_emptyAndGarbage() {
        assertNull(QrParse.parseWifi(null));
        assertNull(QrParse.parseWifi(""));
        assertNull(QrParse.parseWifi("hello world"));
        assertNull(QrParse.parseWifi("WIFI:"));    // header only, no fields
        assertNull(QrParse.parseWifi("WIFI:;;"));  // empty fields only
    }

    // ---- anancyIp: ANANCY:ip=<ip>[;k=v...] (caller guarantees the ANANCY: prefix) ----

    @Test public void anancyIp_wellFormed() {
        assertEquals("192.168.1.2", QrParse.anancyIp("ANANCY:ip=192.168.1.2"));
    }

    @Test public void anancyIp_extraKvPairs() {
        assertEquals("10.0.0.5", QrParse.anancyIp("ANANCY:ip=10.0.0.5;port=8090;k=v"));
    }

    @Test public void anancyIp_keyIsCaseInsensitiveAndTrimmed() {
        assertEquals("5.6.7.8", QrParse.anancyIp("ANANCY:IP = 5.6.7.8 "));
    }

    @Test public void anancyIp_missingIpKeyIsNull() {
        assertNull(QrParse.anancyIp("ANANCY:foo=bar;k=v"));
        assertNull(QrParse.anancyIp("ANANCY:"));        // no pairs at all
        assertNull(QrParse.anancyIp("ANANCY:garbage")); // no '=' -> no ip
    }
}
