package com.kidstv.kidodi.remote;

// ============================================================================
//  Pure command-classification + leader-derivation for the phone remote,
//  factored out of RemoteService so the routing rules can be unit-tested.
//
//  Why this exists: in a synced group, PLAYBACK commands drive the leader alone
//  (followers mirror it), while PER-SCREEN commands (power/light/sleep/volume/…)
//  must hit EVERY member. Getting that split wrong is what once left the Power
//  button napping only the leader. These predicates are the single source of
//  truth for that decision; deriveLeader() is the "never-stored, can't-drift"
//  leader rule. All deterministic and side-effect free.
// ============================================================================

import java.util.List;
import java.util.Set;

final class CommandRouter {
    private CommandRouter() {}

    /** Toggles are debounced against spam (rapid taps could flip screens into opposite states). */
    static boolean isToggleCmd(String c) {
        return "power".equals(c) || "playpause".equals(c) || "mute".equals(c);
    }

    /** Volume-ish commands (routed to the leader alone in leader-only audio mode). */
    static boolean isVolCmd(String c) {
        return c != null && (c.equals("volup") || c.equals("voldown") || c.equals("mute") || c.startsWith("vol"));
    }

    /** Content/position commands that drive the sync LEADER (followers mirror). Everything else is per-screen. */
    static boolean isPlaybackCmd(String c) {
        if (c == null) return false;
        switch (c) {
            case "play": case "pause": case "playpause": case "next": case "prev":
            case "seeknear": case "shuffle": case "loop": return true;
        }
        return c.startsWith("jump") || c.startsWith("seek") || c.startsWith("playlist")
                || c.startsWith("enqueue") || c.startsWith("dequeue") || c.startsWith("savequeue");
    }

    /** Queries have a single answer — ask the representative only, never broadcast. */
    static boolean isQueryCmd(String c) {
        if (c == null) return false;
        return c.equals("list") || c.equals("queue") || c.equals("conn") || c.equals("cfg")
                || c.startsWith("shows") || c.startsWith("eps:");
    }

    /**
     * Derive the sync-group leader from the current selection — the leader is
     * never stored, so it cannot drift out of the group. Preference order:
     * a media host in the group, else the first reachable screen, else the first
     * in order. Returns null only for an empty group.
     */
    static String deriveLeader(List<String> order, Set<String> hostIds, Set<String> reachable) {
        if (order == null || order.isEmpty()) return null;
        for (String id : order) if (hostIds != null && hostIds.contains(id)) return id;    // a media host is the natural leader
        for (String id : order) if (reachable != null && reachable.contains(id)) return id; // else the first we can reach
        return order.get(0);
    }
}
