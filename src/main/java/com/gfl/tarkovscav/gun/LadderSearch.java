package com.gfl.tarkovscav.gun;

import org.jetbrains.annotations.Nullable;

/**
 * The vertical-link decision core behind ladder climbing (design plan A, {@code docs/爬梯设计.md} §1,
 * README 7o).
 *
 * <h2>Why this class exists on its own</h2>
 * <p>Vanilla's {@code WalkNodeEvaluator} grows no vertical edges for a ladder, so for a mob a ladder shaft
 * is a wall (measured: 1.20.1's {@code WalkNodeEvaluator} only expands horizontal neighbours plus a
 * one-block jump/drop). Plan A therefore does not touch the navigator: it detects a <b>legal shaft</b> and
 * splits the trip into "walk to the foot -> climb -> step out -> carry on". This class is that detection,
 * plus the two rules that decide whether a climb may start and why one ended.</p>
 *
 * <h2>Deliberately pure</h2>
 * <p>Nothing here reads a {@code Level}, an entity or a config value. Every decision is a static function
 * over {@code int}s and a {@link Probe} of three predicates, so {@code tools/spike/LadderTest.java} can
 * execute the shipped logic with a synthetic world and no game on the classpath (the same pattern
 * {@code GrenadeBallistics} uses). {@code LadderClimb} supplies the real {@code Level} adapter;
 * {@code tools/spike/CityStructureGen} supplies one over the generated structure grid.</p>
 *
 * <h2>The geometry it accepts (measured on the generator's shafts)</h2>
 * <p>The generator ({@code CityStructureGen.Role.LADDER}) builds a shaft at {@code (x0+1, z0+1)}; with a
 * floor height of {@code fh} the column is a ladder block at every {@code y} in {@code [plateY+1,
 * plateY+fh-1]} of each storey and a one-block hole punched through every floor plate at the column. So the
 * column is <b>not</b> a continuous run of ladder blocks: it has a single passable gap at each floor plate,
 * exactly where the climber's feet pass through. {@link #column} accepts that, and only that: a passable
 * cell must have a climbable cell directly above or below it.</p>
 */
public final class LadderSearch {
    /**
     * How much of a vertical difference counts as "another floor". A one-block step is the ground
     * navigator's job (vanilla jumps it), so only a difference of 2 blocks or more is a vertical link.
     */
    public static final int FLOOR_STEP = 2;

    /** The four side neighbours, in a fixed order, so a shaft is always resolved to the same cell. */
    private static final int[][] SIDES = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    private LadderSearch() {
    }

    /**
     * The block queries this decision core needs. Implemented three times: over a real {@code Level}
     * ({@code LadderClimb}), over the structure generator's palette grid ({@code CityStructureGen}) and
     * over a synthetic cave in {@code tools/spike/LadderTest.java}.
     */
    public interface Probe {
        /** True when a mob can hold on to the block: {@code BlockTags.CLIMBABLE} (ladder/vine/scaffolding). */
        boolean climbable(int x, int y, int z);

        /** True when a mob's body fits in the cell: no collision, i.e. air / an open door. */
        boolean passable(int x, int y, int z);

        /** True when the block's top face can be stood on (any collision at all is enough). */
        boolean solidTop(int x, int y, int z);
    }

    /** A cell of the shaft, in absolute block coordinates. */
    public record Opening(int x, int y, int z) {
    }

    /**
     * One legal shaft for one trip.
     *
     * @param x          the column's x
     * @param z          the column's z
     * @param startY     the feet Y the climber grasps at (its own floor)
     * @param endY       the feet Y it steps out at (the destination floor)
     * @param startOpening the cell beside the column at {@code startY} that the unit walks to
     * @param endOpening   the cell beside the column at {@code endY} that the unit steps out into
     */
    public record Shaft(int x, int z, int startY, int endY, Opening startOpening, Opening endOpening) {
        /** +1 for an upward climb, -1 for a downward one. */
        public int direction() {
            return Integer.compare(this.endY, this.startY);
        }

        /** The number of blocks this climb covers. */
        public int height() {
            return Math.abs(this.endY - this.startY);
        }
    }

    /** Why a climb ended. The goal's four branches are ARRIVED, DESTINATION_GONE, MAX_HEIGHT and
     *  COMBAT_INTERRUPTED; GEOMETRY_LOST and STOPPED are the two backstops. */
    public enum Exit {
        /** The unit reached the destination floor and stepped out into the opening. */
        ARRIVED,
        /** The command mark expired / was deleted, or the target died, before the top. */
        DESTINATION_GONE,
        /** A fight started and {@code ladder.combatWhileClimbing} is false. */
        COMBAT_INTERRUPTED,
        /** {@code ladder.maxHeight} was reached. */
        MAX_HEIGHT,
        /** The unit is no longer on the column (knocked out of the shaft). No teleport: normal fall. */
        GEOMETRY_LOST,
        /** The goal was stopped by something else while on the rungs (removal, goal clearing). */
        STOPPED
    }

    // ------------------------------------------------------------------ the intent rule

    /** True when the destination is on a different floor of the same building. */
    public static boolean differentFloor(int feetY, int destFeetY) {
        return Math.abs(destFeetY - feetY) >= FLOOR_STEP;
    }

    /**
     * May a unit <b>start</b> a climb? The whole intent rule, in one place.
     *
     * <p>The shape of it is the task's rule 3, not a new policy: the ladder goal ranks above random
     * strolling and below every combat/retreat goal, so combat and self-preservation win. {@code combat}
     * and {@code retreating} are the command layer's own vetoes
     * ({@code AdvanceOrder#combatOverrides}/{@code #retreatOverrides}), reused here instead of a second
     * set of flags. {@code ladder.combatWhileClimbing} is the one documented exception: with it on, a
     * unit may climb towards its target and shoot from the rungs.</p>
     *
     * <p>{@code sameFloor} is passed rather than derived so the caller can skip the (much more
     * expensive) shaft scan on the common same-floor case; {@code hasShaft} is that scan's result.</p>
     */
    public static boolean shouldStart(boolean enabled, boolean hasShaft, boolean sameFloor, boolean combat,
                                      boolean retreating, boolean combatWhileClimbing) {
        if (!enabled || !hasShaft || sameFloor) {
            return false;
        }
        if (retreating) {
            return false;
        }
        return combatWhileClimbing || !combat;
    }

    // ------------------------------------------------------------------ geometry

    /** A cell a mob can stand in: passable body cell, passable head cell, solid floor below. */
    public static boolean standable(Probe probe, int x, int y, int z) {
        return probe.passable(x, y, z) && probe.passable(x, y + 1, z) && probe.solidTop(x, y - 1, z);
    }

    /**
     * The cell beside the column at height {@code y} where a unit can stand, or null when there is none.
     *
     * <p>This is deliberately the same test for both ends of a trip: the foot of the shaft (where the unit
     * walks to before it steps onto the rungs) and the destination opening (where it steps out). In the
     * generator's shafts the plate beside the column is solid at every floor level and the two cells
     * beside it are kept clear ({@code CityStructureGen#buildInterior} clears {@code (x, z+1)} and
     * {@code (x+1, z)} at every height), so both ends resolve.</p>
     */
    @Nullable
    public static Opening opening(Probe probe, int x, int y, int z) {
        for (int[] side : SIDES) {
            int ox = x + side[0];
            int oz = z + side[1];
            if (standable(probe, ox, y, oz)) {
                return new Opening(ox, y, oz);
            }
        }
        return null;
    }

    /**
     * True when the column can be climbed between {@code fromY} and {@code toY} inclusive.
     *
     * <p>A cell qualifies when it is climbable, or when it is passable <b>and</b> a climbable block is
     * directly above or below it - the one-block floor-plate hole the generator punches at every storey.
     * Anything else (a wall, a plugged hole, a floor slab with no hole) breaks the run.</p>
     */
    public static boolean column(Probe probe, int x, int fromY, int toY, int z) {
        int lo = Math.min(fromY, toY);
        int hi = Math.max(fromY, toY);
        boolean anyRung = false;
        for (int y = lo; y <= hi; y++) {
            if (probe.climbable(x, y, z)) {
                anyRung = true;
                continue;
            }
            boolean hole = probe.passable(x, y, z)
                    && (probe.climbable(x, y - 1, z) || probe.climbable(x, y + 1, z));
            if (!hole) {
                return false;
            }
        }
        return anyRung;
    }

    /**
     * The closest legal shaft for one trip, or null when there is none in range.
     *
     * <p>Columns are visited in order of increasing horizontal distance from the unit (the unit's own
     * column first), and the first one that satisfies all four conditions wins, so the common case - a unit
     * standing in the room next to its shaft - costs a handful of block lookups instead of the full
     * {@code (2r+1)^2} scan. The conditions:</p>
     * <ol>
     *   <li>the destination really is on a different floor and no further than {@code maxHeight};</li>
     *   <li>the unit can stand beside the column on its own floor ({@link #opening});</li>
     *   <li>the column is climbable, floor hole included ({@link #column});</li>
     *   <li>there is a standable, 2-block-clear opening beside the column on the destination floor.</li>
     * </ol>
     *
     * <p>Whether the unit can actually <em>walk</em> to the foot is not decided here: that is the ground
     * navigator's job, and this class must stay free of path finding. The radius is what bounds the
     * detour.</p>
     */
    @Nullable
    public static Shaft find(Probe probe, int unitX, int unitFeetY, int unitZ, int destFeetY, int radius,
                             int maxHeight) {
        if (!differentFloor(unitFeetY, destFeetY)) {
            return null;
        }
        if (Math.abs(destFeetY - unitFeetY) > maxHeight) {
            return null;
        }
        int side = Math.max(0, radius);
        // Ring by ring outwards, so the nearest shaft is found without scanning the rest of the square.
        for (int ring = 0; ring <= side; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the cells that are new in this ring, so no column is tested twice.
                    if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = unitX + dx;
                    int z = unitZ + dz;
                    Shaft shaft = probeColumn(probe, x, unitFeetY, z, destFeetY);
                    if (shaft != null) {
                        return shaft;
                    }
                }
            }
        }
        return null;
    }

    /** One column, the four conditions of {@link #find}. Null when this column is not a legal shaft. */
    @Nullable
    private static Shaft probeColumn(Probe probe, int x, int unitFeetY, int z, int destFeetY) {
        Opening start = opening(probe, x, unitFeetY, z);
        if (start == null) {
            return null;
        }
        Opening end = opening(probe, x, destFeetY, z);
        if (end == null) {
            return null;
        }
        if (!column(probe, x, unitFeetY, destFeetY, z)) {
            return null;
        }
        return new Shaft(x, z, unitFeetY, destFeetY, start, end);
    }

    /**
     * The nearest height inside the shaft at which the unit can step out onto a floor, or
     * {@link Integer#MIN_VALUE} when there is none.
     *
     * <p>This is the "never end a climb hanging in mid-air" rule: an interruption (order gone, mark
     * deleted, combat, {@code maxHeight}) sends the goal here, and the goal keeps moving - up or down,
     * whichever is nearer - to this height before it hands control back. Ties prefer the upward
     * candidate, i.e. the direction the climb was already going. Only heights where an
     * {@link #opening} exists can be returned, and an opening by construction needs a solid floor below,
     * so a returned height is always a real floor.</p>
     */
    public static int nearestLanding(Probe probe, Shaft shaft, int currentFeetY) {
        int lo = Math.min(shaft.startY(), shaft.endY());
        int hi = Math.max(shaft.startY(), shaft.endY());
        for (int d = 0; d <= hi - lo; d++) {
            int up = currentFeetY + d;
            if (up >= lo && up <= hi && opening(probe, shaft.x(), up, shaft.z()) != null) {
                return up;
            }
            int down = currentFeetY - d;
            if (d > 0 && down >= lo && down <= hi && opening(probe, shaft.x(), down, shaft.z()) != null) {
                return down;
            }
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ the state machine's exits

    /** True once the unit has reached (or passed) the destination floor along the shaft. */
    public static boolean reached(int feetY, Shaft shaft) {
        return shaft.direction() > 0 ? feetY >= shaft.endY() : feetY <= shaft.endY();
    }

    /** True when {@code ladder.maxHeight} has been spent on this climb. */
    public static boolean exceededHeight(int startY, int feetY, int maxHeight) {
        return Math.abs(feetY - startY) > maxHeight;
    }

    /**
     * True when the destination is gone or a fight pre-empts the climb - the reason a climb that is
     * already on the rungs must end on the nearest floor instead of being abandoned.
     */
    public static boolean interrupted(boolean destinationValid, boolean combat, boolean combatWhileClimbing) {
        return !destinationValid || (combat && !combatWhileClimbing);
    }

    /**
     * True when the unit is no longer on the column at all - knocked sideways out of the shaft. No
     * teleport then: it fell, and the fall must behave normally (fall damage included, see
     * {@code LadderClimb}'s fall handler).
     */
    public static boolean geometryLost(Probe probe, Shaft shaft, int x, int y, int z) {
        if (x != shaft.x() || z != shaft.z()) {
            return true;
        }
        return !probe.climbable(x, y, z) && !probe.passable(x, y, z);
    }

    /** The one-line report used by the goal's log lines and by {@code /armedmobs} debug output. */
    public static String describe(@Nullable Shaft shaft) {
        if (shaft == null) {
            return "none";
        }
        return "(" + shaft.x() + "," + shaft.z() + ") " + shaft.startY() + "->" + shaft.endY()
                + " foot=" + shaft.startOpening().x() + "," + shaft.startOpening().y() + ","
                + shaft.startOpening().z()
                + " out=" + shaft.endOpening().x() + "," + shaft.endOpening().y() + ","
                + shaft.endOpening().z();
    }
}
