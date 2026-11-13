package app.anancy.net;

import static org.junit.Assert.*;

import org.junit.Test;

public class PairCryptoTest {
    static final long NOW = 1_800_000_000_000L;

    @Test public void keysAreRandomAnd32Bytes() {
        String a = PairCrypto.newKey(), b = PairCrypto.newKey();
        assertNotEquals(a, b);
        assertTrue(PairCrypto.validKey(a));
        assertEquals(32, PairCrypto.unb64(a).length);
        assertFalse(PairCrypto.validKey("short"));
        assertFalse(PairCrypto.validKey(null));
        assertFalse(PairCrypto.validKey("not base64 at all!!"));
    }

    @Test public void signedRequestVerifies() {
        String k = PairCrypto.newKey();
        String h = PairCrypto.signRequest(k, "1a2b3c4d", "GET", "/state", NOW);
        assertTrue(v0().verify(k, h, "GET", "/state", NOW));
    }

    @Test public void wrongKeyFails() {
        String h = PairCrypto.signRequest(PairCrypto.newKey(), "1a2b3c4d", "GET", "/state", NOW);
        assertFalse(v0().verify(PairCrypto.newKey(), h, "GET", "/state", NOW));
    }

    @Test public void noHeaderFails() {
        assertFalse(v0().verify(PairCrypto.newKey(), null, "GET", "/state", NOW));
        assertFalse(v0().verify(PairCrypto.newKey(), "", "GET", "/state", NOW));
        assertFalse(v0().verify(PairCrypto.newKey(), "v1.a.b.c.d", "GET", "/state", NOW));
    }

    @Test public void signatureCoversPathQueryAndMethod() {
        String k = PairCrypto.newKey();
        PairCrypto.Verifier v = v0();
        String h = PairCrypto.signRequest(k, "c1", "GET", "/vol?d=0.1", NOW);
        assertFalse(v.verify(k, h, "GET", "/vol?d=1.0", NOW));       // a tampered query
        assertFalse(v.verify(k, h, "GET", "/transferowner", NOW));   // another endpoint
        assertFalse(v.verify(k, h, "POST", "/vol?d=0.1", NOW));      // another method
        assertTrue(v.verify(k, h, "GET", "/vol?d=0.1", NOW));
    }

    @Test public void replayIsRejected() {
        String k = PairCrypto.newKey();
        PairCrypto.Verifier v = v0();
        String h = PairCrypto.signRequest(k, "c1", "GET", "/next", NOW);
        assertTrue(v.verify(k, h, "GET", "/next", NOW));
        assertFalse(v.verify(k, h, "GET", "/next", NOW + 1000));
    }

    @Test public void staleOrFutureRequestsAreRejected() {
        String k = PairCrypto.newKey();
        PairCrypto.Verifier v = v0();
        long w = PairCrypto.WINDOW_MS;
        assertFalse(v.verify(k, PairCrypto.signRequest(k, "c", "GET", "/state", NOW - w - 1), "GET", "/state", NOW));
        assertFalse(v.verify(k, PairCrypto.signRequest(k, "c", "GET", "/state", NOW + w + 1), "GET", "/state", NOW));
        assertTrue(v.verify(k, PairCrypto.signRequest(k, "c", "GET", "/state", NOW - w + 1000), "GET", "/state", NOW));
    }

    @Test public void tamperedSignatureFails() {
        String k = PairCrypto.newKey();
        String h = PairCrypto.signRequest(k, "c1", "GET", "/state", NOW);
        char last = h.charAt(h.length() - 1);
        String bad = h.substring(0, h.length() - 1) + (last == '0' ? '1' : '0');
        assertFalse(v0().verify(k, bad, "GET", "/state", NOW));
    }

    @Test public void clientIdCantCarryTheSeparator() {
        String k = PairCrypto.newKey();
        String h = PairCrypto.signRequest(k, "ab.cd", "GET", "/state", NOW);
        assertEquals(5, h.split("\\.").length);
        assertTrue(v0().verify(k, h, "GET", "/state", NOW));
    }

    @Test public void bleProofsDependOnEveryPart() {
        String k = PairCrypto.newKey(), n = PairCrypto.nonce();
        String p = PairCrypto.remoteProof(k, "tv1", n, "phone1");
        assertEquals(p, PairCrypto.remoteProof(k, "tv1", n, "phone1"));
        assertNotEquals(p, PairCrypto.remoteProof(k, "tv2", n, "phone1"));
        assertNotEquals(p, PairCrypto.remoteProof(k, "tv1", PairCrypto.nonce(), "phone1"));
        assertNotEquals(p, PairCrypto.remoteProof(k, "tv1", n, "phone2"));
        assertNotEquals(p, PairCrypto.remoteProof(PairCrypto.newKey(), "tv1", n, "phone1"));
        // the TV's proof can't be replayed as a remote's and vice versa
        assertNotEquals(PairCrypto.tvProof(k, "tv1", n), PairCrypto.remoteProof(k, "tv1", n, ""));
    }

    @Test public void sealRoundTripsAndRejectsOtherKeys() {
        String k = PairCrypto.newKey();
        String s = PairCrypto.seal(k, "MyWifi\tp@ss;word");
        assertEquals("MyWifi\tp@ss;word", PairCrypto.open(k, s));
        assertNull(PairCrypto.open(PairCrypto.newKey(), s));
        assertNotEquals(s, PairCrypto.seal(k, "MyWifi\tp@ss;word"));   // fresh IV every time
        String bad = s.substring(0, s.length() - 2) + (s.endsWith("A") ? "BB" : "AA");
        assertNull(PairCrypto.open(k, bad));
        assertNull(PairCrypto.open(k, "garbage"));
    }

    @Test public void sameIsConstantTimeEquality() {
        assertTrue(PairCrypto.same("abc", "abc"));
        assertFalse(PairCrypto.same("abc", "abd"));
        assertFalse(PairCrypto.same("abc", "abcd"));
        assertFalse(PairCrypto.same(null, "abc"));
    }

    /** A verifier whose TV started an hour before NOW. */
    private static PairCrypto.Verifier v0() { return new PairCrypto.Verifier(NOW - 3_600_000); }

    @Test public void requestsSignedBeforeARestartAreRefused() {
        String k = PairCrypto.newKey();
        PairCrypto.Verifier restarted = new PairCrypto.Verifier(NOW);   // the TV just restarted, nonce cache empty
        long old = NOW - PairCrypto.Verifier.RESTART_GRACE_MS - 5000;
        assertFalse(restarted.verify(k, PairCrypto.signRequest(k, "c", "GET", "/next", old), "GET", "/next", NOW));
        assertTrue(restarted.verify(k, PairCrypto.signRequest(k, "c", "GET", "/next", NOW - 1000), "GET", "/next", NOW));
    }

    @Test public void responseMacTiesBodyToRequest() {
        String k = PairCrypto.newKey();
        String h = PairCrypto.signRequest(k, "c", "GET", "/state", NOW);
        String n = PairCrypto.nonceOf(h);
        assertNotNull(n);
        String body = PairCrypto.sha256Hex("{\"a\":1}".getBytes());
        String mac = PairCrypto.responseMac(k, n, body);
        assertEquals(mac, PairCrypto.responseMac(k, n, body));
        assertNotEquals(mac, PairCrypto.responseMac(k, PairCrypto.nonce(), body));                     // another request
        assertNotEquals(mac, PairCrypto.responseMac(k, n, PairCrypto.sha256Hex("{\"a\":2}".getBytes()))); // another body
        assertNotEquals(mac, PairCrypto.responseMac(PairCrypto.newKey(), n, body));                      // another TV
        assertNull(PairCrypto.nonceOf("junk"));
    }

    @Test public void sessionSealsBothWaysAndBindsBothNonces() {
        String k = PairCrypto.newKey(), a = PairCrypto.nonce(), b = PairCrypto.nonce();
        byte[] s1 = PairCrypto.sessionKey(k, "tv", a, b);
        assertArrayEquals(s1, PairCrypto.sessionKey(k, "tv", a, b));
        assertFalse(java.util.Arrays.equals(s1, PairCrypto.sessionKey(k, "tv", a, PairCrypto.nonce())));
        assertFalse(java.util.Arrays.equals(s1, PairCrypto.sessionKey(PairCrypto.newKey(), "tv", a, b)));
        byte[] still = new byte[467]; new java.util.Random(1).nextBytes(still);
        byte[] sealed = PairCrypto.sealBytes(s1, still);
        assertEquals(still.length + PairCrypto.SEAL_OVERHEAD, sealed.length);
        assertArrayEquals(still, PairCrypto.openBytes(s1, sealed));
        assertNull(PairCrypto.openBytes(PairCrypto.sessionKey(k, "tv", b, a), sealed));   // other session
        sealed[20] ^= 1;
        assertNull(PairCrypto.openBytes(s1, sealed));                                     // tampered
    }

    @Test public void sessionCommandsCarryACounter() {
        byte[] s1 = PairCrypto.sessionKey(PairCrypto.newKey(), "tv", PairCrypto.nonce(), PairCrypto.nonce());
        String[] c = PairCrypto.openCommand(s1, PairCrypto.sealCommand(s1, 42, "cfg:a=b|c"));
        assertEquals("42", c[0]);
        assertEquals("cfg:a=b|c", c[1]);
        assertNull(PairCrypto.openCommand(s1, "next".getBytes()));   // a plain command is refused
    }
}
