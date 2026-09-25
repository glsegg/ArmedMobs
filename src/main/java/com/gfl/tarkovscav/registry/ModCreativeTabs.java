package com.gfl.tarkovscav.registry;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TarkovScav.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.tarkovscav"))
                    .icon(() -> new ItemStack(ModItems.SCAV_SPAWN_EGG.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.SCAV_SPAWN_EGG.get());
                        output.accept(ModItems.GUNNER_PILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.GUNNER_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.SNIPER_PILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.SNIPER_VILLAGER_SPAWN_EGG.get());
                        // The faction troops (README 5y): USEC villager, BEAR pillager, and the elite pair.
                        output.accept(ModItems.USEC_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.BEAR_PILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.ELITE_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.ELITE_PILLAGER_SPAWN_EGG.get());
                        // The five throwables (README 5v), in fuse order: frag, HE, smoke, flash, short flash.
                        output.accept(ModItems.FRAG_GRENADE.get());
                        output.accept(ModItems.HE_GRENADE.get());
                        output.accept(ModItems.SMOKE_GRENADE.get());
                        output.accept(ModItems.FLASH_GRENADE.get());
                        output.accept(ModItems.FLASH_GRENADE_SHORT.get());
                        output.accept(ModBlocks.WEAPON_RACK_ITEM.get());
                        // The deployment beacon: the survival way into the urban wasteland dimension.
                        output.accept(ModItems.DEPLOYMENT_BEACON.get());
                        // The VANT ballistic shield: a craftable, passive front-facing gunfire shield.
                        output.accept(ModItems.VANT_SHIELD.get());
                        // The creative-only twin (README 5n). This tab and /give are the ONLY ways to get it:
                        // there is no recipe, so a survival player can never build the endless variant.
                        output.accept(ModBlocks.CREATIVE_WEAPON_RACK_ITEM.get());
                        // The command system: three faction tools, the signal stick and the signal point.
                        output.accept(ModItems.VILLAGE_COMMAND_TOOL.get());
                        output.accept(ModItems.ILLAGER_COMMAND_TOOL.get());
                        output.accept(ModItems.SCAV_COMMAND_TOOL.get());
                        output.accept(ModItems.SIGNAL_STICK.get());
                        output.accept(ModBlocks.SIGNAL_POINT_ITEM.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
