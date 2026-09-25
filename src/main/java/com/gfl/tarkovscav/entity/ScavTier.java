package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import net.minecraft.util.RandomSource;

import java.util.Locale;
import java.util.Set;

/**
 * A scav's equipment class. Everything that makes one tier of scav different from another hangs off
 * this: which TaCZ gun types it may carry, how far it engages, and (through {@link Config}) its
 * health, armour, accuracy, aim time and burst length.
 *
 * <p>The gun <em>types</em> come from TaCZ's own gun index ({@code CommonGunIndex#getType()}), not
 * from a hard-coded list of ids, so a custom gun pack automatically feeds the pools. The default
 * pack uses exactly: {@code pistol, smg, rifle, mg, shotgun, sniper, rpg}.</p>
 */
public enum ScavTier {
    /** Cheap and fast: pistols and SMGs, short engagement range, weak armour. */
    PISTOL("pistol", Set.of("pistol", "smg"), 18.0D),
    /** Buckshot up close: devastating inside a building, useless past a street. */
    SHOTGUN("shotgun", Set.of("shotgun"), 14.0D),
    /** The all-rounder: assault rifles and light machine guns. */
    RIFLE("rifle", Set.of("rifle", "mg"), 30.0D),
    /** Patient and lethal: bolt-action and anti-materiel rifles. */
    SNIPER("sniper", Set.of("sniper"), 52.0D);

    private final String id;
    private final Set<String> gunTypes;
    private final double engageRange;

    ScavTier(String id, Set<String> gunTypes, double engageRange) {
        this.id = id;
        this.gunTypes = gunTypes;
        this.engageRange = engageRange;
    }

    /** Config section / lang key name, e.g. {@code pistol}. */
    public String id() {
        return this.id;
    }

    /** TaCZ gun {@code type} values this tier may be issued. */
    public Set<String> gunTypes() {
        return this.gunTypes;
    }

    /** How far (in blocks) this tier tries to fight from. */
    public double engageRange() {
        return this.engageRange;
    }

    public boolean acceptsGunType(String type) {
        return type != null && this.gunTypes.contains(type.toLowerCase(Locale.ROOT));
    }

    public static ScavTier byId(String id) {
        for (ScavTier tier : values()) {
            if (tier.id.equalsIgnoreCase(id)) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Picks a tier using the configured spawn weights. A weight of 0 removes the tier entirely, so
     * a pack that only wants rifle scavs sets the other three to 0.
     */
    public static ScavTier pickWeighted(RandomSource random) {
        int total = 0;
        for (ScavTier tier : values()) {
            total += Math.max(0, Config.tier(tier).spawnWeight.get());
        }
        if (total <= 0) {
            return RIFLE;
        }

        int roll = random.nextInt(total);
        for (ScavTier tier : values()) {
            roll -= Math.max(0, Config.tier(tier).spawnWeight.get());
            if (roll < 0) {
                return tier;
            }
        }
        return RIFLE;
    }
}
