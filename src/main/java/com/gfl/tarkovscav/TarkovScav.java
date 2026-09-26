package com.gfl.tarkovscav;

import com.gfl.tarkovscav.gun.GunCapabilities;
import com.gfl.tarkovscav.gun.GunPool;
import com.gfl.tarkovscav.registry.ModCreativeTabs;
import com.gfl.tarkovscav.registry.ModBlocks;
import com.gfl.tarkovscav.registry.ModEntities;
import com.gfl.tarkovscav.registry.ModItems;
import com.gfl.tarkovscav.registry.ModSounds;
import com.gfl.tarkovscav.world.CitySpawnEvents;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.slf4j.Logger;
import software.bernie.geckolib.GeckoLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Armed Mobs - gun-armed mobs that fight with TaCZ firearms, plus a pillager that
 * only spawns inside city areas.
 *
 * <p>Two third-party mods matter here and they are treated very differently:</p>
 * <ul>
 *   <li><b>GeckoLib</b> is bundled into the jar with jarJar (see build.gradle), so players do not
 *       have to install it; if their own copy is older than the bundled one, Forge uses ours.</li>
 *   <li><b>TaCZ</b> is a hard dependency that is deliberately <em>not</em> bundled and <em>not</em>
 *       redistributed. It is a 57 MB third-party mod that supplies every gun, magazine and bullet
 *       this mod fires. {@code mods.toml} declares it mandatory so a missing TaCZ fails loudly at
 *       load instead of at the first shot.</li>
 * </ul>
 */
@Mod(TarkovScav.MOD_ID)
public class TarkovScav {
    public static final String MOD_ID = "tarkovscav";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** TaCZ's mod id - the mod we integrate with but never ship. */
    public static final String TACZ_MOD_ID = "tacz";

    /** Must match geckolib_bundled_version in gradle.properties. */
    public static final String BUNDLED_GECKOLIB_VERSION = "4.8.4";

    public TarkovScav() {
        // GeckoLib must be initialised before any animatable is registered.
        GeckoLib.initialize();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.register(modBus);
        ModItems.register(modBus);
        ModBlocks.register(modBus);
        ModCreativeTabs.register(modBus);
        ModSounds.register(modBus);
        // The wasteland's structure placement type (dense in the wasteland, shipped grid everywhere else).
        com.gfl.tarkovscav.worldgen.ModWorldgen.register(modBus);
        // The city-chest loot extras (deliverable C): the global loot modifier serializer and the
        // "this mod is loaded" loot condition its JSON is gated on. Both are plain registry entries, so a
        // world without TaCZ simply never builds the modifier (see loot/ModLoadedLootCondition).
        com.gfl.tarkovscav.loot.CityChestLootModifier.SERIALIZERS.register(modBus);
        com.gfl.tarkovscav.loot.ModLoadedLootCondition.CONDITIONS.register(modBus);
        modBus.addListener(this::onCommonSetup);

        ConfigMigration.migrate(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(MOD_ID + "-common.toml"));
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(CitySpawnEvents.class);
        MinecraftForge.EVENT_BUS.register(GunCapabilities.class);
        // Friendly fire, the renegade brand and the faction tags (README 5m). One hook for every faction
        // member, so the rule cannot drift between the three entity classes.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.faction.FactionEvents.class);
        // The delayed assertions of /tarkovscav test watch|stall (see FightHarness for why they are
        // not scheduled with a TickTask).
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.command.FightHarness.class);
        // The kill feed (README 5u): one line per death, for the players allowed to see it.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.killfeed.KillFeed.class);
        // Grenades (README 5v): the smoke clouds are ticked by hand; the blast itself is called from the entity.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.grenade.GrenadeEvents.class);
        // The faction troops (README 5y): the rolled armor class and its damage reduction.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.entity.ArmorClass.class);
        // Doors (README 5a): the every-tick driver for the open-then-shut behaviour. GunBrain#tick
        // reaches it too, but that method only runs while GunAttackGoal is active, i.e. while the mob
        // has a target - so this handler is what covers an idle unit that strolls through a building.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.gun.DoorBehavior.class);
        // Their armor is rolled once, when they first join a level (never on a chunk load).
        MinecraftForge.EVENT_BUS.addListener(com.gfl.tarkovscav.entity.ArmorClassSpawn::onJoinLevel);
        // Hard targets (README 5z): the iron golem's 80 % gunfire reduction and its ricochet.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.combat.HardTarget.class);
        // The VANT ballistic shield (README 5zb): the front-arc bullet reduction, its durability cost
        // and the shatter at zero. Same bus as the ricochet, one hook for every living holder.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.combat.VantShieldHandler.class);
        // The urban wasteland: the deployment cast and the shared safe-landing teleport used by both the
        // deployment beacon item and /armedmobs dimension.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.world.WastelandTravel.class);
        // The one-time city garrison (garrison.*): a coarse server-tick trigger that places each city's
        // fixed squads once and then only ever consults the SavedData ledger.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.world.CityGarrison.class);
        // City capture (README 7p): the kill drain and the pool-at-zero veto for the overworld only. The
        // pool creation itself rides the garrison trigger (CityGarrison#factionFor), so this is one handler,
        // not a second scanner.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.world.CityCapture.class);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModEntities::registerSpawnPlacements);
        // The kill feed's one packet (README 5u): server -> client only.
        com.gfl.tarkovscav.killfeed.KillFeedNetwork.register();
        // The capture HUD's one packet (README 7p): server -> client only, names and numbers.
        com.gfl.tarkovscav.world.CaptureHudNetwork.register();
        // Grenades (README 5v) teach the kill feed to name them: ONE registered resolver, no change to the kill
        // feed itself - which is the extension point that batch was built around.
        com.gfl.tarkovscav.killfeed.KillFeedWeapons.register(com.gfl.tarkovscav.grenade.GrenadeAttribution::resolve);

        ModList.get().getModContainerById("geckolib").ifPresent(container -> {
            ArtifactVersion loaded = container.getModInfo().getVersion();
            ArtifactVersion bundled = new DefaultArtifactVersion(BUNDLED_GECKOLIB_VERSION);
            if (loaded.compareTo(bundled) < 0) {
                LOGGER.warn("GeckoLib {} is loaded, but this build was compiled against {}."
                                + " It should still work - update GeckoLib if animations look wrong.",
                        loaded, BUNDLED_GECKOLIB_VERSION);
            } else {
                LOGGER.info("GeckoLib {} detected", loaded);
            }
        });

        // TaCZ is mandatory, so by the time this runs it is present; log the version we integrate with
        // because the gun-data format has changed between releases more than once.
        ModList.get().getModContainerById(TACZ_MOD_ID).ifPresentOrElse(
                container -> LOGGER.info("TaCZ {} detected - guns come from its index at runtime",
                        container.getModInfo().getVersion()),
                () -> LOGGER.error("TaCZ is missing! mods.toml requires it; gun AI cannot work."));

        warnAboutDuplicateGeckoLibJars();
    }

    /**
     * Forge only ever loads ONE jar per modid, so two GeckoLib copies never run at the same time -
     * but when a modpack ships two of them, the winner is not necessarily the newest, which
     * surfaces as a confusing "requires geckolib X" crash. Warn about it early.
     */
    private void warnAboutDuplicateGeckoLibJars() {
        Path modsDir = FMLPaths.MODSDIR.get();
        if (!Files.isDirectory(modsDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(modsDir)) {
            List<String> geckoLibs = entries
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("geckolib"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            if (geckoLibs.size() > 1) {
                LOGGER.warn("Found {} GeckoLib jars in {}: {}", geckoLibs.size(), modsDir, geckoLibs);
                LOGGER.warn("Forge keeps only one of them and it may not be the newest."
                        + " Keep a single GeckoLib jar - or none at all, since this mod already bundles GeckoLib {}.",
                        BUNDLED_GECKOLIB_VERSION);
            }
        } catch (IOException exception) {
            LOGGER.debug("Could not inspect the mods folder {}: {}", modsDir, exception.toString());
        }
    }

    /**
     * TaCZ builds its gun index during a datapack/resource reload. Any cached gun pool is stale
     * afterwards (a new gun pack was added, a gun was removed), so drop it here.
     */
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        GunPool.invalidate();
        com.gfl.tarkovscav.loot.CityChestExtras.invalidate();
        // The per-building city maps (data/<ns>/city_buildings/*.json) are read through the resource manager,
        // so an edited map must be re-read after a datapack reload.
        com.gfl.tarkovscav.world.CityBuildings.invalidate();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // TaCZ's index is populated by then; a fresh pool logs what it found for this world.
        GunPool.invalidate();
        com.gfl.tarkovscav.loot.CityChestExtras.invalidate();
        GunPool.logSummary();
        // Pick up any city structure the user dropped into tarkovscav/city/ by hand.
        List<String> runtime = com.gfl.tarkovscav.world.CityStructures.reloadFromDisk();
        LOGGER.info("[city] runtime city structures: {}{}",
                runtime.isEmpty() ? "none" : runtime,
                runtime.isEmpty() ? " (drop .nbt files into " + com.gfl.tarkovscav.world.CityStructures.directory()
                        + " and run /tarkovscav city reload)" : "");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        com.gfl.tarkovscav.command.ModCommands.register(event.getDispatcher());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
