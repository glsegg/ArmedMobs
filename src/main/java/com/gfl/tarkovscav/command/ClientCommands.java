package com.gfl.tarkovscav.command;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.client.PoseSource;
import com.gfl.tarkovscav.client.PoseWriters;
import com.gfl.tarkovscav.client.RigSupport;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /tarkovscav client ...} - the client-side tuning and diagnostic commands.
 *
 * <p>These are a <b>client</b> command tree on purpose: the model bones, the mount transform and the
 * config file all live on the client, and a vanilla server cannot reach them. That also means they
 * work in single player and on any server without asking the server operator for anything.</p>
 *
 * <ul>
 *   <li>{@code client reload} - re-read {@code config/tarkovscav-common.toml} from disk and re-apply
 *       everything (hidden bones, head accessories, head pitch) on the next frame. No restart, no
 *       resource reload.</li>
 *   <li>{@code client gunpose ...} - the live tuner for the held gun's transform. Applies on the next
 *       frame to every mob using the rig, prints the exact toml line and writes it to the config, so
 *       the tuning survives a restart.</li>
 *   <li>{@code client state} - what is actually in effect right now: the merged hidden-bone set with a
 *       reason per bone, the head pitch and its states, and the mount transform of both weapon
 *       families.</li>
 *   <li>{@code client hide <bone>} / {@code client show <bone>} - the bisect tool: hide one candidate
 *       at a time to find which bone is responsible for something that looks wrong.</li>
 *   <li>{@code client pose auto|code|clips} and {@code client pose molang on|off} - the one-keystroke
 *       A/B for who owns the aim pose bones, and whether the rig's own Molang aim variables are fed.
 *       {@code client.logPoseWriters} prints the writer of every pose bone per frame.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientCommands {
    private ClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // Both root literals, exactly like the server-side ModCommands tree: the display name is
        // "Armed Mobs" (/armedmobs) and /tarkovscav stays for the unchanged mod id.
        dispatcher.register(tree("tarkovscav"));
        dispatcher.register(tree("armedmobs"));
    }

    /** The client-side tree for one root literal: {@code /tarkovscav ...} or {@code /armedmobs ...}. */
    private static LiteralArgumentBuilder<CommandSourceStack> tree(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .then(Commands.literal("client")
                        .then(Commands.literal("reload").executes(ClientCommands::reload))
                        .then(Commands.literal("state").executes(ClientCommands::state))
                        .then(Commands.literal("hide")
                                .then(Commands.argument("bone", StringArgumentType.word())
                                        .executes(context -> hide(context, true))))
                        .then(Commands.literal("show")
                                .then(Commands.argument("bone", StringArgumentType.word())
                                        .executes(context -> hide(context, false))))
                        .then(Commands.literal("gunpose")
                                .executes(context -> gunPose(context, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> gunPose(context,
                                                StringArgumentType.getString(context, "args")))))
                        .then(Commands.literal("scale")
                                .executes(context -> scale(context, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> scale(context,
                                                StringArgumentType.getString(context, "args")))))
                        .then(Commands.literal("villagerpose")
                                .executes(context -> villagerPose(context, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> villagerPose(context,
                                                StringArgumentType.getString(context, "args")))))
                        .then(Commands.literal("pose")
                                .executes(context -> pose(context, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> pose(context,
                                                StringArgumentType.getString(context, "args"))))))
                // README 5u: preview the kill feed without killing anybody - the layout, the colours, the
                // truncation and the line limit are all visible this way, which is what "confirm it by eye"
                // needs.
                .then(Commands.literal("test")
                        .then(Commands.literal("killfeed")
                                .executes(context -> testKillFeed(context, "", "", ""))
                                .then(Commands.argument("killer", StringArgumentType.string())
                                        .executes(context -> testKillFeed(context,
                                                StringArgumentType.getString(context, "killer"), "", ""))
                                        .then(Commands.argument("victim", StringArgumentType.string())
                                                .executes(context -> testKillFeed(context,
                                                        StringArgumentType.getString(context, "killer"),
                                                        StringArgumentType.getString(context, "victim"), ""))
                                                .then(Commands.argument("weapon",
                                                                StringArgumentType.string())
                                                        .executes(context -> testKillFeed(context,
                                                                StringArgumentType.getString(context, "killer"),
                                                                StringArgumentType.getString(context, "victim"),
                                                                StringArgumentType.getString(context,
                                                                        "weapon"))))))));
    }

    // ------------------------------------------------------------------ kill feed preview

    /**
     * {@code /tarkovscav test killfeed [killer] [victim] [weapon]}: push one line into the HUD right now. A
     * client-side command on purpose - the preview must not need a server, and it must not kill anything.
     *
     * <p>The weapon argument takes an item id ({@code minecraft:stone_sword}), a category
     * ({@code fists|explosion|fall|environment|unknown}) or nothing (fists). An unknown id falls back to the
     * "unknown weapon" text, which is also what the server sends when it cannot name a kill.</p>
     */
    private static int testKillFeed(CommandContext<CommandSourceStack> context, String killer, String victim,
                                    String weapon) {
        CommandSourceStack source = context.getSource();
        Minecraft minecraft = Minecraft.getInstance();
        String killerName = killer.isEmpty() ? "Ge_SiLa" : killer;
        String victimName = victim.isEmpty() ? "Armed Mobs" : victim;
        String weaponArg = weapon.isEmpty() ? "fists" : weapon;
        com.gfl.tarkovscav.killfeed.KillFeedSource category =
                com.gfl.tarkovscav.killfeed.KillFeedSource.FISTS;
        net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(weaponArg);
        net.minecraft.world.item.Item item = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            category = com.gfl.tarkovscav.killfeed.KillFeedSource.ITEM;
            stack = new net.minecraft.world.item.ItemStack(item);
        } else {
            for (com.gfl.tarkovscav.killfeed.KillFeedSource candidate
                    : com.gfl.tarkovscav.killfeed.KillFeedSource.values()) {
                if (candidate.id().equalsIgnoreCase(weaponArg)) {
                    category = candidate;
                    break;
                }
            }
        }
        com.gfl.tarkovscav.client.KillFeedHud.add(killerName, victimName, stack, category);
        int lines = com.gfl.tarkovscav.client.KillFeedHud.lineCount();
        source.sendSuccess(() -> Component.literal("killfeed: " + killerName + " [" + weaponArg + "] "
                + victimName + "  -> " + lines + " line(s), maxLines="
                + (Config.SPEC.isLoaded() ? Config.KILLFEED_MAX_LINES.get() : 0)
                + " position=" + (Config.SPEC.isLoaded() ? Config.KILLFEED_POSITION.get() : "?")
                + " scale=" + (Config.SPEC.isLoaded() ? Config.KILLFEED_SCALE.get() : 0.0D)
                + " enabled=" + (Config.SPEC.isLoaded() && Config.KILLFEED_ENABLED.get()))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    // ------------------------------------------------------------------ client scale

    /**
     * The live model-size tuner: {@code /tarkovscav client scale 0.77}, {@code ... scale up},
     * {@code ... scale down 0.02}, and the villager family's own key via
     * {@code /tarkovscav client scale villager 1.0}.
     *
     * <p><b>Why this command had to exist.</b> The renderers used to read {@code client.renderScale} in
     * their <em>constructor</em>, so the only way to try another size was F3+T or a restart - which is
     * exactly what "can I adjust it live?" is about. The scale is now read on every render (see
     * {@code ScavRenderer#scale} and friends), so writing the config is enough: the change shows up on the
     * next frame, and it is saved to the toml so it survives.</p>
     *
     * <p><b>Two keys, two families</b> (README 5b): {@code client.renderScale} is the Bedrock rig's size
     * (authored at 0.7) and {@code client.villagerRenderScale} is the villager family's size (authored at
     * 1.0 = vanilla). A bare {@code scale <value>} keeps its old meaning - the rig - so an existing habit is
     * not broken; the villager key is reached with the {@code villager} prefix.</p>
     *
     * <p>Both notations are accepted: an absolute value ({@code 1.1}) and a relative step
     * ({@code up}/{@code down}, default step 0.05). Values are clamped to
     * {@code 0.3 .. 2.0} and a non-number is refused with a message rather than silently ignored.</p>
     */
    private static int scale(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSourceStack source = context.getSource();
        String token = rawArgs == null ? "" : rawArgs.trim();
        String[] parts = token.isEmpty() ? new String[0] : token.split("\\s+");

        // "villager" (or "villagers") selects the villager family's key; everything else is the rig's, exactly
        // as before.
        boolean villager = parts.length > 0
                && (parts[0].equalsIgnoreCase("villager") || parts[0].equalsIgnoreCase("villagers"));
        float current = villager ? Config.villagerRenderScale() : Config.renderScale();
        String key = villager ? "villagerRenderScale" : "renderScale";

        if (parts.length == 0 || (villager && parts.length == 1)) {
            final String shownKey = key;
            final float shownCurrent = current;
            source.sendSuccess(() -> Component.literal("client." + shownKey + " = " + trim(shownCurrent)
                    + "   (rig: client.renderScale = " + trim(Config.renderScale())
                    + " [" + trim(Config.renderScale() / (float) Config.DEFAULT_RENDER_SCALE)
                    + "x the rig baseline], villager: client.villagerRenderScale = "
                    + trim(Config.villagerRenderScale()) + " [1.0 = vanilla villager size])"), false);
            source.sendSuccess(() -> Component.literal("usage: /tarkovscav client scale <value>|up|down [step]"
                    + "   |   /tarkovscav client scale villager <value>|up|down [step]")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 1;
        }

        String action = villager ? parts[1] : parts[0];
        String stepToken = villager ? (parts.length > 2 ? parts[2] : null)
                : (parts.length > 1 ? parts[1] : null);
        float next;
        double step = Config.RENDER_SCALE_STEP;
        if (stepToken != null) {
            try {
                step = Double.parseDouble(stepToken);
            } catch (NumberFormatException notANumber) {
                source.sendFailure(Component.literal("'" + stepToken + "' is not a number for the step."));
                return 0;
            }
        }
        if (action.equalsIgnoreCase("up") || action.equalsIgnoreCase("down")) {
            double delta = action.equalsIgnoreCase("up") ? step : -step;
            next = (float) (current + delta);
        } else {
            try {
                next = (float) Double.parseDouble(action);
            } catch (NumberFormatException notANumber) {
                source.sendFailure(Component.literal("Could not read '" + action
                        + "' as a size. Usage: /tarkovscav client scale [villager] <value>|up|down [step]"));
                return 0;
            }
        }
        if (Float.isNaN(next) || Float.isInfinite(next)) {
            source.sendFailure(Component.literal("That is not a usable size."));
            return 0;
        }
        float clamped = (float) Math.max(Config.RENDER_SCALE_MIN, Math.min(Config.RENDER_SCALE_MAX, next));
        if (clamped != next) {
            source.sendSuccess(() -> Component.literal("clamped to " + trim(clamped)
                    + " (allowed " + Config.RENDER_SCALE_MIN + " .. " + Config.RENDER_SCALE_MAX + ")"), false);
        }
        if (villager) {
            Config.VILLAGER_RENDER_SCALE.set((double) clamped);
        } else {
            Config.RENDER_SCALE.set((double) clamped);
        }
        Config.SPEC.save();
        final float applied = clamped;
        final boolean shownVillager = villager;
        source.sendSuccess(() -> Component.literal("client." + key + " = " + trim(applied)
                + (shownVillager
                        ? " (1.0 = vanilla villager size, applies next frame)"
                        : " (visible height x" + trim(applied / (float) Config.DEFAULT_RENDER_SCALE)
                                + ", applies next frame)")
                ).withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Written to config/tarkovscav-common.toml:")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  " + key + " = " + trim(applied))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        TarkovScav.LOGGER.info("[client] {} -> {} (the other scale is untouched: rig {} / villager {})",
                key, trim(applied), trim(Config.renderScale()), trim(Config.villagerRenderScale()));
        return 1;
    }

    // ------------------------------------------------------------------ client villagerpose

    /**
     * The live tuner for the gunner villager's gun transform (README 5j).
     *
     * <pre>
     * /tarkovscav client villagerpose                            - print the current values
     * /tarkovscav client villagerpose back=0.05                  - semantic axis: -Z is forward
     * /tarkovscav client villagerpose yaw=15 pitch=-40           - degrees, applied X then Y then Z
     * /tarkovscav client villagerpose x=0 y=0.06 z=0 zoom=..     - raw components (x/y/z/scale)
     * /tarkovscav client villagerpose anchor=arms|body           - arms = follow the aiming pose
     * /tarkovscav client villagerpose hold=0                     - arm pitch OFFSETS; 0 = vanilla crossed arms
     * /tarkovscav client villagerpose aim=-57 reload=0 hunker=0  - all four are offsets from the vanilla rest
     * /tarkovscav client villagerpose idleX=0.05 idleZ=-0.02     - per-pose weapon POSITION deltas
     * /tarkovscav client villagerpose idlePitch=-70 idleYaw= idleRoll= - gun rotation while IDLE only
     * /tarkovscav client villagerpose reloadPitch= reloadYaw= reloadRoll= - gun rotation while RELOADING only
     * /tarkovscav client villagerpose hunkerPitch= hunkerYaw= hunkerRoll= - gun rotation while RETREATING only
     * /tarkovscav client villagerpose hideGun=on|off              - hide the gun while idle (default off)
     * /tarkovscav client villagerpose reset                      - back to the shipped baseline
     * </pre>
     *
     * <p>Everything is written to {@code config/tarkovscav-common.toml} and applies on the next frame
     * (the model reads the config per render), so this is a "type and look" loop rather than an
     * edit-restart loop.</p>
     *
     * <p><b>Order matters, and it is not obvious:</b> the gun hangs off the villager's {@code arms} block,
     * so the arm angle moves it. Settle {@code client.gunnerVillagerAimArmPitch} first, then tune the gun
     * position - otherwise the work is thrown away the moment the arm angle changes.</p>
     */
    private static int villagerPose(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSourceStack source = context.getSource();
        float[] rotation = Config.gunnerVillagerGunRotation();
        float[] offset = Config.gunnerVillagerGunOffset();
        float scale = Config.gunnerVillagerGunScale();
        boolean onBody = Config.gunnerVillagerGunOnBody();
        // README 5j: the idle pose (-20 since the second idle-pose report; 0 = the vanilla crossed arms) and
        // the optional "hide the gun while idle" switch are part of the same silhouette, so they are tunable
        // from the same command.
        float hold = Config.gunnerVillagerHoldArmPitch();
        // The other three silhouettes (README 5j). They used to be toml-only, which meant the whole four-pose
        // table could only be tuned with a hand edit plus /tarkovscav client reload - the one thing this
        // command exists to avoid.
        float aim = Config.gunnerVillagerAimArmPitch();
        float reload = Config.gunnerVillagerReloadArmPitch();
        float hunker = Config.gunnerVillagerHunkerArmPitch();
        boolean hideGunWhenIdle = Config.hideGunWhenIdle();
        // The idle-ONLY gun offset (README 5j). It is a separate triple because the idle arms are the vanilla
        // crossed rest: the gun needs its own correction there, and nothing else may move with it.
        float[] idleRotation = Config.gunnerVillagerIdleGunRotation();
        // One gun-rotation delta per pose (README 5j): the base rotation applies to RAISED, and these two are
        // ADDED for their own state only. Both ship [0,0,0], so they are tunable here without changing what
        // any existing world looks like.
        float[] reloadRotation = Config.gunnerVillagerReloadGunRotation();
        float[] hunkerRotation = Config.gunnerVillagerHunkerGunRotation();
        // ... and one POSITION delta per pose, the same shape (README 5j). All zero by default.
        float[] idleOffset = Config.gunnerVillagerIdleGunOffset();
        float[] reloadOffset = Config.gunnerVillagerReloadGunOffset();
        float[] hunkerOffset = Config.gunnerVillagerHunkerGunOffset();

        String token = rawArgs == null ? "" : rawArgs.trim();
        if (!token.isEmpty()) {
            for (String part : token.split("\\s+")) {
                if (part.equalsIgnoreCase("reset")) {
                    // The shipped baseline, read from the single source of truth - a second hard-coded -90
                    // here is exactly how "reset" would have quietly restored the old, sky-pointing value.
                    rotation = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_GUN_ROTATION, 5.0F, 0.0F, 0.0F);
                    offset = new float[]{0.0F, 0.06F, -0.09F};
                    scale = 1.0F;
                    onBody = false;
                    hold = (float) Config.DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH;
                    aim = (float) Config.DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH;
                    reload = (float) Config.DEFAULT_GUNNER_VILLAGER_RELOAD_ARM_PITCH;
                    hunker = (float) Config.DEFAULT_GUNNER_VILLAGER_HUNKER_ARM_PITCH;
                    hideGunWhenIdle = false;
                    idleRotation = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION,
                            -2.0F, 0.0F, 0.0F);
                    reloadRotation = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION,
                            0.0F, 0.0F, 0.0F);
                    hunkerRotation = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION,
                            0.0F, 0.0F, 0.0F);
                    idleOffset = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_IDLE_GUN_OFFSET,
                            0.0F, 0.0F, 0.0F);
                    reloadOffset = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_OFFSET,
                            0.0F, 0.0F, 0.0F);
                    hunkerOffset = Config.triple(Config.DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_OFFSET,
                            0.0F, 0.0F, 0.0F);
                    continue;
                }
                if (part.equalsIgnoreCase("anchor=arms")) {
                    onBody = false;
                    continue;
                }
                if (part.equalsIgnoreCase("anchor=body")) {
                    onBody = true;
                    continue;
                }
                if (part.equalsIgnoreCase("hideGun=on") || part.equalsIgnoreCase("hideGun=true")) {
                    hideGunWhenIdle = true;
                    continue;
                }
                if (part.equalsIgnoreCase("hideGun=off") || part.equalsIgnoreCase("hideGun=false")) {
                    hideGunWhenIdle = false;
                    continue;
                }
                int equals = part.indexOf('=');
                double value;
                try {
                    if (equals < 0) {
                        throw new NumberFormatException(part);
                    }
                    value = Double.parseDouble(part.substring(equals + 1));
                } catch (NumberFormatException bad) {
                    source.sendFailure(Component.translatable("tarkovscav.command.client.gunpose.badarg", part));
                    return 0;
                }
                switch (part.substring(0, equals).toLowerCase(java.util.Locale.ROOT)) {
                    case "pitch" -> rotation[0] = (float) value;
                    case "yaw" -> rotation[1] = (float) value;
                    case "roll" -> rotation[2] = (float) value;
                    case "x" -> offset[0] = (float) value;
                    case "y" -> offset[1] = (float) value;
                    case "z" -> offset[2] = (float) value;
                    // Semantic axes, the same convention as /tarkovscav client gunpose and as the rig's
                    // gunMount offsets: the character faces -Z, so forward is z -= n and back is z += n.
                    case "forward" -> offset[2] -= (float) value;
                    case "back" -> offset[2] += (float) value;
                    case "right" -> offset[0] += (float) value;
                    case "left" -> offset[0] -= (float) value;
                    case "up" -> offset[1] -= (float) value;
                    case "down" -> offset[1] += (float) value;
                    case "scale" -> scale = (float) value;
                    case "hold" -> hold = (float) value;
                    case "aim" -> aim = (float) value;
                    case "reload" -> reload = (float) value;
                    case "hunker" -> hunker = (float) value;
                    case "idlepitch" -> idleRotation[0] = (float) value;
                    case "idleyaw" -> idleRotation[1] = (float) value;
                    case "idleroll" -> idleRotation[2] = (float) value;
                    case "reloadpitch" -> reloadRotation[0] = (float) value;
                    case "reloadyaw" -> reloadRotation[1] = (float) value;
                    case "reloadroll" -> reloadRotation[2] = (float) value;
                    case "hunkerpitch" -> hunkerRotation[0] = (float) value;
                    case "hunkeryaw" -> hunkerRotation[1] = (float) value;
                    case "hunkerroll" -> hunkerRotation[2] = (float) value;
                    // Position deltas, one triple per pose ("idleX=0.05"), in the same arm frame as x/y/z.
                    case "idlex" -> idleOffset[0] = (float) value;
                    case "idley" -> idleOffset[1] = (float) value;
                    case "idlez" -> idleOffset[2] = (float) value;
                    case "reloadx" -> reloadOffset[0] = (float) value;
                    case "reloady" -> reloadOffset[1] = (float) value;
                    case "reloadz" -> reloadOffset[2] = (float) value;
                    case "hunkerx" -> hunkerOffset[0] = (float) value;
                    case "hunkery" -> hunkerOffset[1] = (float) value;
                    case "hunkerz" -> hunkerOffset[2] = (float) value;
                    default -> {
                        source.sendFailure(Component.translatable("tarkovscav.command.client.gunpose.badarg", part));
                        return 0;
                    }
                }
            }
            Config.GUNNER_VILLAGER_GUN_ROTATION.set(java.util.List.of(
                    trim(rotation[0]), trim(rotation[1]), trim(rotation[2])));
            Config.GUNNER_VILLAGER_GUN_OFFSET.set(java.util.List.of(
                    trim(offset[0]), trim(offset[1]), trim(offset[2])));
            Config.GUNNER_VILLAGER_GUN_SCALE.set((double) scale);
            Config.GUNNER_VILLAGER_GUN_ANCHOR.set(onBody ? "body" : "arms");
            Config.GUNNER_VILLAGER_HOLD_ARM_PITCH.set((double) hold);
            Config.GUNNER_VILLAGER_AIM_ARM_PITCH.set((double) aim);
            Config.GUNNER_VILLAGER_RELOAD_ARM_PITCH.set((double) reload);
            Config.GUNNER_VILLAGER_HUNKER_ARM_PITCH.set((double) hunker);
            Config.HIDE_GUN_WHEN_IDLE.set(hideGunWhenIdle);
            Config.GUNNER_VILLAGER_IDLE_GUN_ROTATION.set(java.util.List.of(
                    trim(idleRotation[0]), trim(idleRotation[1]), trim(idleRotation[2])));
            Config.GUNNER_VILLAGER_RELOAD_GUN_ROTATION.set(java.util.List.of(
                    trim(reloadRotation[0]), trim(reloadRotation[1]), trim(reloadRotation[2])));
            Config.GUNNER_VILLAGER_HUNKER_GUN_ROTATION.set(java.util.List.of(
                    trim(hunkerRotation[0]), trim(hunkerRotation[1]), trim(hunkerRotation[2])));
            Config.GUNNER_VILLAGER_IDLE_GUN_OFFSET.set(java.util.List.of(
                    trim(idleOffset[0]), trim(idleOffset[1]), trim(idleOffset[2])));
            Config.GUNNER_VILLAGER_RELOAD_GUN_OFFSET.set(java.util.List.of(
                    trim(reloadOffset[0]), trim(reloadOffset[1]), trim(reloadOffset[2])));
            Config.GUNNER_VILLAGER_HUNKER_GUN_OFFSET.set(java.util.List.of(
                    trim(hunkerOffset[0]), trim(hunkerOffset[1]), trim(hunkerOffset[2])));
            Config.SPEC.save();
        }

        final float[] shownRotation = rotation;
        final float[] shownOffset = offset;
        final float shownScale = scale;
        final boolean shownBody = onBody;
        final float shownHold = hold;
        final float shownAim = aim;
        final float shownReload = reload;
        final float shownHunker = hunker;
        final boolean shownHideGun = hideGunWhenIdle;
        final float[] shownIdle = idleRotation;
        final float[] shownReloadRot = reloadRotation;
        final float[] shownHunkerRot = hunkerRotation;
        final float[] shownIdleOffset = idleOffset;
        final float[] shownReloadOffset = reloadOffset;
        final float[] shownHunkerOffset = hunkerOffset;
        source.sendSuccess(() -> Component.literal("villager gun: rot=["
                        + trim(shownRotation[0]) + "," + trim(shownRotation[1]) + "," + trim(shownRotation[2])
                        + "] offset=[" + trim(shownOffset[0]) + "," + trim(shownOffset[1]) + ","
                        + trim(shownOffset[2]) + "] scale=" + trim(shownScale)
                        + " anchor=" + (shownBody ? "body" : "arms")
                        // The same four-value order as /tarkovscav client state, so the two read alike.
                        + " armPitch aim/hold/reload/hunker=" + trim(shownAim) + "/" + trim(shownHold) + "/"
                        + trim(shownReload) + "/" + trim(shownHunker)
                        + (shownHold == 0.0F ? " (hold 0 = vanilla crossed arms)" : "")
                        + " idleRot=[" + trim(shownIdle[0]) + "," + trim(shownIdle[1]) + ","
                        + trim(shownIdle[2]) + "] (idle only)"
                        + " reloadRot=[" + trim(shownReloadRot[0]) + "," + trim(shownReloadRot[1]) + ","
                        + trim(shownReloadRot[2]) + "] hunkerRot=[" + trim(shownHunkerRot[0]) + ","
                        + trim(shownHunkerRot[1]) + "," + trim(shownHunkerRot[2]) + "] (rotation deltas)"
                        + " offsetDeltas idle[" + trim(shownIdleOffset[0]) + ","
                        + trim(shownIdleOffset[1]) + "," + trim(shownIdleOffset[2]) + "] reload["
                        + trim(shownReloadOffset[0]) + "," + trim(shownReloadOffset[1]) + ","
                        + trim(shownReloadOffset[2]) + "] hunker[" + trim(shownHunkerOffset[0]) + ","
                        + trim(shownHunkerOffset[1]) + "," + trim(shownHunkerOffset[2]) + "]"
                        + " hideGunWhenIdle=" + shownHideGun
                        + " (applies next frame)")
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Written to config/tarkovscav-common.toml:")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerGunRotation = ["
                        + trim(shownRotation[0]) + ", " + trim(shownRotation[1]) + ", "
                        + trim(shownRotation[2]) + "]")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerGunOffset = ["
                        + trim(shownOffset[0]) + ", " + trim(shownOffset[1]) + ", "
                        + trim(shownOffset[2]) + "]")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerGunScale = " + trim(shownScale)
                        + "  gunnerVillagerGunAnchor = " + (shownBody ? "body" : "arms"))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerAimArmPitch = " + trim(shownAim)
                        + "  gunnerVillagerHoldArmPitch = " + trim(shownHold))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerReloadArmPitch = " + trim(shownReload)
                        + "  gunnerVillagerHunkerArmPitch = " + trim(shownHunker)
                        + "  hideGunWhenIdle = " + shownHideGun)
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerIdleGunRotation = ["
                        + trim(shownIdle[0]) + ", " + trim(shownIdle[1]) + ", " + trim(shownIdle[2])
                        + "]  (added to the gun rotation ONLY while idle)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerReloadGunRotation = ["
                        + trim(shownReloadRot[0]) + ", " + trim(shownReloadRot[1]) + ", "
                        + trim(shownReloadRot[2]) + "]  (ONLY while reloading)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerIdleGunOffset = ["
                        + trim(shownIdleOffset[0]) + ", " + trim(shownIdleOffset[1]) + ", "
                        + trim(shownIdleOffset[2]) + "]  (OFFSET delta, only while idle)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerReloadGunOffset = ["
                        + trim(shownReloadOffset[0]) + ", " + trim(shownReloadOffset[1]) + ", "
                        + trim(shownReloadOffset[2]) + "]  (only while reloading)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerHunkerGunOffset = ["
                        + trim(shownHunkerOffset[0]) + ", " + trim(shownHunkerOffset[1]) + ", "
                        + trim(shownHunkerOffset[2]) + "]  (only while retreating)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunnerVillagerHunkerGunRotation = ["
                        + trim(shownHunkerRot[0]) + ", " + trim(shownHunkerRot[1]) + ", "
                        + trim(shownHunkerRot[2]) + "]  (ONLY while retreating)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        TarkovScav.LOGGER.info("[client] villager gun rot=[{},{},{}] offset=[{},{},{}] scale={} anchor={}"
                        + " armPitch aim/hold/reload/hunker={}/{}/{}/{} idleRot=[{},{},{}]"
                        + " reloadRot=[{},{},{}] hunkerRot=[{},{},{}] idleOff=[{},{},{}]"
                        + " reloadOff=[{},{},{}] hunkerOff=[{},{},{}] hideGunWhenIdle={}",
                trim(shownRotation[0]), trim(shownRotation[1]), trim(shownRotation[2]),
                trim(shownOffset[0]), trim(shownOffset[1]), trim(shownOffset[2]), trim(shownScale),
                shownBody ? "body" : "arms", trim(shownAim), trim(shownHold), trim(shownReload),
                trim(shownHunker), trim(shownIdle[0]), trim(shownIdle[1]), trim(shownIdle[2]),
                trim(shownReloadRot[0]), trim(shownReloadRot[1]), trim(shownReloadRot[2]),
                trim(shownHunkerRot[0]), trim(shownHunkerRot[1]), trim(shownHunkerRot[2]),
                trim(shownIdleOffset[0]), trim(shownIdleOffset[1]), trim(shownIdleOffset[2]),
                trim(shownReloadOffset[0]), trim(shownReloadOffset[1]), trim(shownReloadOffset[2]),
                trim(shownHunkerOffset[0]), trim(shownHunkerOffset[1]), trim(shownHunkerOffset[2]),
                shownHideGun);
        return 1;
    }

    // ------------------------------------------------------------------ client pose

    /**
     * The one-line A/B for the aim-pose owner: {@code /tarkovscav client pose auto|code|clips}
     * (bare {@code pose} prints the current value and the writer counts).
     *
     * <p>This exists so the "which writer looks right" comparison is a keystroke and not a toml edit
     * plus a reload: the arbiter reads the value every frame, so the change is visible on the next one.
     * It is written to the config as well, because a value that silently reverts on restart would make
     * the comparison meaningless.</p>
     *
     * <p>{@code molang off|pitch|all} is the other half of the comparison - it decides which of the
     * rig's own {@code ysm.*}/{@code query.*} aim variables are fed from the entity - and
     * {@code torso <0..1>} sets the share of the look yaw the code puts on the chest (the head always
     * takes the rest, so the aim lands in the same place whatever it is set to).</p>
     */
    private static int pose(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSourceStack source = context.getSource();
        String token = rawArgs == null ? "" : rawArgs.trim().toLowerCase(Locale.ROOT);

        if (token.isEmpty()) {
            reportPose(source);
            return 1;
        }
        if (token.startsWith("molang")) {
            String value = token.substring("molang".length()).trim();
            Config.MolangFeed mode;
            if (value.isEmpty()) {
                // a bare "molang" cycles pitch -> all -> off -> pitch, the three things to compare
                mode = switch (Config.molangFeed()) {
                    case PITCH -> Config.MolangFeed.ALL;
                    case ALL -> Config.MolangFeed.OFF;
                    case OFF -> Config.MolangFeed.PITCH;
                };
            } else {
                mode = switch (value) {
                    case "on", "true", "all", "yaw" -> Config.MolangFeed.ALL;
                    case "pitch", "pitchonly" -> Config.MolangFeed.PITCH;
                    case "off", "false", "none" -> Config.MolangFeed.OFF;
                    default -> null;
                };
            }
            if (mode == null) {
                source.sendFailure(Component.literal("client pose molang takes off|pitch|all, not '"
                        + value + "'"));
                return 0;
            }
            Config.MOLANG_VARIABLES.set(mode.name().toLowerCase(Locale.ROOT));
            Config.SPEC.save();
            RigSupport.invalidateConfig();
            source.sendSuccess(() -> Component.literal("client.molangVariables = "
                    + mode.name().toLowerCase(Locale.ROOT) + "  " + describeMolang(mode))
                    .withStyle(ChatFormatting.GREEN), false);
            reportPose(source);
            return 1;
        }

        if (token.startsWith("torso")) {
            String value = token.substring("torso".length()).trim().replace("=", "");
            if (value.isEmpty()) {
                source.sendFailure(Component.literal("client pose torso takes a 0..1 fraction,"
                        + " e.g. torso 0.0 or torso=0.25 (currently " + Config.torsoYawShare() + ")"));
                return 0;
            }
            float share;
            try {
                share = Float.parseFloat(value);
            } catch (NumberFormatException notANumber) {
                source.sendFailure(Component.literal("client pose torso takes a 0..1 fraction, not '"
                        + value + "'"));
                return 0;
            }
            share = net.minecraft.util.Mth.clamp(share, 0.0F, 1.0F);
            Config.TORSO_YAW_SHARE.set((double) share);
            Config.SPEC.save();
            RigSupport.invalidateConfig();
            final float applied = share;
            source.sendSuccess(() -> Component.literal("client.torsoYawShare = " + applied
                    + "  (the head still carries the rest, so the aim lands in the same place; the"
                    + " chest swing is " + (applied * 66.0F) + " deg for a +/-33 deg look swing)")
                    .withStyle(ChatFormatting.GREEN), false);
            reportPose(source);
            return 1;
        }

        PoseSource parsed = null;
        for (PoseSource candidate : PoseSource.values()) {
            if (candidate.name().equalsIgnoreCase(token)) {
                parsed = candidate;
                break;
            }
        }
        if (parsed == null) {
            source.sendFailure(Component.literal("client pose takes auto|code|clips (or molang on|off), not '"
                    + rawArgs + "'"));
            return 0;
        }
        final PoseSource chosen = parsed;
        Config.POSE_SOURCE.set(chosen.name().toLowerCase(Locale.ROOT));
        Config.SPEC.save();
        RigSupport.invalidateConfig();
        source.sendSuccess(() -> Component.literal("client.poseSource = "
                + chosen.name().toLowerCase(Locale.ROOT) + "  " + describePose(chosen))
                .withStyle(ChatFormatting.GREEN), false);
        reportPose(source);
        return 1;
    }

    private static String describePose(PoseSource poseSource) {
        return switch (poseSource) {
            case AUTO -> "(per bone: the clip owns it where the author's keyframes follow the look)";
            case CODE -> "(this mod's code owns UpperBody and Head - the pre-Molang behaviour)";
            case CLIPS -> "(the clips own everything; the code writes no pose bone)";
        };
    }

    private static String describeMolang(Config.MolangFeed feed) {
        return switch (feed) {
            case OFF -> "(every ysm.*/query.* symbol pinned to 0 - the pre-Molang behaviour)";
            case PITCH -> "(the author's pitch keyframes come alive; the yaw symbols stay 0)";
            case ALL -> "(all five symbols fed, yaw included - see the key's comment for what that adds)";
        };
    }

    private static void reportPose(CommandSourceStack source) {
        PoseSource poseSource = Config.poseSource();
        Config.MolangFeed feed = Config.molangFeed();
        source.sendSuccess(() -> Component.literal("  poseSource = " + poseSource.name().toLowerCase(Locale.ROOT)
                + " " + describePose(poseSource)).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  molangVariables = " + feed.name().toLowerCase(Locale.ROOT)
                + " " + describeMolang(feed)).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  torsoYawShare = " + Config.torsoYawShare()
                + "  (the rest of the look yaw goes to the head; the chest swing is "
                + (Config.torsoYawShare() * 66.0F) + " deg for a +/-33 deg look swing)")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  logPoseWriters = " + Config.LOG_POSE_WRITERS.get()
                + "   " + PoseWriters.summary()
                + "   (one [pose] line per frame names the writer of every pose bone)")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // ------------------------------------------------------------------ client reload

    /**
     * Re-reads the common config from disk.
     *
     * <p>Forge only reads a config file at startup, so without this an edited toml needs a game
     * restart. {@code ConfigTracker#load} is Forge's own "read this type's config files again" entry
     * point - the same one used at startup - and it re-reads <b>every</b> mod's COMMON config, not just
     * this one, which is the documented behaviour of that API and is reported in chat so it is not a
     * surprise.</p>
     */
    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
        } catch (RuntimeException failed) {
            TarkovScav.LOGGER.error("[client] reading the config back from disk failed", failed);
            source.sendFailure(Component.translatable("tarkovscav.command.client.reload.failed",
                    String.valueOf(failed.getMessage())));
            return 0;
        }
        // The next rendered frame re-applies the hide set, the head pitch and the anchor resolution.
        RigSupport.invalidateConfig();
        // And the gun pools: a gun pack (or a config change to the mods keys) is exactly the kind of thing a
        // user reloads the config for, so the attachment pools are dropped here too (README 5p).
        com.gfl.tarkovscav.gun.GunAttachments.invalidate();
        com.gfl.tarkovscav.gun.GunPool.invalidate();
        // The scripted-gun rule is cached per gun id and its "already reported" guards are per id too; a reload
        // is exactly when the trusted-namespace list may have changed, so the answer is recomputed (README 5p).
        com.gfl.tarkovscav.gun.ScriptedGuns.invalidate();
        // The ricochet memory is per projectile and only meaningful within a tick or two, but a reload is the
        // moment the rule may have changed, so it is dropped as well (README 5z).
        com.gfl.tarkovscav.combat.HardTarget.invalidate();
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.client.reloaded")
                .withStyle(ChatFormatting.GREEN), false);
        reportState(source);
        return 1;
    }

    // ------------------------------------------------------------------ client state

    private static int state(CommandContext<CommandSourceStack> context) {
        reportState(context.getSource());
        return 1;
    }

    private static void reportState(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.client.state.header")
                .withStyle(ChatFormatting.AQUA), false);

        source.sendSuccess(() -> Component.literal("  hiddenBones = " + Config.hiddenBones())
                .withStyle(ChatFormatting.GRAY), false);
        List<Config.HiddenBone> accessories = Config.accessoryHiddenBones();
        source.sendSuccess(() -> Component.literal("  headAccessories -> " + accessories.size() + " bone(s)")
                .withStyle(ChatFormatting.GRAY), false);
        for (Config.HiddenBone entry : accessories) {
            source.sendSuccess(() -> Component.literal("    " + entry.name() + "  <- " + entry.reason())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        source.sendSuccess(() -> Component.literal("  headRestPitchDegrees = " + Config.headRestPitchDegrees()
                + "  states = " + Config.HEAD_REST_PITCH_STATES.get()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  gunAnchorBone = " + Config.GUN_ANCHOR_BONE.get()
                + "  offhand = " + Config.GUN_OFFHAND_ANCHOR_BONE.get()
                + "  mode = " + (Config.normalisedHandMode() ? "normalisedHand" : "locatorAnimated")
                + "  context = " + Config.gunMountDisplayContext()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  renderOffhandItem = " + Config.RENDER_OFFHAND_ITEM.get()
                + "  gunTwoHandedSupport = " + Config.GUN_TWO_HANDED_SUPPORT.get()
                + "  offhandMount = " + java.util.Arrays.toString(Config.offhandMount()))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  rifle  rot=" + Config.GUN_MOUNT_RIFLE_ROTATION.get()
                + " offset=" + Config.GUN_MOUNT_RIFLE_OFFSET.get() + " scale=" + Config.GUN_MOUNT_RIFLE_SCALE.get()
                + "   (offset x = the forward nudge, along the barrel in normalisedHand)")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  pistol rot=" + Config.GUN_MOUNT_PISTOL_ROTATION.get()
                + " offset=" + Config.GUN_MOUNT_PISTOL_OFFSET.get() + " scale=" + Config.GUN_MOUNT_PISTOL_SCALE.get())
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  modelLayering = "
                + (Config.singleControllerMode() ? "single" : "upperLower")
                + "  modelRenderType = " + Config.modelRenderType()
                + "  cullingBoxPadding = " + Config.cullingBoxPadding()
                + "   (all three: both layering modes are ONE geometry submission per frame)")
                .withStyle(ChatFormatting.GRAY), false);
        // The TWO model sizes (README 5b). They are separate keys because they are separate meshes: the rig is
        // authored at 0.7, the vanilla villager at 1.0. Printing which key governs which family is the point -
        // "the villagers are too big" was a report about exactly this line of the config.
        source.sendSuccess(() -> Component.literal("  client.renderScale = " + trim(Config.renderScale())
                + "  [RIG: scav + gecko pillager, baseline " + trim((float) Config.DEFAULT_RENDER_SCALE)
                + " -> visible x" + trim(Config.renderScale() / (float) Config.DEFAULT_RENDER_SCALE) + "]"
                + "   client.villagerRenderScale = " + trim(Config.villagerRenderScale())
                + "  [VILLAGER family: gunner/sniper/usec/elite villager, 1.0 = vanilla size]"
                + "   -> /tarkovscav client scale [villager] <value>")
                .withStyle(ChatFormatting.GRAY), false);
        // The villager gun transform: this is the value /tarkovscav client villagerpose writes, so it has
        // to be readable back from here (the user asked for exactly that) - and the arm pitches are shown
        // next to it because the gun's tilt is armPitch + gunRot.x - 90, not gunRot.x on its own.
        source.sendSuccess(() -> Component.literal("  villagerGun rot=[" + trim(Config.gunnerVillagerGunRotation()[0])
                + "," + trim(Config.gunnerVillagerGunRotation()[1]) + ","
                + trim(Config.gunnerVillagerGunRotation()[2]) + "] offset=["
                + trim(Config.gunnerVillagerGunOffset()[0]) + "," + trim(Config.gunnerVillagerGunOffset()[1])
                + "," + trim(Config.gunnerVillagerGunOffset()[2]) + "] scale="
                + trim(Config.gunnerVillagerGunScale()) + " anchor="
                + (Config.gunnerVillagerGunOnBody() ? "body" : "arms")
                + "   armPitch aim/hold/reload/hunker = " + trim(Config.gunnerVillagerAimArmPitch()) + "/"
                + trim(Config.gunnerVillagerHoldArmPitch()) + "/" + trim(Config.gunnerVillagerReloadArmPitch())
                + "/" + trim(Config.gunnerVillagerHunkerArmPitch())
                + (Config.gunnerVillagerHoldArmPitch() == 0.0F ? " (hold 0 = vanilla crossed arms)" : "")
                + " idleRot=[" + trim(Config.gunnerVillagerIdleGunRotation()[0]) + ","
                + trim(Config.gunnerVillagerIdleGunRotation()[1]) + ","
                + trim(Config.gunnerVillagerIdleGunRotation()[2]) + "] (idle only)"
                + " reloadRot=[" + trim(Config.gunnerVillagerReloadGunRotation()[0]) + ","
                + trim(Config.gunnerVillagerReloadGunRotation()[1]) + ","
                + trim(Config.gunnerVillagerReloadGunRotation()[2]) + "] hunkerRot=["
                + trim(Config.gunnerVillagerHunkerGunRotation()[0]) + ","
                + trim(Config.gunnerVillagerHunkerGunRotation()[1]) + ","
                + trim(Config.gunnerVillagerHunkerGunRotation()[2]) + "] (rotation deltas)"
                + " offsetDelta idle=[" + trim(Config.gunnerVillagerIdleGunOffset()[0]) + ","
                + trim(Config.gunnerVillagerIdleGunOffset()[1]) + ","
                + trim(Config.gunnerVillagerIdleGunOffset()[2]) + "] reload=["
                + trim(Config.gunnerVillagerReloadGunOffset()[0]) + ","
                + trim(Config.gunnerVillagerReloadGunOffset()[1]) + ","
                + trim(Config.gunnerVillagerReloadGunOffset()[2]) + "] hunker=["
                + trim(Config.gunnerVillagerHunkerGunOffset()[0]) + ","
                + trim(Config.gunnerVillagerHunkerGunOffset()[1]) + ","
                + trim(Config.gunnerVillagerHunkerGunOffset()[2]) + "] (position, per pose)"
                + " hideGunWhenIdle=" + Config.hideGunWhenIdle()
                + "  -> gun tilt = armPitch + rot.x + thisPoseDeltaX - 90, RAISED has no delta")
                .withStyle(ChatFormatting.GRAY), false);
        // The voice mix (README 5l): the base volume, the per-family multipliers and the effect volume. The
        // user tunes these to fix "this family is quieter than that one", so they have to be readable back.
        source.sendSuccess(() -> Component.literal("  voiceVolume = " + Config.VOICE_VOLUME.get()
                + "  familyVolume " + String.join(" ", Config.familyVolumeSummary())
                + "  effectVolume = " + Config.effectVolume()
                + "   (familyVolume is the escape hatch: the clips ship at matched loudness, so 1.0 is"
                + " calibrated)")
                .withStyle(ChatFormatting.GRAY), false);
        reportPose(source);
        // Player lean (README 5s): the live ramp value, which keys are held, and whether the lateral camera
        // move is actually available (the reflective Camera#setPosition call can degrade to roll-only).
        source.sendSuccess(() -> Component.literal("  " + com.gfl.tarkovscav.client.LeanClient.describe())
                .withStyle(ChatFormatting.GRAY), false);
    }

    // ------------------------------------------------------------------ client hide / show

    private static int hide(CommandContext<CommandSourceStack> context, boolean add) {
        CommandSourceStack source = context.getSource();
        String bone = StringArgumentType.getString(context, "bone");
        List<String> entries = new ArrayList<>(Config.EXTRA_HIDDEN_BONES.get());
        boolean changed;
        if (add) {
            changed = entries.stream().noneMatch(existing -> existing.equalsIgnoreCase(bone));
            if (changed) {
                entries.add(bone);
            }
        } else {
            changed = entries.removeIf(existing -> existing.equalsIgnoreCase(bone));
        }
        if (!changed) {
            source.sendFailure(Component.translatable("tarkovscav.command.client.hide.unchanged", bone));
            return 0;
        }
        Config.EXTRA_HIDDEN_BONES.set(entries);
        Config.SPEC.save();
        RigSupport.invalidateConfig();
        source.sendSuccess(() -> Component.translatable(
                        add ? "tarkovscav.command.client.hide.added" : "tarkovscav.command.client.hide.removed",
                        bone, entries.toString()).withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("client.extraHiddenBones = " + tomlList(entries))
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    // ------------------------------------------------------------------ client gunpose

    /**
     * The live tuner. Grammar (all keys optional, any order):
     * <pre>
     *   /tarkovscav client gunpose [family=rifle|pistol] [hand=main|offhand] [pitch=deg] [yaw=deg]
     *                              [roll=deg] [x=blocks] [y=blocks] [z=blocks] [scale=factor]
     *                              [forward=|back=|left=|right=|up=|down=blocks]  (additive nudges)
     *                              [context=THIRD_PERSON_RIGHT_HAND]
     *                              [mode=normalisedHand|locatorAnimated] [reset]
     * </pre>
     *
     * <p>{@code pitch}/{@code yaw}/{@code roll} are the rotation about X/Y/Z, which is the order the
     * transform is applied in; they are the same three numbers as the {@code [x, y, z]} triple in the
     * config. {@code x=}/{@code y=}/{@code z=} <b>set</b> the offset, while {@code forward=}/{@code up=}
     * and friends <b>add</b> to it, so they can be tapped repeatedly while watching a mob. The named
     * directions are the anchor frame's own axes, named for the direction each points in the default
     * {@code normalisedHand} mode (measured in model space, see the README):
     * <pre>
     *   forward / back  ->  -z / +z   the muzzle axis       USER-MEASURED: z=-0.7 moved the rifle forward
     *   right / left    ->  +x / -x   the character's side  frame basis: +x maps to model -X (their right)
     *   up / down       ->  -y / +y   model up / down       frame basis: +y maps to model -Y (down)
     * </pre>
     * The forward axis is not a guess: the user tuned {@code z=-0.7} in game with the default
     * {@code normalisedHand} mode and reported the gun moved forward, so -Z is towards the muzzle. The
     * other two come from the same frame's basis vectors. In {@code locatorAnimated} the anchor frame is
     * rotated, so the names point elsewhere; the mapping above holds for {@code normalisedHand} only.
     * {@code hand=offhand} tunes {@code gunMountOffhand*}, which is what the offhand item and the
     * optional two-handed support copy use. {@code mode=} switches {@code client.gunAnchorMode} on the
     * fly. The result is applied to every mob using the rig on the next frame (the render layer reads
     * the config per frame), printed as a paste-ready toml line and saved, so it survives a restart.
     * <b>{@code reset}</b> restores the SHIPPED BASELINE, which is not zero: rifle and pistol both go
     * back to {@code rot [0,0,0] offset [0,0,-0.7] scale 1.0} (the user-measured forward slide),
     * offhand to neutral, plus {@code gunMountDisplayContext=THIRD_PERSON_RIGHT_HAND} and
     * {@code gunAnchorMode=normalisedHand}. <b>Note:</b> Forge only writes a default for a key that is
     * missing, so a toml that already carries values written by this command will not pick up a new
     * mod default on its own - run {@code reset} (or delete those lines) to see the new baseline.</p>
     */
    private static int gunPose(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSourceStack source = context.getSource();
        boolean pistol = false;
        boolean offhand = false;
        Float pitch = null;
        Float yaw = null;
        Float roll = null;
        Float offsetX = null;
        Float offsetY = null;
        Float offsetZ = null;
        Float nudgeForward = null;
        Float nudgeUp = null;
        Float nudgeRight = null;
        Float scale = null;
        String contextName = null;
        String modeName = null;
        boolean reset = false;

        for (String token : rawArgs.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.equalsIgnoreCase("reset") || token.equalsIgnoreCase("default")) {
                reset = true;
                continue;
            }
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) {
                source.sendFailure(Component.translatable("tarkovscav.command.client.gunpose.badarg", token));
                return 0;
            }
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(equals + 1).trim();
            try {
                switch (key) {
                    case "family", "class" -> {
                        if (!value.equalsIgnoreCase("rifle") && !value.equalsIgnoreCase("pistol")) {
                            source.sendFailure(Component.translatable(
                                    "tarkovscav.command.client.gunpose.badarg", token));
                            return 0;
                        }
                        pistol = value.equalsIgnoreCase("pistol");
                    }
                    case "pitch", "x", "rx" -> {
                        if (key.equals("x")) {
                            offsetX = Float.parseFloat(value);
                        } else {
                            pitch = Float.parseFloat(value);
                        }
                    }
                    case "yaw", "y", "ry" -> {
                        if (key.equals("y")) {
                            offsetY = Float.parseFloat(value);
                        } else {
                            yaw = Float.parseFloat(value);
                        }
                    }
                    case "roll", "z", "rz" -> {
                        if (key.equals("z")) {
                            offsetZ = Float.parseFloat(value);
                        } else {
                            roll = Float.parseFloat(value);
                        }
                    }
                    // Additive nudges along the anchor frame's own axes. Tapping one repeatedly walks the
                    // value, which is the point: watch the mob, tap, watch again. The axis names come
                    // from the user's in-game measurement of the default normalisedHand frame, where
                    // z = -0.7 moved the rifle forward - so -Z is the muzzle direction and z is forward.
                    case "forward", "barrel" -> nudgeForward = -Float.parseFloat(value);
                    case "back" -> nudgeForward = Float.parseFloat(value);
                    case "right" -> nudgeRight = Float.parseFloat(value);
                    case "left" -> nudgeRight = -Float.parseFloat(value);
                    case "up" -> nudgeUp = -Float.parseFloat(value);
                    case "down" -> nudgeUp = Float.parseFloat(value);
                    case "hand" -> {
                        if (!value.equalsIgnoreCase("main") && !value.equalsIgnoreCase("offhand")) {
                            source.sendFailure(Component.translatable(
                                    "tarkovscav.command.client.gunpose.badarg", token));
                            return 0;
                        }
                        offhand = value.equalsIgnoreCase("offhand");
                    }
                    case "mode" -> {
                        if (value.equalsIgnoreCase("locatorAnimated")) {
                            modeName = "locatorAnimated";
                        } else if (value.equalsIgnoreCase("normalisedHand")
                                || value.equalsIgnoreCase("normalizedHand")) {
                            modeName = "normalisedHand";
                        } else {
                            source.sendFailure(Component.translatable(
                                    "tarkovscav.command.client.gunpose.badarg", token));
                            return 0;
                        }
                    }
                    case "scale" -> scale = Float.parseFloat(value);
                    case "context" -> contextName = value.toUpperCase(Locale.ROOT);
                    default -> {
                        source.sendFailure(Component.translatable(
                                "tarkovscav.command.client.gunpose.badarg", token));
                        return 0;
                    }
                }
            } catch (NumberFormatException notANumber) {
                source.sendFailure(Component.translatable("tarkovscav.command.client.gunpose.badarg", token));
                return 0;
            }
        }

        String family = offhand ? "offhand" : (pistol ? "pistol" : "rifle");
        float[] rotation;
        float[] offset;
        float currentScale;
        if (offhand) {
            float[] mount = Config.offhandMount();
            rotation = new float[]{mount[0], mount[1], mount[2]};
            offset = new float[]{mount[3], mount[4], mount[5]};
            currentScale = mount[6];
        } else {
            rotation = Config.triple(
                    pistol ? Config.GUN_MOUNT_PISTOL_ROTATION.get() : Config.GUN_MOUNT_RIFLE_ROTATION.get(),
                    0.0F, 0.0F, 0.0F);
            offset = Config.triple(
                    pistol ? Config.GUN_MOUNT_PISTOL_OFFSET.get() : Config.GUN_MOUNT_RIFLE_OFFSET.get(),
                    0.0F, 0.0F, 0.0F);
            currentScale = (pistol ? Config.GUN_MOUNT_PISTOL_SCALE.get()
                    : Config.GUN_MOUNT_RIFLE_SCALE.get()).floatValue();
        }

        if (reset) {
            // reset means "back to the mod's shipped baseline", which is NOT zero: the rifle/pistol
            // baseline carries the user's measured 0.7-block forward slide. The defaults come from
            // Config so the command and the config spec cannot drift apart.
            boolean offhandReset = offhand;
            rotation = new float[]{0.0F, 0.0F, 0.0F};
            offset = offhandReset
                    ? Config.triple(Config.DEFAULT_OFFHAND_OFFSET, 0.0F, 0.0F, 0.0F)
                    : Config.triple(Config.DEFAULT_MOUNT_OFFSET, 0.0F, 0.0F, -0.7F);
            scale = offhandReset ? (float) Config.DEFAULT_OFFHAND_SCALE : (float) Config.DEFAULT_MOUNT_SCALE;
            contextName = Config.DEFAULT_MOUNT_DISPLAY_CONTEXT;
            modeName = Config.DEFAULT_ANCHOR_MODE;
        }
        if (pitch != null) {
            rotation[0] = pitch;
        }
        if (yaw != null) {
            rotation[1] = yaw;
        }
        if (roll != null) {
            rotation[2] = roll;
        }
        if (offsetX != null) {
            offset[0] = offsetX;
        }
        if (offsetY != null) {
            offset[1] = offsetY;
        }
        if (offsetZ != null) {
            offset[2] = offsetZ;
        }
        // forward/back -> Z (the muzzle axis, user-measured), right/left -> X, up/down -> Y (negated,
        // because +Y points down in this frame). Additive, so repeated taps walk the offset.
        if (nudgeForward != null) {
            offset[2] += nudgeForward;
        }
        if (nudgeRight != null) {
            offset[0] += nudgeRight;
        }
        if (nudgeUp != null) {
            offset[1] += nudgeUp;
        }
        if (scale == null) {
            scale = currentScale;
        }
        if (contextName == null) {
            contextName = Config.gunMountDisplayContext();
        }
        if (modeName == null) {
            modeName = Config.normalisedHandMode() ? "normalisedHand" : "locatorAnimated";
        }

        List<String> rotationEntry = List.of(trim(rotation[0]), trim(rotation[1]), trim(rotation[2]));
        List<String> offsetEntry = List.of(trim(offset[0]), trim(offset[1]), trim(offset[2]));
        if (offhand) {
            Config.GUN_MOUNT_OFFHAND_ROTATION.set(rotationEntry);
            Config.GUN_MOUNT_OFFHAND_OFFSET.set(offsetEntry);
            Config.GUN_MOUNT_OFFHAND_SCALE.set((double) scale);
        } else if (pistol) {
            Config.GUN_MOUNT_PISTOL_ROTATION.set(rotationEntry);
            Config.GUN_MOUNT_PISTOL_OFFSET.set(offsetEntry);
            Config.GUN_MOUNT_PISTOL_SCALE.set((double) scale);
        } else {
            Config.GUN_MOUNT_RIFLE_ROTATION.set(rotationEntry);
            Config.GUN_MOUNT_RIFLE_OFFSET.set(offsetEntry);
            Config.GUN_MOUNT_RIFLE_SCALE.set((double) scale);
        }
        Config.GUN_MOUNT_DISPLAY_CONTEXT.set(contextName);
        Config.GUN_ANCHOR_MODE.set(modeName);
        Config.SPEC.save();
        // Re-print the [gunmount] line with the new numbers, and let the models re-run their pass.
        RigSupport.invalidateConfig();

        final String familyFinal = family;
        final String contextFinal = contextName;
        final String modeFinal = modeName;
        // The chat lines are built from these: effectively-final copies, because the arrays above are
        // reassigned while the arguments are parsed.
        final float[] rotationFinal = rotation;
        final float[] offsetFinal = offset;
        final float scaleFinal = scale;
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.client.gunpose.set",
                        familyFinal, modeFinal, trim(rotationFinal[0]), trim(rotationFinal[1]),
                        trim(rotationFinal[2]), trim(offsetFinal[0]), trim(offsetFinal[1]),
                        trim(offsetFinal[2]), trim(scaleFinal), contextFinal)
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.translatable("tarkovscav.command.client.gunpose.snippet")
                .withStyle(ChatFormatting.AQUA), false);
        for (String line : tomlSnippet(family, rotationEntry, offsetEntry, scale, contextName, modeName)) {
            source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    private static List<String> tomlSnippet(String family, List<String> rotation, List<String> offset,
                                           float scale, String context, String mode) {
        String prefix = "offhand".equals(family) ? "gunMountOffhand"
                : ("pistol".equals(family) ? "gunMountPistol" : "gunMountRifle");
        List<String> lines = new ArrayList<>();
        lines.add("[client]");
        lines.add("gunAnchorMode = \"" + mode + "\"");
        lines.add(prefix + "Rotation = " + tomlList(rotation));
        lines.add(prefix + "Offset = " + tomlList(offset));
        lines.add(prefix + "Scale = " + trim(scale));
        lines.add("gunMountDisplayContext = \"" + context + "\"");
        return lines;
    }

    private static String tomlList(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('"').append(values.get(i)).append('"');
        }
        return builder.append(']').toString();
    }

    /** Trims a float to something a human would type: 12.0 -> 12, 12.5 -> 12.5, and rounds the
     *  additive nudges so that tapping forward=0.05 five times stores 0.25 rather than 0.25000001. */
    private static String trim(float value) {
        float rounded = Math.round(value * 10000.0F) / 10000.0F;
        if (rounded == Math.round(rounded)) {
            return Integer.toString(Math.round(rounded));
        }
        return Float.toString(rounded);
    }
}
