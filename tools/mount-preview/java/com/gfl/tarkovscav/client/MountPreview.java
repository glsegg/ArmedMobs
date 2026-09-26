package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunUser;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Opt-in real-renderer contact sheets; compiled only by tools/mount-preview.gradle. */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, value = Dist.CLIENT)
public final class MountPreview {
    private static boolean started;
    @SubscribeEvent
    public static void ready(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!started && event.phase == TickEvent.Phase.END
                && System.getProperty("armedmobs.mountPreview") != null
                && mc.screen instanceof ConfirmScreen
                && mc.screen.getTitle().getString().equals(Component.translatable("selectWorld.backupQuestion.experimental").getString())) {
            // Only our disposable calibration-world copy is opened by this opt-in source set.
            for (var widget : mc.screen.children()) {
                if (widget instanceof Button button && button.getMessage().getString().equals(CommonComponents.GUI_PROCEED.getString())) {
                    TarkovScav.LOGGER.info("[mount-preview] confirming experimental world COPY");
                    button.onPress();
                    break;
                }
            }
        }
        if (!started && event.phase == TickEvent.Phase.END && mc.level != null && mc.player != null
                && mc.player.tickCount > 40 && System.getProperty("armedmobs.mountPreview") != null) {
            started = true;
            mc.options.guiScale().set(1);
            mc.resizeDisplay();
            mc.setScreen(new PreviewScreen());
        }
    }

    private static final class PreviewScreen extends Screen {
        private static final List<String> TYPES = Arrays.asList(System.getProperty("armedmobs.mountPreviewTypes").split(","));
        private static final List<String> POSES = Arrays.asList(System.getProperty("armedmobs.mountPreviewPoses", "hold,aim").split(","));
        private final List<String> guns = Arrays.asList(System.getProperty("armedmobs.mountPreviewGuns").split(","));
        private final String mode = System.getProperty("armedmobs.mountPreview");
        private final List<Mob> mobs = new ArrayList<>();
        private int sheet;
        private int age;
        private int renderedAge;
        private boolean finished;
        PreviewScreen() { super(Component.literal("Armed Mobs gun mount audit")); }
        @Override protected void init() {
            if (mobs.isEmpty()) loadSheet();
        }
        private void loadSheet() {
            mobs.clear();
            String id = guns.get(sheet / POSES.size());
            String pose = POSES.get(sheet % POSES.size());
            boolean aim = pose.equals("aim");
            boolean fire = pose.equals("fire");
            boolean reload = pose.equals("reload");
            boolean retreat = pose.equals("retreat");
            var gunId = new ResourceLocation(id);
            boolean pistol = Config.usesPistolClips(TimelessAPI.getCommonGunIndex(gunId).orElseThrow().getType());
            for (String type : TYPES) {
                Mob mob = (Mob) ForgeRegistries.ENTITY_TYPES.getValue(TarkovScav.id(type)).create(minecraft.level);
                mob.setId(600000 + sheet * 20 + mobs.size());
                mob.setNoAi(true);
                mob.setLeftHanded(Boolean.getBoolean("armedmobs.mountPreviewLeft"));
                mob.setItemSlot(EquipmentSlot.MAINHAND, GunItemBuilder.create().setId(gunId).build());
                GunUser user = (GunUser) mob;
                user.setPistolClips(pistol);
                user.setGunAiState(aim ? GunAiState.AIM : fire ? GunAiState.FIRE : reload ? GunAiState.RELOAD : retreat ? GunAiState.RETREAT : GunAiState.IDLE);
                user.setGunPose(aim || fire || retreat || reload, fire, reload);
                mob.yBodyRot = mob.yBodyRotO = 155;
                mob.yHeadRot = mob.yHeadRotO = 155 + Float.parseFloat(System.getProperty("armedmobs.mountPreviewYaw", "0"));
                mob.setXRot(Float.parseFloat(System.getProperty("armedmobs.mountPreviewPitch", "0")));
                mob.xRotO = mob.getXRot();
                mob.setYRot(155);
                mob.yRotO = 155;
                mobs.add(mob);
            }
            age = 0;
            renderedAge = -1;
            TarkovScav.LOGGER.info("[mount-preview] sheet {} {} {}", sheet, id, pose);
        }
        @Override public void tick() {
            if (finished) return;
            age++;
            for (Mob mob : mobs) mob.tickCount++;
            if (renderedAge >= 35) {
                sheet++;
                if (sheet >= guns.size() * POSES.size()) {
                    finished = true;
                    TarkovScav.LOGGER.info("[mount-preview] COMPLETE {} sheets", sheet);
                    minecraft.stop();
                } else loadSheet();
            }
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (finished) return;
            graphics.fill(0, 0, width, height, 0xffb9c6d3);
            graphics.drawString(font, mode + " | " + guns.get(sheet / POSES.size()) + " | " + POSES.get(sheet % POSES.size()), 12, 8, 0xff101820, false);
            int cellWidth = width / 3;
            int cellHeight = (height - 30) / ((mobs.size() + 2) / 3);
            for (int i = 0; i < mobs.size(); i++) {
                int x = (i % 3) * cellWidth;
                int y = 30 + (i / 3) * cellHeight;
                graphics.fill(x + 3, y + 3, x + cellWidth - 3, y + cellHeight - 3, 0xffd5dee6);
                graphics.drawString(font, TYPES.get(i), x + 10, y + 10, 0xff101820, false);
                InventoryScreen.renderEntityInInventory(graphics, x + cellWidth / 2, y + cellHeight - 22,
                        Math.min(cellWidth / 2, cellHeight / 3), Axis.ZP.rotationDegrees(180).mul(Axis.YP.rotationDegrees(
                                Float.parseFloat(System.getProperty("armedmobs.mountPreviewCameraYaw", "25")))), new Quaternionf(), mobs.get(i));
            }
            graphics.flush();
            if (age >= 35 && renderedAge < 35) {
                try {
                    Path folder = minecraft.gameDirectory.toPath().toAbsolutePath().normalize().getParent().resolve("images").resolve(mode);
                    Files.createDirectories(folder);
                    String name = guns.get(sheet / POSES.size()).replace(':', '_') + ("-" + POSES.get(sheet % POSES.size()) + ".png");
                    try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                        image.writeToFile(folder.resolve(name));
                    }
                    TarkovScav.LOGGER.info("[mount-preview] SAVED {}", name);
                } catch (Exception error) {
                    TarkovScav.LOGGER.error("[mount-preview] screenshot failed", error);
                    finished = true;
                    minecraft.stop();
                }
                renderedAge = age;
            }
        }
    }
}
