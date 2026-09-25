package com.gfl.tarkovscav.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The return anchor of the deployment beacon: for every player, the overworld spot they last deployed
 * from.
 *
 * <p>The brief calls for "a small SavedData keyed by player UUID", and this is exactly that - one file
 * per world ({@code data/tarkovscav_retreat.dat}), a map of UUID to (dimension, block position). It is
 * deliberately NOT kept in a static field: a static would forget the anchor on every server restart, so
 * "deploy home" would silently stop working after a reboot - which is the kind of bug that only shows up
 * in a long-lived world.</p>
 *
 * <p>The dimension is stored next to the position because the beacon only ever writes an OVERWORLD
 * anchor, but the command path ({@code /armedmobs dimension}) can be pointed at any dimension, and a
 * stored anchor that lies about its dimension would teleport the player into the wrong level.</p>
 */
public class RetreatData extends SavedData {
    private static final String DATA_NAME = "tarkovscav_retreat";
    private static final String KEY_SPOTS = "spots";
    private static final String KEY_PLAYER = "player";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    /** One remembered position: which level, and where in it. */
    public record Spot(ResourceLocation dimension, BlockPos pos) {
    }

    private final Map<UUID, Spot> spots = new HashMap<>();

    public static RetreatData get(MinecraftServer server) {
        // Forge's DimensionDataStorage overload: (loader, creator, fileName). Used instead of a
        // SavedData.Factory because that nested type is not present on this Forge version's classpath.
        return server.overworld().getDataStorage()
                .computeIfAbsent(RetreatData::load, RetreatData::new, DATA_NAME);
    }

    public static RetreatData load(CompoundTag tag) {
        RetreatData data = new RetreatData();
        ListTag list = tag.getList(KEY_SPOTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(KEY_PLAYER)) {
                continue;
            }
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString(KEY_DIMENSION));
            if (dimension == null) {
                continue;
            }
            data.spots.put(entry.getUUID(KEY_PLAYER), new Spot(dimension, new BlockPos(
                    entry.getInt(KEY_X), entry.getInt(KEY_Y), entry.getInt(KEY_Z))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Spot> entry : this.spots.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_PLAYER, entry.getKey());
            row.putString(KEY_DIMENSION, entry.getValue().dimension().toString());
            row.putInt(KEY_X, entry.getValue().pos().getX());
            row.putInt(KEY_Y, entry.getValue().pos().getY());
            row.putInt(KEY_Z, entry.getValue().pos().getZ());
            list.add(row);
        }
        tag.put(KEY_SPOTS, list);
        return tag;
    }

    /** Remembers where this player deployed from. Marks the data dirty so it is written on the next save. */
    public void remember(UUID player, ResourceLocation dimension, BlockPos pos) {
        this.spots.put(player, new Spot(dimension, pos.immutable()));
        this.setDirty();
    }

    /** The remembered anchor of this player, or null when there is none (a first-time visitor). */
    public Spot last(UUID player) {
        return this.spots.get(player);
    }

    /** How many players have an anchor - printed by the command so the state is never a guess. */
    public int size() {
        return this.spots.size();
    }
}
