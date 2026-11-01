package app.anancy.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

/** The real card: 28 shows, 7 of them Andy, 5 of them Fantus, 1362 episodes. */
public class ShuffleAllTest {

    private static final String[] ANDY = {
        "Andy and the Band", "Andy's Aquatic Adventures", "Andy's Dino Island",
        "Andy's Dinosaur Adventures", "Andy's Global Adventures",
        "Andy's Safari Adventures", "Andy's Wild Workouts" };
    private static final String[] FANTUS = {
        "Fantus and the Animals", "Fantus and the Kitchen Gadgets",
        "Fantus and the Machines", "Fantus and the Tools", "The Fantus Show" };
    private static final String[] REST = {
        "Arthur", "BeddyByes", "Big.Lizard", "Bluey", "Daniel Tiger", "Fizzy.And.Suds",
        "Franklin", "Ginger.And.The.Vegesaurs", "Little Bear", "Mini Kids", "Ms Rachel",
        "Numberblocks", "Piri.Penguins", "Ready.Steady.Wiggle",
        "Songs.From.The.Bright.Side", "Stan.Can" };

    private static List<String> realLibrary() {
        List<String> all = new ArrayList<>();
        all.addAll(Arrays.asList(ANDY));
        all.addAll(Arrays.asList(FANTUS));
        all.addAll(Arrays.asList(REST));
        return all;
    }

    // --- franchise detection -------------------------------------------------

    @Test public void allSevenAndyShowsShareAFranchise() {
        for (String s : ANDY) assertEquals(s, "andy", ShuffleAll.franchiseOf(s));
    }

    @Test public void allFiveFantusShowsShareAFranchise_includingTheLeadingThe() {
        for (String s : FANTUS) assertEquals(s, "fantus", ShuffleAll.franchiseOf(s));
    }

    @Test public void dotSeparatedNamesSplitOnTheDot() {
        assertEquals("big", ShuffleAll.franchiseOf("Big.Lizard"));
        assertEquals("ready", ShuffleAll.franchiseOf("Ready.Steady.Wiggle"));
    }

    @Test public void unrelatedShowsDoNotCollide() {
        assertNotEquals(ShuffleAll.franchiseOf("Bluey"), ShuffleAll.franchiseOf("Arthur"));
        assertNotEquals(ShuffleAll.franchiseOf("Little Bear"), ShuffleAll.franchiseOf("Mini Kids"));
    }

    @Test public void nullAndEmptyAreSurvivable() {
        assertEquals("", ShuffleAll.franchiseOf(null));
        assertEquals("", ShuffleAll.franchiseOf(""));
    }

    // --- rotation: no franchise twice in a row -------------------------------

    @Test public void noTwoAndysEverTouch_overManySeeds() {
        for (int seed = 0; seed < 500; seed++) {
            List<String> order = ShuffleAll.rotation(realLibrary(), new Random(seed));
            assertEquals("seed " + seed + " lost or duplicated shows", 28, order.size());
            assertEquals("seed " + seed, 28, new java.util.HashSet<>(order).size());
            for (int i = 1; i < order.size(); i++) {
                assertNotEquals("seed " + seed + " put " + order.get(i - 1) + " next to " + order.get(i),
                        ShuffleAll.franchiseOf(order.get(i - 1)), ShuffleAll.franchiseOf(order.get(i)));
            }
        }
    }

    /** The queue loops, so the join from the last show back to the first counts. */
    @Test public void theWrapIsCleanToo_overManySeeds() {
        for (int seed = 0; seed < 500; seed++) {
            List<String> order = ShuffleAll.rotation(realLibrary(), new Random(seed));
            assertNotEquals("seed " + seed + " wraps " + order.get(order.size() - 1) + " onto " + order.get(0),
                    ShuffleAll.franchiseOf(order.get(order.size() - 1)),
                    ShuffleAll.franchiseOf(order.get(0)));
        }
    }

    /** Non-adjacency alone is not enough and the first cut of this proved it: a greedy pass gave
        Andy, Fantus, Andy, Fantus, Andy, Fantus, Andy, Fantus, Andy for the opening nine. No two
        Andys touched, and it was still nine straight presenter-led shows. Seven Andys across 28
        slots is one every four, so a window of six should never hold more than two. */
    @Test public void franchisesAreSpreadOut_notMerelyNonAdjacent() {
        final int WINDOW = 6, MAX_IN_WINDOW = 2;
        for (int seed = 0; seed < 300; seed++) {
            List<String> order = ShuffleAll.rotation(realLibrary(), new Random(seed));
            for (int i = 0; i + WINDOW <= order.size(); i++) {
                Map<String, Integer> seen = new HashMap<>();
                for (int j = i; j < i + WINDOW; j++) {
                    String f = ShuffleAll.franchiseOf(order.get(j));
                    seen.put(f, seen.containsKey(f) ? seen.get(f) + 1 : 1);
                }
                for (Map.Entry<String, Integer> e : seen.entrySet())
                    assertTrue("seed " + seed + ": " + e.getValue() + " x " + e.getKey()
                                    + " inside 6 shows at " + i + " -> " + order.subList(i, i + WINDOW),
                            e.getValue() <= MAX_IN_WINDOW);
            }
        }
    }

    /** The specific regression: the opening should not be an Andy/Fantus wall. */
    @Test public void theOpeningIsNotAllFranchiseShows() {
        for (int seed = 0; seed < 300; seed++) {
            List<String> order = ShuffleAll.rotation(realLibrary(), new Random(seed));
            int grouped = 0;
            for (int i = 0; i < 9; i++) {
                String f = ShuffleAll.franchiseOf(order.get(i));
                if (f.equals("andy") || f.equals("fantus")) grouped++;
            }
            assertTrue("seed " + seed + ": " + grouped + "/9 of the opening is Andy or Fantus -> "
                            + order.subList(0, 9), grouped <= 5);
        }
    }

    @Test public void theLeadShowActuallyVaries() {
        java.util.Set<String> leads = new java.util.HashSet<>();
        for (int seed = 0; seed < 100; seed++) leads.add(ShuffleAll.rotation(realLibrary(), new Random(seed)).get(0));
        assertTrue("shuffling the shows should not always lead with the same one, got " + leads,
                leads.size() > 3);
    }

    @Test public void anImpossibleLibraryStillReturnsEverything() {
        List<String> andyOnly = new ArrayList<>(Arrays.asList(ANDY));
        List<String> order = ShuffleAll.rotation(andyOnly, new Random(1));
        assertEquals(7, order.size());
        assertEquals(7, new java.util.HashSet<>(order).size());
    }

    @Test public void degenerateInputs() {
        assertTrue(ShuffleAll.rotation(null, new Random(1)).isEmpty());
        assertTrue(ShuffleAll.rotation(new ArrayList<String>(), new Random(1)).isEmpty());
        assertEquals(Arrays.asList("Bluey"), ShuffleAll.rotation(Arrays.asList("Bluey"), new Random(1)));
    }

    // --- interleave: chronological within a show -----------------------------

    private static LinkedHashMap<String, List<String>> lib(Object... pairs) {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String show = (String) pairs[i];
            int n = (Integer) pairs[i + 1];
            List<String> eps = new ArrayList<>();
            for (int e = 1; e <= n; e++) eps.add(show + "#" + e);
            m.put(show, eps);
        }
        return m;
    }

    private static int epNum(String tag) { return Integer.parseInt(tag.substring(tag.indexOf('#') + 1)); }
    private static String showOf(String tag) { return tag.substring(0, tag.indexOf('#')); }

    @Test public void everyEpisodeAppearsExactlyOnce() {
        LinkedHashMap<String, List<String>> shows = lib("Numberblocks", 165, "Bluey", 152,
                "Fantus and the Tools", 8, "Andy's Safari Adventures", 38);
        List<String> q = ShuffleAll.interleave(shows, null, new Random(7));
        assertEquals(165 + 152 + 8 + 38, q.size());
        assertEquals(q.size(), new java.util.HashSet<>(q).size());
    }

    @Test public void withinAShowTheOrderIsStrictlyChronological() {
        LinkedHashMap<String, List<String>> shows = lib("Numberblocks", 165, "Bluey", 152,
                "Arthur", 85, "Fantus and the Tools", 8);
        for (int seed = 0; seed < 50; seed++) {
            List<String> q = ShuffleAll.interleave(shows, null, new Random(seed));
            Map<String, Integer> lastSeen = new HashMap<>();
            for (String tag : q) {
                String s = showOf(tag);
                Integer prev = lastSeen.get(s);
                if (prev != null) assertEquals("seed " + seed + ": " + s + " went backwards",
                        prev.intValue() + 1, epNum(tag));
                lastSeen.put(s, epNum(tag));
            }
        }
    }

    /** The thing actually asked for: one episode of each show in turn at the start. */
    @Test public void theOpeningIsOneEpisodeOfEachShow() {
        LinkedHashMap<String, List<String>> shows = new LinkedHashMap<>();
        for (String s : realLibrary()) {
            List<String> eps = new ArrayList<>();
            for (int e = 1; e <= 20; e++) eps.add(s + "#" + e);
            shows.put(s, eps);
        }
        List<String> q = ShuffleAll.interleave(shows, null, new Random(3));
        java.util.Set<String> firstRound = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 28; i++) {
            assertEquals("every show should open on episode 1", 1, epNum(q.get(i)));
            firstRound.add(showOf(q.get(i)));
        }
        assertEquals("the first 28 items should be 28 different shows", 28, firstRound.size());
    }

    @Test public void shortShowsDropOutInsteadOfRepeating() {
        LinkedHashMap<String, List<String>> shows = lib("Long", 10, "Short", 2);
        List<String> q = ShuffleAll.interleave(shows, null, new Random(1));
        assertEquals(12, q.size());
        int shorts = 0;
        for (String t : q) if (showOf(t).equals("Short")) shorts++;
        assertEquals("a 2-episode show must contribute 2 episodes, not be padded out", 2, shorts);
    }

    // --- interleave: resuming where she got to -------------------------------

    @Test public void eachShowResumesFromItsWatermark() {
        LinkedHashMap<String, List<String>> shows = lib("Numberblocks", 165, "Bluey", 152, "Arthur", 85);
        Map<String, Integer> at = new HashMap<>();
        at.put("Numberblocks", 46);     // she is up to episode 47 (0-indexed 46)
        at.put("Bluey", 10);
        List<String> q = ShuffleAll.interleave(shows, at, new Random(5));
        Map<String, String> firstOf = new HashMap<>();
        for (String t : q) if (!firstOf.containsKey(showOf(t))) firstOf.put(showOf(t), t);
        assertEquals("Numberblocks#47", firstOf.get("Numberblocks"));
        assertEquals("Bluey#11", firstOf.get("Bluey"));
        assertEquals("no watermark means start at the beginning", "Arthur#1", firstOf.get("Arthur"));
    }

    @Test public void aResumedShowWrapsPastItsEndAndStillPlaysEveryEpisode() {
        LinkedHashMap<String, List<String>> shows = lib("Short", 5);
        Map<String, Integer> at = new HashMap<>();
        at.put("Short", 3);
        List<String> q = ShuffleAll.interleave(shows, at, new Random(1));
        assertEquals(Arrays.asList("Short#4", "Short#5", "Short#1", "Short#2", "Short#3"), q);
    }

    @Test public void aStaleWatermarkPastTheEndDoesNotCrashOrDropTheShow() {
        LinkedHashMap<String, List<String>> shows = lib("Trimmed", 5);
        Map<String, Integer> at = new HashMap<>();
        at.put("Trimmed", 900);                       // episodes were deleted off the card
        List<String> q = ShuffleAll.interleave(shows, at, new Random(1));
        assertEquals(5, q.size());
        assertEquals(5, new java.util.HashSet<>(q).size());
    }

    @Test public void emptyLibraryIsEmptyNotNull() {
        assertTrue(ShuffleAll.interleave(new LinkedHashMap<String, List<String>>(), null, new Random(1)).isEmpty());
        assertTrue(ShuffleAll.interleave(null, null, new Random(1)).isEmpty());
    }

    /** A show folder that scanned to nothing must not wedge the rotation. */
    @Test public void anEmptyShowIsSkipped() {
        LinkedHashMap<String, List<String>> shows = lib("Real", 3);
        shows.put("Empty", new ArrayList<String>());
        List<String> q = ShuffleAll.interleave(shows, null, new Random(1));
        assertEquals(3, q.size());
    }
}
