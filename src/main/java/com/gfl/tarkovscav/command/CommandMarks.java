package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import com.gfl.tarkovscav.entity.ScavEntity;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunUser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The command system's logic layer: making marks, ordering a faction at one, and answering the questions
 * the tools and {@code /armedmobs marks} ask.
 *
 * <h2>The three rules that live here</h2>
 * <ol>
 *   <li><b>Strict faction locking.</b> {@link #issue} selects units with
 *       {@link CommandFaction#affects(net.minecraft.world.entity.Entity)} and nothing else, so the village
 *       tool can never reach a pillager, the pillager tool never a scav, and so on - asserted per tool by
 *       {@code tools/selftest_command_marks.js}.</li>
 *   <li><b>Neutral marks.</b> Nothing in this class looks at which tool made a mark. A mark is a
 *       position and a letter; the faction travels with the order.</li>
 *   <li><b>An order is refused while retreating.</b> The design doc's "self-preservation first" says a
 *       unit breaking contact takes no new orders, so {@link #issue} skips one whose gun state is already
 *       {@link GunAiState#RETREAT}. It keeps the old order's tick data out of the way rather than
 *       overwriting a retreat with a walk.</li>
 * </ol>
 *
 * <h2>Who can be ordered</h2>
 * <p>The tag decides <b>membership</b>; {@link #isCommandable} decides whether this mod can actually make
 * the unit walk. The order is a behaviour the mod attaches to its own mob classes, and Forge gives no way
 * to add a goal to a class the mod does not own, so a vanilla {@code minecraft:pillager} inside the
 * illager tag is skipped rather than given an order it could never obey. That is a documented limitation,
 * not a faction leak: the tag rule is still the only membership test.</p>
 */
public final class CommandMarks {
    private CommandMarks() {
    }

    // ------------------------------------------------------------------ mark creation

    /** How long a thrown signal stick's mark lives, in ticks (5 minutes). */
    public static int stickDurationTicks() {
        return Math.max(1, Config.COMMAND_STICK_DURATION_TICKS.get());
    }

    /**
     * Creates a mark on the first free letter and returns it.
     *
     * <p>{@code source} decides the lifetime: a tool mark and a stick mark expire (the stick after
     * {@code command.stickDurationTicks}), a signal-point mark is permanent. The letter is chosen from the
     * letters already in use in this dimension, so deleting B makes the next mark B again - the numbering
     * rule in the design doc.</p>
     */
    public static CommandMark create(ServerLevel level, BlockPos pos, CommandMark.Source source, long now) {
        ResourceLocation dimension = level.dimension().location();
        MarkData data = MarkData.get(level.getServer());
        String letter = CommandMark.firstFreeLetter(data.usedLetters(dimension, now));
        long expires = source == CommandMark.Source.POINT
                ? CommandMark.PERMANENT
                : now + (source == CommandMark.Source.STICK ? stickDurationTicks()
                        : Math.max(1, Config.COMMAND_STICK_DURATION_TICKS.get()));
        CommandMark mark = new CommandMark(letter, pos, source, expires, dimension);
        return data.put(mark, now);
    }

    /** The marks in this level, live only. */
    public static List<CommandMark> marks(ServerLevel level, long now) {
        return MarkData.get(level.getServer()).live(level.dimension().location(), now);
    }

    @Nullable
    public static CommandMark find(ServerLevel level, String letter, long now) {
        return MarkData.get(level.getServer()).find(level.dimension().location(), letter, now);
    }

    public static boolean remove(ServerLevel level, String letter) {
        return MarkData.get(level.getServer()).remove(level.dimension().location(), letter);
    }

    public static int clear(ServerLevel level) {
        return MarkData.get(level.getServer()).clear(level.dimension().location());
    }

    // ------------------------------------------------------------------ ordering

    /**
     * Orders every commandable unit of {@code faction} within {@code command.radius} of the mark to
     * advance on it, and returns how many were ordered.
     *
     * <p>The element is sorted by entity id before the orders are written, because the overwatch rotation
     * is a function of the position in the element: sorting makes "who holds this window" deterministic
     * instead of dependent on the order the entity query happened to return.</p>
     */
    public static int issue(ServerLevel level, CommandFaction faction, CommandMark mark, long now,
                            @Nullable ServerPlayer feedback) {
        if (!Config.SPEC.isLoaded() || !Config.COMMAND_ENABLED.get()) {
            if (feedback != null) {
                feedback.displayClientMessage(
                        Component.translatable("tarkovscav.command.disabled").withStyle(
                                net.minecraft.ChatFormatting.RED), false);
            }
            return 0;
        }
        double radius = Math.max(1.0D, Config.COMMAND_RADIUS.get());
        List<Mob> selected = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                new AABB(mark.pos()).inflate(radius))) {
            if (!faction.affects(mob) || !isCommandable(mob) || !mob.isAlive()) {
                continue;
            }
            if (AdvanceOrder.retreatOverrides(isRetreating(mob))) {
                continue;
            }
            selected.add(mob);
        }
        selected.sort(Comparator.comparingInt(Mob::getId));

        long expires = mark.permanentMark() ? CommandMark.PERMANENT : mark.expiresAtTick();
        int size = selected.size();
        for (int i = 0; i < size; i++) {
            AdvanceOrder.write(selected.get(i), new AdvanceOrder(mark.pos(), mark.letter(),
                    mark.dimension(), now, expires, i, size));
        }
        if (feedback != null) {
            if (size == 0) {
                feedback.displayClientMessage(Component.translatable(
                        "tarkovscav.command.no.units", faction.id(), (int) radius).withStyle(
                        net.minecraft.ChatFormatting.YELLOW), false);
            } else {
                feedback.displayClientMessage(Component.translatable(
                        "tarkovscav.command.ordered", size, faction.id(), mark.chatLine())
                        .withStyle(net.minecraft.ChatFormatting.AQUA), false);
            }
        }
        TarkovScav.LOGGER.info("[command] {} ordered {} unit(s) to mark {} at {}", faction.id(), size,
                mark.letter(), mark.pos().toShortString());
        return size;
    }

    /** True when this mod can actually make the unit walk: its own three armed base classes. */
    public static boolean isCommandable(Mob mob) {
        return mob instanceof ScavEntity || mob instanceof GunnerPillagerEntity
                || mob instanceof GunnerVillagerEntity;
    }

    /** True while the unit is breaking contact, which is when it takes no new orders. */
    public static boolean isRetreating(Mob mob) {
        return mob instanceof GunUser user && user.gunAiState() == GunAiState.RETREAT;
    }

    // ------------------------------------------------------------------ the player's current mark

    /** The player's current mark with the documented fallback, or null when the level has no marks. */
    @Nullable
    public static CommandMark current(ServerPlayer player, long now) {
        return MarkData.get(player.server).currentMark(player.getUUID(),
                player.level().dimension().location(), now);
    }

    /** Cycles to the next mark and returns it (the sneak-right-click-air action). */
    @Nullable
    public static CommandMark cycle(ServerPlayer player, long now) {
        return MarkData.get(player.server).cycle(player.getUUID(),
                player.level().dimension().location(), now);
    }

    /** Makes a mark the player's current one. */
    public static void select(ServerPlayer player, CommandMark mark) {
        MarkData.get(player.server).setCurrentLetter(player.getUUID(), mark.letter());
    }

    // ------------------------------------------------------------------ diagnostics

    /** The lines {@code /armedmobs marks} prints, in the source's own dimension. */
    public static List<String> listLines(ServerLevel level, long now) {
        List<String> lines = new ArrayList<>();
        List<CommandMark> marks = marks(level, now);
        if (marks.isEmpty()) {
            lines.add("no marks in " + level.dimension().location()
                    + " - right-click a block with a command tool, throw a signal stick, or place a"
                    + " signal point");
            return lines;
        }
        lines.add(marks.size() + " mark(s) in " + level.dimension().location()
                + " (cap " + Config.COMMAND_MAX_MARKS.get() + "):");
        for (CommandMark mark : marks) {
            lines.add("  " + mark.describe(now));
        }
        return lines;
    }
}
