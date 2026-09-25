package com.gfl.tarkovscav.command;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * One command mark: a neutral point on the ground that any of the three command tools can order its own
 * faction towards.
 *
 * <h2>Neutral, and that is the whole point</h2>
 * <p>A mark has no faction field. The user's rule is "the mark is neutral, the tool picks which of A/B/C/D
 * the faction goes to" (标记中立，指挥工具可以控制阵容去 ABCD 哪个标记点), so the faction lives on the
 * <b>tool that gives the order</b>, never on the mark. One mark can be the objective of the village squads
 * and of the pillager squads at the same time, which is what makes setting up a meeting (or a trap) work.</p>
 *
 * <h2>Lifetime</h2>
 * <p>{@code expiresAtTick} is the game time the mark dies at, or {@link #PERMANENT} (-1) for a mark that
 * only ends when its source is removed - the signal-point block. A signal stick is
 * {@code command.stickDurationTicks} (6000 ticks = 5 minutes). The expiry is a <b>number</b>, not a
 * countdown: nothing has to tick a mark down, so a mark cannot lose time to a laggy server tick, and an
 * expired mark is simply not returned by {@link MarkData#live}.</p>
 *
 * @param letter       "A", "B", "C", ... - what the player sees and what {@code /armedmobs marks} uses
 * @param pos          the marked block
 * @param source       how the mark came to exist (tool right-click, thrown stick, signal-point block)
 * @param expiresAtTick game time it expires at, or {@link #PERMANENT}
 * @param dimension    the level it belongs to; marks are per dimension and never leak across
 */
public record CommandMark(String letter, BlockPos pos, Source source, long expiresAtTick,
                          ResourceLocation dimension) {
    /** Marks never live longer than this; the sentinel also means "no expiry". */
    public static final long PERMANENT = -1L;

    /** Where a mark came from - shown by {@code /armedmobs marks} so a stale one can be identified. */
    public enum Source {
        /** A command tool right-clicked a block (sneak or not). */
        TOOL("tool"),
        /** A thrown signal stick: 6000 ticks. */
        STICK("stick"),
        /** A signal-point block: permanent until the block is broken. */
        POINT("point");

        private final String id;

        Source(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }
    }

    public CommandMark {
        pos = pos.immutable();
    }

    /** A mark with no expiry (the signal-point block). */
    public static CommandMark permanent(String letter, BlockPos pos, Source source,
                                        ResourceLocation dimension) {
        return new CommandMark(letter, pos, source, PERMANENT, dimension);
    }

    public boolean permanentMark() {
        return this.expiresAtTick == PERMANENT;
    }

    /** True once the game clock has reached the expiry tick. A permanent mark never expires. */
    public boolean expired(long now) {
        return !permanentMark() && now >= this.expiresAtTick;
    }

    /** Ticks left, or -1 for a permanent mark. Never negative. */
    public long remainingTicks(long now) {
        return permanentMark() ? PERMANENT : Math.max(0L, this.expiresAtTick - now);
    }

    /** Seconds left, rounded up the way a player counts a fuse. -1 for a permanent mark. */
    public long remainingSeconds(long now) {
        return permanentMark() ? PERMANENT : (remainingTicks(now) + 19L) / 20L;
    }

    /** True when this mark is the one the order was issued for: same letter, in the same dimension. */
    public boolean isAt(String otherLetter, ResourceLocation otherDimension, BlockPos otherPos) {
        return this.letter.equals(otherLetter) && this.dimension.equals(otherDimension)
                && this.pos.equals(otherPos);
    }

    /** The line {@code /armedmobs marks} prints. */
    public String describe(long now) {
        String life = permanentMark() ? "permanent" : remainingSeconds(now) + "s";
        return this.letter + " " + this.pos.toShortString() + " " + this.source.id() + " " + life;
    }

    /** The chat line the sneak-right-click cycle shows: "current target: B (x, y, z)". */
    public String chatLine() {
        return this.letter + " (" + this.pos.getX() + ", " + this.pos.getY() + ", "
                + this.pos.getZ() + ")";
    }

    /** Squared distance from a point, used for the arrival test. */
    public double distanceSqr(double x, double y, double z) {
        double dx = this.pos.getX() + 0.5D - x;
        double dy = this.pos.getY() + 0.5D - y;
        double dz = this.pos.getZ() + 0.5D - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Clamps an arbitrary string into a valid mark letter; used when one is typed into a command. */
    public static String sanitiseLetter(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "A";
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return upper.length() > 4 ? upper.substring(0, 4) : upper;
    }

    /** What a letter costs in the alphabet: A=0, B=1, ..., and -1 for anything else. */
    public static int letterIndex(String letter) {
        if (letter == null || letter.length() != 1) {
            return -1;
        }
        char c = Character.toUpperCase(letter.charAt(0));
        return c >= 'A' && c <= 'Z' ? c - 'A' : -1;
    }

    /**
     * The first free letter, A, B, C, D, ... - the numbering rule the design doc asks for.
     *
     * <p>Pure, and it takes the used set rather than a world, so
     * {@code tools/selftest_command_marks.js} mirrors it and drives the "delete B, the next mark takes B
     * again" case without a game. Past Z it keeps going with X1, X2, ... so a very patient player cannot
     * run out of letters (in practice {@code command.maxMarks} stops long before that).</p>
     */
    public static String firstFreeLetter(java.util.Collection<String> used) {
        for (int i = 0; i < 26; i++) {
            String candidate = String.valueOf((char) ('A' + i));
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = "X" + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return "X" + Mth.nextInt(net.minecraft.util.RandomSource.create(), 1000, 9999);
    }
}
