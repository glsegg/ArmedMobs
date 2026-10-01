package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** Refresh server-side weapon filters when Forge's config watcher reloads the common file. */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GunConfigEvents {
    private GunConfigEvents() { }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            // Both caches synchronize their mutation; the watcher may dispatch on a background thread.
            GunPool.invalidate();
            com.gfl.tarkovscav.loot.CityChestExtras.invalidate();
        }
    }
}
