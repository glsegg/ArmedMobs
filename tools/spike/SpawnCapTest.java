import com.gfl.tarkovscav.world.SpawnCapMath;

/**
 * Drives the SHIPPED city faction-cap arithmetic ({@code com.gfl.tarkovscav.world.SpawnCapMath}).
 *
 * <p>Same idea as GrenadeBallisticsTest and CaptureTest: the class under test is JDK-only, so this compiles the
 * real source and exercises the real rule. A copy of the formula in here would prove nothing.</p>
 *
 * <p>Runs with no game: {@code java -cp tools/spike/out SpawnCapTest} (and it is in the suite's Java test
 * list). Exits non-zero if any check fails.</p>
 */
public final class SpawnCapTest {
    private static int failures = 0;
    private static int checks = 0;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void eq(int actual, int expected, String label) {
        check(actual == expected, label + "  (got " + actual + ", want " + expected + ")");
    }

    private static void bool(boolean actual, boolean expected, String label) {
        check(actual == expected, label + "  (got " + actual + ", want " + expected + ")");
    }

    public static void main(String[] args) {
        System.out.println("city faction cap arithmetic (SpawnCapMath, the shipped source)");

        System.out.println("1. effectiveCount = alive + allowed-this-window, both floored at 0");
        eq(SpawnCapMath.effectiveCount(0, 0), 0, "empty city, nothing let through");
        eq(SpawnCapMath.effectiveCount(5, 0), 5, "five alive, none let through yet");
        eq(SpawnCapMath.effectiveCount(5, 3), 8, "five alive plus three already allowed");
        eq(SpawnCapMath.effectiveCount(0, 12), 12, "a burst of twelve inside one window");
        eq(SpawnCapMath.effectiveCount(-5, 2), 2, "a negative live count cannot cancel the accepts");
        eq(SpawnCapMath.effectiveCount(5, -2), 5, "a negative accept count cannot cancel the living");

        System.out.println("");
        System.out.println("2. overCap is 'at most N alive': the Nth is allowed, the N+1th is not");
        bool(SpawnCapMath.overCap(0, 0, 12), false, "cap 12, empty city: allowed");
        bool(SpawnCapMath.overCap(11, 0, 12), false, "cap 12, eleven alive: the twelfth is allowed");
        bool(SpawnCapMath.overCap(12, 0, 12), true, "cap 12, twelve alive: the thirteenth is refused");
        bool(SpawnCapMath.overCap(11, 1, 12), true, "cap 12, eleven alive plus one allowed this window");
        bool(SpawnCapMath.overCap(10, 2, 12), true, "cap 12, ten alive plus two allowed this window");
        bool(SpawnCapMath.overCap(13, 0, 12), true, "cap 12, already over (a lowered cap mid-window)");
        bool(SpawnCapMath.overCap(999, 0, 12), true, "cap 12, absurdly over");
        bool(SpawnCapMath.overCap(0, 1, 1), true, "cap 1: the first unit is in, the second is not");
        bool(SpawnCapMath.overCap(0, 0, 1), false, "cap 1 on an empty city: still allowed");
        bool(SpawnCapMath.overCap(1, 0, 1), true, "cap 1 with one alive");

        System.out.println("");
        System.out.println("3. a cap of 0 or less means NO cap (a hand-edited toml cannot empty the world)");
        bool(SpawnCapMath.overCap(0, 0, 0), false, "cap 0: allowed");
        bool(SpawnCapMath.overCap(999, 999, 0), false, "cap 0 with everything alive: still allowed");
        bool(SpawnCapMath.overCap(999, 999, -5), false, "cap -5: allowed");
        bool(SpawnCapMath.overCap(5, 0, -1), false, "cap -1: allowed");

        System.out.println("");
        System.out.println("4. a death frees a slot, and the recount folds the window back in");
        eq(SpawnCapMath.effectiveCount(11, 0), 11, "eleven alive after a death");
        bool(SpawnCapMath.overCap(11, 0, 12), false, "so the next spawn is allowed again");
        // simulates the recount: live += accepted, accepted = 0
        eq(SpawnCapMath.effectiveCount(SpawnCapMath.effectiveCount(0, 0) + 12, 0), 12, "window folded in");

        System.out.println("");
        System.out.println("5. the invariant that matters: after any number of admissions and recounts, alive <= cap");
        for (int cap : new int[]{1, 2, 12, 128}) {
            int live = 0;
            int accepted = 0;
            int admitted = 0;
            int overAtFold = -1;
            for (int attempt = 0; attempt < 200; attempt++) {
                if (!SpawnCapMath.overCap(live, accepted, cap)) {
                    accepted++;
                    admitted++;
                }
                if (attempt % 5 == 4) {
                    live = SpawnCapMath.effectiveCount(live, accepted);
                    accepted = 0;
                    if (live > cap) {
                        overAtFold = live;
                        break;
                    }
                }
            }
            check(overAtFold < 0, "cap " + cap + ": never exceeded after folding (admitted " + admitted
                    + " of 200 attempts, final live " + live + ")");
            check(live == cap, "cap " + cap + ": settled exactly at the cap");
        }

        System.out.println("");
        System.out.println("6. the window is treated as at least one tick");
        bool(SpawnCapMath.stale(100, 100, 20), false, "counted this tick: fresh");
        bool(SpawnCapMath.stale(119, 100, 20), false, "nineteen ticks later: still fresh");
        bool(SpawnCapMath.stale(120, 100, 20), true, "twenty ticks later: recount");
        bool(SpawnCapMath.stale(200, 100, 20), true, "much later: recount");
        bool(SpawnCapMath.stale(100, 100, 0), false, "window 0 on the same tick: fresh");
        bool(SpawnCapMath.stale(101, 100, 0), true, "window 0 one tick later: recount (0 is clamped to 1)");
        bool(SpawnCapMath.stale(101, 100, -7), true, "a negative window is clamped to one tick too");
        bool(SpawnCapMath.stale(100, Long.MIN_VALUE, 20), true, "never counted: always stale (first use)");
        bool(SpawnCapMath.stale(Long.MIN_VALUE, Long.MIN_VALUE, 20), true, "never counted at the very bottom of the clock");
        bool(SpawnCapMath.stale(5, 100, 20), true, "the clock went backwards (a level loaded from an older save): recount");
        bool(SpawnCapMath.stale(100, 100, Integer.MAX_VALUE), false, "an absurd window is still a window");

        System.out.println("");
        if (failures > 0) {
            System.out.println(failures + " spawn-cap check(s) FAILED (" + checks + " run)");
            System.exit(1);
        }
        System.out.println(checks + " spawn-cap check(s) passed: the cap is 'at most N alive per city per "
                + "faction', a death frees a slot, 0 means uncapped, and the window never lets the count drift "
                + "above the cap");
    }
}
