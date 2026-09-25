package com.gfl.tarkovscav.loot;

import com.gfl.tarkovscav.TarkovScav;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.Serializer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * "Only when this mod is loaded", as a LOOT condition - the gate the city-chest global loot modifier uses so
 * a world without TaCZ never sees a TaCZ item.
 *
 * <h2>Why this exists instead of {@code forge:mod_loaded}</h2>
 * <p>{@code forge:mod_loaded} is a CRAFTING condition: Forge registers it in the {@code forge:conditions}
 * registry ({@code net.minecraftforge.common.ForgeMod#registerCraftingConditions}), not in the vanilla
 * {@code minecraft:loot_condition_type} registry. A global loot modifier's {@code conditions} array is parsed
 * by {@code IGlobalLootModifier.LOOT_CONDITIONS_CODEC}, which is
 * {@code LootModifierManager.GSON_INSTANCE.fromJson(json, LootItemCondition[].class)} - the vanilla loot
 * Gson, whose adapter resolves the {@code condition} field against
 * {@code BuiltInRegistries.LOOT_CONDITION_TYPE} only (javap of
 * {@code LootItemConditions#createGsonAdapter}). The two Forge entries in that registry are exactly
 * {@code forge:loot_table_id} and {@code forge:can_tool_perform_action} (javap of
 * {@code ForgeMod#registerLootData}), so {@code {"condition": "forge:mod_loaded"}} is an UNKNOWN condition:
 * the whole modifier JSON fails to deserialize and Forge drops it with
 * "Could not decode GlobalLootModifier with json id ..." - a silently dead feature.
 *
 * <p>This class is that same condition, expressed where a loot modifier can actually see it:
 * {@code ModList.get().isLoaded(modid)}, registered under our own id. The JSON says
 * {@code {"condition": "tarkovscav:mod_loaded", "modid": "tacz"}};
 * {@code tools/selftest_loot_modifier.js} asserts both halves - that the file uses this condition and that
 * no GLM JSON in the pack claims the unusable {@code forge:mod_loaded}.</p>
 */
public final class ModLoadedLootCondition implements LootItemCondition {
    /** The condition registry of the vanilla loot system. */
    public static final DeferredRegister<LootItemConditionType> CONDITIONS =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, TarkovScav.MOD_ID);

    public static final RegistryObject<LootItemConditionType> MOD_LOADED =
            CONDITIONS.register("mod_loaded", () -> new LootItemConditionType(new Serializer()));

    /** The JSON field, named exactly like the crafting condition's so a reader is not surprised. */
    public static final String MODID = "modid";

    private final String modId;

    public ModLoadedLootCondition(String modId) {
        this.modId = modId;
    }

    public String modId() {
        return this.modId;
    }

    @Override
    public LootItemConditionType getType() {
        return MOD_LOADED.get();
    }

    /** The actual test: the mod id this condition names is loaded right now. */
    @Override
    public boolean test(LootContext context) {
        return this.modId != null && !this.modId.isBlank() && ModList.get().isLoaded(this.modId);
    }

    /** {@code {"condition": "tarkovscav:mod_loaded", "modid": "..."}} - the vanilla loot serializer shape. */
    public static final class Serializer implements net.minecraft.world.level.storage.loot.Serializer<ModLoadedLootCondition> {
        @Override
        public void serialize(JsonObject json, ModLoadedLootCondition condition, JsonSerializationContext context) {
            json.addProperty(MODID, condition.modId);
        }

        @Override
        public ModLoadedLootCondition deserialize(JsonObject json, JsonDeserializationContext context) {
            return new ModLoadedLootCondition(GsonHelper.getAsString(json, MODID));
        }
    }
}
