package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A throwable (README 5v): hold right-click to charge, release to throw.
 *
 * <p>The charge decides the speed ({@code minThrowSpeed} → {@code maxThrowSpeed} over
 * {@code throwChargeTicks}), and - because the fuse starts burning the moment it leaves your hand - cooking is
 * not a separate feature: hold it longer and it goes off sooner after landing, hold it too long and it goes off
 * in your hand. That falls out of the arithmetic rather than being special-cased, which is why the README only
 * has to explain it once.</p>
 */
public class GrenadeItem extends Item {
    private final GrenadeKind kind;

    public GrenadeItem(GrenadeKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public GrenadeKind kind() {
        return this.kind;
    }

    /** The tooltip is the manual: what this one does, and how to throw it (README 5v). */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, java.util.List<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable(this.kind.tooltipKey()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.grenade.charge")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // The bow animation is the closest vanilla pose to "holding something ready to throw".
        return UseAnim.BOW;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!Config.GRENADES_ENABLED.get()) {
            // Completely inert when switched off: no charge, no fuse, no throw.
            return InteractionResultHolder.pass(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        int used = this.getUseDuration(stack) - remaining;
        // A quiet click every 4 ticks while it is held, so "cooking" is audible.
        if (used > 0 && used % 4 == 0 && !level.isClientSide) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.LEVER_CLICK,
                    SoundSource.PLAYERS, 0.3F, 1.6F);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player) || level.isClientSide || !Config.GRENADES_ENABLED.get()) {
            return;
        }
        int used = this.getUseDuration(stack) - timeLeft;
        int chargeTicks = Math.max(1, Config.GRENADES_THROW_CHARGE_TICKS.get());
        float charge = Math.min(1.0F, (float) used / chargeTicks);
        double speed = Config.GRENADES_MIN_THROW_SPEED.get()
                + (Config.GRENADES_MAX_THROW_SPEED.get() - Config.GRENADES_MIN_THROW_SPEED.get()) * charge;
        int fuse = this.kind.fuseTicks();
        if (Config.GRENADES_COOK_WHILE_HOLDING.get()) {
            fuse -= used;
        }
        GrenadeEntity grenade = new GrenadeEntity(level, player, this.kind, Math.max(2, fuse));
        grenade.setPos(player.getX(), player.getEyeY() - 0.15D, player.getZ());
        Vec3 look = player.getLookAngle();
        grenade.shoot(look.x, look.y + 0.05D, look.z, (float) speed, 1.0F);
        if (!level.addFreshEntity(grenade)) {
            grenade.discard();
            return;
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS, 0.8F, 0.7F);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }
}
