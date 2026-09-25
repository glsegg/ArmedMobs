package com.gfl.tarkovscav.entity;

import net.minecraft.world.entity.Entity;

/**
 * "Is this mob really moving?", measured from positions instead of from intent.
 *
 * <h2>Why the animation needs its own answer</h2>
 * <p>Both obvious predicates are instantaneous and can be true while the mob is pinned against
 * geometry: {@code Entity#getDeltaMovement} is the movement the entity <em>tried</em> to make this tick
 * (a mob walking into a closed door keeps a non-zero delta), and GeckoLib's
 * {@code AnimationState#isMoving} / {@code WalkAnimationState#speed} are built from the same per-tick
 * intent. A clip chosen from those keeps playing {@code tac:walk} while the body does not move, which is
 * exactly the reported "legs are stepping but the position does not change".</p>
 *
 * <p>This class keeps a short sliding window of <b>actual</b> per-tick displacement and answers from the
 * average, so a mob that is stuck animates as standing still even if it keeps trying to walk - the
 * animation becomes a readout of what really happened.</p>
 */
public final class WalkTelemetry {
    /** Ticks of history. 10 ticks = half a second: long enough to smooth, short enough to react. */
    public static final int WINDOW = 10;
    /** Average blocks/tick above which the mob counts as walking (0.02 = 0.4 blocks/s). */
    public static final double MOVING_THRESHOLD = 0.02D;
    /** Average blocks/tick above which it counts as running (0.12 = 2.4 blocks/s). */
    public static final double RUNNING_THRESHOLD = 0.12D;

    private final double[] samples = new double[WINDOW];
    private int cursor;
    private int filled;
    private boolean primed;
    private double lastX;
    private double lastZ;

    /** Call once per tick, on both sides - the client's own entity tracks its own positions. */
    public void tick(Entity entity) {
        double x = entity.getX();
        double z = entity.getZ();
        if (this.primed) {
            this.samples[this.cursor] = Math.hypot(x - this.lastX, z - this.lastZ);
            this.cursor = (this.cursor + 1) % WINDOW;
            if (this.filled < WINDOW) {
                this.filled++;
            }
        }
        this.primed = true;
        this.lastX = x;
        this.lastZ = z;
    }

    /** Average horizontal blocks per tick over the window; 0 until the window has any samples. */
    public double average() {
        if (this.filled == 0) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int i = 0; i < this.filled; i++) {
            total += this.samples[i];
        }
        return total / this.filled;
    }

    /** True when the mob has actually covered ground over the last {@link #WINDOW} ticks. */
    public boolean isMoving() {
        return average() >= MOVING_THRESHOLD;
    }

    /** True when it is covering ground fast enough to play the run clip. */
    public boolean isRunning() {
        return average() >= RUNNING_THRESHOLD;
    }

    /** Drops the history, e.g. after a teleport, so a jump is not read as a sprint. */
    public void reset() {
        this.filled = 0;
        this.cursor = 0;
        this.primed = false;
        for (int i = 0; i < WINDOW; i++) {
            this.samples[i] = 0.0D;
        }
    }
}
