package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** File watchers and config screens must invalidate the same rig caches as the in-game command. */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientConfigEvents {
    private ClientConfigEvents() { }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            // Forge may dispatch Reloading on the file watcher's background thread.
            Minecraft.getInstance().execute(RigSupport::invalidateConfig);
        }
    }
}
