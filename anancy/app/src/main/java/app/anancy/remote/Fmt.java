package app.anancy.remote;

// ============================================================================
//  Tiny pure text formatters for the remote UI, factored out of RemoteActivity
//  so they can be unit-tested without Android. Deterministic, side-effect free.
//  Behaviour is preserved exactly as it was inline in RemoteActivity — notably
//  mmss() does NOT clamp negatives (callers only feed non-negative positions).
// ============================================================================

final class Fmt {
    private Fmt() {}

    /** Milliseconds -> "m:ss" (minutes are not capped at 60, so >1hr reads as e.g. "61:01"). */
    static String mmss(int ms) {
        int s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** Clean a media filename for display: drop the extension, dots -> spaces, ⧸ -> /, trim. */
    static String pretty(String t) {
        return t.replaceAll("\\.(mp4|mkv|m4v)$", "").replace('.', ' ').replace('⧸', '/').trim();
    }
}
