package com.gfl.tarkovscav.registry;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import com.gfl.tarkovscav.entity.BearPillagerEntity;
import com.gfl.tarkovscav.entity.ElitePillagerEntity;
import com.gfl.tarkovscav.entity.EliteVillagerEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.entity.UsecVillagerEntity;
import com.gfl.tarkovscav.entity.SniperPillagerEntity;
import com.gfl.tarkovscav.entity.SniperVillagerEntity;
import com.gfl.tarkovscav.command.SignalStickEntity;
import com.gfl.tarkovscav.grenade.GrenadeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, TarkovScav.MOD_ID);

    public static final RegistryObject<EntityType<ScavEntity>> SCAV = ENTITY_TYPES.register("scav",
            () -> EntityType.Builder.<ScavEntity>of(ScavEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.9F)
                    .clientTrackingRange(12)
                    .build(TarkovScav.id("scav").toString()));

    public static final RegistryObject<EntityType<GunnerPillagerEntity>> GUNNER_PILLAGER =
            ENTITY_TYPES.register("gunner_pillager",
                    () -> EntityType.Builder.<GunnerPillagerEntity>of(GunnerPillagerEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("gunner_pillager").toString()));

    /**
     * The gunner villager. {@code MobCategory.MONSTER} so it takes part in the normal hostile spawn
     * pass (and in the mob cap) exactly like the other two; note that this does <em>not</em> make iron
     * golems attack it - they target {@code Enemy}, and {@code Villager} is not one.
     */
    public static final RegistryObject<EntityType<GunnerVillagerEntity>> GUNNER_VILLAGER =
            ENTITY_TYPES.register("gunner_villager",
                    () -> EntityType.Builder.<GunnerVillagerEntity>of(GunnerVillagerEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("gunner_villager").toString()));

    /**
     * The sniper pillager (README 5q). Same body and renderer as the gunner pillager; what differs is the
     * FOLLOW_RANGE (it engages from far away, which is what makes a post worth holding) and the post /
     * relocation behaviour in {@code SniperPillagerEntity}.
     */
    public static final RegistryObject<EntityType<SniperPillagerEntity>> SNIPER_PILLAGER =
            ENTITY_TYPES.register("sniper_pillager",
                    () -> EntityType.Builder.<SniperPillagerEntity>of(SniperPillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(16)
                            .build(TarkovScav.id("sniper_pillager").toString()));

    /**
     * The sniper villager (README 5q): the villager half of the sniper pair. Same body, renderer, arm pose and
     * sounds as {@code gunner_villager}, same {@code SniperBehavior} as {@code sniper_pillager} - what is new
     * is only the combination, which is exactly what the user asked for ("给村民也加个狙击手的").
     *
     * <p>{@code MobCategory.MONSTER} like the gunner villager, so it takes part in the normal hostile spawn
     * pass and the mob cap; that does not make iron golems attack it ({@code Villager} is not an
     * {@code Enemy}).</p>
     */
    public static final RegistryObject<EntityType<SniperVillagerEntity>> SNIPER_VILLAGER =
            ENTITY_TYPES.register("sniper_villager",
                    () -> EntityType.Builder.<SniperVillagerEntity>of(SniperVillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(16)
                            .build(TarkovScav.id("sniper_villager").toString()));

    // ------------------------------------------------------------------ faction troops (README 5y)

    /** The USEC villager: a gunner villager that fights and talks like a USEC operator. */
    public static final RegistryObject<EntityType<UsecVillagerEntity>> USEC_VILLAGER =
            ENTITY_TYPES.register("usec_villager",
                    () -> EntityType.Builder.<UsecVillagerEntity>of(UsecVillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("usec_villager").toString()));

    /** The BEAR pillager: the illager half of the same idea. */
    public static final RegistryObject<EntityType<BearPillagerEntity>> BEAR_PILLAGER =
            ENTITY_TYPES.register("bear_pillager",
                    () -> EntityType.Builder.<BearPillagerEntity>of(BearPillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("bear_pillager").toString()));

    /** The elite villager: 40 health, a rolled armor class and the elite accuracy profile. */
    public static final RegistryObject<EntityType<EliteVillagerEntity>> ELITE_VILLAGER =
            ENTITY_TYPES.register("elite_villager",
                    () -> EntityType.Builder.<EliteVillagerEntity>of(EliteVillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("elite_villager").toString()));

    /** The elite pillager: the illager half of the elite pair. */
    public static final RegistryObject<EntityType<ElitePillagerEntity>> ELITE_PILLAGER =
            ENTITY_TYPES.register("elite_pillager",
                    () -> EntityType.Builder.<ElitePillagerEntity>of(ElitePillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("elite_pillager").toString()));

    /**
     * The thrown signal stick (the command system). {@code MobCategory.MISC} like the grenade - it is a
     * projectile, not a mob, so it has no attributes and no spawn placement, which is what
     * {@code tools/selftest_entity_registry.js} expects before it demands a spawn egg.
     */
    public static final RegistryObject<EntityType<SignalStickEntity>> SIGNAL_STICK =
            ENTITY_TYPES.register("signal_stick",
                    () -> EntityType.Builder.<SignalStickEntity>of(SignalStickEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(8)
                            .updateInterval(4)
                            .build(TarkovScav.id("signal_stick").toString()));
    private ModEntities() {
    }

    /**
     * The thrown grenade (README 5v). ONE entity type for all five throwables: the kind travels in NBT and in
     * the item the entity draws, so adding a sixth grenade needs no new entity, no new renderer and no new
     * registration - and the vanilla {@code ThrownItemRenderer} draws whichever item it is.
     *
     * <p>{@code MobCategory.MISC} with no attributes and no spawn placement: a projectile is not a mob, which is
     * exactly what {@code tools/selftest_entity_registry.js} checks before it demands a spawn egg for
     * something.</p>
     */
    public static final RegistryObject<EntityType<GrenadeEntity>> GRENADE =
            ENTITY_TYPES.register("grenade",
                    () -> EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(8)
                            .updateInterval(2)
                            .build(TarkovScav.id("grenade").toString()));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(ModEntities::onEntityAttributes);
    }
    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(SCAV.get(), ScavEntity.createScavAttributes().build());
        // The gunner keeps the vanilla pillager's attribute set; the tier only overwrites
        // health and armour at spawn time (see GunnerPillagerEntity#applyTierAttributes).
        event.put(GUNNER_PILLAGER.get(), Pillager.createAttributes().build());
        // Same idea for the villager: the vanilla villager attribute set (movement speed 0.5, follow
        // range 48) with per-tier health and armour applied at spawn.
        event.put(GUNNER_VILLAGER.get(), Villager.createAttributes().build());
        event.put(SNIPER_PILLAGER.get(), SniperPillagerEntity.createSniperAttributes().build());
        // The villager base set (movement speed 0.5) plus the sniper's own sight range.
        event.put(SNIPER_VILLAGER.get(), SniperVillagerEntity.createSniperAttributes().build());
        // The faction troops: 40 health on top of their family's attribute set (README 5y). Everything else -
        // movement, follow range, armour POINTS - comes from the vanilla family, except that each troop pins
        // its armour points to 0 through forcedArmorPoints(), because the rolled armor class is its only
        // reduction (see the class comment on ArmorClass).
        event.put(USEC_VILLAGER.get(), troopAttributes(Villager.createAttributes()).build());
        event.put(ELITE_VILLAGER.get(), troopAttributes(Villager.createAttributes()).build());
        event.put(BEAR_PILLAGER.get(), troopAttributes(Pillager.createAttributes()).build());
        event.put(ELITE_PILLAGER.get(), troopAttributes(Pillager.createAttributes()).build());
    }

    /**
     * Without this the biome modifier's spawn entries would be rejected by the vanilla placement
     * rules before our own city gate ever runs.
     *
     * <p>Deliberately the <em>any light</em> monster rule, not the usual darkness-only one: cities
     * are lit, and a mob that can only appear in unlit corners would never show up in the very
     * place it is supposed to inhabit. Keeping them inside cities is {@code CityGate}'s job instead
     * of the light level's.</p>
     */
    /** The faction troops' attribute set: their family's, with 40 health (README 5y). */
    private static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder troopAttributes(
            net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder builder) {
        return builder.add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 40.0D);
    }

    public static void registerSpawnPlacements() {
        SpawnPlacements.register(SCAV.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);
        SpawnPlacements.register(GUNNER_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);
        SpawnPlacements.register(GUNNER_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
        SpawnPlacements.register(SNIPER_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);
        // The villager sniper uses our own any-light rule for the same reason the gunner villager does: the
        // helper takes EntityType<? extends Monster> and this mob is a Villager.
        // The faction troops: any light like the other gun mobs, city-gated (README 5y).
        SpawnPlacements.register(USEC_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
        SpawnPlacements.register(ELITE_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
        SpawnPlacements.register(BEAR_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);
        SpawnPlacements.register(ELITE_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);

        SpawnPlacements.register(SNIPER_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
    }

    /**
     * The same rule as {@code Monster::checkAnyLightMonsterSpawnRules} - any light level, but never on
     * peaceful - written out because that helper takes {@code EntityType<? extends Monster>} and the villager
     * types are {@code Villager}s. {@code Mob#checkMobSpawnRules} is the part that enforces the mob category's
     * own rules (ground, not too crowded, inside the world border).
     */
    private static <T extends Mob> boolean anyLightSpawnRules(EntityType<T> type, ServerLevelAccessor level,
                                                              MobSpawnType reason, BlockPos pos, RandomSource random) {
        return level.getDifficulty() != Difficulty.PEACEFUL
                && Mob.checkMobSpawnRules(type, level, reason, pos, random);
    }
}
