package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Implemented by every sniper mob (README 5q): the pillager sniper and the villager sniper.
 *
 * <p>The behaviour itself lives in {@link SniperBehavior} and the state in {@link SniperPost}; this interface
 * is only what lets the rest of the mod talk about "a sniper" without naming each class - the spawn gate's
 * extra rules, {@code /tarkovscav spawn sniper_villager} and {@code /tarkovscav test sniper} all work off it,
 * so a third sniper needs no change in any of them.</p>
 */
public interface SniperMob {
    /** The shared behaviour instance this mob ticks. */
    SniperBehavior sniper();

    /** One-line report: post, shots from it, last relocation reason, brain state, destination. */
    default String sniperSummary() {
        return sniper().describe();
    }

    /** True while it is walking to a new post. */
    default boolean isRelocating() {
        return sniper().isRelocating();
    }

    /**
     * Applies {@code sniper.followRange} to a sniper that has just entered a level.
     *
     * <p>This is the <b>second half of the fix</b> for the crash that killed the dev server at startup:
     * {@code EntityAttributeCreationEvent} runs before Forge has loaded the common config, so the attribute
     * set is registered with {@link Config#DEFAULT_SNIPER_FOLLOW_RANGE} and the configured number is written
     * here instead, once the entity exists. Every path is covered because Forge calls
     * {@code Entity#onAddedToWorld} for a spawn, a chunk load and a {@code /summon} alike.</p>
     *
     * <p>Guarded by {@link net.minecraftforge.common.ForgeConfigSpec#isLoaded()}: on the client, and during a
     * data-pack reload, the spec can legitimately be unavailable, and reading it then throws the very
     * exception this method exists to avoid. An unloaded config simply leaves the registered default in
     * place, which is the documented default anyway.</p>
     */
    default void applySniperFollowRange() {
        if (!Config.SPEC.isLoaded() || !(this instanceof Mob mob)) {
            return;
        }
        var attribute = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attribute != null) {
            attribute.setBaseValue(Config.SNIPER_FOLLOW_RANGE.get());
        }
    }
}

