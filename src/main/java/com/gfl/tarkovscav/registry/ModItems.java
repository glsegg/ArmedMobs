package com.gfl.tarkovscav.registry;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.grenade.GrenadeItem;
import com.gfl.tarkovscav.grenade.GrenadeKind;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TarkovScav.MOD_ID);

    public static final RegistryObject<Item> SCAV_SPAWN_EGG = ITEMS.register("scav_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.SCAV, 0x4B5D3A, 0x23261F, new Item.Properties()));

    public static final RegistryObject<Item> GUNNER_PILLAGER_SPAWN_EGG = ITEMS.register("gunner_pillager_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.GUNNER_PILLAGER, 0x5A5F6B, 0x8C2F2F, new Item.Properties()));

    /** Villager-brown with the same rifle-green accent the scav egg uses. */
    public static final RegistryObject<Item> GUNNER_VILLAGER_SPAWN_EGG = ITEMS.register("gunner_villager_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.GUNNER_VILLAGER, 0x8C6B4A, 0x4B5D3A, new Item.Properties()));

    /** Dark grey with a scope-green accent: the sniper pillager (README 5q). */
    public static final RegistryObject<Item> SNIPER_PILLAGER_SPAWN_EGG = ITEMS.register("sniper_pillager_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.SNIPER_PILLAGER, 0x3A4148, 0x6E8B3D, new Item.Properties()));

    /** Villager-brown with a scope-green accent: the sniper villager (README 5q), the villager half. */
    public static final RegistryObject<Item> SNIPER_VILLAGER_SPAWN_EGG = ITEMS.register("sniper_villager_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.SNIPER_VILLAGER, 0x8C6B4A, 0x6E8B3D, new Item.Properties()));

    // ------------------------------------------------------------------ grenades (README 5v)

    /** Frag: the damage is the fragments. */
    public static final RegistryObject<Item> FRAG_GRENADE = ITEMS.register(GrenadeKind.FRAG.itemPath(),
            () -> new GrenadeItem(GrenadeKind.FRAG, new Item.Properties().stacksTo(16)));

    /** HE: the blast is the damage. */
    public static final RegistryObject<Item> HE_GRENADE = ITEMS.register(GrenadeKind.HE.itemPath(),
            () -> new GrenadeItem(GrenadeKind.HE, new Item.Properties().stacksTo(16)));

    /** Smoke: no damage, a cloud that takes eyes away. */
    public static final RegistryObject<Item> SMOKE_GRENADE = ITEMS.register(GrenadeKind.SMOKE.itemPath(),
            () -> new GrenadeItem(GrenadeKind.SMOKE, new Item.Properties().stacksTo(16)));

    /** Flash: long fuse, long blind. */
    public static final RegistryObject<Item> FLASH_GRENADE = ITEMS.register(GrenadeKind.FLASH.itemPath(),
            () -> new GrenadeItem(GrenadeKind.FLASH, new Item.Properties().stacksTo(16)));

    /** Short-fuse flash: it goes off almost as it lands. */
    public static final RegistryObject<Item> FLASH_GRENADE_SHORT =
            ITEMS.register(GrenadeKind.FLASH_SHORT.itemPath(),
                    () -> new GrenadeItem(GrenadeKind.FLASH_SHORT, new Item.Properties().stacksTo(16)));

    /** The item of a kind - one lookup, used by the entity, the kill feed and the test command. */
    public static Item grenadeItem(GrenadeKind kind) {
        return switch (kind) {
            case FRAG -> FRAG_GRENADE.get();
            case HE -> HE_GRENADE.get();
            case SMOKE -> SMOKE_GRENADE.get();
            case FLASH -> FLASH_GRENADE.get();
            case FLASH_SHORT -> FLASH_GRENADE_SHORT.get();
        };
    }

    // ------------------------------------------------------------------ faction troops (README 5y)

    /** Blue on grey: the USEC villager. */
    public static final RegistryObject<Item> USEC_VILLAGER_SPAWN_EGG = ITEMS.register("usec_villager_spawn_egg",
            () -> new com.gfl.tarkovscav.item.FactionSpawnEggItem(ModEntities.USEC_VILLAGER, 0x3E5C8C, 0x9AA3AD,
                    new Item.Properties()));

    /** Red on black: the BEAR pillager. */
    public static final RegistryObject<Item> BEAR_PILLAGER_SPAWN_EGG = ITEMS.register("bear_pillager_spawn_egg",
            () -> new com.gfl.tarkovscav.item.FactionSpawnEggItem(ModEntities.BEAR_PILLAGER, 0x8C2F2F, 0x23262B,
                    new Item.Properties()));

    /** Gold on charcoal: the elite villager. */
    public static final RegistryObject<Item> ELITE_VILLAGER_SPAWN_EGG = ITEMS.register("elite_villager_spawn_egg",
            () -> new com.gfl.tarkovscav.item.FactionSpawnEggItem(ModEntities.ELITE_VILLAGER, 0xC9A227, 0x2B2B2B,
                    new Item.Properties()));

    /** Gold on black: the elite pillager. */
    public static final RegistryObject<Item> ELITE_PILLAGER_SPAWN_EGG = ITEMS.register("elite_pillager_spawn_egg",
            () -> new com.gfl.tarkovscav.item.FactionSpawnEggItem(ModEntities.ELITE_PILLAGER, 0xC9A227, 0x1C1C1C,
                    new Item.Properties()));

    // ------------------------------------------------------------------ the urban wasteland

    /**
     * The deployment beacon: the primary way into (and back out of) the {@code tarkovscav:urban_wasteland}
     * dimension. A single-use-slot tool - it is never consumed, and every rule it obeys lives in
     * {@link com.gfl.tarkovscav.world.WastelandTravel}.
     */
    public static final RegistryObject<Item> DEPLOYMENT_BEACON = ITEMS.register("deployment_beacon",
            () -> new com.gfl.tarkovscav.item.DeploymentBeaconItem(
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    /**
     * The VANT ballistic shield (README 5zb): held passively in either hand, it blocks 99 % of the
     * bullet damage that arrives from the holder's front until its 500 durability is gone - at which
     * point it shatters. Every rule lives in
     * {@link com.gfl.tarkovscav.combat.VantShieldHandler}; this line is only the stack shape.
     */
    public static final RegistryObject<Item> VANT_SHIELD = ITEMS.register("vant_shield",
            () -> new com.gfl.tarkovscav.item.VantShieldItem(
                    new Item.Properties().stacksTo(1).durability(500)));

    // ------------------------------------------------------------------ the command system

    /**
     * The three faction command tools (the command system). One class, three registrations: the only
     * difference is the {@link com.gfl.tarkovscav.command.CommandFaction} they carry, which is the only
     * thing that can differ - the faction lock is a property of the item, not of a branch somewhere.
     *
     * <p>The registry names are written out as literals on purpose (the recipe/loot-table gate resolves
     * {@code tarkovscav:<name>} against the strings it can see here), and
     * {@code tools/selftest_command_marks.js} asserts each literal equals its faction's
     * {@code CommandFaction#toolPath()} - so the name and the faction still cannot be pointed at
     * different sides.</p>
     */
    public static final RegistryObject<Item> VILLAGE_COMMAND_TOOL = ITEMS.register("village_command_tool",
            () -> new com.gfl.tarkovscav.command.CommandToolItem(
                    com.gfl.tarkovscav.command.CommandFaction.VILLAGE,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    public static final RegistryObject<Item> ILLAGER_COMMAND_TOOL = ITEMS.register("illager_command_tool",
            () -> new com.gfl.tarkovscav.command.CommandToolItem(
                    com.gfl.tarkovscav.command.CommandFaction.ILLAGER,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    public static final RegistryObject<Item> SCAV_COMMAND_TOOL = ITEMS.register("scav_command_tool",
            () -> new com.gfl.tarkovscav.command.CommandToolItem(
                    com.gfl.tarkovscav.command.CommandFaction.SCAV,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    /** The thrown signal stick: a mark that lives command.stickDurationTicks and drops itself on landing. */
    public static final RegistryObject<Item> SIGNAL_STICK = ITEMS.register("signal_stick",
            () -> new com.gfl.tarkovscav.command.SignalStickItem(
                    new Item.Properties().stacksTo(16).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
