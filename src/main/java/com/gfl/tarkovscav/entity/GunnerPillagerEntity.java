package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunAttackGoal;
import com.gfl.tarkovscav.gun.GunBrain;
import com.gfl.tarkovscav.gun.GunClips;
import com.gfl.tarkovscav.gun.GunLoadout;
import com.gfl.tarkovscav.gun.GunLoot;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.gun.GunPose;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.gun.MobAmmoInventory;
import com.gfl.tarkovscav.gun.NoGunMeleeGoal;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A pillager that carries a TaCZ firearm instead of a crossbow, using the same {@link GunBrain} and
 * the same layered animation scheme as {@link ScavEntity}.
 *
 * <p>Everything else about it is still a vanilla pillager: raids, patrol leaders, its 5-slot pick-up
 * inventory, its sounds, its hostility toward villagers and players. It simply gets the gun goal at a
 * higher priority than the crossbow goal it inherited, and its default crossbow is suppressed so the
 * two weapons cannot both be "held".</p>
 *
 * <p>The city spawn gate treats this mob separately from scavs
 * ({@code spawn.gunnerPillagerCityOnly}) - a rifle-armed pillager patrol walking out of a city is
 * exactly the thing the gate is meant to prevent.</p>
 */
public class GunnerPillagerEntity extends Pillager implements GunUser, GeoEntity {
    private static final String TAG_TIER = "TarkovScavTier";
    private static final String TAG_GUN = "TarkovScavGun";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private static final GunPose.Keys GUN_POSE = GunPose.create(GunnerPillagerEntity.class);

    /** Lazily created, see {@link ScavEntity} for why a field initialiser would be wrong here. */
    private GunBrain gunBrain;
    private MobAmmoInventory ammoInventory;
    private ScavTier tier = ScavTier.RIFLE;
    /** Real-displacement movement telemetry - see {@link ScavEntity}. */
    private final WalkTelemetry walk = new WalkTelemetry();
    /** Voice lines - see README 5l. */
    private final com.gfl.tarkovscav.voice.MobVoice voice = new com.gfl.tarkovscav.voice.MobVoice(this);

    public GunnerPillagerEntity(EntityType<? extends Pillager> type, Level level) {
        super(type, level);
        this.xpReward = 12;
        // Vanilla Raider/Pillager do NOT call setCanOpenDoors (a pillager cannot open a door), which is
        // why an armed pillager used to stand at a closed door stamping its feet once the city had
        // doors. Villager/Vindicator/Zombie do call it; see GunUser#allowDoors.
        GunUser.allowDoors(this);
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
        super.registerGoals();
        // The pillager's own crossbow goal sits at priority 2; the gun fight has to outrank it.
        this.goalSelector.addGoal(1, new GunAttackGoal(this));
        // Melee fallback, exactly as ScavEntity has it. It is not optional here: a vanilla Pillager
        // has no melee goal at all - Pillager registers FloatGoal(0), HoldGroundAttackGoal(2) and
        // RangedCrossbowAttackGoal(3) and AbstractIllager/Raider add no MeleeAttackGoal either
        // (verified against the mapped 1.20.1 jar), and this subclass spawns with no crossbow, so
        // RangedCrossbowAttackGoal can never run. GunAttackGoal#canUse is false whenever the TaCZ gun
        // index yields nothing for the tier, which left the mob with no attacking goal whatsoever:
        // it stood next to its target, unarmed, until it was killed. NoGunMeleeGoal keeps this mutually
        // exclusive with the gun goal, so the two never issue competing navigation calls in one tick.
        this.goalSelector.addGoal(2, new NoGunMeleeGoal(this, 1.1D, false));
        // Grenades (README 5v), behind shooting and melee: thrown only when the target is out of sight. The
        // ladder gate (README 7o) is required because this goal carries no goal flags of its own.
        this.goalSelector.addGoal(3, new com.gfl.tarkovscav.gun.LadderGatedGoal(this,
                new com.gfl.tarkovscav.grenade.GrenadeThrowGoal(this, this)));
        // Grenade resupply (README 5v): a parallel path, priority 4, that only ever takes a THROWABLE
        // off a rack - an armed unit never collects a second gun.
        this.goalSelector.addGoal(4, new com.gfl.tarkovscav.grenade.GrenadeResupplyGoal(this, this));
        // Weapon rack (README 5n): a bow/crossbow taken off the rack is shot, not swung. Inert otherwise
        // (the goal refuses to run unless a bow or crossbow is actually in hand).
        this.goalSelector.addGoal(1, new com.gfl.tarkovscav.gun.ArmedRangedGoal(this, 1.0D, 15.0F));
        // The command system: walk to a mark this unit was ordered to. Priority 6 is below every combat
        // goal and takes only MOVE, so a fight pre-empts it and an idle unit obeys instead of wandering.
        this.goalSelector.addGoal(6, new com.gfl.tarkovscav.command.AdvanceOrderGoal(this));
        // Ladder climbing (README 7o): priority 5, below every combat goal and above the advance order and
        // the inherited stroll, because it supplies the vertical leg the ground navigator cannot path.
        this.goalSelector.addGoal(5, new com.gfl.tarkovscav.gun.LadderClimbGoal(this));
        // Faction layer (README 5m): a branded renegade is hunted by everybody, its own side included.
        // Inert while nobody is a renegade, so the inherited illager targeting is unchanged.
        this.targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                candidate -> candidate != this && com.gfl.tarkovscav.faction.Renegade.is(candidate)));
    }

    /** No crossbow: this pillager is issued a TaCZ gun in {@code finalizeSpawn} instead. */
    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        // intentionally empty
    }

    /** And no enchanted crossbow either. */
    @Override
    protected void populateDefaultEquipmentEnchantments(RandomSource random, DifficultyInstance difficulty) {
        // intentionally empty
    }

    // ------------------------------------------------------------------ spawning

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        this.setChargingCrossbow(false);

        ScavTier forced = forcedSpawnTier();
        this.tier = forced != null ? forced : ScavTier.pickWeighted(this.getRandom());
        applyTierAttributes();
        this.gunBrain().equip(this.getRandom());
        return data;
    }

    /**
     * The tier this mob is <b>always</b> spawned with, or null to roll one from the spawn weights. The sniper
     * subclasses override this, because "always the sniper tier" has to apply to the saved tier field as well
     * as to {@link #scavTier()} - the field is what {@code applyTierAttributes} reads and what is written to
     * the save, so leaving it random gave a sniper a random tier's health and let it come back as a rifleman
     * after a reload.
     */
    @Nullable
    protected ScavTier forcedSpawnTier() {
        return null;
    }

    /**
     * The health this mob always has, or a negative number to use the tier's (README 5y). The faction troops
     * override it with 40; everything else keeps the per-tier value.
     */
    protected double forcedMaxHealth() {
        return -1.0D;
    }

    /** The voice pool family this mob speaks (README 5y); "shared" is the original pool set. */
    public String voiceFamily() {
        return "shared";
    }

    /**
     * The vanilla armour <b>points</b> this mob is pinned to, or a negative number to use the tier's (README
     * 5y). The faction troops override it with 0, and that is not a cosmetic detail: {@code ArmorClass} applies
     * its 10 %-per-class reduction in {@code LivingHurtEvent}, i.e. <em>before</em> vanilla armour absorbs, so
     * a tier's armour points would reduce the blow a second time and class 6 would land well past the 60 % the
     * user asked for. Pinning the points to 0 is what makes "class 6 = 60 %" exact rather than approximate.
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

    @Override
    public void tick() {
        super.tick();
        // PERFORMANCE (tools/spike/work/aiperf): a dead or already-removed unit has no AI left. Everything
        // below - the telemetry, the voice timer, the faction network - used to keep running for the whole
        // 20-tick death animation, on every unit that died. Vanilla's own death handling is in super.tick().
        if (this.isRemoved() || this.isDeadOrDying()) {
            return;
        }
        // Real-displacement movement telemetry: the animation reads this, not "intent to move".
        this.walk.tick(this);
        if (!this.level().isClientSide) {
            this.voice.tick(this.getTarget());
            com.gfl.tarkovscav.faction.FactionAi.tick(this, this.getTarget());
        }
        // Belt and braces: the inherited crossbow goal calls setChargingCrossbow(true) if it ever runs.
        if (!this.level().isClientSide && this.isChargingCrossbow()) {
            this.setChargingCrossbow(false);
        }
    }

    /** See README 5l: the idle voice timer has to run whether or not the brain has a target. */
    public com.gfl.tarkovscav.voice.MobVoice voice() {
        return this.voice;
    }

    /** Silenced when the voice module replaces it, so the clip and the vanilla sound never overlap. */
    @Nullable
    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return Config.VOICE_ENABLED.get() && Config.VOICE_DEATH.get()
                ? null : net.minecraft.sounds.SoundEvents.PILLAGER_DEATH;
    }

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

    @Override
    public void die(DamageSource source) {
        this.voice.sayDeath();
        this.triggerAnim("movement", "death");
        super.die(source);
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
        // A forced tier wins over the saved one, so a sniper also repairs a save written before its tier was
        // enforced (it would otherwise re-load a sniper gun id from the rifle pool, fail, and become a
        // rifleman for the rest of its life).
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

    /** See {@link ScavEntity#getBoundingBoxForCulling()} for why the box is padded. */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(Config.cullingBoxPaddingForScale());
    }

    @Override
    public float getEquipmentDropChance(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? 0.0F : super.getEquipmentDropChance(slot);
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int lootingLevel, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, lootingLevel, recentlyHit);
        GunLoot.dropGunAndAmmo(this, this.gunBrain(), this.ammoInventory(), source, lootingLevel);
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /**
     * The same two-controller layering as the scav (see {@link ScavEntity}). The controllers are
     * registered even while the vanilla illager renderer is in use
     * ({@code client.useGeckoModel = false}) so switching the renderer later needs no change here.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        if (Config.SPEC.isLoaded() && Config.singleControllerMode()) {
            // See ScavEntity#registerControllers: one controller, one clip, one geometry submission.
            controllers.add(new AnimationController<>(this, "model", 3, this::singleController)
                    .triggerableAnim("death", RawAnimation.begin().thenPlay(GunClips.DEATH))
                    .receiveTriggeredAnimations());
            return;
        }
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementController)
                .triggerableAnim("death", RawAnimation.begin().thenPlay(GunClips.DEATH))
                .receiveTriggeredAnimations());
        controllers.add(new AnimationController<>(this, "gun", 2, this::gunController));
    }

    /** The single-controller variant; same clip choice as {@code ScavEntity}'s. */
    private PlayState singleController(AnimationState<GunnerPillagerEntity> state) {
        if (isArmed()) {
            String family = usesPistolClips() ? GunClips.FAMILY_PISTOL : GunClips.FAMILY_RIFLE;
            String action = GunClips.actionFor(isGunReloading(), isGunFiring(), isGunAiming(), gunAiState());
            return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.gun(family, action)));
        }
        boolean moving = this.walk.isMoving();
        boolean running = this.walk.isRunning();
        return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.movement(false, moving, running)));
    }

    private PlayState movementController(AnimationState<GunnerPillagerEntity> state) {
        boolean armed = isArmed();
        boolean moving = this.walk.isMoving();
        boolean running = this.walk.isRunning();
        return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.movement(armed, moving, running)));
    }

    private PlayState gunController(AnimationState<GunnerPillagerEntity> state) {
        if (!isArmed()) {
            return PlayState.STOP;
        }
        String family = usesPistolClips() ? GunClips.FAMILY_PISTOL : GunClips.FAMILY_RIFLE;
        String action;
        if (isGunReloading()) {
            action = "reload";
        } else if (isGunFiring()) {
            action = "aim:fire";
        } else if (isGunAiming()) {
            action = "aim";
        } else {
            action = "hold";
        }
        return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.gun(family, action)));
    }

    private boolean isArmed() {
        ItemStack held = this.getMainHandItem();
        return !held.isEmpty() && IGun.getIGunOrNull(held) != null;
    }
}
