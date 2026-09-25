import com.gfl.tarkovscav.grenade.GrenadeBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the SHIPPED ballistic solver (README 5v) instead of a model of it.
 *
 * <p>tools/selftest_grenades.js mirrors this solver in JavaScript, which catches drift in the constants but
 * could in principle hide a mistake that was made twice. This test closes that hole: the spike build compiles
 * the real {@code src/main/java/com/gfl/tarkovscav/grenade/GrenadeBallistics.java} against the minimal stubs
 * in tools/spike/stubs (Vec3 arithmetic and {@code BlockPos.containing} behave exactly like vanilla), and
 * then asserts the four cases the user cares about:</p>
 *
 * <ol>
 *   <li>flat ground, target 18 blocks away: the solved arc lands on the target;</li>
 *   <li>a 1 block wall between: a clear lob still exists (so the mob is allowed to throw);</li>
 *   <li>a 2 and a 3 block wall: no arc lands on the target, so the throw is refused
 *       ({@code blockedAt} names the wall);</li>
 *   <li>the short-range edge the README documents: 9 blocks is out of reach, 10 is not.</li>
 * </ol>
 *
 * <p>Exit code 0 = every case holds, 1 = a failure (the selftest harness counts it).</p>
 */
public final class GrenadeBallisticsTest {
    /** Half of the grenade's 0.25 box: the flight is sampled at the box bottom (solver's HALF_BOX). */
    private static final double HALF_BOX = 0.125D;
    private static final double SPAWN_BELOW_EYES = 0.2D;
    private static final double MAX_THROW_SPEED = 1.6D;
    private static final double MOB_SPEED_FACTOR = 0.85D;
    private static final int ARC_SAMPLES = 13;
    private static final double MAX_PITCH_DEGREES = 45.0D;

    private static int failures;

    private GrenadeBallisticsTest() {
    }

    public static void main(String[] args) {
        // The mob's eyes are 1.5 above its feet and the grenade spawns 0.2 lower; the target's eye is 1.5.
        Vec3 from = new Vec3(0.0D, 1.5D - SPAWN_BELOW_EYES, 0.0D);
        double speed = MAX_THROW_SPEED * MOB_SPEED_FACTOR;
        System.out.println("grenade ballistics: muzzle " + from + ", target eye (18.000, 1.500, 0.000),"
                + " speed " + String.format(java.util.Locale.ROOT, "%.2f", speed) + " blocks/tick");

        GrenadeBallistics.Solution flat = solve(from, 18.0D, speed, world(-999, 0));
        report("flat ground", flat);
        check(flat.clear() && flat.landing() != null, "flat ground: the arc is clear and lands");
        check(horizontalError(flat, 18.0D) <= 1.0D, "flat ground: the landing is on the target",
                horizontalError(flat, 18.0D) + " blocks off");
        check(flat.pitchDegrees() > 0.0D, "flat ground: it is a lob, not the old straight line",
                flat.pitchDegrees() + " degrees");

        GrenadeBallistics.Solution low = solve(from, 18.0D, speed, world(9, 1));
        report("1 block wall at x=9", low);
        check(low.clear() && low.landing() != null, "1 block wall: a clear lob still exists");
        check(horizontalError(low, 18.0D) <= 1.0D, "1 block wall: and it still lands on the target",
                horizontalError(low, 18.0D) + " blocks off");
        check(low.landing().x > 9.0D, "1 block wall: the landing is past the wall, so it went OVER it",
                "landing x " + String.format(java.util.Locale.ROOT, "%.2f", low.landing().x));
        check(low.pitchDegrees() > flat.pitchDegrees() - 0.001D,
                "1 block wall: the lob is at least as steep as the flat shot", low.pitchDegrees() + " degrees");

        GrenadeBallistics.Solution mid = solve(from, 18.0D, speed, world(9, 2));
        report("2 block wall at x=9", mid);
        check(!mid.clear(), "2 block wall: no arc lands on the target any more");

        GrenadeBallistics.Solution high = solve(from, 18.0D, speed, world(9, 3));
        report("3 block wall at x=9", high);
        check(!high.clear(), "3 block wall: the throw is refused");
        check(high.blockedAt() != null, "3 block wall: the refusal reports where the cover is");
        BlockPos cover = high.blockedAt();
        check(cover != null && cover.getX() == 9 && cover.getZ() == 0,
                "3 block wall: and that cover is the wall column", String.valueOf(cover));

        // The short-range edge the README 5v bullet states.
        GrenadeBallistics.Solution near9 = solve(from, 9.0D, speed, world(-999, 0));
        GrenadeBallistics.Solution near10 = solve(from, 10.0D, speed, world(-999, 0));
        check(!near9.clear(), "9 blocks is out of reach (README 5v says 6-9 blocks is held back)",
                horizontalError(near9, 9.0D) + " blocks off");
        check(near10.clear(), "10 blocks is not",
                horizontalError(near10, 10.0D) + " blocks off");

        // The solver samples the box bottom, so a "clear" wall case also has real headroom.
        check(HALF_BOX == 0.125D && low.clear(), "the box-bottom sample is the one the clear verdict used");

        if (failures > 0) {
            System.out.println(failures + " grenade ballistics check(s) FAILED");
            System.exit(1);
        }
        System.out.println("the shipped ballistic solver clears a 1 block wall and refuses a 3 block one");
    }

    private static GrenadeBallistics.Solution solve(Vec3 from, double targetDistance, double speed,
                                                    GrenadeBallistics.BlockTest blocked) {
        Vec3 aim = new Vec3(targetDistance, 1.5D, 0.0D);
        return GrenadeBallistics.solve(from, aim, speed, ARC_SAMPLES, MAX_PITCH_DEGREES, blocked);
    }

    /** Flat ground is everything below y=0; the wall is one column of blocks at x=wallX, z -1..1. */
    private static GrenadeBallistics.BlockTest world(int wallX, int wallHeight) {
        return pos -> pos.getY() < 0
                || (pos.getX() == wallX && pos.getY() >= 0 && pos.getY() < wallHeight
                    && Math.abs(pos.getZ()) <= 1);
    }

    private static double horizontalError(GrenadeBallistics.Solution solution, double targetDistance) {
        if (solution.landing() == null) {
            return Double.POSITIVE_INFINITY;
        }
        return solution.landing().horizontalDistanceTo(new Vec3(targetDistance, 1.5D, 0.0D));
    }

    private static void report(String label, GrenadeBallistics.Solution solution) {
        System.out.println(String.format(java.util.Locale.ROOT,
                "  %-22s pitch %5.2f deg  landing %s  error %5.2f  clear %s  blockedAt %s",
                label, solution.pitchDegrees(), solution.landing(), horizontalError(solution, 18.0D),
                solution.clear(), solution.blockedAt()));
    }

    private static void check(boolean ok, String label) {
        check(ok, label, "");
    }

    private static void check(boolean ok, String label, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + label + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) {
            failures++;
        }
    }
}
