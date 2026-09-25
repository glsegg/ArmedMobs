package com.gfl.tarkovscav.loot;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.world.CityGate;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * The TaCZ extras in a city chest: a few small ammo stacks always, a built gun rarely - and NOTHING outside
 * a city area, so a real mineshaft keeps the vanilla table it always had.
 *
 * <h2>How it is wired</h2>
 * <ol>
 *   <li>registered as a Forge global loot modifier ({@code forge:global_loot_modifiers}) under
 *       {@code tarkovscav:city_chest_extras};</li>
 *   <li>declared in {@code data/forge/loot_modifiers/global_loot_modifiers.json}, whose entry names
 *       {@code data/tarkovscav/loot_modifiers/city_chest_extras.json};</li>
 *   <li>that file's conditions are {@code tarkovscav:mod_loaded}/{@code modid: tacz} plus
 *       {@code forge:loot_table_id}/{@code loot_table_id: minecraft:chests/abandoned_mineshaft}, so the
 *       modifier is never even constructed without TaCZ and never runs for another table;</li>
 *   <li>and {@link #doApply} re-checks {@code ModList} at runtime and scopes by POSITION through
 *       {@link CityGate}, which is the only thing that knows what a city is (structure starts, the
 *       {@code tarkovscav:city} tag, explicit regions and runtime-placed cities - in ANY dimension, so the
 *       wasteland's cities count exactly like the overworld's).</li>
 * </ol>
 *
 * <h2>Why the position scope matters</h2>
 * <p>{@code minecraft:chests/abandoned_mineshaft} is a real vanilla table: a player exploring a mineshaft
 * must find mineshaft loot. The modifier therefore only fires when the chest's
 * {@code LootContextParams.ORIGIN} is inside a city area according to our own gate - which is also the one
 * place the gate is asked about loot, rather than about spawns.</p>
 */
public final class CityChestLootModifier extends LootModifier {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, TarkovScav.MOD_ID);

    public static final RegistryObject<Codec<CityChestLootModifier>> SERIALIZER =
            SERIALIZERS.register("city_chest_extras", () -> RecordCodecBuilder.create(instance ->
                    codecStart(instance).apply(instance, CityChestLootModifier::new)));

    /** Log prefix, so one grep finds every city-chest line. */
    public static final String LOG_PREFIX = "[cityloot]";

    public CityChestLootModifier(net.minecraft.world.level.storage.loot.predicates.LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        // Belt and braces: the JSON condition already refused to build this modifier without TaCZ, and this
        // is the runtime half of the same rule. A world without TaCZ must see no error and no empty pool.
        if (!ModList.get().isLoaded(TarkovScav.TACZ_MOD_ID)) {
            return loot;
        }
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (level == null || origin == null) {
            return loot;
        }
        BlockPos pos = BlockPos.containing(origin);
        if (!CityGate.isCityArea(level, pos)) {
            return loot;
        }

        List<ItemStack> extras = CityChestExtras.roll(context.getRandom(), "city_chest");
        if (extras.isEmpty()) {
            return loot;
        }
        loot.addAll(extras);
        TarkovScav.LOGGER.info("{} city chest at {} ({}) received {}", LOG_PREFIX, pos,
                level.dimension().location(), CityChestExtras.describe(extras));
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return SERIALIZER.get();
    }
}
