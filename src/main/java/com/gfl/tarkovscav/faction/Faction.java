package com.gfl.tarkovscav.faction;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.registry.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Who is on whose side (README 5m).
 *
 * <p>Membership is a <b>data-pack tag</b>, not a hard-coded class list, so a modpack can move a mob from
 * one side to the other without a rebuild:</p>
 * <ul>
 *   <li>{@code #tarkovscav:faction_scav} - this mod's scavs ("brothers": they never fight each other
 *       unless somebody starts it);</li>
 *   <li>{@code #tarkovscav:faction_illager} - the vanilla illager types plus this mod's gunner
 *       pillager. The types are listed one by one on purpose: 1.20.1 has no {@code #minecraft:illagers}
 *       tag (the vanilla one is {@code #minecraft:raiders}, which also contains the witch), so a
 *       {@code #minecraft:illagers} reference would silently resolve to nothing;</li>
 *   <li>{@code #tarkovscav:faction_village} - vanilla villagers (and their golems) plus this mod's gunner
 *       villager.</li>
 * </ul>
 *
 * <p>The class-level fallback below only runs when no tag matched, i.e. when the shipped tag files are not
 * loaded at all (bare dev run, or a data pack that removed them). It mirrors those files exactly, so
 * behaviour is never worse than "the tags are missing". It compares entity <em>types</em> - it never
 * instantiates an entity to ask what it is.</p>
 */
public enum Faction {
    SCAV("faction_scav"),
    ILLAGER("faction_illager"),
    VILLAGE("faction_village");

    private final ResourceLocation tagId;

    Faction(String path) {
        this.tagId = TarkovScav.id(path);
    }

    /** The entity-type tag that defines this faction - the thing a data pack edits. */
    public TagKey<EntityType<?>> tag() {
        return TagKey.create(Registries.ENTITY_TYPE, this.tagId);
    }

    public ResourceLocation tagId() {
        return this.tagId;
    }

    /** The faction an entity belongs to, or null when it is not a faction member at all (zombies...). */
    @Nullable
    public static Faction of(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        EntityType<?> type = entity.getType();
        for (Faction faction : values()) {
            if (type.is(faction.tag())) {
                return faction;
            }
        }
        return fallback(type);
    }

    /** Type-based fallback; see the class comment. Mirrors the shipped tag files. */
    @Nullable
    private static Faction fallback(EntityType<?> type) {
        if (type == ModEntities.SCAV.get()) {
            return SCAV;
        }
        if (type == ModEntities.GUNNER_PILLAGER.get() || type == ModEntities.SNIPER_PILLAGER.get()
                || type == ModEntities.BEAR_PILLAGER.get() || type == ModEntities.ELITE_PILLAGER.get()
                || type == EntityType.PILLAGER
                || type == EntityType.VINDICATOR || type == EntityType.EVOKER
                || type == EntityType.ILLUSIONER || type == EntityType.RAVAGER) {
            return ILLAGER;
        }
        if (type == ModEntities.GUNNER_VILLAGER.get() || type == ModEntities.SNIPER_VILLAGER.get()
                || type == ModEntities.USEC_VILLAGER.get() || type == ModEntities.ELITE_VILLAGER.get()
                || type == EntityType.VILLAGER
                || type == EntityType.WANDERING_TRADER || type == EntityType.IRON_GOLEM
                || type == EntityType.SNOW_GOLEM) {
            return VILLAGE;
        }
        return null;
    }

    /** True when both are members of the same faction and neither is a renegade. */
    public static boolean allies(@Nullable Entity a, @Nullable Entity b) {
        Faction first = of(a);
        return first != null && first == of(b) && !Renegade.is(a) && !Renegade.is(b);
    }

    /** True when {@code a} should treat {@code b} as an enemy <em>because of the faction split</em>. This is
     * what the friendly-fire accounting asks; it never sets a target by itself (the target selectors and
     * {@code HurtByTargetGoal} do that - see README 5m).
     */
    public static boolean hostile(@Nullable Entity a, @Nullable Entity b) {
        Faction first = of(a);
        Faction second = of(b);
        if (first == null || second == null) {
            return false;
        }
        // A renegade is everybody's enemy, and sees everybody as one.
        if (Renegade.is(a) || Renegade.is(b)) {
            return true;
        }
        return first != second;
    }

    /**
     * The entity-type tag listing what the <b>village faction defends itself against</b> (README 5m): the
     * vanilla raiders, the undead, the arthropods, creepers and a few nether/end hostiles. A data pack can
     * edit it, and {@code faction.villagersAttackMonsters} switches the whole behaviour off.
     */
    public static final TagKey<EntityType<?>> VILLAGE_HOSTILE = TagKey.create(Registries.ENTITY_TYPE,
            TarkovScav.id("faction_village_hostile"));

    /**
     * True when {@code defender} (a village faction member) should attack {@code candidate} as a monster.
     *
     * <p>Three conditions, in order: the switch is on, the candidate's <em>type</em> is in
     * {@link #VILLAGE_HOSTILE}, and the candidate is not a village ally. The last one is what keeps the
     * village from shooting itself: {@link #allies} is false for a zombie (no faction at all) and for a
     * branded renegade, and true for a fellow villager, an iron golem or a player-sided golem. It is the same
     * helper the goal uses, so the gate can simulate the whole truth table without a game.</p>
     */
    public static boolean villageHostile(@Nullable LivingEntity defender, @Nullable LivingEntity candidate) {
        if (candidate == null || candidate == defender) {
            return false;
        }
        if (!Config.FACTION_ENABLED.get() || !Config.VILLAGER_ATTACK_MONSTERS.get()) {
            return false;
        }
        if (!candidate.getType().is(VILLAGE_HOSTILE)) {
            return false;
        }
        return !allies(defender, candidate);
    }

    /** One-line description for {@code /tarkovscav debug}. */
    public static String describe(@Nullable Entity entity) {
        Faction faction = of(entity);
        String base = faction == null ? "none" : faction.name().toLowerCase(java.util.Locale.ROOT);
        return Renegade.is(entity) ? base + "/RENEGADE" : base;
    }

    /**
     * Only gun-carrying, non-renegade members take part in the intel network and in converging: a plain
     * villager has no business marching to a contact report (README 5m).
     */
    public static boolean isArmedMember(@Nullable LivingEntity entity) {
        return entity instanceof GunUser
                && of(entity) != null
                && !Renegade.is(entity)
                && Config.FACTION_ENABLED.get();
    }

    /** Convenience for the projectile/damage path: the attacker's faction, if it is a member. */
    @Nullable
    public static Faction ofAttacker(@Nullable Entity attacker) {
        return attacker instanceof LivingEntity ? of(attacker) : null;
    }

    /** True for this mod's scavs specifically (the only faction with the betrayal rules, README 5m). */
    public static boolean isScav(@Nullable Entity entity) {
        return entity instanceof ScavEntity;
    }
}
