package com.gfl.tarkovscav.registry;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The voice clips (see README 5l).
 *
 * <p>The user-supplied mono Vorbis clips - 121 sound events in total: the base clips declared below plus
 * every clip listed in the {@code voice_clips.txt} manifest - grouped into the pools the AI speaks from.
 * Every entry has its file in {@code assets/tarkovscav/sounds/voice/} and a line in {@code sounds.json}
 * with a subtitle; {@code tools/selftest_voice.js} asserts both of those, because a missing file or a
 * missing {@code sounds.json} entry is a <em>silent</em> failure at runtime.</p>
 *
 * <p>The last twelve came from a Bilibili scav-voice compilation: six contact shouts cut from
 * 2:26-3:02 and six idle mutterings from 7:31-8:46, cut and normalised by
 * {@code tools/make_voice_clips.ps1} (README 5l has the parameters and the measured loudness).</p>
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, TarkovScav.MOD_ID);

    // ================================================================= the family voice pools (delivery 11)
    /**
     * Every faction-family clip, read from the {@code voice_clips.txt} manifest at construction. The key is
     * the pool name the AI asks for - {@code <family>_<category>}, e.g. {@code usec_contact} - and the value
     * is that pool's clips in manifest order (README 5y / 5l).
     *
     * <p>Reading a manifest instead of writing ninety constants is what makes "add a voice line" a
     * <b>data</b> change: {@code tools/voice_emit.js} writes the file, this class only registers what it
     * lists. A missing file is not fatal - the hand-declared clips below still play and the log says so,
     * because a silently empty pool is exactly the failure that is invisible without ears.</p>
     */
    public static final Map<String, List<RegistryObject<SoundEvent>>> FAMILY_POOLS = new LinkedHashMap<>();

    /** The families that own clips; {@code test sound <family>} auditions the whole family. */
    public static final List<String> FAMILIES = List.of("usec", "bear", "elite");

    private static final List<RegistryObject<SoundEvent>> MANAGED = new ArrayList<>();

    static {
        List<String> manifest = manifest();
        for (String event : manifest) {
            RegistryObject<SoundEvent> clip = register(event);
            MANAGED.add(clip);
            FAMILY_POOLS.computeIfAbsent(poolOf(event), key -> new ArrayList<>()).add(clip);
        }
        if (!MANAGED.isEmpty()) {
            TarkovScav.LOGGER.info("[voice] {} managed clip(s) in {} pool(s) from the manifest",
                    MANAGED.size(), FAMILY_POOLS.size());
        }
    }

    /** The pool name of a manifest entry: {@code voice.usec_contact_3} -> {@code usec_contact}. */
    static String poolOf(String event) {
        String path = event.startsWith("voice.") ? event.substring("voice.".length()) : event;
        return path.replaceAll("_[0-9]+$", "");
    }

    /** The manifest name of the impact pool (README 5v). */
    public static final String IMPACT_POOL = "grenade_land";

    /**
     * The grenade impact/bounce clip (README 5v), or an empty list when the manifest does not ship one.
     *
     * <p>It is a "pool" of one, which is why it needs no special case in the manifest: the file names it
     * {@code grenade_land}, {@link #poolOf} keeps that as the name, and the only thing this accessor adds is
     * that the grenade code does not have to know the name.</p>
     */
    public static List<SoundEvent> impact() {
        List<RegistryObject<SoundEvent>> clips = FAMILY_POOLS.get(IMPACT_POOL);
        return clips == null ? List.of() : resolve(clips);
    }

    /** The manifest, one sound event per line; {@code #} starts a comment. */
    private static List<String> manifest() {
        List<String> events = new ArrayList<>();
        String resource = "/assets/tarkovscav/voice_clips.txt";
        try (InputStream in = ModSounds.class.getResourceAsStream(resource)) {
            if (in == null) {
                TarkovScav.LOGGER.warn("[voice] {} is missing from the jar: only the hand-declared clips"
                        + " will play", resource);
                return events;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        events.add(trimmed);
                    }
                }
            }
        } catch (java.io.IOException error) {
            TarkovScav.LOGGER.error("[voice] could not read {}", resource, error);
        }
        return events;
    }

    // ---- the merged chatter pool: "I found them" + taunts (the user asked for one pool) ----------
    public static final RegistryObject<SoundEvent> CONTACT_1 = register("voice.contact_1");
    public static final RegistryObject<SoundEvent> CONTACT_2 = register("voice.contact_2");
    public static final RegistryObject<SoundEvent> CONTACT_3 = register("voice.contact_3");
    public static final RegistryObject<SoundEvent> CONTACT_4 = register("voice.contact_4");
    public static final RegistryObject<SoundEvent> CONTACT_5 = register("voice.contact_5");
    public static final RegistryObject<SoundEvent> TAUNT_1 = register("voice.taunt_1");
    public static final RegistryObject<SoundEvent> TAUNT_2 = register("voice.taunt_2");
    public static final RegistryObject<SoundEvent> TAUNT_3 = register("voice.taunt_3");

    // ---- the Bilibili contact batch (2:26-3:02 of the source video) ------------------------------
    public static final RegistryObject<SoundEvent> CONTACT_6 = register("voice.contact_6");
    public static final RegistryObject<SoundEvent> CONTACT_7 = register("voice.contact_7");
    public static final RegistryObject<SoundEvent> CONTACT_8 = register("voice.contact_8");
    public static final RegistryObject<SoundEvent> CONTACT_9 = register("voice.contact_9");
    public static final RegistryObject<SoundEvent> CONTACT_10 = register("voice.contact_10");
    public static final RegistryObject<SoundEvent> CONTACT_11 = register("voice.contact_11");

    // ---- idle muttering (7:31-8:46 of the same video) --------------------------------------------
    public static final RegistryObject<SoundEvent> IDLE_1 = register("voice.idle_1");
    public static final RegistryObject<SoundEvent> IDLE_2 = register("voice.idle_2");
    public static final RegistryObject<SoundEvent> IDLE_3 = register("voice.idle_3");
    public static final RegistryObject<SoundEvent> IDLE_4 = register("voice.idle_4");
    public static final RegistryObject<SoundEvent> IDLE_5 = register("voice.idle_5");
    public static final RegistryObject<SoundEvent> IDLE_6 = register("voice.idle_6");

    // ---- the other pools ------------------------------------------------------------------------
    public static final RegistryObject<SoundEvent> GRENADE_1 = register("voice.grenade_1");
    public static final RegistryObject<SoundEvent> GRENADE_2 = register("voice.grenade_2");
    // The shared grenade pool went from 2 to 5 lines in the 2026-09-24 batch, cut from the user-specified
    // windows of BV1eyZeBYEnn (4:36-4:43, 8:20-8:24, 8:29-8:30): the shared pool is what 武装暴徒 (scav),
    // 武装村民 (gunner villager) and 武装暴徒掠夺者 (gunner pillager) all draw from, so "more grenade shouts"
    // is exactly this array. See README 5l.
    public static final RegistryObject<SoundEvent> GRENADE_3 = register("voice.grenade_3");
    public static final RegistryObject<SoundEvent> GRENADE_4 = register("voice.grenade_4");
    public static final RegistryObject<SoundEvent> GRENADE_5 = register("voice.grenade_5");
    public static final RegistryObject<SoundEvent> MARK_1 = register("voice.mark_1");
    public static final RegistryObject<SoundEvent> DEATH_1 = register("voice.death_1");
    public static final RegistryObject<SoundEvent> DEATH_2 = register("voice.death_2");
    public static final RegistryObject<SoundEvent> DEATH_3 = register("voice.death_3");
    public static final RegistryObject<SoundEvent> DEATH_4 = register("voice.death_4");

    /** Found-you + taunt lines. Used for both "on contact" and "during the firefight" (user's call). */
    public static final RegistryObject<SoundEvent>[] CHATTER = new RegistryObject[]{
            CONTACT_1, CONTACT_2, CONTACT_3, CONTACT_4, CONTACT_5, TAUNT_1, TAUNT_2, TAUNT_3,
            CONTACT_6, CONTACT_7, CONTACT_8, CONTACT_9, CONTACT_10, CONTACT_11};

    /**
     * Idle muttering: the six self-talk clips cut from 7:31-8:46 of the source video. This pool used to
     * borrow the chatter pool because that batch did not exist yet; it has its own now, which is what the
     * user asked for.
     */
    public static final RegistryObject<SoundEvent>[] IDLE = new RegistryObject[]{
            IDLE_1, IDLE_2, IDLE_3, IDLE_4, IDLE_5, IDLE_6};

    public static final RegistryObject<SoundEvent>[] GRENADE = new RegistryObject[]{
            GRENADE_1, GRENADE_2, GRENADE_3, GRENADE_4, GRENADE_5};
    public static final RegistryObject<SoundEvent>[] MARK = new RegistryObject[]{MARK_1};
    public static final RegistryObject<SoundEvent>[] DEATH = new RegistryObject[]{
            DEATH_1, DEATH_2, DEATH_3, DEATH_4};

    private ModSounds() {
    }

    /** Every clip, in the order the report lists them - the {@code test sound all} run uses this. */
    public static final RegistryObject<SoundEvent>[] ALL = all();

    /** The hand-declared clips first, then everything the manifest added (README 5l). */
    private static RegistryObject<SoundEvent>[] all() {
        List<RegistryObject<SoundEvent>> every = new ArrayList<>(List.of(
                CONTACT_1, CONTACT_2, CONTACT_3, CONTACT_4, CONTACT_5, TAUNT_1, TAUNT_2, TAUNT_3,
                CONTACT_6, CONTACT_7, CONTACT_8, CONTACT_9, CONTACT_10, CONTACT_11,
                IDLE_1, IDLE_2, IDLE_3, IDLE_4, IDLE_5, IDLE_6,
                GRENADE_1, GRENADE_2, GRENADE_3, GRENADE_4, GRENADE_5, MARK_1, DEATH_1, DEATH_2, DEATH_3,
                DEATH_4));
        every.addAll(MANAGED);
        return every.toArray(new RegistryObject[0]);
    }

    /**
     * Resolves a pool name for {@code /tarkovscav test sound}: {@code all}, a hand-declared pool
     * ({@code idle|chatter|contact|taunt|grenade|mark|death}), a family pool ({@code usec_contact}), a whole
     * family ({@code usec} - every family clip) or one clip by its short name ({@code contact_1},
     * {@code usec_contact_3}, {@code grenade_land}). An unknown name yields an empty list, and the command
     * says so - never silence.
     */
    public static List<SoundEvent> pool(String name) {
        String key = name == null ? "all" : name.trim().toLowerCase(Locale.ROOT);
        RegistryObject<SoundEvent>[] chosen = switch (key) {
            case "all" -> ALL;
            case "idle" -> IDLE;
            // contact and taunt are the same merged pool (the user's call: one shout pool)
            case "chatter", "contact", "taunt" -> CHATTER;
            case "grenade" -> GRENADE;
            case "mark" -> MARK;
            case "death" -> DEATH;
            default -> null;
        };
        if (chosen != null) {
            return resolve(chosen);
        }
        List<RegistryObject<SoundEvent>> familyPool = FAMILY_POOLS.get(key);
        if (familyPool != null) {
            return resolve(familyPool);
        }
        // A whole family ("usec"): every pool that starts with that family's name.
        List<RegistryObject<SoundEvent>> family = new ArrayList<>();
        for (Map.Entry<String, List<RegistryObject<SoundEvent>>> entry : FAMILY_POOLS.entrySet()) {
            if (entry.getKey().startsWith(key + "_")) {
                family.addAll(entry.getValue());
            }
        }
        if (!family.isEmpty()) {
            return resolve(family);
        }
        for (RegistryObject<SoundEvent> entry : ALL) {
            if (entry.getId().getPath().equals("voice." + key)) {
                return List.of(entry.get());
            }
        }
        return List.of();
    }

    private static List<SoundEvent> resolve(RegistryObject<SoundEvent>[] clips) {
        return java.util.Arrays.stream(clips).map(RegistryObject::get).toList();
    }

    private static List<SoundEvent> resolve(List<RegistryObject<SoundEvent>> clips) {
        return clips.stream().map(RegistryObject::get).toList();
    }

    /** The pool names {@code test sound} understands, for the error message and the README. */
    public static List<String> poolNames() {
        List<String> names = new ArrayList<>(List.of("all", "idle", "chatter", "contact", "taunt", "grenade",
                "mark", "death"));
        names.addAll(FAMILIES);
        names.addAll(FAMILY_POOLS.keySet());
        return names;
    }

    private static RegistryObject<SoundEvent> register(String path) {
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(TarkovScav.MOD_ID, path)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
