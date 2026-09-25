package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.killfeed.KillFeedSource;
import com.gfl.tarkovscav.killfeed.KillFeedWeapons;
import com.gfl.tarkovscav.registry.ModItems;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Which grenade was it?" - the one thing a damage source cannot say (README 5v).
 *
 * <p>By the time a grenade kills somebody the grenade entity is long gone, and the damage source only knows
 * "an explosion by this thrower". So {@link GrenadeBlast} records the kind against the victim immediately
 * before the damage is applied and clears it immediately after; the kill feed's resolver then reads it during
 * the death event (which happens inside that same {@code hurt} call).</p>
 *
 * <p><b>This class is also the demonstration of the kill feed's extension point:</b> registering one resolver
 * is all it takes for the feed to print {@code Ge_SiLa [破片手雷] 狙击手掠夺者} instead of "Explosion", and the
 * kill feed itself was not touched.</p>
 */
public final class GrenadeAttribution {
    private static final Map<UUID, GrenadeKind> LAST = new ConcurrentHashMap<>();

    private GrenadeAttribution() {
    }

    public static void remember(LivingEntity victim, GrenadeKind kind) {
        LAST.put(victim.getUUID(), kind);
    }

    public static void forget(LivingEntity victim) {
        LAST.remove(victim.getUUID());
    }

    @Nullable
    public static GrenadeKind of(LivingEntity victim) {
        return LAST.get(victim.getUUID());
    }

    /**
     * The kill feed resolver. Returns null (meaning "not my business") unless the victim was just hit by one of
     * our grenades, so a bow kill or a fall is still named by whoever else answers first.
     */
    @Nullable
    public static KillFeedWeapons.Armament resolve(@Nullable LivingEntity killer, LivingEntity victim,
                                                   DamageSource source) {
        GrenadeKind kind = of(victim);
        if (kind == null) {
            return null;
        }
        ItemStack stack = new ItemStack(ModItems.grenadeItem(kind));
        return new KillFeedWeapons.Armament(KillFeedSource.ITEM, stack, "grenade:" + kind.id());
    }
}
