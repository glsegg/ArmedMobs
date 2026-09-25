// Scratch: finish delivery 10 - VoicePools (List<SoundEvent>), the egg item, the .build() fix.
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/';
const JAVA = R + 'src/main/java/com/gfl/tarkovscav/';
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, t) => fs.writeFileSync(p, t);
let misses = 0;
const sub = (file, from, to) => {
  const text = read(file);
  if (!text.includes(from)) {
    console.log('MISS in', file.split('/').pop(), ':', from.slice(0, 70));
    misses++;
    return;
  }
  write(file, text.replace(from, to));
};

// --- ModEntities: the supplier needs .build() -----------------------------------------------
sub(JAVA + 'registry/ModEntities.java',
  'event.put(USEC_VILLAGER.get(), troopAttributes(Villager.createAttributes()));',
  'event.put(USEC_VILLAGER.get(), troopAttributes(Villager.createAttributes()).build());');
sub(JAVA + 'registry/ModEntities.java',
  'event.put(ELITE_VILLAGER.get(), troopAttributes(Villager.createAttributes()));',
  'event.put(ELITE_VILLAGER.get(), troopAttributes(Villager.createAttributes()).build());');
sub(JAVA + 'registry/ModEntities.java',
  'event.put(BEAR_PILLAGER.get(), troopAttributes(Pillager.createAttributes()));',
  'event.put(BEAR_PILLAGER.get(), troopAttributes(Pillager.createAttributes()).build());');
sub(JAVA + 'registry/ModEntities.java',
  'event.put(ELITE_PILLAGER.get(), troopAttributes(Pillager.createAttributes()));',
  'event.put(ELITE_PILLAGER.get(), troopAttributes(Pillager.createAttributes()).build());');

// --- MobVoice: say() takes a List<SoundEvent> so the pool can be family-specific -------------
sub(JAVA + 'voice/MobVoice.java',
  'private void say(ServerLevel level, RegistryObject<SoundEvent>[] pool, String kind) {',
  'private void say(ServerLevel level, java.util.List<SoundEvent> pool, String kind) {');
sub(JAVA + 'voice/MobVoice.java', 'if (pool.length == 0) {', 'if (pool.isEmpty()) {');
sub(JAVA + 'voice/MobVoice.java', 'SoundEvent sound = pool[this.random.nextInt(pool.length)].get();',
  'SoundEvent sound = pool.get(this.random.nextInt(pool.size()));');
sub(JAVA + 'voice/MobVoice.java', '"({} line(s) in pool)"', '"({} line(s) in pool)"');
sub(JAVA + 'voice/MobVoice.java', 'pool.length', 'pool.size()');

// --- the two missing files -------------------------------------------------------------------
write(JAVA + 'voice/VoicePools.java', [
  'package com.gfl.tarkovscav.voice;',
  '',
  'import com.gfl.tarkovscav.registry.ModSounds;',
  'import net.minecraft.sounds.SoundEvent;',
  'import net.minecraft.world.entity.Mob;',
  'import net.minecraftforge.registries.RegistryObject;',
  '',
  'import java.util.Arrays;',
  'import java.util.List;',
  '',
  '/**',
  ' * Which voice pool a mob speaks (README 5y) - and the rule that keeps the factions from borrowing each',
  ' * other\'s lines.',
  ' *',
  ' * <h2>The table</h2>',
  ' * <p>Every mob answers {@code voiceFamily()}: {@code usec} for the USEC villager, {@code bear} for the BEAR',
  ' * pillager, {@code elite} for the elite pair, and {@code shared} for everything that existed before (so the',
  ' * original scavs and gunners keep exactly the pool set they always had). A family\'s pool is looked up as',
  ' * {@code <family>_<category>} - e.g. {@code usec_contact} - and <b>falls back to the shared pool when that',
  ' * family has no clips for the category yet</b>. That is what lets delivery 10 wire the structure while the',
  ' * audio itself arrives in delivery 11: today every family falls back, and the day the clips exist the same',
  ' * code starts using them with no change here.</p>',
  ' *',
  ' * <h2>Isolation</h2>',
  ' * <p>A category never crosses families: a USEC mob asks for {@code usec_*}, a BEAR mob for {@code bear_*},',
  ' * and the only shared thing is the fallback. The gate asserts the table per entity type, so a "the BEAR said',
  ' * the USEC line" bug cannot be introduced by editing one call site.</p>',
  ' */',
  'public final class VoicePools {',
  '    /** The families that may own their own clips; the gate walks this list. */',
  '    public static final List<String> FAMILIES = List.of("shared", "usec", "bear", "elite");',
  '',
  '    private VoicePools() {',
  '    }',
  '',
  '    /** The family of a mob; anything that does not override it speaks the shared pools. */',
  '    public static String familyOf(Mob mob) {',
  '        if (mob instanceof com.gfl.tarkovscav.entity.GunnerPillagerEntity pillager) {',
  '            return pillager.voiceFamily();',
  '        }',
  '        if (mob instanceof com.gfl.tarkovscav.entity.GunnerVillagerEntity villager) {',
  '            return villager.voiceFamily();',
  '        }',
  '        return "shared";',
  '    }',
  '',
  '    /**',
  '     * The pool for one category: the family\'s own clips when they exist, the shared pool otherwise.',
  '     *',
  '     * @param shared   the pool a mob without a family uses, and the fallback',
  '     * @param category the category name, e.g. {@code contact}',
  '     */',
  '    public static List<SoundEvent> pool(Mob mob, RegistryObject<SoundEvent>[] shared, String category) {',
  '        String family = familyOf(mob);',
  '        if (!family.equals("shared")) {',
  '            List<SoundEvent> familyClips = ModSounds.pool(family + "_" + category);',
  '            if (!familyClips.isEmpty()) {',
  '                return familyClips;',
  '            }',
  '        }',
  '        return Arrays.stream(shared).map(RegistryObject::get).toList();',
  '    }',
  '',
  '    /** One line for the debug output: the family, and whether it has clips of its own yet. */',
  '    public static String describe(Mob mob) {',
  '        String family = familyOf(mob);',
  '        if (family.equals("shared")) {',
  '            return "voice=shared";',
  '        }',
  '        boolean own = !ModSounds.pool(family + "_contact").isEmpty()',
  '                || !ModSounds.pool(family + "_idle").isEmpty();',
  '        return "voice=" + family + (own ? " (own clips)" : " (falls back to shared)");',
  '    }',
  '}',
  '',
].join('\n'));

write(JAVA + 'item/FactionSpawnEggItem.java', [
  'package com.gfl.tarkovscav.item;',
  '',
  'import net.minecraft.ChatFormatting;',
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
  ' * A spawn egg that explains itself (README 5y): the faction troops have rules the egg\'s colour cannot show',
  ' * (40 health, a rolled armor class, their own voice), so the tooltip says so - the same reasoning as the',
  ' * weapon rack\'s tooltip manual.',
  ' */',
  'public class FactionSpawnEggItem extends ForgeSpawnEggItem {',
  '    /** Set on first use: the tooltip key of this egg, derived from its own registry name. */',
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
  '            this.tooltipKey = "item." + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(this)',
  '                    .getPath() + ".tooltip";',
  '        }',
  '        tooltip.add(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));',
  '    }',
  '}',
  '',
].join('\n'));
console.log('done, misses =', misses);
