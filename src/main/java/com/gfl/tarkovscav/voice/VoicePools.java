package com.gfl.tarkovscav.voice;

import com.gfl.tarkovscav.registry.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.RegistryObject;

import java.util.Arrays;
import java.util.List;

/**
 * Which voice pool a mob speaks (README 5y) - and the rule that keeps the factions from borrowing each
 * other's lines.
 *
 * <h2>The table</h2>
 * <p>Every mob answers {@code voiceFamily()}: {@code usec} for the USEC villager, {@code bear} for the BEAR
 * pillager, {@code elite} for the elite pair, and {@code shared} for everything that existed before (so the
 * original scavs and gunners keep exactly the pool set they always had). A family's pool is looked up as
 * {@code <family>_<category>} - e.g. {@code usec_contact} - and <b>falls back to the shared pool when that
 * family has no clips for the category yet</b>. That is what lets delivery 10 wire the structure while the
 * audio itself arrives in delivery 11: today every family falls back, and the day the clips exist the same
 * code starts using them with no change here.</p>
 *
 * <h2>Isolation</h2>
 * <p>A category never crosses families: a USEC mob asks for {@code usec_*}, a BEAR mob for {@code bear_*},
 * and the only shared thing is the fallback. The gate asserts the table per entity type, so a "the BEAR said
 * the USEC line" bug cannot be introduced by editing one call site.</p>
 */
public final class VoicePools {
    /** The families that may own their own clips; the gate walks this list. */
    public static final List<String> FAMILIES = List.of("shared", "usec", "bear", "elite");

    private VoicePools() {
    }

    /** The family of a mob; anything that does not override it speaks the shared pools. */
    public static String familyOf(Mob mob) {
        if (mob instanceof com.gfl.tarkovscav.entity.GunnerPillagerEntity pillager) {
            return pillager.voiceFamily();
        }
        if (mob instanceof com.gfl.tarkovscav.entity.GunnerVillagerEntity villager) {
            return villager.voiceFamily();
        }
        return "shared";
    }

    /**
     * The family an entity <b>type id</b> speaks, or {@code shared} when it has none (README 5y).
     *
     * <p>This is the name form of {@link #familyOf(Mob)}, for {@code /tarkovscav test sound <entity>} - the
     * command has a name, not a mob. The gate asserts this table against the four classes' own
     * {@code voiceFamily()} literals, so the two can never disagree: a name that stops being a troop is a
     * failing check, not a wrong pool in game.</p>
     */
    public static String familyForEntityId(String entityId) {
        if (entityId == null) {
            return "shared";
        }
        return switch (entityId.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "usec_villager" -> "usec";
            case "bear_pillager" -> "bear";
            case "elite_villager", "elite_pillager" -> "elite";
            default -> "shared";
        };
    }

    /**
     * The pool for one category: the family's own clips when they exist, the shared pool otherwise.
     *
     * @param shared   the pool a mob without a family uses, and the fallback
     * @param category the category name, e.g. {@code contact}
     */
    public static List<SoundEvent> pool(Mob mob, RegistryObject<SoundEvent>[] shared, String category) {
        String family = familyOf(mob);
        if (!family.equals("shared")) {
            List<SoundEvent> familyClips = ModSounds.pool(family + "_" + category);
            if (!familyClips.isEmpty()) {
                return familyClips;
            }
        }
        return Arrays.stream(shared).map(RegistryObject::get).toList();
    }

    /** One line for the debug output: the family, and whether it has clips of its own yet. */
    public static String describe(Mob mob) {
        String family = familyOf(mob);
        String volume = String.format(java.util.Locale.ROOT, "%.2f", volumeFor(mob));
        if (family.equals("shared")) {
            return "voice=shared x" + volume;
        }
        boolean own = !ModSounds.pool(family + "_contact").isEmpty()
                || !ModSounds.pool(family + "_idle").isEmpty();
        return "voice=" + family + (own ? " (own clips, x" : " (falls back to shared, x") + volume + ")";
    }

    /**
     * The volume one of this mob's lines plays at: {@code voice.volume} times its family's multiplier
     * (README 5l). One formula, used by {@code MobVoice} and printed by the commands, so the number the user
     * reads is the number the mix uses.
     */
    public static float volumeFor(Mob mob) {
        return (float) (com.gfl.tarkovscav.Config.VOICE_VOLUME.get()
                * com.gfl.tarkovscav.Config.familyVolume(familyOf(mob)));
    }
}
