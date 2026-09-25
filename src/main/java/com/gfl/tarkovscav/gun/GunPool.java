package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavTier;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The gun shop these mobs draw from.
 *
 * <p>Pools are built from <b>TaCZ's own live index</b> ({@code TimelessAPI.getAllCommonGunIndex()}),
 * not from a hard-coded id list, so any gun pack the player installs is picked up automatically.
 * Classification uses TaCZ's gun {@code type} field ({@code pistol, smg, rifle, mg, shotgun,
 * sniper, rpg} in the default pack); the config can then blacklist ids, whitelist ids, or drop whole
 * types.</p>
 *
 * <p>The cache is dropped on every datapack/resource reload and on server start, because that is
 * when TaCZ rebuilds its index.</p>
 */
public final class GunPool {
    private static final Map<ScavTier, List<ResourceLocation>> BY_TIER = new EnumMap<>(ScavTier.class);
    private static final Map<ResourceLocation, String> TYPES = new LinkedHashMap<>();
    /** Guns whose fire-mode decision has already been logged, so the INFO line appears once per gun. */
    private static final java.util.Set<ResourceLocation> FIRE_MODE_LOGGED = new java.util.HashSet<>();
    private static boolean built;
    private static int rejectedByFilter;
    private static int rejectedByScript;

    private GunPool() {
    }

    public static synchronized void invalidate() {
        built = false;
        BY_TIER.clear();
        TYPES.clear();
        FIRE_MODE_LOGGED.clear();
        rejectedByFilter = 0;
        rejectedByScript = 0;
    }

    /** All gun ids TaCZ currently knows, keyed to their type, before any config filtering. */
    private static synchronized void build() {
        if (built) {
            return;
        }
        built = true;
        BY_TIER.clear();
        TYPES.clear();
        rejectedByFilter = 0;
        rejectedByScript = 0;

        for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
            ResourceLocation id = entry.getKey();
            CommonGunIndex index = entry.getValue();
            if (id == null || index == null) {
                continue;
            }

            String type = index.getType() == null ? "" : index.getType().toLowerCase(Locale.ROOT);
            if (!Config.gunIdAllowed(id.toString()) || Config.gunTypeExcluded(type)) {
                rejectedByFilter++;
                continue;
            }
            // README 5p: a gun whose Lua script is not trusted never reaches a mob. A broken script throws
            // inside TaCZ's own tick and crashes the server, and there is no way to catch it - so the pool is
            // the only place this can be fixed. Reported once per id by ScriptedGuns.
            if (ScriptedGuns.isBlocked(id)) {
                rejectedByScript++;
                continue;
            }
            TYPES.put(id, type);

            for (ScavTier tier : ScavTier.values()) {
                if (tier.acceptsGunType(type)) {
                    BY_TIER.computeIfAbsent(tier, ignored -> new ArrayList<>()).add(id);
                }
            }
        }

        for (ScavTier tier : ScavTier.values()) {
            BY_TIER.computeIfAbsent(tier, ignored -> new ArrayList<>()).sort(ResourceLocation::compareTo);
        }
    }

    /** The gun ids a tier may be issued, after the config filters. Never null, possibly empty. */
    public static synchronized List<ResourceLocation> forTier(ScavTier tier) {
        build();
        return List.copyOf(BY_TIER.getOrDefault(tier, List.of()));
    }

    /** Every gun TaCZ knows that passed the filters (any tier). */
    public static synchronized List<ResourceLocation> allAllowed() {
        build();
        return List.copyOf(TYPES.keySet());
    }

    public static synchronized String typeOf(ResourceLocation gunId) {
        build();
        return TYPES.get(gunId);
    }

    public static Optional<CommonGunIndex> index(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId);
    }

    /**
     * Picks a gun for this tier at random and reads everything else out of TaCZ's index. Returns
     * null when the pool is empty (TaCZ index not loaded, or every gun filtered out) - the caller
     * then falls back to plain melee, which is a lot better than an invisible broken gun.
     */
    public static GunLoadout rollLoadout(ScavTier tier, RandomSource random) {
        List<ResourceLocation> pool = forTier(tier);
        if (pool.isEmpty()) {
            return null;
        }

        // Try a few candidates in case one has no usable index entry (a broken gun pack).
        for (int attempt = 0; attempt < 8; attempt++) {
            ResourceLocation gunId = pool.get(random.nextInt(pool.size()));
            Optional<CommonGunIndex> index = index(gunId);
            if (index.isEmpty()) {
                continue;
            }
            CommonGunIndex gun = index.get();
            ResourceLocation ammoId = gun.getGunData().getAmmoId();
            if (ammoId == null) {
                continue;
            }
            int magazine = Math.max(1, gun.getGunData().getAmmoAmount());
            boolean manual = gun.getGunData().getBolt() == Bolt.MANUAL_ACTION;
            return new GunLoadout(tier, gunId, ammoId, gun.getType(),
                    magazine, gun.getGunData().getRoundsPerMinute(), manual);
        }
        return null;
    }

    /**
     * Rebuilds the loadout for a gun id that was read back from a saved mob, so a reloaded world does
     * not silently re-roll a scav's weapon. Returns null when TaCZ no longer knows that gun (gun pack
     * removed): the caller then rolls a fresh one.
     */
    public static GunLoadout loadoutFor(ScavTier tier, ResourceLocation gunId) {
        Optional<CommonGunIndex> index = index(gunId);
        if (index.isEmpty()) {
            return null;
        }
        CommonGunIndex gun = index.get();
        ResourceLocation ammoId = gun.getGunData().getAmmoId();
        if (ammoId == null) {
            return null;
        }
        return new GunLoadout(tier, gunId, ammoId, gun.getType(),
                Math.max(1, gun.getGunData().getAmmoAmount()),
                gun.getGunData().getRoundsPerMinute(),
                gun.getGunData().getBolt() == Bolt.MANUAL_ACTION);
    }

    /**
     * The fire mode a stack of {@code gunId} is built with (README 5p).
     *
     * <p>With {@code guns.respectDeclaredFireModes = false} - the default, and what every shipped jar did
     * before - this is always {@link FireMode#AUTO}.</p>
     *
     * <p>With it true the gun's own data file decides: AUTO when the gun declares AUTO, otherwise the
     * <b>first</b> mode it declares, otherwise AUTO. A gun TaCZ does not index, or one whose declared set is
     * empty, is therefore treated exactly as before - this option can never leave a mob unable to shoot.</p>
     */
    public static synchronized FireMode fireModeFor(ResourceLocation gunId) {
        if (!Config.respectDeclaredFireModes()) {
            return FireMode.AUTO;
        }
        List<FireMode> declared = declaredFireModes(gunId);
        FireMode chosen = declared.isEmpty() || declared.contains(FireMode.AUTO) ? FireMode.AUTO
                : declared.get(0);
        if (FIRE_MODE_LOGGED.add(gunId)) {
            TarkovScav.LOGGER.info("respectDeclaredFireModes=true: {} declares {} -> mob stack uses {}",
                    gunId, declared.isEmpty() ? "[]" : declared.toString(), chosen);
        }
        return chosen;
    }

    /**
     * The fire modes a gun's own data file declares ({@code GunData#getFireModeSet}). Empty means "TaCZ has
     * no answer" and is never cached: TaCZ may not have loaded the pack yet.
     */
    public static List<FireMode> declaredFireModes(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getFireModeSet())
                .filter(list -> list != null && !list.isEmpty())
                .orElse(List.of());
    }

    /**
     * Builds the gun stack with a full magazine. TaCZ reads ammo out of the stack's own NBT for
     * magazine-fed guns, and out of the shooter's item handler for inventory-fed ones - our mobs get
     * an item handler (see {@link GunCapabilities}) so both paths work.
     */
    public static ItemStack buildGun(GunLoadout loadout) {
        return buildGun(loadout, null, null);
    }

    /**
     * The same, with the "gun-modder" random attachment pool applied (README 5p).
     *
     * <p>The order matters and is deliberate: the magazine is set from TaCZ's index first, then the
     * attachments are installed (TaCZ decides which fit), and only then is the gun topped up - so an extended
     * magazine ends up full, and the capacity the mob uses is the <b>re-read</b> one rather than the one this
     * method was told. Passing {@code null} for the random skips the pool entirely (used by the gun-pool
     * self-test, which must stay deterministic).</p>
     */
    public static ItemStack buildGun(GunLoadout loadout, @Nullable RandomSource random,
                                     @Nullable String owner) {
        // Read once, so both the normal build and the forceBuild fallback agree (README 5p).
        FireMode fireMode = fireModeFor(loadout.gunId());
        ItemStack stack = GunItemBuilder.create()
                .setId(loadout.gunId())
                .setAmmoCount(loadout.magazineSize())
                .setAmmoInBarrel(true)
                .setFireMode(fireMode)
                .build();
        if (stack.isEmpty()) {
            // build() refuses unknown ids; forceBuild() is the documented escape hatch.
            stack = GunItemBuilder.create()
                    .setId(loadout.gunId())
                    .setAmmoCount(loadout.magazineSize())
                    .setAmmoInBarrel(true)
                    .setFireMode(fireMode)
                    .forceBuild();
        }
        if (random != null && !stack.isEmpty()) {
            int mods = GunAttachments.apply(stack, random, owner);
            if (mods > 0) {
                GunAttachments.refillToCapacity(stack);
            }
        }
        return stack;
    }

    /**
     * One stack of the ammunition this gun eats.
     *
     * <p>The count is clamped to the item's own maximum stack size: TaCZ ammo items are not all
     * stack-to-64 (9mm is 60), and an over-sized stack is the kind of thing that only shows up as a
     * mysterious desync later.</p>
     */
    public static ItemStack buildAmmo(ResourceLocation ammoId, int count) {
        ItemStack stack = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
        if (stack.isEmpty()) {
            return stack;
        }
        stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }

    /** Logs what the pool looks like for this world - the first thing to read when a mob has no gun. */
    public static synchronized void logSummary() {
        build();
        if (TYPES.isEmpty() && rejectedByFilter == 0) {
            TarkovScav.LOGGER.warn("TaCZ gun index is empty - no guns available. Is the TaCZ gun pack loaded?");
            return;
        }
        TarkovScav.LOGGER.info("TaCZ gun pool: {} gun(s) allowed, {} filtered out by config, {} kept out by the"
                + " scripted-gun rule", TYPES.size(), rejectedByFilter, rejectedByScript);
        if (rejectedByScript > 0) {
            TarkovScav.LOGGER.info("  scripted-gun rule: {}", ScriptedGuns.describe(8));
        }
        for (ScavTier tier : ScavTier.values()) {
            List<ResourceLocation> pool = forTier(tier);
            TarkovScav.LOGGER.info("  {} tier ({} guns): {}", tier.id(), pool.size(), pool);
        }
    }

    /** How many guns the scripted-gun rule kept out of the pools (README 5p), for the commands. */
    public static synchronized int rejectedByScript() {
        build();
        return rejectedByScript;
    }

    /** How many guns the config filters (blacklist/whitelist/type) kept out. */
    public static synchronized int rejectedByFilter() {
        build();
        return rejectedByFilter;
    }
}
