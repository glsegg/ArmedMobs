package com.gfl.tarkovscav.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The rack's item: the tooltip is the manual (README 5n).
 *
 * <p>A block whose rules are not obvious (sneak to take, refuse rather than swap, absorb drops) has to say so
 * somewhere a player will actually look, and the item tooltip is that place. It also prints what the rack
 * accepts, which is the answer to "only weapons, or anything?" - so the answer is in game and not only in this
 * README.</p>
 *
 * <p>The creative twin gets its own wording because the single most important thing about it is that it
 * <em>never runs out</em>: a player who does not know that will think the block is broken, and a player who
 * does not read it may report "the rack duplicated my gun" as a bug.</p>
 */
public class WeaponRackItem extends BlockItem {
    private final boolean creative;

    public WeaponRackItem(Block block, Properties properties, boolean creative) {
        super(block, properties);
        this.creative = creative;
    }

    /** @return whether this is the creative-only endless variant. */
    public boolean creative() {
        return creative;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        if (creative) {
            tooltip.add(Component.translatable("block.tarkovscav.creative_weapon_rack.tooltip.infinite")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltip.add(Component.translatable("block.tarkovscav.creative_weapon_rack.tooltip.take")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("block.tarkovscav.creative_weapon_rack.tooltip.ai")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("block.tarkovscav.creative_weapon_rack.tooltip.noRecipe")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("block.tarkovscav.weapon_rack.tooltip.absorb")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("block.tarkovscav.weapon_rack.tooltip.take")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("block.tarkovscav.weapon_rack.tooltip.ai")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("block.tarkovscav.weapon_rack.tooltip.accepts")
                .withStyle(ChatFormatting.GRAY));
    }
}
