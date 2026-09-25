package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.entity.EntityNames;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.entity.ScavTier;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.gun.CombatTactics;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunBrain;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.registry.ModEntities;
import com.gfl.tarkovscav.registry.ModSounds;
import com.gfl.tarkovscav.world.CityFactions;
import com.gfl.tarkovscav.world.CityGate;
import com.gfl.tarkovscav.world.CityRegion;
import com.gfl.tarkovscav.world.CityStructures;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /tarkovscav ...} - the operator's console, and the harness the head-less RCON tests drive.
 *
 * <ul>
 *   <li>{@code city add|remove|list|test} - the city spawn areas and the real {@link CityGate} verdict.</li>
 *   <li>{@code spawn <type> [pos]} - spawn a mob <em>through</em> the gate; refuses outside a city and
 *       says why. That is the accept/reject evidence.</li>
 *   <li>{@code test fight [distance]} - a complete, reproducible firefight: one scav and one practice
 *       dummy (a no-AI zombie tagged {@code tarkovscav_dummy}) at a given distance. This is how the gun
 *       AI is verified without a human player in the world.</li>
 *   <li>{@code test watch [seconds] [distance]} - the same fight, but after a fixed window it asserts,
 *       in the server log, that the mob moved and fired. This is the anti-stall regression gate.</li>
 *   <li>{@code test stall [seconds] [distance]} - the same fight with every shot refused on purpose,
 *       which asserts that the anti-stall watchdog breaks the stand-off.</li>
 *   <li>{@code cover} - print the cover spots the nearest gun mob can currently see, with their
 *       hidden/open verdict, straight out of {@link CombatTactics}.</li>
 *   <li>{@code gunpool [tier]} - the TaCZ guns each tier may be issued.</li>
 *   <li>{@code debug} - the full state machine report of every gun mob within 32 blocks.</li>
 *   <li>{@code dimension [name]} - teleport the operator into {@code tarkovscav:urban_wasteland} (the
 *       default) or into any named dimension, through the <em>same</em> safe-landing helper the
 *       deployment beacon item uses.</li>
 * </ul>
 */
public final class ModCommands {
    private ModCommands() {
    }

    /**
     * Registers the whole tree under both root literals. {@code /armedmobs} is the display name
     * ("Armed Mobs"), while {@code /tarkovscav} is kept because the mod id has to stay
     * {@code tarkovscav} for existing worlds and configs - both spellings reach the same tree.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The two supported spellings side by side, so a rename cannot update one and forget the other.
        dispatcher.register(tree("tarkovscav"));
        dispatcher.register(tree("armedmobs"));
    }

    /** Builds the operator tree for one root literal: {@code /tarkovscav ...} or {@code /armedmobs ...}. */
    private static LiteralArgumentBuilder<CommandSourceStack> tree(String rootLiteral) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(rootLiteral)
                .requires(source -> source.hasPermission(2));

        root.then(city());
        root.then(garrison());
        root.then(marks());
        root.then(spawn());
        root.then(test());
        root.then(cover());
        root.then(gunPool());
        root.then(debug());
        root.then(dimension());

        return root;
    }

    // ------------------------------------------------------------------ /tarkovscav dimension

    /**
     * {@code /armedmobs dimension [name]} - the admin path into the urban wasteland.
     *
     * <p>With no argument it deploys the operator into {@code tarkovscav:urban_wasteland}; with a name it
     * moves them to that dimension (a bare name gets the {@code tarkovscav} namespace). Both branches go
     * through {@link com.gfl.tarkovscav.world.WastelandTravel}, the same helper the deployment beacon
     * item calls, so "where the command puts you" and "where the beacon puts you" cannot differ: the
     * motion-blocking heightmap decides the Y, an arrival platform is built when there is nothing to
     * stand on, and the landing coordinates are printed.</p>
     *
     * <p>Refuses cleanly - and says which case it was - for a source with no player behind it (a command
     * block or the console has no body to move) and for a dimension this server does not have.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> dimension() {
        return Commands.literal("dimension")
                .executes(context -> dimension(context, null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                                builder.suggest(level.dimension().location().toString());
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> dimension(context,
                                StringArgumentType.getString(context, "name"))));
    }

    private static int dimension(CommandContext<CommandSourceStack> context, String name) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(com.gfl.tarkovscav.world.WastelandTravel.refusalMessage(
                            com.gfl.tarkovscav.world.WastelandTravel.Refusal.NOT_A_PLAYER)
                    .copy().withStyle(ChatFormatting.RED));
            return 0;
        }
        ResourceLocation target = name == null
                ? com.gfl.tarkovscav.world.WastelandTravel.WASTELAND
                : (name.contains(":") ? ResourceLocation.tryParse(name) : TarkovScav.id(name));
        if (target == null || com.gfl.tarkovscav.world.WastelandTravel.resolveLevel(source.getServer(),
                target) == null) {
            source.sendFailure(Component.translatable("tarkovscav.command.dimension.unknown",
                    name == null ? "-" : name).withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean wasteland = com.gfl.tarkovscav.world.WastelandTravel.WASTELAND.equals(target);
        // The wasteland keeps the operator's own grid square (and remembers where they came from); any
        // other named dimension goes to the same X/Z of that dimension.
        com.gfl.tarkovscav.world.WastelandTravel.Arrival arrival = wasteland
                ? com.gfl.tarkovscav.world.WastelandTravel.teleportInto(player)
                : com.gfl.tarkovscav.world.WastelandTravel.teleportTo(player, target,
                        player.getBlockX(), player.getBlockZ(), true);
        if (arrival == null) {
            source.sendFailure(Component.translatable("tarkovscav.command.dimension.unknown",
                    target.toString()).withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.dimension.arrived",
                arrival.dimension().toString(), arrival.pos().toShortString()).withStyle(ChatFormatting.GREEN),
                true);
        if (arrival.fallback()) {
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.dimension.fallback")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ /tarkovscav city

    private static LiteralArgumentBuilder<CommandSourceStack> city() {
        return Commands.literal("city")
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> cityAdd(context, 64, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 512))
                                        .executes(context -> cityAdd(context,
                                                IntegerArgumentType.getInteger(context, "radius"), null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> cityAdd(context,
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        BlockPosArgument.getBlockPos(context, "pos")))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ModCommands::cityRemove)))
                .then(Commands.literal("list").executes(ModCommands::cityList))
                .then(Commands.literal("import")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ModCommands::cityImport)))
                .then(Commands.literal("reload").executes(ModCommands::cityReload))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> cityPlace(context, null, 0, "none"))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> cityPlace(context,
                                                BlockPosArgument.getBlockPos(context, "pos"), 0, "none"))
                                        .then(Commands.argument("rotation", IntegerArgumentType.integer(0, 270))
                                                .executes(context -> cityPlace(context,
                                                        BlockPosArgument.getBlockPos(context, "pos"),
                                                        IntegerArgumentType.getInteger(context, "rotation"), "none"))
                                                .then(Commands.argument("mirror", StringArgumentType.word())
                                                        .executes(context -> cityPlace(context,
                                                                BlockPosArgument.getBlockPos(context, "pos"),
                                                                IntegerArgumentType.getInteger(context, "rotation"),
                                                                StringArgumentType.getString(context, "mirror"))))))))
                .then(Commands.literal("structures").executes(ModCommands::cityStructures))
                .then(Commands.literal("district")
                        .executes(context -> cityDistrict(context, null, 3))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(context -> cityDistrict(context,
                                        LongArgumentType.getLong(context, "seed"), 3))
                                .then(Commands.argument("grid", IntegerArgumentType.integer(1, 7))
                                        .executes(context -> cityDistrict(context,
                                                LongArgumentType.getLong(context, "seed"),
                                                IntegerArgumentType.getInteger(context, "grid"))))))
                .then(Commands.literal("test")
                        .executes(context -> cityTest(context, null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> cityTest(context,
                                        BlockPosArgument.getBlockPos(context, "pos")))))
                .then(Commands.literal("faction")
                        .executes(ModCommands::cityFactionList)
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .executes(ModCommands::cityFactionByKey)))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .executes(context -> cityFactionAt(context, null))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> cityFactionAt(context,
                                                BlockPosArgument.getBlockPos(context, "pos"))))));
    }

    // ------------------------------------------------------------------ /tarkovscav city faction

    /**
     * {@code /armedmobs city faction} - the per-city and per-BUILDING faction ledger.
     *
     * <p>Three forms, one node:</p>
     * <ul>
     *   <li>{@code city faction} - list every decided city and every one of its buildings, with the faction
     *       in force and whether that building's spawners have been rewritten;</li>
     *   <li>{@code city faction <cityKey> <village|illager|auto>} - override a city by its ledger key (append
     *       {@code #<buildingId>} to override one building). The override persists and is applied on the next
     *       trigger, because only the key is available here - this form cannot see the city's box;</li>
     *   <li>{@code city faction <village|illager|auto> [pos]} - resolve the city at the caller (or at
     *       {@code pos}), override it, and apply it IMMEDIATELY: the loaded chunks' spawners are rewritten
     *       and a garrison that has not been placed yet is placed now. This is the form that works from the
     *       server console through {@code /execute positioned ...}.</li>
     * </ul>
     */
    private static int cityFactionList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        for (String line : com.gfl.tarkovscav.world.CityGarrison.describeFactions(source.getServer())) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    /** The by-key form: {@code city faction <cityKey>[#<buildingId>] <faction>}. */
    private static int cityFactionByKey(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String rawKey = StringArgumentType.getString(context, "key");
        String rawFaction = StringArgumentType.getString(context, "faction");
        Faction override = factionOf(source, rawFaction);
        if (override == null && !com.gfl.tarkovscav.world.CityFactions.isAuto(rawFaction)) {
            return 0;
        }
        int hash = rawKey.indexOf('#');
        String cityKey = hash >= 0 ? rawKey.substring(0, hash) : rawKey;
        String buildingId = hash >= 0 ? rawKey.substring(hash + 1) : null;
        ResourceLocation dimension = source.getLevel().dimension().location();
        com.gfl.tarkovscav.world.GarrisonData data =
                com.gfl.tarkovscav.world.GarrisonData.get(source.getServer());
        if (buildingId != null) {
            if (!data.overrideBuilding(dimension, cityKey, buildingId, override)) {
                source.sendFailure(Component.literal("No building '" + buildingId + "' of city '"
                        + cityKey + "' in " + dimension + " (see /armedmobs city faction)"));
                return 0;
            }
        } else {
            if (data.city(dimension, cityKey) == null) {
                source.sendFailure(Component.literal("No decided city '" + cityKey + "' in " + dimension
                        + ". A city is decided when a player first comes near it; use"
                        + " /armedmobs city faction <faction> <pos> to decide one from the console."));
                return 0;
            }
            data.overrideCity(dimension, cityKey, override);
        }
        String where = buildingId != null ? cityKey + "#" + buildingId : cityKey;
        source.sendSuccess(() -> Component.literal("City faction override for " + dimension + " " + where
                + " set to " + (override == null ? "auto (the recorded roll)" : CityFactions.name(override))
                + "; the spawners are rewritten on the next garrison trigger.").withStyle(ChatFormatting.GREEN),
                true);
        for (com.gfl.tarkovscav.world.GarrisonData.BuildingRow row
                : data.buildingsOf(dimension, cityKey)) {
            source.sendSuccess(() -> Component.literal("  " + row.describe()), false);
        }
        return 1;
    }

    /** The by-position form: resolve the city here (or at {@code pos}), override it and apply it now. */
    private static int cityFactionAt(CommandContext<CommandSourceStack> context, @Nullable BlockPos pos) {
        CommandSourceStack source = context.getSource();
        String rawFaction = StringArgumentType.getString(context, "faction");
        Faction override = factionOf(source, rawFaction);
        if (override == null && !com.gfl.tarkovscav.world.CityFactions.isAuto(rawFaction)) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos where = pos != null ? pos : BlockPos.containing(source.getPosition());
        int radius = (int) Math.round(Config.GARRISON_TRIGGER_RADIUS.get());
        List<CityGate.Area> near = CityGate.citiesNear(level, where, radius);
        if (near.isEmpty()) {
            source.sendFailure(Component.literal("No city within " + radius + " blocks of "
                    + where.toShortString() + " in " + level.dimension().location()
                    + " (only loaded chunks are inspected)"));
            return 0;
        }
        CityGate.Area city = near.stream()
                .min(Comparator.comparingDouble((CityGate.Area area) -> CityGate.distanceToBox(area.box(), where)))
                .orElse(near.get(0));
        ResourceLocation dimension = level.dimension().location();
        com.gfl.tarkovscav.world.GarrisonData data =
                com.gfl.tarkovscav.world.GarrisonData.get(source.getServer());
        // Decide the city first, so an "auto" override falls back to a recorded roll rather than to nothing.
        com.gfl.tarkovscav.world.CityGarrison.factionFor(level, city, data);
        data.overrideCity(dimension, city.key(), override);
        com.gfl.tarkovscav.world.CityGarrison.applyCity(level, city, data, level.getGameTime());
        source.sendSuccess(() -> Component.literal("City " + city.name() + " (" + city.key() + ") faction "
                + (override == null ? "auto (the recorded roll)" : CityFactions.name(override))
                + " applied now.").withStyle(ChatFormatting.GREEN), true);
        source.sendSuccess(() -> Component.literal("  dominant=" + CityFactions.name(
                com.gfl.tarkovscav.world.CityGarrison.buildingFaction(data, dimension, city.key(),
                        com.gfl.tarkovscav.world.CityGarrison.WHOLE_CITY))
                + " buildings: " + com.gfl.tarkovscav.world.CityGarrison.buildingSummary(data, city, dimension)),
                false);
        return 1;
    }

    /** Parses a faction word, sending the failure message itself; null means "not a faction word". */
    @Nullable
    private static Faction factionOf(CommandSourceStack source, String raw) {
        Faction faction = com.gfl.tarkovscav.world.CityFactions.parse(raw);
        if (faction == null && !com.gfl.tarkovscav.world.CityFactions.isAuto(raw)) {
            source.sendFailure(Component.literal("Unknown faction '" + raw
                    + "' - expected village, illager or auto"));
        }
        return faction;
    }

    // ------------------------------------------------------------------ /tarkovscav garrison

    /**
     * {@code /armedmobs garrison} - the one-time city garrison's state, in one place.
     *
     * <p>Prints the config actually in force (including whether the squad count is pinned or coming from
     * the size formula, and the formula itself) and then the ledger: every city that has already placed
     * its garrison, which dimension it is in, how much was placed and at which tick. This is the
     * observable half of "a city spawns once and never again" - the other half is the
     * {@code [garrison] <city> -> N squads / M units} line written at placement time.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> garrison() {
        return Commands.literal("garrison").executes(context -> {
            CommandSourceStack source = context.getSource();
            for (String line : com.gfl.tarkovscav.world.CityGarrison.describe(source.getServer())) {
                source.sendSuccess(() -> Component.literal("  " + line), false);
            }
            return 1;
        });
    }

    // ------------------------------------------------------------------ /tarkovscav marks

    /**
     * {@code /armedmobs marks} - the command system's mark list, in the dimension the caller is in.
     *
     * <ul>
     *   <li>{@code marks} - every live mark: letter, position, source and remaining time;</li>
     *   <li>{@code marks remove <letter>} - delete one mark (and with it every order pointing at it);</li>
     *   <li>{@code marks clear} - empty this dimension (the debugging path).</li>
     * </ul>
     *
     * <p>The target is the <b>source's own level</b>, so the list a player sees is the list they can act
     * on; a mark in another dimension is listed by the debug dump, not here.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> marks() {
        return Commands.literal("marks")
                .executes(ModCommands::marksList)
                .then(Commands.literal("remove")
                        .then(Commands.argument("letter", StringArgumentType.word())
                                .executes(ModCommands::marksRemove)))
                .then(Commands.literal("clear")
                        .executes(ModCommands::marksClear));
    }

    private static int marksList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        long now = level.getGameTime();
        source.sendSuccess(() -> Component.literal("  marks in " + level.dimension().location()
                + " (cap " + Config.COMMAND_MAX_MARKS.get() + ", radius "
                + Config.COMMAND_RADIUS.get().intValue() + "):"), false);
        for (String line : com.gfl.tarkovscav.command.CommandMarks.listLines(level, now)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    private static int marksRemove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String letter = com.gfl.tarkovscav.command.CommandMark.sanitiseLetter(
                StringArgumentType.getString(context, "letter"));
        boolean removed = com.gfl.tarkovscav.command.CommandMarks.remove(level, letter);
        if (!removed) {
            source.sendFailure(Component.literal("No mark '" + letter + "' in "
                    + level.dimension().location()));
            return 0;
        }
        // Orders naming it are dropped by their own validity check on the next tick: an order carries the
        // mark's letter AND position, and MarkData no longer answers for either. Saying so makes the
        // consequence explicit rather than something the player has to discover.
        source.sendSuccess(() -> Component.literal("Removed mark " + letter
                + "; any order pointing at it is dropped."), true);
        return 1;
    }

    private static int marksClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int removed = com.gfl.tarkovscav.command.CommandMarks.clear(level);
        source.sendSuccess(() -> Component.literal("Cleared " + removed + " mark(s) in "
                + level.dimension().location()), true);
        return removed == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ /tarkovscav city import | reload | place

    /**
     * {@code /tarkovscav city import <name>}: takes the structure-block output of the world the player
     * is standing in ({@code <world>/generated/minecraft/structures/<name>.nbt}), validates it, copies
     * it to {@code <gameDir>/tarkovscav/city/<name>.nbt} and registers it in the runtime pool.
     *
     * <p>Everything that can make an import useless is checked and reported here rather than at place
     * time: the file exists, it is compressed NBT, it has a size/palette/blocks, the size is sane, and
     * every block entry references a palette entry that exists.</p>
     */
    private static int cityImport(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String name = StringArgumentType.getString(context, "name");
        try {
            CityStructures.Loaded loaded = CityStructures.importFromWorld(level, name);
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.imported",
                            loaded.name(), loaded.id().toString()).withStyle(ChatFormatting.GREEN), true);
            source.sendSuccess(() -> Component.literal("  " + loaded.describe())
                    .withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("  " + loaded.file())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.import.next",
                    loaded.name()).withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (CityStructures.InvalidStructure invalid) {
            TarkovScav.LOGGER.warn("[city] import of '{}' failed: {}", name, invalid.getMessage());
            source.sendFailure(Component.translatable("tarkovscav.command.city.import.failed",
                    name, invalid.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** {@code /tarkovscav city reload}: re-scan {@code tarkovscav/city/*.nbt} and register everything. */
    private static int cityReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> names = CityStructures.reloadFromDisk();
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.reloaded",
                names.size(), CityStructures.directory().toString()).withStyle(ChatFormatting.GREEN), true);
        for (String name : names) {
            CityStructures.Loaded loaded = CityStructures.get(name);
            source.sendSuccess(() -> Component.literal("  " + (loaded == null ? name : loaded.describe()))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.reload.empty")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return names.size();
    }

    /** {@code /tarkovscav city place <name> [pos] [rotation] [mirror]}: place one instance in place. */
    private static int cityPlace(CommandContext<CommandSourceStack> context, BlockPos pos, int rotation,
                                 String mirrorName) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String name = StringArgumentType.getString(context, "name");
        BlockPos target = pos != null ? pos : BlockPos.containing(source.getPosition());
        Rotation rotationValue = switch (((rotation % 360) + 360) % 360) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        Mirror mirror = switch (mirrorName == null ? "none" : mirrorName.toLowerCase(java.util.Locale.ROOT)) {
            case "left_right", "leftright", "x" -> Mirror.LEFT_RIGHT;
            case "front_back", "frontback", "z" -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
        try {
            net.minecraft.world.level.levelgen.structure.BoundingBox box =
                    CityStructures.place(level, name, target, rotationValue, mirror);
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.placed",
                    name, target.toShortString(), CityStructures.id(name).toString())
                    .withStyle(ChatFormatting.GREEN), true);
            source.sendSuccess(() -> Component.literal("  box " + box + " rotation=" + rotationValue
                    + " mirror=" + mirror).withStyle(ChatFormatting.GRAY), false);
            // The placed instance is a city area now: show the gate agreeing, using the real gate.
            CityGate.Result gate = CityGate.test(level, target);
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.place.gate",
                    gate.allowed() ? "ACCEPT" : "REJECT", gate.reason())
                    .withStyle(gate.allowed() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.place.next", name)
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (CityStructures.InvalidStructure invalid) {
            TarkovScav.LOGGER.warn("[city] place of '{}' failed: {}", name, invalid.getMessage());
            source.sendFailure(Component.translatable("tarkovscav.command.city.place.failed",
                    name, invalid.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /** {@code /tarkovscav city structures}: what the gate can actually see, jar pool and runtime pool. */
    private static int cityStructures(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.structures.jar")
                .withStyle(ChatFormatting.AQUA), false);
        for (String id : CityStructures.shippedIds()) {
            source.sendSuccess(() -> Component.literal("  " + id).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.structures.runtime",
                CityStructures.directory().toString()).withStyle(ChatFormatting.AQUA), false);
        List<String> names = CityStructures.names();
        for (String name : names) {
            CityStructures.Loaded loaded = CityStructures.get(name);
            source.sendSuccess(() -> Component.literal("  " + (loaded == null ? name : loaded.describe()))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.structures.none")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.structures.placed",
                CityStructures.placed().size()).withStyle(ChatFormatting.AQUA), false);
        for (CityStructures.Placed placed : CityStructures.placed()) {
            source.sendSuccess(() -> Component.literal("  " + placed.name() + " " + placed.dimension()
                    + " " + placed.box()).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.structures.worldgen")
                .withStyle(ChatFormatting.YELLOW), false);
        return names.size();
    }

    /**
     * Marks a box as a city area and writes it to {@code spawn.cityRegions} on disk.
     *
     * <p>Deliberately works from any command source, not just a player: with no {@code pos} the box
     * is centred on the source (a player standing in their city, a command block, or the console at
     * 0,0,0), and an explicit {@code pos} makes the whole thing scriptable over RCON. That matters
     * because this is <em>the</em> path for a city that was pasted or built by hand - see
     * {@link CityGate} for why a structure placed with a command cannot be seen by the structure
     * lookup.</p>
     */
    private static int cityAdd(CommandContext<CommandSourceStack> context, int radius, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        BlockPos centre = pos != null ? pos : BlockPos.containing(source.getPosition());
        CityRegion region = CityRegion.cube(name, source.getLevel().dimension().location(), centre, radius);

        List<String> entries = Config.cityRegionEntries();
        entries.removeIf(entry -> {
            CityRegion parsed = CityRegion.parse(entry);
            return parsed != null && parsed.name().equalsIgnoreCase(name);
        });
        entries.add(region.toString());
        Config.CITY_REGIONS.set(entries);
        Config.SPEC.save();

        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.added", name, region.toString())
                .withStyle(ChatFormatting.GREEN), true);
        TarkovScav.LOGGER.info("[city] region '{}' added: {} (centre {}, radius {})", name, region, centre, radius);
        return 1;
    }

    private static int cityRemove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        List<String> entries = Config.cityRegionEntries();
        boolean removed = entries.removeIf(entry -> {
            CityRegion parsed = CityRegion.parse(entry);
            return parsed != null && parsed.name().equalsIgnoreCase(name);
        });
        if (!removed) {
            source.sendFailure(Component.literal("No city region called '" + name + "'"));
            return 0;
        }
        Config.CITY_REGIONS.set(entries);
        Config.SPEC.save();
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.removed", name)
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * {@code /tarkovscav city district [seed] [grid]} - build a district out of the same structure NBTs the
     * worldgen structure uses, right here, so an existing save can see the finished city today (README 7).
     * The seed is printed so the same layout can be reproduced; {@code grid} is how many street tiles across.
     */
    private static int cityDistrict(CommandContext<CommandSourceStack> context, Long seed, int grid) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        long usedSeed = seed == null ? level.getRandom().nextLong() : seed;
        BlockPos centre = BlockPos.containing(source.getPosition());
        var result = com.gfl.tarkovscav.worldgen.CityDistrictAssembler.place(level, centre, usedSeed, grid,
                0.7D, 0.5D);
        source.sendSuccess(() -> Component.literal("city district: seed=" + result.seed() + " grid="
                        + result.gridSize() + "x" + result.gridSize() + " streets=" + result.streets()
                        + " buildings=" + result.buildings() + " decor=" + result.decor()
                        + " bounds=" + result.bounds())
                .withStyle(ChatFormatting.AQUA), true);
        source.sendSuccess(() -> Component.literal("  same pieces and pools as the worldgen structure "
                        + "tarkovscav:city_district (README 7); re-run with seed=" + result.seed()
                        + " for this exact layout").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int cityList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> entries = Config.cityRegionEntries();
        source.sendSuccess(() -> Component.literal("cityRegions (" + entries.size() + "):")
                .withStyle(ChatFormatting.AQUA), false);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("tarkovscav.command.city.none")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        for (String entry : entries) {
            source.sendSuccess(() -> Component.literal("  " + entry), false);
        }
        source.sendSuccess(() -> Component.literal("cityStructureIds: " + Config.CITY_STRUCTURE_IDS.get()), false);
        source.sendSuccess(() -> Component.literal("cityStructureTags: " + Config.CITY_STRUCTURE_TAGS.get()), false);
        return 1;
    }

    private static int cityTest(CommandContext<CommandSourceStack> context, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos target = pos != null ? pos : BlockPos.containing(source.getPosition());
        CityGate.Result result = CityGate.test(level, target);

        String message = result.allowed() ? "tarkovscav.command.city.test.inside" : "tarkovscav.command.city.test.outside";
        source.sendSuccess(() -> Component.translatable(message, result.reason())
                        .withStyle(result.allowed() ? ChatFormatting.GREEN : ChatFormatting.RED)
                        .append(Component.literal(" @ " + target.toShortString()).withStyle(ChatFormatting.GRAY)),
                true);
        // The gate reads structure starts from the chunk; this prints what the chunk actually holds,
        // which is the only way to tell "no start" apart from "start with the wrong bounding box".
        String chunkReport = CityGate.describeChunkStarts(level, target);
        source.sendSuccess(() -> Component.literal("  " + chunkReport).withStyle(ChatFormatting.DARK_GRAY), false);
        TarkovScav.LOGGER.info("[spawngate] test at {} -> {} ({}); {}",
                target, result.allowed() ? "ACCEPT" : "REJECT", result.reason(), chunkReport);
        return result.allowed() ? 1 : 0;
    }

    // ------------------------------------------------------------------ /tarkovscav spawn

    private static LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return Commands.literal("spawn")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("scav");
                            builder.suggest("gunner_pillager");
                            builder.suggest("gunner_villager");
                            builder.suggest("sniper_pillager");
                            builder.suggest("sniper_villager");
                            builder.suggest("sniper");
                            // README 5y: the faction troops are spawnable too, so the tab-completion lists
                            // the four names the 5y table uses.
                            builder.suggest("usec_villager");
                            builder.suggest("bear_pillager");
                            builder.suggest("elite_villager");
                            builder.suggest("elite_pillager");
                            return builder.buildFuture();
                        })
                        .executes(context -> spawn(context, StringArgumentType.getString(context, "type"), null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> spawn(context,
                                        StringArgumentType.getString(context, "type"),
                                        BlockPosArgument.getBlockPos(context, "pos")))));
    }

    /**
     * Spawns a mob <b>through the city gate</b>: outside a city the command refuses and says why.
     * Use vanilla {@code /summon} to place one anywhere for a fight test - that path is exempt from
     * the gate unless {@code spawn.gateCommandSpawns} is turned on.
     */
    private static int spawn(CommandContext<CommandSourceStack> context, String typeName, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos target = pos != null ? pos : BlockPos.containing(source.getPosition());

        // 'sniper' is the short alias the user asked for (README 5q).
        String resolved = typeName.equalsIgnoreCase("sniper") ? "sniper_pillager" : typeName;
        ResourceLocation id = resolved.contains(":")
                ? ResourceLocation.tryParse(resolved)
                : TarkovScav.id(resolved);
        EntityType<?> type = id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !(type.equals(ModEntities.SCAV.get())
                || type.equals(ModEntities.GUNNER_PILLAGER.get())
                || type.equals(ModEntities.GUNNER_VILLAGER.get())
                || type.equals(ModEntities.SNIPER_PILLAGER.get())
                || type.equals(ModEntities.SNIPER_VILLAGER.get())
                || type.equals(ModEntities.USEC_VILLAGER.get())
                || type.equals(ModEntities.BEAR_PILLAGER.get())
                || type.equals(ModEntities.ELITE_VILLAGER.get())
                || type.equals(ModEntities.ELITE_PILLAGER.get()))) {
            source.sendFailure(Component.literal("Unknown mob '" + typeName
                    + "'; use 'scav', 'gunner_pillager', 'gunner_villager', 'sniper_pillager', 'sniper',"
                    + " 'sniper_villager', 'usec_villager', 'bear_pillager', 'elite_villager' or"
                    + " 'elite_pillager'."));
            return 0;
        }

        CityGate.Result gate = CityGate.test(level, target);
        if (!gate.allowed()) {
            source.sendFailure(Component.translatable("tarkovscav.command.spawn.denied",
                    typeName, target.toShortString(), gate.reason()).withStyle(ChatFormatting.RED));
            TarkovScav.LOGGER.info("[spawngate] REJECT command spawn of {} at {}: {}",
                    typeName, target, gate.reason());
            return 0;
        }

        Mob mob = create(level, type, Vec3.atBottomCenterOf(target));
        if (mob == null) {
            source.sendFailure(Component.literal("Could not create " + typeName));
            return 0;
        }

        String tierDescription = mob instanceof GunUser user ? user.scavTier().id() : "-";
        // README 5y: a faction troop also reports the three things that are new about it and that the gun
        // state cannot show - the rolled armor class, the accuracy tier it was given, and which voice pool it
        // will speak from. Only troops grow this tail, so every other mob's line is exactly as it was.
        String troop = com.gfl.tarkovscav.entity.ArmorClass.isTroop(mob)
                ? " | " + com.gfl.tarkovscav.entity.ArmorClass.describe(mob)
                        + " accuracy=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(mob)
                        + " " + com.gfl.tarkovscav.voice.VoicePools.describe(mob)
                : "";
        // README 5q: for a sniper, also say which gun the pool actually issued and which accuracy profile it
        // lands in - the exact two things a "did it get a sniper rifle" question is about.
        String extra = (mob instanceof com.gfl.tarkovscav.gun.SniperMob sniper
                ? " | " + sniper.sniperSummary()
                        + " entity=" + mob.getType().toShortString()
                        + " profile=" + com.gfl.tarkovscav.gun.AccuracyProfile.profileFor(mob).id()
                        + " cap=" + com.gfl.tarkovscav.gun.AccuracyProfile.capFor(mob)
                        + " " + com.gfl.tarkovscav.entity.ArmorClass.describe(mob)
                        + " accuracy=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(mob)
                        + " " + com.gfl.tarkovscav.voice.VoicePools.describe(mob)
                : "") + troop;
        source.sendSuccess(() -> Component.literal(Component.translatable("tarkovscav.command.spawn.ok",
                        typeName, tierDescription, target.toShortString()).getString() + extra)
                .withStyle(ChatFormatting.GREEN), true);
        TarkovScav.LOGGER.info("[spawngate] ACCEPT command spawn of {} ({}) at {}: {}{}",
                typeName, tierDescription, target, gate.reason(), extra);
        return 1;
    }

    /** Creates and finalizes a mob at a position. Shared by {@code spawn} and the fight harness. */
    private static Mob create(ServerLevel level, EntityType<?> type, Vec3 position) {
        var created = type.create(level);
        if (!(created instanceof Mob mob)) {
            return null;
        }
        mob.moveTo(position.x, position.y, position.z,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(position)),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntityWithPassengers(mob);
        return mob;
    }

    // ------------------------------------------------------------------ /tarkovscav test

    private static LiteralArgumentBuilder<CommandSourceStack> test() {
        return Commands.literal("test")
                .then(Commands.literal("fight")
                        .executes(context -> testFight(context, 16.0D, null))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg(4.0D, 64.0D))
                                .executes(context -> testFight(context,
                                        DoubleArgumentType.getDouble(context, "distance"), null))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> testFight(context,
                                                DoubleArgumentType.getDouble(context, "distance"),
                                                BlockPosArgument.getBlockPos(context, "pos"))))))
                // ------------------------------------------------------------------ A/B regression gate
                // All three take an optional `pos`, because the gate needs open ground with a clear
                // line of sight: at the world spawn (the RCON command position) the mobs can end up on
                // opposite sides of a city wall, where the target selector drops the target for lack of
                // sight and the fight never starts at all.
                .then(Commands.literal("watch")
                        .executes(context -> testWatch(context, DEFAULT_WATCH_SECONDS, 16.0D, null))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(2, 600))
                                .executes(context -> testWatch(context,
                                        IntegerArgumentType.getInteger(context, "seconds"), 16.0D, null))
                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(4.0D, 64.0D))
                                        .executes(context -> testWatch(context,
                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                DoubleArgumentType.getDouble(context, "distance"), null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> testWatch(context,
                                                        IntegerArgumentType.getInteger(context, "seconds"),
                                                        DoubleArgumentType.getDouble(context, "distance"),
                                                        BlockPosArgument.getBlockPos(context, "pos")))))))
                .then(Commands.literal("stall")
                        .executes(context -> testStall(context, DEFAULT_WATCH_SECONDS, 16.0D, null))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(2, 600))
                                .executes(context -> testStall(context,
                                        IntegerArgumentType.getInteger(context, "seconds"), 16.0D, null))
                                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(4.0D, 64.0D))
                                        .executes(context -> testStall(context,
                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                DoubleArgumentType.getDouble(context, "distance"), null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> testStall(context,
                                                        IntegerArgumentType.getInteger(context, "seconds"),
                                                        DoubleArgumentType.getDouble(context, "distance"),
                                                        BlockPosArgument.getBlockPos(context, "pos")))))))
                // Plays the voice clips one by one at the command position, so the user can tell which
                // line is which: test sound all | idle | chatter | grenade | mark | death | contact_1 ...
                // The optional pitch auditions the band: with it, every clip is played at that pitch,
                // without it the clips are spread across voice.pitchMin..pitchMax so one command shows the
                // whole range a mob can speak in.
                .then(Commands.literal("sound")
                        .executes(context -> testSound(context, "all", -1.0D))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> testSound(context,
                                        StringArgumentType.getString(context, "name"), -1.0D))
                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(0.1D, 2.0D))
                                        .executes(context -> testSound(context,
                                                StringArgumentType.getString(context, "name"),
                                                DoubleArgumentType.getDouble(context, "pitch"))))))
                // The weapon rack, checked where you are standing (README 5n): reports the nearest rack's
                // contents and says what a mob would do with it.
                .then(Commands.literal("rack").executes(ModCommands::testRack))
                // README 5p: report the gun-modding pool on every gun mob nearby.
                .then(Commands.literal("mods").executes(ModCommands::testMods))
                // README 5q: what the nearest sniper is doing - post, shots from it, last relocation reason.
                .then(Commands.literal("sniper").executes(ModCommands::testSniper))
                // README 5v: throw one right here and print what it would do - the expected damage per
                // distance, who is in range, and whether a wall is in the way.
                .then(Commands.literal("grenade")
                        .executes(context -> testGrenade(context, "frag"))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (com.gfl.tarkovscav.grenade.GrenadeKind kind
                                            : com.gfl.tarkovscav.grenade.GrenadeKind.values()) {
                                        builder.suggest(kind.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> testGrenade(context,
                                        StringArgumentType.getString(context, "type")))));
    }

    /**
     * {@code /tarkovscav test sniper}: every sniper within 64 blocks - post state, tier, accuracy profile, the
     * gun it was actually issued, and its distance to the operator. Asked through {@link SniperMob}, so it
     * covers the pillager sniper and the villager sniper (and a third one would need no change here).
     */
    private static int testSniper(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos centre = BlockPos.containing(source.getPosition());
        List<Mob> snipers = level.getEntitiesOfClass(Mob.class,
                new net.minecraft.world.phys.AABB(centre).inflate(64.0D),
                mob -> mob instanceof com.gfl.tarkovscav.gun.SniperMob);
        if (snipers.isEmpty()) {
            source.sendFailure(Component.literal("No sniper (pillager or villager) within 64 blocks."));
            return 0;
        }
        for (Mob sniper : snipers) {
            var profile = com.gfl.tarkovscav.gun.AccuracyProfile.profileFor(sniper);
            double cap = com.gfl.tarkovscav.gun.AccuracyProfile.capFor(sniper);
            var tier = sniper instanceof GunUser user ? user.scavTier() : null;
            int magazine = com.gfl.tarkovscav.gun.GunAttachments.capacityOf(sniper.getMainHandItem());
            String report = ((com.gfl.tarkovscav.gun.SniperMob) sniper).sniperSummary()
                    + " entity=" + sniper.getType().toShortString()
                    + " tier=" + (tier == null ? "-" : tier.id())
                    + " profile=" + profile.id() + " cap=" + cap
                    + " gun=" + sniper.getMainHandItem().getItem()
                    + " capacity=" + magazine
                    + " followRange=" + String.format(java.util.Locale.ROOT, "%.0f",
                            sniper.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE))
                    + " distanceToPlayer=" + String.format(java.util.Locale.ROOT, "%.1f",
                            Math.sqrt(sniper.distanceToSqr(source.getPosition())))
                    // README 5y: the same three facts the spawn command reports, so a sniper standing in the
                    // world can be asked about its armor class and its voice pool without respawning it.
                    + " " + com.gfl.tarkovscav.entity.ArmorClass.describe(sniper)
                    + " accuracy=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(sniper)
                    + " " + com.gfl.tarkovscav.voice.VoicePools.describe(sniper);
            source.sendSuccess(() -> Component.literal("  " + report), false);
            TarkovScav.LOGGER.info("[sniper] test: {}", report);
        }
        return snipers.size();
    }

    /**
     * {@code /tarkovscav test grenade [type]} (README 5v): throw one where you are looking and report what it
     * will do - the damage table per distance, who is inside the radius, and whether a wall blocks the blast
     * from where you are standing. The table is the same closed form the fragments use
     * ({@code GrenadeBlast.expectedFragmentDamage}), so it is a prediction and not a decoration.
     */
    private static int testGrenade(CommandContext<CommandSourceStack> context, String typeName) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        com.gfl.tarkovscav.grenade.GrenadeKind kind =
                com.gfl.tarkovscav.grenade.GrenadeKind.byId(typeName);
        if (kind == null) {
            source.sendFailure(Component.literal("Unknown grenade '" + typeName + "'; use one of: "
                    + com.gfl.tarkovscav.grenade.GrenadeKind.ids()));
            return 0;
        }
        if (!Config.GRENADES_ENABLED.get()) {
            source.sendFailure(Component.literal("grenades.enabled = false - the throw would do nothing."));
            return 0;
        }
        net.minecraft.world.entity.player.Player player = source.getPlayer() == null
                ? null : source.getPlayer();
        Vec3 eye = source.getPosition();
        Vec3 direction = player == null ? Vec3.ZERO : player.getLookAngle();
        if (direction.lengthSqr() < 1.0E-6D) {
            direction = new Vec3(0.0D, 0.0D, 1.0D);
        }
        double speed = Config.GRENADES_MAX_THROW_SPEED.get();
        // README 5v: the report is produced by the SAME solver the mobs throw with, so "what the command
        // prints" and "what a throw really does" cannot drift apart again. The aim point is one max range
        // along the look direction; the solver picks the launch pitch and says whether that arc is clear.
        Vec3 aim = eye.add(direction.scale(Config.MOB_GRENADE_MAX_RANGE.get()));
        com.gfl.tarkovscav.grenade.GrenadeBallistics.Solution solution =
                com.gfl.tarkovscav.grenade.GrenadeBallistics.solve(eye, aim, speed,
                        Config.MOB_GRENADE_ARC_SAMPLES.get(),
                        Config.MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES.get(),
                        com.gfl.tarkovscav.grenade.GrenadeBallistics.levelBlockTest(level));
        Vec3 landing = solution.landing();
        if (landing == null) {
            // The solved arc never met a block (shot off a cliff): fall back to the clipped path so the
            // command still reports a point.
            net.minecraft.world.phys.BlockHitResult ground = level.clip(
                    new net.minecraft.world.level.ClipContext(eye, aim,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, null));
            landing = ground.getLocation();
        }
        double radius = kind.isFlash() ? kind.flashRadius()
                : kind.isSmoke() ? kind.smokeRadius()
                : Math.max(kind.fragmentRadius(), kind.blastPower() * 2.0D);
        // Final copies: the sendSuccess lambdas capture these.
        final Vec3 point = landing;
        source.sendSuccess(() -> Component.literal("grenade " + kind.id()
                + ": landing ~" + point.toString() + " radius " + String.format(java.util.Locale.ROOT, "%.1f",
                        radius)
                + " pitch " + String.format(java.util.Locale.ROOT, "%.1f", solution.pitchDegrees()) + " deg"
                + (solution.clear() ? " [arc clear]" : " [arc NOT clear]")
                + " fuse " + kind.fuseTicks() + "t" + (Config.GRENADES_TERRAIN_DAMAGE.get()
                        ? " [TERRAIN DAMAGE ON]" : " [no terrain damage]")), false);
        // README 5v: 1/2/4/6/8 blocks, the same five distances the README table uses, and the two knobs that
        // decide them - so "the table in the docs" and "the table in game" cannot drift apart.
        StringBuilder table = new StringBuilder("  expected damage: ");
        for (double distance : new double[] { 1.0D, 2.0D, 4.0D, 6.0D, 8.0D }) {
            double frag = com.gfl.tarkovscav.grenade.GrenadeBlast.expectedFragmentDamage(kind, distance);
            double blast = com.gfl.tarkovscav.grenade.GrenadeBlast.expectedBlastDamage(kind, distance);
            table.append(String.format(java.util.Locale.ROOT, "%.0fb=%.1f  ", distance, frag + blast));
        }
        source.sendSuccess(() -> Component.literal(table.toString()
                + "(fragments capped at " + com.gfl.tarkovscav.grenade.GrenadeBlast.MAX_HITS_PER_ENTITY
                + " per body, pierce " + Config.GRENADE_FRAG_ARMOR_PIERCE.get()
                + ", blastDamagePerPower " + Config.GRENADE_BLAST_DAMAGE_PER_POWER.get() + ")"), false);
        if (kind.isFlash()) {
            source.sendSuccess(() -> Component.literal("  flash: fuse " + kind.fuseTicks() + "t"
                    + " playerBlind " + kind.playerBlindTicks() + "t"
                    + " mobBlind " + kind.mobBlindTicks() + "t"
                    + " (shortFuse " + Config.GRENADE_FLASH_SHORT_FUSE_TICKS.get() + "t"
                    + ", shortBlindFactor " + Config.GRENADE_FLASH_SHORT_BLIND_FACTOR.get() + ")"), false);
        }
        List<net.minecraft.world.entity.LivingEntity> inRange =
                com.gfl.tarkovscav.grenade.GrenadeEntity.candidates(level, landing, radius);
        if (inRange.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  nothing alive inside the radius"), false);
        }
        for (net.minecraft.world.entity.LivingEntity victim : inRange) {
            double distance = victim.getEyePosition().distanceTo(landing);
            boolean blocked = level.clip(new net.minecraft.world.level.ClipContext(landing, victim.getEyePosition(),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, null)).getType()
                    != net.minecraft.world.phys.HitResult.Type.MISS;
            double damage = com.gfl.tarkovscav.grenade.GrenadeBlast.expectedFragmentDamage(kind, distance)
                    + com.gfl.tarkovscav.grenade.GrenadeBlast.expectedBlastDamage(kind, distance);
            double flash = kind.isFlash() ? com.gfl.tarkovscav.grenade.GrenadeBlast.flashIntensityFor(landing,
                    victim.getEyePosition(), victim.getViewVector(1.0F), radius) : 0.0D;
            source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                            "  %s at %.1f b: damage ~%.1f%s%s", EntityNames.safeName(victim), distance, damage,
                            blocked ? " (BEHIND A WALL: no fragments, no flash)" : "",
                            kind.isFlash() ? String.format(java.util.Locale.ROOT, " flash %.2f", flash) : "")),
                    false);
        }
        // And actually throw one, so the sound, the arc and the fuse can be watched - along the solved
        // direction, so the printed arc is the arc that flies (inaccuracy 0, like the mobs').
        if (player != null) {
            com.gfl.tarkovscav.grenade.GrenadeEntity grenade =
                    new com.gfl.tarkovscav.grenade.GrenadeEntity(level, player, kind, kind.fuseTicks());
            grenade.setPos(eye.x, eye.y - 0.15D, eye.z);
            grenade.shoot(solution.direction().x, solution.direction().y, solution.direction().z,
                    (float) speed, 0.0F);
            level.addFreshEntity(grenade);
        }
        return 1;
    }

    /** {@code /tarkovscav test mods}: what each nearby gun mob is carrying, attachments and all. */    private static int testMods(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<Mob> found = gunMobs(level, BlockPos.containing(source.getPosition()));
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("No TarkovScav gun mob within 32 blocks."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("mods: enabled=" + Config.MODS_ENABLED.get()
                + " perSlotChance=" + Config.MODS_PER_SLOT_CHANCE.get()
                + " fullModChance=" + Config.MODS_FULL_MOD_CHANCE.get()
                + " maxPerGun=" + Config.MODS_MAX_PER_GUN.get()
                + " allowExtendedMag=" + Config.MODS_ALLOW_EXTENDED_MAG.get()), false);
        // The pool behind every "kitted" line (README 5p). `index=` is TaCZ's own attachment index, and
        // `registry-scan=` is the item-registry fallback; printing both is what tells "the packs define no
        // attachments" apart from "we asked before TaCZ finished scanning its packs" - the 2026 bug where every
        // pool was cached empty and no gun ever got a mod.
        source.sendSuccess(() -> Component.literal("  attachment pool (index = TaCZ's attachment index,"
                + " registry-scan = the fallback item sweep):"), false);
        for (String line : com.gfl.tarkovscav.gun.GunAttachments.diagnostics()) {
            source.sendSuccess(() -> Component.literal("    " + line), false);
        }
        if (com.gfl.tarkovscav.gun.GunAttachments.indexEmpty()) {
            source.sendSuccess(() -> Component.literal("    TaCZ's attachment index is EMPTY: your gun packs"
                            + " define no attachments, so no gun can be modded. This is not a bug in this mod.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        // The scripted-gun rule (README 5p): which guns are kept away from mobs, and why. This is the list to
        // read when a gun the user expects to see on a mob is missing from the pool.
        source.sendSuccess(() -> Component.literal("  scripted guns: excludeScriptedGuns="
                + Config.EXCLUDE_SCRIPTED_GUNS.get()
                + " trustedScriptNamespaces=" + Config.TRUSTED_SCRIPT_NAMESPACES.get()
                + " rescanTicks=" + Config.SCRIPTED_GUN_RESCAN_TICKS.get()), false);
        source.sendSuccess(() -> Component.literal("    kept out of the pool: "
                + com.gfl.tarkovscav.gun.ScriptedGuns.describe(10)), false);
        for (Mob mob : found) {
            ItemStack held = mob.getMainHandItem();
            String report = com.gfl.tarkovscav.gun.GunAttachments.describe(held);
            source.sendSuccess(() -> Component.literal("  " + mob.getType().toShortString() + ": " + report),
                    false);
            TarkovScav.LOGGER.info("[mods] {} {} -> {}", mob.getType().toShortString(),
                    EntityNames.safeName(mob), report);
        }
        return found.size();
    }

    /**
     * {@code /tarkovscav test rack}: the nearest rack within 16 blocks, the item on it, which armament it
     * classifies as, what a taker would become, and every candidate in range - i.e. the whole AI path
     * without waiting for a villager to wander past.
     *
     * <p>It also reports the two things that decide whether the rack is the normal one or the creative twin
     * (which is a different BLOCK and therefore a different block entity type, never a flag in NBT): the
     * {@code infinite} template flag, and the absorb scan's radius/height/interval - plus the nearest drop
     * the scan would currently pick up, or why it would not.</p>
     */
    private static int testRack(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos centre = BlockPos.containing(source.getPosition());
        com.gfl.tarkovscav.block.WeaponRackBlockEntity rack = null;
        BlockPos rackPos = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-16, -8, -16), centre.offset(16, 8, 16))) {
            if (level.getBlockEntity(pos) instanceof com.gfl.tarkovscav.block.WeaponRackBlockEntity found) {
                double distance = pos.distSqr(centre);
                if (distance < best) {
                    best = distance;
                    rack = found;
                    rackPos = pos.immutable();
                }
            }
        }
        if (rack == null) {
            source.sendFailure(Component.literal("No weapon rack within 16 blocks."));
            return 0;
        }
        // Final copies: the lambdas below capture these, and the loop variables above are not effectively
        // final.
        final com.gfl.tarkovscav.block.WeaponRackBlockEntity foundRack = rack;
        final BlockPos foundPos = rackPos;
        ItemStack held = foundRack.held();
        String contents = held.isEmpty() ? "(empty)" : held.getHoverName().getString()
                + " x" + held.getCount() + " [" + held.getItem() + "]";
        var armament = com.gfl.tarkovscav.block.WeaponRackArmament.armamentOf(held);
        boolean infinite = foundRack.infinite();
        boolean creativeOff = infinite && !Config.RACK_CREATIVE_RACK_ENABLED.get();
        // The facing (README 5n): readable here because it is exactly what is easy to get wrong in game.
        net.minecraft.world.level.block.state.BlockState rackState = foundRack.getBlockState();
        net.minecraft.core.Direction facing = rackState.hasProperty(
                com.gfl.tarkovscav.block.WeaponRackBlock.FACING)
                        ? rackState.getValue(com.gfl.tarkovscav.block.WeaponRackBlock.FACING)
                        : net.minecraft.core.Direction.NORTH;
        source.sendSuccess(() -> Component.literal("rack " + foundPos.toShortString() + ": " + contents
                + " kind=" + (infinite ? "creative" : "normal")
                + " infinite=" + infinite
                + " facing=" + facing.getName()
                + " creativeRackEnabled=" + Config.RACK_CREATIVE_RACK_ENABLED.get()
                + (creativeOff ? " (INERT: creative rack switched off)" : "")
                + " armament=" + (held.isEmpty() ? "-" : armament.id())
                + " cooldown=" + foundRack.takeCooldown()
                + " accepts=" + com.gfl.tarkovscav.block.WeaponRackArmament.acceptedList()), false);
        Mob recruit = com.gfl.tarkovscav.block.WeaponRackTaker.findRecruit(level, foundPos);
        source.sendSuccess(() -> Component.literal(recruit == null
                ? "  no unarmed villager/pillager in takeRadius=" + Config.RACK_TAKE_RADIUS.get()
                        + " (priority=" + Config.RACK_PRIORITY.get() + ")"
                : "  would take it: " + EntityNames.safeName(recruit) + " -> "
                        + (recruit instanceof net.minecraft.world.entity.npc.Villager
                                ? "tarkovscav:gunner_villager" : "tarkovscav:gunner_pillager")
                        + " fighting as " + armament.id()
                        + (infinite ? " (and the template STAYS on the rack)" : " (and the rack empties)")),
                false);
        // The absorb scan, reported the same way: what it covers, and what it would take right now.
        net.minecraft.world.entity.item.ItemEntity drop =
                com.gfl.tarkovscav.block.WeaponRackBlockEntity.nearestDrop(level, foundPos);
        source.sendSuccess(() -> Component.literal("  absorb=" + Config.RACK_ABSORB_DROPPED_ITEMS.get()
                + " radius=" + Config.RACK_ABSORB_RADIUS.get()
                + " height=" + Config.RACK_ABSORB_HEIGHT.get()
                + " every=" + Config.RACK_ABSORB_CHECK_INTERVAL_TICKS.get() + "t"
                + (drop == null ? " -> nothing dropped in range"
                        : " -> would absorb " + drop.getItem().getHoverName().getString()
                                + " x1 of x" + drop.getItem().getCount()
                                + " [" + drop.blockPosition().toShortString() + "]")), false);
        TarkovScav.LOGGER.info("[rack] test at {}: {} armament={} infinite={} recruit={} drop={}",
                foundPos.toShortString(), contents, armament.id(), infinite,
                recruit == null ? "none" : EntityNames.safeName(recruit),
                drop == null ? "none" : drop.getItem().getHoverName().getString());
        return 1;
    }

    /** {@code /tarkovscav test sound}: play a pool (or one clip) here, one line every 1.5s. */
    private static int testSound(CommandContext<CommandSourceStack> context, String name, double requested) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // README 5y: a name may also be an ENTITY ("usec_villager"), which is how you audition exactly what
        // one mob will say. The family comes from VoicePools, so the name table and the AI cannot drift.
        String poolName = name;
        String family = com.gfl.tarkovscav.voice.VoicePools.familyForEntityId(name);
        if (!family.equals("shared")) {
            poolName = family;
        }
        List<SoundEvent> sounds = ModSounds.pool(poolName);
        if (sounds.isEmpty()) {
            source.sendFailure(Component.literal("No such voice pool, family, entity or clip '" + name
                    + "'; try " + String.join(", ", ModSounds.poolNames())
                    + ", an entity like usec_villager, or a clip name like usec_contact_3."));
            return 0;
        }
        // Final copies for the lambdas below (a lambda may only capture effectively-final locals).
        final String shown = poolName;
        final String entityNote = poolName.equals(name) ? "" : " (entity " + name + ")";
        float min = Config.voicePitchMin();
        float max = Config.voicePitchMax();
        Vec3 pos = source.getPosition();
        for (int i = 0; i < sounds.size(); i++) {
            SoundEvent sound = sounds.get(i);
            int order = i + 1;
            // No explicit pitch -> walk the band, so the first clip is the lowest voice and the last the
            // highest; with one clip there is nothing to walk, so the middle of the band is used.
            float pitch = requested > 0.0D
                    ? (float) Math.max(min, Math.min(max, requested))
                    : (sounds.size() > 1 ? min + (max - min) * i / (sounds.size() - 1) : (min + max) * 0.5F);
            FightHarness.schedule(level.getServer(), i * 30, () -> {
                level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, 1.0F, pitch);
                TarkovScav.LOGGER.info("[sound] playing {} ({}/{}) at {} pitch={} band={}..{}",
                        sound.getLocation(), order, sounds.size(),
                        String.format("(%.1f,%.1f,%.1f)", pos.x, pos.y, pos.z),
                        String.format("%.3f", pitch), String.format("%.2f", min), String.format("%.2f", max));
            });
        }
        source.sendSuccess(() -> Component.literal("Playing " + sounds.size() + " clip(s) from '" + shown
                + "'" + entityNote + " here, one every 1.5s, "
                + (requested > 0.0D
                        ? "all at pitch " + String.format("%.3f", Math.max(min, Math.min(max, requested)))
                        : "pitch swept across " + String.format("%.2f..%.2f", min, max)
                                + " (voice.pitchMin..pitchMax)"))
                .withStyle(ChatFormatting.AQUA), true);
        return sounds.size();
    }

    /** Duration of {@code /tarkovscav test watch} with no argument, in seconds. */
    public static final int DEFAULT_WATCH_SECONDS = 20;
    /**
     * The two numbers {@code /tarkovscav test watch} asserts on, measured over its fixed window on a
     * healthy scav at the default 16 blocks (see README 5i for the recorded runs: 41 shots and 1.3-2.3
     * blocks at 16 blocks, more when the mob has to advance). They are deliberately below what the gate
     * actually measures, so it fails on a broken state machine - the bug it exists for is a mob that
     * moves <b>0.00</b> blocks and fires 0 shots - rather than on fight-to-fight variance.
     */
    public static final double WATCH_MIN_MOVED_BLOCKS = 1.0D;
    public static final int WATCH_MIN_SHOTS = 3;
    /** Max health of the practice dummy in the watch/stall harness, so the fight lasts the window. */
    public static final float DUMMY_HARNESS_HEALTH = 1000.0F;

    /** One live firefight, handed to the delayed assertion of {@code test watch} / {@code test stall}. */
    private record Fight(Mob mob, Mob dummy, Vec3 startPos, GunBrain brain) {
    }

    /**
     * Sets up a reproducible firefight between a scav and a practice dummy.
     *
     * <p>The dummy is a {@code minecraft:zombie} with {@code NoAI} and the
     * {@link ScavEntity#DUMMY_TAG} scoreboard tag: scavs target that tag, and a zombie has real
     * health, so TaCZ's bullets do real damage to it and every point of that damage can be read back
     * with {@code /data get entity <uuid> Health}. No human player is needed anywhere in the loop.</p>
     */
    private static int testFight(CommandContext<CommandSourceStack> context, double distance, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        Fight fight = setupFight(source, distance, pos, false);
        if (fight == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Fight set up: scav "
                        + (fight.brain().loadout() == null ? "" : "(" + fight.brain().loadout() + ") ")
                        + "at " + fight.mob().blockPosition().toShortString() + ", dummy at "
                        + fight.dummy().blockPosition().toShortString() + " (" + Math.round(distance)
                        + " blocks)")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * The requirement-A regression gate: run one firefight for a fixed number of seconds and then
     * assert, in the server log, that the mob actually <b>moved</b> and actually <b>fired</b>.
     *
     * <p>This is the head-less replacement for "watch a scav and see if it is stuck": the previous
     * failure mode was a mob that both stood still and never fired, so those two numbers are exactly
     * what the gate checks. It prints every number it used, so a failing run can be diagnosed from the
     * log alone, and it is driven over RCON by the dedicated-server test.</p>
     */
    private static int testWatch(CommandContext<CommandSourceStack> context, int seconds, double distance,
                                 @Nullable BlockPos pos) {
        CommandSourceStack source = context.getSource();
        Fight fight = setupFight(source, distance, pos, true);
        if (fight == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        schedule(level, fight.mob(), seconds * 20, () -> reportWatch(seconds, fight));
        source.sendSuccess(() -> Component.literal("Watching for " + seconds + "s (gate: moved >= "
                + WATCH_MIN_MOVED_BLOCKS + " blocks, shots >= " + WATCH_MIN_SHOTS + ")")
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /**
     * The requirement-A <em>fault-injection</em> gate: the same fight, but every shot in the window is
     * answered {@code FORGE_EVENT_CANCEL} without calling TaCZ, which is the condition that used to
     * leave a mob standing in the open aiming for ever (FIRE was only left on an accepted shot or on
     * losing sight). The gate asserts the anti-stall watchdog broke the stand-off.
     */
    private static int testStall(CommandContext<CommandSourceStack> context, int seconds, double distance,
                                 @Nullable BlockPos pos) {
        CommandSourceStack source = context.getSource();
        Fight fight = setupFight(source, distance, pos, true);
        if (fight == null) {
            return 0;
        }
        fight.brain().simulateShotFailures(seconds * 20);
        ServerLevel level = source.getLevel();
        schedule(level, fight.mob(), seconds * 20, () -> reportStall(seconds, fight));
        source.sendSuccess(() -> Component.literal("Stall gate for " + seconds
                        + "s: every shot will be refused (FORGE_EVENT_CANCEL); gate: escapes >= 1")
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /**
     * Creates the scav + dummy pair, faces them at each other and reports the setup.
     *
     * @param durable when true the dummy gets a large max health, so the fight lasts the whole harness
     *                window instead of ending with the first burst - a gate that can fail because its
     *                target died would be measuring the dummy, not the AI
     */
    @Nullable
    private static Fight setupFight(CommandSourceStack source, double distance, @Nullable BlockPos pos,
                                    boolean durable) {
        ServerLevel level = source.getLevel();
        BlockPos origin = pos != null ? pos : BlockPos.containing(source.getPosition());

        // Put the scav at the command position and the dummy `distance` blocks east of it.
        Mob scav = create(level, ModEntities.SCAV.get(), Vec3.atBottomCenterOf(origin));
        if (scav == null) {
            source.sendFailure(Component.literal("Could not create a scav"));
            return null;
        }
        Vec3 dummyPos = Vec3.atBottomCenterOf(origin.offset((int) Math.round(distance), 0, 0));
        Mob dummy = create(level, EntityType.ZOMBIE, dummyPos);
        if (dummy == null) {
            source.sendFailure(Component.literal("Could not create the practice dummy"));
            return null;
        }
        dummy.addTag(ScavEntity.DUMMY_TAG);
        dummy.setNoAi(true);
        dummy.setSilent(true);
        dummy.setPersistenceRequired();
        dummy.setCustomName(Component.literal("Armed Mobs Practice Dummy"));
        dummy.setCustomNameVisible(false);
        dummy.setInvulnerable(false);
        if (durable) {
            // `Health` is clamped to max health when it is read back, so the attribute moves first.
            if (dummy.getAttribute(Attributes.MAX_HEALTH) != null) {
                dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DUMMY_HARNESS_HEALTH);
            }
            dummy.setHealth(DUMMY_HARNESS_HEALTH);
        }

        // Face each other so the fight starts immediately.
        scav.setTarget(dummy);
        scav.getLookControl().setLookAt(dummy, 180.0F, 180.0F);

        GunBrain brain = scav instanceof GunUser user ? user.gunBrain() : null;
        String gun = brain == null || brain.loadout() == null ? "none" : brain.loadout().toString();

        TarkovScav.LOGGER.info("[test] fight set up: scav at {} (tier {}, gun {}) vs dummy at {} ({} blocks, health {})",
                scav.blockPosition().toShortString(),
                scav instanceof GunUser user ? user.scavTier().id() : "-", gun,
                dummy.blockPosition().toShortString(), Math.round(distance), dummy.getHealth());
        TarkovScav.LOGGER.info("[test] dummy uuid {} - read its health with: /data get entity {} Health",
                dummy.getUUID(), dummy.getUUID());
        return brain == null ? null : new Fight(scav, dummy, scav.position(), brain);
    }

    /** Queues {@code task} to run on the server thread after {@code delayTicks} game ticks. */
    private static void schedule(ServerLevel level, Mob subject, int delayTicks, Runnable task) {
        FightHarness.schedule(level.getServer(), subject, delayTicks, task);
    }

    /** Prints - and judges - the numbers of a {@code test watch} run. */
    private static void reportWatch(int seconds, Fight fight) {
        double moved = fight.startPos().distanceTo(fight.mob().position());
        int shots = fight.brain().shotsFired();
        int escapes = fight.brain().stallEscapes();
        double damage = fight.dummy().getMaxHealth() - fight.dummy().getHealth();
        boolean pass = moved >= WATCH_MIN_MOVED_BLOCKS && shots >= WATCH_MIN_SHOTS;
        TarkovScav.LOGGER.info("[test] WATCH {} after {}s: moved={} blocks, shots={}, dummyDamage={},"
                        + " stalls={}, state={}, gates[moved>={}, shots>={}]",
                pass ? "PASS" : "FAIL", seconds, fmt(moved), shots, fmt(damage), escapes,
                fight.brain().state(), WATCH_MIN_MOVED_BLOCKS, WATCH_MIN_SHOTS);
        // A failing gate has to say why, or it is just a red light: `target=none` means the mob lost
        // the dummy (usually no line of sight from where it was spawned), which is a test-site problem
        // rather than a state-machine problem.
        TarkovScav.LOGGER.info("[test] WATCH context: target={} los={} distance={} dummyAlive={} pos={}",
                fight.mob().getTarget() == null ? "none" : "set",
                fight.mob().hasLineOfSight(fight.dummy()),
                fmt(fight.mob().distanceTo(fight.dummy())), fight.dummy().isAlive(),
                fmt(fight.mob().position()));
        if (escapes > 0) {
            TarkovScav.LOGGER.warn("[test] WATCH note: the anti-stall watchdog fired {} time(s) - see the"
                    + " [gunai] WARN lines above for the TaCZ result that caused it", escapes);
        }
        if (fight.brain().state() == GunAiState.IDLE && shots == 0 && moved < 0.01D) {
            TarkovScav.LOGGER.warn("[test] WATCH hint: the mob never entered the fight. If target=none,"
                    + " re-run with an explicit `pos` on open ground - the practice dummy has to be in"
                    + " line of sight, or the target selector drops it before the gun goal can start.");
        }
    }

    /**
     * Prints - and judges - the numbers of a {@code test stall} run.
     *
     * <p>The assertion is on the <em>escape</em>, not on the distance: with every shot refused, the
     * watchdog is the only thing that can move the mob, so a mob that never escapes stands still for
     * ever - which is the bug. `moved` is reported for context but not asserted, because a mob can
     * legitimately cover a couple of blocks on its way into FIRE (advancing into range) before the
     * stall begins.</p>
     */
    private static void reportStall(int seconds, Fight fight) {
        double moved = fight.startPos().distanceTo(fight.mob().position());
        int escapes = fight.brain().stallEscapes();
        boolean inFire = fight.brain().state() == GunAiState.FIRE;
        boolean pass = escapes >= 1;
        TarkovScav.LOGGER.info("[test] STALL {} after {}s of refused shots: escapes={}, moved={} blocks,"
                        + " stillInFire={}, state={}, gate[escapes>=1]",
                pass ? "PASS" : "FAIL", seconds, escapes, fmt(moved), inFire, fight.brain().state());
        TarkovScav.LOGGER.info("[test] STALL context: target={} los={} lastResult={} pos={}",
                fight.mob().getTarget() == null ? "none" : "set",
                fight.mob().hasLineOfSight(fight.dummy()),
                fight.brain().lastResult(), fmt(fight.mob().position()));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static String fmt(Vec3 vec) {
        return String.format("(%.1f,%.1f,%.1f)", vec.x, vec.y, vec.z);
    }

    // ------------------------------------------------------------------ /tarkovscav cover

    private static LiteralArgumentBuilder<CommandSourceStack> cover() {
        return Commands.literal("cover").executes(context -> {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            Mob nearest = nearestGunMob(level, BlockPos.containing(source.getPosition()));
            if (nearest == null) {
                source.sendFailure(Component.literal("No TarkovScav mob within 32 blocks."));
                return 0;
            }
            GunBrain brain = ((GunUser) nearest).gunBrain();
            net.minecraft.world.entity.LivingEntity target = nearest.getTarget();
            if (target == null) {
                source.sendFailure(Component.literal("That mob has no target; use /tarkovscav test fight first."));
                return 0;
            }

            Vec3 threatEye = target.getEyePosition();
            List<CombatTactics.Spot> spots = brain.tactics().spots(level, threatEye);
            spots.sort(Comparator.comparingDouble(spot -> spot.distanceFromMob()));
            int hidden = (int) spots.stream().filter(CombatTactics.Spot::hidden).count();

            source.sendSuccess(() -> Component.literal("cover search for " + nearest.getType().toShortString()
                    + ": " + spots.size() + " candidate(s), " + hidden + " fully hidden"
                    + " (radius " + Config.COVER_SEARCH_RADIUS.get() + ", cache "
                    + Config.COVER_CACHE_TICKS.get() + " ticks)").withStyle(ChatFormatting.AQUA), false);
            for (CombatTactics.Spot spot : spots) {
                String line = "  " + spot.describe();
                source.sendSuccess(() -> Component.literal(line), false);
            }
            TarkovScav.LOGGER.info("[cover] {} sees {} candidate(s), {} hidden; best cover {}",
                    nearest.getType().toShortString(), spots.size(), hidden,
                    brain.tactics().bestCover(level, threatEye, true, 0.0D, true) == null ? "none"
                            : brain.tactics().bestCover(level, threatEye, true, 0.0D, true).describe());
            return spots.size();
        });
    }

    // ------------------------------------------------------------------ /tarkovscav gunpool

    private static LiteralArgumentBuilder<CommandSourceStack> gunPool() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("gunpool")
                .executes(context -> gunPool(context, ScavTier.RIFLE));
        for (ScavTier tier : ScavTier.values()) {
            node.then(Commands.literal(tier.id()).executes(context -> gunPool(context, tier)));
        }
        return node;
    }

    private static int gunPool(CommandContext<CommandSourceStack> context, ScavTier tier) {
        CommandSourceStack source = context.getSource();
        List<ResourceLocation> pool = GunPool.forTier(tier);
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.gunpool", tier.id(), pool.size())
                .withStyle(ChatFormatting.AQUA), false);
        // Why the list is shorter than "every gun TaCZ knows": the two filters, side by side (README 5p).
        source.sendSuccess(() -> Component.literal("  filtered: " + GunPool.rejectedByFilter()
                + " by gunBlacklist/gunWhitelist/excludedGunTypes, " + GunPool.rejectedByScript()
                + " by the scripted-gun rule"), false);
        if (GunPool.rejectedByScript() > 0) {
            source.sendSuccess(() -> Component.literal("  scripted-gun rule kept out: "
                    + com.gfl.tarkovscav.gun.ScriptedGuns.describe(10)), false);
        }
        for (ResourceLocation gun : pool) {
            source.sendSuccess(() -> Component.literal("  " + gun + " [" + GunPool.typeOf(gun) + "]"), false);
        }
        if (pool.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  (empty - is the TaCZ gun pack loaded?)")
                    .withStyle(ChatFormatting.RED), false);
        }
        return pool.size();
    }

    // ------------------------------------------------------------------ /tarkovscav debug

    private static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug").executes(context -> {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            BlockPos centre = BlockPos.containing(source.getPosition());
            List<Mob> found = gunMobs(level, centre);
            if (found.isEmpty()) {
                source.sendFailure(Component.literal("No TarkovScav mob within 32 blocks."));
                return 0;
            }
            for (Mob mob : found) {
                GunBrain brain = ((GunUser) mob).gunBrain();
                String report = brain.debugSummary();
                // The faction layer is reported next to the gun state, not inside it: one tells you what
                // the mob knows, the other what it is doing with its weapon (README 5m).
                String faction = com.gfl.tarkovscav.faction.Faction.describe(mob)
                        + " " + com.gfl.tarkovscav.faction.Renegade.describe(mob)
                        + " " + com.gfl.tarkovscav.faction.AlertNetwork.describe(mob)
                        + " " + com.gfl.tarkovscav.gun.AccuracyProfile.describe(mob)
                        // README 5y: the armor class has no other way to be seen in game - it is a number in
                        // the persistent data, not a piece of equipment - so debug is where it is read out.
                        + " " + com.gfl.tarkovscav.entity.ArmorClass.describe(mob)
                        + " accuracyProfile=" + com.gfl.tarkovscav.entity.FactionTierProfile.describe(mob)
                        + " " + com.gfl.tarkovscav.voice.VoicePools.describe(mob)
                        // README 5m: WHO it is currently hunting, and whether that target is a village-defence
                        // target (the faction_village_hostile list) - the answer to "is it shooting zombies?".
                        + " target=" + (mob.getTarget() == null ? "none"
                                : EntityNames.safeName(mob.getTarget()) + " ("
                                        + mob.getTarget().getType().toShortString() + ")")
                        + (mob.getTarget() != null
                                && com.gfl.tarkovscav.faction.Faction.villageHostile(mob, mob.getTarget())
                                        ? " VILLAGE-HOSTILE" : "");
                source.sendSuccess(() -> Component.literal(mob.getType().toShortString() + ": " + report
                        + " | " + faction), false);
                TarkovScav.LOGGER.info("[debug] {} {} | {} at {}", mob.getType().toShortString(), report,
                        faction, mob.blockPosition().toShortString());
            }
            return found.size();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static List<Mob> gunMobs(ServerLevel level, BlockPos centre) {
        List<Mob> found = new ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof GunUser && entity.distanceToSqr(
                    centre.getX() + 0.5D, centre.getY() + 0.5D, centre.getZ() + 0.5D) < 1024.0D) {
                found.add((Mob) entity);
            }
        }
        return found;
    }

    private static Mob nearestGunMob(ServerLevel level, BlockPos centre) {
        return gunMobs(level, centre).stream()
                .min(Comparator.comparingDouble(mob -> mob.distanceToSqr(
                        centre.getX() + 0.5D, centre.getY() + 0.5D, centre.getZ() + 0.5D)))
                .orElse(null);
    }
}
