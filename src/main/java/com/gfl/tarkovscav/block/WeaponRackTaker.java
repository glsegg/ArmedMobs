package com.gfl.tarkovscav.block;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavTier;
import com.gfl.tarkovscav.gun.GunBrain;
import com.gfl.tarkovscav.gun.GunLoadout;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.registry.ModEntities;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * The mob side of the weapon rack: an <b>unarmed</b> villager or pillager walks up, takes the weapon, and
 * turns into a fighter (README 5n).
 *
 * <h2>Who may take, and why only them</h2>
 * <ul>
 *   <li>a vanilla {@link Villager} or {@link Pillager} that is <b>not</b> already a {@link GunUser} (the
 *       gunner types extend those classes, so "already armed" would otherwise include them);</li>
 *   <li>an adult, on the ground, within {@code rack.takeRadius} of the rack (vertical distance included);</li>
 *   <li>and it must be genuinely unarmed: an empty main hand. A villager holding a sword was already armed
 *       by somebody else and is not this block's business.</li>
 * </ul>
 *
 * <h2>The weapon decides how it fights</h2>
 * <table border="1">
 *   <caption>armament -> conversion</caption>
 *   <tr><th>Took</th><th>Becomes</th><th>Fights with</th></tr>
 *   <tr><td>TaCZ gun</td><td>{@code gunner_villager} / {@code gunner_pillager}</td><td>the shared
 *       {@link GunBrain} - and it is given <b>that</b> gun if the pool can arm it ({@code GunPool.loadoutFor}),
 *       otherwise no gun at all plus a WARN (a gun it cannot feed must not become a magic gun)</td></tr>
 *   <tr><td>bow</td><td>same two types</td><td>ranged, our {@code ArmedRangedGoal} (arrows)</td></tr>
 *   <tr><td>crossbow</td><td>same two types</td><td>ranged, same goal, drawn longer</td></tr>
 *   <tr><td>sword / axe</td><td>same two types</td><td>melee, the existing {@code NoGunMeleeGoal}</td></tr>
 *   <tr><td>throwable (a grenade of this mod)</td><td><b>nothing happens here, and nothing is logged</b></td>
 *       <td>it is a legitimate payload for a mob that is ALREADY armed: the resupply goal takes it (README 5v).
 *       A WARN would be wrong - and it used to name grenades as accepted in the very same sentence</td></tr>
 *   <tr><td>anything else</td><td><b>nothing happens</b></td><td>left on the rack and WARNed once by item id
 *       ({@link WeaponRackArmament#warnUnsupported}) - the item is not consumed for nothing</td></tr>
 * </table>
 *
 * <h2>No duplication, and no free gear</h2>
 * <p>The stack comes off the rack exactly once ({@link WeaponRackBlockEntity#take()} clears the slot in the
 * same call) and is handed to exactly one new mob. If the new mob cannot be created, the stack is put back.
 * The old mob is discarded only after the new one exists. Nothing else is carried over: no equipment, no name,
 * no NBT - a converted mob is a fresh one standing where the old one stood (documented so nobody expects
 * otherwise).</p>
 */
public final class WeaponRackTaker {
    /** The item a converted mob took, so a reload re-applies the bow/sword instead of the random gun. */
    private static final String TAG_RACK_WEAPON = "tarkovscav:rackWeapon";

    private WeaponRackTaker() {
    }

    /** Server-side, from the rack's own ticker (see {@link WeaponRackBlockEntity#serverTick}). */
    public static void tick(Level level, BlockPos pos, WeaponRackBlockEntity rack) {
        if (rack.isEmpty() || !Config.RACK_ENABLED.get()) {
            return;
        }
        if (rack.infinite() && !Config.RACK_CREATIVE_RACK_ENABLED.get()) {
            return;
        }
        ItemStack offered = rack.held();
        WeaponRackArmament armament = WeaponRackArmament.armamentOf(offered);
        if (armament == WeaponRackArmament.THROWABLE) {
            // A throwable never converts anybody, and this is NOT a problem to report: an already armed unit
            // comes back for it on the resupply path (README 5v, the parallel goal in ScavEntity /
            // GunnerVillagerEntity / GunnerPillagerEntity). WARNing here was a lie - the message even listed
            // "a throwable (a grenade of this mod)" as ACCEPTED, so the log contradicted itself once per tick
            // while a grenade sat on a rack (the user's [rack] unsupported item tarkovscav:he_grenade line).
            return;
        }
        if (armament == WeaponRackArmament.UNSUPPORTED) {
            WeaponRackArmament.warnUnsupported(offered, "on the rack at " + pos.toShortString());
            return;
        }
        Mob recruit = findRecruit(level, pos);
        if (recruit == null) {
            return;
        }
        convert(level, rack, recruit, offered, armament);
    }

    /** The nearest acceptable unarmed villager/pillager, ordered by {@code rack.priority}. */
    @Nullable
    public static Mob findRecruit(Level level, BlockPos pos) {
        double radius = Config.RACK_TAKE_RADIUS.get();
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class,
                new net.minecraft.world.phys.AABB(pos).inflate(radius),
                mob -> isRecruit(mob));
        if (candidates.isEmpty()) {
            return null;
        }
        String priority = Config.RACK_PRIORITY.get();
        double centreX = pos.getX() + 0.5D;
        double centreY = pos.getY() + 0.5D;
        double centreZ = pos.getZ() + 0.5D;
        Comparator<Mob> byDistance = Comparator.comparingDouble(
                mob -> mob.distanceToSqr(centreX, centreY, centreZ));
        // "priority" is a preference, not a filter: with nobody of the preferred type in range, the other
        // type still gets served, otherwise a rack in a village would simply never work.
        Comparator<Mob> order = switch (priority == null ? "nearest" : priority.toLowerCase(java.util.Locale.ROOT)) {
            case "villager" -> Comparator.<Mob>comparingInt(mob -> mob instanceof Villager ? 0 : 1).thenComparing(byDistance);
            case "pillager" -> Comparator.<Mob>comparingInt(mob -> mob instanceof Pillager ? 0 : 1).thenComparing(byDistance);
            default -> byDistance;
        };
        candidates.sort(order);
        return candidates.get(0);
    }

    /** True for a mob the rack may arm. */
    public static boolean isRecruit(Mob mob) {
        if (mob instanceof GunUser || mob.isBaby() || !mob.onGround()) {
            return false;
        }
        if (!(mob instanceof Villager) && !(mob instanceof Pillager)) {
            return false;
        }
        // Genuinely unarmed: an empty main hand. Anything else was armed by somebody else already.
        return mob.getMainHandItem().isEmpty();
    }

    /** Takes the stack and turns the mob into the matching gunner type. */
    private static void convert(Level level, WeaponRackBlockEntity rack, Mob recruit, ItemStack offered,
                                WeaponRackArmament armament) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        EntityType<? extends Mob> type = recruit instanceof Villager
                ? ModEntities.GUNNER_VILLAGER.get()
                : ModEntities.GUNNER_PILLAGER.get();
        Mob armed = type.create(server);
        if (armed == null) {
            TarkovScav.LOGGER.warn("[rack] could not create {} for {}; the item stays on the rack",
                    BuiltInRegistries.ENTITY_TYPE.getKey(type), recruit.getName().getString());
            return;
        }
        // The one take: on a normal rack the slot is empty after this line and `offered` is ours alone; on a
        // creative rack this is a copy and the template stays, which is what makes it an endless source.
        ItemStack taken = rack.claim();
        if (taken.isEmpty()) {
            return;
        }
        if (rack.infinite()) {
            TarkovScav.LOGGER.info("[rack] creative: item not consumed ({} stays on the rack at {})",
                    taken.getHoverName().getString(), rack.getBlockPos().toShortString());
        }
        armed.moveTo(recruit.getX(), recruit.getY(), recruit.getZ(), recruit.getYRot(), recruit.getXRot());
        armed.setYHeadRot(recruit.getYHeadRot());
        armed.finalizeSpawn(server, server.getCurrentDifficultyAt(armed.blockPosition()),
                MobSpawnType.CONVERSION, null, null);
        armed.setPersistenceRequired();

        boolean armedWithIt = applyArmament(armed, taken, armament);
        server.addFreshEntity(armed);
        recruit.discard();

        rack.setTakeCooldown(Math.max(1, Config.RACK_TAKE_COOLDOWN_TICKS.get()));
        TarkovScav.LOGGER.info("[rack] {} took {} ({}) at {} -> {} (armed={}){}",
                recruit.getName().getString(), taken.getHoverName().getString(), armament.id(),
                rack.getBlockPos().toShortString(), armed.getName().getString(), armedWithIt,
                rack.infinite() ? " [creative: template kept]" : "");
    }

    /**
     * Gives the converted mob what it took, in the form its combat path understands. Returns false when the
     * weapon could not be used (a TaCZ gun the pool cannot feed), which is always WARNed.
     */
    static boolean applyArmament(Mob armed, ItemStack taken, WeaponRackArmament armament) {
        switch (armament) {
            case TACZ_GUN -> {
                if (!(armed instanceof GunUser user) || user.gunBrain() == null) {
                    // Only the two gunner types can use a gun, and they are the only ones we create.
                    TarkovScav.LOGGER.warn("[rack] {} is not a gun user; the gun was consumed anyway",
                            armed.getName().getString());
                    return false;
                }
                // IGun is the capability instance; getGunId is called on it (it is not a static helper).
                IGun gun = IGun.getIGunOrNull(taken);
                ResourceLocation gunId = gun == null ? null : gun.getGunId(taken);
                ScavTier tier = user.scavTier();
                GunLoadout loadout = gunId == null ? null : GunPool.loadoutFor(tier, gunId);
                if (loadout == null) {
                    TarkovScav.LOGGER.warn("[rack] {} took a TaCZ gun whose id {} is not in the pool for"
                                    + " tier {} - converting unarmed (melee only). The gun was consumed.",
                            armed.getName().getString(), gunId, tier);
                    return false;
                }
                user.gunBrain().equipLoadout(loadout);
                return true;
            }
            case BOW, CROSSBOW, MELEE -> {
                armed.setItemInHand(InteractionHand.MAIN_HAND, taken);
                armed.getPersistentData().put(TAG_RACK_WEAPON, taken.save(new CompoundTag()));
                return true;
            }
            default -> {
                WeaponRackArmament.warnUnsupported(taken, "after conversion");
                return false;
            }
        }
    }

    /**
     * Re-applies the rack weapon after a load. Called from the two gunner entities'
     * {@code readAdditionalSaveData} <b>after</b> their own gun restore, so the bow/sword a converted mob
     * took wins over the random gun the restore would otherwise put in its hand. Without this a converted
     * archer would silently turn back into a gunner on the next server restart.
     */
    public static void restoreArmament(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(TAG_RACK_WEAPON)) {
            return;
        }
        ItemStack taken = ItemStack.of(data.getCompound(TAG_RACK_WEAPON));
        if (taken.isEmpty()) {
            data.remove(TAG_RACK_WEAPON);
            return;
        }
        mob.setItemInHand(InteractionHand.MAIN_HAND, taken);
    }

    /** Does this entity already carry a rack weapon? Used by the debug/test output. */
    public static boolean hasRackWeapon(Entity entity) {
        return entity.getPersistentData().contains(TAG_RACK_WEAPON);
    }
}
