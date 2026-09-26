import com.gfl.tarkovscav.gun.LadderSearch;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs the SHIPPED ladder decision core ({@code com.gfl.tarkovscav.gun.LadderSearch}) over a synthetic
 * block world, with no game on the classpath.
 *
 * <p>{@code tools/selftest_ladder.js} mirrors none of this logic in JavaScript: the point of this test is
 * that the real {@link LadderSearch} - the same class {@code LadderClimbGoal} calls - accepts and refuses
 * shafts, and that all four of its state-machine exits land somewhere legal. The world it is run against is
 * not invented: {@link #generatedShaft} reproduces the geometry {@code CityStructureGen#buildBuilding}
 * actually produces, including the two quirks that matter -</p>
 * <ol>
 *   <li>a storey's ladder run is {@code fh - 2} rungs long and the cell directly under the next floor plate
 *       is left as air by {@code buildInterior}'s "keep the ladder shaft usable" pass, so a climber passes
 *       through <b>two</b> consecutive air cells at every storey line (that air cell, then the hole in the
 *       plate);</li>
 *   <li>the roof pass turns the top floor's last rung back into a ladder and punches the roof hole.</li>
 * </ol>
 *
 * <p>Exit code 0 = every case holds, 1 = at least one failed (the selftest harness counts it).</p>
 */
public final class LadderTest {
    private static int failures;
    private static int checks;

    private LadderTest() {
    }

    public static void main(String[] args) {
        System.out.println("ladder decision core: the shipped LadderSearch run against the generator's own"
                + " shaft geometry (fh=4, 3 floors) and against obstructed variants");

        generatorGeometryCases();
        obstructionCases();
        intentCases();
        stateMachineCases();

        if (failures > 0) {
            System.out.println(failures + " ladder check(s) FAILED (" + checks + " run)");
            System.exit(1);
        }
        System.out.println(checks + " ladder checks passed: the shipped shaft finder accepts every legal"
                + " floor pair, refuses a same-floor trip, a missing shaft and an obstructed opening, and all"
                + " four exits land on a standable floor");
    }

    // ------------------------------------------------------------------ 1. the generator's geometry

    private static void generatorGeometryCases() {
        System.out.println();
        System.out.println("1. the generator's own shaft (ladder run fh-2, the 'keep the shaft usable' air"
                + " cell, the plate hole, the roof rung)");
        World world = generatedShaft(3, 4, false);
        // Floor feet heights are plateY + 1: 2, 6, 10.
        for (int from = 0; from < 3; from++) {
            for (int to = 0; to < 3; to++) {
                if (from == to) {
                    continue;
                }
                int fromY = from * 4 + 2;
                int toY = to * 4 + 2;
                LadderSearch.Shaft shaft = LadderSearch.find(world, 0, fromY, 0, toY, 0, Integer.MAX_VALUE);
                check(shaft != null, "floor " + from + " -> floor " + to + ": the shaft is found",
                        LadderSearch.describe(shaft));
                if (shaft != null) {
                    check(shaft.startY() == fromY && shaft.endY() == toY,
                            "floor " + from + " -> floor " + to + ": the shaft's ends are the two floors",
                            shaft.startY() + " -> " + shaft.endY() + " direction " + shaft.direction());
                    check(LadderSearch.standable(world, shaft.endOpening().x(), shaft.endOpening().y(),
                                    shaft.endOpening().z()),
                            "floor " + from + " -> floor " + to + ": the step-out cell is standable");
                    check(LadderSearch.standable(world, shaft.startOpening().x(), shaft.startOpening().y(),
                                    shaft.startOpening().z()),
                            "floor " + from + " -> floor " + to + ": the foot cell is standable");
                }
            }
        }
        check(LadderSearch.find(world, 0, 2, 0, 2, 8, Integer.MAX_VALUE) == null,
                "a same-floor destination (.Y equal) is not a climb");
        check(!LadderSearch.differentFloor(2, 3),
                "a one-block difference is the navigator's step, not another floor");
        check(LadderSearch.differentFloor(2, 6), "a whole storey is another floor");
        check(LadderSearch.find(world, 5, 2, 5, 10, 1, Integer.MAX_VALUE) == null,
                "a shaft outside the search radius is not used (the unit walks instead)",
                "unit 7 blocks away, radius 1");
        check(LadderSearch.find(world, 5, 2, 5, 10, 8, Integer.MAX_VALUE) != null,
                "the same shaft inside the radius is used", "radius 8");
        check(LadderSearch.find(world, 0, 2, 0, 10, 0, 4) == null,
                "a two-storey trip (8 blocks) is refused by maxHeight=4");
        check(LadderSearch.find(world, 0, 2, 0, 10, 0, 8) != null,
                "and accepted by maxHeight=8");

        // Continuity: the two-cell air gap at a storey line is legal, a plugged hole is not.
        check(LadderSearch.column(world, 0, 2, 10, 0),
                "the column is continuous through the air cell and the plate hole");
        World plugged = generatedShaft(3, 4, false);
        plugged.solid(5, 0, 0);
        check(!LadderSearch.column(plugged, 0, 2, 10, 0),
                "a solid block where the plate hole is breaks the column");
        check(LadderSearch.find(plugged, 0, 2, 0, 6, 0, Integer.MAX_VALUE) == null,
                "and the finder refuses that shaft");

        // Headroom: the opening needs two clear cells. Both openable sides are blocked at the head height
        // of floor 1, so that floor has no usable opening at all.
        World lowCeiling = generatedShaft(3, 4, false);
        lowCeiling.solid(7, 1, 0);
        lowCeiling.solid(7, 0, 1);
        check(!LadderSearch.standable(lowCeiling, 1, 6, 0),
                "a block in the head cell makes the opening unusable");
        check(LadderSearch.find(lowCeiling, 0, 2, 0, 6, 0, Integer.MAX_VALUE) == null,
                "and the finder refuses that destination floor");
    }

    // ------------------------------------------------------------------ 2. the shipped-structure obstruction

    private static void obstructionCases() {
        System.out.println();
        System.out.println("2. the obstructed shafts the shipped presets contain: cover and furniture on the"
                + " two cells beside the ladder (measured by CityStructureGen --report)");
        World furnished = generatedShaft(3, 4, true);
        check(LadderSearch.find(furnished, 0, 2, 0, 6, 0, Integer.MAX_VALUE) == null,
                "a 2-high stack on BOTH openable cells refuses the climb (cover_sandbag + furniture)");
        check(LadderSearch.column(furnished, 0, 2, 10, 0),
                "the column itself is still continuous - the blockage is the opening, not the rungs");
        // Every floor blocked: nothing may be climbed at all, and every landing must be refused too.
        check(LadderSearch.find(furnished, 0, 2, 0, 10, 8, 48) == null,
                "with every floor blocked the finder finds nothing in the whole radius, so the unit walks");
        check(!LadderSearch.shouldStart(true, false, false, false, false, false),
                "no shaft -> no climb (the unit falls back to the ground navigator)");
    }

    // ------------------------------------------------------------------ 3. the intent rule

    private static void intentCases() {
        System.out.println();
        System.out.println("3. the intent rule (shouldStart): combat and self-preservation win over climbing");
        check(LadderSearch.shouldStart(true, true, false, false, false, false),
                "clear destination + shaft + no fight -> climb");
        check(!LadderSearch.shouldStart(false, true, false, false, false, false),
                "ladder.enabled=false -> no climb (the old behaviour)");
        check(!LadderSearch.shouldStart(true, true, true, false, false, false),
                "same floor -> no climb");
        check(!LadderSearch.shouldStart(true, false, false, false, false, false),
                "no shaft in radius -> no climb");
        check(!LadderSearch.shouldStart(true, true, false, true, false, false),
                "a live target refuses the climb (combat wins)");
        check(!LadderSearch.shouldStart(true, true, false, false, true, false),
                "retreating refuses the climb (self-preservation wins)");
        check(LadderSearch.shouldStart(true, true, false, true, false, true),
                "ladder.combatWhileClimbing=true lets a fighting unit climb");
        check(!LadderSearch.shouldStart(true, true, false, true, true, true),
                "but even then a retreat refuses it");
    }

    // ------------------------------------------------------------------ 4. the four exits

    private static void stateMachineCases() {
        System.out.println();
        System.out.println("4. the four state-machine exits, each landing somewhere legal");
        World world = generatedShaft(3, 4, false);
        LadderSearch.Shaft up = LadderSearch.find(world, 0, 2, 0, 10, 0, Integer.MAX_VALUE);
        check(up != null, "the reference climb exists");
        if (up == null) {
            return;
        }
        // (a) ARRIVED: the destination opening is the landing.
        check(LadderSearch.reached(up.endY(), up), "ARRIVED: feet at the destination floor is 'reached'");
        check(LadderSearch.reached(up.endY() + 1, up), "ARRIVED: so is overshooting it by a tick");
        check(!LadderSearch.reached(up.startY(), up), "the climb itself is not 'reached'");
        check(LadderSearch.standable(world, up.endOpening().x(), up.endOpening().y(), up.endOpening().z()),
                "ARRIVED: the destination opening is standable");

        // (b) DESTINATION_GONE and (c) COMBAT_INTERRUPTED both end in the LANDING phase.
        check(LadderSearch.interrupted(false, false, false),
                "DESTINATION_GONE: a vanished destination interrupts");
        check(LadderSearch.interrupted(true, true, false),
                "COMBAT_INTERRUPTED: a fight interrupts");
        check(!LadderSearch.interrupted(true, true, true),
                "with combatWhileClimbing a fight does not interrupt");
        for (int feetY = up.startY(); feetY <= up.endY(); feetY++) {
            int land = LadderSearch.nearestLanding(world, up, feetY);
            LadderSearch.Opening out = land == Integer.MIN_VALUE ? null
                    : LadderSearch.opening(world, up.x(), land, up.z());
            check(land != Integer.MIN_VALUE && out != null && LadderSearch.standable(world, out.x(), out.y(),
                            out.z()),
                    "INTERRUPTED at y=" + feetY + ": a standable floor is found", "landing y=" + land);
        }
        check(LadderSearch.nearestLanding(world, up, 7) == 6,
                "INTERRUPTED mid-storey lands on the nearer floor below", "y=6 from y=7");
        check(LadderSearch.nearestLanding(world, up, 9) == 10,
                "one block below the top lands on the top floor", "y=10 from y=9");

        // (d) MAX_HEIGHT: the same landing rule, so the exit lands legally too.
        check(LadderSearch.exceededHeight(2, 11, 8), "MAX_HEIGHT: y=11 from y=2 exceeds maxHeight=8");
        check(!LadderSearch.exceededHeight(2, 10, 8), "and y=10 does not");
        int land = LadderSearch.nearestLanding(world, up, 11);
        check(land == 10, "MAX_HEIGHT at y=11 lands on the top floor", "landing y=" + land);

        // (e) GEOMETRY_LOST: a knocked-out unit gets no teleport - the fall must behave normally.
        check(LadderSearch.geometryLost(world, up, 3, 6, 0),
                "GEOMETRY_LOST: a unit one block off the column is no longer climbing");
        check(!LadderSearch.geometryLost(world, up, 0, 8, 0),
                "an air cell inside the column (the 'keep usable' cell) is not 'lost'");
        World plugged = generatedShaft(3, 4, false);
        plugged.solid(5, 0, 0);
        check(LadderSearch.geometryLost(plugged, up, 0, 5, 0),
                "a solid block in the column is 'lost'");
        check(LadderSearch.nearestLanding(world, up, 2) == 2,
                "STOPPED while standing on a floor resolves to that same floor (no teleport needed)");
    }

    // ------------------------------------------------------------------ the synthetic world

    /**
     * The geometry {@code CityStructureGen#buildBuilding} produces for one shaft: floor plates with a hole
     * at the column, a ladder run of {@code fh - 2} rungs per storey, the air cell under each plate, the
     * roof hole and the restored roof rung. The column is at (0,0) and the two shell walls are at x=-1 and
     * z=-1, so - exactly as in a real building - only the (1,0) and (0,1) sides of the column can ever be
     * an opening.
     *
     * @param blockOpenings true to put the 2-high cover/furniture stack the shipped presets have on BOTH
     *                      openable cells of every floor - the measured defect this test pins
     */
    private static World generatedShaft(int floors, int floorHeight, boolean blockOpenings) {
        World world = new World();
        int topY = 1 + floors * floorHeight;
        for (int k = 0; k < floors; k++) {
            int plateY = 1 + k * floorHeight;
            for (int x = -1; x <= 3; x++) {
                for (int z = -1; z <= 3; z++) {
                    if (x == 0 && z == 0) {
                        continue;                       // the hole at the column
                    }
                    world.solid(plateY, x, z);
                }
            }
            // the ladder run, with buildInterior's "keep the shaft usable" pass leaving yHigh as air
            for (int y = plateY + 1; y < plateY + floorHeight - 1; y++) {
                world.climb(y, 0, 0);
            }
            if (blockOpenings) {
                int feetY = plateY + 1;
                world.solid(feetY, 1, 0);
                world.solid(feetY + 1, 1, 0);
                world.solid(feetY, 0, 1);
                world.solid(feetY + 1, 0, 1);
            }
        }
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                if (x != 0 || z != 0) {
                    world.solid(topY, x, z);
                }
            }
        }
        // the two shell walls, full height: the (-1,0) and (0,-1) sides of the column are never openings
        for (int y = 1; y <= topY + 1; y++) {
            for (int z = -1; z <= 3; z++) {
                world.solid(y, -1, z);
            }
            for (int x = -1; x <= 3; x++) {
                world.solid(y, x, -1);
            }
        }
        world.climb(topY - 1, 0, 0);                    // the roof rung the roofAccess pass restores
        return world;
    }

    /** A cell set: climbable, solid, or (by omission) passable air. */
    private static final class World implements LadderSearch.Probe {
        private final Set<String> climbable = new HashSet<>();
        private final Set<String> solid = new HashSet<>();

        void climb(int y, int x, int z) {
            this.climbable.add(y + ":" + x + ":" + z);
        }

        void solid(int y, int x, int z) {
            this.solid.add(y + ":" + x + ":" + z);
        }

        private String key(int x, int y, int z) {
            return y + ":" + x + ":" + z;
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return this.climbable.contains(key(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            String key = key(x, y, z);
            return !this.climbable.contains(key) && !this.solid.contains(key);
        }

        @Override
        public boolean solidTop(int x, int y, int z) {
            return this.solid.contains(key(x, y, z));
        }
    }

    // ------------------------------------------------------------------ checks

    private static void check(boolean ok, String label) {
        check(ok, label, "");
    }

    private static void check(boolean ok, String label, String detail) {
        checks++;
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + label
                + (detail == null || detail.isEmpty() ? "" : "  " + detail));
        if (!ok) {
            failures++;
        }
    }
}
