package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every command mark in the world, plus each player's "current mark" - one {@link SavedData} file,
 * {@code data/tarkovscav_marks.dat}.
 *
 * <h2>Per dimension, and why that is structural rather than a filter</h2>
 * <p>The storage is {@code Map<dimension, List<CommandMark>>}. A mark is created, listed, removed and
 * expired inside one dimension, and {@code /armedmobs marks} only ever shows the dimension its source is
 * standing in. Two marks that happen to share a letter in two dimensions are two unrelated marks, and an
 * {@link AdvanceOrder} carries the dimension it was issued in, so an order can never pull a unit towards
 * a coordinate in a level it is not in.</p>
 *
 * <h2>The current mark, per player</h2>
 * <p>{@code Map<UUID, letter>}. It is server-side state on purpose (the design doc asks for exactly
 * that): right-clicking air with a command tool must work with no client mod at all, and the choice has
 * to survive a re-login. When the letter a player points at disappears, {@link #currentMark} falls back
 * to the first live mark in that dimension instead of failing silently.</p>
 *
 * <h2>Capacity</h2>
 * <p>{@code command.maxMarks} (default 12) caps a dimension. Adding a mark past the cap drops the
 * <b>oldest</b> one, which is the rule in the design doc: a player who keeps marking without ordering
 * loses the marks they have stopped thinking about, never the one they just made.</p>
 */
public class MarkData extends SavedData {
    /** The file name under {@code <world>/data/}. */
    public static final String DATA_NAME = "tarkovscav_marks";

    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_MARKS = "marks";
    private static final String KEY_LETTER = "letter";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_EXPIRES = "expiresAtTick";
    private static final String KEY_PLAYERS = "players";
    private static final String KEY_PLAYER = "player";
    private static final String KEY_CURRENT = "current";

    /** Dimension -> marks, insertion ordered so "the oldest" is well defined. */
    private final Map<ResourceLocation, List<CommandMark>> marks = new LinkedHashMap<>();

    /** Player -> the letter they last selected. */
    private final Map<UUID, String> current = new HashMap<>();

    public static MarkData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MarkData::load, MarkData::new, DATA_NAME);
    }

    // ------------------------------------------------------------------ load / save

    public static MarkData load(CompoundTag tag) {
        MarkData data = new MarkData();
        ListTag dimensions = tag.getList(KEY_DIMENSIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < dimensions.size(); i++) {
            CompoundTag row = dimensions.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(row.getString(KEY_DIMENSION));
            if (dimension == null) {
                continue;
            }
            List<CommandMark> list = new ArrayList<>();
            ListTag stored = row.getList(KEY_MARKS, Tag.TAG_COMPOUND);
            for (int m = 0; m < stored.size(); m++) {
                CompoundTag entry = stored.getCompound(m);
                CommandMark.Source source = sourceOf(entry.getString(KEY_SOURCE));
                list.add(new CommandMark(entry.getString(KEY_LETTER),
                        new BlockPos(entry.getInt(KEY_X), entry.getInt(KEY_Y), entry.getInt(KEY_Z)),
                        source, entry.contains(KEY_EXPIRES) ? entry.getLong(KEY_EXPIRES)
                                : CommandMark.PERMANENT, dimension));
            }
            data.marks.put(dimension, list);
        }
        ListTag players = tag.getList(KEY_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag row = players.getCompound(i);
            if (row.hasUUID(KEY_PLAYER)) {
                data.current.put(row.getUUID(KEY_PLAYER), row.getString(KEY_CURRENT));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag dimensions = new ListTag();
        for (Map.Entry<ResourceLocation, List<CommandMark>> entry : this.marks.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString(KEY_DIMENSION, entry.getKey().toString());
            ListTag stored = new ListTag();
            for (CommandMark mark : entry.getValue()) {
                CompoundTag item = new CompoundTag();
                item.putString(KEY_LETTER, mark.letter());
                item.putInt(KEY_X, mark.pos().getX());
                item.putInt(KEY_Y, mark.pos().getY());
                item.putInt(KEY_Z, mark.pos().getZ());
                item.putString(KEY_SOURCE, mark.source().id());
                item.putLong(KEY_EXPIRES, mark.expiresAtTick());
                stored.add(item);
            }
            row.put(KEY_MARKS, stored);
            dimensions.add(row);
        }
        tag.put(KEY_DIMENSIONS, dimensions);

        ListTag players = new ListTag();
        for (Map.Entry<UUID, String> entry : this.current.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_PLAYER, entry.getKey());
            row.putString(KEY_CURRENT, entry.getValue());
            players.add(row);
        }
        tag.put(KEY_PLAYERS, players);
        return tag;
    }

    private static CommandMark.Source sourceOf(String id) {
        for (CommandMark.Source source : CommandMark.Source.values()) {
            if (source.id().equals(id)) {
                return source;
            }
        }
        return CommandMark.Source.TOOL;
    }

    // ------------------------------------------------------------------ reading

    /**
     * The live marks in one dimension, expired ones dropped as a side effect. Expiry is evaluated here
     * rather than by a ticking task, so a mark cannot lose time to a laggy server and an expired mark can
     * never be ordered to.
     */
    public List<CommandMark> live(ResourceLocation dimension, long now) {
        List<CommandMark> list = this.marks.get(dimension);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        boolean removed = list.removeIf(mark -> mark.expired(now));
        if (removed) {
            this.setDirty();
        }
        return List.copyOf(list);
    }

    /** Every live mark, all dimensions - the debug dump. */
    public Map<ResourceLocation, List<CommandMark>> liveAll(long now) {
        Map<ResourceLocation, List<CommandMark>> out = new LinkedHashMap<>();
        for (ResourceLocation dimension : new ArrayList<>(this.marks.keySet())) {
            List<CommandMark> live = live(dimension, now);
            if (!live.isEmpty()) {
                out.put(dimension, live);
            }
        }
        return out;
    }

    /** The marks in a dimension ignoring expiry - only the expiry sweep needs this. */
    public List<CommandMark> raw(ResourceLocation dimension) {
        return List.copyOf(this.marks.getOrDefault(dimension, List.of()));
    }

    /** The letters in use in one dimension (live ones only), which is what the numbering rule reads. */
    public List<String> usedLetters(ResourceLocation dimension, long now) {
        List<String> letters = new ArrayList<>();
        for (CommandMark mark : live(dimension, now)) {
            letters.add(mark.letter());
        }
        return letters;
    }

    @Nullable
    public CommandMark find(ResourceLocation dimension, String letter, long now) {
        for (CommandMark mark : live(dimension, now)) {
            if (mark.letter().equalsIgnoreCase(letter)) {
                return mark;
            }
        }
        return null;
    }

    /**
     * The mark an {@link AdvanceOrder} is still valid for: the same letter, the same block, in the same
     * dimension, and not expired. An order whose mark was removed (or whose stick ran out) fails this and
     * is cleared by {@code AdvanceOrder}.
     */
    public boolean stillExists(ResourceLocation dimension, String letter, BlockPos pos, long now) {
        CommandMark mark = find(dimension, letter, now);
        return mark != null && mark.pos().equals(pos);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Adds (or replaces) a mark and returns the one that got in.
     *
     * <p>Capacity: when the dimension already holds {@code command.maxMarks} live marks, the <b>oldest</b>
     * is dropped first. The mark being added is never the one dropped, so "I just placed this, where did
     * it go" cannot happen.</p>
     */
    public CommandMark put(CommandMark mark, long now) {
        List<CommandMark> list = this.marks.computeIfAbsent(mark.dimension(), key -> new ArrayList<>());
        list.removeIf(existing -> existing.letter().equalsIgnoreCase(mark.letter())
                || existing.expired(now));
        int cap = Math.max(1, Config.COMMAND_MAX_MARKS.get());
        while (list.size() >= cap) {
            list.remove(0);
        }
        list.add(mark);
        this.setDirty();
        return mark;
    }

    /** Removes one mark by letter. True when something was there. */
    public boolean remove(ResourceLocation dimension, String letter) {
        List<CommandMark> list = this.marks.get(dimension);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(mark -> mark.letter().equalsIgnoreCase(letter));
        if (removed) {
            this.setDirty();
        }
        return removed;
    }

    /** Removes one exact mark (the signal-point block breaking). */
    public boolean removeExact(ResourceLocation dimension, String letter, BlockPos pos) {
        List<CommandMark> list = this.marks.get(dimension);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(mark -> mark.letter().equalsIgnoreCase(letter)
                && mark.pos().equals(pos));
        if (removed) {
            this.setDirty();
        }
        return removed;
    }

    /** Empties a dimension. Returns how many marks went away. */
    public int clear(ResourceLocation dimension) {
        List<CommandMark> list = this.marks.remove(dimension);
        int removed = list == null ? 0 : list.size();
        if (removed > 0) {
            this.setDirty();
        }
        return removed;
    }

    /** Sweeps every dimension; returns how many expired marks were dropped. */
    public int prune(long now) {
        int before = 0;
        int after = 0;
        for (List<CommandMark> list : this.marks.values()) {
            before += list.size();
            list.removeIf(mark -> mark.expired(now));
            after += list.size();
        }
        if (before != after) {
            this.setDirty();
        }
        return before - after;
    }

    // ------------------------------------------------------------------ the current mark

    public String currentLetter(UUID player) {
        return this.current.get(player);
    }

    /** Sets (or clears, with null) the player's current mark. */
    public void setCurrentLetter(UUID player, @Nullable String letter) {
        if (letter == null) {
            if (this.current.remove(player) != null) {
                this.setDirty();
            }
            return;
        }
        this.current.put(player, letter);
        this.setDirty();
    }

    /**
     * The player's current mark, with the documented fallback: if the letter they had selected is gone,
     * answer with the first live mark instead of failing. Returns null only when the dimension has no
     * marks at all - which is a state the caller has to report out loud.
     */
    @Nullable
    public CommandMark currentMark(UUID player, ResourceLocation dimension, long now) {
        List<CommandMark> live = live(dimension, now);
        if (live.isEmpty()) {
            return null;
        }
        String letter = this.current.get(player);
        for (CommandMark mark : live) {
            if (mark.letter().equalsIgnoreCase(letter == null ? "" : letter)) {
                return mark;
            }
        }
        CommandMark fallback = live.get(0);
        this.setCurrentLetter(player, fallback.letter());
        return fallback;
    }

    /** The next live mark after {@code letter}, wrapping - the sneak-right-click cycle. */
    @Nullable
    public CommandMark cycle(UUID player, ResourceLocation dimension, long now) {
        List<CommandMark> live = new ArrayList<>(live(dimension, now));
        if (live.isEmpty()) {
            return null;
        }
        live.sort(Comparator.comparingInt(mark -> letterOrder(mark.letter())));
        String letter = this.current.get(player);
        int index = 0;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).letter().equalsIgnoreCase(letter == null ? "" : letter)) {
                index = (i + 1) % live.size();
                break;
            }
        }
        CommandMark next = live.get(index);
        this.setCurrentLetter(player, next.letter());
        return next;
    }

    /** A letter's position in the cycle: A=0 ... Z=25, then X1, X2, ... */
    public static int letterOrder(String letter) {
        int index = CommandMark.letterIndex(letter);
        if (index >= 0) {
            return index;
        }
        if (letter != null && letter.length() > 1 && letter.charAt(0) == 'X') {
            try {
                return 100 + Integer.parseInt(letter.substring(1));
            } catch (NumberFormatException ignored) {
                return 1000;
            }
        }
        return 2000;
    }

    /** How many marks a dimension holds, expired and live alike - diagnostics only. */
    public int rawSize(ResourceLocation dimension) {
        List<CommandMark> list = this.marks.get(dimension);
        return list == null ? 0 : list.size();
    }
}
