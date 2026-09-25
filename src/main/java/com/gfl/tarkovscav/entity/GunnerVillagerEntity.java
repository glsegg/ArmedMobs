package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunBrain;
import com.gfl.tarkovscav.gun.GunLoadout;
import com.gfl.tarkovscav.gun.GunLoot;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.gun.GunPose;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.gun.MobAmmoInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * 武装村民 / the gunner villager: a villager that has picked up a TaCZ firearm and now fights the
 * pillagers instead of hiding from them.
 *
 * <h2>What is reused, and why extending {@link Villager} is the whole trick</h2>
 * <ul>
 *   <li><b>Model, texture and sounds are the vanilla ones.</b> Extending {@code Villager} keeps
 *       {@code VillagerData} ({@link VillagerType} from the biome, {@link VillagerProfession} and its
 *       level), which is exactly what the vanilla {@code VillagerProfessionLayer} reads to draw the
 *       biome and profession skins on top of {@code textures/entity/villager/villager.png}. No new
 *       model, texture or animation asset is added for this mob.
 *       {@code getAmbientSound}/{@code getHurtSound}/{@code getDeathSound} are already overridden by
 *       {@code Villager} with {@code SoundEvents.VILLAGER_*}, so they are inherited unchanged - this
 *       class deliberately does <b>not</b> override them.</li>
 *   <li><b>The gun AI is the shared one.</b> Implementing {@link GunUser} gives this mob the same
 *       {@link GunBrain}, {@code GunAttackGoal}, cover/reload/suppression/retreat tactics and
 *       {@link com.gfl.tarkovscav.gun.GunClips} clip selection as {@link ScavEntity} and
 *       {@link GunnerPillagerEntity}. Nothing about the fight is forked for villagers.</li>
 * </ul>
 *
 * <h2>The arm pose: villagers are not illagers</h2>
 * <p>Vanilla illagers expose {@code AbstractIllager#getArmPose()} and an {@code IllagerModel} whose
 * arms are separate parts, which is how {@link GunnerPillagerEntity} gets its "weapon up" look.
 * {@code Villager} has none of that: {@code VillagerModel} carries the arms as <b>one static
 * {@code arms} part</b> (the crossed-arms block) that its own {@code setupAnim} never even touches, and
 * it does not implement {@code ArmedModel}, so the vanilla {@code ItemInHandLayer} cannot be attached
 * to it either. The pose therefore lives in {@code client.GunnerVillagerModel} - a subclass that keeps
 * the vanilla geometry and adds exactly two things: an {@code ArmedModel#translateToHand} (the same
 * one-line body {@code HumanoidModel} uses for its arm) so the held gun renders in the standard hand
 * frame TaCZ is authored for, and an arm rotation driven by the synced {@code GunAiState}, the same
 * single source of truth {@code ArmPose} uses for the rig.</p>
 *
 * <h2>Hostility</h2>
 * <p>Hostile to {@link AbstractIllager} (so vanilla pillagers, vindicators, illusioners <em>and</em>
 * this mod's {@link GunnerPillagerEntity}, which is a {@code Pillager}) and friendly to everything
 * else: players, villagers and iron golems are simply never targeted. Being attacked is what makes it
 * fight back ({@code HurtByTargetGoal}), players included - a "friendly" mob that ignores being shot
 * would be worse than a hostile one.</p>
 */
public class GunnerVillagerEntity extends Villager implements GunUser, RangedAttackMob {
    private static final String TAG_TIER = "TarkovScavTier";
    private static final String TAG_GUN = "TarkovScavGun";

    /** Synced aiming/firing/reloading flags, created once for this entity class. */
    private static final GunPose.Keys GUN_POSE = GunPose.create(GunnerVillagerEntity.class);

    /** Lazily created - see {@link ScavEntity} for why a field initialiser would run too early. */
    private GunBrain gunBrain;
    private MobAmmoInventory ammoInventory;
    private ScavTier tier = ScavTier.RIFLE;
    /** Real-displacement movement telemetry - see {@link ScavEntity}. */
    private final WalkTelemetry walk = new WalkTelemetry();
    /** Voice lines - see README 5l. */
    private final com.gfl.tarkovscav.voice.MobVoice voice = new com.gfl.tarkovscav.voice.MobVoice(this);

    public GunnerVillagerEntity(EntityType<? extends GunnerVillagerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
        // Villager's own constructor already called setCanOpenDoors(true); calling it again is
        // idempotent and keeps the three gun mobs explicit about the same fact.
        GunUser.allowDoors(this);
    }

    @Override
    public void tick() {
        super.tick();
        // PERFORMANCE (tools/spike/work/aiperf): a dead or already-removed unit has no AI left. Everything
        // below - the telemetry, the voice timer, the faction network - used to keep running for the whole
        // 20-tick death animation, on every unit that died. Vanilla's own death handling is in super.tick().
        if (this.isRemoved() || this.isDeadOrDying()) {
            return;
        }
        this.walk.tick(this);
        if (!this.level().isClientSide) {
            this.voice.tick(this.getTarget());
            com.gfl.tarkovscav.faction.FactionAi.tick(this, this.getTarget());
        }
    }

    /** See README 5l: the idle voice timer runs whether or not the brain has a target. */
    public com.gfl.tarkovscav.voice.MobVoice voice() {
        return this.voice;
    }

    @Override
    public void die(DamageSource source) {
        this.voice.sayDeath();
        super.die(source);
    }

    /** Silenced when the voice module replaces it, so the clip and the vanilla sound never overlap. */
    @Nullable
    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return Config.VOICE_ENABLED.get() && Config.VOICE_DEATH.get()
                ? null : net.minecraft.sounds.SoundEvents.VILLAGER_DEATH;
    }

    /**
     * The name shown in the kill feed, on the name plate and in every debug line (README 5x).
     *
     * <p>Vanilla {@code Villager#getTypeName()} concatenates the profession onto the entity's own translation
     * key ({@code entity.tarkovscav.gunner_villager.weaponsmith}), which this mod never shipped - so the raw key
     * reached the screen. This returns <b>our</b> name with the <b>vanilla</b> profession in brackets instead
     * (武装村民（武器匠）), and the bare name when there is no profession. The sniper villager inherits this,
     * which is why its name comes out right as well.</p>
     */
    @Override
    public net.minecraft.network.chat.Component getTypeName() {
        return EntityNames.villagerTypeName(this.getType(), this.getVillagerData());
    }

    // ------------------------------------------------------------------ GunUser

    @Override
    public Mob asMob() {
        return this;
    }

    @Override
    public GunBrain gunBrain() {
        if (this.gunBrain == null) {
            this.gunBrain = new GunBrain(this, this);
        }
        return this.gunBrain;
    }

    /**
     * The bow/crossbow shot of a mob that took one off the weapon rack (README 5n). A villager has no ranged
     * attack of its own - it is not a {@code Monster}, which is also why the vanilla
     * {@code RangedBowAttackGoal} cannot be used for this class - so the arrow is spawned here, following
     * {@code AbstractSkeleton}'s shot (the same launch angle and the same difficulty-scaled inaccuracy).
     */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        ItemStack weapon = this.getItemInHand(net.minecraft.world.entity.projectile.ProjectileUtil
                .getWeaponHoldingHand(this, item -> item == net.minecraft.world.item.Items.BOW
                        || item == net.minecraft.world.item.Items.CROSSBOW));
        ItemStack ammo = this.getProjectile(weapon);
        // var, not Arrow: getMobArrow's return type is the mapped arrow class, and naming it here would only
        // add an import that the compiler already knows about.
        var arrow = net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(this, ammo, distanceFactor);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F,
                (float) (14 - this.level().getDifficulty().getId() * 4));
        this.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1.0F,
                1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    @Override
    public MobAmmoInventory ammoInventory() {
        if (this.ammoInventory == null) {
            this.ammoInventory = new MobAmmoInventory();
        }
        return this.ammoInventory;
    }

    @Override
    public ScavTier scavTier() {
        return this.tier;
    }

    @Override
    public void setScavTier(ScavTier tier) {
        this.tier = tier == null ? ScavTier.RIFLE : tier;
        this.gunBrain().equip(this.getRandom());
    }

    // ------------------------------------------------------------------ AI

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // priority 1: the gun fight outranks everything except drowning
        this.goalSelector.addGoal(1, new com.gfl.tarkovscav.gun.GunAttackGoal(this));
        // melee fallback for the case where TaCZ's index yields no gun for this tier, exactly as on
        // the scav and the gunner pillager
        this.goalSelector.addGoal(2, new com.gfl.tarkovscav.gun.NoGunMeleeGoal(this, 1.1D, false));
        // Grenades (README 5v), behind shooting and melee: thrown only when the target is out of sight.
        this.goalSelector.addGoal(3, new com.gfl.tarkovscav.grenade.GrenadeThrowGoal(this, this));
        // Grenade resupply (README 5v): a parallel path, priority 4, that only ever takes a THROWABLE
        // off a rack - an armed unit never collects a second gun.
        this.goalSelector.addGoal(4, new com.gfl.tarkovscav.grenade.GrenadeResupplyGoal(this, this));
        // Weapon rack (README 5n): a mob that took a bow/crossbow shoots instead of closing in. The goal is
        // inert unless such a weapon is in hand, so an ordinary gunner is unaffected.
        this.goalSelector.addGoal(1, new com.gfl.tarkovscav.gun.ArmedRangedGoal(this, 1.0D, 15.0F));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // The command system: walk to a mark this unit was ordered to. Priority 5 puts it BELOW every
        // combat goal and above the stroll, and it takes only the MOVE flag - so a fight (GunAttackGoal,
        // priority 1, MOVE+LOOK) pre-empts it for free, and an idle unit obeys the order instead of
        // wandering. Inert until an order exists, so nothing changes for a unit nobody commanded.
        this.goalSelector.addGoal(5, new com.gfl.tarkovscav.command.AdvanceOrderGoal(this));

        // Whoever hurts it, it shoots back - including a player who attacks a "friendly" villager.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Faction layer (README 5m): a branded renegade is hunted by everybody, its own side included.
        // Inert while nobody is a renegade, so normal play is unchanged.
        this.targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                candidate -> candidate != this && com.gfl.tarkovscav.faction.Renegade.is(candidate)));
        // The enemy list is one class: every illager, which covers vanilla pillagers, vindicators and
        // illusioners and our own GunnerPillagerEntity (Pillager extends AbstractIllager).
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, AbstractIllager.class, true));
        // Village defence (README 5m): the village family fights back against the monsters the
        // faction_village_hostile tag lists - zombies, skeletons, spiders, creepers, the vanilla raiders -
        // which is what "the armed villagers also shoot the zombies" asked for. The tag is a data-pack file,
        // and faction.villagersAttackMonsters is the master switch; the predicate reads both per scan, so a
        // config change takes effect without respawning the mob, and an existing world is unaffected until
        // the switch says otherwise. Faction.villageHostile() also refuses a fellow faction member, so this
        // can never make a villager shoot a villager.
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                candidate -> com.gfl.tarkovscav.faction.Faction.villageHostile(this, candidate)));
        // Practice targets for the head-less RCON harness, same tag the scav uses.
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true,
                false, candidate -> candidate.getTags().contains(ScavEntity.DUMMY_TAG)));
    }

    /**
     * Reports the moment a village defender picks up a monster target (README 5m), gated by the existing
     * {@code logGunAi} switch. The goal itself is silent and runs whenever the game asks, so this is what
     * makes "is it actually hunting the zombie?" visible in the log instead of a guess.
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        LivingEntity previous = this.getTarget();
        super.setTarget(target);
        if (target != null && target != previous && Config.LOG_GUN_AI.get()
                && com.gfl.tarkovscav.faction.Faction.villageHostile(this, target)) {
            TarkovScav.LOGGER.info("[faction] {} locked onto {} ({})",
                    com.gfl.tarkovscav.entity.EntityNames.safeName(this),
                    com.gfl.tarkovscav.entity.EntityNames.safeName(target),
                    target.getType().toShortString());
        }
    }

    /**
     * Villagers are a <b>Brain</b> mob: their work, stroll and panic behaviour writes walk-target
     * memories, and {@code MoveToTargetSink} turns those into navigation <em>without consulting the
     * goal flags</em>. Left running during a fight it would therefore keep overriding the path the
     * {@code GunAttackGoal} just issued. So while this mob has a target the goal selector owns it, and
     * the villager brain is parked; with no target the vanilla brain runs untouched and it behaves like
     * the villager it is.
     */
    @Override
    protected void customServerAiStep() {
        // The villager Brain writes walk targets through MoveToTargetSink without consulting the goal
        // flags, so it has to be parked while the command system is walking this unit to a mark as well -
        // otherwise the brain would immediately overwrite the ordered path. It comes back the moment the
        // order is cleared (arrival, expiry, or the mark being removed).
        if (this.getTarget() == null && !com.gfl.tarkovscav.command.AdvanceOrder.has(this)) {
            super.customServerAiStep();
        }
    }

    // ------------------------------------------------------------------ spawning

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);

        // Keep the vanilla villager look, but make the skin say what the mob is: the biome picks the
        // VillagerType (so the same entity looks right in every biome) and the profession picks the
        // overlay the VillagerProfessionLayer draws. Changing either needs no asset work at all.
        this.setVillagerData(new VillagerData(
                VillagerType.byBiome(level.getBiome(this.blockPosition())),
                VillagerProfession.WEAPONSMITH, 1));

        ScavTier forced = forcedSpawnTier();
        this.tier = forced != null ? forced : ScavTier.pickWeighted(this.getRandom());
        applyTierAttributes();
        this.gunBrain().equip(this.getRandom());
        return data;
    }

    /**
     * The tier this mob is <b>always</b> spawned with, or null to roll one from the spawn weights. The sniper
     * villager overrides this; see {@link GunnerPillagerEntity#forcedSpawnTier()} for why the saved tier field
     * and {@link #scavTier()} have to agree.
     */
    @Nullable
    protected ScavTier forcedSpawnTier() {
        return null;
    }

    /** Per-tier health and armour, applied the moment the tier is known. */
    /** See GunnerPillagerEntity#forcedMaxHealth: the faction troops pin their health to 40 (README 5y). */
    protected double forcedMaxHealth() {
        return -1.0D;
    }

    /** The voice pool family this mob speaks (README 5y); "shared" is the original pool set. */
    public String voiceFamily() {
        return "shared";
    }

    /**
     * The vanilla armour <b>points</b> this mob is pinned to, or a negative number to use the tier's (README
     * 5y). The faction troops override it with 0: {@code ArmorClass} reduces the blow in {@code
     * LivingHurtEvent}, before vanilla armour absorbs, so leaving a tier's armour points in place would stack a
     * second reduction on top and class 6 would exceed the 60 % the user asked for.
     */
    protected double forcedArmorPoints() {
        return -1.0D;
    }

    private void applyTierAttributes() {
        Config.TierSettings settings = Config.tier(this.tier);
        if (this.getAttribute(Attributes.MAX_HEALTH) != null) {
            this.getAttribute(Attributes.MAX_HEALTH)
                    .setBaseValue(forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get());
        }
        this.setHealth((float) (forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get()));
        if (this.getAttribute(Attributes.ARMOR) != null) {
            this.getAttribute(Attributes.ARMOR)
                    .setBaseValue(forcedArmorPoints() >= 0.0D ? forcedArmorPoints() : settings.armor.get());
        }
    }

    /**
     * No trading GUI. A combat unit that opens an empty merchant screen when right-clicked is a bug
     * waiting to be reported; carrying a TaCZ gun is this villager's whole job.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ pose sync

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        GUN_POSE.defineOn(this.entityData);
    }

    @Override
    public void setGunPose(boolean aiming, boolean firing, boolean reloading) {
        GUN_POSE.set(this, aiming, firing, reloading);
    }

    @Override
    public void setGunAiState(GunAiState state) {
        GUN_POSE.setState(this, state);
    }

    @Override
    public GunAiState gunAiState() {
        return GUN_POSE.state(this);
    }

    @Override
    public void setPistolClips(boolean pistolClips) {
        GUN_POSE.setPistolClips(this, pistolClips);
    }

    @Override
    public boolean isGunAiming() {
        return GUN_POSE.isAiming(this);
    }

    @Override
    public boolean isGunFiring() {
        return GUN_POSE.isFiring(this);
    }

    @Override
    public boolean isGunReloading() {
        return GUN_POSE.isReloading(this);
    }

    @Override
    public boolean usesPistolClips() {
        return GUN_POSE.usesPistolClips(this);
    }

    // ------------------------------------------------------------------ damage + death

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide) {
            this.gunBrain().onHurt();
        }
        return hurt;
    }

    /**
     * See {@link ScavEntity#getBoundingBoxForCulling()}: the same padding, but coupled to the VILLAGER's own
     * scale ({@code client.villagerRenderScale}) rather than the rig's, because the two are separate sizes now
     * (README 5b). A villager scaled up by its own key still cannot be culled while part of it is on screen.
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(Config.cullingBoxPaddingForVillagerScale());
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(TAG_TIER, this.tier.id());
        GunLoadout loadout = this.gunBrain().loadout();
        if (loadout != null) {
            tag.putString(TAG_GUN, loadout.gunId().toString());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ScavTier saved = ScavTier.byId(tag.getString(TAG_TIER));
        // A forced tier wins over the saved one (see GunnerPillagerEntity#forcedSpawnTier).
        ScavTier forced = forcedSpawnTier();
        this.tier = forced != null ? forced : (saved == null ? ScavTier.RIFLE : saved);
        applyTierAttributes();
        if (this.level().isClientSide) {
            return;
        }
        if (tag.contains(TAG_GUN)) {
            ResourceLocation gunId = ResourceLocation.tryParse(tag.getString(TAG_GUN));
            GunLoadout loadout = gunId == null ? null : GunPool.loadoutFor(this.tier, gunId);
            if (loadout != null) {
                this.gunBrain().equipLoadout(loadout);
                // The rack weapon still wins: a converted archer must not become a gunner on reload (5n).
                com.gfl.tarkovscav.block.WeaponRackTaker.restoreArmament(this);
                return;
            }
        }
        this.gunBrain().equip(this.getRandom());
        com.gfl.tarkovscav.block.WeaponRackTaker.restoreArmament(this);
    }

    // ------------------------------------------------------------------ loot

    /** See {@link ScavEntity#getEquipmentDropChance} - our own loot path drops the gun instead. */
    @Override
    public float getEquipmentDropChance(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? 0.0F : super.getEquipmentDropChance(slot);
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int lootingLevel, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, lootingLevel, recentlyHit);
        GunLoot.dropGunAndAmmo(this, this.gunBrain(), this.ammoInventory(), source, lootingLevel);
    }

    // ------------------------------------------------------------------ sounds
    // Deliberately none: Villager already answers with SoundEvents.VILLAGER_AMBIENT / _HURT / _DEATH,
    // which is part of "reuse the vanilla villager". Overriding them here would only be a way to
    // accidentally stop reusing them.

    @Override
    public String toString() {
        return "GunnerVillager[" + this.tier.id() + " " + TarkovScav.MOD_ID + "]";
    }
}
