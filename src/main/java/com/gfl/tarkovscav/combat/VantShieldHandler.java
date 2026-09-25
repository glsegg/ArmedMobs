package com.gfl.tarkovscav.combat;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The VANT ballistic shield rule (README 5zb): a holder whose FRONT is turned into the shot takes
 * {@code shield.bulletReduction} less bullet damage, and the shield pays for it in durability until it
 * shatters.
 *
 * <h2>Four gates, in this order</h2>
 * <ol>
 *   <li>{@code shield.enabled} - the master switch;</li>
 *   <li>{@code source.is(DamageTypeTags.IS_EXPLOSION)} - a grenade, TNT or a creeper is NEVER reduced.
 *       With the shipped
 *       {@code shield.protectFromExplosions = false} this is a plain early return, so the user's
 *       "无法防御手雷伤害" is a property of the default config; an operator may opt in, and only
 *       then does an explosion ever reach the arc;</li>
 *   <li>{@link HardTarget#isGunfire(DamageSource)} - the SAME classifier the iron golem's ricochet
 *       uses, so "this is gunfire" has exactly one definition in this mod: TaCZ's own damage-type tag
 *       {@code #tacz:bullets}. Melee, fall, fire, magic and this mod's own fragments are untouched;</li>
 *   <li>the holder actually holds the shield in main or off hand, and the blow comes from inside the
 *       front cone.</li>
 * </ol>
 *
 * <h2>The arc</h2>
 * <p>One dot product: the blow is blocked when the angle between the holder's look vector and the
 * vector from the holder's eyes to the damage source is at most {@code shield.frontAngleDegrees}
 * (default 90, i.e. a 180-degree front cone). The cone edge itself counts as inside. The fallback chain
 * for the source position is {@link DamageSource#getSourcePosition()} (the projectile, when there is
 * one), then the attacker's position, then null. <b>An unknown origin is treated as IN the arc</b> -
 * that is the safe side for the holder, and it is unreachable for real TaCZ bullet damage because the
 * bullet entity supplies a position.</p>
 *
 * <h2>The durability</h2>
 * <p>{@code max(1, ceil(blockedDamage / shield.durabilityPerBlockedHit))} points per blocked hit
 * (default: 2 damage per point, so a 20-damage rifle round that is reduced to 0.2 costs 10). When the
 * charge would reach zero the stack is destroyed on the spot - removed from the hand, break sound,
 * particles and a message - so a broken shield protects nothing afterwards.</p>
 */
public final class VantShieldHandler {
    /** The gate's unit test needs the same tolerance the code uses, so it is a named constant. */
    public static final double ARC_EPSILON = 1.0E-9D;

    private VantShieldHandler() {
    }

    /** The Forge hook. Registered from {@code TarkovScav} on the Forge event bus. */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!Config.SHIELD_ENABLED.get()) {
            return;
        }
        LivingEntity holder = event.getEntity();
        if (holder.level().isClientSide()) {
            return;
        }
        DamageSource source = event.getSource();
        // Grenades and every other explosion bypass the shield unless an operator opts in. The default
        // (false) makes this a plain early return, which is the user's "no defence against grenades".
        // 1.20.1 has no DamageSource#isExplosion(), so this asks the vanilla damage-type tag
        // #minecraft:is_explosion - the same tag-shaped judgement the gunfire test uses, and it also
        // covers a modded explosion damage type that opts into the vanilla tag.
        if (source.is(DamageTypeTags.IS_EXPLOSION) && !Config.SHIELD_PROTECT_FROM_EXPLOSIONS.get()) {
            return;
        }
        // "Is this gunfire" is asked of TaCZ's #tacz:bullets damage-type tag - the same predicate the
        // iron golem's ricochet uses. No second classifier, no guessed damage-type name.
        if (!HardTarget.isGunfire(source)) {
            return;
        }
        ItemStack shield = heldShield(holder);
        if (shield.isEmpty()) {
            return;
        }
        if (!inFrontArc(holder, source)) {
            return;
        }
        float before = event.getAmount();
        if (before <= 0.0F) {
            return;
        }
        double reduction = bulletReduction();
        if (reduction <= 0.0D) {
            // Nothing to block: do not charge durability for a hit the shield did not stop.
            return;
        }
        float after = (float) (before * (1.0D - reduction));
        event.setAmount(after);
        double blocked = before - after;
        // Feedback first, so the player sees the shield work on the tick it worked.
        blockEffects(holder, source);
        if (applyCharge(holder, shield, durabilityCharge(blocked))) {
            breakEffects(holder, source);
        }
    }

    /** The shield in the main hand, else the off hand; EMPTY when the holder carries none. */
    public static ItemStack heldShield(LivingEntity holder) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = holder.getItemInHand(hand);
            if (held.is(ModItems.VANT_SHIELD.get())) {
                return held;
            }
        }
        return ItemStack.EMPTY;
    }

    /** True when the blow comes from inside the shield holder's front cone. */
    public static boolean inFrontArc(LivingEntity holder, DamageSource source) {
        Vec3 toSource = directionToSource(holder, source);
        if (toSource == null) {
            // Unknown origin: protect the holder. Stated in the class comment and pinned by the gate.
            return true;
        }
        return insideCone(holder.getViewVector(1.0F), toSource,
                Config.SHIELD_FRONT_ANGLE_DEGREES.get());
    }

    /**
     * The arc rule as one dot product: inside when the angle between the look vector and the direction
     * to the source is at most {@code halfAngleDegrees}. The cone edge counts as inside (angle <=
     * half angle), which is why cos(90 degrees) = 6.1E-17 needs the epsilon rather than a bare ">= 0".
     *
     * <p>Pure and static on purpose: {@code tools/selftest_shield.js} re-runs this as a deterministic
     * unit test over fixed vectors and prints every angle.</p>
     */
    public static boolean insideCone(Vec3 look, Vec3 toSource, double halfAngleDegrees) {
        if (look.lengthSqr() < 1.0E-8D || toSource.lengthSqr() < 1.0E-8D) {
            // Degenerate (the shooter is inside the holder): treat it as a hit from the front.
            return true;
        }
        double halfAngle = Math.max(0.0D, Math.min(180.0D, halfAngleDegrees));
        double cos = Math.cos(Math.toRadians(halfAngle));
        return look.normalize().dot(toSource.normalize()) >= cos - ARC_EPSILON;
    }

    /**
     * The holder's eyes to the damage source, or null when the source carries no position at all.
     * {@link DamageSource#getSourcePosition()} already prefers the projectile (the direct entity), so
     * the attacker's position is the fallback for a hitscan-shaped source.
     */
    public static Vec3 directionToSource(LivingEntity holder, DamageSource source) {
        Vec3 from = holder.getEyePosition();
        Vec3 at = source.getSourcePosition();
        if (at == null && source.getEntity() != null) {
            at = source.getEntity().position();
        }
        if (at == null) {
            return null;
        }
        return at.subtract(from);
    }

    /** The configured reduction, clamped to 0..1 so a silly toml cannot turn a hit into a heal. */
    public static double bulletReduction() {
        return Math.max(0.0D, Math.min(1.0D, Config.SHIELD_BULLET_REDUCTION.get()));
    }

    /**
     * The durability charge for one blocked hit:
     * {@code max(1, ceil(blockedDamage / shield.durabilityPerBlockedHit))}.
     */
    public static int durabilityCharge(double blockedDamage) {
        double per = Config.SHIELD_DURABILITY_PER_BLOCKED_HIT.get();
        if (per <= 0.0D) {
            return Math.max(1, (int) Math.ceil(blockedDamage));
        }
        return Math.max(1, (int) Math.ceil(blockedDamage / per));
    }

    /**
     * Charges one shield stack. Returns true when the charge exhausted the durability: the stack is
     * then destroyed (removed from the hand, break sound, particles, message) rather than left at zero.
     */
    public static boolean applyCharge(LivingEntity holder, ItemStack shield, int charge) {
        int remaining = shield.getMaxDamage() - shield.getDamageValue();
        if (charge >= remaining) {
            destroyShield(holder, shield);
            return true;
        }
        shield.setDamageValue(shield.getDamageValue() + charge);
        return false;
    }

    /**
     * "碎掉消失": the stack leaves the hand it is held in and the holder is told. Called only at zero
     * durability, so a shield can never be left at 0 and keep protecting.
     */
    public static void destroyShield(LivingEntity holder, ItemStack shield) {
        boolean cleared = false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (holder.getItemInHand(hand) == shield) {
                holder.setItemInHand(hand, ItemStack.EMPTY);
                cleared = true;
                break;
            }
        }
        if (!cleared) {
            // Defensive: the stack was not reachable through the hands, so empty it where it stands.
            shield.setCount(0);
        }
        if (holder instanceof Player player) {
            player.displayClientMessage(Component.translatable("tarkovscav.shield.broken")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** The metallic clang and the sparks on every reduced hit. */
    public static void blockEffects(LivingEntity holder, DamageSource source) {
        if (!(holder.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 at = effectPosition(holder, source);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL,
                0.9F, 0.9F + level.random.nextFloat() * 0.2F);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6,
                0.18D, 0.18D, 0.18D, 0.06D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 4,
                0.15D, 0.15D, 0.15D, 0.02D);
    }

    /** The extra crack when the shield is destroyed. */
    public static void breakEffects(LivingEntity holder, DamageSource source) {
        if (!(holder.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 at = effectPosition(holder, source);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BREAK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 14,
                0.30D, 0.30D, 0.30D, 0.20D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 10,
                0.25D, 0.25D, 0.25D, 0.08D);
    }

    /** Where the sparks go: the bullet when there is one, otherwise the holder's face. */
    static Vec3 effectPosition(LivingEntity holder, DamageSource source) {
        Entity direct = source.getDirectEntity();
        return direct == null ? holder.getEyePosition() : direct.position();
    }
}
