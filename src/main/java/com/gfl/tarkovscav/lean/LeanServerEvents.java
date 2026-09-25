package com.gfl.tarkovscav.lean;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The server side of the player lean (README 5s): where a leaning player's shots come out, and the guard that
 * keeps Q from dropping an item while a lean key is held.
 *
 * <h2>Shots: the spawn is moved, the direction is not</h2>
 * <p>Every projectile the game adds to the level passes through {@link EntityJoinLevelEvent}, and TaCZ's
 * {@code EntityKineticBullet} <b>is</b> a {@code Projectile} (verified against the shipped jar: it extends
 * {@code net.minecraft.world.entity.projectile.Projectile}), so one hook covers guns, bows and crossbows
 * without touching TaCZ or the vanilla bow code at all. The spawn position is translated by exactly the same
 * {@link LeanMath#offsetFor} the camera uses, clipped by {@link LeanMath#slide} so it cannot move through a
 * wall; the velocity is left alone, which is what makes the shot follow the shifted crosshair (see
 * {@code LeanMath}'s class comment for the parallel-ray geometry).</p>
 *
 * <p><b>Deliberately server-only.</b> The client also spawns a local copy of its own bullet for the tracer,
 * and TaCZ reconciles the two; shifting both would double the offset for whichever copy carries the server's
 * spawn data, and shifting only the client's would put the tracer where the damage is not. The cost is that
 * the shooter's own tracer can start at the un-shifted muzzle for the fraction of a tick before the server's
 * copy arrives, while everyone else (and the damage) is shifted from the first frame. That is a visual
 * nit on one screen versus a duplicated-offset bug on every screen.</p>
 *
 * <h2>The item-toss guard, and why it re-adds the stack</h2>
 * <p>Q is the vanilla drop key, so the client-side suppression ({@code LeanClient}) already consumes the
 * press before it becomes an action. This guard is the belt-and-braces for any path that still gets a toss
 * through. Forge fires {@link ItemTossEvent} from {@code Player#drop(ItemStack, boolean, boolean)} with the
 * {@link ItemEntity} already built and the stack already taken out of the inventory, so cancelling it
 * <b>destroys</b> the stack unless it is put back - hence the explicit re-add, and the container fallback for
 * a full inventory (which must NOT go through {@code Player#drop}, or the event would fire again forever).</p>
 */
public final class LeanServerEvents {
    private LeanServerEvents() {
    }

    /** The shot-origin rule: a leaning player's projectile starts at the leaned muzzle. */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!Config.LEAN_ENABLED.get() || event.getLevel().isClientSide() || event.loadedFromDisk()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Projectile projectile)) {
            return;
        }
        if (!(projectile.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        float lean = LeanState.leanOf(player);
        if (lean == 0.0F) {
            return;
        }
        Vec3 wanted = LeanMath.offsetFor(player.getYRot(), lean, Config.LEAN_MAX_OFFSET.get());
        Vec3 offset = LeanMath.slide(event.getLevel(), projectile.position(), wanted);
        if (offset.lengthSqr() < 1.0E-8D) {
            return;
        }
        entity.setPos(entity.getX() + offset.x, entity.getY() + offset.y, entity.getZ() + offset.z);
        TarkovScav.LOGGER.info("[lean] {} shot from the lean: projectile {} spawned {} block(s) off centre",
                player.getName().getString(),
                entity.getType().toShortString(),
                String.format(java.util.Locale.ROOT, "%.2f", offset.length()));
    }

    /** Belt and braces for the drop key: never let a leaning player's item leave the inventory. */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!Config.LEAN_ENABLED.get() || !Config.LEAN_SUPPRESS_VANILLA_KEYS.get()) {
            return;
        }
        Player player = event.getPlayer();
        // HOLDING, not the lean amount (README 5t): a tap releases the key, so the vanilla drop we replay on
        // release arrives while holding == false and is never cancelled. A toss that happens WHILE the key is
        // held is an accident (or a leftover) and gets cancelled and given back.
        if (!LeanState.holding(player)) {
            return;
        }
        ItemEntity dropped = event.getEntity();
        ItemStack stack = dropped.getItem().copy();
        event.setCanceled(true);
        // See the class comment: cancel() alone would delete the stack.
        if (!player.getInventory().add(stack)) {
            Containers.dropItemStack(player.level(), dropped.getX(), dropped.getY(), dropped.getZ(), stack);
        }
        TarkovScav.LOGGER.info("[lean] {} kept {} while leaning (the toss was suppressed)",
                player.getName().getString(), stack.getHoverName().getString());
    }

    /** A player who disconnects while leaning must not leave the value behind. */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LeanState.clear(event.getEntity().getUUID());
    }
}
