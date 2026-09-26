import com.gfl.tarkovscav.world.CapturePools;

/**
 * The city-capture pool arithmetic, driven with no game present (README 7p).
 *
 * <h2>What this proves, and what it does not</h2>
 * <p>It runs the <b>shipped</b> {@link CapturePools} - the same source the server calls - through the size
 * formula, the clamps, the drain floor, the exact-zero capture boundary and the ledger key shape. It proves
 * the arithmetic; it cannot prove the overworld gate, the three spawn vetoes or the HUD, which need a running
 * world and are asserted structurally by {@code tools/selftest_capture.js}.</p>
 *
 * <h2>Why the class has to stay Minecraft-free</h2>
 * <p>The suite compiles its spikes with {@code javac}, a handful of Minecraft stubs and exactly one mod source
 * per feature (this is how {@code GrenadeBallisticsTest} gets the real grenade solver). {@code CapturePools}
 * imports nothing but the JDK, so this test compiles with no stubs at all and still exercises the real code
 * rather than a copy of the formula.</p>
 *
 * <p>Output convention, so the Node gate can fold these into its own report: one {@code PASS <label>} or
 * {@code FAIL <label>} line per case, then one summary line. Exit code 0 only when everything passed.</p>
 */
public final class CaptureTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        // ---- the size formula: clamp(poolMin + buildings * poolPerBuilding, poolMin, poolMax) -------------
        size(1, 20, 100, 2, 22, "one building: 20 + 1*2");
        size(4, 20, 100, 2, 28, "four buildings: 20 + 4*2 = 28 (the shipped example)");
        size(12, 20, 100, 2, 44, "twelve buildings: 20 + 24");
        size(40, 20, 100, 2, 100, "forty buildings: 20 + 80 is exactly the cap");
        size(100, 20, 100, 2, 100, "a hundred buildings: clamped at poolMax");
        size(0, 20, 100, 2, 20, "no buildings: clamped at poolMin");
        size(-5, 20, 100, 2, 20, "a negative building count cannot go below the floor");
        size(4, 20, 100, 0, 20, "poolPerBuilding 0 means every city opens at the floor");
        size(4, 20, 100, -3, 20, "a negative poolPerBuilding cannot subtract men");
        size(4, 20, 100, 1000, 100, "an absurd poolPerBuilding is clamped, not overflowed");
        size(100000, 20, 400, 20, 400, "a huge building count is clamped rather than overflowing");
        size(4, 4, 4, 2, 4, "poolMin == poolMax pins the pool");
        // A hand-edited toml can put the floor above the ceiling; the bounds are normalised so the result is
        // never negative and never outside [min(poolMin,poolMax), max(poolMin,poolMax)].
        size(4, 100, 20, 2, 100, "swapped min/max is normalised (value 108 -> the 100 ceiling)");
        size(0, 100, 20, 2, 100, "swapped min/max, no buildings -> the 100 floor of the normalised range");
        size(0, 0, 0, 5, 0, "an all-zero config can produce a zero pool - the caller's ledger keeps it sane");

        // ---- the drain: floor at zero, never below ------------------------------------------------------
        equal(CapturePools.drain(28, 1), 27, "drainPerKill 1 removes exactly one");
        equal(CapturePools.drain(28, 3), 25, "drainPerKill 3 removes three");
        equal(CapturePools.drain(1, 1), 0, "the last man takes the pool to exactly zero");
        equal(CapturePools.drain(0, 1), 0, "a spent pool stays at zero");
        equal(CapturePools.drain(0, 10), 0, "a spent pool stays at zero whatever the drain");
        equal(CapturePools.drain(5, 10), 0, "a drain larger than the pool floors at zero");
        equal(CapturePools.drain(2, 5), 0, "an over-drain never goes negative");
        equal(CapturePools.drain(10, 0), 10, "drainPerKill 0 is no drain, not a heal");
        equal(CapturePools.drain(10, -4), 10, "a negative drainPerKill is no drain, not a heal");

        // ---- the capture decision is EXACTLY zero -------------------------------------------------------
        check(!CapturePools.isCaptured(1), "a pool of 1 is not captured");
        check(CapturePools.isCaptured(0), "a pool of exactly 0 IS captured");
        check(CapturePools.isCaptured(-3), "a negative pool (a hand-edited ledger) still counts as captured");

        // ---- a whole small city, kill by kill -----------------------------------------------------------
        int pool = CapturePools.poolSize(4, 20, 100, 2);
        equal(pool, 28, "the simulated city opens at 28");
        int capturedAt = -1;
        for (int kill = 1; kill <= 28; kill++) {
            pool = CapturePools.drain(pool, 1);
            if (CapturePools.isCaptured(pool) && capturedAt < 0) {
                capturedAt = kill;
            }
        }
        equal(pool, 0, "28 kills take a 28-man pool to zero");
        equal(capturedAt, 28, "the capture is decided on the 28th kill, not the 27th");
        for (int extra = 0; extra < 50; extra++) {
            pool = CapturePools.drain(pool, 1);
        }
        equal(pool, 0, "fifty more deaths on a spent pool leave it at zero (never negative)");
        check(CapturePools.isCaptured(pool), "and it is still captured");

        // ---- the ledger key shape ------------------------------------------------------------------------
        equal("minecraft:overworld|structure/tarkovscav:city_small/10,-4,10|village",
                CapturePools.poolKey("minecraft:overworld", "structure/tarkovscav:city_small/10,-4,10",
                        "village"),
                "the pool key is <dimension>|<city>|<faction>");
        check(!CapturePools.poolKey("minecraft:overworld", "city", "village")
                        .equals(CapturePools.poolKey("tarkovscav:urban_wasteland", "city", "village")),
                "the same city in the wasteland gets a different pool key (the ledger is dimension-aware)");

        System.out.println("CaptureTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("CaptureTest: " + failures + " FAILED");
            System.exit(1);
        }
    }

    /** One size-formula case. */
    private static void size(int buildings, int poolMin, int poolMax, int perBuilding, int expected,
                             String why) {
        equal(CapturePools.poolSize(buildings, poolMin, poolMax, perBuilding), expected,
                "poolSize(buildings=" + buildings + ", min=" + poolMin + ", max=" + poolMax + ", per="
                        + perBuilding + ") = " + expected + " (" + why + ")");
    }

    private static void equal(int actual, int expected, String label) {
        check(actual == expected, label + " [expected " + expected + ", got " + actual + "]");
    }

    private static void equal(String actual, String expected, String label) {
        check(expected.equals(actual), label + " [expected " + expected + ", got " + actual + "]");
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }
}
