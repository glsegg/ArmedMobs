package com.gfl.tarkovscav.item;

import com.gfl.tarkovscav.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The VANT ballistic shield (Chinese: the four characters in the lang file): a held, passive
 * front-facing gunfire shield.
 *
 * <p>This class is only the shell plus the tooltip. Every rule - which damage counts, the front cone,
 * the 99 % reduction, the per-hit durability charge and the shatter at zero - lives in
 * {@link com.gfl.tarkovscav.combat.VantShieldHandler}, which is one Forge handler for ANY living
 * entity, not a player-only branch in here. That split is deliberate: the rule has to work for an
 * armed mob that picks one up too, and two copies of it would drift.</p>
 *
 * <h2>No raise state in this batch</h2>
 * <p>The shield protects while it is held in the MAIN or OFF hand - that is the whole rule. There is
 * no right-click "raise" state, no use animation and no per-tick state machine; a later batch can add
 * one (a use channel that raises the shield, with the arc checked only while raised) without moving
 * the maths, because {@code VantShieldHandler.insideCone} is a pure function of two vectors.</p>
 *
 * <p>The durability bar is the vanilla one: {@code durability(500)} in the registry call, charged by
 * the handler when a hit is actually blocked.</p>
 */
public class VantShieldItem extends Item {
    public VantShieldItem(Properties properties) {
        super(properties);
    }

    /**
     * Four lines: what it does, the two numbers that matter (read from the live config, so a server
     * that tunes them sees the tuned values in the tooltip), what it does NOT stop, and that there is
     * no raise action to look for.
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        // A tooltip is drawn by whoever asks (the inventory today, a recipe viewer tomorrow), so the
        // config is only read once it is loaded - the same guard CommandToolItem and SignalStickItem use.
        boolean loaded = Config.SPEC.isLoaded();
        double reduction = loaded
                ? Math.max(0.0D, Math.min(1.0D, Config.SHIELD_BULLET_REDUCTION.get()))
                : 0.99D;
        double halfAngle = loaded
                ? Math.max(0.0D, Math.min(180.0D, Config.SHIELD_FRONT_ANGLE_DEGREES.get()))
                : 90.0D;
        int percent = (int) Math.round(reduction * 100.0D);
        int coneDegrees = (int) Math.round(halfAngle * 2.0D);
        tooltip.add(Component.translatable("item.tarkovscav.vant_shield.tooltip")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.vant_shield.tooltip2", percent, coneDegrees)
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.vant_shield.tooltip3", stack.getMaxDamage())
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.vant_shield.tooltip4")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
