package com.gfl.tarkovscav.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A spawn egg that explains itself (README 5y): the faction troops have rules the egg colour cannot show
 * (40 health, a rolled armor class, their own voice), so the tooltip says so - the same reasoning as the weapon
 * rack's tooltip manual.
 */
public class FactionSpawnEggItem extends ForgeSpawnEggItem {
    @Nullable
    private String tooltipKey;

    public FactionSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type, int background, int highlight,
                               Properties properties) {
        super(type, background, highlight, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        if (this.tooltipKey == null) {
            this.tooltipKey = getDescriptionId() + ".tooltip";
        }
        tooltip.add(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
