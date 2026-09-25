package com.gfl.tarkovscav.command;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The signal stick - the thrown half of the command system.
 *
 * <p>Right-click throws a {@link SignalStickEntity}, which plants a mark that lives
 * {@code command.stickDurationTicks} (5 minutes) and then drops the stick for pickup. The stick itself is
 * never consumed: it is a tool, so a player who wants their stick back only has to walk to the mark, and
 * one who does not leaves it there as a visible objective.</p>
 */
public class SignalStickItem extends Item {
    /** Throw speed; a touch slower than a snowball so the arc reads as a thrown object. */
    private static final float THROW_SPEED = 1.1F;

    public SignalStickItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarkovscav.signal_stick.tooltip")
                .withStyle(ChatFormatting.GRAY));
        // Guarded: a tooltip can be asked for while the config is not loaded yet (the same class of crash
        // tools/selftest_attribute_config.js was written for), and the guards value is only cosmetic.
        int seconds = com.gfl.tarkovscav.Config.SPEC.isLoaded()
                ? com.gfl.tarkovscav.Config.COMMAND_STICK_DURATION_TICKS.get() / 20 : 300;
        tooltip.add(Component.translatable("item.tarkovscav.signal_stick.tooltip2", seconds)
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        SignalStickEntity stick = new SignalStickEntity(serverLevel, player);
        stick.setItem(stack.copyWithCount(1));
        stick.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, THROW_SPEED, 1.0F);
        serverLevel.addFreshEntity(stick);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.7F);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
