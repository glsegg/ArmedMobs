package com.gfl.tarkovscav.faction;

import com.gfl.tarkovscav.Config;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The damage hook for {@link Renegade} (README 5m).
 *
 * <p>{@link LivingHurtEvent} is used rather than an override of {@code Mob#hurt} inside each of the three
 * entity classes: the faction rules are <b>one</b> rule, so they live in one place, apply to a mob this mod
 * has never heard of (any future faction member, or anything a data pack adds to a tag), and cannot drift
 * between three copies. The event also carries the already-resolved attacker, which is exactly what the
 * rule needs.</p>
 *
 * <p>Nothing here cancels or modifies the damage: the hit lands exactly as vanilla resolved it. All this
 * does is <em>count</em> it and, at the thresholds, make the victim hit back or brand the attacker.</p>
 */
public final class FactionEvents {
    private FactionEvents() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!Config.FACTION_ENABLED.get()) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof net.minecraft.world.entity.Mob attacker)) {
            return;
        }
        if (!(event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        Renegade.noteFriendlyFire(level, attacker, event.getEntity());
    }
}
