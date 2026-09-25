// Scratch: the mechanical half of delivery 10 (README 5y) - four new entities everywhere they must appear.
// Absolute paths; every replacement fails loudly if its anchor moved.
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/';
const JAVA = R + 'src/main/java/com/gfl/tarkovscav/';
const RES = R + 'src/main/resources/';
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, t) => fs.writeFileSync(p, t);
let misses = 0;
const sub = (file, from, to) => {
  const text = read(file);
  if (!text.includes(from)) {
    console.log('MISS in', file.split('/').pop(), ':', from.slice(0, 60));
    misses++;
    return;
  }
  write(file, text.replace(from, to));
};

const ENTITIES = [
  ['USEC_VILLAGER', 'usec_villager', 'UsecVillagerEntity'],
  ['BEAR_PILLAGER', 'bear_pillager', 'BearPillagerEntity'],
  ['ELITE_VILLAGER', 'elite_villager', 'EliteVillagerEntity'],
  ['ELITE_PILLAGER', 'elite_pillager', 'ElitePillagerEntity'],
];

// ---------------------------------------------------------------- Config -------------------------------
sub(JAVA + 'Config.java',
  '    public static final ForgeConfigSpec.BooleanValue GRENADES_ENABLED;',
  `    // ------------------------------------------------------------------ faction troops (README 5y)
    public static final ForgeConfigSpec.BooleanValue ARMOR_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ARMOR_REDUCTION_PER_CLASS;
    public static final ForgeConfigSpec.IntValue ELITE_MIN_ARMOR_CLASS;
    public static final ForgeConfigSpec.IntValue ELITE_MAX_ARMOR_CLASS;
    public static final ForgeConfigSpec.IntValue USEC_VILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue BEAR_PILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue ELITE_VILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue ELITE_PILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.BooleanValue GRENADES_ENABLED;`);
sub(JAVA + 'Config.java',
  '        // ================================================================= grenades',
  `        // ================================================================= faction troops
        b.comment("The faction troops (README 5y): the USEC villager, the BEAR pillager and their elite",
                "counterparts. Same systems as the mobs they extend; what is new is 40 health, a rolled armor",
                "class and a voice of their own.").push("troops");
        ARMOR_ENABLED = b
                .comment("Master switch for the rolled armor class. false = the class is still rolled and",
                        "stored, but it reduces nothing (and /tarkovscav debug still shows it).")
                .define("armorEnabled", true);
        ARMOR_REDUCTION_PER_CLASS = b
                .comment("Damage reduction per armor class: 0.10 means class 1 takes 10 % less damage and",
                        "class 6 takes 60 % less. The mobs do NOT wear vanilla armor on purpose - vanilla",
                        "armor points would reduce damage a second time, and the total would exceed 60 %.")
                .defineInRange("armorReductionPerClass", 0.10D, 0.0D, 0.25D);
        ELITE_MIN_ARMOR_CLASS = b
                .comment("Lowest armor class a newly spawned troop can roll.")
                .defineInRange("minArmorClass", 1, 1, 6);
        ELITE_MAX_ARMOR_CLASS = b
                .comment("Highest armor class a newly spawned troop can roll.")
                .defineInRange("maxArmorClass", 6, 1, 6);
        USEC_VILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the USEC villager (the plain gunner villager is 4).")
                .defineInRange("usecVillagerWeight", 2, 0, 1000);
        BEAR_PILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the BEAR pillager (the plain gunner pillager is 6).")
                .defineInRange("bearPillagerWeight", 2, 0, 1000);
        ELITE_VILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the elite villager - the rarest of the four on purpose.")
                .defineInRange("eliteVillagerWeight", 1, 0, 1000);
        ELITE_PILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the elite pillager - the rarest of the four on purpose.")
                .defineInRange("elitePillagerWeight", 1, 0, 1000);
        b.pop();

        // ================================================================= grenades`);

// ---------------------------------------------------------------- ModEntities --------------------------
sub(JAVA + 'registry/ModEntities.java',
  '    private ModEntities() {\n    }',
  `    // ------------------------------------------------------------------ faction troops (README 5y)

    /** The USEC villager: a gunner villager that fights and talks like a USEC operator. */
    public static final RegistryObject<EntityType<UsecVillagerEntity>> USEC_VILLAGER =
            ENTITY_TYPES.register("usec_villager",
                    () -> EntityType.Builder.<UsecVillagerEntity>of(UsecVillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("usec_villager").toString()));

    /** The BEAR pillager: the illager half of the same idea. */
    public static final RegistryObject<EntityType<BearPillagerEntity>> BEAR_PILLAGER =
            ENTITY_TYPES.register("bear_pillager",
                    () -> EntityType.Builder.<BearPillagerEntity>of(BearPillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("bear_pillager").toString()));

    /** The elite villager: 40 health, a rolled armor class and the elite accuracy profile. */
    public static final RegistryObject<EntityType<EliteVillagerEntity>> ELITE_VILLAGER =
            ENTITY_TYPES.register("elite_villager",
                    () -> EntityType.Builder.<EliteVillagerEntity>of(EliteVillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("elite_villager").toString()));

    /** The elite pillager: the illager half of the elite pair. */
    public static final RegistryObject<EntityType<ElitePillagerEntity>> ELITE_PILLAGER =
            ENTITY_TYPES.register("elite_pillager",
                    () -> EntityType.Builder.<ElitePillagerEntity>of(ElitePillagerEntity::new,
                                    MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(12)
                            .build(TarkovScav.id("elite_pillager").toString()));

    private ModEntities() {
    }`);
sub(JAVA + 'registry/ModEntities.java',
  '        event.put(SNIPER_VILLAGER.get(), SniperVillagerEntity.createSniperAttributes().build());',
  `        event.put(SNIPER_VILLAGER.get(), SniperVillagerEntity.createSniperAttributes().build());
        // The faction troops: 40 health on top of their family's attribute set (README 5y). Everything else -
        // movement, follow range, armour POINTS (which stay 0, see the class comment on ArmorClass) - comes
        // from the vanilla family.
        event.put(USEC_VILLAGER.get(), troopAttributes(Villager.createAttributes()));
        event.put(ELITE_VILLAGER.get(), troopAttributes(Villager.createAttributes()));
        event.put(BEAR_PILLAGER.get(), troopAttributes(Pillager.createAttributes()));
        event.put(ELITE_PILLAGER.get(), troopAttributes(Pillager.createAttributes()));`);
sub(JAVA + 'registry/ModEntities.java',
  '    public static void registerSpawnPlacements() {',
  `    /** The faction troops' attribute set: their family's, with 40 health (README 5y). */
    private static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder troopAttributes(
            net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder builder) {
        return builder.add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 40.0D);
    }

    public static void registerSpawnPlacements() {`);
sub(JAVA + 'registry/ModEntities.java',
  '        SpawnPlacements.register(SNIPER_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,',
  `        // The faction troops: any light like the other gun mobs, city-gated (README 5y).
        SpawnPlacements.register(USEC_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
        SpawnPlacements.register(ELITE_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ModEntities::anyLightSpawnRules);
        SpawnPlacements.register(BEAR_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);
        SpawnPlacements.register(ELITE_PILLAGER.get(), SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Monster::checkAnyLightMonsterSpawnRules);

        SpawnPlacements.register(SNIPER_VILLAGER.get(), SpawnPlacements.Type.ON_GROUND,`);
sub(JAVA + 'registry/ModEntities.java',
  'import com.gfl.tarkovscav.entity.ScavEntity;',
  `import com.gfl.tarkovscav.entity.BearPillagerEntity;
import com.gfl.tarkovscav.entity.ElitePillagerEntity;
import com.gfl.tarkovscav.entity.EliteVillagerEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.entity.UsecVillagerEntity;`);

// ---------------------------------------------------------------- ModItems -----------------------------
sub(JAVA + 'registry/ModItems.java',
  '    private ModItems() {\n    }',
  `    // ------------------------------------------------------------------ faction troops (README 5y)

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

    private ModItems() {
    }`);

// ---------------------------------------------------------------- creative tab -------------------------
sub(JAVA + 'registry/ModCreativeTabs.java',
  '                        output.accept(ModItems.SNIPER_VILLAGER_SPAWN_EGG.get());',
  `                        output.accept(ModItems.SNIPER_VILLAGER_SPAWN_EGG.get());
                        // The faction troops (README 5y): USEC villager, BEAR pillager, and the elite pair.
                        output.accept(ModItems.USEC_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.BEAR_PILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.ELITE_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.ELITE_PILLAGER_SPAWN_EGG.get());`);

// ---------------------------------------------------------------- renderers (one call + loop) ----------
sub(JAVA + 'client/ClientSetup.java',
  'villagerRenderers(event, ModEntities.GUNNER_VILLAGER, ModEntities.SNIPER_VILLAGER);',
  `villagerRenderers(event, ModEntities.GUNNER_VILLAGER, ModEntities.SNIPER_VILLAGER,
                ModEntities.USEC_VILLAGER, ModEntities.ELITE_VILLAGER);`);
sub(JAVA + 'client/ClientSetup.java',
  'illagerRenderers(event, useGecko, ModEntities.GUNNER_PILLAGER, ModEntities.SNIPER_PILLAGER);',
  `illagerRenderers(event, useGecko, ModEntities.GUNNER_PILLAGER, ModEntities.SNIPER_PILLAGER,
                ModEntities.BEAR_PILLAGER, ModEntities.ELITE_PILLAGER);`);

// ---------------------------------------------------------------- factions -----------------------------
sub(JAVA + 'faction/Faction.java',
  '        if (type == ModEntities.GUNNER_PILLAGER.get() || type == ModEntities.SNIPER_PILLAGER.get()',
  '        if (type == ModEntities.GUNNER_PILLAGER.get() || type == ModEntities.SNIPER_PILLAGER.get()\n'
  + '                || type == ModEntities.BEAR_PILLAGER.get() || type == ModEntities.ELITE_PILLAGER.get()');
sub(JAVA + 'faction/Faction.java',
  '        if (type == ModEntities.GUNNER_VILLAGER.get() || type == ModEntities.SNIPER_VILLAGER.get()',
  '        if (type == ModEntities.GUNNER_VILLAGER.get() || type == ModEntities.SNIPER_VILLAGER.get()\n'
  + '                || type == ModEntities.USEC_VILLAGER.get() || type == ModEntities.ELITE_VILLAGER.get()');
for (const [tag, ids] of [['faction_village.json', ['usec_villager', 'elite_villager']],
  ['faction_illager.json', ['bear_pillager', 'elite_pillager']]]) {
  const file = RES + 'data/tarkovscav/tags/entity_types/' + tag;
  const text = read(file);
  const entries = ids.map((id) => `    "tarkovscav:${id}"`).join(',\n');
  write(file, text.replace(/(\n\s*"tarkovscav:[a-z_]+")(\n\s*\])/, `$1,\n${entries}$2`));
}

// ---------------------------------------------------------------- lang ---------------------------------
for (const [lang, names, tooltip] of [
  ['zh_cn', ['部队村民', '部队掠夺者', '优质村民', '优质掠夺者'],
    '血量 40；生成时随机 1–6 级甲（减免 10%–60%）；说自己的阵营语音'],
  ['en_us', ['USEC Villager', 'BEAR Pillager', 'Elite Villager', 'Elite Pillager'],
    '40 health; rolls armor class 1-6 on spawn (10 %-60 % less damage); speaks its own faction voice'],
]) {
  const file = RES + 'assets/tarkovscav/lang/' + lang + '.json';
  let text = read(file);
  ENTITIES.forEach(([, id], index) => {
    const key = id === 'usec_villager' ? 'usec_villager' : id === 'bear_pillager' ? 'bear_pillager'
      : id === 'elite_villager' ? 'elite_villager' : 'elite_pillager';
    text = text.replace('  "entity.tarkovscav.sniper_villager": "',
      `  "entity.tarkovscav.${key}": "${names[index]}",\n`
      + `  "item.tarkovscav.${key}_spawn_egg": "${names[index]}" ,\n`
      + '  "entity.tarkovscav.sniper_villager": "');
    text = text.replace('  "tarkovscav.name.withProfession"',
      `  "item.tarkovscav.${key}_spawn_egg.tooltip": "${tooltip}",\n  "tarkovscav.name.withProfession"`);
  });
  write(file, text);
}

// ---------------------------------------------------------------- egg models + biome ----------------
for (const [, id] of ENTITIES) {
  write(RES + 'assets/tarkovscav/models/item/' + id + '_spawn_egg.json',
    '{\n  "parent": "minecraft:item/template_spawn_egg"\n}\n');
}
{
  const file = RES + 'data/tarkovscav/forge/biome_modifier/add_scavs.json';
  const json = JSON.parse(read(file));
  const weights = { usec_villager: 2, bear_pillager: 2, elite_villager: 1, elite_pillager: 1 };
  for (const [, id] of ENTITIES) {
    json.spawners.push({ type: 'tarkovscav:' + id, weight: weights[id], minCount: 1, maxCount: 1 });
  }
  write(file, JSON.stringify(json, null, 2) + '\n');
}

console.log('done, misses =', misses);
