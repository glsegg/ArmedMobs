package com.gfl.tarkovscav.loot;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavTier;
import com.gfl.tarkovscav.gun.GunLoadout;
import com.gfl.tarkovscav.gun.GunPool;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The TaCZ side of the city-chest loot modifier, and the ONLY place in this mod that touches the TaCZ API for
 * loot. Everything here runs after two independent guards - the modifier's JSON {@code tarkovscav:mod_loaded}
 * condition and the runtime {@code ModList.get().isLoaded("tacz")} check in
 * {@link CityChestLootModifier#doApply} - so a world without TaCZ never reaches a line of it.
 *
 * <h2>Not one TaCZ item id is written down</h2>
 * <p>Both halves come out of TaCZ's own live index at runtime, through the code paths the mobs already use:</p>
 * <ul>
 *   <li><b>ammo</b>: the calibers of the guns the mod is allowed to issue
 *       ({@code GunPool#allAllowed()} plus each gun's {@code GunData#getAmmoId()}), built with
 *       {@code GunPool#buildAmmo} - so a gun pack's own ammunition appears here automatically and an
 *       extended/odd stack size is clamped to the item's maximum;</li>
 *   <li><b>the gun</b>: {@code GunPool#rollLoadout} for a tier and {@code GunPool#buildGun(loadout, random,
 *       owner)} - the existing build path, which is also the one that installs RANDOM ATTACHMENTS through
 *       {@code GunAttachments#apply} and then refills the magazine. There is deliberately no second
 *       gun-building path here.</li>
 * </ul>
 *
 * <p>{@code tools/selftest_loot_modifier.js} greps the whole source tree for {@code tacz:} item id strings
 * and for TaCZ API types used outside this file, which is how "no hard-coded TaCZ id" is checked rather than
 * claimed.</p>
 */
public final class CityChestExtras {
    /** How many ammo stacks one city chest gets, inclusive lower/upper bound. */
    public static final int AMMO_STACKS_MIN = 1;
    public static final int AMMO_STACKS_MAX = 3;
    /** The size band of one ammo stack, before the item's own maximum is applied. */
    public static final int AMMO_COUNT_MIN = 6;
    public static final int AMMO_COUNT_MAX = 24;
    /** The chance of the gun, in PERCENT - the low weight the brief asks for. */
    public static final int GUN_CHANCE_PERCENT = 5;
    /**
     * Which tier the rare gun is rolled from, as (tier, percent) pairs. A rifle most of the time (a city
     * chest is worth carrying), a shotgun sometimes, a pistol now and then - the same tiers
     * {@code ScavTier#pickWeighted} uses for the mobs themselves.
     */
    private static final ScavTier[] GUN_TIERS = {ScavTier.RIFLE, ScavTier.SHOTGUN, ScavTier.PISTOL};
    private static final int[] GUN_TIER_WEIGHTS = {50, 30, 20};

    /** Ammo ids of every gun TaCZ lets this mod issue, sorted; empty when the index is not loaded yet. */
    private static List<ResourceLocation> cachedAmmo;

    private CityChestExtras() {
    }

    /**
     * What one city chest receives. Never throws and never returns null: an empty list means "TaCZ has no
     * usable index right now", which the caller must treat exactly like "nothing to add".
     */
    public static List<ItemStack> roll(RandomSource random, String owner) {
        List<ItemStack> extras = new ArrayList<>();
        int stacks = AMMO_STACKS_MIN + random.nextInt(AMMO_STACKS_MAX - AMMO_STACKS_MIN + 1);
        List<ResourceLocation> ammo = ammoIds();
        if (!ammo.isEmpty()) {
            for (int i = 0; i < stacks; i++) {
                ResourceLocation caliber = ammo.get(random.nextInt(ammo.size()));
                int count = AMMO_COUNT_MIN + random.nextInt(AMMO_COUNT_MAX - AMMO_COUNT_MIN + 1);
                ItemStack stack = GunPool.buildAmmo(caliber, count);
                if (!stack.isEmpty()) {
                    extras.add(stack);
                }
            }
        }
        ItemStack gun = rollGun(random, owner);
        if (gun != null && !gun.isEmpty()) {
            extras.add(gun);
        }
        return extras;
    }

    /** The rare gun, or null when no tier has a gun available (an empty pool is not an error). */
    @Nullable
    private static ItemStack rollGun(RandomSource random, String owner) {
        if (random.nextInt(100) >= GUN_CHANCE_PERCENT) {
            return null;
        }
        int roll = random.nextInt(GUN_TIER_WEIGHTS[0] + GUN_TIER_WEIGHTS[1] + GUN_TIER_WEIGHTS[2]);
        ScavTier tier = GUN_TIERS[GUN_TIERS.length - 1];
        for (int i = 0; i < GUN_TIERS.length; i++) {
            roll -= GUN_TIER_WEIGHTS[i];
            if (roll < 0) {
                tier = GUN_TIERS[i];
                break;
            }
        }
        // The existing path: the tier's pool, the gun's own index, the random-attachment install and the
        // magazine refill all live inside these two calls.
        GunLoadout loadout = GunPool.rollLoadout(tier, random);
        if (loadout == null) {
            return null;
        }
        return GunPool.buildGun(loadout, random, owner);
    }

    /**
     * The calibers a city chest may hold: one per gun this mod may issue, so the ammunition always fits
     * something a troop can carry. Sorted, so a given index always produces the same list; the cache is
     * dropped by {@code GunPool#invalidate} indirectly (a reload changes the index, and this list is
     * recomputed whenever it comes back empty).
     */
    public static synchronized List<ResourceLocation> ammoIds() {
        if (cachedAmmo != null && !cachedAmmo.isEmpty()) {
            return cachedAmmo;
        }
        List<ResourceLocation> ids = new ArrayList<>();
        for (ResourceLocation gunId : GunPool.allAllowed()) {
            GunPool.index(gunId).map(index -> index.getGunData().getAmmoId())
                    .filter(java.util.Objects::nonNull)
                    .ifPresent(id -> {
                        if (!ids.contains(id)) {
                            ids.add(id);
                        }
                    });
        }
        if (ids.isEmpty()) {
            // No allowed gun at all (every id blacklisted): fall back to the pack's own ammo index, which is
            // still TaCZ's live data and still contains no hard-coded id.
            for (Map.Entry<ResourceLocation, ?> entry : TimelessAPI.getAllCommonAmmoIndex()) {
                if (entry.getKey() != null) {
                    ids.add(entry.getKey());
                }
            }
        }
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        if (!ids.isEmpty()) {
            cachedAmmo = List.copyOf(ids);
        }
        return ids;
    }

    /** One-line description of one roll, for the log line the user asked for. */
    public static String describe(List<ItemStack> extras) {
        StringBuilder text = new StringBuilder();
        for (ItemStack stack : extras) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(stack.getCount()).append('x').append(stack.getItem());
        }
        return text.length() == 0 ? "nothing (TaCZ has no usable index)" : text.toString();
    }

    /** Drops the cached caliber list (used by the gun-pool invalidation on a reload). */
    public static synchronized void invalidate() {
        cachedAmmo = null;
    }
}
