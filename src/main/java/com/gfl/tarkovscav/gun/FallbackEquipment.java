package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * What a unit is handed when the pack has no TaCZ (README 5ac).
 *
 * <h2>The fallback, in one paragraph</h2>
 * <p>TaCZ is no longer a hard dependency: without it the mod loads, the nine units still spawn inside cities,
 * still path, take cover, suppress, bound, retreat, throw grenades, open doors and climb ladders - they fight
 * with a <b>bow or a crossbow</b> instead of a firearm. The shooting already existed: that is exactly what
 * {@link ArmedRangedGoal} does for a mob that took a bow or crossbow off a weapon rack, and it hands the shot
 * to the mob's own {@code RangedAttackMob#performRangedAttack}. What was missing is the equipment, which is
 * this class.</p>
 *
 * <h2>Why the weapon appears in the main hand</h2>
 * <p>{@code ArmedRangedGoal} looks at the MAIN HAND ({@code ProjectileUtil.getWeaponHoldingHand} in the shot
 * itself, and the goal's own {@code weapon()} selector), so the fallback has to put the weapon there rather
 * than in a hidden inventory. Arrows go into the mob's own inventory because
 * {@code performRangedAttack} reads them with {@code getProjectile(weapon)} - the same path a vanilla skeleton
 * uses, so the arrow type, the difficulty-scaled inaccuracy and the drop-off are the vanilla ones rather than
 * a new set of numbers to tune.</p>
 *
 * <h2>What it does NOT touch</h2>
 * <p>Armour, health and the tier attributes are applied by the entity's own per-tier code and are unchanged
 * (a TaCZ-free pack still gets four tiers of bandits).</p>
 *
 * <h2>The arrows are cosmetic, and that is measured rather than assumed</h2>
 * <p>The offhand stack is a carried quiver, not a magazine: the shot goes through {@code getProjectile(weapon)}
 * into {@code ProjectileUtil.getMobArrow} - the same path a vanilla skeleton uses - and nothing in that path
 * decrements the stack, which is why a vanilla skeleton never runs out of arrows either. So
 * {@code guns.fallbackArrows} changes what a unit visibly carries and nothing else, and a TaCZ-free unit
 * effectively has unlimited arrows. Consuming the stack here would fight the vanilla bow/crossbow handling
 * (the item's own {@code releaseUsing} is what spends arrows for a player), so it is deliberately not done:
 * the fallback is meant to be the weaker way to fight, and it is already weaker by weapon, range and rate of
 * fire. {@code Config.GUNS_FALLBACK_ARROWS} and README 5ac say the same thing in the same words, and
 * {@code tools/selftest_no_tacz.js} pins this behaviour so the claim cannot rot.</p>
 */
public final class FallbackEquipment {
    private FallbackEquipment() {
    }

    /** True when the configured fallback weapon is the crossbow (the default). */
    public static boolean wantsCrossbow() {
        return !"bow".equalsIgnoreCase(Config.GUNS_FALLBACK_WEAPON.get().trim());
    }

    /**
     * Hands this unit its fallback weapon and ammunition. Called from {@code finalizeSpawn} of the three base
     * classes when {@link TaczPresence#loaded()} is false, so it never runs in a pack that has TaCZ.
     */
    public static void equip(Mob mob, RandomSource random) {
        ItemStack weapon = new ItemStack(wantsCrossbow() ? Items.CROSSBOW : Items.BOW);
        // A small chance of a enchanted fallback weapon, using the same table the rack-taker uses for a
        // bow/crossbow it did not build itself (Power/Infinity on a bow, Quick Charge on a crossbow). Kept
        // simple and level-bounded: an elite with a Power II bow should be a threat, not a one-shot.
        if (random.nextFloat() < 0.15F) {
            if (weapon.is(Items.BOW)) {
                EnchantmentHelper.setEnchantments(
                        java.util.Map.of(Enchantments.POWER_ARROWS, 1 + random.nextInt(2)),
                        weapon);
            } else {
                EnchantmentHelper.setEnchantments(
                        java.util.Map.of(Enchantments.QUICK_CHARGE, 1 + random.nextInt(2)),
                        weapon);
            }
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        // No setDropChance call: each of the three base classes already overrides getEquipmentDropChance to
        // return 0 for the MAIN HAND (the gun path made the same choice for the same reason - a city full of
        // lootable weapons would hand the player the mod's whole arsenal), and the offhand arrow stack uses
        // the vanilla chance, which is a small and deliberate exception.

        int arrows = Math.max(0, Config.GUNS_FALLBACK_ARROWS.get());
        if (arrows > 0) {
            mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, arrows));
        }
        // A crossbow cannot be fired without the charging bit being driven by a goal, and ArmedRangedGoal
        // drives it; nothing to do here beyond leaving the stack in hand.
    }

    /** One line for the debug output: what a unit of this install actually carries. */
    public static String describe() {
        return wantsCrossbow()
                ? "fallback weapon: crossbow + " + Config.GUNS_FALLBACK_ARROWS.get() + " arrow(s)"
                : "fallback weapon: bow + " + Config.GUNS_FALLBACK_ARROWS.get() + " arrow(s)";
    }
}
