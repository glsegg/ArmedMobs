package com.gfl.tarkovscav.world;

/**
 * The city-capture pool arithmetic, and nothing else (README 7p).
 *
 * <h2>Why this is a separate class with no Minecraft type in sight</h2>
 * <p>Exactly the same reason {@link CityFactions} is separate from {@link CityGarrison}: the numbers that
 * decide a capture are the part that must be <b>measurable without a game</b>. Every method here takes and
 * returns primitives, so {@code tools/spike/CaptureTest.java} can compile THIS shipped source (it is the only
 * mod file that spike needs) and drive the real formula through the real cases - a copy of the formula inside
 * the test would prove nothing. The server-side glue (the overworld gate, the three vetoes, the kill drain,
 * the ledger writes) lives in {@link CityCapture}; the ledger shape lives in {@link GarrisonData}.</p>
 *
 * <h2>The three numbers, and what each one means</h2>
 * <ul>
 *   <li><b>size</b> - a city's opening strength for a faction:
 *       {@code clamp(poolMin + buildingCount * poolPerBuilding, poolMin, poolMax)}. The design bounds are 20
 *       to 100 men; a small city sits at the floor and a city with enough buildings reaches the cap. Both
 *       sides of one city get the SAME opening size, which is what makes the tug-of-war a fair one.</li>
 *   <li><b>drain</b> - every death of a unit of that faction in that city removes {@code drainPerKill}
 *       (default 1, so the pool is literally a head count). Floored at zero and never allowed to go below:
 *       "negative men" would break the "exactly zero decides the capture" rule.</li>
 *   <li><b>captured</b> - the decision at <b>exactly</b> zero. A pool at 0 means that faction can no longer
 *       spawn in that city; the surviving line-up has taken it. The comparison is {@code <= 0} rather than
 *       {@code == 0} so a hand-edited ledger or a future drain larger than the pool can never leave a
 *       "negative but not captured" state.</li>
 * </ul>
 *
 * <h2>Hand-edited toml safety</h2>
 * <p>{@code poolMin} and {@code poolMax} are independent config ranges, so an operator CAN set the floor
 * above the ceiling. {@link #poolSize} therefore normalises the two bounds before clamping: the result is
 * always inside {@code [min(poolMin, poolMax), max(poolMin, poolMax)]} and never negative, whatever the
 * toml says. A pool of zero men from the start would be a silently captured city, which is not a state the
 * game should ever reach by accident.</p>
 */
public final class CapturePools {
    private CapturePools() {
    }

    /**
     * The opening strength of one faction in a city of {@code buildingCount} buildings.
     *
     * <p>The arithmetic is done in {@code long} and only narrowed at the end: a toml with a huge
     * {@code poolPerBuilding} and a city pasted a thousand times must clamp at {@code poolMax}, not overflow
     * into a negative pool.</p>
     */
    public static int poolSize(int buildingCount, int poolMin, int poolMax, int poolPerBuilding) {
        long buildings = Math.max(0, buildingCount);
        long perBuilding = Math.max(0, poolPerBuilding);
        long floor = Math.max(0, poolMin);
        long ceiling = Math.max(0, poolMax);
        long low = Math.min(floor, ceiling);
        long high = Math.max(floor, ceiling);
        long value = floor + buildings * perBuilding;
        return (int) Math.max(low, Math.min(high, value));
    }

    /**
     * One faction's pool after {@code drainPerKill} of its units died: floored at zero, never negative.
     * A non-positive drain is treated as no drain rather than as a heal.
     */
    public static int drain(int strength, int drainPerKill) {
        long per = Math.max(0, drainPerKill);
        return (int) Math.max(0L, (long) strength - per);
    }

    /** The capture decision: a pool of zero (or less) is spent, and that is the exact boundary. */
    public static boolean isCaptured(int strength) {
        return strength <= 0;
    }

    /**
     * The ledger key of one faction's pool in one city: {@code <dimension>|<city>|<faction>} - the shape
     * {@link GarrisonData} documented as the follow-up slot, and the same key shape {@link GarrisonData#key}
     * and {@link CityFactions#cityTag} already use for a city.
     *
     * <p>Pure strings on purpose: the ledger's own {@code poolKey} hands its two objects to this method, so
     * the one place the key shape is defined is here, and the spike can pin it without a registry.</p>
     */
    public static String poolKey(String dimension, String cityKey, String factionName) {
        return dimension + "|" + cityKey + "|" + factionName;
    }
}
