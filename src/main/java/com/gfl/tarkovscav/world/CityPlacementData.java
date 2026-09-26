package com.gfl.tarkovscav.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/** Runtime city bounds belong to the world save, just like their faction and garrison records. */
public final class CityPlacementData extends SavedData {
    private static final String DATA_NAME = "tarkovscav_city_placements";
    private final List<CityStructures.Placed> placements = new ArrayList<>();

    public static CityPlacementData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(CityPlacementData::load, CityPlacementData::new, DATA_NAME);
    }

    public static CityPlacementData load(CompoundTag tag) {
        CityPlacementData data = new CityPlacementData();
        ListTag rows = tag.getList("placements", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            String name = row.getString("name");
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString("dimension"));
            int[] box = row.getIntArray("box");
            if (name.isEmpty() || dimension == null || box.length != 6
                    || box[0] > box[3] || box[1] > box[4] || box[2] > box[5]
                    || !row.contains("origin", Tag.TAG_LONG)) {
                continue;
            }
            Rotation rotation;
            Mirror mirror;
            try {
                rotation = Rotation.valueOf(row.getString("rotation"));
                mirror = Mirror.valueOf(row.getString("mirror"));
            } catch (IllegalArgumentException invalidTransform) {
                continue;
            }
            data.placements.add(new CityStructures.Placed(name, dimension,
                    new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]),
                    BlockPos.of(row.getLong("origin")), rotation, mirror));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag rows = new ListTag();
        for (CityStructures.Placed placed : this.placements) {
            CompoundTag row = new CompoundTag();
            row.putString("name", placed.name());
            row.putString("dimension", placed.dimension().toString());
            BoundingBox box = placed.box();
            row.putIntArray("box", new int[]{box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()});
            row.putLong("origin", placed.origin().asLong());
            row.putString("rotation", placed.rotation().name());
            row.putString("mirror", placed.mirror().name());
            rows.add(row);
        }
        tag.put("placements", rows);
        return tag;
    }

    public void record(CityStructures.Placed placed) {
        this.placements.removeIf(existing -> existing.name().equalsIgnoreCase(placed.name())
                && existing.dimension().equals(placed.dimension())
                && existing.origin().equals(placed.origin()));
        BoundingBox box = placed.box();
        this.placements.add(new CityStructures.Placed(placed.name(), placed.dimension(),
                new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                placed.origin().immutable(), placed.rotation(), placed.mirror()));
        this.setDirty();
    }

    public List<CityStructures.Placed> placed() {
        return List.copyOf(this.placements);
    }
}
