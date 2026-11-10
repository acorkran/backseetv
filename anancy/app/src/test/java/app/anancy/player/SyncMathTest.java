package app.anancy.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the pure clock-sync + drift math that keeps followers in step with the leader. */
public class SyncMathTest {

    // ---- expectedPos: extrapolate the leader's position from a timestamped sample ----

    @Test public void expectedPos_advancesWhilePlaying() {
        // leader sampled pos=1000 at clock=5000; "now" on the leader clock is 5300 -> +300ms
        assertEquals(1300, SyncMath.expectedPos(1000, 5000, true, 5300, true));
    }

    @Test public void expectedPos_frozenWhenPaused() {
        assertEquals(1000, SyncMath.expectedPos(1000, 5000, false, 5300, true));
    }

    @Test public void expectedPos_ignoredWhenClockInvalid() {
        assertEquals(1000, SyncMath.expectedPos(1000, 5000, true, 5300, false));
    }

    @Test public void expectedPos_guardsWildForwardJump() {
        // delta >= 60s is treated as a bad clock and ignored
        assertEquals(1000, SyncMath.expectedPos(1000, 5000, true, 5000 + 60000, true));
    }

    @Test public void expectedPos_guardsNegativeDelta() {
        assertEquals(1000, SyncMath.expectedPos(1000, 5000, true, 4000, true));
    }

    @Test public void expectedPos_neverNegative() {
        assertEquals(0, SyncMath.expectedPos(-500, 0, false, 0, false));
    }

    @Test public void expectedPos_leaderClockZeroDoesNotAdvance() {
        // leaderClock==0 is treated as "no timestamp yet" -> hold the sampled pos even while playing
        assertEquals(1000, SyncMath.expectedPos(1000, 0, true, 5300, true));
    }

    @Test public void expectedPos_advancesRightUpToTheGuard() {
        // adv just under 60s is still applied (the guard is a strict < 60000)
        assertEquals(1000 + 59999, SyncMath.expectedPos(1000, 5000, true, 5000 + 59999, true));
    }

    // ---- shouldHardSeek: big drift => re-align, small drift => nudge ----

    @Test public void shouldHardSeek_boundary() {
        assertFalse(SyncMath.shouldHardSeek(SyncMath.HARD_SEEK_MS));
        assertTrue(SyncMath.shouldHardSeek(SyncMath.HARD_SEEK_MS + 1));
        assertTrue(SyncMath.shouldHardSeek(-(SyncMath.HARD_SEEK_MS + 1)));
    }

    // ---- driftSpeed: converge small drift with a speed change nobody hears ----

    @Test public void driftSpeed_zeroIsRealtime() {
        assertEquals(1.0f, SyncMath.driftSpeed(0), 1e-4f);
    }

    @Test public void driftSpeed_deadbandLeavesSpeedAlone() {
        assertEquals(1.0f, SyncMath.driftSpeed(SyncMath.DEADBAND_MS), 1e-4f);
        assertEquals(1.0f, SyncMath.driftSpeed(-SyncMath.DEADBAND_MS), 1e-4f);
    }

    @Test public void driftSpeed_aheadPlaysSlower() {
        assertEquals(0.97f, SyncMath.driftSpeed(60), 1e-4f);
    }

    @Test public void driftSpeed_behindPlaysFaster() {
        assertEquals(1.03f, SyncMath.driftSpeed(-60), 1e-4f);
    }

    @Test public void driftSpeed_clampedBothWays() {
        assertEquals(0.96f, SyncMath.driftSpeed(1_000_000), 1e-4f);
        assertEquals(1.04f, SyncMath.driftSpeed(-1_000_000), 1e-4f);
    }

    @Test public void driftSpeed_justInsideTheClamp() {
        assertEquals(0.9605f, SyncMath.driftSpeed(79), 1e-4f);
        assertEquals(1.0395f, SyncMath.driftSpeed(-79), 1e-4f);
    }

    @Test public void driftSpeed_exactlyAtTheClamp() {
        assertEquals(0.96f, SyncMath.driftSpeed(80), 1e-4f);
        assertEquals(1.04f, SyncMath.driftSpeed(-80), 1e-4f);
    }

    // ---- lead: how far ahead a follower seeks so it can buffer and start on time ----

    @Test public void leadFromReady_coversTheMeasuredSeekWithMargin() {
        assertEquals(1450L, SyncMath.leadFromReady(1000));
    }

    @Test public void leadFromReady_staysInRange() {
        assertEquals(SyncMath.MIN_LEAD_MS, SyncMath.leadFromReady(0));
        assertEquals(SyncMath.MAX_LEAD_MS, SyncMath.leadFromReady(60_000));
    }

    @Test public void missedLead_doublesUpToTheLimit() {
        assertEquals(1600L, SyncMath.missedLead(800));
        assertEquals(SyncMath.MAX_LEAD_MS, SyncMath.missedLead(SyncMath.MAX_LEAD_MS));
    }

    // ---- fold: NTP-lite offset estimate from /clock probes ----

    @Test public void fold_firstSampleBecomesValid() {
        // t0=100, tL=5000, t2=120 -> rtt=20, offset=(5000+10)-120=4890
        SyncMath.Clock c = SyncMath.fold(SyncMath.Clock.initial(), 100, 5000, 120);
        assertTrue(c.valid);
        assertEquals(20, c.bestRtt);
        assertEquals(4890, c.offset);
    }

    @Test public void fold_lowerRttReplacesEstimate() {
        SyncMath.Clock start = new SyncMath.Clock(4890, 20, true);
        // t0=0, tL=5000, t2=10 -> rtt=10 (<=20) -> offset=(5000+5)-10=4995
        SyncMath.Clock c = SyncMath.fold(start, 0, 5000, 10);
        assertEquals(10, c.bestRtt);
        assertEquals(4995, c.offset);
    }

    @Test public void fold_moderatelyHigherRttNudgesQuarter() {
        SyncMath.Clock start = new SyncMath.Clock(1000, 10, true);
        // rtt=15 (< bestRtt*2=20) -> nudge. tL chosen so off=1100: (1108+7)-15=1100
        // new offset = 1000 + (1100-1000)/4 = 1025 ; bestRtt = 10 + 10/32 = 10
        SyncMath.Clock c = SyncMath.fold(start, 0, 1108, 15);
        assertEquals(1025, c.offset);
        assertEquals(10, c.bestRtt);
        assertTrue(c.valid);
    }

    @Test public void fold_wildlyHigherRttIsIgnored() {
        SyncMath.Clock start = new SyncMath.Clock(1000, 10, true);
        // rtt=25 (>= bestRtt*2=20) -> estimate untouched
        SyncMath.Clock c = SyncMath.fold(start, 0, 9999, 25);
        assertEquals(1000, c.offset);
        assertEquals(10, c.bestRtt);
    }

    @Test public void fold_equalRttReplacesEstimate() {
        // rtt == bestRtt satisfies the "<=" cleaner-sample branch -> re-baseline outright
        SyncMath.Clock start = new SyncMath.Clock(1000, 20, true);
        // t0=0, t2=20 -> rtt=20 (==bestRtt); off=(tL+10)-20; tL=2010 -> off=2000
        SyncMath.Clock c = SyncMath.fold(start, 0, 2010, 20);
        assertEquals(2000, c.offset);
        assertEquals(20, c.bestRtt);
        assertTrue(c.valid);
    }

    @Test public void fold_exactlyTwiceBestRttIsIgnored() {
        // the nudge branch is a STRICT rtt < bestRtt*2, so rtt == 2*bestRtt falls through -> untouched
        SyncMath.Clock start = new SyncMath.Clock(1000, 10, true);
        SyncMath.Clock c = SyncMath.fold(start, 0, 9999, 20);   // rtt=20 == bestRtt*2
        assertEquals(1000, c.offset);
        assertEquals(10, c.bestRtt);
    }

    @Test public void fold_firstSampleCanBeNegativeOffset() {
        // leader clock behind ours (small tL, large t2) -> a legitimately negative offset
        SyncMath.Clock c = SyncMath.fold(SyncMath.Clock.initial(), 0, 100, 1000);
        // rtt=1000, off=(100+500)-1000 = -400
        assertTrue(c.valid);
        assertEquals(1000, c.bestRtt);
        assertEquals(-400, c.offset);
    }
}
