package com.gfl.tarkovscav.worldgen;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's own worldgen registry entries. Today there is exactly one: the structure placement that makes
 * the urban wasteland dense while leaving every other dimension on the shipped spacing.
 *
 * <p>It lives in its own class rather than in {@code registry/ModEntities} &amp; friends because it
 * registers into a {@code worldgen} registry ({@code minecraft:structure_placement_type}), which is a
 * different kind of thing from an item or a block: it is referenced from a datapack JSON, not from
 * gameplay code.</p>
 */
public final class ModWorldgen {
    /** {@code minecraft:structure_placement} - the registry a structure_set's "type" dispatches on. */
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENT_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, TarkovScav.MOD_ID);

    /**
     * {@code tarkovscav:wasteland_spread}: dense in the wasteland, byte-identical placement everywhere
     * else. See {@link WastelandSpreadPlacement} for the discriminator and the {@code locate} trade-off.
     */
    public static final RegistryObject<StructurePlacementType<WastelandSpreadPlacement>> WASTELAND_SPREAD =
            PLACEMENT_TYPES.register("wasteland_spread",
                    () -> () -> WastelandSpreadPlacement.CODEC.codec());

    private ModWorldgen() {
    }

    public static void register(IEventBus modBus) {
        PLACEMENT_TYPES.register(modBus);
    }
}
