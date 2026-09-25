// Scratch: final fixes for delivery 10.
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/';
const JAVA = R + 'src/main/java/com/gfl/tarkovscav/';
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, t) => fs.mkdirSync(require('path').dirname(p), { recursive: true }) || fs.writeFileSync(p, t);
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

// 1. the egg item (the package did not exist)
write(JAVA + 'item/FactionSpawnEggItem.java', [
  'package com.gfl.tarkovscav.item;',
  '',
  'import net.minecraft.ChatFormatting;',
  'import net.minecraft.core.registries.BuiltInRegistries;',
  'import net.minecraft.network.chat.Component;',
  'import net.minecraft.world.entity.EntityType;',
  'import net.minecraft.world.entity.Mob;',
  'import net.minecraft.world.item.ItemStack;',
  'import net.minecraft.world.item.TooltipFlag;',
  'import net.minecraft.world.level.Level;',
  'import net.minecraftforge.common.ForgeSpawnEggItem;',
  'import org.jetbrains.annotations.Nullable;',
  '',
  'import java.util.List;',
  'import java.util.function.Supplier;',
  '',
  '/**',
  ' * A spawn egg that explains itself (README 5y): the faction troops have rules the egg colour cannot show',
  ' * (40 health, a rolled armor class, their own voice), so the tooltip says so - the same reasoning as the',
  ' * weapon rack tooltip manual.',
  ' */',
  'public class FactionSpawnEggItem extends ForgeSpawnEggItem {',
  '    @Nullable',
  '    private String tooltipKey;',
  '',
  '    public FactionSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type, int background,',
  '                               int highlight, Properties properties) {',
  '        super(type, background, highlight, properties);',
  '    }',
  '',
  '    @Override',
  '    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,',
  '                                TooltipFlag flag) {',
  '        if (this.tooltipKey == null) {',
  '            this.tooltipKey = "item." + BuiltInRegistries.ITEM.getKey(this).getPath() + ".tooltip";',
  '        }',
  '        tooltip.add(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));',
  '    }',
  '}',
  '',
].join('\n'));

// 2. the last raw-array voice call site
sub(JAVA + 'voice/MobVoice.java', 'say(level, ModSounds.GRENADE, "grenade");',
  'say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.GRENADE, "grenade"), "grenade");');

// 3. the family helpers: pass the members as an explicit array (the varargs wildcard would not infer)
sub(JAVA + 'client/ClientSetup.java',
  `villagerRenderers(event, ModEntities.GUNNER_VILLAGER, ModEntities.SNIPER_VILLAGER,
                ModEntities.USEC_VILLAGER, ModEntities.ELITE_VILLAGER);`,
  `villagerRenderers(event, new RegistryObject[] { ModEntities.GUNNER_VILLAGER,
                ModEntities.SNIPER_VILLAGER, ModEntities.USEC_VILLAGER, ModEntities.ELITE_VILLAGER });`);
sub(JAVA + 'client/ClientSetup.java',
  `illagerRenderers(event, useGecko, ModEntities.GUNNER_PILLAGER, ModEntities.SNIPER_PILLAGER,
                ModEntities.BEAR_PILLAGER, ModEntities.ELITE_PILLAGER);`,
  `illagerRenderers(event, useGecko, new RegistryObject[] { ModEntities.GUNNER_PILLAGER,
                ModEntities.SNIPER_PILLAGER, ModEntities.BEAR_PILLAGER, ModEntities.ELITE_PILLAGER });`);

// 4. the gates that pinned the 2-argument family calls
sub('D:/deepseek/ArmedMobs/tools/selftest_entity_registry.js',
  "check(/villagerRenderers\\(event, ModEntities\\.GUNNER_VILLAGER, ModEntities\\.SNIPER_VILLAGER\\)/\n  .test(registerAllBody), 'with the villager members listed at the one call site');",
  "check(/villagerRenderers\\(event, new RegistryObject\\[\\] \\{[^}]*GUNNER_VILLAGER[^}]*SNIPER_VILLAGER[^}]*USEC_VILLAGER[^}]*ELITE_VILLAGER/.test(registerAllBody),\n  'with the villager members listed at the one call site');");
sub('D:/deepseek/ArmedMobs/tools/selftest_entity_registry.js',
  "check(/illagerRenderers\\(event, useGecko, ModEntities\\.\\w+, ModEntities\\.\\w+\\)/.test(registerAllBody),\n  'with the illager members listed at the one call site');",
  "check(/illagerRenderers\\(event, useGecko, new RegistryObject\\[\\] \\{[^}]*GUNNER_PILLAGER[^}]*SNIPER_PILLAGER[^}]*BEAR_PILLAGER[^}]*ELITE_PILLAGER/.test(registerAllBody),\n  'with the illager members listed at the one call site');");
sub('D:/deepseek/ArmedMobs/tools/selftest_single_pass.js',
  "&& /villagerRenderers\\(event, ModEntities\\.GUNNER_VILLAGER, ModEntities\\.SNIPER_VILLAGER\\)/.test(setup)\n    && /illagerRenderers\\(event, useGecko, ModEntities\\.GUNNER_PILLAGER, ModEntities\\.SNIPER_PILLAGER\\)/\n      .test(setup)",
  "&& /villagerRenderers\\(event, new RegistryObject\\[\\] \\{/.test(setup)\n    && /illagerRenderers\\(event, useGecko, new RegistryObject\\[\\] \\{/.test(setup)");
console.log('done, misses =', misses);
