package app.anancy.wear;

// ============================================================================
//  Pairing crypto. Pure Java (no Android), so it is unit-tested on the JVM.
//
//  Every TV holds one random 256-bit key. The key leaves the TV only inside
//  its pairing QR, so holding it proves someone stood in front of that screen.
//  Nothing on the air or the network ever carries the key itself:
//
//   - Bluetooth: challenge-response, both ways. The TV sends a nonce, the
//     remote answers HMAC(key, nonce...), then proves the TV knows the key too
//     with a nonce of its own. An unpaired TV or remote gets nowhere.
//   - HTTP: every request carries HMAC(key, client|time|nonce|method|path).
//     Time-windowed and nonce-checked, so a captured request can't be replayed.
//   - Secrets that must travel (Wi-Fi passwords, another TV's key handed to a
//     follower) are sealed with AES-GCM under a key derived from the TV key.
// ============================================================================

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PairCrypto {
    private PairCrypto() {}

    public static final String HEADER = "X-BST-Auth";
    public static final String TIME_HEADER = "X-BST-Time";
    /** How far a request's clock may be from the TV's. Wide, because an offline TV's clock drifts;
        the nonce cache is what actually stops replays inside the window. */
    public static final long WINDOW_MS = 10 * 60 * 1000L;

    private static final SecureRandom RNG = new SecureRandom();

    /** A fresh 256-bit key, base64url without padding (43 chars, QR-friendly). */
    public static String newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return b64(k);
    }

    /** 16 random bytes as hex. */
    public static String nonce() {
        byte[] n = new byte[16];
        RNG.nextBytes(n);
        return hex(n);
    }

    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }

    /** A key string is usable only if it decodes to exactly 32 bytes. */
    public static boolean validKey(String key) {
        try { return key != null && unb64(key).length == 32; } catch (Exception e) { return false; }
    }

    public static String hmacHex(String key, String msg) { return hex(hmac(unb64(key), msg.getBytes(StandardCharsets.UTF_8))); }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(msg);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Constant-time compare, so a wrong guess takes as long as a nearly-right one. */
    public static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int d = 0;
        for (int i = 0; i < x.length; i++) d |= x[i] ^ y[i];
        return d == 0;
    }

    // --- Bluetooth challenge-response -------------------------------------------------------------

    /** The remote's answer to the TV's challenge. Binds the TV, the nonce and who is asking. */
    public static String remoteProof(String key, String tvId, String tvNonce, String clientId) {
        return hmacHex(key, "ble1|remote|" + tvId + "|" + tvNonce + "|" + clientId);
    }

    /** The TV's answer to the remote's challenge: proves this really is the paired TV. */
    public static String tvProof(String key, String tvId, String clientNonce) {
        return hmacHex(key, "ble1|tv|" + tvId + "|" + clientNonce);
    }

    // --- HTTP request signing ---------------------------------------------------------------------

    /** Header value: v1.<client>.<time ms>.<nonce>.<hmac>. */
    public static String signRequest(String key, String clientId, String method, String pathAndQuery, long nowMs) {
        String n = nonce(), c = clean(clientId);
        return "v1." + c + "." + nowMs + "." + n + "." + hmacHex(key, reqMsg(c, nowMs, n, method, pathAndQuery));
    }

    private static String reqMsg(String client, long t, String nonce, String method, String pathAndQuery) {
        return "http1|" + client + "|" + t + "|" + nonce + "|" + (method == null ? "GET" : method.toUpperCase()) + "|" + pathAndQuery;
    }

    /** Ids are hex; anything else is dropped so the separator can't be smuggled in. */
    private static String clean(String id) {
        if (id == null) return "x";
        StringBuilder b = new StringBuilder();
        for (char ch : id.toCharArray()) if (Character.isLetterOrDigit(ch)) b.append(ch);
        return b.length() == 0 ? "x" : b.toString();
    }

    /** The request's nonce (for tying a response to it), or null. */
    public static String nonceOf(String header) {
        if (header == null) return null;
        String[] p = header.trim().split("\\.");
        return p.length == 5 && p[3].length() == 32 ? p[3] : null;
    }

    /** Checks signed requests for one TV key: signature, time window, and a nonce seen only once.
        The nonce cache lives in memory, so after a restart anything signed before it is refused
        (notBefore): a request captured earlier can't be replayed into a freshly started TV. */
    public static final class Verifier {
        private final Map<String, Long> seen = new ConcurrentHashMap<>();
        private long lastPurge = 0;
        private volatile long notBefore;
        /** Clients can be this far behind the TV's clock and still get in right after a restart. */
        public static final long RESTART_GRACE_MS = 60_000;

        public Verifier() { this(System.currentTimeMillis()); }
        public Verifier(long startedMs) { notBefore = startedMs - RESTART_GRACE_MS; }

        public boolean verify(String key, String header, String method, String pathAndQuery, long nowMs) {
            if (key == null || header == null) return false;
            String[] p = header.trim().split("\\.");
            if (p.length != 5 || !"v1".equals(p[0])) return false;
            long t;
            try { t = Long.parseLong(p[2]); } catch (Exception e) { return false; }
            if (Math.abs(nowMs - t) > WINDOW_MS || t < notBefore) return false;
            if (p[3].length() != 32) return false;
            String want = hmacHex(key, reqMsg(p[1], t, p[3], method, pathAndQuery));
            if (!same(want, p[4])) return false;
            purge(nowMs);
            return seen.putIfAbsent(p[3], t) == null;   // a nonce works once
        }

        private void purge(long nowMs) {
            if (nowMs - lastPurge < 30_000) return;
            lastPurge = nowMs;
            for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); )
                if (Math.abs(nowMs - it.next().getValue()) > 2 * WINDOW_MS) it.remove();
        }

        /** Forget every nonce (new key: old signatures are dead anyway). */
        public void reset() { seen.clear(); }
    }

    // --- responses ---------------------------------------------------------------------------------

    /** The TV's MAC over a response, tied to the request's nonce: a client only believes an answer
        (and only learns which TV lives at an address, or how far its clock is off) when this checks
        out. The "resp1" prefix keeps it from ever standing in for a request or Bluetooth proof. */
    public static String responseMac(String key, String reqNonce, String bodySha) {
        return hmacHex(key, "resp1|" + reqNonce + "|" + bodySha);
    }
    public static final String RESP_HEADER = "X-BST-Resp";

    public static String sha256Hex(byte[] b) {
        try { return hex(java.security.MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // --- narrower keys --------------------------------------------------------------------------------

    /** A key that only reaches a TV's shows: what a follower in a synced group gets instead of the
        TV's own key, so a TV that was once in a group can stream from its leader but never drive it,
        change it or see its camera. The TV accepts it only on its media paths. */
    public static String mediaKey(String key) {
        return b64(hmac(unb64(key), "media1".getBytes(StandardCharsets.UTF_8)));
    }

    /** Seals one streamed response (the camera) under the key the request was signed with and that
        request's nonce: only the device that asked can open it. */
    public static byte[] streamKey(String key, String reqNonce) {
        return hmac(unb64(key), ("live1|" + reqNonce).getBytes(StandardCharsets.UTF_8));
    }

    // --- a show streamed from one TV to another in a synced group ----------------------------------

    /** Video goes in blocks so the player can still seek: each block is sealed on its own, its
        number and the file's length are bound into the seal, and the key is new for every request. */
    public static final int MEDIA_BLOCK = 64 * 1024, BLOCK_TAG = 16;

    /** One /media answer's key: the requester's key and that request's nonce, kept apart from the camera's. */
    public static byte[] mediaStreamKey(String key, String reqNonce) {
        return hmac(unb64(key), ("media2|" + reqNonce).getBytes(StandardCharsets.UTF_8));
    }
    private static GCMParameterSpec blockIv(long index) {
        byte[] iv = new byte[12];
        iv[0] = 'B'; iv[1] = 'S'; iv[2] = 'T'; iv[3] = 'm';
        for (int i = 0; i < 8; i++) iv[4 + i] = (byte) (index >>> (56 - 8 * i));
        return new GCMParameterSpec(128, iv);
    }
    private static byte[] blockAad(long total) { return ("media2|" + total).getBytes(StandardCharsets.UTF_8); }

    /** Seal block `index` (its plaintext is buf[off..off+len)) of a file `total` bytes long. Output is len + 16. */
    public static byte[] sealBlock(byte[] k, long total, long index, byte[] buf, int off, int len) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), blockIv(index));
            c.updateAAD(blockAad(total));
            return c.doFinal(buf, off, len);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** The plaintext of a sealed block, or null if it was altered, moved, or belongs to another request. */
    public static byte[] openBlock(byte[] k, long total, long index, byte[] in, int off, int len) {
        try {
            if (k == null || len < BLOCK_TAG) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), blockIv(index));
            c.updateAAD(blockAad(total));
            return c.doFinal(in, off, len);
        } catch (Exception e) { return null; }
    }
    /** Plaintext size of block `index` of a `total`-byte file. */
    public static int blockLen(long total, long index) {
        return (int) Math.max(0, Math.min(MEDIA_BLOCK, total - index * MEDIA_BLOCK));
    }

    // --- the Bluetooth session after the handshake --------------------------------------------------

    /** A key for one Bluetooth session, from the TV key and BOTH handshake nonces. Everything after
        the handshake is sealed with it, so neither a passive listener nor someone relaying the
        handshake can read a still or slip a command into the link. */
    public static byte[] sessionKey(String key, String tvId, String tvNonce, String clientNonce) {
        return hmac(unb64(key), ("sess1|" + tvId + "|" + tvNonce + "|" + clientNonce).getBytes(StandardCharsets.UTF_8));
    }

    /** AES-GCM with a raw 32-byte key: iv(12) || ciphertext+tag(16). 28 bytes of overhead. */
    public static byte[] sealBytes(byte[] k, byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static final int SEAL_OVERHEAD = 28;

    /** The plaintext, or null if it was tampered with or sealed under another session. */
    public static byte[] openBytes(byte[] k, byte[] in) {
        try {
            if (k == null || in == null || in.length < SEAL_OVERHEAD) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return c.doFinal(in, 12, in.length - 12);
        } catch (Exception e) { return null; }
    }

    /** A command inside the session: "<counter>|<cmd>", sealed. The counter only goes up, so a
        recorded command can't be played back into the same session. */
    public static byte[] sealCommand(byte[] k, long counter, String cmd) {
        return sealBytes(k, (counter + "|" + cmd).getBytes(StandardCharsets.UTF_8));
    }
    /** [counter, cmd] or null. */
    public static String[] openCommand(byte[] k, byte[] in) {
        byte[] p = openBytes(k, in);
        if (p == null) return null;
        String s = new String(p, StandardCharsets.UTF_8);
        int i = s.indexOf('|');
        if (i <= 0) return null;
        try { Long.parseLong(s.substring(0, i)); } catch (Exception e) { return null; }
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    // --- sealing secrets in transit -----------------------------------------------------------------

    /** AES-256-GCM under a key derived from the TV key. Output: base64url(iv || ciphertext+tag). */
    public static String seal(String key, String plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sealKey(key), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return b64(out);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** The plaintext, or null if it was sealed under another key or tampered with. */
    public static String open(String key, String sealed) {
        try {
            byte[] in = unb64(sealed);
            if (in.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sealKey(key), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private static SecretKeySpec sealKey(String key) {
        return new SecretKeySpec(hmac(unb64(key), "seal1".getBytes(StandardCharsets.UTF_8)), "AES");
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }
}
