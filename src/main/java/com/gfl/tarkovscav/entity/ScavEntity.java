package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunBrain;
import com.gfl.tarkovscav.gun.GunClips;
import com.gfl.tarkovscav.gun.GunLoadout;
import com.gfl.tarkovscav.gun.GunLoot;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.gun.GunPose;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.gun.MobAmmoInventory;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
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
 * A scav: a hostile mob armed with a TaCZ firearm, using the tactics in {@link GunBrain}.
 *
 * <p>What is specific here is the monster behaviour (targeting players and test dummies, wandering,
 * melee fallback), the GeckoLib wiring, and the fact that its gear is rolled from a {@link ScavTier}
 * when it spawns.</p>
 *
 * <h2>Animation layering</h2>
 * <p>Two controllers, registered in this order, because the author's rig splits at {@code UpBody} /
 * {@code DownBody}:</p>
 * <ol>
 *   <li><b>movement</b> - the legs. {@code idle}/{@code walk}/{@code run} when unarmed, the author's
 *       lower-body {@code tac:idle}/{@code tac:walk}/{@code tac:run} when a gun is held. It also
 *       carries the triggered {@code death} clip.</li>
 *   <li><b>gun</b> - the upper body: {@code hold} / {@code aim} / {@code aim:fire} / {@code reload},
 *       one-handed or two-handed depending on the weapon. Registered second, so it owns the arms,
 *       head and torso while the legs keep moving - which is the whole reason a walking and shooting
 *       mob does not look like it is sliding along the floor.</li>
 * </ol>
 */
public class ScavEntity extends Monster implements GeoEntity, GunUser {
    private static final String TAG_TIER = "TarkovScavTier";
    private static final String TAG_GUN = "TarkovScavGun";

    /** Scoreboard tag that marks a mob as a practice target for the RCON tests. */
    public static final String DUMMY_TAG = "tarkovscav_dummy";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** Synced aiming/firing/reloading flags, created once for this entity class. */
    private static final GunPose.Keys GUN_POSE = GunPose.create(ScavEntity.class);

    /**
     * Created lazily and never assigned in a field initialiser: Forge fires
     * {@code AttachCapabilitiesEvent} from the {@code Entity} constructor, i.e. <em>before</em>
     * subclass field initialisers run, and {@code GunCapabilities} asks for this inventory right there.
     */
    private GunBrain gunBrain;
    private MobAmmoInventory ammoInventory;
    private ScavTier tier = ScavTier.RIFLE;
    /**
     * Real-displacement movement telemetry - the animation reads THIS, not "intent to move", so a mob
     * pinned at a door or a wall animates as standing still instead of stepping in place.
     */
    private final WalkTelemetry walk = new WalkTelemetry();
    /** Voice lines - see README 5l. Server-side only; it plays itself at this mob's position. */
    private final com.gfl.tarkovscav.voice.MobVoice voice = new com.gfl.tarkovscav.voice.MobVoice(this);

    public ScavEntity(EntityType<? extends ScavEntity> type, Level level) {
        super(type, level);
        this.xpReward = 8;
        // Vanilla Villager/Vindicator/Zombie open doors; a plain Monster does not. Without this a scav
        // fails the path node at any closed door: moveTo produces no movement while the walk animation
        // keeps running - the "steps in place at a doorway" report. See GunUser#allowDoors.
        GunUser.allowDoors(this);
    }

    /** See README 5l: idle muttering needs a timer that runs whether or not the brain has a target. */
    public com.gfl.tarkovscav.voice.MobVoice voice() {
        return this.voice;
    }

    public static AttributeSupplier.Builder createScavAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.29D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.ARMOR, 0.0D);
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

    /**
     * Called from the {@code Mob} constructor, i.e. before this class's field initialisers run - so
     * nothing in here may read a field. {@code new GunAttackGoal(this)} is safe because the brain is
     * created lazily.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // priority 1: the gun fight outranks everything except drowning
        this.goalSelector.addGoal(1, new com.gfl.tarkovscav.gun.GunAttackGoal(this));
        // Plain melee stays as the fallback for a mob with no gun (empty/filtered TaCZ index) - and it
        // is *mutually exclusive* with the gun goal: NoGunMeleeGoal#canUse is false while a gun is
        // held, so the two can never issue competing navigation calls in the same tick.
        this.goalSelector.addGoal(2, new com.gfl.tarkovscav.gun.NoGunMeleeGoal(this, 1.1D, false));
        // Grenades (README 5v): priority 3, behind shooting and melee, and only when the target is out of
        // sight. Completely inert for a mob with no grenades, so an ordinary scav is unaffected.
        this.goalSelector.addGoal(3, new com.gfl.tarkovscav.grenade.GrenadeThrowGoal(this, this));
        // Grenade resupply (README 5v): a parallel path, priority 4, that only ever takes a THROWABLE
        // off a rack - an armed unit never collects a second gun.
        this.goalSelector.addGoal(4, new com.gfl.tarkovscav.grenade.GrenadeResupplyGoal(this, this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // The command system: walk to a mark this unit was ordered to. Priority 5 is below every combat
        // goal and takes only MOVE, so a fight pre-empts it and an idle unit obeys instead of wandering.
        this.goalSelector.addGoal(5, new com.gfl.tarkovscav.command.AdvanceOrderGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, ScavEntity.class));
        // Faction layer (README 5m): a branded renegade is hunted by everybody. The predicate is inert
        // while nobody is a renegade, so normal play is byte-for-byte unchanged.
        this.targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                candidate -> candidate != this && com.gfl.tarkovscav.faction.Renegade.is(candidate)));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        // Practice targets: anything tagged tarkovscav_dummy. This is what the RCON verification
        // spawns instead of a real player, so the fight can be driven and observed head-lessly.
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true,
                false, candidate -> candidate.getTags().contains(DUMMY_TAG)));
    }

    // ------------------------------------------------------------------ spawning

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);

        this.tier = ScavTier.pickWeighted(this.getRandom());
        applyTierAttributes();
        this.gunBrain().equip(this.getRandom());
        return data;
    }

    /** Per-tier health and armour, applied the moment the tier is known. */
    private void applyTierAttributes() {
        Config.TierSettings settings = Config.tier(this.tier);
        if (this.getAttribute(Attributes.MAX_HEALTH) != null) {
            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(settings.health.get());
        }
        this.setHealth(settings.health.get().floatValue());
        if (this.getAttribute(Attributes.ARMOR) != null) {
            this.getAttribute(Attributes.ARMOR).setBaseValue(settings.armor.get());
        }
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

    /**
     * Being hit is what turns a scav from "shooting at you" into "getting behind something" - the
     * brain starts its under-fire timer and may break contact outright.
     */
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
        // Our own death cry replaces the vanilla one (client.voice.death); see README 5l.
        this.voice.sayDeath();
        // Play the rig's own death clip on the movement controller (registered as a triggered
        // animation). It only affects the legs+root, so the upper body keeps whatever pose it had.
        this.triggerAnim("movement", "death");
        super.die(source);
    }

    /** Silenced when the voice module replaces it, so the clip and the vanilla sound never overlap. */
    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return Config.VOICE_ENABLED.get() && Config.VOICE_DEATH.get() ? null : SoundEvents.PILLAGER_DEATH;
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
        this.tier = saved == null ? ScavTier.RIFLE : saved;
        applyTierAttributes();
        if (this.level().isClientSide) {
            return;
        }

        if (tag.contains(TAG_GUN)) {
            ResourceLocation gunId = ResourceLocation.tryParse(tag.getString(TAG_GUN));
            GunLoadout loadout = gunId == null ? null : GunPool.loadoutFor(this.tier, gunId);
            if (loadout != null) {
                this.gunBrain().equipLoadout(loadout);
                return;
            }
        }
        this.gunBrain().equip(this.getRandom());
    }

    // ------------------------------------------------------------------ loot

    /**
     * The gun is dropped by our own code (with a configurable chance) rather than by vanilla's
     * equipment-drop roll, so {@code getEquipmentDropChance} is forced to 0 for the main hand -
     * otherwise a lucky roll would drop a second copy.
     */
    @Override
    public float getEquipmentDropChance(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? 0.0F : super.getEquipmentDropChance(slot);
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int lootingLevel, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, lootingLevel, recentlyHit);
        GunLoot.dropGunAndAmmo(this, this.gunBrain(), this.ammoInventory(), source, lootingLevel);
    }

    // ------------------------------------------------------------------ sounds (placeholders)

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PILLAGER_HURT;
    }

    // getDeathSound lives next to die() above, because the voice module may replace it.

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        if (Config.SPEC.isLoaded() && Config.singleControllerMode()) {
            // ONE controller for the whole rig (client.modelLayering = single, the default). GeckoLib
            // submits the geometry once either way - the layered scheme below is NOT a second draw -
            // so the only thing that changes is which clip drives which bones:
            //   * a gun is held -> the gun clip for the current action, whose tracks are upper-body
            //     only (the rig's gun clips animate 0 of the 8 leg bones), so the legs hold their rest
            //     pose while it walks;
            //   * no gun -> the whole-body idle/walk/run clip.
            controllers.add(new AnimationController<>(this, "model", 3, this::singleController)
                    .triggerableAnim("death", RawAnimation.begin().thenPlay(GunClips.DEATH))
                    .receiveTriggeredAnimations());
            return;
        }
        // ---- 1. lower body: movement ------------------------------------------------------
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementController)
                .triggerableAnim("death", RawAnimation.begin().thenPlay(GunClips.DEATH))
                .receiveTriggeredAnimations());

        // ---- 2. upper body: the gun ------------------------------------------------------
        // Registered second on purpose: GeckoLib applies controllers in order and the later one wins
        // per bone, so this one owns the arms/head/torso while the movement controller keeps the legs.
        controllers.add(new AnimationController<>(this, "gun", 2, this::gunController));
    }

    /**
     * The single-controller variant: one clip for the whole model, chosen from the same synced gun pose
     * the two-controller scheme uses.
     */
    private PlayState singleController(AnimationState<ScavEntity> state) {
        if (isArmed()) {
            String family = usesPistolClips() ? GunClips.FAMILY_PISTOL : GunClips.FAMILY_RIFLE;
            String action = GunClips.actionFor(isGunReloading(), isGunFiring(), isGunAiming(), gunAiState());
            return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.gun(family, action)));
        }
        boolean moving = this.walk.isMoving();
        boolean running = this.walk.isRunning();
        return state.setAndContinue(RawAnimation.begin().thenLoop(GunClips.movement(false, moving, running)));
    }

    /**
     * Legs only. The author's rig provides proper lower-body movement clips for an armed mob
     * ({@code tac:idle}/{@code tac:walk}/{@code tac:run} touch zero upper-body bones), so an armed
     * scav walks with the correct gait instead of having a whole-body clip fight the gun pose.
     */
    private PlayState movementController(AnimationState<ScavEntity> state) {
        boolean armed = isArmed();
        boolean moving = this.walk.isMoving();
        boolean running = this.walk.isRunning();
        return state.setAndContinue(RawAnimation.begin()
                .thenLoop(GunClips.movement(armed, moving, running)));
    }

    /**
     * Arms, head and torso: one clip chosen from the synced gun pose. The clip comes in a one-handed
     * and a two-handed variant and the server tells us which (the client cannot look the gun's TaCZ
     * type up reliably).
     */
    private PlayState gunController(AnimationState<ScavEntity> state) {
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

    /** True when the mob visibly holds a TaCZ gun. Read from the main hand, which is synced. */
    private boolean isArmed() {
        ItemStack held = this.getMainHandItem();
        return !held.isEmpty() && IGun.getIGunOrNull(held) != null;
    }

    /**
     * Frustum culling uses {@link net.minecraft.world.entity.Entity#getBoundingBoxForCulling}
     * (inflated by 0.5 by {@code EntityRenderer#shouldRender}), i.e. the 0.6 x 1.95 mob hitbox - while
     * this rig renders about 2.8 x 3.85 blocks of geometry (4 x 5.5 model units at
     * {@code client.renderScale = 0.7}) and the held gun reaches further than that. A part-visible mob
     * at the screen edge could therefore be dropped entirely. {@code client.cullingBoxPadding} (default
     * 1.0 block) covers the difference; 0 restores vanilla behaviour.
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(Config.cullingBoxPaddingForScale());
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
        // Both sides: the client's own entity tracks its own positions, which is what makes the
        // animation a readout of real displacement (see WalkTelemetry). Voice is server-side only.
        this.walk.tick(this);
        if (!this.level().isClientSide) {
            this.voice.tick(this.getTarget());
            // Faction intel (README 5m): shares a contact, or acts on one it was told. It can never set a
            // target - only line of sight can, which is GunBrain's job.
            com.gfl.tarkovscav.faction.FactionAi.tick(this, this.getTarget());
        }
    }

    /** Handy in the log lines GunBrain writes. */
    @Override
    public String toString() {
        return "Scav[" + this.tier.id() + " " + TarkovScav.MOD_ID + "]";
    }
}
