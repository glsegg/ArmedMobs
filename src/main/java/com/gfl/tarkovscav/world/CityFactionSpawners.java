package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a city's {@code minecraft:spawner} block entities to the city's faction.
 *
 * <h2>What it does</h2>
 * <p>Walks the city's bounding box chunk by chunk. Chunks that are not loaded are skipped and reported,
 * so the caller can retry later and only records the city as done once the whole box was covered. For
 * every spawner actually inside the box it read-modify-writes the block entity NBT: the vanilla
 * {@code SpawnCount}/{@code SpawnRange}/{@code Delay}/{@code MinSpawnDelay}/{@code MaxSpawnDelay}/
 * {@code RequiredPlayerRange}/{@code MaxNearbyEntities} are preserved, and only {@code SpawnData} and
 * {@code SpawnPotentials} are replaced.</p>
 *
 * <p>The replacement names the city faction's <b>TROOP id and nothing else</b>. The shipped structures
 * keep their mixed default pair (the template is shared, so it cannot carry a per-city faction); the
 * ELITE tier stays a garrison-only event at {@code garrison.eliteLeaderChance}, exactly as it was, so the
 * "a spawner produces the TROOP tier only" contract survives the rewrite.</p>
 *
 * <h2>Why read-modify-write and not a codec call</h2>
 * <p>{@code BaseSpawner#setNextSpawnData} is protected, and the field-free path through
 * {@code SpawnerBlockEntity#load(CompoundTag)} is the same one the world save uses, so the shape written
 * here is exactly the shape the vanilla codec reads - it is the shape {@code tools/spike/CityStructureGen}
 * already bakes into the shipped NBT and {@code tools/selftest_blockentities.js} parses back.</p>
 */
public final class CityFactionSpawners {
    /** The two keys the rewrite touches; everything else in the block entity tag is preserved. */
    public static final String SPAWN_DATA = "SpawnData";
    public static final String SPAWN_POTENTIALS = "SpawnPotentials";

    /** The inner shape: {@code {entity: {id: ...}}} - the 1.19+ SpawnData codec shape. */
    public static final String ENTITY = "entity";
    public static final String ID = "id";
    public static final String WEIGHT = "weight";
    public static final String DATA = "data";

    private CityFactionSpawners() {
    }

    /** {@code rewritten} spawners were written; {@code complete} says every chunk of the box was loaded. */
    public record Result(int rewritten, boolean complete) {
    }

    /** {@code {entity: {id: ...}}} - one SpawnData compound. */
    public static CompoundTag spawnData(String entityId) {
        CompoundTag entity = new CompoundTag();
        entity.putString(ID, entityId);
        CompoundTag data = new CompoundTag();
        data.put(ENTITY, entity);
        return data;
    }

    /** The weighted list, one entry per id, weight 1 - the same shape the generator bakes. */
    public static ListTag spawnPotentials(Faction faction) {
        ListTag list = new ListTag();
        for (String id : CityFactions.spawnerIds(faction)) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(WEIGHT, 1);
            entry.put(DATA, spawnData(id));
            list.add(entry);
        }
        return list;
    }

    /** Sets the faction payload in place, leaving every other spawner key alone. */
    public static void apply(CompoundTag tag, Faction faction) {
        List<String> ids = CityFactions.spawnerIds(faction);
        tag.put(SPAWN_DATA, spawnData(ids.get(0)));
        tag.put(SPAWN_POTENTIALS, spawnPotentials(faction));
    }

    /** Every entity id the spawner payload names, from {@code SpawnData} and every potential. */
    public static List<String> payloadIds(CompoundTag tag) {
        List<String> ids = new ArrayList<>();
        String dataId = entityId(tag.getCompound(SPAWN_DATA));
        if (dataId != null) {
            ids.add(dataId);
        }
        ListTag potentials = tag.getList(SPAWN_POTENTIALS, net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < potentials.size(); i++) {
            String id = entityId(potentials.getCompound(i).getCompound(DATA));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * True when the payload names nothing outside this faction's TROOP ids - the invariant the rewrite
     * writes and the gate re-derives from the shipped NBT. It is checked again immediately before the
     * block entity is touched, so a mixed payload can never reach a city spawner.
     */
    public static boolean isPure(CompoundTag tag, Faction faction) {
        List<String> allowed = CityFactions.spawnerIds(faction);
        List<String> ids = payloadIds(tag);
        return !ids.isEmpty() && allowed.containsAll(ids);
    }

    /** The id inside one {@code {entity: {id: ...}}} compound, or null when it is empty. */
    private static String entityId(CompoundTag spawnData) {
        CompoundTag entity = spawnData.getCompound(ENTITY);
        String id = entity.getString(ID);
        return id.isEmpty() ? null : id;
    }

    /**
     * Rewrites every loaded spawner inside {@code box}, each to the faction {@code factionAt} gives for its
     * own position (i.e. the faction of the building it stands in). Spawners outside the box are never
     * touched, and a position whose chunk is not loaded is simply not visited.
     */
    public static Result rewrite(ServerLevel level, BoundingBox box,
                                 java.util.function.Function<BlockPos, Faction> factionAt) {
        int rewritten = 0;
        boolean complete = true;
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                ChunkAccess raw = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (!(raw instanceof LevelChunk chunk)) {
                    complete = false;
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof SpawnerBlockEntity spawner)) {
                        continue;
                    }
                    if (!box.isInside(entry.getKey())) {
                        continue;
                    }
                    Faction faction = factionAt.apply(entry.getKey());
                    if (faction != null && write(level, entry.getKey(), spawner, faction)) {
                        rewritten++;
                    }
                }
            }
        }
        return new Result(rewritten, complete);
    }

    /** One spawner: preserve its tag, swap the payload, save and tell the clients. */
    private static boolean write(ServerLevel level, BlockPos pos, SpawnerBlockEntity spawner, Faction faction) {
        CompoundTag tag = spawner.saveWithoutMetadata();
        apply(tag, faction);
        if (!isPure(tag, faction)) {
            return false;
        }
        spawner.load(tag);
        spawner.setChanged();
        BlockState state = spawner.getBlockState();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        return true;
    }
}
