package app.anancy.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the pure text formatters extracted from RemoteActivity.
 * These lock in the exact strings the remote shows for playback time + titles.
 */
public class FmtTest {

    // ---- mmss: ms -> "m:ss" (minutes not capped, negatives NOT guarded — behaviour preserved) ----

    @Test public void mmss_zero() {
        assertEquals("0:00", Fmt.mmss(0));
    }

    @Test public void mmss_underOneMinute() {
        assertEquals("0:59", Fmt.mmss(59_000));
        assertEquals("0:05", Fmt.mmss(5_000));
    }

    @Test public void mmss_wholeAndPartialMinutes() {
        assertEquals("1:00", Fmt.mmss(60_000));
        assertEquals("1:30", Fmt.mmss(90_000));
    }

    @Test public void mmss_overOneHourStaysInMinutes() {
        // no h:mm:ss rollover — 1h1m1s reads as 61:01
        assertEquals("61:01", Fmt.mmss(3_661_000));
    }

    @Test public void mmss_truncatesSubSecond() {
        assertEquals("0:01", Fmt.mmss(1_999));
    }

    @Test public void mmss_negativeIsUnguarded() {
        // documents the current (unclamped) behaviour: callers only feed >=0 positions
        assertEquals("0:-1", Fmt.mmss(-1_000));
    }

    // ---- pretty: clean a media filename for display ----

    @Test public void pretty_stripsExtensionAndDots() {
        assertEquals("Bluey S01E01", Fmt.pretty("Bluey.S01E01.mp4"));
    }

    @Test public void pretty_mapsFullwidthSlashAndTrims() {
        assertEquals("Peppa/Pig", Fmt.pretty("Peppa⧸Pig.mkv"));
        assertEquals("Daniel Tiger", Fmt.pretty("  Daniel.Tiger.mp4"));
    }

    @Test public void pretty_leavesNonMediaDotsAsSpaces() {
        // ".here" isn't a known media extension, so it isn't stripped — just dot->space
        assertEquals("no extension here", Fmt.pretty("no extension.here"));
    }

    @Test public void pretty_emptyStaysEmpty() {
        assertEquals("", Fmt.pretty(""));
    }
}
