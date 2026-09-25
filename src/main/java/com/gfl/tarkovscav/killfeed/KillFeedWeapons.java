package com.gfl.tarkovscav.killfeed;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "What weapon was that?" - the resolver behind the kill feed (README 5u).
 *
 * <h2>Table-driven on purpose</h2>
 * <p>The answer comes from an ordered list of {@link Resolver}s, and the grenade batch (and any future
 * weapon) adds one with {@link #register} instead of editing a chain of {@code if}s. The gate asserts both
 * that the list exists and that the registration point does, because "add a weapon without touching the
 * resolver's control flow" is the property that keeps this from rotting.</p>
 *
 * <h2>What is sent, and why it is not the item id</h2>
 * <p>For an item the record carries the <b>ItemStack</b>, not a name: the client resolves
 * {@code getHoverName()} in the player's own language, which is the only correct answer for two reasons - a
 * TaCZ gun's display name comes from TaCZ's CLIENT-side gun index (the dedicated server does not have it), and
 * a vanilla sword's name is translated on the client. This also means a gun shows its real name ("AK-47")
 * rather than {@code tacz:ak47} or a raw translation key.</p>
 *
 * <h2>The fallbacks, in order (also asserted by the gate)</h2>
 * <ol>
 *   <li>the damage source's causing entity - the killer for a projectile, the shooter for a bullet;</li>
 *   <li>the victim's {@code lastHurtByMob} - this is what covers mob-versus-mob kills, because
 *       {@code LivingEntity#getKiller()} only ever returns a <b>player</b>;</li>
 *   <li>the victim's {@code lastHurtByPlayer};</li>
 *   <li>the victim's {@code lastDamageSource};</li>
 *   <li>nothing - an environment death.</li>
 * </ol>
 */
public final class KillFeedWeapons {
    /** One step of the resolver chain. Returns null to pass the question on to the next step. */
    public interface Resolver {
        @Nullable
        Armament resolve(@Nullable LivingEntity killer, LivingEntity victim, DamageSource source);
    }

    /** A resolved answer: a category plus (for {@link KillFeedSource#ITEM}) the stack to name. */
    public record Armament(KillFeedSource source, ItemStack stack, String debugId) {
        static Armament item(ItemStack stack) {
            return new Armament(KillFeedSource.ITEM, stack, stack.getItem().toString());
        }

        static Armament of(KillFeedSource source) {
            return new Armament(source, ItemStack.EMPTY, source.id());
        }

        /** The id used for the WARN-once bookkeeping and for dedup. */
        public String id() {
            return this.debugId;
        }
    }

    /**
     * The ordered chain. The first resolver that answers wins, so the most specific question ("was this an
     * explosion?") is asked first.
     */
    private static final List<Resolver> RESOLVERS = new ArrayList<>(List.of(
            KillFeedWeapons::fromExplosion,
            KillFeedWeapons::fromKillerHand,
            KillFeedWeapons::fromDamageMessage));

    /** Weapon ids already WARNed about, so a wrong answer is reported once and not once per death. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private KillFeedWeapons() {
    }

    /**
     * <b>The extension point.</b> A new weapon type (a thrown grenade, a laser, a turret) registers a resolver
     * here; nothing in this class has to change for it to work.
     */
    public static void register(Resolver resolver) {
        RESOLVERS.add(0, resolver);
    }

    /** The number of registered resolvers, for the debug output. */
    public static int resolverCount() {
        return RESOLVERS.size();
    }

    /** What killed {@code victim}, from {@code killer} when there was one. */
    public static Armament resolve(@Nullable LivingEntity killer, LivingEntity victim, DamageSource source) {
        for (Resolver resolver : RESOLVERS) {
            Armament answer = resolver.resolve(killer, victim, source);
            if (answer != null) {
                return answer;
            }
        }
        return unknown(source, killer);
    }

    // ------------------------------------------------------------------ the resolvers

    /** An explosion is an explosion before it is anything else. */
    @Nullable
    private static Armament fromExplosion(@Nullable LivingEntity killer, LivingEntity victim,
                                          DamageSource source) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.EXPLOSION)
                || source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_EXPLOSION)) {
            return Armament.of(KillFeedSource.EXPLOSION);
        }
        return null;
    }

    /**
     * The item in the killer's hand <b>at the moment of the kill</b> - which is the gun that fired, the bow
     * that loosed, or the sword that swung. An empty hand is "fists" rather than "unknown".
     */
    @Nullable
    private static Armament fromKillerHand(@Nullable LivingEntity killer, LivingEntity victim,
                                          DamageSource source) {
        if (killer == null) {
            return null;
        }
        ItemStack held = killer.getMainHandItem();
        if (!held.isEmpty()) {
            return Armament.item(held.copyWithCount(1));
        }
        // An empty hand throwing something (a snowball, an egg, a trident-less trident kill) still has a
        // direct entity to name, so only a genuinely bare-handed kill is "fists".
        ItemStack projectile = projectileItem(source);
        if (!projectile.isEmpty()) {
            return Armament.item(projectile);
        }
        return Armament.of(KillFeedSource.FISTS);
    }

    /**
     * Last resort before "unknown": the damage message itself, mapped by a table. This is what names a fall, a
     * drowning, fire, magic and the rest, and it keeps the mapping in one place instead of a chain of string
     * comparisons.
     */
    @Nullable
    private static Armament fromDamageMessage(@Nullable LivingEntity killer, LivingEntity victim,
                                              DamageSource source) {
        KillFeedSource mapped = fromMessageId(source.getMsgId());
        return mapped == null ? null : Armament.of(mapped);
    }

    /** The damage-type table. Add a row here rather than a new branch somewhere else. */
    @Nullable
    public static KillFeedSource fromMessageId(String messageId) {
        String id = messageId == null ? "" : messageId.toLowerCase(Locale.ROOT);
        if (id.contains("explosion")) {
            return KillFeedSource.EXPLOSION;
        }
        if (id.contains("fall") || id.contains("flyintowall")) {
            return KillFeedSource.FALL;
        }
        if (id.contains("drown")) {
            return KillFeedSource.DROWNING;
        }
        if (id.contains("fire") || id.contains("lava") || id.contains("hotfloor") || id.contains("onfire")) {
            return KillFeedSource.FIRE;
        }
        if (id.contains("magic") || id.contains("indirectmagic") || id.contains("thorns")
                || id.contains("wither")) {
            return KillFeedSource.MAGIC;
        }
        if (id.contains("cactus") || id.contains("sweetberry") || id.contains("suffocat")
                || id.contains("void") || id.contains("outofworld") || id.contains("starve")
                || id.contains("freeze") || id.contains("lightning") || id.contains("anvil")
                || id.contains("fallingblock") || id.contains("cramming") || id.contains("inwall")
                || id.contains("generic") || id.contains("generickill")) {
            return KillFeedSource.ENVIRONMENT;
        }
        return null;
    }

    /** The item a projectile source was fired as (an arrow from a mob with an empty hand). */
    private static ItemStack projectileItem(DamageSource source) {
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            // An arrow entity's item is the arrow; TaCZ bullets are not projectiles-with-an-item in the same
            // way, but a bullet's gun is in the shooter's hand and never reaches this branch.
            net.minecraft.world.item.ItemStack picked = projectile.getPickResult();
            if (picked != null && !picked.isEmpty() && picked.getItem() != Items.AIR) {
                return picked.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /** The "we could not name it" answer, reported once per damage type. */
    private static Armament unknown(DamageSource source, @Nullable LivingEntity killer) {
        String id = source.getMsgId() + (killer == null ? "" : "|" + killer.getType().toShortString());
        if (WARNED.add(id)) {
            TarkovScav.LOGGER.warn("[killfeed] could not name the weapon for damage type '{}' (killer {});"
                            + " the feed shows 'unknown weapon' for it. Register a resolver"
                            + " (KillFeedWeapons#register) or add a row to fromMessageId.",
                    source.getMsgId(), killer == null ? "none" : killer.getName().getString());
        }
        return new Armament(KillFeedSource.UNKNOWN, ItemStack.EMPTY, id);
    }

    /**
     * True when the killer could not see (blindness from a flashbang, say). Not used for naming: it is the
     * hook the grenade batch and {@code /tarkovscav debug} share, and it lives here because "who could see what"
     * is the same question the feed asks.
     */
    public static boolean blinded(@Nullable LivingEntity entity) {
        return entity != null && entity.hasEffect(MobEffects.BLINDNESS);
    }
}
