package com.kidstv.kidodi.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for the command-routing rules. These lock in the sync-group split
 * that a past bug got wrong: PLAYBACK drives the leader alone (followers mirror),
 * PER-SCREEN commands (power/light/sleep/volume/…) must hit every member.
 */
public class CommandRouterTest {

    @Test public void playbackCommands_driveTheLeader() {
        for (String c : new String[]{"play", "pause", "playpause", "next", "prev", "seeknear", "shuffle", "loop"})
            assertTrue(c + " should be playback", CommandRouter.isPlaybackCmd(c));
        // prefix-matched families
        for (String c : new String[]{"jump5", "seek12000", "playlistShuffle All", "enqueue:3", "dequeue:2", "savequeue:Bedtime"})
            assertTrue(c + " should be playback", CommandRouter.isPlaybackCmd(c));
    }

    @Test public void perScreenCommands_areNotPlayback() {
        // the regression class: these MUST NOT be treated as leader-only
        for (String c : new String[]{"power", "light", "sleep", "toddler", "volup", "voldown", "mute", "cfg:x=1"})
            assertFalse(c + " should be per-screen", CommandRouter.isPlaybackCmd(c));
        assertFalse(CommandRouter.isPlaybackCmd(null));
    }

    @Test public void toggleCommands_areDebounced() {
        assertTrue(CommandRouter.isToggleCmd("power"));
        assertTrue(CommandRouter.isToggleCmd("playpause"));
        assertTrue(CommandRouter.isToggleCmd("mute"));
        assertFalse(CommandRouter.isToggleCmd("next"));
        assertFalse(CommandRouter.isToggleCmd("volup"));
        assertFalse(CommandRouter.isToggleCmd(null));
    }

    @Test public void volumeCommands_detected() {
        for (String c : new String[]{"volup", "voldown", "mute", "vol50"})
            assertTrue(c + " should be volume", CommandRouter.isVolCmd(c));
        assertFalse(CommandRouter.isVolCmd("play"));
        assertFalse(CommandRouter.isVolCmd(null));
    }

    @Test public void queryCommands_askRepresentativeOnly() {
        for (String c : new String[]{"list", "queue", "conn", "cfg", "shows", "shows:3", "eps:Bluey:0"})
            assertTrue(c + " should be a query", CommandRouter.isQueryCmd(c));
        assertFalse(CommandRouter.isQueryCmd("play"));
        assertFalse(CommandRouter.isQueryCmd(null));
    }

    // ---- deriveLeader: host > first-reachable > first; never stored, so it can't drift ----

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    @Test public void deriveLeader_prefersAMediaHost() {
        List<String> order = Arrays.asList("a", "b", "c");
        assertEquals("b", CommandRouter.deriveLeader(order, set("b"), set("a", "b", "c")));
    }

    @Test public void deriveLeader_hostBeatsAnEarlierReachableScreen() {
        List<String> order = Arrays.asList("a", "b");
        // 'a' is first and reachable, but 'b' is the media host -> b leads
        assertEquals("b", CommandRouter.deriveLeader(order, set("b"), set("a", "b")));
    }

    @Test public void deriveLeader_fallsBackToFirstReachable() {
        List<String> order = Arrays.asList("a", "b", "c");
        assertEquals("b", CommandRouter.deriveLeader(order, set(), set("b", "c")));
    }

    @Test public void deriveLeader_fallsBackToFirstInOrder() {
        List<String> order = Arrays.asList("a", "b", "c");
        assertEquals("a", CommandRouter.deriveLeader(order, set(), set()));
    }

    @Test public void deriveLeader_emptyGroupIsNull() {
        assertNull(CommandRouter.deriveLeader(Collections.<String>emptyList(), set(), set()));
        assertNull(CommandRouter.deriveLeader(null, set(), set()));
    }
}
