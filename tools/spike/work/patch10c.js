// Scratch: the three helper classes + the last wiring for delivery 10.
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

// --- the missing MobVoice sites ---------------------------------------------------------------
sub(JAVA + 'voice/MobVoice.java', 'say(level, ModSounds.CHATTER, "contact");',
  'say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.CHATTER, "contact"), "contact");');
sub(JAVA + 'voice/MobVoice.java', 'say(level, ModSounds.MARK, "mark");',
  'say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.MARK, "mark"), "mark");');

// --- AccuracyProfile: the faction-troop override -----------------------------------------------
sub(JAVA + 'gun/AccuracyProfile.java', '    public static Profile profileFor(Mob mob) {',
  `    public static Profile profileFor(Mob mob) {
        // README 5y: the faction troops bring their own profile (USEC/BEAR -> veteran, elite -> elite), so the
        // "these mobs are better shots" rule lives on the entity and not in a list here.
        Profile troop = com.gfl.tarkovscav.entity.FactionTierProfile.profileFor(mob);
        if (troop != null) {
            return troop;
        }`);

// --- the new classes ---------------------------------------------------------------------------
write(JAVA + 'entity/FactionTierProfile.java', `package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.gun.AccuracyProfile;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * The accuracy profile of the faction troops (README 5y).
 *
 * <p>The request: the USEC/BEAR troops shoot like <b>veterans</b> (0.85) and the elite pair like the
 * <b>elite</b> (0.90), both above the plain scav/villager's rookie 0.75. The profile is a property of the mob
 * here rather than another branch inside {@link AccuracyProfile}, so adding a troop later is one line - and the
 * gate can assert the mapping (and the caps it resolves to) without a client.</p>
 */
public final class FactionTierProfile {
    private FactionTierProfile() {
    }

    /** The profile override for a mob, or null when the normal by-type rule should decide. */
    @Nullable
    public static AccuracyProfile.Profile profileFor(Mob mob) {
        if (mob instanceof EliteVillagerEntity || mob instanceof ElitePillagerEntity) {
            return AccuracyProfile.Profile.ELITE;
        }
        if (mob instanceof UsecVillagerEntity || mob instanceof BearPillagerEntity) {
            return AccuracyProfile.Profile.VETERAN;
        }
        return null;
    }

    /** The id of the override for the debug output, or "by-type". */
    public static String describe(Mob mob) {
        AccuracyProfile.Profile profile = profileFor(mob);
        return profile == null ? "by-type" : "troop:" + profile.id();
    }
}
`);

write(JAVA + 'entity/ArmorClassSpawn.java', `package com.gfl.tarkovscav.entity;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/**
 * Rolls the armor class of a faction troop exactly once (README 5y).
 *
 * <p>{@link EntityJoinLevelEvent} is the one hook every spawn path goes through - natural spawn, spawn egg,
 * {@code /summon}, weapon-rack conversion - and {@code loadedFromDisk()} separates a real spawn from a chunk
 * load, so a troop that was saved and loaded keeps the class it rolled ({@link ArmorClass#rollOnce} refuses to
 * roll twice anyway, which is the belt to this braces).</p>
 */
public final class ArmorClassSpawn {
    private ArmorClassSpawn() {
    }

    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }
        ArmorClass.rollForIfTroop(mob);
    }
}
`);

write(JAVA + 'voice/VoicePools.java', `package com.gfl.tarkovscav.voice;

import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.registry.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Which voice pool a mob speaks (README 5y) - and the rule that keeps the factions from borrowing each other's
 * lines.
 *
 * <h2>The table</h2>
 * <p>Every mob answers {@code voiceFamily()}: {@code usec} for the USEC villager, {@code bear} for the BEAR
 * pillager, {@code elite} for the elite pair, and {@code shared} for everything that existed before (which is
 * how the original scavs and gunners keep the pool set they always had). A family's pool is looked up as
 * {@code <family>_<category>} - e.g. {@code usec_contact} - and <b>falls back to the shared pool when that
 * family has no clips for the category yet</b>. That is what lets delivery 10 wire the structure while the
 * audio itself arrives in delivery 11: today every family falls back, and the day the clips exist the same code
 * starts using them with no change here.</p>
 *
 * <h2>Isolation</h2>
 * <p>The category is never allowed to cross families: a USEC mob asks for {@code usec_*}, a BEAR mob for
 * {@code bear_*}, and the only shared thing is the fallback. The gate asserts that table per entity type, so a
 * "the BEAR said the USEC line" bug cannot be introduced by editing one call site.</p>
 */
public final class VoicePools {
    /** The families that may own their own clips. Order is the assertion order in the gate. */
    public static final List<String> FAMILIES = List.of("shared", "usec", "bear", "elite");

    private VoicePools() {
    }

    /** The family of a mob; anything that does not override it speaks the shared pools. */
    public static String familyOf(Mob mob) {
        return mob instanceof com.gfl.tarkovscav.entity.GunnerPillagerEntity pillager
                ? pillager.voiceFamily()
                : mob instanceof com.gfl.tarkovscav.entity.GunnerVillagerEntity villager
                    ? villager.voiceFamily() : "shared";
    }

    /**
     * The pool for one category: the family's own clips when they exist, the shared pool otherwise.
     *
     * @param shared the pool a mob without a family uses (and the fallback)
     * @param category the category name, e.g. {@code contact}
     */
    public static RegistryObject<SoundEvent>[] pool(Mob mob, RegistryObject<SoundEvent>[] shared,
                                                    String category) {
        String family = familyOf(mob);
        if (family.equals("shared")) {
            return shared;
        }
        List<SoundEvent> familyClips = ModSounds.pool(family + "_" + category);
        if (familyClips.isEmpty()) {
            return shared;
        }
        @SuppressWarnings("unchecked")
        RegistryObject<SoundEvent>[] out = familyClips.stream()
                .map(sound -> (RegistryObject<SoundEvent>) null)
                .toArray(RegistryObject[]::new);
        // ModSounds.pool returns resolved SoundEvents (it is what /tarkovscav test sound plays), so the family
        // clips are wrapped back into one-element holders: the voice code only ever asks for `.get()`.
        for (int i = 0; i < out.length; i++) {
            SoundEvent clip = familyClips.get(i);
            out[i] = RegistryObject.create(clip.getLocation(), net.minecraft.core.registries.Registries
                    .SOUND_EVENT, TarkovScav.MOD_ID);
        }
        return out;
    }

    /** One line for the debug output: the family and whether it has its own clips. */
    public static String describe(Mob mob) {
        String family = familyOf(mob);
        if (family.equals("shared")) {
            return "voice=shared";
        }
        boolean hasOwn = !ModSounds.pool(family + "_contact").isEmpty()
                || !ModSounds.pool(family + "_idle").isEmpty();
        return "voice=" + family + (hasOwn ? " (own clips)" : " (falls back to shared)");
    }
}
`);

write(JAVA + 'item/FactionSpawnEggItem.java', `package com.gfl.tarkovscav.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A spawn egg that explains itself (README 5y): the faction troops have rules that are not obvious from the
 * egg's colour (40 health, a rolled armor class, their own voice), so the tooltip says so - the same reasoning
 * as the weapon rack's tooltip manual.
 */
public class FactionSpawnEggItem extends ForgeSpawnEggItem {
    private final String tooltipKey;

    public FactionSpawnEggItem(java.util.function.Supplier<? extends EntityType<? extends Mob>> type, int background,
                               int highlight, Properties properties) {
        super(type, background, highlight, properties);
        this.tooltipKey = "item." + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(this).getPath() + ".tooltip";
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
`);
console.log('done, misses =', misses);
