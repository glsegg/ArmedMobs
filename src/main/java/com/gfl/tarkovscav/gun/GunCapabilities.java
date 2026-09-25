package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Attaches {@link MobAmmoInventory} to every {@link GunUser}.
 *
 * <p>No Forge capability of our own: the provider hands out TaCZ's expected
 * {@code ForgeCapabilities.ITEM_HANDLER}, which is the whole point - TaCZ's ammo lookup and reload
 * code is what reads it.</p>
 */
public final class GunCapabilities {
    private GunCapabilities() {
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof GunUser user) {
            event.addCapability(TarkovScav.id("ammo"), user.ammoInventory());
        }
    }
}
