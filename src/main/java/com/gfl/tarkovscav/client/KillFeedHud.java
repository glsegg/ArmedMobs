package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.killfeed.KillFeedNetwork;
import com.gfl.tarkovscav.killfeed.KillFeedSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The kill feed HUD (README 5u): the FPS-style lines at the top of the screen.
 *
 * <h2>The layout decision</h2>
 * <p>New lines go in at the <b>top</b> and the older ones are pushed down: the newest kill is always in the
 * same place, which is the thing a player actually reads. The block hangs from the top edge of the screen, its
 * horizontal anchor comes from {@code killFeed.position} ({@code top_center} by default), and the oldest line is
 * dropped once {@code maxLines} are on screen.</p>
 *
 * <h2>Nothing here trusts the wire</h2>
 * <p>The two names are plain strings from the server and are <b>truncated to the available width</b> rather
 * than wrapped, so a 300-character name cannot push the block off the screen or cover the crosshair. The weapon
 * is drawn from the item stack the server sent (so a TaCZ gun shows its real, translated name) or, for a
 * category with no item, from this client's own translation of that category.</p>
 *
 * <p>It respects F1 ({@code options.hideGui}) and {@code killFeed.enabled}, and a category the client does not
 * know is drawn as "unknown weapon" instead of being skipped.</p>
 */
public final class KillFeedHud implements IGuiOverlay {
    public static final KillFeedHud INSTANCE = new KillFeedHud();

    /** How long the fade-in takes, and how much of the end of a line's life is its fade-out. */
    private static final int FADE_IN_TICKS = 4;
    private static final int FADE_OUT_TICKS = 12;
    /** The colour of a name for a faction that has none (the environment, an unknown mob). */
    private static final int GREY = 0xAAAAAA;
    private static final int WHITE = 0xFFFFFF;
    private static final int YELLOW = 0xFFE066;
    private static final int WEAPON_GREY = 0xB0B0B0;
    /** Lines are truncated to this share of the screen width, so a long name stays readable and centred. */
    private static final float MAX_WIDTH_SHARE = 0.9F;

    private static final List<Line> LINES = new ArrayList<>();

    private KillFeedHud() {
    }

    /** One line: two names, a weapon and how long it has been on screen. */
    public static final class Line {
        private final String killer;
        private final String victim;
        private final ItemStack weapon;
        private final KillFeedSource source;
        private final int killerColor;
        private final int victimColor;
        private int age;

        Line(String killer, String victim, ItemStack weapon, KillFeedSource source, int killerColor,
             int victimColor) {
            this.killer = killer;
            this.victim = victim;
            this.weapon = weapon;
            this.source = source;
            this.killerColor = killerColor;
            this.victimColor = victimColor;
        }

        /** For the gate and the test command: the weapon text as it will be drawn. */
        public Component weaponText() {
            if (this.source == KillFeedSource.ITEM && !this.weapon.isEmpty()) {
                return this.weapon.getHoverName();
            }
            return this.source.text();
        }

        public String killer() {
            return this.killer;
        }

        public String victim() {
            return this.victim;
        }

        public int age() {
            return this.age;
        }
    }

    /** Server -&gt; client: one line arrived. */
    public static void accept(KillFeedNetwork.KillFeedMessage message) {
        if (!ClientHudEvents.ensureWorld() || clearIfDisabled()) {
            return;
        }
        add(message.killer(), message.victim(), message.weapon(), message.source());
    }

    /** Also used by {@code /tarkovscav test killfeed}: the preview path needs no server round trip. */
    public static void add(String killer, String victim, ItemStack weapon, KillFeedSource source) {
        if (!ClientHudEvents.ensureWorld() || clearIfDisabled()) return;
        // The colours are resolved ONCE, here: looking an entity up by name every frame for every line would be
        // a per-frame scan of the entity list for no reason.
        LINES.add(0, new Line(killer, victim, weapon.copy(), source, colorOf(killer), colorOf(victim)));
        int max = Config.SPEC.isLoaded() ? Config.KILLFEED_MAX_LINES.get() : 5;
        while (LINES.size() > max) {
            LINES.remove(LINES.size() - 1);
        }
    }

    /** For the test command: how many lines are on screen right now. */
    public static int lineCount() {
        return LINES.size();
    }

    /** For the test command and the gates. */
    public static void clear() {
        LINES.clear();
    }

    /** The names and ages, for {@code /tarkovscav test killfeed} and the gate. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Line line : LINES) {
            out.add(String.format(Locale.ROOT, "%s [%s] %s (age %d)",
                    line.killer().isEmpty() ? "Environment" : line.killer(),
                    line.weaponText().getString(), line.victim(), line.age()));
        }
        return out;
    }

    static boolean clearIfDisabled() {
        if (!Config.SPEC.isLoaded() || !Config.KILLFEED_ENABLED.get()) {
            clear();
            return true;
        }
        return false;
    }

    /** Client tick: age the lines and drop the expired ones. Called by {@link ClientHudEvents}. */
    public static void tick() {
        if (clearIfDisabled() || LINES.isEmpty()) {
            return;
        }
        int duration = Config.SPEC.isLoaded() ? Config.KILLFEED_LINE_DURATION_TICKS.get() : 100;
        LINES.removeIf(line -> ++line.age > duration);
        int max = Config.KILLFEED_MAX_LINES.get();
        if (LINES.size() > max) LINES.subList(max, LINES.size()).clear();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics, float partialTick,
                       int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (clearIfDisabled() || LINES.isEmpty()
                || minecraft.options.hideGui || minecraft.player == null || minecraft.level == null) {
            return;
        }
        Font font = minecraft.font;
        double scale = Config.KILLFEED_SCALE.get();
        int duration = Math.max(1, Config.KILLFEED_LINE_DURATION_TICKS.get());
        int lineHeight = (int) Math.ceil((font.lineHeight + 2) * scale);
        int allowed = Math.max(0, (int) ((width * MAX_WIDTH_SHARE - 8) / scale));
        List<FormattedText> texts = new ArrayList<>(LINES.size());
        int textWidth = 0;
        for (Line entry : LINES) {
            FormattedText text = trimStyled(font, line(entry), allowed);
            texts.add(text);
            textWidth = Math.max(textWidth, font.width(text));
        }
        HudLayout.Panel panel = HudLayout.allocate(position(), (int) Math.ceil(textWidth * scale) + 8,
                lineHeight, LINES.size(), 1);
        if (panel == null) return;
        for (int index = 0; index < panel.rows(); index++) {
            Line line = LINES.get(index);
            int alpha = alphaOf(line.age(), duration);
            if (alpha <= 0) {
                continue;
            }
            FormattedText text = texts.get(index);
            // The allocated coordinates are GUI pixels. Translation happens before scaling so row
            // spacing and screen margins are scaled exactly once.
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(panel.bounds().left() + 4,
                        panel.bounds().top() + 2 + index * lineHeight, 0);
                graphics.pose().scale((float) scale, (float) scale, 1.0F);
                graphics.fill(-1, -1, font.width(text) + 1, font.lineHeight, (alpha / 3) << 24);
                graphics.drawString(font, Language.getInstance().getVisualOrder(text), 0, 0,
                        withAlpha(WHITE, alpha), true);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    /** The line's text, colour-coded by faction so the sides can be told apart at a glance. */
    private static MutableComponent line(Line line) {
        String killerName = line.killer().isEmpty() ? Component.translatable("tarkovscav.killfeed.environment")
                .getString() : line.killer();
        MutableComponent text = Component.literal(killerName)
                .withStyle(style -> style.withColor(line.killerColor));
        text.append(Component.literal("  [").withStyle(ChatFormatting.DARK_GRAY));
        text.append(line.weaponText().copy().withStyle(style -> style.withColor(WEAPON_GREY)));
        text.append(Component.literal("]  ").withStyle(ChatFormatting.DARK_GRAY));
        text.append(Component.literal(line.victim()).withStyle(style -> style.withColor(line.victimColor)));
        return text;
    }

    /**
     * The colour of a name, found by matching it against the entities the client can see (once per line, when
     * the line arrives). Players are white/yellow, our units are red, villagers are green and a name we cannot
     * place (the environment, a mob that has already despawned) is grey - the same idea as the faction layer,
     * so the feed reads like the rest of the mod.
     */
    static int colorOf(String name) {
        if (name == null || name.isEmpty()) {
            return GREY;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.getDisplayName().getString().equals(name)) {
                    return colorOf(entity);
                }
            }
        }
        return WHITE;
    }

    /** The colour of an entity, by faction (used by the test command with a real entity too). */
    public static int colorOf(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return YELLOW;
        }
        Faction faction = Faction.of(entity);
        if (faction == null) {
            return GREY;
        }
        return switch (faction) {
            case SCAV, ILLAGER -> 0xFF6B6B;
            case VILLAGE -> 0x7BE07B;
        };
    }

    private static String position() {
        if (!Config.SPEC.isLoaded()) {
            return "top_center";
        }
        String raw = Config.KILLFEED_POSITION.get().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "top_left", "top_right" -> raw;
            default -> "top_center";
        };
    }

    /** Fade in at the start, fade out at the end, full opacity in between. */
    static int alphaOf(int age, int duration) {
        int fadeOut = Math.min(FADE_OUT_TICKS, Math.max(1, duration / 4));
        if (age < FADE_IN_TICKS) {
            return clampAlpha(255 * age / Math.max(1, FADE_IN_TICKS));
        }
        int remaining = duration - age;
        if (remaining < fadeOut) {
            return clampAlpha(255 * remaining / Math.max(1, fadeOut));
        }
        return 255;
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    private static int withAlpha(int rgb, int alpha) {
        return (clampAlpha(alpha) << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Truncate to a pixel width, appending an ellipsis. This is the "long names are cut, never wrapped"
     * rule: the block must not grow a second line and must not run off the screen.
     */
    static String trimToWidth(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    private static FormattedText trimStyled(Font font, FormattedText text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        int room = maxWidth - font.width("...");
        if (room <= 0) return font.substrByWidth(text, Math.max(0, maxWidth));
        return FormattedText.composite(font.substrByWidth(text, room), Component.literal("..."));
    }
}
