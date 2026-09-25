package com.gfl.tarkovscav.client;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.entity.GunnerPillagerEntity;
import com.gfl.tarkovscav.entity.GunnerVillagerEntity;
import com.gfl.tarkovscav.registry.ModEntities;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Client-only setup: which renderer each mob gets.
 *
 * <p>Two of the mobs have a choice: the vanilla model (no new assets needed) or the GeckoLib Bedrock rig.
 * {@code client.useGeckoModel} picks at startup, which is what makes "the user sends a Bedrock model later"
 * a config flip plus three asset files rather than a code change.</p>
 *
 * <h2>Why this class can crash the client, and what guards it (2026-09-23)</h2>
 * <p>A registered entity type with <b>no renderer</b> is not a missing model and not a purple cube: the
 * render dispatcher hands back {@code null} and the very first frame the entity enters the frustum throws</p>
 * <pre>
 * java.lang.NullPointerException: Cannot invoke
 *   "net.minecraft.client.renderer.entity.EntityRenderer.shouldRender(...)" because "entityrenderer" is null
 *   at net.minecraft.client.renderer.entity.EntityRenderDispatcher.render(EntityRenderDispatcher.java:127)
 * </pre>
 * <p>which is exactly what {@code tarkovscav:sniper_pillager} did: registered in {@link ModEntities},
 * spawnable, armed and talking in the log - and then the client died the moment it became visible. Two
 * things now prevent a repeat:</p>
 * <ol>
 *   <li>every registration goes through {@link #renderer}, which records the type id in {@link #RENDERED},
 *       so a renderer can no longer be added for one pillager and forgotten for its sibling;</li>
 *   <li>{@link #onLoadComplete} walks {@link ModEntities} <b>reflectively</b> and logs an ERROR for every
 *       entity type that has no renderer. That turns "client crash on sight" into "one red line in the log
 *       at startup" for any future entity.</li>
 * </ol>
 * <p>The gate {@code tools/selftest_entity_registry.js} checks the same thing on the sources, so the
 * omission cannot even reach a build.</p>
 */
@Mod.EventBusSubscriber(modid = TarkovScav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    /**
     * The entity types this class registered a renderer for. Filled by {@link #renderer} and compared
     * against {@link ModEntities} by {@link #onLoadComplete}.
     */
    private static final Set<ResourceLocation> RENDERED = new LinkedHashSet<>();

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        boolean useGecko = Config.SPEC.isLoaded() && Config.USE_GECKO_MODEL.get();
        registerAll(event, useGecko);
    }

    /**
     * The lean key bindings (README 5s): Q = lean left, E = lean right, as the user asked. Both are vanilla
     * keys, and {@code LeanClient} suppresses the vanilla actions while they are held.
     */
    @SubscribeEvent
    public static void onRegisterKeys(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        LeanClient.registerKeys(event);
    }

    /**
     * The kill feed HUD (README 5u): registered above everything, because it is a top-of-screen element and
     * the vanilla overlays must not draw over it. It keeps its own {@code enabled} check, so this registration
     * is unconditional and the config decides.
     */
    @SubscribeEvent
    public static void onRegisterOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("killfeed", KillFeedHud.INSTANCE);
        // The flashbang white-out (README 5v) is BELOW the kill feed but above the hotbar: a flash must not
        // hide the feed (that would be a double punishment) but it does cover the world.
        event.registerBelow(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CHAT_PANEL.id(),
                "grenade_flash", FlashOverlay.INSTANCE);
    }

    /** The one place where "this entity type gets this renderer" is decided. */
    private static void registerAll(EntityRenderersEvent.RegisterRenderers event, boolean useGecko) {
        renderer(event, ModEntities.SCAV, ScavRenderer::new);

        // The villager-bodied types render with the vanilla villager model/texture (see
        // GunnerVillagerRenderer) - there is no GeckoLib variant for villagers, which is the point of the mob.
        // One renderer CLASS, but one registration per entity TYPE, through ONE call site so a new villager
        // type cannot be added to the game and forgotten here.
        villagerRenderers(event, new RegistryObject[] { ModEntities.GUNNER_VILLAGER,
                ModEntities.SNIPER_VILLAGER, ModEntities.USEC_VILLAGER, ModEntities.ELITE_VILLAGER });

        // The illager-bodied types share the illager model (or its GeckoLib rig). They are SEPARATE entity
        // types, so each needs its own registration - and they are passed to ONE helper as a list, so a new
        // member of the family is added in one place and cannot land in one branch but not the other. The
        // sniper being left out of the hand-written version of this is what crashed the client.
        illagerRenderers(event, useGecko, new RegistryObject[] { ModEntities.GUNNER_PILLAGER,
                ModEntities.SNIPER_PILLAGER, ModEntities.BEAR_PILLAGER, ModEntities.ELITE_PILLAGER });

        // Grenades (README 5v): the vanilla thrown-item renderer draws whatever item the entity carries, so all
        // five throwables share one renderer and adding a sixth needs no client change at all.
        renderer(event, ModEntities.GRENADE,
                net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        // The signal stick (the command system) is a thrown item too, so it uses the same vanilla renderer
        // and draws whatever item the entity carries.
        renderer(event, ModEntities.SIGNAL_STICK,
                net.minecraft.client.renderer.entity.ThrownItemRenderer::new);

        // The weapon rack's item: a block entity renderer, because the item on it can be anything (5n).
        event.registerBlockEntityRenderer(com.gfl.tarkovscav.registry.ModBlocks.WEAPON_RACK_BE.get(),
                WeaponRackRenderer::new);
        // The creative twin needs the SAME renderer registered under its own block entity type: two types
        // mean two registrations, and without this the endless rack would be an empty-looking block.
        event.registerBlockEntityRenderer(com.gfl.tarkovscav.registry.ModBlocks.CREATIVE_WEAPON_RACK_BE.get(),
                WeaponRackRenderer::new);
    }

    /**
     * Registers one entity renderer and remembers that it happened, so {@link #onLoadComplete} can tell
     * whether any entity type was left out.
     */
    private static <T extends Entity> void renderer(EntityRenderersEvent.RegisterRenderers event,
                                                    RegistryObject<EntityType<T>> type,
                                                    EntityRendererProvider<T> provider) {
        event.registerEntityRenderer(type.get(), provider);
        RENDERED.add(type.getId());
    }

    /**
     * The villager-bodied family (the gunner villager and its sniper sibling): one call site, one loop, so no
     * member can be given a renderer while another is forgotten - the same shape as
     * {@link #illagerRenderers}, and the reason the sniper villager cannot repeat the 2026-09-23 crash.
     */
    @SafeVarargs
    private static void villagerRenderers(EntityRenderersEvent.RegisterRenderers event,
                                          RegistryObject<? extends EntityType<? extends GunnerVillagerEntity>>...
                                                  types) {
        for (RegistryObject<? extends EntityType<? extends GunnerVillagerEntity>> type : types) {
            EntityType<? extends GunnerVillagerEntity> entityType = type.get();
            event.registerEntityRenderer(entityType, GunnerVillagerRenderer::new);
            RENDERED.add(type.getId());
        }
    }

    /**
     * The illager-bodied family (pillager and its sniper sibling): one call site, one loop, so no member can
     * be given a renderer while another is forgotten. The wildcard is what makes the shared renderer classes
     * acceptable here - they are declared for the base type and are valid supertypes of each member.
     */
    @SafeVarargs
    private static void illagerRenderers(EntityRenderersEvent.RegisterRenderers event, boolean useGecko,
                                         RegistryObject<? extends EntityType<? extends GunnerPillagerEntity>>...
                                                 types) {
        if (useGecko) {
            TarkovScav.LOGGER.info("Illager-bodied mobs: using the GeckoLib Bedrock renderer");
        }
        for (RegistryObject<? extends EntityType<? extends GunnerPillagerEntity>> type : types) {
            EntityType<? extends GunnerPillagerEntity> entityType = type.get();
            event.registerEntityRenderer(entityType,
                    useGecko ? GunnerPillagerGeoRenderer::new : GunnerPillagerRenderer::new);
            RENDERED.add(type.getId());
        }
    }

    /**
     * The startup guard: every entity type declared in {@link ModEntities} must have a renderer. A missing
     * one is logged as an ERROR (it would otherwise crash the client the first time it is seen), and the
     * registered/total count is logged either way, so "which mobs exist and are they all drawn?" is one
     * question with one answer in the log.
     */
    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            int total = 0;
            int missing = 0;
            for (java.lang.reflect.Field field : ModEntities.class.getDeclaredFields()) {
                if (!RegistryObject.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException inaccessible) {
                    continue;
                }
                if (!(value instanceof RegistryObject<?> object)
                        || !(object.get() instanceof EntityType<?> type)) {
                    continue;
                }
                total++;
                if (RENDERED.contains(object.getId())) {
                    TarkovScav.LOGGER.debug("[client] entity renderer: {} -> ok", object.getId());
                } else {
                    missing++;
                    TarkovScav.LOGGER.error("[client] entity type {} has NO renderer registered in"
                                    + " ClientSetup: the client will crash with a NullPointerException in"
                                    + " EntityRenderDispatcher.render() the first time this entity is"
                                    + " visible. Add it to ClientSetup.registerAll().",
                            object.getId());
                }
            }
            TarkovScav.LOGGER.info("[client] entity renderers registered: {}/{}", total - missing, total);
        });
    }
}
