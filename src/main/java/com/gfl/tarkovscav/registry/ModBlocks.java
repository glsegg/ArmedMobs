package com.gfl.tarkovscav.registry;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.block.WeaponRackBlock;
import com.gfl.tarkovscav.block.WeaponRackBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The weapon rack's block, block entity and item (README 5n), plus its creative-only twin. These are the only
 * blocks this mod adds so far, which is why they are registered separately from the entity-only
 * {@code ModEntities}.
 */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TarkovScav.MOD_ID);
    public static final DeferredRegister<Item> BLOCK_ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TarkovScav.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TarkovScav.MOD_ID);

    public static final RegistryObject<Block> WEAPON_RACK = BLOCKS.register("weapon_rack",
            WeaponRackBlock::new);

    public static final RegistryObject<Item> WEAPON_RACK_ITEM = BLOCK_ITEMS.register("weapon_rack",
            () -> new com.gfl.tarkovscav.block.WeaponRackItem(WEAPON_RACK.get(),
                    new Item.Properties(), false));

    public static final RegistryObject<BlockEntityType<WeaponRackBlockEntity>> WEAPON_RACK_BE =
            BLOCK_ENTITIES.register("weapon_rack",
                    () -> BlockEntityType.Builder.of(WeaponRackBlockEntity::new, WEAPON_RACK.get())
                            .build(null));

    /**
     * Creative-only twin of the rack (README 5n). It is a SEPARATE block on purpose: the endless-template
     * behaviour lives in the block entity type, never in NBT or a blockstate, so a survival player can never
     * craft, copy or place their way into one. It has no recipe and is listed in the creative tab only.
     */
    public static final RegistryObject<Block> CREATIVE_WEAPON_RACK = BLOCKS.register("creative_weapon_rack",
            () -> new WeaponRackBlock(true));

    public static final RegistryObject<Item> CREATIVE_WEAPON_RACK_ITEM =
            BLOCK_ITEMS.register("creative_weapon_rack",
                    () -> new com.gfl.tarkovscav.block.WeaponRackItem(CREATIVE_WEAPON_RACK.get(),
                            new Item.Properties(), true));

    public static final RegistryObject<BlockEntityType<WeaponRackBlockEntity>> CREATIVE_WEAPON_RACK_BE =
            BLOCK_ENTITIES.register("creative_weapon_rack",
                    () -> BlockEntityType.Builder.of(
                                    (pos, state) -> new WeaponRackBlockEntity(pos, state, true),
                                    CREATIVE_WEAPON_RACK.get())
                            .build(null));

    // ------------------------------------------------------------------ the command system

    /**
     * The signal point (the command system): a permanent command mark you can see and break. It is a real
     * block with a block entity because "breaking the block invalidates the mark" needs somewhere to
     * remember WHICH mark the block owns - see {@code command/SignalPointBlockEntity}.
     */
    public static final RegistryObject<Block> SIGNAL_POINT = BLOCKS.register("signal_point",
            com.gfl.tarkovscav.command.SignalPointBlock::new);

    public static final RegistryObject<Item> SIGNAL_POINT_ITEM = BLOCK_ITEMS.register("signal_point",
            () -> new BlockItem(SIGNAL_POINT.get(),
                    new Item.Properties().rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    public static final RegistryObject<BlockEntityType<com.gfl.tarkovscav.command.SignalPointBlockEntity>>
            SIGNAL_POINT_BE = BLOCK_ENTITIES.register("signal_point",
                    () -> BlockEntityType.Builder.of(
                                    com.gfl.tarkovscav.command.SignalPointBlockEntity::new, SIGNAL_POINT.get())
                            .build(null));

    private ModBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
