package app.anancy;

// ============================================================================
//  Who may talk to whom.
//
//  As a TV:     this device's own key (made on first use). It leaves the device
//               only inside the pairing QR. "Unpair all remotes" makes a new one.
//  As a remote: the keys of the TVs it has paired with, by TV id, learned by
//               scanning each TV's QR (a TV in a synced group also learns its
//               leader's key, sealed, from the remote that set the group up).
//
//  Kept in their own preferences file, never in Cfg, so no /config read or
//  write can reach them. See net/PairCrypto for what the keys are used for.
// ============================================================================

import android.content.Context;
import android.content.SharedPreferences;

import app.anancy.net.PairCrypto;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Pairing {
    private Pairing() {}

    private static final String PREFS = "anancy_pairing";
    private static final String MY_KEY = "my_key", EVER = "ever_paired", PEERS = "peers";

    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // --- this device as a TV ---------------------------------------------------------------------

    private static final PairCrypto.Verifier VERIFIER = new PairCrypto.Verifier();

    /** This TV's key, made on first use. */
    public static synchronized String myKey(Context c) {
        String k = p(c).getString(MY_KEY, null);
        if (!PairCrypto.validKey(k)) { k = PairCrypto.newKey(); p(c).edit().putString(MY_KEY, k).commit(); }
        return k;
    }

    /** A new key: every remote paired so far is locked out until it scans the QR again. */
    public static synchronized void resetMyKey(Context c) {
        p(c).edit().putString(MY_KEY, PairCrypto.newKey()).putBoolean(EVER, false).commit();
        VERIFIER.reset();
        Diag.log("Anancy", "pairing: TV key replaced, all remotes unpaired");
        Runnable r = onKeyReset;
        if (r != null) try { r.run(); } catch (Exception ignored) {}
    }
    /** Set by the Bluetooth server: a new key ends every live session at once. */
    public static volatile Runnable onKeyReset;

    /** True once any remote has proved it holds this TV's key. */
    public static boolean everPaired(Context c) { return p(c).getBoolean(EVER, false); }

    /** A remote just proved itself. The first time, close the first-run pairing QR. */
    public static void markPaired(Context c) {
        if (everPaired(c)) return;
        p(c).edit().putBoolean(EVER, true).apply();
        Runnable r = onFirstPair;
        if (r != null) try { r.run(); } catch (Exception ignored) {}
    }
    /** Set by the first-run pairing screen so it can close itself. */
    public static volatile Runnable onFirstPair;

    /** Check a signed HTTP request against this TV's key. */
    public static boolean verifyRequest(Context c, String header, String method, String pathAndQuery) {
        return VERIFIER.verify(myKey(c), header, method, pathAndQuery, System.currentTimeMillis());
    }

    // --- this device as a remote -----------------------------------------------------------------

    private static JSONObject peers(Context c) {
        try { return new JSONObject(p(c).getString(PEERS, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** The key for talking to TV `id`: our own when it's this device, else one we paired with. */
    public static String keyFor(Context c, String id) {
        if (id == null) return null;
        if (id.equals(Identity.id(c))) return myKey(c);
        JSONObject o = peers(c).optJSONObject(id);
        String k = o != null ? o.optString("key", null) : null;
        return PairCrypto.validKey(k) ? k : null;
    }

    public static boolean isPaired(Context c, String id) { return keyFor(c, id) != null; }

    /** Remember a TV from its QR (or a key handed over by a remote). */
    public static synchronized boolean remember(Context c, String id, String name, String key) {
        if (id == null || id.isEmpty() || !PairCrypto.validKey(key)) return false;
        try {
            JSONObject all = peers(c);
            JSONObject prev = all.optJSONObject(id);
            if (prev != null && key.equals(prev.optString("key")) && (name == null || name.isEmpty() || name.equals(prev.optString("name"))))
                return true;   // nothing new (a sync group re-sends its leader's key): don't stir the links
            JSONObject o = new JSONObject();
            String was = prev != null ? prev.optString("name", "") : "";
            o.put("name", name != null && !name.isEmpty() ? name : was);
            o.put("key", key);
            all.put(id, o);
            p(c).edit().putString(PEERS, all.toString()).commit();
            Diag.log("Anancy", "pairing: paired with " + id + (name != null && !name.isEmpty() ? " (" + name + ")" : ""));
            Runnable r = onPeersChanged;
            if (r != null) try { r.run(); } catch (Exception ignored) {}
            return true;
        } catch (Exception e) { return false; }
    }

    public static synchronized void forget(Context c, String id) {
        JSONObject all = peers(c);
        all.remove(id);
        p(c).edit().putString(PEERS, all.toString()).commit();
        Diag.log("Anancy", "pairing: forgot " + id);
        Runnable r = onPeersChanged;
        if (r != null) try { r.run(); } catch (Exception ignored) {}
    }

    /** id -> name of every TV this device is paired with. */
    public static Map<String, String> paired(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject all = peers(c);
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next(); JSONObject o = all.optJSONObject(id);
            if (o != null && PairCrypto.validKey(o.optString("key", null))) m.put(id, o.optString("name", id));
        }
        return m;
    }
    /** id -> key, for handing to the watch over the (private, same-signature) Wear Data Layer. */
    public static Map<String, String> pairedKeys(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject all = peers(c);
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next(); JSONObject o = all.optJSONObject(id);
            String k = o != null ? o.optString("key", null) : null;
            if (PairCrypto.validKey(k)) m.put(id, k);
        }
        return m;
    }
    /** Fired when a TV is paired or forgotten (the remote re-aims its links, the watch gets the keys). */
    public static volatile Runnable onPeersChanged;

    // --- addresses -> TV ids, for signing HTTP -----------------------------------------------------

    private static final Map<String, String> HOSTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> OFFSETS = new ConcurrentHashMap<>();
    private static final String HOSTS_PREF = "hosts";
    private static volatile boolean hostsLoaded = false;

    /** A TV with this id answers at this address. Kept across restarts: a follower TV that reboots
        has to reach its leader again before anything has told it the leader's id. */
    public static void noteHost(Context c, String ip, String id) {
        if (ip == null || ip.isEmpty() || id == null || id.isEmpty()) return;
        int i = ip.indexOf(':'); if (i > 0) ip = ip.substring(0, i);
        loadHosts(c);
        if (id.equals(HOSTS.put(ip, id))) return;
        try {
            JSONObject o = new JSONObject();
            int n = 0;
            for (Map.Entry<String, String> e : HOSTS.entrySet()) { if (++n > 64) break; o.put(e.getKey(), e.getValue()); }
            p(c).edit().putString(HOSTS_PREF, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void loadHosts(Context c) {
        if (hostsLoaded) return;
        hostsLoaded = true;
        try {
            JSONObject o = new JSONObject(p(c).getString(HOSTS_PREF, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String ip = it.next(); HOSTS.putIfAbsent(ip, o.optString(ip)); }
        } catch (Exception ignored) {}
    }

    private static String idForHost(Context c, String host) {
        if (host == null) return null;
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return Identity.id(c);
        loadHosts(c);
        return HOSTS.get(host);
    }

    /** The auth header for a request to `url`, or null when we hold no key for that TV. */
    public static String headerFor(Context c, String url, String method) {
        try { return headerFor(c, url, method, idForHost(c, new java.net.URL(url).getHost())); }
        catch (Exception e) { return null; }
    }
    private static String headerFor(Context c, String url, String method, String id) {
        try {
            java.net.URL u = new java.net.URL(url);
            String key = keyFor(c, id);
            if (key == null) return null;
            String pq = u.getFile();   // path + "?" + query, exactly as sent
            if (pq == null || pq.isEmpty()) pq = "/";
            Long off = OFFSETS.get(u.getHost());
            long now = System.currentTimeMillis() + (off == null ? 0 : off);
            return PairCrypto.signRequest(key, Identity.id(c), method, pq, now);
        } catch (Exception e) { return null; }
    }

    /** What each signed request was: which TV it was for and its nonce, to check the answer against. */
    private static final Map<java.net.HttpURLConnection, String[]> SENT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Sign an HTTP request. Call after setRequestMethod and before connecting. */
    public static java.net.HttpURLConnection sign(Context c, java.net.HttpURLConnection conn) {
        if (conn == null) return null;
        return sign(c, conn, idForHost(c, conn.getURL().getHost()));
    }
    /** Sign for a TV whose id we know but whose address isn't confirmed yet (a fresh mDNS answer). */
    public static java.net.HttpURLConnection sign(Context c, java.net.HttpURLConnection conn, String id) {
        if (conn == null) return null;
        String h = headerFor(c, conn.getURL().toString(), conn.getRequestMethod(), id);
        if (h != null) { conn.setRequestProperty(PairCrypto.HEADER, h); SENT.put(conn, new String[]{id, PairCrypto.nonceOf(h)}); }
        return conn;
    }

    /** Did this answer really come from the TV we signed the request for? Checks the TV's MAC over
        status, time and body against our request's nonce. Only then is the TV's clock believed. */
    public static boolean checkResponse(Context c, java.net.HttpURLConnection conn, int status, byte[] body) {
        try {
            String[] sent = SENT.remove(conn);
            if (sent == null || sent[1] == null) return false;
            String key = keyFor(c, sent[0]);
            String mac = conn.getHeaderField(PairCrypto.RESP_HEADER), t = conn.getHeaderField(PairCrypto.TIME_HEADER);
            if (key == null || mac == null || t == null) return false;
            String want = PairCrypto.responseMac(key, sent[1], status + "|" + t.trim() + "|" + PairCrypto.sha256Hex(body == null ? new byte[0] : body));
            if (!PairCrypto.same(want, mac)) return false;
            long off = Long.parseLong(t.trim()) - System.currentTimeMillis();
            String host = conn.getURL().getHost();
            if (Math.abs(off) > 30_000) OFFSETS.put(host, off); else OFFSETS.remove(host);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Read a signed GET: the body when the TV answered 200 AND proved it's the TV, else null.
        A 401 carrying the TV's proof still corrects our clock, so the next try gets in. */
    public static String fetch(Context c, java.net.HttpURLConnection conn) {
        try {
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            if (in != null) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) bo.write(b, 0, n); in.close(); }
            byte[] body = bo.toByteArray();
            boolean genuine = checkResponse(c, conn, code, body);
            return code == 200 && genuine ? new String(body, "UTF-8") : null;
        } catch (Exception e) { return null; }
    }

    // --- handing secrets between paired devices ---------------------------------------------------

    /** Seal `plain` so only TV `id` (or someone holding its key) can read it; null if not paired. */
    public static String sealFor(Context c, String id, String plain) {
        String k = keyFor(c, id);
        return k == null ? null : PairCrypto.seal(k, plain);
    }
    /** Open something sealed for this device as a TV. */
    public static String openMine(Context c, String sealed) { return PairCrypto.open(myKey(c), sealed); }
}
