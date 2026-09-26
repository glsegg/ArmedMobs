package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** HUD state belongs to the current client world, independently of any input feature. */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientHudEvents {
    private static ClientLevel world;

    private ClientHudEvents() { }

    static boolean ensureWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != world || minecraft.player == null) {
            clear();
            world = minecraft.level;
        }
        return minecraft.level != null && minecraft.player != null;
    }

    private static void clear() {
        KillFeedHud.clear();
        CaptureHud.clear();
        FlashOverlay.clear();
        RenderStats.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        world = null;
        HudLayout.beginFrame(0, 0);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ensureWorld()) return;
        Minecraft minecraft = Minecraft.getInstance();
        // Disabling a feature discards its pending state even while the integrated game is paused.
        KillFeedHud.clearIfDisabled();
        CaptureHud.clearIfDisabled();
        FlashOverlay.clearIfDisabled();
        if (minecraft.isPaused()) return;
        KillFeedHud.tick();
        CaptureHud.tick();
        FlashOverlay.tick();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        ensureWorld();
        HudLayout.beginFrame(event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight());
        reserveScoreboard(event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        Minecraft minecraft = Minecraft.getInstance();
        int nameWidth = minecraft.font.width(event.getBossEvent().getName());
        int barWidth = Math.max(182, nameWidth);
        HudLayout.reserve(new HudLayout.Rect(event.getX() + 91 - barWidth / 2,
                event.getY() - minecraft.font.lineHeight, barWidth, minecraft.font.lineHeight + 5));
    }

    private static void reserveScoreboard(int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) return;
        var scoreboard = minecraft.level.getScoreboard();
        var team = scoreboard.getPlayersTeam(minecraft.player.getScoreboardName());
        var objective = team != null && team.getColor().getId() >= 0
                ? scoreboard.getDisplayObjective(3 + team.getColor().getId()) : null;
        if (objective == null) objective = scoreboard.getDisplayObjective(1);
        if (objective == null) return;
        var scores = scoreboard.getPlayerScores(objective).stream()
                .filter(score -> score.getOwner() != null && !score.getOwner().startsWith("#")).toList();
        if (scores.isEmpty()) return;
        int count = Math.min(15, scores.size());
        int panelWidth = minecraft.font.width(objective.getDisplayName());
        for (var score : scores.subList(scores.size() - count, scores.size())) {
            var name = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(score.getOwner()),
                    Component.literal(score.getOwner()));
            panelWidth = Math.max(panelWidth, minecraft.font.width(name) + minecraft.font.width(": ")
                    + minecraft.font.width(Integer.toString(score.getScore())));
        }
        // Gui.displayScoreboardSidebar uses nine-pixel rows and a ten-pixel title in 1.20.1.
        int rowsHeight = count * 9;
        int end = height / 2 + rowsHeight / 3;
        HudLayout.reserve(new HudLayout.Rect(width - panelWidth - 5, end - rowsHeight - 10,
                panelWidth + 4, rowsHeight + 10));
    }
}
