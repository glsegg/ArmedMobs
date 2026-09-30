package com.gfl.tarkovscav.world;

/**
 * The city faction-cap arithmetic, and nothing else.
 *
 * <h2>Why the numbers live in their own class</h2>
 * <p>Same reason as {@link CapturePools} and {@link CityFactions}: the part that decides whether a spawn is
 * refused has to be <b>measurable without a game</b>. Every method takes and returns primitives, so
 * {@code tools/spike/SpawnCapTest.java} compiles THIS shipped source and drives the real rule - a copy of the
 * formula inside the test would prove nothing. The counting, the cache and the event hooks live in
 * {@link CitySpawnCap}.</p>
 *
 * <h2>What the cap is, in the user's words</h2>
 * <p>The report behind it (quoted verbatim in README 7q, which is where the Chinese lives - this file stays
 * ASCII by the project's own rule for Java sources): spawners keep spawning endlessly, which causes serious
 * stutter, and unless a player places a unit by hand (a spawn egg), a place should hold at most twelve
 * gun-armed units of one line-up <b>at the same time</b>. So the rule is a comparison of a
 * <b>simultaneous count</b> against a limit, not a rate and not a tally: two units dying and two spawning
 * leaves the count where it was, which is what "at the same time" means.</p>
 *
 * <h2>The window, and why it exists</h2>
 * <p>Counting live entities costs a level query, so the count is cached for
 * {@code spawn.cityFactionCapCountTicks}. The cached count alone would let a burst through (a spawner firing
 * several times inside one window would each see the same stale number), so the window also counts the
 * spawns it has already <b>allowed</b>: {@link #effectiveCount} is the live count plus those. That over-counts
 * by at most the spawns that were allowed and then failed later in the pipeline, it errs towards fewer units
 * (which is the point of the key), and it self-corrects at the next recount.</p>
 */
public final class SpawnCapMath {
    private SpawnCapMath() {
    }

    /**
     * What the cap is compared against: alive now, plus what this window has already let through.
     *
     * <p>Both halves are floored at zero: a negative count would make {@link #overCap} silently useless, and
     * the counted half comes from a live level query while the accepted half is a local counter that a
     * mis-ordered event could in principle drive negative.</p>
     */
    public static int effectiveCount(int live, int acceptedThisWindow) {
        return Math.max(0, live) + Math.max(0, acceptedThisWindow);
    }

    /**
     * True when one more unit of this faction must be refused.
     *
     * <p>{@code cap <= 0} means "no cap" and is answered false: an operator who sets the key to 0 gets the
     * uncapped behaviour rather than a city that can never spawn anything, and a hand-edited toml cannot
     * empty the world by accident.</p>
     *
     * <p>The comparison is {@code >= cap}, so a cap of 12 allows exactly 12: the 13th is refused. That is the
     * boundary the user asked for ("at most twelve of one line-up standing at the same time").</p>
     */
    public static boolean overCap(int live, int acceptedThisWindow, int cap) {
        if (cap <= 0) {
            return false;
        }
        return effectiveCount(live, acceptedThisWindow) >= cap;
    }

    /**
     * True when a cached count has aged out and has to be taken again.
     *
     * <p>Two edges are load-bearing, and {@code tools/spike/SpawnCapTest.java} found the first one for real:</p>
     * <ul>
     *   <li><b>Never counted.</b> The cache starts at {@link Long#MIN_VALUE}, and {@code now - MIN_VALUE}
     *       <em>overflows to a negative number</em>, so a plain subtraction would answer "fresh" - the first
     *       measurement would never be taken, the window's accept counter would grow without ever being reset,
     *       and a city would refuse every spawn for good once the cap was reached. Hence the explicit test.</li>
     *   <li><b>A clock that went backwards.</b> {@code gameTime} restarts in a level that is loaded from an
     *       older save, and the cache is static, so {@code countedAt > now} is possible; answering "stale"
     *       recounts rather than trusting a count from the future.</li>
     * </ul>
     *
     * <p>A window of one tick or less is treated as one tick, so a hand-edited {@code 0} cannot mean "recount
     * on every single spawn attempt".</p>
     */
    public static boolean stale(long now, long countedAt, int countTicks) {
        if (countedAt == Long.MIN_VALUE || countedAt > now) {
            return true;
        }
        return now - countedAt >= Math.max(1, countTicks);
    }
}
