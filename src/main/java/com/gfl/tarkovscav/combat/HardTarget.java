package com.gfl.tarkovscav.combat;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard targets: entities that soak up gunfire and sometimes bounce it (README 5z).
 *
 * <h2>How "this is gunfire" is decided - TaCZ's own damage tag</h2>
 * <p>Not by the shooter's item, not by the projectile class, and not by a guessed damage-type name: TaCZ ships
 * a <b>damage-type tag</b> {@code #tacz:bullets} (verified inside the user's TaCZ 1.1.7 jar at
 * {@code data/tacz/tags/damage_type/bullets.json}) listing exactly
 * {@code tacz:bullet}, {@code tacz:bullet_ignore_armor}, {@code tacz:bullet_void} and
 * {@code tacz:bullet_void_ignore_armor}. So the question is one call:
 * {@code damageSource.is(TACZ_BULLETS)}. Every other damage type - melee, explosion, fall, fire, magic, our
 * own grenades - fails that test and is left completely alone.</p>
 *
 * <h2>The order: ricochet first, reduction second</h2>
 * <ol>
 *   <li>the victim is in the {@code #tarkovscav:hard_target} tag (default: the iron golem);</li>
 *   <li>the damage is TaCZ bullet damage (or an arrow, if {@code ricochet.includeArrows});</li>
 *   <li>roll {@code ricochet.chance}: on a bounce the blow is set to <b>zero</b> and the projectile is
 *       reflected, with the clang and the sparks;</li>
 *   <li>otherwise the blow is multiplied by {@code ricochet.gunDamageMultiplier} (0.2 = the user's "80 %
 *       less").</li>
 * </ol>
 * <p>That order is the whole point of the feature: a ricochet must be <b>no damage at all</b>, not "damage
 * that was also reduced".</p>
 *
 * <h2>The reflection</h2>
 * <p>The projectile's velocity is reflected about the surface normal taken from the victim's bounding box:
 * the normal is the vector from the closest point of that box to the projectile, so a hit on the chest
 * bounces forward and a hit on the shoulder bounces sideways. When that vector is degenerate (the bullet is
 * inside the box) the incoming direction is simply reversed with a small random offset. Only a
 * {@link Projectile} is ever touched, and only {@code setDeltaMovement} - a direct entity that is not a
 * projectile (a hitscan source, a modded damage dealer) still gets the zero damage, the sound and the sparks.
 * Every projectile is marked, and a bullet may bounce at most
 * {@code ricochet.maxBouncesPerBullet} times, so two golems cannot ping it back and forth for ever.</p>
 *
 * <p><b>Honest limitation</b>: whether the reflected bullet <em>keeps flying</em> is TaCZ's business - its own
 * hit handling may discard the bullet on the same tick (piercing rounds continue, most do not). The reflected
 * direction, the zero damage, the sound and the sparks always happen; "it then hit somebody else" depends on
 * the round. See README 5z.</p>
 */
public final class HardTarget {
    /** The entities this rule applies to; a data-pack tag so a pack can add its own mechs (README 5z). */
    public static final TagKey<EntityType<?>> HARD_TARGET =
            TagKey.create(Registries.ENTITY_TYPE, TarkovScav.id("hard_target"));

    /** TaCZ's own bullet damage tag ({@code #tacz:bullets}); see the class comment. */
    public static final TagKey<DamageType> TACZ_BULLETS =
            TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("tacz", "bullets"));

    /** Bent per projectile UUID, so one bullet cannot bounce for ever (bounded, see {@link #rememberBounce}). */
    private static final Map<UUID, Integer> BOUNCES = new ConcurrentHashMap<>();
    private static final int BOUNCE_MEMORY_LIMIT = 4096;

    private HardTarget() {
    }

    /** True when this entity is a hard target at all. */
    public static boolean isHardTarget(Entity entity) {
        return entity != null && entity.getType().is(HARD_TARGET);
    }

    /**
     * True when the damage counts as gunfire for this rule: TaCZ bullet damage, or - only with
     * {@code ricochet.includeArrows} - a vanilla arrow.
     */
    public static boolean isGunfire(DamageSource source) {
        if (source == null) {
            return false;
        }
        if (source.is(TACZ_BULLETS)) {
            return true;
        }
        return Config.RICOCHET_INCLUDE_ARROWS.get() && source.getDirectEntity() instanceof AbstractArrow;
    }

    /**
     * The damage multiplier for a blow that did not ricochet (0.2 = "80 % less"), clamped to 0..1 so a silly
     * toml cannot turn a hit into a heal.
     */
    public static double damageMultiplier() {
        return Math.max(0.0D, Math.min(1.0D, Config.RICOCHET_GUN_DAMAGE_MULTIPLIER.get()));
    }

    /** The ricochet roll: true when this hit bounces (and therefore does no damage at all). */
    public static boolean rollsRicochet(RandomSource random) {
        double chance = Math.max(0.0D, Math.min(1.0D, Config.RICOCHET_CHANCE.get()));
        return chance > 0.0D && random.nextDouble() < chance;
    }

    /** The event hook. Registered on the Forge event bus from {@code TarkovScav}. */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!Config.RICOCHET_ENABLED.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!isHardTarget(victim) || !isGunfire(event.getSource())) {
            return;
        }
        // 1) the bounce: rolled FIRST, and it means zero damage rather than "less damage".
        if (rollsRicochet(victim.getRandom())) {
            boolean reflected = reflect(victim, event.getSource());
            float before = event.getAmount();
            event.setAmount(0.0F);
            effects(victim, event.getSource());
            if (Config.RICOCHET_LOG.get()) {
                TarkovScav.LOGGER.info("[ricochet] {} bounced {} damage off {} ({}); reflected={}",
                        victim.getName().getString(), String.format(java.util.Locale.ROOT, "%.1f", before),
                        event.getSource().getEntity() == null ? "unknown"
                                : event.getSource().getEntity().getName().getString(),
                        event.getSource().typeHolder().unwrapKey()
                                .map(key -> key.location().toString()).orElse("?"),
                        reflected);
            }
            return;
        }
        // 2) no bounce: the user's flat reduction.
        double multiplier = damageMultiplier();
        if (multiplier >= 1.0D) {
            return;
        }
        float before = event.getAmount();
        event.setAmount((float) (before * multiplier));
        if (Config.RICOCHET_LOG.get()) {
            TarkovScav.LOGGER.info("[ricochet] {} took {} instead of {} gunfire damage (x{})",
                    victim.getName().getString(),
                    String.format(java.util.Locale.ROOT, "%.1f", before * multiplier),
                    String.format(java.util.Locale.ROOT, "%.1f", before),
                    String.format(java.util.Locale.ROOT, "%.2f", multiplier));
        }
    }

    /**
     * Reflects the projectile that caused this damage, if there is one. Returns true when a projectile's
     * motion was actually changed; false means "the rule still applied (zero damage + effects), but there was
     * nothing to reflect" - a hitscan source, a non-projectile direct entity, or a bullet that is out of
     * bounces.
     */
    public static boolean reflect(LivingEntity victim, DamageSource source) {
        if (!(source.getDirectEntity() instanceof Projectile projectile)) {
            return false;
        }
        int max = Config.RICOCHET_MAX_BOUNCES_PER_BULLET.get();
        if (max <= 0) {
            return false;
        }
        int already = rememberedBounces(projectile);
        if (already >= max) {
            return false;
        }
        Vec3 incoming = projectile.getDeltaMovement();
        if (incoming.lengthSqr() < 1.0E-8D) {
            return false;
        }
        Vec3 normal = surfaceNormal(victim, projectile);
        Vec3 reflected = incoming.subtract(normal.scale(2.0D * incoming.dot(normal)));
        if (reflected.lengthSqr() < 1.0E-8D) {
            // Degenerate (straight-on into a flat face): reverse and jitter, so it never freezes in place.
            reflected = incoming.reverse().add((victim.getRandom().nextDouble() - 0.5D) * 0.4D,
                    (victim.getRandom().nextDouble() - 0.5D) * 0.4D,
                    (victim.getRandom().nextDouble() - 0.5D) * 0.4D);
        }
        projectile.setDeltaMovement(reflected);
        projectile.hasImpulse = true;
        // Nudge it out of the victim's hitbox, otherwise the same bullet is still inside the golem next tick.
        AABB box = victim.getBoundingBox().inflate(0.15D);
        Vec3 position = projectile.position();
        if (box.contains(position)) {
            Vec3 out = position.subtract(box.getCenter());
            if (out.lengthSqr() < 1.0E-8D) {
                out = normal;
            }
            projectile.setPos(position.add(out.normalize().scale(0.35D)));
        }
        rememberBounce(projectile, already + 1);
        return true;
    }

    /**
     * The surface normal at the impact: from the closest point of the victim's bounding box to the
     * projectile. A hit on the chest of a golem therefore bounces forward, a hit on its shoulder sideways -
     * which is what makes a ricochet look like it came off the armour rather than out of thin air.
     */
    static Vec3 surfaceNormal(LivingEntity victim, Projectile projectile) {
        AABB box = victim.getBoundingBox();
        Vec3 point = projectile.position();
        Vec3 closest = new Vec3(
                Math.max(box.minX, Math.min(box.maxX, point.x)),
                Math.max(box.minY, Math.min(box.maxY, point.y)),
                Math.max(box.minZ, Math.min(box.maxZ, point.z)));
        Vec3 normal = point.subtract(closest);
        if (normal.lengthSqr() < 1.0E-8D) {
            return projectile.getDeltaMovement().normalize().reverse();
        }
        return normal.normalize();
    }

    /** The metal-on-metal clang and the sparks (README 5z). */
    public static void effects(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        Entity direct = source.getDirectEntity() == null ? victim : source.getDirectEntity();
        Vec3 at = direct.position();
        float volume = (float) Math.max(0.0D, Config.RICOCHET_SOUND_VOLUME.get());
        if (volume > 0.0F) {
            // 1.20.1 has no item.trident.ricochet sound (that file only appears in later versions), so the
            // closest vanilla metal-on-metal impact - the trident's ground_impact - is used instead. It is a
            // SoundEvent constant, not a mod asset: nothing to ship, nothing to register.
            level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.NEUTRAL, volume,
                    1.0F + level.random.nextFloat() * 0.2F);
        }
        if (Config.RICOCHET_SPARKS.get()) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, at.x, at.y, at.z, 8,
                    0.15D, 0.15D, 0.15D, 0.12D);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 6,
                    0.12D, 0.12D, 0.12D, 0.05D);
        }
    }

    /** How many times this projectile has bounced already. */
    static int rememberedBounces(Projectile projectile) {
        return BOUNCES.getOrDefault(projectile.getUUID(), 0);
    }

    /** Remembers one more bounce for this projectile, keeping the map bounded. */
    static void rememberBounce(Projectile projectile, int total) {
        if (BOUNCES.size() > BOUNCE_MEMORY_LIMIT) {
            BOUNCES.clear();
        }
        BOUNCES.put(projectile.getUUID(), total);
    }

    /** Forgets everything; called on a config reload so a changed rule starts clean. */
    public static void invalidate() {
        BOUNCES.clear();
    }
}
