package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * What actually happens when one of the five throwables goes off (README 5v).
 *
 * <h2>No block is ever touched unless you ask for it</h2>
 * <p>With {@code grenades.terrainDamage = false} (the default) <b>no explosion object is created at all</b>:
 * this class does the damage itself and spawns only particles and a sound. That is a deliberate choice over
 * {@code ExplosionInteraction.NONE} for two reasons - the friendly-fire rule has to apply to the blast as well
 * as to the fragments (vanilla's explosion does not know about factions), and "the terrain is untouched" then
 * holds <em>by construction</em> instead of by trusting a flag. With {@code terrainDamage = true} the vanilla
 * block-breaking explosion runs as well, and the fragments are still applied on top.</p>
 *
 * <h2>The fragment model</h2>
 * <p>{@code fragmentCount} rays are cast from the blast point in a deterministic, evenly spread set of
 * directions. Each ray walks forward in {@code fragmentStep} blocks and <b>stops at the first block it hits</b>,
 * so a wall is real cover; along the way it damages the first living entity within 0.6 blocks of the sample
 * point, at most {@link #MAX_HITS_PER_ENTITY} times per blast (a body stops most of the spray). Damage is
 * {@code fragmentDamage * (1 - distance / fragmentRadius)}, and the share {@code fragmentArmorPierce} of it
 * ignores armour: {@code final = damage * (1 - (1 - pierce) * min(20, armour) / 25)}.</p>
 *
 * <h2>Flash and smoke</h2>
 * <p>A flash needs line of sight (a wall blocks it), falls off with distance and is halved by looking away;
 * players get a client-side white overlay, mobs get vanilla Blindness, which the gun AI reads as "cannot see,
 * do not shoot". Smoke is a <b>simplified</b> cloud: it spawns particles and blinds the mobs inside it for its
 * duration - there is no real line-of-sight occlusion model.</p>
 */
public final class GrenadeBlast {
    /** A body stops most of the spray: at most this many fragments hit one entity per blast. */
    public static final int MAX_HITS_PER_ENTITY = 3;
    /** How close a fragment sample point has to be to an entity to count as a hit, in blocks. */
    private static final double FRAGMENT_HIT_RADIUS = 0.6D;

    private GrenadeBlast() {
    }

    /** The entry point: one grenade just reached the end of its fuse. */
    public static void detonate(ServerLevel level, Vec3 centre, GrenadeKind kind, @Nullable LivingEntity thrower) {
        if (!Config.GRENADES_ENABLED.get()) {
            return;
        }
        if (kind.isSmoke()) {
            GrenadeEvents.spawnSmoke(level, centre, kind);
            return;
        }
        if (kind.isFlash()) {
            flash(level, centre, kind, thrower);
            return;
        }
        if (Config.GRENADES_TERRAIN_DAMAGE.get()) {
            // The opt-in path: the vanilla explosion breaks blocks AND damages entities.
            level.explode(thrower, centre.x, centre.y, centre.z, (float) kind.blastPower(),
                    net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
        } else {
            GrenadeEntity.visualExplosion(level, centre, kind.blastPower());
            blastDamage(level, centre, kind, thrower);
        }
        castFragments(level, centre, kind, thrower);
        TarkovScav.LOGGER.info("[grenade] {} detonated at {} ({}{})", kind.id(), centre.toString(),
                GrenadeEntity.describe(kind), Config.GRENADES_TERRAIN_DAMAGE.get() ? ", terrain damage" : "");
    }

    // ------------------------------------------------------------------ the blast

    /** Entity damage for the no-terrain-damage path: an inverse-square-ish falloff inside power * 2 blocks. */
    private static void blastDamage(ServerLevel level, Vec3 centre, GrenadeKind kind,
                                    @Nullable LivingEntity thrower) {
        double radius = kind.blastPower() * 2.0D;
        if (radius <= 0.0D) {
            return;
        }
        for (LivingEntity victim : GrenadeEntity.candidates(level, centre, radius)) {
            if (!GrenadeEntity.mayHurt(thrower, victim)) {
                continue;
            }
            double distance = victim.getEyePosition().distanceTo(centre);
            double falloff = 1.0D - Math.min(1.0D, distance / radius);
            double damage = kind.blastPower() * Config.GRENADE_BLAST_DAMAGE_PER_POWER.get() * falloff;
            if (damage <= 0.05D) {
                continue;
            }
            hurt(level, victim, thrower, kind, damage, 1.0D);
        }
    }

    // ------------------------------------------------------------------ the fragments

    /** The fragment fan. Directions are deterministic for a given blast seed, so a test can replay them. */
    public static void castFragments(ServerLevel level, Vec3 centre, GrenadeKind kind,
                                     @Nullable LivingEntity thrower) {
        int count = kind.fragmentCount();
        double radius = kind.fragmentRadius();
        if (count <= 0 || radius <= 0.0D) {
            return;
        }
        double step = Config.GRENADE_FRAG_STEP.get();
        Random random = new Random(level.random.nextLong());
        Map<UUID, Integer> hits = new HashMap<>();
        Set<UUID> logged = new HashSet<>();
        for (int ray = 0; ray < count; ray++) {
            Vec3 direction = spreadDirection(random, ray, count);
            Vec3 end = centre.add(direction.scale(radius));
            HitResult block = level.clip(new ClipContext(centre, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, null));
            double reach = block.getType() == HitResult.Type.MISS ? radius : centre.distanceTo(block.getLocation());
            for (double travelled = step; travelled <= reach; travelled += step) {
                Vec3 sample = centre.add(direction.scale(travelled));
                List<LivingEntity> found = GrenadeEntity.candidates(level, sample, FRAGMENT_HIT_RADIUS);
                if (found.isEmpty()) {
                    continue;
                }
                for (LivingEntity victim : found) {
                    if (!GrenadeEntity.mayHurt(thrower, victim)) {
                        continue;
                    }
                    int soFar = hits.getOrDefault(victim.getUUID(), 0);
                    if (soFar >= MAX_HITS_PER_ENTITY) {
                        continue;
                    }
                    hits.put(victim.getUUID(), soFar + 1);
                    double falloff = 1.0D - Math.min(1.0D, travelled / radius);
                    double damage = kind.fragmentDamage() * falloff;
                    hurt(level, victim, thrower, kind, damage, Config.GRENADE_FRAG_ARMOR_PIERCE.get());
                    if (logged.add(victim.getUUID())) {
                        TarkovScav.LOGGER.debug("[grenade] fragment hit {} at {} block(s) for {}",
                                victim.getName().getString(), String.format(java.util.Locale.ROOT, "%.1f", travelled),
                                String.format(java.util.Locale.ROOT, "%.1f", damage));
                    }
                    break;
                }
            }
        }
    }

    /**
     * An evenly spread direction: a golden-angle spiral over the sphere, so 8 rays cover the sphere as well as
     * 8 rays can and the pattern is the same for every blast of a given count. A little jitter keeps it from
     * looking mechanical.
     */
    static Vec3 spreadDirection(Random random, int index, int count) {
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        double y = 1.0D - 2.0D * (index + 0.5D) / count;
        double radiusAtY = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
        double theta = golden * index + random.nextDouble() * 0.25D;
        return new Vec3(Math.cos(theta) * radiusAtY, y, Math.sin(theta) * radiusAtY);
    }

    /** The shared damage path: the faction rule, armour piercing, and the kill-feed attribution. */
    private static void hurt(ServerLevel level, LivingEntity victim, @Nullable LivingEntity thrower,
                             GrenadeKind kind, double damage, double armorPierce) {
        if (damage <= 0.0D) {
            return;
        }
        double armor = Math.min(20.0D, victim.getArmorValue());
        double finalDamage = damage * (1.0D - (1.0D - armorPierce) * armor / 25.0D);
        if (finalDamage <= 0.0D) {
            return;
        }
        // Which grenade was it? The kill feed asks this map when the death event fires (same call, below), and
        // it is cleared immediately afterwards so a later death cannot inherit it.
        GrenadeAttribution.remember(victim, kind);
        try {
            victim.hurt(level.damageSources().explosion(thrower, thrower), (float) finalDamage);
        } finally {
            GrenadeAttribution.forget(victim);
        }
    }

    // ------------------------------------------------------------------ the flash

    /** Line of sight and facing decide how bad a flashbang is (README 5v). */
    public static void flash(ServerLevel level, Vec3 centre, GrenadeKind kind, @Nullable LivingEntity thrower) {
        double radius = kind.flashRadius();
        double lookAway = Config.GRENADE_FLASH_LOOK_AWAY_FACTOR.get();
        level.playSound(null, BlockPos.containing(centre), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL,
                4.0F, 1.3F);
        level.sendParticles(ParticleTypes.FLASH, centre.x, centre.y, centre.z, 2, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, centre.x, centre.y, centre.z, 40, 1.0D, 1.0D, 1.0D, 0.2D);
        for (LivingEntity victim : GrenadeEntity.candidates(level, centre, radius)) {
            // README 5v: a flashbang blinds EVERYBODY, our own units and their allies included - the light does
            // not check factions. (The blast and the fragments DO check them; see GrenadeEntity#mayHurt.)
            if (!Config.GRENADE_FLASH_BLINDS_MOBS.get() && !(victim instanceof ServerPlayer)) {
                continue;
            }
            Vec3 eyes = victim.getEyePosition();
            double distance = eyes.distanceTo(centre);
            double falloff = 1.0D - Math.min(1.0D, distance / radius);
            if (falloff <= 0.0D) {
                continue;
            }
            // A wall blocks the light: no line of sight, no flash.
            HitResult block = level.clip(new ClipContext(centre, eyes, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, null));
            if (block.getType() != HitResult.Type.MISS) {
                continue;
            }
            Vec3 toBlast = centre.subtract(eyes).normalize();
            double facing = victim.getViewVector(1.0F).dot(toBlast);
            double lookFactor = facing <= 0.0D ? lookAway : lookAway + (1.0D - lookAway) * facing;
            double intensity = kind.flashIntensity() * falloff * lookFactor;
            if (intensity <= 0.02D) {
                continue;
            }
            if (victim instanceof ServerPlayer player) {
                int ticks = (int) (kind.playerBlindTicks() * falloff * lookFactor);
                com.gfl.tarkovscav.killfeed.KillFeedNetwork.sendFlash(player, intensity, ticks);
            } else {
                int ticks = (int) (kind.mobBlindTicks() * falloff * lookFactor);
                victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Math.max(10, ticks), 0, false,
                        false));
            }
            TarkovScav.LOGGER.debug("[grenade] flash on {}: distance {} falloff {} facing {} intensity {}",
                    victim.getName().getString(), String.format(java.util.Locale.ROOT, "%.1f", distance),
                    String.format(java.util.Locale.ROOT, "%.2f", falloff),
                    String.format(java.util.Locale.ROOT, "%.2f", facing),
                    String.format(java.util.Locale.ROOT, "%.2f", intensity));
        }
    }

    /**
     * The expected fragment damage on one body at {@code distance}, in closed form.
     *
     * <p>A body is treated as a sphere of radius {@link #FRAGMENT_HIT_RADIUS}; a ray direction is uniform over
     * the sphere, so the chance a given ray passes through that sphere at distance {@code d} is its solid-angle
     * share {@code r^2 / (4 d^2)}. Multiply by the fragment count, cap at {@link #MAX_HITS_PER_ENTITY}, and
     * apply the same linear falloff the blast uses. The gate checks this against a Monte-Carlo run of the real
     * direction function, so the number the command prints is the number the fragments deliver.</p>
     */
    public static double expectedFragmentDamage(GrenadeKind kind, double distance) {
        double radius = kind.fragmentRadius();
        if (kind.fragmentCount() <= 0 || distance <= 0.0D || distance >= radius) {
            return 0.0D;
        }
        double hits = kind.fragmentCount() * FRAGMENT_HIT_RADIUS * FRAGMENT_HIT_RADIUS / (4.0D * distance * distance);
        hits = Math.min(MAX_HITS_PER_ENTITY, hits);
        return kind.fragmentDamage() * (1.0D - distance / radius) * hits;
    }

    /** The blast damage on one body at {@code distance} for the no-terrain-damage path. */
    public static double expectedBlastDamage(GrenadeKind kind, double distance) {
        double radius = kind.blastPower() * 2.0D;
        if (radius <= 0.0D || distance >= radius) {
            return 0.0D;
        }
        return kind.blastPower() * Config.GRENADE_BLAST_DAMAGE_PER_POWER.get() * (1.0D - distance / radius);
    }

    /** For the test command: the same facing/falloff maths, without touching anybody. */    public static double flashIntensityFor(Vec3 centre, Vec3 eyes, Vec3 look, double radius) {
        double distance = eyes.distanceTo(centre);
        double falloff = 1.0D - Math.min(1.0D, distance / radius);
        if (falloff <= 0.0D) {
            return 0.0D;
        }
        double lookAway = Config.GRENADE_FLASH_LOOK_AWAY_FACTOR.get();
        double facing = look.normalize().dot(centre.subtract(eyes).normalize());
        double lookFactor = facing <= 0.0D ? lookAway : lookAway + (1.0D - lookAway) * facing;
        return Math.max(0.0D, falloff * lookFactor);
    }
}
