package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.world.CaptureHudNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * The city-capture strength bars (README 7p): one thin bar per faction, top centre of the screen.
 *
 * <h2>What this class is, and what it is not</h2>
 * <p>It is <b>rendering only</b>. The server sends a city name and, per faction, the current and opening
 * strength plus whether the pool is spent ({@link CaptureHudNetwork}) - so the whole look lives here, needs
 * no resource pack, and cannot influence who wins. A change of colour or bar width is a client-side edit with
 * no gameplay effect, which is the design's "server only syncs numbers" rule.</p>
 *
 * <h2>The hide rules, and why there are two of them</h2>
 * <ul>
 *   <li><b>Immediate</b>: a city stops being contested the moment one pool is spent, and the design says the
 *       bars then vanish at once. The server sends {@code hide = true} for that city and the client clears.
 *       The client ALSO refuses to draw a one-bar state at all, so even a malformed or stale packet cannot
 *       leave a lone bar on the screen.</li>
 *   <li><b>Delayed</b>: a player who simply walks out of the city stops receiving packets, so the last state
 *       ages out after {@code capture.hudHideDelaySeconds}. That countdown is client-side because there is
 *       nothing to send.</li>
 * </ul>
 *
 * <h2>The wasteland rule, enforced twice</h2>
 * <p>The server never builds a pool outside the overworld, so it never sends a bar for the wasteland
 * ({@code CityCapture.isOverworld}). The client checks the dimension of the level it is actually in as well,
 * so "never in the wasteland" does not depend on a server behaving. And {@code capture.hudEnabled = false}
 * hides it entirely here and stops the traffic on the server side too.</p>
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID,
        bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class CaptureHud implements IGuiOverlay {
    public static final CaptureHud INSTANCE = new CaptureHud();

    /** The thin bar: 120 px wide and 4 px tall, so it reads as a bar and never covers the crosshair. */
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 4;
    /** The gap between one faction's row and the next. */
    private static final int ROW_GAP = 5;
    /** Distance from the top edge of the screen. */
    private static final int TOP = 4;

    private static final int VILLAGE_COLOUR = 0x7BE07B;
    private static final int ILLAGER_COLOUR = 0xFF6B6B;
    private static final int NEUTRAL_COLOUR = 0xFFFFFF;
    /** The spent bar and its label: grey, per the design's "greyed and labelled as captured". */
    private static final int CAPTURED_COLOUR = 0x9A9A9A;
    private static final int TRACK_COLOUR = 0xFF202020;
    private static final int PLATE_COLOUR = 0x66000000;

    /** What the server last told this client, plus how long ago. Null means "nothing to draw". */
    private static State state;

    private CaptureHud() {
    }

    /** One synced city: its identity, its bars, and how many client ticks have passed since the sync. */
    private static final class State {
        private final String cityKey;
        private final String cityName;
        private final List<CaptureHudNetwork.Bar> bars;
        private int age;

        private State(String cityKey, String cityName, List<CaptureHudNetwork.Bar> bars) {
            this.cityKey = cityKey;
            this.cityName = cityName;
            this.bars = bars;
        }
    }

    /** Server -&gt; client: the current state of one city, or a hide. */
    public static void accept(CaptureHudNetwork.CaptureHudMessage message) {
        if (!Config.SPEC.isLoaded() || !Config.CAPTURE_HUD_ENABLED.get()) {
            clear();
            return;
        }
        if (message.hide()) {
            // Only a hide for the city currently on screen may clear it: a second contested city being
            // captured elsewhere is not this player's business.
            if (state == null || state.cityKey.equals(message.cityKey())) {
                clear();
            }
            return;
        }
        if (message.bars().size() < 2) {
            // A one-faction city has nothing to contest, and never had a HUD. Treated as a clear so a stale
            // two-bar state cannot survive into it.
            clear();
            return;
        }
        state = new State(message.cityKey(), message.cityName(), message.bars());
    }

    /** For the gate and any future test command: what is on screen right now. */
    public static List<CaptureHudNetwork.Bar> bars() {
        return state == null ? List.of() : state.bars;
    }

    /** For the gate and any future test command. */
    public static String cityKey() {
        return state == null ? null : state.cityKey;
    }

    /** For the gate and any future test command. */
    public static String cityName() {
        return state == null ? null : state.cityName;
    }

    /** Drops the bars now (the delayed-hide timer expiring, a disable, or a hide packet). */
    public static void clear() {
        state = null;
    }

    /**
     * Client tick: age the last sync. Registered through this class's own {@code @EventBusSubscriber}, so a
     * player who walks out of a city sees the bars fade on the configured delay without the server having to
     * say anything.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || state == null) {
            return;
        }
        int delaySeconds = Config.SPEC.isLoaded() ? Math.max(0, Config.CAPTURE_HUD_HIDE_DELAY_SECONDS.get()) : 8;
        if (++state.age > delaySeconds * 20) {
            state = null;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        State current = state;
        if (current == null || current.bars.size() < 2) {
            return;
        }
        if (!Config.SPEC.isLoaded() || !Config.CAPTURE_HUD_ENABLED.get()
                || minecraft.options.hideGui || minecraft.player == null || minecraft.level == null) {
            return;
        }
        // The wasteland never shows a capture HUD; the server does not send one, and the client does not
        // draw one either.
        if (!minecraft.level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        Font font = minecraft.font;
        int rows = current.bars.size();
        int rowHeight = font.lineHeight + BAR_HEIGHT + ROW_GAP;
        int plateWidth = 0;
        for (CaptureHudNetwork.Bar bar : current.bars) {
            plateWidth = Math.max(plateWidth, Math.max(font.width(label(bar)), BAR_WIDTH));
        }
        int left = Math.max(2, (width - plateWidth) / 2);
        int top = TOP;
        graphics.fill(left - 3, top - 2, left + plateWidth + 3, top + rows * rowHeight, PLATE_COLOUR);

        int y = top;
        for (CaptureHudNetwork.Bar bar : current.bars) {
            int colour = bar.captured() ? CAPTURED_COLOUR : colourOf(bar.faction());
            graphics.drawString(font, label(bar), left, y, colour, true);
            int barY = y + font.lineHeight + 1;
            graphics.fill(left, barY, left + BAR_WIDTH, barY + BAR_HEIGHT, TRACK_COLOUR);
            int filled = 0;
            if (bar.max() > 0 && bar.strength() > 0) {
                filled = (int) Math.round(BAR_WIDTH * (double) bar.strength() / (double) bar.max());
                filled = Math.max(1, Math.min(BAR_WIDTH, filled));
            }
            if (filled > 0) {
                graphics.fill(left, barY, left + filled, barY + BAR_HEIGHT, colour);
            }
            y += rowHeight;
        }
    }

    /**
     * The label: the faction, "current/opening", and the captured word. The faction names are drawn from
     * literals rather than translation keys on purpose - the server owns the two possible factions and the
     * two words are part of the mod's own vocabulary, so this needs no shipped lang entry to be readable.
     */
    static String label(CaptureHudNetwork.Bar bar) {
        return factionName(bar.faction()) + "  " + bar.strength() + "/" + bar.max()
                + (bar.captured() ? "  captured" : "");
    }

    /** "village" -&gt; "Village"; anything unexpected is shown as the server sent it. */
    static String factionName(String faction) {
        if (faction == null || faction.isEmpty()) {
            return "?";
        }
        return Character.toUpperCase(faction.charAt(0)) + faction.substring(1);
    }

    /** The bar colour by faction; an unknown name gets white rather than a wrong side's colour. */
    static int colourOf(String faction) {
        if (faction == null) {
            return NEUTRAL_COLOUR;
        }
        return switch (faction) {
            case "village" -> VILLAGE_COLOUR;
            case "illager" -> ILLAGER_COLOUR;
            default -> NEUTRAL_COLOUR;
        };
    }
}
