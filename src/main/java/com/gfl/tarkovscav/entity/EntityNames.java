package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity names that are safe to put on a screen (README 5x).
 *
 * <h2>The bug this exists for</h2>
 * <p>Vanilla {@code Villager#getTypeName()} builds its name by <b>concatenating the profession onto the entity's
 * own translation key</b>: {@code entity.tarkovscav.gunner_villager.weaponsmith}. For vanilla villagers those
 * keys exist; for ours they never did, so the kill feed (and the name plate, and anything else that asks for a
 * name) printed the raw key -
 * <code>entity.tarkovscav.gunner_villager.weaponsmith [BAS-P 微型冲锋枪] 叛徒 entity.tarkovscav.gunner_villager.none</code>.
 * Two things were wrong: our villagers had no variant-aware name at all, and nothing on the way to the screen
 * refused to show an untranslated key.</p>
 *
 * <h2>The two halves of the fix</h2>
 * <ol>
 *   <li>{@link #villagerTypeName} composes a human name: <b>our</b> entity key as the base
 *       ({@code entity.tarkovscav.gunner_villager} = 武装村民 / {@code sniper_villager} = 狙击手村民) plus the
 *       <b>vanilla</b> profession key as a suffix ({@code entity.minecraft.villager.weaponsmith} = 武器匠),
 *       giving 武装村民（武器匠）. No profession - or a modded one without a name - gives the bare base name,
 *       so 30+ profession/level combinations need no new lang entries from us.</li>
 *   <li>{@link #safeName} is the floor: if a rendered name still <b>looks like a key</b> (it starts with a
 *       translation prefix or contains our namespace), the raw text is replaced by the entity type's readable
 *       path and the type is WARNed about once. A raw key is never drawn, on any path, ever.</li>
 * </ol>
 */
public final class EntityNames {
    /** Entity types already warned about, so a broken name is reported once and not once per frame. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    /** The prefixes that mean "this was never translated". */
    private static final String[] KEY_PREFIXES = { "entity.", "item.", "block.", "translation{", "effect.",
        "subtitles." };

    private EntityNames() {
    }

    /**
     * The name of one of our villagers: our entity name, plus the vanilla profession in brackets when there is
     * one. Used by {@code GunnerVillagerEntity#getTypeName()} and therefore inherited by the sniper villager.
     */
    public static Component villagerTypeName(EntityType<?> type, @Nullable VillagerData data) {
        Component base = Component.translatable(type.getDescriptionId());
        VillagerProfession profession = data == null ? null : data.getProfession();
        String professionKey = professionKey(profession);
        if (professionKey == null) {
            return base;
        }
        Component professionName = Component.translatable(professionKey);
        // A modded profession has no vanilla name key: fall back to the bare base name rather than showing
        // "entity.minecraft.villager.something".
        if (looksUntranslated(professionName.getString())) {
            return base;
        }
        return Component.translatable("tarkovscav.name.withProfession", base, professionName);
    }

    /**
     * The vanilla name key of a profession, or null for "no useful suffix".
     *
     * <p>A switch of <b>literal</b> keys rather than {@code "entity.minecraft.villager." + path}: the asset gate
     * resolves every key the code mentions, and a key built by concatenation is invisible to it (it caught
     * exactly that). A profession added by another mod therefore gets no suffix instead of a raw key, which is
     * the behaviour we want anyway.</p>
     */
    @Nullable
    private static String professionKey(@Nullable VillagerProfession profession) {
        if (profession == null) {
            return null;
        }
        if (profession == VillagerProfession.ARMORER) {
            return "entity.minecraft.villager.armorer";
        }
        if (profession == VillagerProfession.BUTCHER) {
            return "entity.minecraft.villager.butcher";
        }
        if (profession == VillagerProfession.CARTOGRAPHER) {
            return "entity.minecraft.villager.cartographer";
        }
        if (profession == VillagerProfession.CLERIC) {
            return "entity.minecraft.villager.cleric";
        }
        if (profession == VillagerProfession.FARMER) {
            return "entity.minecraft.villager.farmer";
        }
        if (profession == VillagerProfession.FISHERMAN) {
            return "entity.minecraft.villager.fisherman";
        }
        if (profession == VillagerProfession.FLETCHER) {
            return "entity.minecraft.villager.fletcher";
        }
        if (profession == VillagerProfession.LEATHERWORKER) {
            return "entity.minecraft.villager.leatherworker";
        }
        if (profession == VillagerProfession.LIBRARIAN) {
            return "entity.minecraft.villager.librarian";
        }
        if (profession == VillagerProfession.MASON) {
            return "entity.minecraft.villager.mason";
        }
        if (profession == VillagerProfession.NITWIT) {
            return "entity.minecraft.villager.nitwit";
        }
        if (profession == VillagerProfession.SHEPHERD) {
            return "entity.minecraft.villager.shepherd";
        }
        if (profession == VillagerProfession.TOOLSMITH) {
            return "entity.minecraft.villager.toolsmith";
        }
        if (profession == VillagerProfession.WEAPONSMITH) {
            return "entity.minecraft.villager.weaponsmith";
        }
        return null;
    }

    /** True when a rendered string is really an untranslated key. */
    public static boolean looksUntranslated(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String trimmed = text.trim();
        for (String prefix : KEY_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return trimmed.contains(TarkovScav.MOD_ID + ".");
    }

    /**
     * The name to print for an entity. Returns the rendered name, or - when that is an untranslated key - the
     * entity type's readable path (e.g. {@code gunner_villager}), plus a WARN once per entity type so an
     * operator hears about it instead of seeing it.
     */
    public static String safeName(Entity entity) {
        return safe(entity, entity.getDisplayName().getString());
    }

    /** {@link #safeName} for a name that was rendered elsewhere (the kill feed renders names on the server). */
    public static String safe(Entity entity, String rendered) {
        if (!looksUntranslated(rendered)) {
            return rendered;
        }
        String type = EntityType.getKey(entity.getType()).toString();
        String fallback = EntityType.getKey(entity.getType()).getPath().replace('_', ' ');
        if (WARNED.add(type)) {
            TarkovScav.LOGGER.warn("[names] {} rendered as the untranslated key '{}' (a server without a"
                            + " language file cannot resolve it, and a missing key never resolves anywhere)."
                            + " Showing '{}' instead. Fix the key or the name source; this WARN is once per"
                            + " entity type.",
                    type, rendered.trim(), fallback);
        }
        return fallback;
    }

    /** True when the given name key exists in this build's language file - used by the gate. */
    public static boolean looksLikeNameKey(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).startsWith("entity.");
    }
}
