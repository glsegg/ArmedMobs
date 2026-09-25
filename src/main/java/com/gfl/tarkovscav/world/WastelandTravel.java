package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single way into and out of {@code tarkovscav:urban_wasteland}: the 40-tick deployment cast and the
 * safe-landing teleport.
 *
 * <p>Both entry points call this class and <b>the same</b> landing code, so the operator command and the
 * deployment beacon item cannot drift apart:</p>
 * <ul>
 *   <li>{@link #teleportInto(ServerPlayer)} - the wasteland, keeping the player's own X/Z grid square.</li>
 *   <li>{@link #teleportBack(ServerPlayer)} - the remembered overworld anchor, or the overworld's shared
 *       spawn when there is none (and it says so).</li>
 *   <li>{@link #teleportTo(ServerPlayer, ResourceLocation, int, int, boolean)} - the one implementation
 *       both of the above are thin wrappers around; the command calls it directly for a named
 *       dimension.</li>
 * </ul>
 *
 * <h2>The cast rule (what is implemented, exactly)</h2>
 * <p>Right-clicking the beacon starts a {@value #CAST_TICKS}-tick cast (2 s) on the vanilla item-use
 * channel, so holding the button does not restart it. During the cast a warning sound and dust particles
 * play, and the cast is aborted - with a message - when any of these happens:</p>
 * <ul>
 *   <li>the player takes any damage ({@link LivingHurtEvent});</li>
 *   <li>the player moves more than {@value #MAX_CAST_DRIFT} blocks from the cast position (measured
 *       centre to centre, checked every tick);</li>
 *   <li>the player releases right-click early (vanilla's {@code releaseUsing});</li>
 *   <li>the player changes dimension or disconnects.</li>
 * </ul>
 * <p>Only the overworld and the wasteland are legal: the overworld starts a deployment, the wasteland
 * starts the return trip, anywhere else is refused. The cooldown is
 * {@value #COOLDOWN_TICKS} ticks (10 s) between arrivals and the item is never consumed.</p>
 *
 * <h2>Why the item is not consumed and nothing is client-side</h2>
 * <p>Every decision here is made on the server from the player's real position; the client only mirrors
 * the standard "am I using an item" flag, which is what every vanilla chargeable item does. That is why
 * the same code answers {@code /armedmobs dimension} and a right-click with no divergence.</p>
 */
public final class WastelandTravel {
    /** The wasteland dimension, also the id the datapack registers. */
    public static final ResourceLocation WASTELAND = new ResourceLocation(TarkovScav.MOD_ID, "urban_wasteland");
    /** The overworld, the only dimension the beacon can be deployed FROM. */
    public static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    /** Length of the deployment cast, in ticks (2 s). */
    public static final int CAST_TICKS = 40;
    /** Cooldown between two arrivals, in ticks (10 s). */
    public static final int COOLDOWN_TICKS = 200;
    /** How far the player may drift from the cast position, in blocks. */
    public static final double MAX_CAST_DRIFT = 4.0D;
    /** The arrival platform is (2 * this + 1) squared; 2 gives the 5x5 pad in the brief. */
    public static final int PLATFORM_RADIUS = 2;

    /** Which way a running cast will go. */
    public enum Target {
        INTO_WASTELAND,
        BACK_TO_OVERWORLD
    }

    /** Why a cast could not start. NONE means it did. */
    public enum Refusal {
        NONE,
        NOT_A_PLAYER,
        WRONG_DIMENSION,
        COOLDOWN,
        ALREADY_CASTING,
        NO_SUCH_DIMENSION
    }

    /** Where a teleport landed, and what had to be built to make it safe. */
    public record Arrival(ResourceLocation dimension, BlockPos pos, boolean platform, boolean fallback) {
    }

    private record Cast(ServerLevel level, BlockPos origin, Target target, int dueTick) {
    }

    private static final Map<UUID, Cast> CASTS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private WastelandTravel() {
    }

    // ------------------------------------------------------------------ the cast

    /** The direction a cast would take from this player's current dimension, or null for "not here". */
    @Nullable
    public static Target targetFor(ServerPlayer player) {
        ResourceLocation here = player.level().dimension().location();
        if (here.equals(OVERWORLD)) {
            return Target.INTO_WASTELAND;
        }
        if (here.equals(WASTELAND)) {
            return Target.BACK_TO_OVERWORLD;
        }
        return null;
    }

    /** True when the beacon does something in this dimension (the overworld and the wasteland). */
    public static boolean isDeploymentDimension(ResourceLocation dimension) {
        return dimension.equals(OVERWORLD) || dimension.equals(WASTELAND);
    }

    /** True while this player is mid-cast. */
    public static boolean isCasting(ServerPlayer player) {
        return CASTS.containsKey(player.getUUID());
    }

    /** Seconds left on the cooldown, or 0 when the player may deploy again. */
    public static int cooldownSeconds(ServerPlayer player) {
        Long until = COOLDOWN_UNTIL.get(player.getUUID());
        if (until == null) {
            return 0;
        }
        long left = until - player.level().getGameTime();
        return left <= 0L ? 0 : (int) Math.ceil(left / 20.0D);
    }

    /**
     * The one place a {@link Refusal} becomes a player-visible sentence, so the beacon item and the
     * {@code /armedmobs dimension} command cannot word the same refusal differently. Every key here
     * exists in both {@code en_us.json} and {@code zh_cn.json}.
     */
    public static Component refusalMessage(Refusal refusal) {
        return switch (refusal) {
            case NONE -> Component.empty();
            case NOT_A_PLAYER -> Component.translatable("tarkovscav.beacon.refused.player");
            case WRONG_DIMENSION -> Component.translatable("tarkovscav.beacon.refused.dimension",
                    WASTELAND.toString(), OVERWORLD.toString());
            case COOLDOWN -> Component.translatable("tarkovscav.beacon.refused.cooldown", COOLDOWN_TICKS / 20);
            case ALREADY_CASTING -> Component.translatable("tarkovscav.beacon.refused.casting");
            case NO_SUCH_DIMENSION -> Component.translatable("tarkovscav.beacon.refused.missing");
        };
    }

    /** Starts the 2 s deployment cast, or says why it cannot start. */
    public static Refusal beginCast(ServerPlayer player) {
        Target target = targetFor(player);
        if (target == null) {
            return Refusal.WRONG_DIMENSION;
        }
        UUID id = player.getUUID();
        if (CASTS.containsKey(id)) {
            return Refusal.ALREADY_CASTING;
        }
        int cooldown = cooldownSeconds(player);
        if (cooldown > 0) {
            return Refusal.COOLDOWN;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Refusal.NO_SUCH_DIMENSION;
        }
        CASTS.put(id, new Cast(serverLevel(player), player.blockPosition().immutable(), target,
                server.getTickCount() + CAST_TICKS));
        ServerLevel level = serverLevel(player);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 1.0F, 1.35F);
        player.displayClientMessage(Component.translatable("tarkovscav.beacon.cast.start",
                CAST_TICKS / 20, (int) MAX_CAST_DRIFT).withStyle(ChatFormatting.AQUA), false);
        TarkovScav.LOGGER.info("[wasteland] {} started a {} deployment cast at {}",
                player.getName().getString(), target, player.blockPosition().toShortString());
        return Refusal.NONE;
    }

    /** One tick of the cast: dust, and the "moved too far" abort. Called from the item's use tick. */
    public static void castTick(ServerPlayer player) {
        Cast cast = CASTS.get(player.getUUID());
        if (cast == null) {
            return;
        }
        if (player.level() != cast.level() || !player.isAlive()) {
            abort(player, "tarkovscav.beacon.cast.cancelled");
            return;
        }
        if (player.position().distanceTo(Vec3.atBottomCenterOf(cast.origin())) > MAX_CAST_DRIFT) {
            abort(player, "tarkovscav.beacon.cast.moved");
            return;
        }
        if (player.tickCount % 4 == 0) {
            ServerLevel level = cast.level();
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    player.getX(), player.getY() + 0.2D, player.getZ(), 4, 0.5D, 0.15D, 0.5D, 0.005D);
            level.sendParticles(ParticleTypes.CLOUD,
                    player.getX(), player.getY() + 1.4D, player.getZ(), 2, 0.4D, 0.1D, 0.4D, 0.01D);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_AMBIENT,
                    SoundSource.PLAYERS, 0.35F, 1.6F);
        }
    }

    /** The cast ran its full 2 s: make the trip. */
    public static void completeCast(ServerPlayer player) {
        Cast cast = CASTS.remove(player.getUUID());
        if (cast == null) {
            return;
        }
        COOLDOWN_UNTIL.put(player.getUUID(), player.level().getGameTime() + COOLDOWN_TICKS);
        ServerLevel level = serverLevel(player);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 0.8F);
        Arrival arrival = cast.target() == Target.INTO_WASTELAND ? teleportInto(player) : teleportBack(player);
        if (arrival == null) {
            // The dimension is not loaded on this server: the datapack is missing or was refused.
            player.displayClientMessage(refusalMessage(Refusal.NO_SUCH_DIMENSION)
                    .copy().withStyle(ChatFormatting.RED), false);
        }
    }

    /** The player let go (or something else ended the use) before the cast finished. */
    public static void abandonCast(ServerPlayer player) {
        if (!CASTS.containsKey(player.getUUID())) {
            return;
        }
        abort(player, "tarkovscav.beacon.cast.cancelled");
    }

    /**
     * Damage aborts the cast. The cast is removed BEFORE {@code stopUsingItem()} on purpose: vanilla may
     * or may not route that through {@code releaseUsing}, and removing it first makes the interrupt
     * message the only one the player sees either way.
     */
    public static void interrupt(ServerPlayer player) {
        if (!CASTS.containsKey(player.getUUID())) {
            return;
        }
        abort(player, "tarkovscav.beacon.cast.hurt");
    }

    private static void abort(ServerPlayer player, String messageKey) {
        if (CASTS.remove(player.getUUID()) == null) {
            return;
        }
        ServerLevel level = serverLevel(player);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.9F, 1.2F);
        player.displayClientMessage(Component.translatable(messageKey).withStyle(ChatFormatting.YELLOW), false);
    }

    // ------------------------------------------------------------------ the shared way in and out

    /** The wasteland, at the player's own X/Z: "deploy straight down this grid square". */
    @Nullable
    public static Arrival teleportInto(ServerPlayer player) {
        return teleportTo(player, WASTELAND, player.getBlockX(), player.getBlockZ(), true);
    }

    /** Back to the remembered overworld anchor, or the overworld shared spawn when there is none. */
    @Nullable
    public static Arrival teleportBack(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        RetreatData.Spot spot = RetreatData.get(server).last(player.getUUID());
        if (spot != null) {
            return teleportTo(player, spot.dimension(), spot.pos().getX(), spot.pos().getZ(), false);
        }
        ServerLevel overworld = resolveLevel(server, OVERWORLD);
        if (overworld == null) {
            return null;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        Arrival arrival = teleportTo(player, OVERWORLD, spawn.getX(), spawn.getZ(), false);
        if (arrival != null) {
            player.displayClientMessage(Component.translatable("tarkovscav.beacon.return.fallback",
                    spawn.toShortString()).withStyle(ChatFormatting.YELLOW), false);
            return new Arrival(arrival.dimension(), arrival.pos(), arrival.platform(), true);
        }
        return null;
    }

    /**
     * The one teleport implementation. Keeps the target column's X/Z, finds the highest motion-blocking
     * height, makes sure the player has two clear cells and solid ground, builds the arrival platform
     * when there is none, then moves the player and prints where they went.
     *
     * @param remember when true, and the player is currently in the overworld, this spot becomes their
     *                 return anchor
     * @return null when the dimension does not exist on this server
     */
    @Nullable
    public static Arrival teleportTo(ServerPlayer player, ResourceLocation dimensionId, int x, int z,
                                     boolean remember) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel target = resolveLevel(server, dimensionId);
        if (target == null) {
            return null;
        }
        ServerLevel from = serverLevel(player);
        if (remember && from.dimension().location().equals(OVERWORLD)) {
            RetreatData.get(server).remember(player.getUUID(), OVERWORLD, player.blockPosition());
        }
        boolean[] built = { false };
        BlockPos landing = safeLanding(target, x, z, built);
        player.teleportTo(target, landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.displayClientMessage(Component.translatable("tarkovscav.beacon.arrived",
                dimensionId.toString(), landing.toShortString()).withStyle(ChatFormatting.GREEN), false);
        if (built[0]) {
            player.displayClientMessage(Component.translatable("tarkovscav.beacon.arrived.platform")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        TarkovScav.LOGGER.info("[wasteland] {} arrived in {} at {} (platform placed: {})",
                player.getName().getString(), dimensionId, landing.toShortString(), built[0]);
        return new Arrival(dimensionId, landing, built[0], false);
    }

    /**
     * The landing rule. The motion-blocking heightmap gives the first air cell above the terrain; the
     * search then walks up while the cell or the one above it is not clear (a plant, a fence, a wall),
     * and finally checks that the cell below can actually be stood on. When it cannot, a 5x5
     * stone-brick pad with a torch is built so nobody arrives inside a hole.
     *
     * <p><b>The target chunk is forced to {@code FULL} before the heightmap is read.</b> A heightmap query
     * on a chunk that does not exist yet answers with the minimum build height, and the user arrived at
     * y=-56 UNDER the wasteland because of exactly that. If the height is still at/below the world floor
     * after the chunk exists (a modded dimension with an unusual generator), a downward scan finds the
     * highest standable cell instead, so a teleport can never land below the terrain.</p>
     */
    public static BlockPos safeLanding(ServerLevel level, int x, int z, boolean[] platformPlaced) {
        // 1. the column must exist before it can be measured (see the javadoc above)
        level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        int minY = level.getMinBuildHeight();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (surface <= minY + 1) {
            surface = scanDownForGround(level, x, z, level.getMaxBuildHeight() - 1, minY);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surface, z);
        for (int step = 0; step < 8 && !isClear(level, pos); step++) {
            pos.move(Direction.UP);
        }
        if (!isStandable(level, pos.below())) {
            buildPlatform(level, pos);
            if (platformPlaced != null && platformPlaced.length > 0) {
                platformPlaced[0] = true;
            }
        }
        return pos.immutable();
    }

    /**
     * The fallback when the heightmap has nothing to say: the highest y in {@code (minY, top]} whose block
     * is standable and whose two cells above are open. Returns {@code minY + 1} when the column is empty,
     * which the caller then turns into a platform - never a negative y.
     */
    private static int scanDownForGround(ServerLevel level, int x, int z, int top, int minY) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = top; y > minY; y--) {
            probe.setY(y);
            if (isStandable(level, probe) && isOpen(level, probe.above()) && isOpen(level, probe.above(2))) {
                return y + 1;
            }
        }
        return minY + 1;
    }

    /** Two cells the player can occupy without suffocating. */
    private static boolean isClear(ServerLevel level, BlockPos pos) {
        return isOpen(level, pos) && isOpen(level, pos.above());
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * A 5x5 stone-brick pad one block under the landing cell, the 5x5x2 box above it cleared, and a
     * torch on a corner. Only the cells that are air or replaceable become bricks, so the pad never
     * carves into terrain that is already there.
     */
    public static void buildPlatform(ServerLevel level, BlockPos centre) {
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                BlockPos floor = centre.offset(dx, -1, dz);
                BlockState existing = level.getBlockState(floor);
                if (existing.isAir() || existing.canBeReplaced()) {
                    level.setBlock(floor, bricks, 3);
                }
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos head = centre.offset(dx, dy, dz);
                    if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                        level.setBlock(head, air, 3);
                    }
                }
            }
        }
        BlockPos torch = centre.offset(PLATFORM_RADIUS, 0, PLATFORM_RADIUS);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), 3);
    }

    /** The ServerLevel of a server player - the cast is only ever built from one. */
    public static ServerLevel serverLevel(ServerPlayer player) {
        return (ServerLevel) player.level();
    }

    /** A level by dimension id, or null when this server has no such dimension. */
    @Nullable
    public static ServerLevel resolveLevel(MinecraftServer server, ResourceLocation dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ events

    /** Stale casts and expired cooldowns are dropped here; the cast itself is driven by the item tick. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int now = event.getServer().getTickCount();
        // A cast finishes at exactly dueTick, from the item's finishUsingItem. This is only the safety
        // net for a cast whose use channel died silently (a desynced client), so it waits a whole extra
        // cast length before dropping one - never racing the real completion.
        CASTS.entrySet().removeIf(entry -> now > entry.getValue().dueTick() + CAST_TICKS);
        long gameTime = event.getServer().overworld().getGameTime();
        COOLDOWN_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
    }

    /** Taking damage aborts a running cast: you cannot deploy out of a losing fight. */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isCasting(player)) {
            interrupt(player);
        }
    }

    /** A cast does not survive a disconnect. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CASTS.remove(player.getUUID());
        }
    }

    /** A cast does not survive a dimension change either. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CASTS.remove(player.getUUID());
        }
    }
}
