package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * The flashbang white-out (README 5v): a full-screen white overlay that fades from the intensity the server
 * decided down to nothing over the ticks it decided.
 *
 * <p>The server owns the decision (distance, line of sight, whether you were looking at it, whether you threw
 * it yourself); this class only draws it. The fade is linear in time on purpose: a flashbang that dims on a
 * curve reads as "the game lagged", while a straight ramp reads as "my eyes are recovering".</p>
 *
 * <p>It respects F1 like every other overlay, and it is skipped entirely when the grenade feature is off.</p>
 */
public final class FlashOverlay implements IGuiOverlay {
    public static final FlashOverlay INSTANCE = new FlashOverlay();

    private static double intensity;
    private static int ticksLeft;
    private static int totalTicks;

    private FlashOverlay() {
    }

    /** Server -&gt; client: a flash arrived. A stronger or longer flash replaces a weaker one. */
    public static void accept(double newIntensity, int ticks) {
        if (!ClientHudEvents.ensureWorld() || clearIfDisabled()
                || !Double.isFinite(newIntensity) || newIntensity <= 0.0D || ticks <= 0) {
            return;
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, newIntensity));
        double current = intensity * ((double) ticksLeft / Math.max(1, totalTicks));
        if (clamped >= current || ticks >= ticksLeft) {
            // A second flash starts from the brightness remaining now, not the original peak of an
            // almost-recovered flash. Otherwise even a weak flash can restore a full white-out.
            intensity = Math.max(current, clamped);
            ticksLeft = Math.max(ticksLeft, ticks);
            totalTicks = Math.max(1, ticksLeft);
        }
    }

    /** Client tick: run the recovery down. */
    public static void tick() {
        if (clearIfDisabled() || ticksLeft <= 0) {
            return;
        }
        ticksLeft--;
        if (ticksLeft <= 0) {
            intensity = 0.0D;
            totalTicks = 0;
        }
    }

    /** For the debug output and the gate. */
    public static double intensity() {
        return intensity;
    }

    /** How many ticks of white are left. */
    public static int ticksLeft() {
        return ticksLeft;
    }

    public static void clear() {
        intensity = 0.0D;
        ticksLeft = 0;
        totalTicks = 0;
    }

    static boolean clearIfDisabled() {
        if (!Config.SPEC.isLoaded() || !Config.GRENADES_ENABLED.get()) {
            clear();
            return true;
        }
        return false;
    }

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics, float partialTick,
                       int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (clearIfDisabled() || ticksLeft <= 0 || minecraft.player == null || minecraft.level == null
                || minecraft.options.hideGui) {
            return;
        }
        double alpha = intensity * ((double) ticksLeft / Math.max(1, totalTicks));
        int argb = ((int) Math.round(Math.min(1.0D, alpha) * 255.0D) << 24) | 0xFFFFFF;
        graphics.fill(0, 0, width, height, argb);
    }

    /** The one-line state for {@code /tarkovscav client state}. */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "flash intensity=%.2f ticksLeft=%d", intensity, ticksLeft);
    }
}
