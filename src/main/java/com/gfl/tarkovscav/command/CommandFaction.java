package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * The three faction-locked sides of the command system, one per existing entity-type tag.
 *
 * <h2>Strict, and never cross-faction</h2>
 * <p>The user's rule: "the two handle different line-ups and must not reach across to the other"
 * (两个负责不同的阵容单位 不能跨级去管其他阵容的). So a command tool is bound to exactly one tag and
 * {@link #affects(Entity)} is the <b>only</b> membership test in the whole feature: a village tool can
 * never touch a pillager or a scav, whether or not that pillager is hostile to it, and whoever spawned
 * it is irrelevant.</p>
 *
 * <p>The tags are the ones the mod already ships in
 * {@code data/tarkovscav/tags/entity_types/}, so a data pack that adds a unit to a faction gets it
 * commanded for free - the same extension point the faction AI uses.</p>
 */
public enum CommandFaction {
    /** {@code #tarkovscav:faction_village}: villagers, iron/snow golems and the armed villagers. */
    VILLAGE("village", "faction_village", "village_command_tool"),
    /** {@code #tarkovscav:faction_illager}: vanilla illagers, the ravager and the armed pillagers. */
    ILLAGER("illager", "faction_illager", "illager_command_tool"),
    /** {@code #tarkovscav:faction_scav}: the scav. */
    SCAV("scav", "faction_scav", "scav_command_tool");

    private final String id;
    private final TagKey<EntityType<?>> tag;
    private final String toolPath;

    CommandFaction(String id, String tagPath, String toolPath) {
        this.id = id;
        this.tag = TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
                TarkovScav.id(tagPath));
        this.toolPath = toolPath;
    }

    /** The short id used in messages and in the {@code command} config comments: village/illager/scav. */
    public String id() {
        return this.id;
    }

    /** The entity-type tag this faction commands. */
    public TagKey<EntityType<?>> tag() {
        return this.tag;
    }

    /** The registry path of this faction's command tool item. */
    public String toolPath() {
        return this.toolPath;
    }

    /** The only membership test the command system uses. Never null, never cross-faction. */
    public boolean affects(Entity entity) {
        return entity != null && entity.getType().is(this.tag);
    }

    /** Case-insensitive lookup by short id, null when the name is unknown. */
    public static CommandFaction byId(String id) {
        for (CommandFaction faction : values()) {
            if (faction.id.equalsIgnoreCase(id)) {
                return faction;
            }
        }
        return null;
    }

    /** The resource id of the tag, for messages and the reference document. */
    public ResourceLocation tagId() {
        return this.tag.location();
    }
}
