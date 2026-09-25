package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Delayed assertions for {@code /tarkovscav test watch} and {@code /tarkovscav test stall}.
 *
 * <p><b>Why this class exists instead of {@code server.tell(new TickTask(tick + delay, task))}.</b>
 * The obvious one-liner does not delay anything in 1.20.1: {@code ReentrantBlockableEventLoop} decides
 * whether a queued {@code TickTask} may run with {@code shouldRun}, whose body is
 * {@code task.getTick() + 3 < this.tickCount ? true : this.haveTime()} - so a task stamped hundreds of
 * ticks into the future runs as soon as the current tick still has time left, which is normally the
 * case, i.e. immediately. That is measured, not assumed: the first version of this harness logged
 * "WATCH FAIL after 20s" on the same log second as the setup. A tick counter ticking down on the server
 * tick event is both correct and readable in the log.</p>
 */
public final class FightHarness {
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(Mob subject, int dueTick, Runnable task) {
    }

    private FightHarness() {
    }

    /**
     * Runs {@code task} after {@code delayTicks} server ticks.
     *
     * @param subject the entity the assertion is about; if it is gone by then the task is dropped with
     *                a warning rather than reporting numbers about a mob that no longer exists
     */
    public static void schedule(MinecraftServer server, Mob subject, int delayTicks, Runnable task) {
        synchronized (PENDING) {
            PENDING.add(new Pending(subject, server.getTickCount() + delayTicks, task));
        }
    }

    /**
     * Runs {@code task} after {@code delayTicks} with no subject to watch - used by the sound-test
     * command, which is about the command source's position rather than a mob.
     */
    public static void schedule(MinecraftServer server, int delayTicks, Runnable task) {
        synchronized (PENDING) {
            PENDING.add(new Pending(null, server.getTickCount() + delayTicks, task));
        }
    }

    /** How many assertions are waiting - the debug line uses this. */
    public static int pending() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        synchronized (PENDING) {
            Iterator<Pending> iterator = PENDING.iterator();
            while (iterator.hasNext()) {
                Pending pending = iterator.next();
                if (pending.subject() != null && pending.subject().isRemoved()) {
                    iterator.remove();
                    TarkovScav.LOGGER.warn("[test] the harness mob was removed before its assertion ran"
                            + " - dropping the pending /tarkovscav test report");
                    continue;
                }
                if (event.getServer().getTickCount() >= pending.dueTick()) {
                    iterator.remove();
                    due.add(pending.task());
                }
            }
        }
        due.forEach(Runnable::run);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }
}
