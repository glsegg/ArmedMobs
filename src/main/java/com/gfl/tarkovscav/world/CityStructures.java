package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The runtime city-structure pool: the NBTs the user edits in a world and hands back to the mod.
 *
 * <h2>The workflow this exists for</h2>
 * <pre>
 *   edit the building in game, save it with a structure block as "mycity"
 *     -&gt; &lt;world&gt;/generated/minecraft/structures/mycity.nbt
 *   /tarkovscav city import mycity      copies it to &lt;gameDir&gt;/tarkovscav/city/mycity.nbt,
 *                                       validates it and registers it in this pool
 *   /tarkovscav city place mycity       places it and remembers the box it occupies
 *   /tarkovscav city add mycity 128     makes a spawn area of it (or use the box from place)
 * </pre>
 *
 * <h2>What can and cannot see these files - read this before blaming the mod</h2>
 * <p>The vanilla worldgen pipeline ({@code worldgen/structure/*.json}, {@code structure_set/*.json},
 * {@code template_pool/*.json}) is resolved by the <b>resource manager</b> when the server starts, and
 * the structure lookups the spawn gate uses read the structures that worldgen registered. A file
 * dropped into {@code tarkovscav/city/} at runtime therefore <b>cannot</b> become a worldgen structure:
 * it is not in a datapack, has no structure json, no structure set and no biome modifier, so natural
 * generation will never place it and {@code /place structure} cannot name it. Making one generate
 * naturally requires shipping it as a datapack and restarting.</p>
 *
 * <p>What this pool <b>does</b> give you - and the reason it is worth having:</p>
 * <ol>
 *   <li>the loaded template can be placed on demand with {@code /tarkovscav city place}, which is the
 *       exact geometry the user edited;</li>
 *   <li>every placed instance is remembered as a box, and {@link CityGate} treats a position inside
 *       one as a city area - so mobs spawn in the building that was just placed, with the same
 *       {@code spawn.scavCityOnly} gating as the shipped {@code tarkovscav:city_small};</li>
 *   <li>the shipped jar structure keeps working exactly as before: this pool is additive, and
 *       {@code Config.CITY_STRUCTURE_IDS} / {@code CITY_STRUCTURE_TAGS} keep their meaning.</li>
 * </ol>
 */
public final class CityStructures {
    /** Where imported templates live: {@code <gameDir>/tarkovscav/city}. */
    private static final String DIRECTORY = "tarkovscav/city";

    /** A structure must have at least one block and one palette entry to be worth placing. */
    private static final int MIN_BLOCKS = 1;

    /** Refuse anything a single command must not try to place in one tick. */
    private static final int MAX_BLOCKS = 250_000;

    /** Refuse a template whose bounding box is absurd (a mis-saved structure fills the world). */
    private static final int MAX_AXIS = 512;

    /** Above this a warning is logged - it is allowed, it is just slow. */
    private static final int WARN_AXIS = 256;

    /** name (lower case) -&gt; loaded template. Insertion ordered so listings are stable. */
    private static final Map<String, Loaded> POOL = new LinkedHashMap<>();

    private CityStructures() {
    }

    /** A validated template from the runtime pool. {@code template} is null until a level is known. */
    public record Loaded(String name, ResourceLocation id, Path file, @Nullable StructureTemplate template,
                         Vec3i size, int paletteEntries, int blocks, int entities) {
        /** The one-line report the commands print. */
        public String describe() {
            return id + " size=" + size.getX() + "x" + size.getY() + "x" + size.getZ()
                    + " palette=" + paletteEntries + " blocks=" + blocks + " entities=" + entities;
        }
    }

    /** One placed instance: which world, which box, and where it came from. */
    public record Placed(String name, ResourceLocation dimension, BoundingBox box, BlockPos origin,
                         Rotation rotation, Mirror mirror) {
    }

    /** A validation failure with a message meant for the user. */
    public static final class InvalidStructure extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidStructure(String message) {
            super(message);
        }
    }

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve(DIRECTORY);
    }

    /** The structure-block output of a world: {@code <world>/generated/minecraft/structures}. */
    public static Path worldStructureFile(ServerLevel level, String name) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.GENERATED_DIR)
                .resolve("minecraft").resolve("structures").resolve(name + ".nbt");
    }

    /**
     * Reads {@code <world>/generated/minecraft/structures/<name>.nbt}, validates it, copies it into
     * {@link #directory()} and registers it.
     *
     * @return the loaded entry, with the final structure id
     * @throws InvalidStructure when the file is missing, empty, unreadable or implausible
     */
    public static Loaded importFromWorld(ServerLevel level, String name) throws InvalidStructure {
        Path source = worldStructureFile(level, name);
        if (!Files.isRegularFile(source)) {
            throw new InvalidStructure("no structure file at " + source
                    + " - in game, put a structure block in the corner of the building, set it to SAVE,"
                    + " name it '" + name + "' and press SAVE, then run this again");
        }
        Loaded loaded = read(source, name);
        Path target = directory().resolve(name.toLowerCase(Locale.ROOT) + ".nbt");
        try {
            Files.createDirectories(directory());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            throw new InvalidStructure("could not copy the structure to " + target + ": " + failed.getMessage());
        }
        Loaded copied = new Loaded(loaded.name(), loaded.id(), target, null, loaded.size(),
                loaded.paletteEntries(), loaded.blocks(), loaded.entities());
        POOL.put(copied.name().toLowerCase(Locale.ROOT), copied);
        TarkovScav.LOGGER.info("[city] imported {} from {} -> {}", copied.id(), source, target);
        return copied;
    }

    /**
     * Re-scans {@link #directory()} and registers every {@code *.nbt} in it.
     *
     * @return the names now in the pool, in scan order
     */
    public static List<String> reloadFromDisk() {
        POOL.clear();
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            TarkovScav.LOGGER.info("[city] no {} directory yet - nothing to load", dir);
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList()) {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - ".nbt".length());
                try {
                    Loaded loaded = read(file, name);
                    POOL.put(name.toLowerCase(Locale.ROOT), loaded);
                    names.add(loaded.name());
                    TarkovScav.LOGGER.info("[city] loaded {}", loaded.describe());
                } catch (InvalidStructure invalid) {
                    // A bad file must not stop the rest of the pool from loading, but it must be loud.
                    TarkovScav.LOGGER.warn("[city] skipping {}: {}", file, invalid.getMessage());
                }
            }
        } catch (IOException failed) {
            TarkovScav.LOGGER.warn("[city] could not list {}: {}", dir, failed.getMessage());
        }
        return names;
    }

    /** Reads and validates one file without registering it. */
    public static Loaded read(Path file, String name) throws InvalidStructure {
        if (!Files.isRegularFile(file)) {
            throw new InvalidStructure("no such file: " + file);
        }
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException failed) {
            throw new InvalidStructure("could not stat " + file + ": " + failed.getMessage());
        }
        if (bytes <= 0L) {
            throw new InvalidStructure(file + " is empty (0 bytes) - the structure block never wrote it");
        }

        CompoundTag tag;
        try (InputStream in = Files.newInputStream(file)) {
            tag = NbtIo.readCompressed(in);
        } catch (IOException | RuntimeException failed) {
            throw new InvalidStructure(file + " is not a readable compressed NBT structure: "
                    + failed.getMessage());
        }
        if (tag == null || tag.isEmpty()) {
            throw new InvalidStructure(file + " holds no NBT data");
        }

        ListTag size = tag.getList("size", 3);
        if (size.size() != 3) {
            throw new InvalidStructure(file + " has no size tag - is it really a structure file?");
        }
        int sx = size.getInt(0);
        int sy = size.getInt(1);
        int sz = size.getInt(2);
        for (int axis : new int[]{sx, sy, sz}) {
            if (axis <= 0) {
                throw new InvalidStructure(file + " has a non-positive size (" + sx + "x" + sy + "x" + sz + ")");
            }
            if (axis > MAX_AXIS) {
                throw new InvalidStructure(file + " is " + sx + "x" + sy + "x" + sz
                        + ", which exceeds the " + MAX_AXIS + "-block limit per axis");
            }
            if (axis > WARN_AXIS) {
                TarkovScav.LOGGER.warn("[city] {} is {} blocks on one axis - placing it will be slow",
                        file, axis);
            }
        }

        ListTag palette = tag.getList("palette", 10);
        ListTag blocks = tag.getList("blocks", 10);
        ListTag entities = tag.getList("entities", 10);
        if (palette.isEmpty()) {
            throw new InvalidStructure(file + " has an empty palette - there is nothing to place");
        }
        if (blocks.size() < MIN_BLOCKS) {
            throw new InvalidStructure(file + " contains no blocks (palette of " + palette.size()
                    + ", but 0 block entries) - the structure block saved an empty selection");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new InvalidStructure(file + " contains " + blocks.size() + " blocks, above the "
                    + MAX_BLOCKS + "-block limit");
        }
        // Every block entry must reference a palette index that exists, or StructureTemplate#load throws
        // deep inside vanilla with an unhelpful message.
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            int state = block.getInt("state");
            if (state < 0 || state >= palette.size()) {
                throw new InvalidStructure(file + " block " + i + " references palette entry " + state
                        + " but the palette has " + palette.size() + " entries");
            }
        }

        StructureTemplate template = null; // loaded lazily in loadTemplate(), which needs a level
        return new Loaded(name, id(name), file, template, new Vec3i(sx, sy, sz),
                palette.size(), blocks.size(), entities.size());
    }

    /** The resource id a runtime structure is registered under, e.g. {@code tarkovscav:city/mycity}. */
    public static ResourceLocation id(String name) {
        return TarkovScav.id("city/" + name.toLowerCase(Locale.ROOT));
    }

    /** Loads the template for real (needs a level for the block registry). */
    public static Loaded loadTemplate(ServerLevel level, String name) throws InvalidStructure {
        Loaded loaded = POOL.get(name.toLowerCase(Locale.ROOT));
        if (loaded == null) {
            throw new InvalidStructure("'" + name + "' is not in the runtime pool; use"
                    + " /tarkovscav city import " + name + " or /tarkovscav city reload first"
                    + " (pool: " + names() + ")");
        }
        HolderLookup<Block> blocks = level.holderLookup(Registries.BLOCK);
        StructureTemplate template = new StructureTemplate();
        try (InputStream in = Files.newInputStream(loaded.file())) {
            CompoundTag tag = NbtIo.readCompressed(in);
            if (tag == null) {
                throw new InvalidStructure(loaded.file() + " holds no NBT data");
            }
            template.load(blocks, tag);
        } catch (IOException | RuntimeException failed) {
            throw new InvalidStructure("could not load " + loaded.file() + ": " + failed.getMessage());
        }
        Loaded withTemplate = new Loaded(loaded.name(), loaded.id(), loaded.file(), template, loaded.size(),
                loaded.paletteEntries(), loaded.blocks(), loaded.entities());
        POOL.put(loaded.name().toLowerCase(Locale.ROOT), withTemplate);
        return withTemplate;
    }

    /**
     * Places a runtime structure and remembers the box for the spawn gate.
     *
     * @return the box the instance occupies
     */
    public static BoundingBox place(ServerLevel level, String name, BlockPos pos, Rotation rotation,
                                    Mirror mirror) throws InvalidStructure {
        Loaded loaded = loadTemplate(level, name);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(mirror)
                .setIgnoreEntities(false)
                .setKnownShape(false);
        boolean placed = loaded.template() != null
                && loaded.template().placeInWorld(level, pos, pos, settings, level.getRandom(), 2);
        if (!placed) {
            throw new InvalidStructure("vanilla refused to place " + loaded.id() + " at " + pos.toShortString());
        }
        BoundingBox box = loaded.template().getBoundingBox(settings, pos);
        record(level, loaded.name(), pos, rotation, mirror, box);
        TarkovScav.LOGGER.info("[city] placed {} at {} (rotation {}, mirror {}), box {}",
                loaded.id(), pos.toShortString(), rotation, mirror, box);
        return box;
    }

    /** Registers an already-placed box (also used when a box is known from somewhere else). */
    public static void record(ServerLevel level, String name, BlockPos origin, Rotation rotation,
                              Mirror mirror, BoundingBox box) {
        CityPlacementData.get(level.getServer()).record(
                new Placed(name, level.dimension().location(), box, origin.immutable(), rotation, mirror));
    }

    /** The gate probe: is this position inside a placed runtime city instance? */
    @Nullable
    public static Placed instanceAt(ServerLevel level, BlockPos pos) {
        ResourceLocation dimension = level.dimension().location();
        for (Placed placed : placed(level.getServer())) {
            if (placed.dimension().equals(dimension) && placed.box().isInside(pos)) {
                return placed;
            }
        }
        return null;
    }

    /** Every instance recorded in this world, including earlier sessions. */
    public static List<Placed> placed(MinecraftServer server) {
        return CityPlacementData.get(server).placed();
    }

    /** The names currently in the pool, in registration order. */
    public static List<String> names() {
        List<String> names = new ArrayList<>(POOL.size());
        for (Loaded loaded : POOL.values()) {
            names.add(loaded.name());
        }
        return names;
    }

    @Nullable
    public static Loaded get(String name) {
        return POOL.get(name.toLowerCase(Locale.ROOT));
    }

    /** The structure files shipped inside the jar, for the {@code city structures} listing. */
    public static List<String> shippedIds() {
        List<String> ids = new ArrayList<>();
        for (String name : SHIPPED) {
            ids.add("tarkovscav:" + name + "  (jar resource data/tarkovscav/structures/" + name + ".nbt)");
        }
        return ids;
    }

    /**
     * The presets generated from {@code tools/city-layout*.json}. Kept in one place so the listing, the
     * config default and {@code tools/selftest_city_import.js} name the same four.
     */
    public static final List<String> SHIPPED = List.of("city_small", "city_a", "city_b", "city_c");
}
