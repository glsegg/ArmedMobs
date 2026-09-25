package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One of the three faction command tools: 村民方 / 掠夺者方 / 暴徒方.
 *
 * <h2>The right-click truth table</h2>
 * <table border="1">
 *   <caption>the first match wins; the design doc's section 4 verbatim</caption>
 *   <tr><th>Gesture</th><th>What happens</th></tr>
 *   <tr><td>right-click a BLOCK</td><td>new mark on the next free letter, becomes the player's current
 *       mark, and this tool's faction immediately advances on it</td></tr>
 *   <tr><td>sneak + right-click a BLOCK</td><td>new mark only, no order - so A/B/C/D can be laid out
 *       first and ordered one at a time</td></tr>
 *   <tr><td>right-click AIR</td><td>order this tool's faction at the player's <b>current</b> mark</td></tr>
 *   <tr><td>sneak + right-click AIR</td><td>cycle the current mark and show it in chat</td></tr>
 * </table>
 *
 * <p>{@code useOn} is the block gesture and {@code use} is the air gesture - vanilla routes them that way,
 * so the table needs no raycast of its own. Sneaking is the modifier in both cases, which is why the
 * design doc can ask for four behaviours from two callbacks.</p>
 *
 * <h2>Faction locking</h2>
 * <p>The faction is a constructor parameter and is the <b>only</b> thing that differs between the three
 * tools other than the texture and the name. Every order goes through
 * {@link CommandMarks#issue}, which selects units with {@link CommandFaction#affects} - so a village tool
 * cannot reach a pillager even if the two are standing on the same mark. There is no code path here that
 * mentions another faction.</p>
 *
 * <h2>Never a silent click</h2>
 * <p>Every branch says something: the mark it made, the unit count it ordered, "there are no marks yet",
 * or that the system is switched off.</p>
 */
public class CommandToolItem extends Item {
    private final CommandFaction faction;

    public CommandToolItem(CommandFaction faction, Properties properties) {
        super(properties);
        this.faction = faction;
    }

    /** The faction this tool commands. */
    public CommandFaction faction() {
        return this.faction;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarkovscav.command_tool.tooltip", this.faction.id())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.command_tool.tooltip2",
                this.faction.tagId().toString()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.tarkovscav.command_tool.tooltip3")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------ right-click a block

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!Config.SPEC.isLoaded() || !Config.COMMAND_ENABLED.get()) {
            serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.disabled")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.FAIL;
        }
        BlockPos pos = context.getClickedPos();
        long now = serverLevel.getGameTime();
        CommandMark mark = CommandMarks.create(serverLevel, pos, CommandMark.Source.TOOL, now);
        CommandMarks.select(serverPlayer, mark);

        if (player.isShiftKeyDown()) {
            // Mark only: the player is laying out A/B/C/D and will order them one at a time.
            serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.mark.created",
                    mark.chatLine()).withStyle(ChatFormatting.AQUA), false);
            return InteractionResult.CONSUME;
        }
        CommandMarks.issue(serverLevel, this.faction, mark, now, serverPlayer);
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ right-click the air

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!Config.SPEC.isLoaded() || !Config.COMMAND_ENABLED.get()) {
            serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.disabled")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResultHolder.fail(stack);
        }
        long now = serverLevel.getGameTime();

        if (player.isShiftKeyDown()) {
            // Cycle the current mark: "current target: B (x, y, z)".
            CommandMark next = CommandMarks.cycle(serverPlayer, now);
            if (next == null) {
                serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.no.marks")
                        .withStyle(ChatFormatting.YELLOW), false);
                return InteractionResultHolder.fail(stack);
            }
            serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.current",
                    next.chatLine()).withStyle(ChatFormatting.AQUA), false);
            return InteractionResultHolder.success(stack);
        }

        CommandMark current = CommandMarks.current(serverPlayer, now);
        if (current == null) {
            serverPlayer.displayClientMessage(Component.translatable("tarkovscav.command.no.marks")
                    .withStyle(ChatFormatting.YELLOW), false);
            return InteractionResultHolder.fail(stack);
        }
        CommandMarks.issue(serverLevel, this.faction, current, now, serverPlayer);
        return InteractionResultHolder.success(stack);
    }
}
