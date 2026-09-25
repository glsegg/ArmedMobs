package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The smoke clouds (README 5v), ticked by hand.
 *
 * <p><b>This is a simplification and the README says so:</b> there is no line-of-sight occlusion model. A cloud
 * is a position, a radius and an expiry; while it lives it spawns particles and applies vanilla Blindness to
 * the <b>mobs</b> inside it - which the gun AI reads as "cannot see, do not shoot" ({@code GunBrain}). Players
 * are deliberately left alone: standing in smoke hides you by hiding the screen, not by taking your controls
 * away.</p>
 */
public final class GrenadeEvents {
    /** One live cloud. Kept out of the entity system on purpose: nothing saves, syncs or collides. */
    private record Cloud(ServerLevel level, Vec3 centre, double radius, long until, long lastPuff) {
    }

    private static final List<Cloud> CLOUDS = new ArrayList<>();

    private GrenadeEvents() {
    }

    /** A smoke grenade just went off. */
    public static void spawnSmoke(ServerLevel level, Vec3 centre, GrenadeKind kind) {
        long now = level.getGameTime();
        CLOUDS.add(new Cloud(level, centre, kind.smokeRadius(), now + kind.smokeDurationTicks(), now));
        level.playSound(null, net.minecraft.core.BlockPos.containing(centre),
                net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.NEUTRAL,
                2.0F, 0.6F);
        TarkovScav.LOGGER.info("[grenade] smoke cloud at {} for {} tick(s), radius {}",
                centre.toString(), kind.smokeDurationTicks(), kind.smokeRadius());
    }

    /** How many clouds are alive, for the test command and the gate. */
    public static int cloudCount() {
        return CLOUDS.size();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CLOUDS.isEmpty()) {
            return;
        }
        long now = CLOUDS.get(0).level().getGameTime();
        Iterator<Cloud> iterator = CLOUDS.iterator();
        while (iterator.hasNext()) {
            Cloud cloud = iterator.next();
            if (now > cloud.until() || !Config.GRENADES_ENABLED.get()) {
                iterator.remove();
                continue;
            }
            puff(cloud, now);
        }
    }

    /** Particles every 5 ticks, blindness every 10 - cheap, and dense enough to look like smoke. */
    private static void puff(Cloud cloud, long now) {
        ServerLevel level = cloud.level();
        if (now - cloud.lastPuff() >= 5L) {
            double radius = cloud.radius();
            int count = (int) Math.max(8.0D, radius * 8.0D);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, cloud.centre().x, cloud.centre().y + 0.2D,
                    cloud.centre().z, count, radius * 0.7D, radius * 0.4D, radius * 0.7D, 0.01D);
            CLOUDS.set(CLOUDS.indexOf(cloud), new Cloud(level, cloud.centre(), cloud.radius(), cloud.until(), now));
        }
        if (now % 10L != 0L) {
            return;
        }
        for (LivingEntity entity : GrenadeEntity.candidates(level, cloud.centre(), cloud.radius())) {
            if (entity instanceof Player) {
                // See the class comment: the player's view is blocked by the particles, nothing else.
                continue;
            }
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        }
    }

    /** Test-command hook: forget every cloud (so a test can start clean). */
    public static void clearClouds() {
        CLOUDS.clear();
    }
}
