package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.registry.ModEntities;
import com.gfl.tarkovscav.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The thrown grenade itself (README 5v): a projectile that bounces, counts down and goes off.
 *
 * <p>It is a {@link ThrowableItemProjectile} so the vanilla {@code ThrownItemRenderer} can draw the item
 * sprite - no new model, no new renderer code - and so gravity, chunk checks and collision come from vanilla.
 * What this class adds is the fuse, the bounce and the call into {@link GrenadeBlast}.</p>
 */
public class GrenadeEntity extends ThrowableItemProjectile {
    private static final String TAG_KIND = "TarkovScavGrenadeKind";
    private static final String TAG_FUSE = "TarkovScavGrenadeFuse";
    /** How much of the speed a bounce keeps. Below 1 so a grenade settles instead of pinging around forever. */
    private static final double BOUNCE_DAMPING = 0.4D;
    /** Below this speed a grenade is considered stopped (and stops playing bounce sounds). */
    private static final double REST_SPEED = 0.05D;

    private GrenadeKind kind = GrenadeKind.FRAG;
    private int fuse = -1;
    private boolean detonated;
    /** How many impact clips this grenade has played, and the ticks left before it may play another. */
    private int impactPlays;
    private int impactSoundCooldown;

    public GrenadeEntity(net.minecraft.world.entity.EntityType<? extends GrenadeEntity> type, Level level) {
        super(type, level);
    }

    /** The one constructor the item uses: kind, fuse (already cooked if it was held) and the thrower. */
    public GrenadeEntity(Level level, LivingEntity thrower, GrenadeKind kind, int fuse) {
        super(ModEntities.GRENADE.get(), thrower, level);
        this.kind = kind;
        this.fuse = fuse;
        syncItem();
    }

    public GrenadeKind kind() {
        return this.kind;
    }

    public int fuse() {
        return this.fuse;
    }

    @Override
    protected Item getDefaultItem() {
        // The default must agree on both sides: only the server knows the fuse/kind fields.
        return com.gfl.tarkovscav.registry.ModItems.grenadeItem(GrenadeKind.FRAG);
    }

    private void syncItem() {
        // ThrowableItemProjectile synchronizes non-default items through DATA_ITEM_STACK.
        this.setItem(new ItemStack(com.gfl.tarkovscav.registry.ModItems.grenadeItem(this.kind)));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.impactSoundCooldown > 0) {
            this.impactSoundCooldown--;
        }
        if (!Config.GRENADES_ENABLED.get()) {
            // Switched off while this one was in the air: it is quietly defused rather than exploding.
            this.discard();
            return;
        }
        if (this.fuse < 0) {
            this.fuse = this.kind.fuseTicks();
        }
        this.fuse--;
        if (this.fuse <= 0) {
            this.detonate();
        }
    }

    /** A bounce, not an impact: a grenade that hurts on contact would be a different weapon. */
    @Override
    protected void onHit(HitResult result) {
        if (this.level().isClientSide) {
            return;
        }
        Vec3 motion = this.getDeltaMovement();
        if (result.getType() == HitResult.Type.BLOCK) {
            Vec3 normal = Vec3.atLowerCornerOf(((net.minecraft.world.phys.BlockHitResult) result).getDirection()
                    .getNormal());
            motion = motion.subtract(normal.scale(2.0D * motion.dot(normal))).scale(BOUNCE_DAMPING);
            this.setDeltaMovement(motion);
            if (motion.length() > REST_SPEED) {
                this.level().playSound(null, this.blockPosition(), SoundEvents.ITEM_FRAME_ROTATE_ITEM,
                        SoundSource.NEUTRAL, 0.4F, 1.4F);
                playImpactSound();
            }
        } else {
            this.setDeltaMovement(motion.scale(0.6D));
        }
        // Deliberately no damage on contact: everything happens at the end of the fuse.
    }

    /**
     * The impact/bounce clip (README 5v): the sound of the grenade hitting the world is what tells the
     * player "it is over there", so it plays on the first ground contact and on the harder bounces after it.
     *
     * <p>Two guard rails, both config keys: a <b>cap per grenade</b> ({@code impactSoundMaxPerGrenade},
     * default 3) because the clip is about two seconds long and an uncapped bounce would stack it on itself,
     * and a <b>cooldown</b> ({@code impactSoundCooldownTicks}, default 8) so a grenade rolling down a slope
     * does not machine-gun it. The counters are not saved to NBT on purpose: after a chunk reload the
     * grenade may speak again, which is what a player expects from a falling object.</p>
     */
    private void playImpactSound() {
        if (!Config.GRENADE_IMPACT_SOUND.get()
                || this.impactPlays >= Config.GRENADE_IMPACT_SOUND_MAX_PER_GRENADE.get()
                || this.impactSoundCooldown > 0) {
            return;
        }
        List<SoundEvent> clips = ModSounds.impact();
        if (clips.isEmpty()) {
            // The manifest did not ship one: silence, but never a crash and never a missing sound id.
            return;
        }
        this.impactPlays++;
        this.impactSoundCooldown = Config.GRENADE_IMPACT_SOUND_COOLDOWN_TICKS.get();
        SoundEvent clip = clips.get(this.level().random.nextInt(clips.size()));
        this.level().playSound(null, this.blockPosition(), clip, SoundSource.NEUTRAL,
                0.9F * (float) Config.effectVolume(), 0.95F + this.level().random.nextFloat() * 0.1F);
        TarkovScav.LOGGER.debug("[grenade] {} impact {} ({}/{})", this.kind.id(), clip.getLocation(),
                this.impactPlays, Config.GRENADE_IMPACT_SOUND_MAX_PER_GRENADE.get());
    }

    /** Boom (or puff, or flash). */
    public void detonate() {
        if (this.detonated || this.level().isClientSide || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        this.detonated = true;
        GrenadeBlast.detonate(level, this.position(), this.kind, this.getOwner() instanceof LivingEntity living
                ? living : null);
        this.discard();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(TAG_KIND, this.kind.id());
        tag.putInt(TAG_FUSE, this.fuse);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        GrenadeKind saved = GrenadeKind.byId(tag.getString(TAG_KIND));
        this.kind = saved == null ? GrenadeKind.FRAG : saved;
        this.fuse = tag.contains(TAG_FUSE) ? tag.getInt(TAG_FUSE) : this.kind.fuseTicks();
        syncItem();
    }

    // ------------------------------------------------------------------ the shared arithmetic

    // "Where would it come down" lives in GrenadeBallistics and nowhere else (README 5v). The old flat
    // projection that used to sit here disagreed with the real flight, and two formulas never agree: the mob
    // safety check said "clear" while the grenade buried itself in a wall. Call GrenadeBallistics.solve -
    // never add a second step loop, the gate counts them.

    // ------------------------------------------------------------------ the guard rails

    /**
     * Whether a blast from {@code centre} may hurt {@code victim}, given who threw it (README 5v).
     *
     * <p>The rule, in order: with {@code grenades.friendlyFire = true} everything is fair game. Otherwise the
     * thrower is a special case - {@code grenades.playerSelfDamage} decides whether you can blow yourself up -
     * and anybody in the thrower's own faction is skipped, which is what keeps a squad from wiping itself
     * out.</p>
     */
    public static boolean mayHurt(@Nullable LivingEntity thrower, LivingEntity victim) {
        if (Config.GRENADES_FRIENDLY_FIRE.get()) {
            return true;
        }
        if (thrower == null) {
            return true;
        }
        if (victim == thrower) {
            return Config.GRENADES_PLAYER_SELF_DAMAGE.get();
        }
        return !Faction.allies(thrower, victim);
    }

    /** Sounds and particles for a blast with no block damage; also the "explosion" the kill feed sees. */
    static void visualExplosion(ServerLevel level, Vec3 centre, double power) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 1,
                0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z,
                (int) Math.max(4, power * 6), power * 0.4D, power * 0.4D, power * 0.4D, 0.0D);
        level.playSound(null, BlockPos.containing(centre), SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL,
                4.0F, 0.85F + level.random.nextFloat() * 0.2F);
    }

    /** A rough "how far does this kind reach", used for the safety check and the command's report. */
    public double effectiveRadius() {
        if (this.kind.isFlash()) {
            return this.kind.flashRadius();
        }
        if (this.kind.isSmoke()) {
            return this.kind.smokeRadius();
        }
        return Math.max(this.kind.fragmentRadius(), this.kind.blastPower() * 2.0D);
    }

    /** The entities a blast at {@code centre} would consider, for the safety check and the test command. */
    public static List<LivingEntity> candidates(ServerLevel level, Vec3 centre, double radius) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius),
                entity -> entity.isAlive() && !entity.isSpectator());
    }

    /** The readable id of a kind, for logs. */
    public static String describe(GrenadeKind kind) {
        return String.format(Locale.ROOT, "%s (fuse %d, frag %d x %.1f, blast %.1f, flash radius %.1f)",
                kind.id(), kind.fuseTicks(), kind.fragmentCount(), kind.fragmentDamage(), kind.blastPower(),
                kind.flashRadius());
    }
}
