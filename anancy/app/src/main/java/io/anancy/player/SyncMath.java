package io.anancy.player;

// ============================================================================
//  Pure clock-sync + drift math for synced multi-screen playback, factored out
//  of PlayerController so it can be unit-tested without ExoPlayer or Android.
//
//  Model: a follower estimates the leader's monotonic clock as
//    leaderClock ≈ localClock + offset
//  The offset is learned from /clock probes (NTP-lite), keeping the sample with
//  the LOWEST round-trip (least jitter) and tracking slow drift. Given a
//  timestamped leader position, the follower extrapolates where the leader is
//  "now" and either hard-seeks (large drift) or nudges playback speed (small
//  drift) to converge. Every method here is deterministic and side-effect free.
// ============================================================================

final class SyncMath {
    private SyncMath() {}

    /** Drift beyond this (ms) warrants a seek; under it, a gentle speed nudge. */
    static final long HARD_SEEK_MS = 250;

    /** Immutable running clock estimate produced by {@link #fold}. */
    static final class Clock {
        final long offset;    // add to local elapsedRealtime() to get the leader's clock
        final long bestRtt;   // lowest round-trip seen (the sample we trust most)
        final boolean valid;  // have we taken a usable sample yet?
        Clock(long offset, long bestRtt, boolean valid) { this.offset = offset; this.bestRtt = bestRtt; this.valid = valid; }
        static Clock initial() { return new Clock(0, Long.MAX_VALUE, false); }
    }

    /**
     * Fold one NTP-lite probe into the estimate. t0 = local time before the
     * request, tL = the leader's clock in its reply, t2 = local time after.
     * Keeps the lowest-RTT sample; a moderately-higher RTT nudges the offset a
     * quarter of the way (slow-drift tracking) and lets bestRtt creep up so a
     * genuine re-baseline can eventually win; a wildly-higher RTT is ignored.
     */
    static Clock fold(Clock cur, long t0, long tL, long t2) {
        long rtt = t2 - t0;
        long off = (tL + rtt / 2) - t2;                       // leader = local + off
        if (!cur.valid || rtt <= cur.bestRtt) {
            return new Clock(off, rtt, true);                 // first, or a cleaner sample: trust it
        } else if (rtt < cur.bestRtt * 2) {
            return new Clock(cur.offset + (off - cur.offset) / 4, cur.bestRtt + cur.bestRtt / 32, true);
        }
        return cur;                                           // too jittery — leave the estimate untouched
    }

    /**
     * The leader's position RIGHT NOW, extrapolated from a timestamped sample.
     * leaderNow is the leader's clock at "now" (localNow + offset). A non-advancing
     * or wildly-large delta is guarded. Never negative.
     */
    static long expectedPos(long leaderPos, long leaderClock, boolean leaderPlaying, long leaderNow, boolean clkValid) {
        long e = leaderPos;
        if (clkValid && leaderClock > 0 && leaderPlaying) {
            long adv = leaderNow - leaderClock;               // ms the leader advanced since it sampled
            if (adv > 0 && adv < 60000) e += adv;             // guard a wild clock
        }
        return Math.max(0, e);
    }

    /** True when |drift| warrants a hard seek instead of a gentle speed nudge. */
    static boolean shouldHardSeek(long drift) { return Math.abs(drift) > HARD_SEEK_MS; }

    /**
     * Playback speed to converge small drift: drift>0 means we're AHEAD of the
     * leader, so play slightly slower. Clamped to ±1.5% so it stays inaudible.
     */
    static float driftSpeed(long drift) {
        float sp = 1f - drift / 6000f;
        return Math.max(0.985f, Math.min(1.015f, sp));
    }
}
