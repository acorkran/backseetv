package app.anancy.player;

// ============================================================================
//  How "Shuffle All" is ordered.
//
//  The old behaviour was Collections.shuffle over every episode in the library,
//  so Numberblocks 47 could follow Numberblocks 3. That show builds its maths
//  across its run, and the same goes for any show with a story; out of order
//  it is just noise.
//
//  So: the SHOWS are shuffled, their EPISODES are not. The queue rotates
//  through the shows one episode at a time -- Big Lizard 1, Bluey 1,
//  Numberblocks 1, Arthur 1, ... -- and each show stays in its own
//  chronological order the whole way down.
//
//  Two wrinkles that only show up against a real library:
//
//  1. Seven of the 28 shows here are Andy (Safari, Dinosaur, Global, Aquatic,
//     Dino Island, Wild Workouts, and the Band) and five are Fantus. A plain
//     shuffle of show names clumps them, and a quarter of the queue being the
//     same presenter back to back is the boredom this is meant to avoid.
//     Shows are grouped by FRANCHISE and the rotation is built so no two from
//     one franchise are ever adjacent -- the wrap included, since the queue
//     loops and the join between last and first is a real adjacency.
//
//  2. Shows start from wherever she got up to, not from episode 1. Without
//     that this whole feature backfires: the player has no resume, so every
//     boot would replay episode 1 of everything and she would never reach the
//     later Numberblocks. See PlayerController's progress watermarks.
//
//  Episode counts are very uneven (165 Numberblocks vs 8 Fantus Tools), so
//  short shows do run out before the end and the last stretch of a full
//  1,362-episode queue thins out to the biggest few. That is fine: the
//  watermarks mean each session starts a fresh rotation across all 28 shows,
//  so the thin tail is only reachable by watching the entire library in one
//  sitting.
//
//  Everything here is pure so it can be unit-tested without a device.
// ============================================================================

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class ShuffleAll {
    private ShuffleAll() {}

    /** Shows that belong together for spacing purposes: "Andy's Safari Adventures" and
        "Andy and the Band" both key to "andy", "The Fantus Show" to "fantus".

        Deliberately crude -- the first meaningful word, minus a leading article and any
        possessive. A false grouping only buys two unrelated shows some extra distance from
        each other, which costs nothing; a MISSED grouping is the bug being fixed. */
    static String franchiseOf(String show) {
        if (show == null) return "";
        String s = show.toLowerCase(Locale.ROOT).replace('.', ' ').replace('_', ' ').trim();
        if (s.startsWith("the ")) s = s.substring(4).trim();
        int sp = s.indexOf(' ');
        String first = sp > 0 ? s.substring(0, sp) : s;
        if (first.endsWith("'s")) first = first.substring(0, first.length() - 2);
        else if (first.endsWith("s'")) first = first.substring(0, first.length() - 1);
        return first;
    }

    /** Order the shows so each franchise is spread evenly down the rotation, and no two of one
        franchise are ever adjacent (wrap included).

        "Never adjacent" on its own is not enough, and the first attempt at this proved it: a
        greedy largest-franchise-first pass produced Andy, Fantus, Andy, Fantus, Andy, Fantus,
        Andy, Fantus, Andy for the opening nine. Technically no two Andys touched -- and it was
        still nine straight presenter-led episodes, which is the boredom the rule exists to stop.

        Spacing franchises at an even stride through a random ordering was tried next and is not
        enough either: it only spreads them ON AVERAGE, and with 21 other shows falling across
        Andy's 7 gaps an unlucky draw still bunched three Andys into six slots.

        What works is dealing into COLUMNS. Take m = the size of the biggest franchise (7), make
        m buckets, list the shows biggest-franchise-first, and deal them round-robin so show t
        lands in bucket t % m. Each franchise has at most m members and its members are
        contiguous in that list, so they always land in m DIFFERENT buckets. Reading the buckets
        out end to end then puts Andy exactly every fourth slot, Fantus likewise, and the sixteen
        one-off shows in between -- no two of a franchise adjacent, by construction, and evenly
        spread rather than merely separated.

        The variety comes from WHICH show fills each slot: the franchises are shuffled among
        equal-sized peers and the one-off shows are shuffled outright, so the pattern is steady
        but the running order is different every time. */
    static List<String> rotation(List<String> shows, Random rnd) {
        if (shows == null) return new ArrayList<>();
        if (shows.size() < 2) return new ArrayList<>(shows);

        LinkedHashMap<String, List<String>> byFranchise = new LinkedHashMap<>();
        List<String> pool = new ArrayList<>(shows);
        Collections.shuffle(pool, rnd);                       // the "shuffled" part: which show leads
        for (String s : pool) {
            String k = franchiseOf(s);
            List<String> l = byFranchise.get(k);
            if (l == null) { l = new ArrayList<>(); byFranchise.put(k, l); }
            l.add(s);
        }

        // Biggest franchise first. The shuffle above already randomised the order of the
        // same-sized ones, and a stable sort keeps that.
        List<List<String>> groups = new ArrayList<>(byFranchise.values());
        Collections.sort(groups, (a, b) -> Integer.compare(b.size(), a.size()));

        int m = groups.get(0).size();                         // widest franchise = number of columns
        List<List<String>> buckets = new ArrayList<>(m);
        for (int i = 0; i < m; i++) buckets.add(new ArrayList<String>());
        int t = 0;
        for (List<String> g : groups) for (String s : g) buckets.get(t++ % m).add(s);

        List<String> out = new ArrayList<>(shows.size());
        for (List<String> b : buckets) out.addAll(b);

        // Guaranteed clean for any library where the biggest franchise is at most half the
        // shows. Beyond that no arrangement exists, so this is a safety net, not the mechanism.
        separate(out);
        return out;
    }

    /** Pull apart any two same-franchise shows that ended up side by side, the wrap included.

        Swaps the second of the pair with some show whose franchise fits in both places. Bounded
        by a couple of passes: a library dominated by one franchise has no valid arrangement at
        all, and spinning forever over it would be worse than handing back a slightly clumped
        order that still plays. */
    private static void separate(List<String> out) {
        int n = out.size();
        if (n < 3) return;
        for (int pass = 0; pass < 2; pass++) {
            boolean clean = true;
            for (int i = 0; i < n; i++) {
                String a = franchiseOf(out.get(i));
                String b = franchiseOf(out.get((i + 1) % n));   // % n so the wrap is checked too
                if (!a.equals(b)) continue;
                clean = false;
                int at = (i + 1) % n;
                for (int off = 2; off < n - 1; off++) {
                    int j = (at + off) % n;
                    String cand = franchiseOf(out.get(j));
                    String jPrev = franchiseOf(out.get((j - 1 + n) % n));
                    String jNext = franchiseOf(out.get((j + 1) % n));
                    String aNext = franchiseOf(out.get((at + 1) % n));
                    // cand must sit happily where the clash is, and the clashing show must sit
                    // happily where cand came from.
                    if (cand.equals(a) || cand.equals(aNext)) continue;
                    if (jPrev.equals(b) || jNext.equals(b)) continue;
                    Collections.swap(out, at, j);
                    break;
                }
            }
            if (clean) return;
        }
    }

    /** Build the play order.

        shows    show name -> that show's episodes, already in chronological order
        startAt  show name -> index of the episode she is up to (absent/out of range = 0)

        Each show is emitted from its start point onward, wrapping past the end of its own run,
        and the shows are visited in rotation. The result is one episode of each show in turn,
        every show advancing through its own chronology, and each show contributing exactly as
        many episodes as it has. */
    static <T> List<T> interleave(LinkedHashMap<String, List<T>> shows, Map<String, Integer> startAt, Random rnd) {
        List<T> out = new ArrayList<>();
        if (shows == null || shows.isEmpty()) return out;

        List<String> order = rotation(new ArrayList<>(shows.keySet()), rnd);
        int longest = 0;
        for (List<T> v : shows.values()) longest = Math.max(longest, v == null ? 0 : v.size());

        for (int round = 0; round < longest; round++) {
            for (String name : order) {
                List<T> eps = shows.get(name);
                if (eps == null || eps.isEmpty() || round >= eps.size()) continue;   // this show is spent
                int from = 0;
                if (startAt != null) {
                    Integer s = startAt.get(name);
                    // A stale watermark (episodes deleted off the card) must not drop the show
                    // or throw -- fold it back into range and carry on.
                    if (s != null && s > 0) from = s % eps.size();
                }
                out.add(eps.get((from + round) % eps.size()));
            }
        }
        return out;
    }
}
