package com.gfl.tarkovscav.item;

import com.gfl.tarkovscav.world.WastelandTravel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The deployment beacon (Chinese: the four characters in the lang file): the primary way into
 * {@code tarkovscav:urban_wasteland}, and the way back out.
 *
 * <p>This class is only the item shell. Every rule - the 2 s cast, the "you moved, the cast broke"
 * cancel, the safe landing, the arrival platform, the return anchor and the cooldown - lives in
 * {@link WastelandTravel}, which the {@code /armedmobs dimension} command uses too. That is deliberate:
 * a second copy of the landing logic in here is exactly how the two paths would drift apart.</p>
 *
 * <h2>How the cast rides the vanilla use channel</h2>
 * <p>{@code getUseDuration} is the cast length, so right-click starts one continuous 2 s use:
 * {@code use} begins the cast, {@code onUseTick} drives the dust and the move check,
 * {@code finishUsingItem} makes the trip and {@code releaseUsing} means "let go early, cancel".
 * Holding the button therefore cannot restart the cast, which is why the item never spams the
 * already-deploying message.</p>
 *
 * <p>The client mirrors only the part it can know (is this a deployment dimension), so the use
 * animation appears where it will actually do something; the server still decides everything and the
 * item is never consumed.</p>
 */
public class DeploymentBeaconItem extends Item {
    public DeploymentBeaconItem(Properties properties) {
        super(properties);
    }

    /** The tooltip is the manual: where it works, how long the cast is, and how to come back. */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarkovscav.deployment_beacon.tooltip")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.deployment_beacon.tooltip2",
                WastelandTravel.CAST_TICKS / 20, WastelandTravel.COOLDOWN_TICKS / 20)
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return WastelandTravel.CAST_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // The spyglass pose is the closest vanilla "hold it up and wait" animation.
        return UseAnim.SPYGLASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // The client only knows the dimension, so that is all it mirrors. The server validates the
            // cooldown and the rest, and its refusal is what the player is told.
            if (WastelandTravel.isDeploymentDimension(level.dimension().location())) {
                player.startUsingItem(hand);
                return InteractionResultHolder.consume(stack);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        WastelandTravel.Refusal refusal = WastelandTravel.beginCast(serverPlayer);
        if (refusal != WastelandTravel.Refusal.NONE) {
            serverPlayer.displayClientMessage(WastelandTravel.refusalMessage(refusal)
                    .copy().withStyle(ChatFormatting.RED), false);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        WastelandTravel.castTick(player);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            WastelandTravel.completeCast(player);
        }
        // A tool, not a charge: the stack always comes back unchanged.
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        WastelandTravel.abandonCast(player);
    }
}
