package com.gfl.tarkovscav.entity;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/**
 * Rolls the armor class of a faction troop exactly once (README 5y).
 *
 * <p>{@link EntityJoinLevelEvent} is the one hook every spawn path goes through - natural spawn, spawn egg,
 * {@code /summon}, weapon-rack conversion - and {@code loadedFromDisk()} separates a real spawn from a chunk
 * load, so a troop that was saved and loaded keeps the class it rolled ({@link ArmorClass#rollOnce} refuses to
 * roll twice anyway, which is the belt to this braces).</p>
 */
public final class ArmorClassSpawn {
    private ArmorClassSpawn() {
    }

    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }
        ArmorClass.rollForIfTroop(mob);
    }
}
