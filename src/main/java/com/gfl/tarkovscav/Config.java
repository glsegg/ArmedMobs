package com.gfl.tarkovscav;

import com.gfl.tarkovscav.client.PoseSource;
import com.gfl.tarkovscav.entity.ScavTier;
import com.gfl.tarkovscav.gun.AiProfile;
import com.gfl.tarkovscav.gun.GunAiState;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Common (server + client) configuration. The file lives at
 * {@code config/tarkovscav-common.toml}.
 *
 * <p>Everything a server operator needs to tune is here: where these mobs may spawn, which TaCZ
 * guns they may be issued, how each tier fights, how the Scav AI uses cover, and which bones of the
 * Bedrock rig are hidden.</p>
 */
public final class Config {
    public static final ForgeConfigSpec SPEC;

    // ------------------------------------------------------------------ spawn
    public static final ForgeConfigSpec.BooleanValue SCAV_CITY_ONLY;
    public static final ForgeConfigSpec.BooleanValue GUNNER_PILLAGER_CITY_ONLY;
    public static final ForgeConfigSpec.BooleanValue GUNNER_VILLAGER_CITY_ONLY;
    public static final ForgeConfigSpec.BooleanValue GUNNER_VILLAGER_NATURAL_SPAWN;
    public static final ForgeConfigSpec.BooleanValue GATE_COMMAND_SPAWNS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CITY_STRUCTURE_IDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CITY_STRUCTURE_TAGS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CITY_REGIONS;
    public static final ForgeConfigSpec.IntValue CITY_REGION_PADDING;
    public static final ForgeConfigSpec.IntValue CITY_FOUNDATION_DEPTH;
    public static final ForgeConfigSpec.BooleanValue LOG_SPAWN_GATE;

    // ------------------------------------------------------------------ guns
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_WHITELIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_GUN_TYPES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PISTOL_CLIP_TYPES;
    public static final ForgeConfigSpec.BooleanValue EXCLUDE_SCRIPTED_GUNS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TRUSTED_SCRIPT_NAMESPACES;
    public static final ForgeConfigSpec.IntValue SCRIPTED_GUN_RESCAN_TICKS;
    public static final ForgeConfigSpec.BooleanValue RESPECT_DECLARED_FIRE_MODES;
    public static final ForgeConfigSpec.DoubleValue GUN_DROP_CHANCE;
    public static final ForgeConfigSpec.DoubleValue AMMO_DROP_CHANCE;
    public static final ForgeConfigSpec.IntValue AMMO_ITEM_STACKS;
    public static final ForgeConfigSpec.BooleanValue MANUAL_RELOAD_FALLBACK;
    public static final ForgeConfigSpec.IntValue MANUAL_RELOAD_TICKS;
    public static final ForgeConfigSpec.IntValue RELOAD_STALL_TICKS;

    // ------------------------------------------------------------------ combat
    public static final ForgeConfigSpec.IntValue REACTION_TICKS;
    public static final ForgeConfigSpec.IntValue GIVE_UP_TICKS;
    public static final ForgeConfigSpec.DoubleValue RETREAT_HEALTH_FRACTION;
    public static final ForgeConfigSpec.IntValue REPOSITION_TICKS;
    public static final ForgeConfigSpec.IntValue TARGET_MEMORY_TICKS;
    public static final ForgeConfigSpec.IntValue FIRE_STALL_TICKS;
    public static final ForgeConfigSpec.BooleanValue LOG_GUN_AI;

    // ------------------------------------------------------------------ ai (doors, README 5a)
    public static final ForgeConfigSpec.BooleanValue CLOSE_DOORS_BEHIND;
    public static final ForgeConfigSpec.IntValue DOOR_CLOSE_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue DOOR_CLOSE_ALLY_RADIUS;

    // ------------------------------------------------------------------ ai tiers (README 5aa)
    public static final ForgeConfigSpec.BooleanValue AI_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AI_COORD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue AI_COORD_FOCUS_FIRE;
    public static final ForgeConfigSpec.BooleanValue AI_COORD_OVERWATCH;
    public static final ForgeConfigSpec.BooleanValue AI_COORD_FLANKING;
    public static final ForgeConfigSpec.BooleanValue AI_COORD_COVER_CLAIMS;
    public static final ForgeConfigSpec.DoubleValue AI_COORD_SQUAD_RADIUS;
    public static final ForgeConfigSpec.IntValue AI_COORD_SQUAD_CACHE_TICKS;
    public static final ForgeConfigSpec.IntValue AI_COORD_OVERWATCH_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue AI_COORD_OVERWATCH_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.DoubleValue AI_COORD_FLANK_FRACTION;
    public static final ForgeConfigSpec.DoubleValue AI_COORD_FLANK_OFFSET;
    public static final ForgeConfigSpec.IntValue AI_COORD_COVER_CLAIM_TICKS;

    // ------------------------------------------------------------------ tactics (cover + suppression)
    public static final ForgeConfigSpec.IntValue COVER_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue COVER_CACHE_TICKS;
    public static final ForgeConfigSpec.IntValue COVER_SAMPLES;
    public static final ForgeConfigSpec.DoubleValue SUPPRESS_CHANCE;
    public static final ForgeConfigSpec.IntValue SUPPRESS_TICKS;
    public static final ForgeConfigSpec.DoubleValue SUPPRESS_ACCURACY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue SUPPRESS_BURST_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue ADVANCE_COVER_STEP;
    public static final ForgeConfigSpec.DoubleValue COVER_SEEK_SPEED_MODIFIER;
    public static final ForgeConfigSpec.BooleanValue RETREAT_SPRINT;
    public static final ForgeConfigSpec.DoubleValue ESCAPE_WITHOUT_COVER_SPEED_MODIFIER;
    public static final ForgeConfigSpec.IntValue MOVE_PROGRESS_SAMPLE_TICKS;
    public static final ForgeConfigSpec.DoubleValue MOVE_PROGRESS_MIN_BLOCKS;
    public static final ForgeConfigSpec.IntValue MOVE_PROGRESS_RETRIES;
    public static final ForgeConfigSpec.IntValue UNDER_FIRE_TICKS;
    public static final ForgeConfigSpec.DoubleValue HURT_RETREAT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DISTANCE_ACCURACY_NEAR;
    public static final ForgeConfigSpec.DoubleValue DISTANCE_ACCURACY_MID;
    public static final ForgeConfigSpec.DoubleValue DISTANCE_ACCURACY_FAR;

    // ------------------------------------------------------------------ voice
    public static final ForgeConfigSpec.BooleanValue VOICE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue VOICE_IDLE;
    public static final ForgeConfigSpec.IntValue VOICE_IDLE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue VOICE_IDLE_JITTER_TICKS;
    public static final ForgeConfigSpec.BooleanValue VOICE_CONTACT;
    public static final ForgeConfigSpec.IntValue VOICE_CONTACT_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue VOICE_CHATTER;
    public static final ForgeConfigSpec.IntValue VOICE_CHATTER_MIN_TICKS;
    public static final ForgeConfigSpec.IntValue VOICE_CHATTER_MAX_TICKS;
    public static final ForgeConfigSpec.BooleanValue VOICE_GRENADE;
    public static final ForgeConfigSpec.DoubleValue VOICE_GRENADE_RADIUS;
    public static final ForgeConfigSpec.IntValue VOICE_GRENADE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue VOICE_GRENADE_SHOUT_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue VOICE_MARK;
    public static final ForgeConfigSpec.BooleanValue VOICE_DEATH;
    public static final ForgeConfigSpec.DoubleValue VOICE_VOLUME;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VOICE_FAMILY_VOLUME;
    public static final ForgeConfigSpec.DoubleValue VOICE_EFFECT_VOLUME;
    public static final ForgeConfigSpec.DoubleValue VOICE_PITCH_MIN;
    public static final ForgeConfigSpec.DoubleValue VOICE_PITCH_MAX;
    public static final ForgeConfigSpec.DoubleValue VOICE_PITCH_JITTER;

    // ------------------------------------------------------------------ faction
    public static final ForgeConfigSpec.BooleanValue FACTION_ENABLED;
    public static final ForgeConfigSpec.BooleanValue VILLAGER_ATTACK_MONSTERS;
    public static final ForgeConfigSpec.IntValue FACTION_FRIENDLY_FIRE_HITS_TO_ANGER;
    public static final ForgeConfigSpec.IntValue FACTION_FRIENDLY_FIRE_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue FACTION_BETRAYAL_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue FACTION_RENEGADE_BROADCAST_RADIUS;
    public static final ForgeConfigSpec.IntValue FACTION_RENEGADE_DECAY_TICKS;
    public static final ForgeConfigSpec.BooleanValue FACTION_RENEGADE_GLOW;

    // ------------------------------------------------------------------ alert network
    public static final ForgeConfigSpec.BooleanValue ALERT_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ALERT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ALERT_NEAR_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ALERT_MID_DISTANCE;
    public static final ForgeConfigSpec.IntValue ALERT_MEMORY_TICKS;
    public static final ForgeConfigSpec.IntValue ALERT_MAX_RECIPIENTS;
    public static final ForgeConfigSpec.IntValue ALERT_BROADCAST_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue ALERT_BEARING_NOISE_DEGREES;
    public static final ForgeConfigSpec.DoubleValue ALERT_CONVERGE_RADIUS;
    public static final ForgeConfigSpec.IntValue ALERT_CONVERGE_MAX_ALLIES;
    public static final ForgeConfigSpec.DoubleValue ALERT_SURROUND_STANDOFF;
    public static final ForgeConfigSpec.IntValue ALERT_MIN_REPATH_INTERVAL_TICKS;

    // ------------------------------------------------------------------ client / model
    public static final ForgeConfigSpec.BooleanValue USE_GECKO_MODEL;
    public static final ForgeConfigSpec.DoubleValue RENDER_SCALE;
    public static final ForgeConfigSpec.DoubleValue VILLAGER_RENDER_SCALE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> HIDDEN_BONES;
    public static final ForgeConfigSpec.BooleanValue LOG_HIDDEN_BONES;
    public static final ForgeConfigSpec.ConfigValue<String> GUN_ANCHOR_BONE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_RIFLE_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_RIFLE_OFFSET;
    public static final ForgeConfigSpec.DoubleValue GUN_MOUNT_RIFLE_SCALE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_PISTOL_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_PISTOL_OFFSET;
    public static final ForgeConfigSpec.DoubleValue GUN_MOUNT_PISTOL_SCALE;
    public static final ForgeConfigSpec.ConfigValue<String> GUN_MOUNT_DISPLAY_CONTEXT;
    public static final ForgeConfigSpec.ConfigValue<String> GUN_ANCHOR_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_LAYERING;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL_RENDER_TYPE;
    public static final ForgeConfigSpec.DoubleValue CULLING_BOX_PADDING;
    public static final ForgeConfigSpec.BooleanValue LOG_RENDER_STATS;
    public static final ForgeConfigSpec.BooleanValue LOG_GL_STATE;
    public static final ForgeConfigSpec.ConfigValue<String> POSE_SOURCE;
    public static final ForgeConfigSpec.ConfigValue<String> MOLANG_VARIABLES;
    public static final ForgeConfigSpec.BooleanValue LOG_POSE_WRITERS;
    // ------------------------------------------------------------------ player lean (README 5s)
    public static final ForgeConfigSpec.BooleanValue LEAN_ENABLED;
    public static final ForgeConfigSpec.DoubleValue LEAN_MAX_OFFSET;
    public static final ForgeConfigSpec.DoubleValue LEAN_ROLL_DEGREES;
    public static final ForgeConfigSpec.BooleanValue LEAN_INVERT_OFFSET;
    public static final ForgeConfigSpec.BooleanValue LEAN_INVERT_ROLL;
    public static final ForgeConfigSpec.IntValue LEAN_SPEED_TICKS;
    public static final ForgeConfigSpec.BooleanValue LEAN_SUPPRESS_VANILLA_KEYS;
    public static final ForgeConfigSpec.IntValue LEAN_TAP_THRESHOLD_TICKS;
    public static final ForgeConfigSpec.ConfigValue<String> LEAN_START_MODE;
    public static final ForgeConfigSpec.BooleanValue LEAN_REPLAY_VANILLA_ON_TAP;
    // ------------------------------------------------------------------ kill feed (README 5u)
    public static final ForgeConfigSpec.BooleanValue KILLFEED_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> KILLFEED_MODE;
    public static final ForgeConfigSpec.DoubleValue KILLFEED_RADIUS;
    public static final ForgeConfigSpec.BooleanValue KILLFEED_SHOW_MOB_KILLS;
    public static final ForgeConfigSpec.BooleanValue KILLFEED_SHOW_PLAYER_KILLS;
    public static final ForgeConfigSpec.BooleanValue KILLFEED_SHOW_ENVIRONMENT_DEATHS;
    public static final ForgeConfigSpec.IntValue KILLFEED_LINE_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue KILLFEED_MAX_LINES;
    public static final ForgeConfigSpec.ConfigValue<String> KILLFEED_POSITION;
    public static final ForgeConfigSpec.DoubleValue KILLFEED_SCALE;
    public static final ForgeConfigSpec.IntValue KILLFEED_MAX_PER_SECOND;
    public static final ForgeConfigSpec.IntValue KILLFEED_DEDUP_TICKS;
    // ------------------------------------------------------------------ grenades (README 5v)
    // ------------------------------------------------------------------ faction troops (README 5y)
    public static final ForgeConfigSpec.BooleanValue ARMOR_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ARMOR_REDUCTION_PER_CLASS;
    public static final ForgeConfigSpec.IntValue ELITE_MIN_ARMOR_CLASS;
    public static final ForgeConfigSpec.IntValue ELITE_MAX_ARMOR_CLASS;
    public static final ForgeConfigSpec.IntValue USEC_VILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue BEAR_PILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue ELITE_VILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue ELITE_PILLAGER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.BooleanValue RICOCHET_ENABLED;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_GUN_DAMAGE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_CHANCE;
    public static final ForgeConfigSpec.IntValue RICOCHET_MAX_BOUNCES_PER_BULLET;
    public static final ForgeConfigSpec.BooleanValue RICOCHET_INCLUDE_ARROWS;
    public static final ForgeConfigSpec.DoubleValue RICOCHET_SOUND_VOLUME;
    public static final ForgeConfigSpec.BooleanValue RICOCHET_SPARKS;
    public static final ForgeConfigSpec.BooleanValue RICOCHET_LOG;
    // ------------------------------------------------------------------ the VANT ballistic shield (README 5zb)
    public static final ForgeConfigSpec.BooleanValue SHIELD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SHIELD_BULLET_REDUCTION;
    public static final ForgeConfigSpec.DoubleValue SHIELD_FRONT_ANGLE_DEGREES;
    public static final ForgeConfigSpec.DoubleValue SHIELD_DURABILITY_PER_BLOCKED_HIT;
    public static final ForgeConfigSpec.BooleanValue SHIELD_PROTECT_FROM_EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue GRENADES_ENABLED;
    public static final ForgeConfigSpec.BooleanValue GRENADES_TERRAIN_DAMAGE;
    public static final ForgeConfigSpec.IntValue GRENADES_THROW_CHARGE_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADES_MIN_THROW_SPEED;
    public static final ForgeConfigSpec.DoubleValue GRENADES_MAX_THROW_SPEED;
    public static final ForgeConfigSpec.BooleanValue GRENADES_COOK_WHILE_HOLDING;
    public static final ForgeConfigSpec.BooleanValue GRENADES_FRIENDLY_FIRE;
    public static final ForgeConfigSpec.BooleanValue GRENADES_PLAYER_SELF_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_BLAST_DAMAGE_PER_POWER;
    public static final ForgeConfigSpec.BooleanValue GRENADE_IMPACT_SOUND;
    public static final ForgeConfigSpec.IntValue GRENADE_IMPACT_SOUND_MAX_PER_GRENADE;
    public static final ForgeConfigSpec.IntValue GRENADE_IMPACT_SOUND_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_FRAG_FUSE_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_FRAG_COUNT;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FRAG_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FRAG_RADIUS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FRAG_ARMOR_PIERCE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FRAG_STEP;
    public static final ForgeConfigSpec.IntValue GRENADE_HE_FUSE_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_HE_BLAST_POWER;
    public static final ForgeConfigSpec.IntValue GRENADE_HE_FRAG_COUNT;
    public static final ForgeConfigSpec.DoubleValue GRENADE_HE_FRAG_DAMAGE;
    public static final ForgeConfigSpec.IntValue GRENADE_SMOKE_FUSE_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_SMOKE_RADIUS;
    public static final ForgeConfigSpec.IntValue GRENADE_SMOKE_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_FLASH_FUSE_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FLASH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FLASH_INTENSITY;
    public static final ForgeConfigSpec.IntValue GRENADE_FLASH_PLAYER_BLIND_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_FLASH_MOB_BLIND_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FLASH_LOOK_AWAY_FACTOR;
    public static final ForgeConfigSpec.BooleanValue GRENADE_FLASH_BLINDS_MOBS;
    public static final ForgeConfigSpec.BooleanValue GRENADE_FLASH_PANIC_FIRE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FLASH_PANIC_SPREAD_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue GRENADE_FLASH_PANIC_BURST_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_FLASH_SHORT_FUSE_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_FLASH_SHORT_BLIND_FACTOR;
    public static final ForgeConfigSpec.BooleanValue MOB_GRENADES_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MOB_GRENADE_CARRY_CHANCE;
    public static final ForgeConfigSpec.IntValue MOB_GRENADE_MAX_PER_MOB;
    public static final ForgeConfigSpec.IntValue MOB_GRENADE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue MOB_GRENADE_MIN_RANGE;
    public static final ForgeConfigSpec.DoubleValue MOB_GRENADE_MAX_RANGE;
    public static final ForgeConfigSpec.DoubleValue MOB_GRENADE_ALLY_SAFETY_RADIUS;
    public static final ForgeConfigSpec.IntValue MOB_GRENADE_ALLY_SAFETY_MAX;
    public static final ForgeConfigSpec.IntValue MOB_GRENADE_ARC_SAMPLES;
    public static final ForgeConfigSpec.BooleanValue MOB_GRENADE_REQUIRE_CLEAR_ARC;
    public static final ForgeConfigSpec.DoubleValue MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES;
    public static final ForgeConfigSpec.IntValue MOB_GRENADE_RETRY_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue GRENADES_RESUPPLY_ENABLED;
    public static final ForgeConfigSpec.DoubleValue GRENADES_RESUPPLY_RADIUS;
    public static final ForgeConfigSpec.IntValue GRENADES_RESUPPLY_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADES_RESUPPLY_SEARCH_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.ConfigValue<String> GRENADES_RACK_PRIORITY;
    public static final ForgeConfigSpec.DoubleValue TORSO_YAW_SHARE;
    public static final ForgeConfigSpec.ConfigValue<String> GUN_OFFHAND_ANCHOR_BONE;
    public static final ForgeConfigSpec.BooleanValue RENDER_OFFHAND_ITEM;
    public static final ForgeConfigSpec.BooleanValue GUN_TWO_HANDED_SUPPORT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_OFFHAND_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_MOUNT_OFFHAND_OFFSET;
    public static final ForgeConfigSpec.DoubleValue GUN_MOUNT_OFFHAND_SCALE;
    public static final ForgeConfigSpec.BooleanValue LOG_GUN_MOUNT;

    // ------------------------------------------------------------------ weapon rack
    public static final ForgeConfigSpec.BooleanValue RACK_ENABLED;
    public static final ForgeConfigSpec.BooleanValue RACK_ACCEPTS_ANY_ITEM;
    public static final ForgeConfigSpec.DoubleValue RACK_TAKE_RADIUS;
    public static final ForgeConfigSpec.IntValue RACK_TAKE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue RACK_TAKE_CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.ConfigValue<String> RACK_PRIORITY;
    public static final ForgeConfigSpec.BooleanValue RACK_ABSORB_DROPPED_ITEMS;
    public static final ForgeConfigSpec.DoubleValue RACK_ABSORB_RADIUS;
    public static final ForgeConfigSpec.DoubleValue RACK_ABSORB_HEIGHT;
    public static final ForgeConfigSpec.IntValue RACK_ABSORB_CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue RACK_CREATIVE_RACK_ENABLED;

    // ------------------------------------------------------------------ accuracy profile
    public static final ForgeConfigSpec.BooleanValue ACCURACY_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ACCURACY_HARD_CEILING;
    public static final ForgeConfigSpec.DoubleValue ACCURACY_ROOKIE_CAP;
    public static final ForgeConfigSpec.DoubleValue ACCURACY_VETERAN_CAP;
    public static final ForgeConfigSpec.DoubleValue ACCURACY_ELITE_CAP;
    public static final ForgeConfigSpec.ConfigValue<String> ACCURACY_PROFILE_SCAV;
    public static final ForgeConfigSpec.ConfigValue<String> ACCURACY_PROFILE_GUNNER_PILLAGER;
    public static final ForgeConfigSpec.ConfigValue<String> ACCURACY_PROFILE_GUNNER_VILLAGER;
    public static final ForgeConfigSpec.ConfigValue<String> ACCURACY_PROFILE_SNIPER_TIER;
    public static final ForgeConfigSpec.IntValue ACCURACY_WARMUP_SHOTS;
    public static final ForgeConfigSpec.DoubleValue ACCURACY_WARMUP_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue ACCURACY_RESET_TICKS;

    // ------------------------------------------------------------------ gun modding pool (gun-modder)
    public static final ForgeConfigSpec.BooleanValue MODS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MODS_PER_SLOT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue MODS_FULL_MOD_CHANCE;
    public static final ForgeConfigSpec.IntValue MODS_MAX_PER_GUN;
    public static final ForgeConfigSpec.BooleanValue MODS_ALLOW_EXTENDED_MAG;

    // ------------------------------------------------------------------ sniper pillager
    public static final ForgeConfigSpec.BooleanValue SNIPER_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SNIPER_FOLLOW_RANGE;

    /**
     * The FOLLOW_RANGE the two snipers are <b>registered</b> with, and the default of
     * {@link #SNIPER_FOLLOW_RANGE}.
     *
     * <p>One constant, two jobs, because they must agree: {@code EntityAttributeCreationEvent} runs while
     * Forge is still loading the config, so reading {@link #SNIPER_FOLLOW_RANGE} there threw
     * {@code Cannot get config value before config is loaded} and killed the server before the first world
     * tick. The attribute set therefore uses this plain number and the configured value is applied when the
     * entity actually enters a level; see {@code SniperMob#applySniperFollowRange}. Keeping the config
     * default equal to this constant is what makes "config loaded" a no-op rather than a jump.</p>
     */
    public static final double DEFAULT_SNIPER_FOLLOW_RANGE = 64.0D;
    public static final ForgeConfigSpec.DoubleValue SNIPER_DISCOVERED_RANGE;
    public static final ForgeConfigSpec.IntValue SNIPER_SHOTS_BEFORE_MOVE;
    public static final ForgeConfigSpec.DoubleValue SNIPER_MIN_POST_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue SNIPER_CLOSE_RANGE;
    public static final ForgeConfigSpec.DoubleValue SNIPER_MOVE_SPEED;
    public static final ForgeConfigSpec.IntValue SNIPER_RELOCATE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.DoubleValue SNIPER_MIN_SPAWN_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SNIPER_PREFER_HIGH_GROUND;
    public static final ForgeConfigSpec.IntValue SNIPER_MIN_ELEVATION;
    public static final ForgeConfigSpec.IntValue SNIPER_SPAWN_WEIGHT;
    public static final ForgeConfigSpec.IntValue SNIPER_VILLAGER_SPAWN_WEIGHT;

    // ------------------------------------------------------------------ city garrison (one-time)
    public static final ForgeConfigSpec.BooleanValue GARRISON_ENABLED;
    public static final ForgeConfigSpec.IntValue GARRISON_SQUADS_PER_CITY;
    public static final ForgeConfigSpec.IntValue GARRISON_SQUAD_SIZE_MIN;
    public static final ForgeConfigSpec.IntValue GARRISON_SQUAD_SIZE_MAX;
    public static final ForgeConfigSpec.DoubleValue GARRISON_ELITE_LEADER_CHANCE;
    public static final ForgeConfigSpec.DoubleValue GARRISON_TRIGGER_RADIUS;
    public static final ForgeConfigSpec.IntValue GARRISON_CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue GARRISON_FRIENDLY_CITY_CHANCE;
    public static final ForgeConfigSpec.DoubleValue GARRISON_CITY_DOMINANT_FACTION_CHANCE;
    public static final ForgeConfigSpec.BooleanValue GARRISON_REWRITE_CITY_SPAWNERS;
    public static final ForgeConfigSpec.BooleanValue GARRISON_FACTION_SPAWN_FILTER;

    // ------------------------------------------------------------------ the command system
    public static final ForgeConfigSpec.BooleanValue COMMAND_ENABLED;
    public static final ForgeConfigSpec.DoubleValue COMMAND_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_SPEED_SCALE;
    public static final ForgeConfigSpec.DoubleValue COMMAND_ARRIVAL_RADIUS;
    public static final ForgeConfigSpec.IntValue COMMAND_STICK_DURATION_TICKS;
    public static final ForgeConfigSpec.BooleanValue COMMAND_COORDINATION;
    public static final ForgeConfigSpec.IntValue COMMAND_MAX_MARKS;

    // ------------------------------------------------------------------ ladder climbing (README 7o)
    public static final ForgeConfigSpec.BooleanValue LADDER_ENABLED;
    public static final ForgeConfigSpec.DoubleValue LADDER_CLIMB_SPEED;
    public static final ForgeConfigSpec.DoubleValue LADDER_DOWN_SPEED;
    public static final ForgeConfigSpec.IntValue LADDER_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue LADDER_MAX_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue LADDER_COMBAT_WHILE_CLIMBING;
    public static final ForgeConfigSpec.BooleanValue LADDER_FALL_DAMAGE_IN_SHAFT;

    // ------------------------------------------------------------------ city capture (README 7p)
    public static final ForgeConfigSpec.BooleanValue CAPTURE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue CAPTURE_HUD_ENABLED;
    public static final ForgeConfigSpec.IntValue CAPTURE_POOL_MIN;
    public static final ForgeConfigSpec.IntValue CAPTURE_POOL_MAX;
    public static final ForgeConfigSpec.IntValue CAPTURE_POOL_PER_BUILDING;
    public static final ForgeConfigSpec.IntValue CAPTURE_DRAIN_PER_KILL;
    public static final ForgeConfigSpec.BooleanValue CAPTURE_PLAYER_KILLS_ONLY;
    public static final ForgeConfigSpec.IntValue CAPTURE_HUD_HIDE_DELAY_SECONDS;

    // ------------------------------------------------------------------ client / gunner villager pose
    public static final ForgeConfigSpec.DoubleValue GUNNER_VILLAGER_AIM_ARM_PITCH;
    public static final ForgeConfigSpec.DoubleValue GUNNER_VILLAGER_HOLD_ARM_PITCH;
    public static final ForgeConfigSpec.DoubleValue GUNNER_VILLAGER_RELOAD_ARM_PITCH;
    public static final ForgeConfigSpec.DoubleValue GUNNER_VILLAGER_HUNKER_ARM_PITCH;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_GUN_OFFSET;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_GUN_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_IDLE_GUN_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_RELOAD_GUN_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_HUNKER_GUN_ROTATION;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_IDLE_GUN_OFFSET;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_RELOAD_GUN_OFFSET;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNNER_VILLAGER_HUNKER_GUN_OFFSET;
    public static final ForgeConfigSpec.DoubleValue GUNNER_VILLAGER_GUN_SCALE;
    public static final ForgeConfigSpec.ConfigValue<String> GUNNER_VILLAGER_GUN_ANCHOR;
    public static final ForgeConfigSpec.BooleanValue HIDE_GUN_WHEN_IDLE;

    // ------------------------------------------------------------------ client / head accessories
    public static final ForgeConfigSpec.ConfigValue<String> HAT_ACCESSORY;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> HAT_BONES;
    public static final ForgeConfigSpec.ConfigValue<String> KEPT_HAT_BONE;
    public static final ForgeConfigSpec.ConfigValue<String> EYE_GEAR_ACCESSORY;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EYE_GEAR_BONES;
    public static final ForgeConfigSpec.BooleanValue CIGARETTE;
    public static final ForgeConfigSpec.ConfigValue<String> CIGARETTE_BONE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_HIDDEN_BONES;
    public static final ForgeConfigSpec.DoubleValue HEAD_REST_PITCH_DEGREES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> HEAD_REST_PITCH_STATES;

    /** Per-tier tuning, keyed by {@link ScavTier}. */
    private static final Map<ScavTier, TierSettings> TIERS = new EnumMap<>(ScavTier.class);

    /** The intelligence tier knobs, keyed by {@link AiProfile.Tier} (README 5aa). */
    private static final Map<AiProfile.Tier, AiSettings> AI_TIERS = new EnumMap<>(AiProfile.Tier.class);

    // ------------------------------------------------------------------ the shipped mount baseline
    // ONE source of truth for the defaults: the spec below builds its keys from these, and
    // /tarkovscav client gunpose reset restores exactly them. They can never drift apart.
    //
    // The offset is the USER'S MEASURED VALUE, not a simulation guess: in game, with
    // gunAnchorMode = normalisedHand (the default), "/tarkovscav client gunpose z=-0.7" put the rifle
    // where it belongs. In that hand frame the fore/aft axis is Z (negative = towards the muzzle), which
    // is the empirical answer to "which axis is forward" - the frame's axes are the item model's own,
    // and the gun mesh is rotated inside them by TaCZ's positioning groups, so the only reliable
    // mapping is the one that was measured in game.
    public static final List<String> DEFAULT_MOUNT_ROTATION = List.of("0", "0", "0");
    /** 0.7 blocks towards the muzzle, along the hand frame's -Z (user-measured). */
    public static final List<String> DEFAULT_MOUNT_OFFSET = List.of("0", "0", "-0.7");
    public static final double DEFAULT_MOUNT_SCALE = 1.0D;
    /**
     * The shipped model size. <b>0.7 is the baseline the rig is authored at, and it does not move</b>:
     * the user's "1.1 times bigger" is a matter of taste, so it is expressed as {@code renderScale = 0.77}
     * (= 0.7 x 1.1) in their own toml, or live with {@code /tarkovscav client scale 0.77}.
     */
    public static final double DEFAULT_RENDER_SCALE = 0.77D;   // = 0.7 x 1.1, the user value (release baseline)

    /**
     * The villager family's render scale. <b>1.0 = the vanilla villager size</b>, because the vanilla model
     * is authored at 1.0 - the Bedrock rig's 0.7 baseline does not apply to it (README 5j).
     */
    public static final double DEFAULT_VILLAGER_RENDER_SCALE = 1.0D;
    /** Allowed range for {@code /tarkovscav client scale}; a hand-edited toml is clamped to it too. */
    public static final double RENDER_SCALE_MIN = 0.3D;
    public static final double RENDER_SCALE_MAX = 2.0D;
    /** Default step for {@code scale up} / {@code scale down}. */
    public static final double RENDER_SCALE_STEP = 0.05D;
    public static final String DEFAULT_MOUNT_DISPLAY_CONTEXT = "THIRD_PERSON_RIGHT_HAND";
    public static final String DEFAULT_ANCHOR_MODE = "normalisedHand";
    public static final String DEFAULT_MODEL_LAYERING = "upperLower";
    public static final String DEFAULT_MODEL_RENDER_TYPE = "cutout";
    public static final double DEFAULT_CULLING_BOX_PADDING = 1.0D;

    /**
     * The shipped {@code client.molangVariables}: the pitch symbols only. See the key's comment - the
     * yaw symbols are the author's body-yaw terms, which GeckoLib already applies at the root.
     */
    public static final String DEFAULT_MOLANG_VARIABLES = "pitch";

    /**
     * The shipped {@code client.torsoYawShare}. 0.25 keeps a small stylistic chest lean (16.2 degrees
     * peak-to-peak over a +/-25 degree look swing) where the old 0.7 gave 45.3 degrees, the size of the
     * reported twist, while the head still carries the whole of the look either way.
     */
    public static final double DEFAULT_TORSO_YAW_SHARE = 0.25D;
    /**
     * The shipped voice pitch band. Vanilla mobs vary their voice with {@code getVoicePitch()} (0.8-1.2,
     * drawn once per mob), which is why a crowd of pillagers does not sound like one recording played
     * repeatedly; the scav clips reproduce that idea with an explicit, tunable band.
     *
     * <p><b>0.9-1.1 is deliberately narrower than vanilla's 0.8-1.2</b>: these are human speech clips from
     * one speaker, so a wide band makes the same person sound like a chipmunk in one spawn and a giant in
     * the next. 0.9-1.1 reads as "different people" without the formant artefacts a wider shift causes.
     * {@code voice.pitchJitter} adds a small per-line wobble on top, inside the band.</p>
     */
    public static final double DEFAULT_VOICE_PITCH_MIN = 0.9D;
    public static final double DEFAULT_VOICE_PITCH_MAX = 1.1D;
    public static final double DEFAULT_VOICE_PITCH_JITTER = 0.03D;
    /** Hard limits for the pitch band; a hand-edited toml is clamped to them. */
    public static final double VOICE_PITCH_FLOOR = 0.1D;
    public static final double VOICE_PITCH_CEILING = 2.0D;
    /**
     * NBT key holding a mob's own voice pitch, so the same mob keeps its voice across a save, a chunk
     * unload and a server restart. Namespaced because {@code getPersistentData()} is shared with Forge
     * and every other mod.
     */
    public static final String NBT_VOICE_PITCH = "tarkovscav:voicePitch";
    public static final List<String> DEFAULT_OFFHAND_ROTATION = List.of("0", "0", "0");
    public static final List<String> DEFAULT_OFFHAND_OFFSET = List.of("0", "0", "0");
    public static final double DEFAULT_OFFHAND_SCALE = 1.0D;
    /**
     * Degrees the vanilla villager's crossed-arms block is swung <b>above its rest</b> while the weapon is
     * raised (ALERT/AIM/FIRE/SUPPRESS/BOLT/REPOSITION).
     *
     * <p><b>An OFFSET, like all four arm keys.</b> The model captures the arms block's baked rotation from the
     * vanilla mesh ({@code PartPose.offsetAndRotation(0, 3, -1, -0.75F, 0, 0)}, about -43 degrees) and adds
     * this key to it, so <b>0 = the normal villager's arms</b> and negative lifts the gun higher. The aiming
     * silhouette the user confirmed in game was an absolute -100 degrees, which is an offset of -57.</p>
     */
    public static final double DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH = -57.0D;
    /**
     * Same, for the pose where a gun is held but not aimed (IDLE with a weapon in hand).
     *
     * <p><b>This is an OFFSET from the vanilla arm position, not an absolute angle.</b> The vanilla
     * {@code VillagerModel} bakes its crossed arms at {@code PartPose.offsetAndRotation(0, 3, -1, -0.75F, 0, 0)}
     * - verified in the 1.20.1 client jar's bytecode (the {@code m_171052_} mesh method: the two "arms" cubes
     * are followed by {@code ldc -0.75f}) - i.e. about <b>-43 degrees</b>, and vanilla never rewrites it. So
     * <b>0 (the shipped default) is exactly what a normal villager shows</b>; negative lifts the arms above
     * that rest, positive presses them down into the body.</p>
     *
     * <p><b>History, because the value has moved twice.</b> The idle pose used to write the raw angle, so 0
     * laid the crossed arms flat against the belly ("his hands lie flat against his body and clip into it"),
     * and -20 (the second idle-pose report: the hand and the gun seemed to sink into the body) was tried as a
     * workaround. Both were symptoms of the same mistake: a bare angle cannot express "the vanilla pose",
     * because vanilla's pose is not zero. The model now restores the captured baked value and adds this offset
     * to it, so 0 really is the normal villager and -20 really is "20 degrees above it".</p>
     *
     * <p>The arms are also the gun's anchor, so this angle moves the gun as well - which is why
     * {@link #DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION} takes the same 43 degrees back in the other
     * direction. See that key.</p>
     */
    public static final double DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH = 0.0D;
    /**
     * Same, for RELOAD - its own silhouette so "this one is reloading" reads at a glance. An OFFSET from the
     * vanilla rest like every other arm key: 0 = the arms sit exactly where a normal villager's do, negative
     * lifts them. The user asked for the reload pose to look like the idle one, so the shipped value is 0.
     */
    public static final double DEFAULT_GUNNER_VILLAGER_RELOAD_ARM_PITCH = 0.0D;
    /**
     * Same, for RETREAT (breaking contact) - shoulders in, weapon down, so "this one is leaving" is visible
     * from a distance. An OFFSET from the vanilla rest: 0 keeps the normal villager arms, positive presses
     * them down into the body.
     */
    public static final double DEFAULT_GUNNER_VILLAGER_HUNKER_ARM_PITCH = 0.0D;
    /**
     * Where the held gun sits relative to the villager's arm block, in blocks. The -0.12 forward slide is
     * the <b>user's pick between two offered values</b> (option B: keep the 0.06 lift, move 0.03 further
     * forward) after the arm pitch went to -90.
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_GUN_OFFSET = List.of("0", "0.06", "-0.09");
    /**
     * Rotation for the villager's held gun, degrees, applied X then Y then Z inside the arm frame.
     *
     * <p><b>Why a non-zero default.</b> The gun is placed with
     * {@code ArmedModel#translateToHand}, whose body is the same line {@code HumanoidModel} uses - so the
     * vanilla {@code ItemInHandLayer} then adds the <em>humanoid</em> hand frame
     * ({@code mulPose XP -90}, {@code mulPose YP 180}, {@code translate +/-1/16, 0.125, -0.625}). That frame
     * is authored for an arm hanging <b>down</b>; the villager's arm is the vanilla {@code arms} block, a
     * horizontal forearm bar across the chest whose baked orientation is about 90 degrees away from that,
     * so a large negative X is needed to turn the frame back.</p>
     *
     * <p><b>The shipped value is the user's own in-game calibration.</b> History: the factory value was
     * {@code -90}, the muzzle pointed at the sky ("现在这个朝天上看了") and the user asked for 50 degrees
     * back - {@code -90 + 50 = -40}, judged on the <b>aiming</b> silhouette. He then tuned it further in his
     * own instance and settled on {@code 5}, which is the release baseline; the four silhouettes it produces
     * are tabulated in README 5j. This triple is USER-MEASURED, not derived.</p>
     *
     * <p><b>The arm angle adds to this number.</b> The arm pitch and this rotation are both X rotations
     * applied to the same pose stack (the arm's own {@code translateAndRotate}, then this, then the
     * layer's constant {@code -90}), so the gun's tilt is {@code ARMS_REST + armOffset + gunPitch +
     * poseGunPitch - 90}: changing the arm pose moves the gun by exactly as much, in the same direction.
     * README 5j's table is the authoritative list of the four silhouettes (aiming -185, idle -130,
     * reloading -128, hunkered -128). If a pose reads as aiming at the ground, do NOT rotate this shared
     * base back: change that pose's own arm offset or gun delta instead, or split the difference with
     * {@code /tarkovscav client villagerpose pitch=-62}. See README 5j.</p>
     *
     * <p>Tune it live with {@code /tarkovscav client villagerpose pitch=...}; the command writes the
     * config, so the value survives a restart, and {@code client state} prints what is in force.</p>
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_GUN_ROTATION = List.of("5", "0", "0");

    /**
     * Extra gun rotation that applies <b>only while the villager is idle</b> (the LOWERED pose), on top of
     * {@link #DEFAULT_GUNNER_VILLAGER_GUN_ROTATION}.
     *
     * <p>Why it is needed: when the idle pose was changed to the vanilla crossed arms (README 5j, 2026), the
     * gun - which hangs off that arms block - inherited the rest orientation and ended up pointing UP. The
     * arm angle is already per-pose, so the gun's offset is per-pose too.</p>
     *
     * <p><b>How the shipped value was arrived at:</b> the gun is anchored on {@code arms} and placed with
     * {@code ArmedModel#translateToHand}, which walks the anchor's {@code translateAndRotate}. So the arm
     * angle and this rotation are X rotations on the same stack and <b>add up</b>: restoring the vanilla arm
     * position (-0.75 rad, about -43 degrees) from a flat 0 tilts the gun by another 43 degrees with it.
     * With the base at {@code 5} the shipped delta is {@code -2}, i.e. the idle tilt is
     * {@code ARMS_REST + 0 + 5 - 2 - 90 = -130} - the muzzle level, with the arms in the normal villager
     * position instead of flat. README 5j tabulates all four silhouettes (RAISED -185, IDLE -130,
     * RELOADING -128, HUNKERED -128).</p>
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION = List.of("-2", "0", "0");
    /**
     * Gun rotation ADDED while the villager is RELOADING, on top of
     * {@link #DEFAULT_GUNNER_VILLAGER_GUN_ROTATION} - the same per-pose arrangement as the arm angles.
     *
     * <p>{@code [0,0,0]} means "the base rotation and nothing else", which is exactly what shipped before this
     * key existed, so the default is <b>frame-for-frame unchanged</b>. It exists because the user asked for a
     * rotation per state ("不同状态下枪的旋转角度") and the reload silhouette is the one place where the gun
     * is being handled rather than held, so it is the most likely to need its own tilt.</p>
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION = List.of("0", "0", "0");
    /**
     * Gun rotation ADDED while the villager is HUNKERED (retreating), on top of
     * {@link #DEFAULT_GUNNER_VILLAGER_GUN_ROTATION}. {@code [0,0,0]} by default, so the retreat silhouette is
     * untouched until somebody tunes it with {@code /tarkovscav client villagerpose hunkerPitch=...}.
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION = List.of("0", "0", "0");
    /**
     * Weapon POSITION deltas, one triple per pose, in blocks - the same per-pose arrangement as the rotation
     * deltas above, and all-zero by default (so the shipped look is unchanged).
     *
     * <p>Why they exist: {@code gunnerVillagerGunOffset} is the position for EVERY pose, so "move the gun out
     * of his body while idle" was impossible - moving it moved the aiming pose the user had already
     * calibrated. With these, RAISED keeps the base offset exactly, and LOWERED / RELOADING / HUNKERED can be
     * nudged independently.</p>
     */
    public static final List<String> DEFAULT_GUNNER_VILLAGER_IDLE_GUN_OFFSET = List.of("0", "0", "0");
    public static final List<String> DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_OFFSET = List.of("0", "0", "0");
    public static final List<String> DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_OFFSET = List.of("0", "0", "0");
    /** Extra uniform scale for the villager's held gun (multiplied on top of TaCZ's own 0.6). */
    public static final double DEFAULT_GUNNER_VILLAGER_GUN_SCALE = 1.0D;
    /**
     * Which model part the gun hangs off: {@code arms} (default) follows the animated crossed-arms block,
     * so the gun rises with the aiming pose; {@code body} pins it to the torso instead, which is steadier
     * but ignores the arm animation.
     */
    public static final String DEFAULT_GUNNER_VILLAGER_GUN_ANCHOR = "arms";

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ================================================================= spawn gating
        b.comment("Where these mobs are allowed to spawn.",
                "",
                "A position counts as 'inside a city' when EITHER",
                "  (a) it lies inside a generated structure instance of one of cityStructureIds, or",
                "      inside a generated structure that carries one of cityStructureTags, or",
                "  (b) it lies inside one of the explicit boxes in cityRegions.",
                "",
                "Point (a) at your own city mod's structures by adding its structure ids here; point",
                "point (b) at an area you built by hand, either by editing cityRegions directly or",
                "in-game with /tarkovscav city add <name> [radius].",
                "",
                "The shipped city preset tarkovscav:city_small is already in cityStructureIds and in",
                "the tarkovscav:city tag, so /place structure tarkovscav:city_small opens an area up",
                "for spawning immediately.").push("spawn");
        SCAV_CITY_ONLY = b
                .comment("When true, scavs only spawn inside city areas. Turn off for a global invasion.")
                .define("scavCityOnly", true);
        GUNNER_PILLAGER_CITY_ONLY = b
                .comment("When true, gun-armed pillagers only spawn inside city areas.",
                        "This is the mob the city gate exists for - a pillager patrol walking out of a",
                        "city with a rifle would be a nasty surprise.")
                .define("gunnerPillagerCityOnly", true);
        GUNNER_VILLAGER_CITY_ONLY = b
                .comment("When true, gun-armed villagers only spawn inside city areas.",
                        "They are the residents who armed themselves, so they belong where the",
                        "residents are - the same city gate as the scavs and the gunner pillagers.")
                .define("gunnerVillagerCityOnly", true);
        GUNNER_VILLAGER_NATURAL_SPAWN = b
                .comment("When false, gunner villagers never appear from the biome spawner - only a",
                        "spawn egg, /summon or /tarkovscav spawn can create one.",
                        "The natural weight itself lives in",
                        "data/tarkovscav/forge/biome_modifier/add_scavs.json and is deliberately low",
                        "(4 against the scav's 12), because a friendly gun unit is meant to be a rare,",
                        "memorable encounter rather than the default city population.")
                .define("gunnerVillagerNaturalSpawn", true);
        GATE_COMMAND_SPAWNS = b
                .comment("When false (default), /summon and spawn eggs ignore the city gate, so you can",
                        "always place one by hand for testing. When true even those are refused outside",
                        "a city area.")
                .define("gateCommandSpawns", false);
        CITY_STRUCTURE_IDS = b
                .comment("Structure ids that make a position a city area, e.g. \"tarkovscav:city_small\",",
                        "one of the other three presets (tarkovscav:city_a, city_b, city_c - all four are",
                        "generated from tools/city-layout*.json, see README section 7),",
                        "the 18-building strongpoint tarkovscav:city_strongpoint (generated from",
                        "tools/strongpoint-layout.json, the one big city strongpoint),",
                        "\"minecraft:village_plains\" or \"somecitymod:downtown\".")
                .defineListAllowEmpty(List.of("cityStructureIds"), Config::defaultCityStructureIds,
                        element -> element instanceof String);
        CITY_STRUCTURE_TAGS = b
                .comment("Structure tags that make a position a city area, e.g. \"tarkovscav:city\".",
                        "A tag is the easiest hook for somebody else's city mod: ask them for their tag,",
                        "or write your own tag file in a datapack listing their structure ids.")
                .defineListAllowEmpty(List.of("cityStructureTags"), Config::defaultCityStructureTags,
                        element -> element instanceof String);
        CITY_REGIONS = b
                .comment("Explicit city boxes for areas that are NOT worldgen structures - a city you",
                        "built and saved by hand, for instance. Format:",
                        "  <dimension>|<x1> <y1> <z1>|<x2> <y2> <z2>",
                        "Example: \"minecraft:overworld|-120 40 -80|120 120 80\"",
                        "Add one in-game with: /tarkovscav city add <name> [radius]")
                .defineListAllowEmpty(List.of("cityRegions"), List::of, element -> element instanceof String);
        CITY_REGION_PADDING = b
                .comment("How many blocks around a city structure still count as city (the streets and",
                        "outskirts immediately outside the walls).",
                        "",
                        "THIS IS THE LINEAR LEVER ON THE CITY SPAWN RATE. With scavCityOnly (and the two",
                        "gunner city-only switches) on, only this ring can spawn the mod's mobs, so the",
                        "gateable area around one district is (width + 2*padding)^2 - a 24-block padding",
                        "around an 80-wide district is 16,384 cells, and the 4 it ships with now is 7,744:",
                        "about 53% less area per district. It was lowered from 24 to 4 together with the",
                        "biome-spawner weights in",
                        "data/tarkovscav/forge/biome_modifier/add_scavs.json (33 -> 15), because the two",
                        "do different jobs: a biome-spawner weight is only a SHARE of the monster",
                        "category, while this number moves the area directly. See README section 7i.",
                        "",
                        "NOTE FOR AN EXISTING INSTALL: Forge never rewrites an existing",
                        "config/tarkovscav-common.toml, so a world that already generated one keeps its",
                        "old 24 until you edit that file (or delete it) yourself. The new default only",
                        "applies to a fresh config.")
                .defineInRange("cityRegionPadding", 4, 0, 256);
        LOG_SPAWN_GATE = b
                .comment("Log every spawn-gate decision (accept and reject) to the server log.",
                        "Useful while wiring up a city mod; noisy in normal play.")
                .define("logSpawnGate", true);
        CITY_FOUNDATION_DEPTH = b
                .comment("How many blocks of footing the city-district assembler guarantees UNDER every",
                        "piece it places (/tarkovscav city district). Default 5.",
                        "",
                        "THE TWO KINDS OF PIECE ARE DIFFERENT, ON PURPOSE:",
                        "  * the hand-built pieces cut out of the user's save (README 7b, buildings/<name>.nbt)",
                        "    carry a FROZEN 5 blocks of footing, baked in when they were extracted - this key",
                        "    cannot change them;",
                        "  * the buildings the generator produces (tools/district-layout.json, run with",
                        "    CityStructureGen --pieces-only --foundation N) are baked with whatever N the",
                        "    generator was given.",
                        "This key is the assembler's side of the deal: before placing a piece it extends the",
                        "footing upwards to this depth, so a district dropped on rough terrain is buried",
                        "rather than floating. With the default 5 it is a no-op for the shipped pieces (they",
                        "already have 5); raise it to 7-9 on a mountain, lower it to 3 to leave less of a",
                        "skirt in flat land. The generator's --foundation and this key should be kept equal",
                        "for new pieces to look like the extracted ones.")
                .defineInRange("foundationDepth", 5, 0, 16);
        b.pop();

        // ================================================================= guns
        b.comment("Which TaCZ guns these mobs may be issued, and what they drop.",
                "",
                "The pool is built from TaCZ's own gun index, so custom gun packs are picked up",
                "automatically. Tiers are assigned by TaCZ's gun 'type' field:",
                "  pistol tier  <- pistol, smg",
                "  shotgun tier <- shotgun",
                "  rifle tier   <- rifle, mg",
                "  sniper tier  <- sniper",
                "",
                "Damage is NOT configured here and never has been: every round is a real TaCZ bullet",
                "from IGunOperator#shoot, so damage, armour penetration, headshots and range falloff",
                "all come from the gun's own data file. This mod only drives the operator.").push("guns");
        GUN_BLACKLIST = b
                .comment("Gun ids that must never be handed to a mob. Defaults exclude the explosives",
                        "and the minigun: an RPG-armed scav levels the city it is supposed to defend.")
                .defineListAllowEmpty(List.of("gunBlacklist"), Config::defaultGunBlacklist,
                        element -> element instanceof String);
        GUN_WHITELIST = b
                .comment("When non-empty, ONLY these gun ids may be issued (the blacklist still applies).",
                        "Leave empty to allow every gun TaCZ knows.")
                .defineListAllowEmpty(List.of("gunWhitelist"), List::of, element -> element instanceof String);
        EXCLUDED_GUN_TYPES = b
                .comment("TaCZ gun 'type' values that are never issued. Defaults to rpg: rocket launchers",
                        "are not a fair mob weapon.")
                .defineListAllowEmpty(List.of("excludedGunTypes"), () -> List.of("rpg"),
                        element -> element instanceof String);
        PISTOL_CLIP_TYPES = b
                .comment("TaCZ gun types that use the model's *_pistol animation clips instead of the",
                        "*_rifle ones. YSM - which this rig was authored for - special-cases exactly",
                        "'pistol' and 'rpg'; everything else (smg, sniper, shotgun, mg) uses the rifle",
                        "clips. Add 'smg' here if you prefer the one-handed poses for SMGs.")
                .defineListAllowEmpty(List.of("pistolClipTypes"), () -> List.of("pistol"),
                        element -> element instanceof String);
        EXCLUDE_SCRIPTED_GUNS = b
                .comment("Keep Lua-scripted guns away from mobs whose script namespace is not trusted",
                        "(see trustedScriptNamespaces). Default true.",
                        "",
                        "WHY THIS EXISTS: a gun pack's Lua runs inside TaCZ's own tick - a script that errors",
                        "throws out of ModernKineticGunItem.tickBolt and takes the whole server down with it",
                        "(the 2026-09-23 crash: 'hamster_win1894_gun_logic:32 attempt to perform arithmetic",
                        "__mul on nil and number' on a tarkovscav:usec_villager). We cannot catch an exception",
                        "thrown inside TaCZ's tick, so the only structural defence is to not hand such a gun",
                        "to a mob in the first place.",
                        "",
                        "The judgement comes from TaCZ's own index (GunData#getScript -> the declared script",
                        "id, plus CommonGunIndex#getScript -> the compiled LuaTable as a fallback), never from",
                        "a hard-coded id list.",
                        "",
                        "false = the old behaviour (every gun TaCZ knows may be issued). That can crash the",
                        "server again - see README 5p.")
                .define("excludeScriptedGuns", true);
        TRUSTED_SCRIPT_NAMESPACES = b
                .comment("Script namespaces that are still allowed to reach mobs while excludeScriptedGuns",
                        "is true. Default [\"tacz\"].",
                        "",
                        "MEASURED on the user's real instance (7 gun packs, 230 guns; see",
                        "tools/gunpack_script_scan.ps1): 105 guns declare a Lua script and the shipped default",
                        "keeps 79 of them out of the mob pool (~34 %). Counting only TaCZ's own default pack",
                        "would UNDERSTATE this badly - that pack is 20 of its 47 guns, all 'tacz:*'.",
                        "",
                        "The crashing gun (hamster:win1894_gun_logic) comes from GunpowderRevolution v1.3.5,",
                        "whose 48 guns declare 34 scripts (33 blocked). Script namespaces in that instance:",
                        "hamster 33, echoes_of_ruin 31, tacz 26, bf1 7, suffuse 6, gsl_server 1, m18 1.",
                        "",
                        "  []            = also block TaCZ's own 26 scripted guns (105 of 230 blocked in total);",
                        "  [\"tacz\",\"x\"] = also trust the namespace 'x' (use for a pack you have checked);",
                        "  anything      = matched case-insensitively against the script id's namespace.",
                        "",
                        "The default already keeps EVERY third-party script out; the list only decides which",
                        "third-party namespaces get a second chance.")
                .defineListAllowEmpty(List.of("trustedScriptNamespaces"), () -> List.of("tacz"),
                        element -> element instanceof String);
        SCRIPTED_GUN_RESCAN_TICKS = b
                .comment("How often (in ticks) a mob re-checks the gun in its hand against this rule, so a",
                        "gun that arrived from somewhere else - a weapon rack, /give, a saved world - is",
                        "swapped for a pool gun instead of being ticked with a broken script. The check is a",
                        "cached map lookup; the mobs are staggered by entity id so they do not all do it on",
                        "the same tick. 0 disables the runtime guard (spawn-time filtering still applies).")
                .defineInRange("scriptedGunRescanTicks", 40, 0, 2400);
        RESPECT_DECLARED_FIRE_MODES = b
                .comment("Let a gun's own data file decide the fire mode of the stack a mob is issued,",
                        "instead of forcing FULL-AUTO on every gun. Default false.",
                        "",
                        "WHY THE DEFAULT IS false: false is the behaviour every already-shipped jar has,",
                        "and it is what makes a mob with a bolt-action or a semi-auto actually feel like",
                        "one - the mob's trigger discipline comes from the fire mode stored in the stack.",
                        "Flipping the default would silently re-balance every existing world.",
                        "",
                        "WHY THE OPTION EXISTS: measured on the user's real instance",
                        "(tools/firemode_scan.ps1 - it reads the fire_mode array out of every gun data",
                        "file): 230 guns, 108 of which declare 'auto' and 122 of which do NOT. Those 122",
                        "(bolt-action rifles, semi-auto pistols, shotguns, ...) are today driven in a fire",
                        "mode their data file never asked for, because we write AUTO into the stack.",
                        "TaCZ's own Lua scripts branch on api:getFireMode() == AUTO -",
                        "win1894_gun_logic.lua:31/32 does exactly that - and that branch is only ever taken",
                        "because of us. hamster:win1894_data declares fire_mode [semi] and does NOT define",
                        "rapid_bolt_time, while smle_mk3_data ([semi]) and win1873_data ([semi, auto]) do,",
                        "which is why the forced-AUTO branch multiplies nil by a number and kills the",
                        "server. With this option on, win1894 is issued SEMI and that branch never runs",
                        "(the whole pool: SEMI 114, AUTO 108, BURST 8).",
                        "",
                        "true = ask TaCZ's index (GunData#getFireModeSet) and use: AUTO when the gun",
                        "declares it, otherwise the FIRST mode the gun declares, otherwise AUTO (a gun that",
                        "declares nothing keeps working exactly as before). One INFO line per gun reports",
                        "what was decided.",
                        "",
                        "This is a mitigation, not a cure: a script can still misbehave on a mob for other",
                        "reasons, which is why excludeScriptedGuns stays the structural defence.")
                .define("respectDeclaredFireModes", false);
        GUN_DROP_CHANCE = b
                .comment("Chance (0..1) that a dead mob drops the TaCZ gun it was carrying.")
                .defineInRange("gunDropChance", 0.35D, 0.0D, 1.0D);
        AMMO_DROP_CHANCE = b
                .comment("Chance (0..1) that a dead mob drops its spare ammunition.")
                .defineInRange("ammoDropChance", 0.5D, 0.0D, 1.0D);
        AMMO_ITEM_STACKS = b
                .comment("How many stacks of matching ammo a mob carries.")
                .defineInRange("ammoItemStacks", 3, 1, 27);
        MANUAL_RELOAD_FALLBACK = b
                .comment("Reload the magazine from the mob's ammo items ourselves when TaCZ's own",
                        "reload() does nothing.",
                        "",
                        "This is on by default because it has to be: TaCZ's LivingEntityReload#reload",
                        "silently returns for a mob - no state change, no log - so a mob whose magazine",
                        "is empty would otherwise stand there answering NO_AMMO for ever. The fallback",
                        "still uses TaCZ's own API and still consumes real ammunition: the rounds are",
                        "taken out of the mob's item handler by",
                        "AbstractGunItem#findAndExtractInventoryAmmo and put into the magazine with",
                        "IGun#setCurrentAmmoCount, so only the timing is ours. Turn it off to see the",
                        "raw TaCZ behaviour.")
                .define("manualReloadFallback", true);
        MANUAL_RELOAD_TICKS = b
                .comment("How long the fallback reload takes, in ticks. The mob stays behind cover and",
                        "plays the rig's reload pose for this long.")
                .defineInRange("manualReloadTicks", 45, 5, 400);
        RELOAD_STALL_TICKS = b
                .comment("How long TaCZ may keep reporting a reload 'in progress' before the mob stops",
                        "believing it and breaks contact, in ticks (20 = 1s).",
                        "",
                        "This is the bound on the one state that is otherwise driven entirely by TaCZ.",
                        "It has to be checked on the 'still reloading' path itself: the older",
                        "`stateTicks > 200` check below only runs once TaCZ has *stopped* reporting a",
                        "reload, so a reload state that never clears (TaCZ's LivingEntityReload bails",
                        "out silently in five documented cases) used to park the mob behind cover for",
                        "ever. 200 ticks is comfortably longer than any real reload.")
                .defineInRange("reloadStallTicks", 200, 20, 2400);
        b.pop();

        // ================================================================= combat
        b.comment("Shared combat behaviour. Per-tier numbers live in the tier sections below.").push("combat");
        REACTION_TICKS = b
                .comment("Ticks between spotting a player and starting to raise the gun (20 ticks = 1s).",
                        "This is the fairness window: it stops a scav from deleting you the instant you",
                        "round a corner.")
                .defineInRange("reactionTicks", 12, 0, 200);
        GIVE_UP_TICKS = b
                .comment("How long a mob keeps trying before it gives up on an unreachable target.")
                .defineInRange("giveUpTicks", 160, 20, 2400);
        RETREAT_HEALTH_FRACTION = b
                .comment("Below this fraction of max health a mob falls back instead of holding ground.",
                        "Being *hit* also has a chance to trigger a retreat - see hurtRetreatChance.")
                .defineInRange("retreatHealthFraction", 0.35D, 0.0D, 1.0D);
        REPOSITION_TICKS = b
                .comment("How long a mob spends moving to a new firing position before it re-engages.")
                .defineInRange("repositionTicks", 40, 10, 400);
        TARGET_MEMORY_TICKS = b
                .comment("How long a mob remembers where its target was after losing sight of it.",
                        "While the memory lasts it keeps suppressing that spot; when it expires it",
                        "advances or gives up.")
                .defineInRange("targetMemoryTicks", 100, 0, 1200);
        FIRE_STALL_TICKS = b
                .comment("How long FIRE may go without a single accepted shot before the mob gives up on",
                        "the burst and moves, in ticks (20 = 1s).",
                        "",
                        "TaCZ can refuse every shot of a burst - COOL_DOWN never coming down, or",
                        "NETWORK_FAIL / UNKNOWN_FAIL / FORGE_EVENT_CANCEL coming back every time - and",
                        "FIRE holds the mob still (navigation stopped, no sprint) while it waits for the",
                        "burst to finish. The burst only ends on SUCCESS, so without this bound the mob",
                        "aims at its target for ever without firing: the 'stands there waiting to be",
                        "shot' bug. When the window expires the mob repositions, and after",
                        "`giveUpTicks / 2` failed windows it breaks contact. 100 ticks is several times",
                        "the slowest gun's shot interval, so a healthy mob never reaches it.")
                .defineInRange("fireStallTicks", 100, 20, 2400);
        LOG_GUN_AI = b
                .comment("Log every gun-AI state change, every TaCZ ShootResult and every cover decision",
                        "to the server log. This is the switch that produces the evidence trail for gun",
                        "integration and for the cover system.")
                .define("logGunAi", true);
        b.pop();

        // ================================================================= ai (doors, and the rest later)
        b.comment("Behaviour shared by every armed unit that is not about the gun or the fight itself.",
                "The door rules (README 5a) are the first entry; later AI work extends this same section,",
                "so a server operator has one place for 'how do these units move through the world'.").push("ai");
        CLOSE_DOORS_BEHIND = b
                .comment("Close a wooden door again, once, after the unit that opened it has walked clear",
                        "of it. Opening is unaffected by this key: the unit still opens the wooden door in",
                        "its path, this only decides whether the door swings shut behind it.",
                        "",
                        "WHY THIS IS OURS: vanilla never closes a door after a mob passes through it. The",
                        "only goal-based vanilla door opener, AbstractIllager.RaiderOpenDoorGoal, is built",
                        "with closeDoor=false (the 1.20.1 bytecode pushes iconst_0), so it opens the door and",
                        "then stops; the one vanilla thing that does close doors - the villager brain",
                        "behaviour InteractWithDoor - is parked on our gunner villagers for as long as they",
                        "have a target (GunnerVillagerEntity#customServerAiStep). Five of the nine armed",
                        "types (the Monster scav and the whole pillager family) have no door goal at all,",
                        "so they could not even open one: see README 5a. false restores the old behaviour:",
                        "every door a unit walks through stays open.")
                .define("closeDoorsBehind", true);
        DOOR_CLOSE_DELAY_TICKS = b
                .comment("How long after opening a door the unit may still have it open, in ticks (20 = 1s),",
                        "before the door is shut once nothing is in the way. Default 40 = 2 seconds.",
                        "",
                        "WHY 40: this is the backstop for the unit that opens a door and then stops within",
                        "doorCloseAllyRadius of it - normally the door closes much earlier, the moment the",
                        "unit is more than that radius away (that is the 'or' in the rule). 20 ticks is",
                        "vanilla's own door re-run cooldown (InteractWithDoor.COOLDOWN_BEFORE_RERUNNING_IN_",
                        "SAME_NODE = 20, read from the 1.20.1 constant pool); 40 is twice that, so a unit",
                        "that opened a door and immediately turned around still has a full second to come",
                        "back through it before we swing it shut. 0 = close as soon as the doorway is free.")
                .defineInRange("doorCloseDelayTicks", 40, 0, 1200);
        DOOR_CLOSE_ALLY_RADIUS = b
                .comment("The safety radius, in blocks: a door is not closed while ANY living entity other",
                        "than the closing unit is within this distance of either door half, and never while",
                        "anybody at all (the unit included) is standing inside a door block.",
                        "",
                        "WHY 2.0: vanilla uses exactly this number for the same decision - the villager",
                        "brain behaviour InteractWithDoor holds a door open for other mobs within",
                        "MAX_DISTANCE_TO_HOLD_DOOR_OPEN_FOR_OTHER_MOBS = 2.0d (read from the 1.20.1 constant",
                        "pool), tested with BlockPos#closerToCenterThan, which is the test this mod copies.",
                        "It is also larger than vanilla's own door interaction range, 1.5 blocks",
                        "(DoorInteractGoal#canUse tests distanceToSqr <= 2.25), so whoever is close enough",
                        "to have made the unit open the door is inside the protected zone. Larger = fewer",
                        "doors shut while a squad walks through; smaller = doors shut sooner.")
                .defineInRange("doorCloseAllyRadius", 2.0D, 0.5D, 16.0D);

        // ---- the per-tier intelligence layer (README 5aa) ----
        AI_ENABLED = b
                .comment("Master switch for the per-tier intelligence profiles (README 5aa).",
                        "",
                        "false = every armed unit keeps exactly the global behaviour it had before this",
                        "layer existed: [combat] reactionTicks and the raw [tactics] numbers. Nothing",
                        "below is read at all, which makes 'the new AI is not for me' a single key.",
                        "",
                        "true (default) = the four tiers below SCALE (or, where there is no global key,",
                        "OVERRIDE) those same global keys, so a user who already tuned [tactics] does",
                        "not lose their numbers - their value is the baseline the profile multiplies.",
                        "The exact scaling rule per key is in README 5aa; the resolver that applies it",
                        "is gun/AiProfile.java.",
                        "",
                        "REVERT TO DUMB without a rebuild: copy every number of [ai.scav] into",
                        "[ai.sniper], [ai.troop] and [ai.elite], and set those three blocks'",
                        "holdPost=false, minHitChance=0.0, patienceTicks=0, coordination=false. Every",
                        "tier then behaves like a scav. For the exact pre-feature feel as well, also",
                        "set reactionMinTicks = reactionMaxTicks = [combat] reactionTicks, because the",
                        "0.6-1.2 s random reaction is new.",
                        "",
                        "The README 5ab keys need no extra step: copying [ai.scav] carries their",
                        "sentinels too (exposedBurstShots=0, exposedBurstCooldownTicks=-1,",
                        "warmupShotsWhenExposed=-1, retreatHealthFraction=-1, hurtRetreatChance=-1,",
                        "retreatHoldTicks=0), and every sentinel means 'use the global/gun-tier",
                        "rule', i.e. the pre-5ab behaviour. tools/selftest_ai_fire.js executes that",
                        "recipe over the six new keys and asserts the sentinels survive.")
                .define("enabled", true);

        for (AiProfile.Tier tier : AiProfile.Tier.values()) {
            AI_TIERS.put(tier, new AiSettings(b, tier));
        }

        b.comment("Squad coordination (README 5aa): focus fire, bounding overwatch, flanking and",
                "cover exclusivity, for the TROOP and ELITE tiers only (a profile's own",
                "'coordination' switch decides which). Every sub-switch below defaults to true;",
                "set all four to false to keep the tier profiles but drop the coordination layer.",
                "",
                "Squad = same faction + within squadRadius + alert-shared (one of them has a live",
                "target or a current contact report). The member list is CACHED for squadCacheTicks,",
                "so this is one entity query per mob per window, never a per-tick world scan.").push("coordination");
        AI_COORD_ENABLED = b
                .comment("Master switch for the coordination layer. false = no squad lookup, no role,",
                        "no claims: every mob fights on its own exactly as the tier profile says.")
                .define("enabled", true);
        AI_COORD_FOCUS_FIRE = b
                .comment("Focus fire: the squad designates ONE target (the one most members are",
                        "already fighting, ties by lowest entity id) and a member that has just",
                        "acquired a target adopts it. The adopted target must still be visible to",
                        "the adopting mob - squad intel alone never starts a fight (README 5m).")
                .define("focusFire", true);
        AI_COORD_OVERWATCH = b
                .comment("Bounding overwatch: one squad member at a time is the suppressor (it pins",
                        "the target down when line of sight is lost) while the rest move; the role",
                        "rotates every overwatchWindowTicks. A member that stays the suppressor",
                        "longer than overwatchTimeoutTicks falls back to moving, so a role can never",
                        "park a mob (README 5aa, 'no deadlock').")
                .define("overwatch", true);
        AI_COORD_FLANKING = b
                .comment("Flanking: part of the squad (flankFraction of it, always at least one and",
                        "never all of it) only takes cover on ONE side of the mob-to-target axis,",
                        "so the squad approaches from two directions instead of single file.")
                .define("flanking", true);
        AI_COORD_COVER_CLAIMS = b
                .comment("Cover exclusivity: a cover spot is claimed, by block position, for",
                        "coverClaimTicks, and another mob will not pick a spot somebody else has",
                        "claimed - so two mobs do not stack behind the same block.")
                .define("coverClaims", true);
        AI_COORD_SQUAD_RADIUS = b
                .comment("How far (in blocks) a mob looks for its squad. 24 is a bit under the",
                        "sniper's sight range and about the width of a street block in the shipped",
                        "city, so a fight on one street is one squad.")
                .defineInRange("squadRadius", 24.0D, 4.0D, 64.0D);
        AI_COORD_SQUAD_CACHE_TICKS = b
                .comment("How long the squad member list is reused before it is looked up again.",
                        "This is the whole cost control: one entity query per mob per window, and",
                        "only for the coordinated tiers.")
                .defineInRange("squadCacheTicks", 40, 5, 400);
        AI_COORD_OVERWATCH_WINDOW_TICKS = b
                .comment("How long one overwatch role lasts before it rotates to the next member.",
                        "60 ticks = 3 s: long enough to cross a street, short enough that a whole",
                        "squad keeps cycling.")
                .defineInRange("overwatchWindowTicks", 60, 10, 600);
        AI_COORD_OVERWATCH_TIMEOUT_TICKS = b
                .comment("The anti-deadlock bound: a member that has held the suppressor role for",
                        "longer than this ignores the role and moves. Must be >= overwatchWindowTicks",
                        "for the rotation to normally win; 120 = two windows.")
                .defineInRange("overwatchTimeoutTicks", 120, 20, 1200);
        AI_COORD_FLANK_FRACTION = b
                .comment("Fraction of the squad that flanks. Rounded, clamped to [1, size-1] for a",
                        "squad of 2 or more, so there is always somebody holding the front and",
                        "never a squad that all goes the same way round.")
                .defineInRange("flankFraction", 0.5D, 0.0D, 1.0D);
        AI_COORD_FLANK_OFFSET = b
                .comment("How far (in blocks) to the side of the mob-to-target axis a flanker's",
                        "cover is preferred to be. Used as the side test's stand-off, so a flanker",
                        "does not simply walk the same street as the base of fire.")
                .defineInRange("flankOffset", 6.0D, 1.0D, 24.0D);
        AI_COORD_COVER_CLAIM_TICKS = b
                .comment("How long a claimed cover spot stays reserved after the claim, in ticks.",
                        "Short enough that a dead or disengaged owner frees the spot quickly, long",
                        "enough to cover a reload behind it (manualReloadTicks is 45).")
                .defineInRange("coverClaimTicks", 100, 10, 1200);
        b.pop();

        b.pop();

        // ================================================================= tactics
        b.comment("The Scav tactics layer: cover, suppression, advancing and breaking contact.",
                "",
                "Cover is found by ray casting from the threat's eyes to candidate positions around the",
                "mob (level.clip), scoring each by how well it is hidden, how close it is to the mob,",
                "and - when advancing - how much closer to the target it is. Results are cached for",
                "coverCacheTicks, so this does not run every tick.").push("tactics");
        COVER_SEARCH_RADIUS = b
                .comment("How far (in blocks) a mob looks for cover around itself.")
                .defineInRange("coverSearchRadius", 14, 3, 48);
        COVER_CACHE_TICKS = b
                .comment("How long a cover search result is reused before it is recomputed.")
                .defineInRange("coverCacheTicks", 20, 1, 400);
        COVER_SAMPLES = b
                .comment("How many candidate positions are tested per cover search. Higher finds better",
                        "cover at a linear CPU cost; each candidate is two ray casts.")
                .defineInRange("coverSamples", 28, 6, 128);
        SUPPRESS_CHANCE = b
                .comment("0..1. Chance that a mob whose target just broke line of sight lays down",
                        "suppressing fire on the last known position instead of moving.")
                .defineInRange("suppressChance", 0.6D, 0.0D, 1.0D);
        SUPPRESS_TICKS = b
                .comment("How long a mob keeps suppressing before it re-evaluates.")
                .defineInRange("suppressTicks", 60, 10, 600);
        SUPPRESS_ACCURACY_MULTIPLIER = b
                .comment("Accuracy multiplier while suppressing (blind fire at a remembered position).")
                .defineInRange("suppressAccuracyMultiplier", 0.45D, 0.05D, 1.0D);
        SUPPRESS_BURST_MULTIPLIER = b
                .comment("Burst length is multiplied by this while suppressing: suppression is meant to",
                        "be long and inaccurate, not precise.")
                .defineInRange("suppressBurstMultiplier", 2.0D, 1.0D, 6.0D);
        ADVANCE_COVER_STEP = b
                .comment("When advancing, a cover position must be at least this many blocks closer to",
                        "the target than the mob's current position to be worth moving to.")
                .defineInRange("advanceCoverStep", 3.0D, 1.0D, 16.0D);
        COVER_SEEK_SPEED_MODIFIER = b
                .comment("Speed multiplier used only by the three 'get behind cover' moves - reloading",
                        "behind cover, repositioning to a new firing position, and the retreat dash -",
                        "relative to the mob's normal walking speed (the vanilla speed modifier 1.0 is the",
                        "mob's movementSpeed attribute at full walk).",
                        "",
                        "Default 1.0 = cover seeking is a NORMAL WALK. It shipped at 1.5 and then was tuned",
                        "twice by the user: first '1.5x normal' when the old values (1.2/1.2/1.25 plus a",
                        "retreat sprint) looked like three times walking speed, then - after seeing 1.5 in",
                        "game - 'make their running-away speed normal 1x'. Advancing (1.15) is deliberately",
                        "NOT affected: it is about closing a gap, not about finding cover. See README 5i.")
                .defineInRange("coverSeekSpeedModifier", 1.0D, 0.5D, 3.0D);
        RETREAT_SPRINT = b
                .comment("Whether a retreating mob also sprints. This was the hidden multiplier behind",
                        "'they run away absurdly fast': sprinting adds its own speed on top of the",
                        "navigation modifier, so 1.25 (or now 1.0) times the attribute was never what the",
                        "mob actually moved at.",
                        "",
                        "Default false, i.e. 'running away = normal 1x' as the user asked. Set true to get",
                        "the old frantic flee back.")
                .define("retreatSprint", false);
        ESCAPE_WITHOUT_COVER_SPEED_MODIFIER = b
                .comment("Speed for the retreat fallback where NO cover was found and the mob simply runs",
                        "away from the threat. It used to be a hard-coded 1.25 WITH sprinting on top, which",
                        "is why fleeing looked so fast; it is now its own key, default 1.0 (normal walk).",
                        "The cover-seeking retreat (when a spot was found) uses coverSeekSpeedModifier.")
                .defineInRange("escapeWithoutCoverSpeedModifier", 1.0D, 0.5D, 3.0D);
        MOVE_PROGRESS_SAMPLE_TICKS = b
                .comment("The no-progress watchdog: how often (in ticks) the position of a mob that is in a",
                        "movement state (ADVANCE / REPOSITION / RETREAT) is compared with the previous",
                        "sample. This is the check that catches 'legs stepping but the position never",
                        "changes' - at a closed door, against a wall, or on a path the navigation keeps",
                        "failing to build.")
                .defineInRange("moveProgressSampleTicks", 20, 5, 200);
        MOVE_PROGRESS_MIN_BLOCKS = b
                .comment("How many blocks a mob has to cover per sample window to count as making progress.",
                        "Below this it re-paths and tries to hop the obstacle; after moveProgressRetries",
                        "wasted windows it logs a WARN and changes state instead of standing there.")
                .defineInRange("moveProgressMinBlocks", 0.5D, 0.05D, 8.0D);
        MOVE_PROGRESS_RETRIES = b
                .comment("How many re-path attempts a stuck mob gets before it gives up on the position",
                        "(then it repositions, or breaks contact if it was already retreating).")
                .defineInRange("moveProgressRetries", 3, 1, 20);
        UNDER_FIRE_TICKS = b
                .comment("After being hit, a mob counts as 'under fire' for this many ticks. While it",
                        "does, it prefers cover that is further from the threat.")
                .defineInRange("underFireTicks", 100, 0, 1200);
        HURT_RETREAT_CHANCE = b
                .comment("Chance that taking a hit immediately triggers a retreat (on top of the health",
                        "threshold). This is what makes scavs look like they value their lives.")
                .defineInRange("hurtRetreatChance", 0.5D, 0.0D, 1.0D);
        DISTANCE_ACCURACY_NEAR = b
                .comment("Accuracy multipliers by distance band (near = inside 35% of the tier's range,",
                        "far = beyond 70%). Snapshotted aim error plus these are the whole difficulty knob.")
                .defineInRange("accuracyNear", 1.0D, 0.05D, 2.0D);
        DISTANCE_ACCURACY_MID = b.defineInRange("accuracyMid", 0.85D, 0.05D, 2.0D);
        DISTANCE_ACCURACY_FAR = b.defineInRange("accuracyFar", 0.7D, 0.05D, 2.0D);
        b.pop();

        // ================================================================= tiers
        b.comment("Per-tier tuning. spawnWeight 0 removes a tier from the world entirely.",
                "accuracy is 0..1: the share of the aim error that is removed, so 1.0 is a",
                "laser and 0.3 is a drunk scav. Error also grows with distance and while moving.").push("tiers");
        for (ScavTier tier : ScavTier.values()) {
            TIERS.put(tier, new TierSettings(b, tier));
        }
        b.pop();

        // ================================================================= voice
        b.comment("The voice lines (README 5l). Fifteen mono clips supplied by the user; every trigger",
                "below has its own switch, and the pools live in ModSounds.",
                "",
                "Everything is played at the mob's own position, so it is spatialised - you can tell which",
                "building a line came from. The clips are mono on purpose: a stereo clip is not",
                "spatialised at all and would be audible everywhere at once.",
                "",
                "The idle timer is OURS, not vanilla's ambient interval (about 6s): the request was one",
                "line every ~20s, so getAmbientSoundInterval is not used for voice.").push("voice");
        VOICE_ENABLED = b.comment("Master switch for all voice lines.")
                .define("enabled", true);
        VOICE_IDLE = b.comment("Mutter to itself while it has no target (the 'self-talk' pool).")
                .define("idle", true);
        VOICE_IDLE_INTERVAL_TICKS = b
                .comment("Ticks between idle lines, 20 ticks = 1s. Default 400 = 20 seconds, as requested.",
                        "This replaces vanilla's ~6s ambient cadence for voice.")
                .defineInRange("idleIntervalTicks", 400, 40, 24000);
        VOICE_IDLE_JITTER_TICKS = b
                .comment("Random extra ticks added to each idle interval, so a group of mobs does not",
                        "speak in unison.")
                .defineInRange("idleJitterTicks", 80, 0, 2400);
        VOICE_CONTACT = b
                .comment("Say one line from the chatter pool when it first engages a target.")
                .define("contact", true);
        VOICE_CONTACT_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two contact lines for the same mob (anti-spam), and the",
                        "reuse cooldown for the 'lost them' line.")
                .defineInRange("contactCooldownTicks", 200, 0, 12000);
        VOICE_CHATTER = b
                .comment("Keep talking during the firefight (FIRE/SUPPRESS), on a random interval.")
                .define("chatter", true);
        VOICE_CHATTER_MIN_TICKS = b
                .comment("Lower bound of that random interval, in ticks. Default 300 = 15s.")
                .defineInRange("chatterMinIntervalTicks", 300, 20, 24000);
        VOICE_CHATTER_MAX_TICKS = b
                .comment("Upper bound, in ticks. Default 600 = 30s.")
                .defineInRange("chatterMaxIntervalTicks", 600, 40, 24000);
        VOICE_GRENADE = b
                .comment("Shout when something explosive lands nearby (vanilla primed TNT, plus any entity",
                        "whose registry name contains 'grenade', which covers other mods' grenades).")
                .define("grenade", true);
        VOICE_GRENADE_RADIUS = b
                .comment("How close (blocks) that explosive has to be.")
                .defineInRange("grenadeRadius", 12.0D, 2.0D, 48.0D);
        VOICE_GRENADE_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two grenade shouts.")
                .defineInRange("grenadeCooldownTicks", 200, 0, 12000);
        VOICE_GRENADE_SHOUT_COOLDOWN_TICKS = b
                .comment("Cooldown in ticks for the SHOUT a mob makes when it throws a grenade, separate from",
                        "grenadeCooldownTicks above.",
                        "",
                        "WHY TWO KEYS: grenadeCooldownTicks (200) is the REACTION cooldown - how often a mob may",
                        "react to a grenade landing near it. The shout used to share that same 200-tick timer,",
                        "so in a firefight a reaction could swallow the shout (and the other way round): the",
                        "user heard 'grenade shouts got rarer' while the log showed the pool being used.",
                        "40 ticks (2 s) is enough to stop a burst of identical shouts from one mob while",
                        "letting every throw be heard. 0 disables the shout cooldown entirely.")
                .defineInRange("grenadeShoutCooldownTicks", 40, 0, 12000);
        VOICE_MARK = b
                .comment("Say the 'where did you run off to' line when it loses its target.")
                .define("mark", true);
        VOICE_DEATH = b
                .comment("Replace the vanilla death sound with one of the four death clips. When true the",
                        "mob's own getDeathSound() is silenced and the clip plays instead, so the two do",
                        "not overlap; set false to get the vanilla sound back.")
                .define("death", true);
        VOICE_VOLUME = b
                .comment("Volume of every voice line (1.0 = vanilla mob volume).")
                .defineInRange("volume", 1.0D, 0.0D, 4.0D);
        VOICE_FAMILY_VOLUME = b
                .comment("Per-family volume MULTIPLIER on top of voice.volume, as 'family=value' entries:",
                        "  familyVolume = [\"shared=1.0\", \"usec=1.0\", \"bear=1.0\", \"elite=1.0\"]",
                        "",
                        "shared is what every mob that has no family of its own speaks (the original",
                        "scavs/gunners), and the other three are the faction families (README 5y/5l).",
                        "This is the escape hatch for the 'the usec/bear/elite voices are quieter than",
                        "the scav's' class of report: the clips themselves are now rendered to the same",
                        "measured loudness as the original 27 (see voice_levels.json), so 1.0 is the",
                        "calibrated value - nudge it here instead of re-cutting audio.",
                        "",
                        "An unknown family name or an unparseable number is reported once and ignored.")
                .defineListAllowEmpty(List.of("familyVolume"),
                        () -> List.of("shared=1.0", "usec=1.0", "bear=1.0", "elite=1.0"),
                        element -> element instanceof String);
        VOICE_EFFECT_VOLUME = b
                .comment("Volume multiplier for the non-voice effect clips (the grenade impact/bounce, see",
                        "README 5v). It is separate from the families because the impact clip is a",
                        "transient: its measured MEAN is low by nature (most of it is the tail), so the",
                        "only useful knob is the level, and it should not drag the voices with it.")
                .defineInRange("effectVolume", 1.0D, 0.0D, 4.0D);
        VOICE_PITCH_MIN = b
                .comment("Lower end of the voice pitch band. Every mob draws its OWN pitch once, stores it",
                        "in its saved data, and keeps it for life - the same mob always sounds the same,",
                        "and two mobs of the same type sound like two people. Vanilla does the same thing",
                        "with getVoicePitch() and a 0.8-1.2 band; this default is narrower on purpose,",
                        "because these are real speech clips from one speaker and a wide shift makes the",
                        "same person sound like a chipmunk.")
                .defineInRange("pitchMin", DEFAULT_VOICE_PITCH_MIN, VOICE_PITCH_FLOOR, VOICE_PITCH_CEILING);
        VOICE_PITCH_MAX = b
                .comment("Upper end of the voice pitch band. If pitchMin > pitchMax the pair is rejected",
                        "and both fall back to the shipped 0.9/1.1 rather than playing a broken voice.")
                .defineInRange("pitchMax", DEFAULT_VOICE_PITCH_MAX, VOICE_PITCH_FLOOR, VOICE_PITCH_CEILING);
        VOICE_PITCH_JITTER = b
                .comment("Per-line wobble, in pitch units, added on top of a mob's own pitch (0.03 = about",
                        "+/-3 %). The result is always clamped back into [pitchMin, pitchMax], so no single",
                        "line can leave the band. 0 makes every line of one mob identical.")
                .defineInRange("pitchJitter", DEFAULT_VOICE_PITCH_JITTER, 0.0D, 0.5D);
        b.pop();

        // ================================================================= faction
        b.comment("Who is on whose side, friendly fire, and the renegade brand (README 5m). Membership",
                "itself is a data-pack tag (data/tarkovscav/tags/entity_types/faction_*.json), not a config",
                "value, so a modpack can move a mob between sides without a rebuild.").push("faction");
        FACTION_ENABLED = b
                .comment("Master switch for the faction layer: allies, the friendly-fire accounting, the",
                        "renegade brand and the contact network. false restores the plain behaviour of the",
                        "three mobs: everybody is hostile to each other, nothing is shared.")
                .define("enabled", true);
        VILLAGER_ATTACK_MONSTERS = b
                .comment("Whether the VILLAGER family (gunner_villager, sniper_villager, usec_villager,",
                        "elite_villager) defends the village against hostile monsters - zombies, skeletons,",
                        "spiders, creepers and the vanilla raiders - on sight.",
                        "",
                        "WHO exactly is on that list is a data-pack tag, not this switch:",
                        "  data/tarkovscav/tags/entity_types/faction_village_hostile.json",
                        "Default: #minecraft:raiders, #minecraft:undead, the four arthropods listed one by",
                        "one (spider, cave_spider, silverfish, endermite - NOT #minecraft:arthropods, which",
                        "would include bees), creeper, slime, magma_cube, blaze, ghast, guardian,",
                        "elder_guardian, shulker, vex, hoglin, zoglin. Add or remove ids there to tune it",
                        "(a pack that dislikes creeper-hunting just deletes that line).",
                        "",
                        "false = the pre-2026 target list only: illagers, whoever hurt it, and a branded",
                        "renegade - so the villagers go back to ignoring the undead.",
                        "",
                        "The village never shoots its own side either way (Faction.allies is checked inside",
                        "the goal), and the scavs/pillagers are deliberately NOT changed by this key.").define(
                        "villagersAttackMonsters", true);
        FACTION_FRIENDLY_FIRE_HITS_TO_ANGER = b
                .comment("Friendly hits from the SAME attacker, inside the window, before the victim turns",
                        "on that attacker by UUID (nobody else, and only that pair).")
                .defineInRange("friendlyFireHitsToAnger", 3, 1, 100);
        FACTION_FRIENDLY_FIRE_WINDOW_TICKS = b
                .comment("Sliding window for those hits: a hit that lands more than this many ticks after",
                        "the first one restarts the count. 200 ticks = 10 s.")
                .defineInRange("friendlyFireWindowTicks", 200, 1, 72000);
        FACTION_BETRAYAL_THRESHOLD = b
                .comment("Friendly hits from the same attacker, inside the window, after which the",
                        "ATTACKER is branded a renegade. Should be >= friendlyFireHitsToAnger, otherwise",
                        "the victim never gets its one warning shot before the brand lands.")
                .defineInRange("betrayalThreshold", 3, 1, 100);
        FACTION_RENEGADE_BROADCAST_RADIUS = b
                .comment("How far the 'X is a renegade' news travels when the brand lands, in blocks.")
                .defineInRange("renegadeBroadcastRadius", 48.0D, 4.0D, 256.0D);
        FACTION_RENEGADE_DECAY_TICKS = b
                .comment("0 = a traitor stays a traitor (the default, and the more interesting rule). A",
                        "positive value forgives the brand after that many ticks.")
                .defineInRange("renegadeDecayTicks", 0, 0, 240000);
        FACTION_RENEGADE_GLOW = b
                .comment("Give a renegade the glowing outline, so the player can see the traitor too.")
                .define("renegadeGlow", true);
        b.pop();

        // ================================================================= alert network
        b.comment("Faction intel sharing: a contact report is a DIRECTION and a DISTANCE BAND, never a",
                "position, and it is never written to the mob's target - only line of sight can start a",
                "fight (README 5m).").push("alert");
        ALERT_ENABLED = b
                .comment("Master switch for intel sharing. Mobs still fight normally without it; they just",
                        "never tell each other anything.")
                .define("enabled", true);
        ALERT_RADIUS = b
                .comment("How far a contact report carries, in blocks: allies inside this radius of the",
                        "reporter are candidates. It is also the 'far' band edge.")
                .defineInRange("radius", 40.0D, 4.0D, 128.0D);
        ALERT_NEAR_DISTANCE = b
                .comment("Upper edge of the NEAR band, in blocks. The receiver is told the band, not the",
                        "real distance, so the point it walks to can be tens of blocks off on purpose.")
                .defineInRange("nearDistance", 8.0D, 1.0D, 128.0D);
        ALERT_MID_DISTANCE = b
                .comment("Upper edge of the MID band, in blocks. Should be >= nearDistance.")
                .defineInRange("midDistance", 24.0D, 1.0D, 256.0D);
        ALERT_MEMORY_TICKS = b
                .comment("How long a report stays news, in ticks (300 = 15 s). When it expires the receiver",
                        "has no contact again - that is what takes it out of the held-alert stance.")
                .defineInRange("memoryTicks", 300, 1, 24000);
        ALERT_MAX_RECIPIENTS = b
                .comment("At most this many armed allies are told per broadcast, chosen by entity id so the",
                        "choice is stable rather than random (one shout does not wake the whole city).")
                .defineInRange("maxRecipients", 6, 1, 64);
        ALERT_BROADCAST_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two broadcasts from the same mob.")
                .defineInRange("broadcastCooldownTicks", 40, 1, 24000);
        ALERT_BEARING_NOISE_DEGREES = b
                .comment("Extra error, in degrees, added on top of the 45-degree sector quantisation, so the",
                        "receiver cannot pin the contact to a sector boundary. 22 keeps the total error",
                        "inside half a sector.")
                .defineInRange("bearingNoiseDegrees", 22, 0, 45);
        ALERT_CONVERGE_RADIUS = b
                .comment("A receiver walks to the reported area only when that area is within this many",
                        "blocks; further away it just turns its head and holds.")
                .defineInRange("convergeRadius", 20.0D, 1.0D, 128.0D);
        ALERT_CONVERGE_MAX_ALLIES = b
                .comment("At most this many allies converge on one report. The rest hold, so a report cannot",
                        "pull an entire city across the map.")
                .defineInRange("convergeMaxAllies", 3, 1, 32);
        ALERT_SURROUND_STANDOFF = b
                .comment("Converging allies stand on a ring of this radius around the contact point, spread",
                        "evenly (2*pi*i/n), instead of piling onto one block.")
                .defineInRange("surroundStandoff", 6.0D, 1.0D, 32.0D);
        ALERT_MIN_REPATH_INTERVAL_TICKS = b
                .comment("A converging mob re-paths at most this often, so a report cannot turn into a",
                        "pathfinding storm while it is already walking.")
                .defineInRange("minRepathIntervalTicks", 60, 1, 24000);
        b.pop();

        // ================================================================= weapon rack
        b.comment("The weapon rack (README 5n): one slot, and the unarmed villagers/pillagers who take",
                "what is on it and turn into fighters. The weapon decides how they fight.").push("rack");
        RACK_ENABLED = b
                .comment("Master switch for the whole block: false stops mobs from taking anything AND stops",
                        "the drop-absorb scan (see rack.absorbDroppedItems), i.e. the rack becomes a plain",
                        "one-slot shelf. The block itself always stores and returns items by hand.")
                .define("enabled", true);
        RACK_ACCEPTS_ANY_ITEM = b
                .comment("false (the default) accepts only a TaCZ gun (IGun), a bow, a crossbow, or an item",
                        "in #minecraft:swords / #minecraft:axes. true lets anything on the rack - an item",
                        "with no combat path is then left on the rack and WARNed once by item id, never",
                        "consumed for nothing.")
                .define("acceptsAnyItem", false);
        RACK_TAKE_RADIUS = b
                .comment("How close an unarmed villager/pillager has to be for the rack to arm it, in",
                        "blocks (vertical distance included).")
                .defineInRange("takeRadius", 8.0D, 1.0D, 32.0D);
        RACK_TAKE_COOLDOWN_TICKS = b
                .comment("After a mob takes something, the rack waits this long before arming the next one,",
                        "so one rack cannot convert a whole village in a second.")
                .defineInRange("takeCooldownTicks", 200, 1, 24000);
        RACK_TAKE_CHECK_INTERVAL_TICKS = b
                .comment("How often the rack looks for a taker, in ticks. The lookup is one entity query per",
                        "rack per interval, not per tick.")
                .defineInRange("takeCheckIntervalTicks", 20, 1, 1200);
        RACK_PRIORITY = b
                .comment("Which unarmed mob is served first when several are in range: nearest (default),",
                        "villager, or pillager. A preference, not a filter - with nobody of the preferred",
                        "type around, the other type still gets served.")
                .define("priority", "nearest");
        RACK_ABSORB_DROPPED_ITEMS = b
                .comment("Whether a rack should PICK UP an item dropped on top of it, so a weapon can be handed",
                        "over by throwing it (the player cannot interact with a rack while holding a gun,",
                        "because right-click with any item in hand is the place/refuse branch).",
                        "",
                        "Only an EMPTY rack absorbs, only an accepted item is absorbed (a piece of dirt is",
                        "left on the ground instead of taking up the slot), and exactly ONE item is taken: a",
                        "stack of 5 becomes 1 on the rack and 4 still lying there. The rack's single slot",
                        "means the item is either on the rack or on the ground, never both - and never two.",
                        "Every absorption is logged at INFO with the item name and where it came from.")
                .define("absorbDroppedItems", true);
        RACK_ABSORB_RADIUS = b
                .comment("Horizontal radius, in blocks, around the rack's centre that the absorb scan covers.",
                        "0.75 reaches the block itself and a little of its neighbours, so an item has to be",
                        "thrown AT the rack rather than dropped near it.")
                .defineInRange("absorbRadius", 0.75D, 0.0D, 4.0D);
        RACK_ABSORB_HEIGHT = b
                .comment("How far ABOVE the rack's base the absorb scan reaches, in blocks. 1.25 covers the",
                        "rack's own height (0.9375) plus the arc of a thrown item; 0 makes the scan",
                        "horizontal-only, so nothing above the block is collected.")
                .defineInRange("absorbHeight", 1.25D, 0.0D, 4.0D);
        RACK_ABSORB_CHECK_INTERVAL_TICKS = b
                .comment("How often the rack scans for dropped items, in ticks. The scan is one entity query",
                        "per rack per interval, not per tick.")
                .defineInRange("absorbCheckIntervalTicks", 8, 1, 1200);
        RACK_CREATIVE_RACK_ENABLED = b
                .comment("Whether the CREATIVE weapon rack works: a separate block (tarkovscav:creative_weapon_rack,",
                        "creative inventory only, no recipe) whose slot is a TEMPLATE - a mob that takes from",
                        "it is converted and armed, and the template stays, so one block can arm an endless",
                        "number of villagers/pillagers; a player right-clicking it gets a copy, also without",
                        "consuming it. Hand a copy to whoever needs one, or put it where you want the armed",
                        "mobs to come from.",
                        "",
                        "Set false to make the block inert: it can still be placed, but it will not arm",
                        "anyone, will not absorb dropped items, and right-clicking it says so. The block",
                        "exists in both cases - there is no way to turn a normal rack into a creative one,",
                        "which is deliberate: a survival player can never get the infinite behaviour by",
                        "accident.")
                .define("creativeRackEnabled", true);
        b.pop();

        // ================================================================= accuracy profile
        b.comment("How good a shot a mob is over the length of an engagement (README 5o): wild first shots,",
                "and a hard ceiling on the steady state.").push("accuracy");
        ACCURACY_ENABLED = b
                .comment("Master switch. false restores the old behaviour exactly (the tier's accuracy, used",
                        "from the first shot, uncapped), which is what to set if you preferred it.")
                .define("enabled", true);
        ACCURACY_HARD_CEILING = b
                .comment("Absolute ceiling for every profile. 1.0 would mean 'never misses', which no unit",
                        "in this mod may be, so the shipped value is 0.95 and a hand-edited profile cap of",
                        "1.0 (or 3.0) is clamped to it.")
                .defineInRange("hardCeiling", 0.95D, 0.0D, 0.95D);
        ACCURACY_ROOKIE_CAP = b
                .comment("rookie: the ordinary gunmen (thugs / pillagers / armed villagers). Their hit",
                        "chance may never exceed this - 0.75 is the user's number for the small fry.")
                .defineInRange("profileRookieCap", 0.75D, 0.0D, 1.0D);
        ACCURACY_VETERAN_CAP = b
                .comment("veteran: the better humanoids (the sniper tier, and future 'stronger' units).",
                        "0.85 by default - noticeably more dangerous, still not a guaranteed hit.")
                .defineInRange("profileVeteranCap", 0.85D, 0.0D, 1.0D);
        ACCURACY_ELITE_CAP = b
                .comment("elite: reserved for whatever comes after the sniper. 0.90 by default; raise the",
                        "hardCeiling too if you ever want more.")
                .defineInRange("profileEliteCap", 0.90D, 0.0D, 1.0D);
        ACCURACY_PROFILE_SCAV = b
                .comment("Which profile a plain scav uses: rookie, veteran or elite.")
                .define("profileScav", "rookie");
        ACCURACY_PROFILE_GUNNER_PILLAGER = b
                .comment("Which profile the gunner pillager uses.")
                .define("profileGunnerPillager", "rookie");
        ACCURACY_PROFILE_GUNNER_VILLAGER = b
                .comment("Which profile the gunner villager uses.")
                .define("profileGunnerVillager", "rookie");
        ACCURACY_PROFILE_SNIPER_TIER = b
                .comment("A mob whose TIER is sniper is promoted to this profile if it is better than the",
                        "one its type gives it, so the sniper (and anything else issued a sniper tier)",
                        "shoots better than a rifleman without a new entity type being told so twice.")
                .define("profileSniperTier", "veteran");
        ACCURACY_WARMUP_SHOTS = b
                .comment("How many shots of an engagement are 'wild' (the warm-up). 0 disables the warm-up.")
                .defineInRange("warmupShots", 8, 0, 1000);
        ACCURACY_WARMUP_MULTIPLIER = b
                .comment("Accuracy multiplier during the warm-up, so the first shots of a fight are the",
                        "player's window. 0.45 x a sniper's 0.85 = 0.38 while warming up.")
                .defineInRange("warmupMultiplier", 0.45D, 0.0D, 1.0D);
        ACCURACY_RESET_TICKS = b
                .comment("Ticks without firing after which the warm-up starts over, so losing a mob and",
                        "meeting it again is a fresh, wild engagement rather than an instant headshot.",
                        "0 keeps the counter for the mob's whole life.")
                .defineInRange("resetTicks", 600, 0, 240000);
        b.pop();

        // ================================================================= gun modding pool
        b.comment("The random attachment pool (README 5p, the 'gun-modder'): mobs that spawn with a",
                "kitted-out gun. Legality is TaCZ's answer (IGun#allowAttachment), never a table of our own.")
                .push("mods");
        MODS_ENABLED = b
                .comment("Master switch for the whole pool. false = every mob gets a bare gun, exactly as",
                        "before this feature existed.")
                .define("enabled", true);
        MODS_PER_SLOT_CHANCE = b
                .comment("Chance, per attachment slot, that the slot is filled (0.35 = a gun usually has one",
                        "or two attachments). Slots the gun does not have are skipped, not counted.")
                .defineInRange("perSlotChance", 0.35D, 0.0D, 1.0D);
        MODS_FULL_MOD_CHANCE = b
                .comment("The 'gun-modder' roll: with this chance every slot the gun allows is filled. 0.02 =",
                        "about one mob in fifty is the fully kitted one.")
                .defineInRange("fullModChance", 0.02D, 0.0D, 1.0D);
        MODS_MAX_PER_GUN = b
                .comment("Hard cap on attachments per gun, whatever the slots allow.")
                .defineInRange("maxPerGun", 5, 1, 8);
        MODS_ALLOW_EXTENDED_MAG = b
                .comment("Allow the EXTENDED_MAG slot. It is the one attachment that changes the gun's",
                        "capacity, which is always re-read from the item afterwards (never assumed), but",
                        "set it false if you want mobs to keep their vanilla magazine size.")
                .define("allowExtendedMag", true);
        b.pop();

        // ================================================================= sniper pillager
        b.comment("The sniper pillager (README 5q): a gunner pillager that fights from a post, leaves when",
                "the post is burned, and is issued a sniper-tier gun (which also puts it in the veteran",
                "accuracy profile automatically).").push("sniper");
        SNIPER_ENABLED = b
                .comment("Master switch for the mob: false stops it spawning and stops the post logic (the",
                        "entity can still be summoned by hand for testing).")
                .define("enabled", true);
        SNIPER_FOLLOW_RANGE = b
                .comment("FOLLOW_RANGE in blocks: how far away it notices a target. A rifleman is 35, which",
                        "is why the sniper's post is worth holding - it engages long before it is reachable.",
                        "The default MUST stay equal to Config.DEFAULT_SNIPER_FOLLOW_RANGE: that constant is",
                        "what the entity attribute set is registered with (a config value cannot be read",
                        "during attribute creation, see the constant), and this key is applied to each",
                        "sniper the moment it enters a level - so the two only looking the same is what",
                        "keeps a fresh install identical to a tuned one.")
                .defineInRange("followRange", DEFAULT_SNIPER_FOLLOW_RANGE, 16.0D, 128.0D);
        SNIPER_DISCOVERED_RANGE = b
                .comment("Relocation trigger 2: the target has line of sight AND is closer than this.")
                .defineInRange("discoveredRange", 24.0D, 4.0D, 64.0D);
        SNIPER_SHOTS_BEFORE_MOVE = b
                .comment("Relocation trigger 3: shots fired from one post before it moves (shoot and scoot).",
                        "1 = a true one-shot-then-move sniper, 2 = a pair of shots.")
                .defineInRange("shotsBeforeMove", 2, 1, 20);
        SNIPER_MIN_POST_DISTANCE = b
                .comment("How far the new post must be from the mob (and, by construction, roughly from the",
                        "target): the user's 16 blocks.")
                .defineInRange("minPostDistance", 16.0D, 8.0D, 64.0D);
        SNIPER_CLOSE_RANGE = b
                .comment("Relocation trigger 4 and the melee rule: inside this range the sniper backs off",
                        "instead of trading blows. Melee stays available as a last resort only.")
                .defineInRange("closeRange", 8.0D, 2.0D, 24.0D);
        SNIPER_MOVE_SPEED = b
                .comment("Navigation speed while relocating (vanilla walking is 1.0).")
                .defineInRange("moveSpeed", 1.1D, 0.5D, 2.0D);
        SNIPER_RELOCATE_TIMEOUT_TICKS = b
                .comment("If the walk to a new post takes longer than this, it stops trying, digs in where it",
                        "is and re-acquires the target - so a bad path can never leave it wandering.")
                .defineInRange("relocateTimeoutTicks", 200, 20, 2400);
        SNIPER_MIN_SPAWN_DISTANCE = b
                .comment("Natural spawns are refused closer than this to any player, so a sniper never pops",
                        "into existence in someone's face.")
                .defineInRange("minSpawnDistanceFromPlayer", 32.0D, 8.0D, 128.0D);
        SNIPER_PREFER_HIGH_GROUND = b
                .comment("Require an elevated spawn: the column must be at least minElevation blocks above",
                        "most of the surrounding ground, so snipers appear on rooftops and ridges.")
                .define("preferHighGround", true);
        SNIPER_MIN_ELEVATION = b
                .comment("How much higher than its surroundings an elevated spawn must be, in blocks.")
                .defineInRange("minElevation", 4, 1, 32);
        SNIPER_SPAWN_WEIGHT = b
                .comment("Relative natural-spawn weight (the scav is 5, the gunner villager 2). 0 disables",
                        "natural spawning without disabling the mob. Kept EQUAL to the",
                        "tarkovscav:sniper_pillager weight in the biome modifier, which is what the game",
                        "actually reads.")
                .defineInRange("spawnWeight", 1, 0, 1000);
        SNIPER_VILLAGER_SPAWN_WEIGHT = b
                .comment("Relative natural-spawn weight of the SNIPER VILLAGER (tarkovscav:sniper_villager),",
                        "the villager half of the sniper pair. Deliberately no higher than the pillager",
                        "sniper's, because a friendly long-range unit is a bigger change to a village",
                        "than another pillager. 0 disables its natural spawning without disabling the",
                        "mob. Kept EQUAL to the tarkovscav:sniper_villager weight in the biome modifier.")
                .defineInRange("villagerWeight", 1, 0, 1000);
        b.pop();

        // ================================================================= the one-time city garrison
        b.comment("The one-time city garrison: a FIXED number of TROOP-tier squads per city, spawned the",
                "first time a player comes near it and NEVER respawned.",
                "",
                "The state is a SavedData entry keyed by dimension + the city's identity, so a dead",
                "garrison stays dead across a chunk unload, a server restart and a world reload - the",
                "trigger checks the ledger and simply does nothing the second time. This is a different",
                "mechanism from the biome spawner in [spawn]: that one keeps the city populated, this one",
                "gives each city a single, finite, memorable garrison.",
                "",
                "Composition: tarkovscav:usec_villager / tarkovscav:bear_pillager - the TROOP tier in",
                "gun/AiProfile.java - optionally with ONE elite leader per squad. The units are placed on",
                "valid standing spots in or around the city (solid ground, two blocks of headroom, never",
                "in a doorway, interiors preferred) and are made a real squad through gun/SquadCoordinator",
                "(one shared squad id, one claimed cover spot each), not a pile of unrelated mobs.",
                "",
                "Diagnostics: every city that spawns logs one",
                "[garrison] <city> -> N squads / M units line, and /armedmobs garrison prints the ledger.").push("garrison");
        GARRISON_ENABLED = b
                .comment("Master switch. false stops new garrisons from being placed; one already placed",
                        "stays where it is, and its ledger entry is kept.")
                .define("enabled", true);
        GARRISON_SQUADS_PER_CITY = b
                .comment("How many squads a city gets. -1 (the default) uses the SIZE FORMULA",
                        "    1 + max(width, depth) / 48, clamped to 1..6",
                        "so a small strongpoint gets one squad and a full district gets several, without",
                        "anyone having to tune per city. A value of 1..6 pins every city to that number.",
                        "The formula is the same computation CityGarrison.squadsForCity does, and",
                        "tools/selftest_garrison.js mirrors it.")
                .defineInRange("squadsPerCity", -1, -1, 6);
        GARRISON_SQUAD_SIZE_MIN = b
                .comment("Smallest squad (the user's \"1-5 people per squad\"): 1.")
                .defineInRange("squadSizeMin", 1, 1, 16);
        GARRISON_SQUAD_SIZE_MAX = b
                .comment("Largest squad: 5. If min is set above max the two are swapped at read time,",
                        "so a hand-edited toml can never make the roll crash or return an empty squad.")
                .defineInRange("squadSizeMax", 5, 1, 16);
        GARRISON_ELITE_LEADER_CHANCE = b
                .comment("Chance per squad that it also gets ONE elite-tier leader (tarkovscav:elite_villager",
                        "or tarkovscav:elite_pillager, the two ELITE units from gun/AiProfile.java).",
                        "Default 0.2, so about one squad in five is led by an elite - enough to be a",
                        "memorable surprise, rare enough that a city never reads as an elite nest.",
                        "0 disables leaders entirely; the leader is an ADDITIONAL unit on top of the",
                        "1-5 troop members.")
                .defineInRange("eliteLeaderChance", 0.2D, 0.0D, 1.0D);
        GARRISON_TRIGGER_RADIUS = b
                .comment("How close a player has to be to a city's box for its garrison to be placed,",
                        "in blocks. 64 is 'you can see the walls and the garrison is already manning",
                        "them'. Distances are measured to the nearest point of the city box, so a big",
                        "district triggers from its edge, not from its centre.")
                .defineInRange("triggerRadius", 64.0D, 16.0D, 256.0D);
        GARRISON_CHECK_INTERVAL_TICKS = b
                .comment("How often the trigger runs, in ticks. It is a COARSE interval on purpose:",
                        "the check walks the loaded chunks near each player, which is cheap but not free,",
                        "and a garrison is a one-time event. 100 ticks = 5 s.")
                .defineInRange("checkIntervalTicks", 100, 20, 6000);
        GARRISON_FRIENDLY_CITY_CHANCE = b
                .comment("Chance that a city's DOMINANT line-up is the village faction (tarkovscav:usec_",
                        "villager / elite_villager); the rest of the time it is the illager faction",
                        "(tarkovscav:bear_pillager / elite_pillager). 0.5 is an even split, 0 makes every",
                        "city lean illager and 1 makes every city lean village - the 'all hostile / all",
                        "friendly world' lever.",
                        "The roll is seeded by the world seed and the city key and is RECORDED the first",
                        "time the city is asked about, so editing this value later never flips a city that",
                        "already exists.")
                .defineInRange("friendlyCityChance", 0.5D, 0.0D, 1.0D);
        GARRISON_CITY_DOMINANT_FACTION_CHANCE = b
                .comment("Chance that a whole city comes out UNIFIED (every building takes the dominant",
                        "line-up). Otherwise the city is CONTESTED and every building rolls its own faction",
                        "independently, 50/50 - so one chunk can hold a village building and an illager",
                        "building next door.",
                        "0.5 (the default) means about half the cities are unified and half are contested;",
                        "1.0 restores the old 'one city, one faction' behaviour exactly; 0.0 makes every",
                        "city contested. Recorded per city and per building on first sight, so it is also",
                        "safe to change later - existing cities keep the split they were given.")
                .defineInRange("cityDominantFactionChance", 0.5D, 0.0D, 1.0D);
        GARRISON_REWRITE_CITY_SPAWNERS = b
                .comment("Rewrite the city's minecraft:spawner blocks to their building's faction when the",
                        "city's faction is first decided. Each spawner's SpawnData / SpawnPotentials are set",
                        "to that faction's TROOP id (tarkovscav:usec_villager in a village building,",
                        "tarkovscav:bear_pillager in an illager one); every other spawner key (count, range,",
                        "delays) is preserved, spawners outside the city are never touched, and only LOADED",
                        "chunks are visited - the rewrite is retried until the whole city box was covered.",
                        "false keeps the shipped mixed TROOP pair baked into the structure NBT and leaves",
                        "purity to garrison.factionSpawnFilter.")
                .define("rewriteCitySpawners", true);
        GARRISON_FACTION_SPAWN_FILTER = b
                .comment("Natural-spawn purity: inside a city, a village-faction mob may not spawn in an",
                        "illager building and an illager-faction mob may not spawn in a village building.",
                        "The check uses entity-type tags (faction/Faction.java), so it covers this mod's",
                        "gunners, snipers, TROOP and ELITE units AND vanilla villagers / pillagers /",
                        "golems. The SCAV faction is the unaligned third party and is deliberately allowed",
                        "in BOTH city types. A city that has no ledger entry yet ROLLS one on the spot, so",
                        "the filter can never block everything by accident. Manual /summon and spawn eggs",
                        "are exempt unless spawn.gateCommandSpawns is on.")
                .define("factionSpawnFilter", true);
        b.pop();

        // ================================================================= ladder climbing
        b.comment("Ladder climbing for the nine armed units (README 7o).",
                "",
                "Mobs cannot climb ladders in vanilla: WalkNodeEvaluator grows no vertical edges for a",
                "ladder, and Mob has no 'shove yourself up the rungs' branch - only LocalPlayer#aiStep has",
                "one. So the generator's ladder shafts were, for a mob, a wall it could never use: a unit",
                "ordered to a mark on the next floor would stand at the shaft foot until the order expired.",
                "",
                "The mod now adds all three missing layers itself:",
                "  1. a vertical link - when the destination is on another floor, find a climbable shaft",
                "     near the unit (ladder block, headroom, a usable opening on the target floor) and split",
                "     the trip into 'walk to the foot -> climb -> step out -> carry on';",
                "  2. the movement - stick to the ladder, move up (or down) at the configured speed, and",
                "     hand control back to the normal navigator only once the unit is standing on the",
                "     target floor;",
                "  3. the intent - a goal that only runs when the destination really is on another floor",
                "     and a shaft was found.",
                "",
                "While a unit is on the rungs it does not shoot or reload (a muzzle pointed at the ceiling",
                "hits nothing); combat and retreat still win over climbing, and an interrupted climb ends",
                "standing on a floor, never hanging in the shaft. Falling inside a shaft does not hurt by",
                "default, because a one-block slip off a rung is not a fall. Set enabled = false to get the",
                "old behaviour back. Diagnostics: the shaft report is printed by the structure generator and",
                "asserted by tools/selftest_ladder.js.").push("ladder");
        LADDER_ENABLED = b
                .comment("Master switch. false = exactly the old behaviour (a mob will not use a ladder at",
                        "all), which is also the way to A/B the feature in game.")
                .define("enabled", true);
        LADDER_CLIMB_SPEED = b
                .comment("Upward speed while on the rungs, in blocks per tick. The player climbs at about",
                        "0.2; 0.15 keeps a unit slower than a player so being chased up a shaft still",
                        "reads the way it should.")
                .defineInRange("climbSpeed", 0.15D, 0.01D, 1.0D);
        LADDER_DOWN_SPEED = b
                .comment("Downward speed, in blocks per tick. Deliberately slower than the climb: a unit",
                        "that drops down a shaft at full speed looks like it fell, not like it climbed,",
                        "and it would outrun the squadmates it is supposed to arrive with.")
                .defineInRange("downSpeed", 0.10D, 0.01D, 1.0D);
        LADDER_SEARCH_RADIUS = b
                .comment("How far, in blocks, a unit will detour to find a shaft before giving up and",
                        "walking the long way. 8 covers a shaft in the next room or just around a corner;",
                        "much more and units start crossing the whole building to a ladder they did not",
                        "need.")
                .defineInRange("searchRadius", 8, 2, 32);
        LADDER_MAX_HEIGHT = b
                .comment("The tallest single continuous climb, in blocks, before a unit stops and",
                        "re-plans. A guard against a shaft that leads nowhere (or a mod-added infinite",
                        "ladder) turning into a unit that climbs forever.")
                .defineInRange("maxHeight", 48, 4, 256);
        LADDER_COMBAT_WHILE_CLIMBING = b
                .comment("Allow shooting and reloading while on the rungs. Default false: the unit is",
                        "facing the ladder, so its muzzle is in the wall, and letting it fire there both",
                        "wastes ammo and produces shots with no visible source. true is for experimenting.",
                        "Grenades are never thrown from a ladder regardless of this key.")
                .define("combatWhileClimbing", false);
        LADDER_FALL_DAMAGE_IN_SHAFT = b
                .comment("Take fall damage inside a shaft. Default false: slipping one or two blocks off a",
                        "rung is part of climbing, and vanilla fall damage for it reads as a bug. A unit",
                        "knocked OUT of the shaft (no ladder under it any more) always takes normal fall",
                        "damage - this key only affects the shaft itself.")
                .define("fallDamageInShaft", false);

        // ================================================================= city capture
        b.comment("City capture, OVERWORLD ONLY (README 7p).",
                "",
                "The user's rule, verbatim: 'occupying cities only exists in the overworld', 'if it is not",
                "captured, units of a few line-ups keep spawning until one side's strength is gone (the",
                "idea is the same as Battlefield)', 'the wasteland is a free-for-all between everyone, no",
                "capture needed'.",
                "",
                "So the two dimensions are deliberately different:",
                "  overworld     - every city gets a strength pool per faction present, sized from the",
                "                  city's building count. Both sides keep receiving reinforcements while",
                "                  their pool is above zero, every death drains that faction's pool, and",
                "                  when a pool reaches zero that faction stops spawning in that city for",
                "                  good: natural spawns, spawner spawns and garrison top-ups are all",
                "                  refused. The surviving faction has TAKEN the city.",
                "  wasteland     - the same per-building line-ups, no pools, no HUD, no winner. It stays",
                "                  a brawl, as asked.",
                "",
                "Nothing is written into the terrain: a captured city is a ledger entry, and stopping the",
                "loser's spawns is a runtime veto (LivingSpawnEvent.SpecialSpawn), so the spawners keep",
                "their blocks and the whole thing is reversible with a command. The pools and the capture",
                "flag survive a restart. Diagnostics: /armedmobs capture.").push("capture");
        CAPTURE_ENABLED = b
                .comment("Master switch for the overworld capture game. false keeps per-building factions",
                        "and the garrison, but builds no pools and never stops a faction from spawning.")
                .define("enabled", true);
        CAPTURE_HUD_ENABLED = b
                .comment("Show the strength bars while a player is inside a contested city. Client-side",
                        "rendering only - the server just syncs the two numbers - so turning it off cannot",
                        "change who wins.")
                .define("hudEnabled", true);
        CAPTURE_POOL_MIN = b
                .comment("The smallest starting pool, for the smallest city. The design bounds are 20..100",
                        "men; this is the floor of that range.")
                .defineInRange("poolMin", 20, 4, 200);
        CAPTURE_POOL_MAX = b
                .comment("The largest starting pool, for a city with enough buildings to reach it. A pool",
                        "is clamp(poolMin + buildings * poolPerBuilding, poolMin, poolMax), so a city with",
                        "few buildings sits near poolMin and a big one is capped here.")
                .defineInRange("poolMax", 100, 4, 400);
        CAPTURE_POOL_PER_BUILDING = b
                .comment("How many men each building in the city adds to that faction's pool. 2 puts a",
                        "4-building city at 28 and a 40-building city at the 100 cap.")
                .defineInRange("poolPerBuilding", 2, 0, 20);
        CAPTURE_DRAIN_PER_KILL = b
                .comment("How much each death drains from that faction's pool in that city. 1 means the",
                        "pool is literally a head count: it reaches zero exactly when that many of that",
                        "faction have died there.")
                .defineInRange("drainPerKill", 1, 1, 10);
        CAPTURE_PLAYER_KILLS_ONLY = b
                .comment("Only deaths caused by a player drain the pool. Default false is the Battlefield",
                        "reading the user described: the two line-ups fight each other whether or not a",
                        "player is there, so the front moves on its own and the player is an accelerator",
                        "rather than the only cause.")
                .define("playerKillsOnly", false);
        CAPTURE_HUD_HIDE_DELAY_SECONDS = b
                .comment("How long the bars stay on screen after the player leaves the city, in seconds.",
                        "They also hide immediately once only one faction is left alive there.")
                .defineInRange("hudHideDelaySeconds", 8, 0, 60);
        b.pop();

        // ================================================================= the command system
        b.comment("The command system: three faction-locked command tools, a thrown signal stick and a",
                "signal-point block, plus the neutral A/B/C/D marks they all work through.",
                "",
                "A mark is NEUTRAL - any of the three tools can order its own faction at any mark. The",
                "faction lives on the TOOL and nowhere else: the village tool only ever affects",
                "#tarkovscav:faction_village, the illager tool #tarkovscav:faction_illager and the scav",
                "tool #tarkovscav:faction_scav, and never each other's (the user's rule: the tools must",
                "not reach across line-ups).",
                "",
                "Right-click semantics:",
                "  tool + right-click BLOCK          -> new mark (next free letter), becomes current,",
                "                                       and the faction advances on it immediately",
                "  tool + sneak + right-click BLOCK  -> new mark only, no order (set A/B/C/D up first)",
                "  tool + right-click AIR            -> order the current mark",
                "  tool + sneak + right-click AIR    -> cycle the current mark, shown in chat",
                "  throw the signal stick            -> a mark that lives 5 minutes, then vanishes",
                "  place the signal point block      -> a permanent mark; breaking the block removes it",
                "",
                "Advancing is deliberately slow and cover-aware, and combat and self-preservation always",
                "win: a unit in a firefight or breaking contact does not walk to a mark, and an order",
                "whose mark is gone is dropped. Orders live on the unit (persistent NBT), so they survive",
                "a restart. Diagnostics: /armedmobs marks, /armedmobs garrison, /armedmobs debug.").push("command");
        COMMAND_ENABLED = b
                .comment("Master switch for the whole system. false makes the tools inert (they say so),",
                        "stops new orders being given and stops existing orders from advancing.")
                .define("enabled", true);
        COMMAND_RADIUS = b
                .comment("How far from the mark a unit is picked up, in blocks. The default 32 is the",
                        "design doc's number: wide enough to gather a street, narrow enough that a mark",
                        "does not pull in the whole city.")
                .defineInRange("radius", 32.0D, 4.0D, 256.0D);
        COMMAND_SPEED_SCALE = b
                .comment("Advance speed as a multiple of the unit's own walking speed, and sprinting is",
                        "always off. 0.65 is the design doc's \"slowly\" - noticeably slower than a walk,",
                        "so an ordered advance reads as a deliberate push and not a charge.")
                .defineInRange("speedScale", 0.65D, 0.05D, 1.0D);
        COMMAND_ARRIVAL_RADIUS = b
                .comment("A unit that gets this close to the mark has arrived: the order is cleared and",
                        "its own AI takes over again.")
                .defineInRange("arrivalRadius", 4.0D, 0.5D, 32.0D);
        COMMAND_STICK_DURATION_TICKS = b
                .comment("How long a thrown signal stick's mark (and a right-click tool mark) lives, in",
                        "ticks. 6000 = 5 minutes, the design doc's number. A signal-POINT mark is",
                        "permanent and is not affected by this key.")
                .defineInRange("stickDurationTicks", 6000, 20, 240000);
        COMMAND_COORDINATION = b
                .comment("Coordinate the advance: stagger the departures by entity id and leapfrog the",
                        "element (one member holds while the others move) through the EXISTING squad",
                        "overwatch rotation. false = every unit starts on the same tick and walks, which",
                        "is the behaviour tools/selftest_command_marks.js asserts against the true case.")
                .define("coordination", true);
        COMMAND_MAX_MARKS = b
                .comment("How many marks one dimension may hold. Adding one past the cap drops the OLDEST",
                        "mark, never the new one.")
                .defineInRange("maxMarks", 12, 1, 64);
        b.pop();

        // ================================================================= client / model
        b.comment("Client-side presentation and the Bedrock rig.").push("client");
        USE_GECKO_MODEL = b
                .comment("Draw the gun-armed pillager with the GeckoLib Bedrock rig instead of the",
                        "vanilla illager model.")
                .define("useGeckoModel", false);
        RENDER_SCALE = b
                .comment("Render scale of the Bedrock-rig mobs (the scav, and the gunner pillager when",
                        "useGeckoModel = true). The shipped rig is authored at 0.7 (YSM's",
                        "height_scale/width_scale), which makes it roughly player height.",
                        "",
                        "**Live-tunable**: /tarkovscav client scale <value|up|down> [step] writes this key,",
                        "saves the toml and applies on the NEXT FRAME (the renderers read it per render, not",
                        "in their constructors).",
                        "",
                        "The user's own preference is \"1.1 times bigger\", which is 0.77 here (0.7 x 1.1) -",
                        "the default stays 0.7 because that is the authored baseline, not a matter of taste.",
                        "",
                        "**This key no longer touches the villager-based mobs**: they use their own",
                        "villagerRenderScale (default 1.0 = vanilla villager size), because sharing one",
                        "number made every armed villager 10 % too big as soon as the rig was enlarged.",
                        "",
                        "Two things grow with it: the shadow radius (0.5 x scale) and the frustum-culling",
                        "padding (cullingBoxPadding x scale/0.7), because the rig already overflows the",
                        "0.6 x 1.95 hitbox and a bigger rig overflows it more. See README 5b/10.")
                .defineInRange("renderScale", DEFAULT_RENDER_SCALE, RENDER_SCALE_MIN, RENDER_SCALE_MAX);
        VILLAGER_RENDER_SCALE = b
                .comment("Render scale of the VILLAGER-based mobs: gunner_villager, sniper_villager,",
                        "usec_villager and elite_villager (they share one renderer, so all four follow this",
                        "key).",
                        "",
                        "Default 1.0 = exactly the vanilla villager size, because the vanilla model is",
                        "authored at 1.0 - unlike the Bedrock rig, which is authored at 0.7. That is the",
                        "whole reason this key exists: renderScale is the RIG's baseline, and sharing it",
                        "with a vanilla-model mob made every armed villager grow by rig-scale/0.7.",
                        "",
                        "**Live-tunable**: /tarkovscav client scale villager <value|up|down> [step].",
                        "The shadow radius (0.5 x this) and the villager's culling padding follow it.",
                        "See README 5b/5j.")
                .defineInRange("villagerRenderScale", DEFAULT_VILLAGER_RENDER_SCALE,
                        RENDER_SCALE_MIN, RENDER_SCALE_MAX);
        HIDDEN_BONES = b
                .comment("Bones hidden every frame, written as plain bone names.",
                        "Older rigs carried the author's reference props in the right hand - a board, a",
                        "bag, a bottle, a parrot, a wad of money and the placeholder rifle Gun3 - and",
                        "they had to be hidden so they did not show up in normal play. Hiding a bone",
                        "also hides its children.",
                        "",
                        "EMPTY BY DEFAULT since the 2026 rig re-export: the user deleted all of those",
                        "parts from the model, so there is nothing to hide and every default name would",
                        "have pointed at a bone the rig does not have. Put names back here if you use",
                        "an older rig; a name that is not in the current rig is reported ONCE (per rig",
                        "and bone) and then ignored - never silently, and never per frame.",
                        "",
                        "Body and clothing bones are protected in code and cannot be hidden from here:",
                        "see PROTECTED_BONES in Config.java for the list and why.")
                .defineListAllowEmpty(List.of("hiddenBones"), Config::defaultHiddenBones,
                        element -> element instanceof String);
        LOG_HIDDEN_BONES = b
                .comment("Log once per mob which bones the config hid (and which entries were ignored).")
                .define("logHiddenBones", true);
        GUN_ANCHOR_BONE = b
                .comment("The bone the held TaCZ gun is mounted on.",
                        "",
                        "Default: RightHandLocator. That is the model author's in-hand item locator, and",
                        "it is the bone the rig's own tac:hold:*/tac:aim:*/tac:reload:* clips actually",
                        "animate - so the gun follows the authored gun poses. (Recovered by",
                        "reverse-engineering YSM 2.6.5, which renders the held TaCZ gun on its",
                        "ILocationModel bone groups; the same clips animate RightHandLocator here.)",
                        "",
                        "The older rig also had a Gun3 bone under RightHand - the 'placeholder rifle'",
                        "bone. The 2026 re-export deleted it, so the RightHandLocator -> Gun3 ->",
                        "RightHand fallback chain now simply skips it; RightHandLocator is present in",
                        "both rigs, which is why the default does not have to change.")
                .define("gunAnchorBone", "RightHandLocator");
        GUN_MOUNT_RIFLE_ROTATION = b
                .comment("Extra rotation of the held gun on the anchor bone, in degrees, as [x, y, z].",
                        "",
                        "Default [0, 0, 0]: add nothing. The mount is neutral now because the display",
                        "CONTEXT below is the fix - TaCZ's own renderer already places the gun for a",
                        "third-person hand (its 'thirdperson' display scale, 0.6, is what YSM's magic",
                        "0.65 was approximating), so anything added here is on top of a correct pose.",
                        "",
                        "Tune it live instead of guessing: /tarkovscav client gunpose yaw=... pitch=...",
                        "roll=... [scale=] [x= y= z=] - it applies immediately, prints the toml line and",
                        "writes it to this file. Order of application is x, then y, then z.")
                .defineListAllowEmpty(List.of("gunMountRifleRotation"), () -> DEFAULT_MOUNT_ROTATION,
                        element -> element instanceof String);
        GUN_MOUNT_RIFLE_OFFSET = b
                .comment("Offset from the anchor pivot, in blocks, as [x, y, z] - in the anchor frame the",
                        "mode transform produces (see gunAnchorMode).",
                        "",
                        "Default [0, 0, -0.7] is the USER'S IN-GAME MEASUREMENT, not a simulation: with",
                        "gunAnchorMode = normalisedHand they ran /tarkovscav client gunpose z=-0.7 and the",
                        "rifle landed where it belongs. That also settles the axis question empirically:",
                        "in this hand frame -Z is towards the muzzle, so z is the fore/aft slide. (The",
                        "frame's axes are the item model's own and TaCZ's positioning groups rotate the gun",
                        "inside them, which is why a simulation of the bare frame cannot predict which axis",
                        "looks 'forward' - the in-game value can.)",
                        "",
                        "Simulation only explains the value now: a pure translation cannot change the",
                        "orientation, and z = -0.7 moves the anchor by (dx, dy, dz) = (0.44, -0.07, -0.54)",
                        "blocks in model space - 0.54 blocks forward and 0.44 to the character's left - which",
                        "is exactly the 'forward, a fair distance' the user reported. Tune it with",
                        "/tarkovscav client gunpose forward=0.05 (adds 0.05 along -Z) or set z= directly.")
                .defineListAllowEmpty(List.of("gunMountRifleOffset"), () -> DEFAULT_MOUNT_OFFSET,
                        element -> element instanceof String);
        GUN_MOUNT_RIFLE_SCALE = b
                .comment("Extra uniform scale for rifle-class weapons. Default 1.0: TaCZ applies 0.6",
                        "itself for the third-person-hand context, so this multiplies that.")
                .defineInRange("gunMountRifleScale", DEFAULT_MOUNT_SCALE, 0.05D, 4.0D);
        GUN_MOUNT_PISTOL_ROTATION = b
                .comment("Extra rotation for pistol-class weapons, same rules as the rifle one.")
                .defineListAllowEmpty(List.of("gunMountPistolRotation"), () -> DEFAULT_MOUNT_ROTATION,
                        element -> element instanceof String);
        GUN_MOUNT_PISTOL_OFFSET = b
                .comment("Offset for pistol-class weapons, in blocks. The same user-measured forward slide as",
                        "the rifle default ([0, 0, -0.7]) because a pistol is drawn in exactly the same",
                        "normalisedHand frame on the same kind of anchor - one-handed or not, the frame",
                        "axes and their meaning do not change. This REPLACES YSM's old y = -0.125 default",
                        "rather than adding to it: the triple is the whole offset. Add the -0.125 back",
                        "yourself if a pistol looks too high, e.g. [0, -0.125, -0.7]. To be re-checked on a",
                        "pistol-tier scav.")
                .defineListAllowEmpty(List.of("gunMountPistolOffset"), () -> DEFAULT_MOUNT_OFFSET,
                        element -> element instanceof String);
        GUN_MOUNT_PISTOL_SCALE = b
                .comment("Extra uniform scale for pistol-class weapons.")
                .defineInRange("gunMountPistolScale", DEFAULT_MOUNT_SCALE, 0.05D, 4.0D);
        GUN_MOUNT_DISPLAY_CONTEXT = b
                .comment("The ItemDisplayContext the held gun is rendered with. This is NOT cosmetic -",
                        "TaCZ renders gun items through its own BlockEntityWithoutLevelRenderer and",
                        "that renderer branches on this value (verified in the 1.1.8 bytecode):",
                        "  FIRST_PERSON_LEFT/RIGHT_HAND -> renders nothing at all",
                        "  THIRD_PERSON_LEFT_HAND       -> renders nothing at all",
                        "  GUI                          -> the flat slot icon only",
                        "  FIXED                        -> item-frame layout: translate(0.5,2,0.5),",
                        "                                  scale(-1,-1,1) (a mirror flip!), the model's",
                        "                                  'fixed' positioning group and the fixed display",
                        "                                  scale (1.2 in the default gun pack)",
                        "  THIRD_PERSON_RIGHT_HAND      -> the model's third-person-hand positioning group",
                        "                                  and the thirdperson display scale (0.6)",
                        "Vanilla's ItemInHandLayer - the layer the plain illager renderer uses for the",
                        "same mob - passes THIRD_PERSON_RIGHT_HAND, so this default makes the Bedrock",
                        "path and the vanilla path agree. FIXED is what an item frame uses and is why",
                        "the gun used to sit mirrored, flipped and oversized on the hand.",
                        "Accepts any ItemDisplayContext name, upper case.")
                .define("gunMountDisplayContext", DEFAULT_MOUNT_DISPLAY_CONTEXT);
        GUN_ANCHOR_MODE = b
                .comment("How the anchor bone's frame is turned into a held-item frame.",
                        "Values: locatorAnimated | normalisedHand (default).",
                        "",
                        "  locatorAnimated  - draw the item straight in the anchor bone's own frame.",
                        "      GeckoLib's item layer applies the bone's rotation a SECOND time on top of",
                        "      the transform renderRecursively already left (BlockAndItemGeoLayer calls",
                        "      translateAndRotateMatrixForBone again), so an anchor bone that a clip",
                        "      animates - and RightHandLocator is animated by every tac:* clip - ends up",
                        "      rotated by its own keyframe twice. Measured on this rig: the barrel",
                        "      direction differs by 31.9 degrees in tac:hold:rifle and 27.3 in",
                        "      tac:aim:rifle from the single application, which is why the gun reads as",
                        "      a flat slab lying across the chest instead of a held rifle.",
                        "",
                        "  normalisedHand   - cancel that duplicate rotation and then use the frame",
                        "      vanilla's ItemInHandLayer gives a held item, i.e. the frame a TaCZ gun's",
                        "      own positioning groups are authored for:",
                        "          mulPose(X, -90) * mulPose(Y, 180) * translate(+/-1/16, +0.125, -0.625)",
                        "      This is 'treat the locator as a real hand', and it is the default.",
                        "",
                        "Both modes leave the extra gunMount* rotation/offset/scale in place, so the",
                        "tuner still works; /tarkovscav client gunpose mode=... switches live.")
                .define("gunAnchorMode", DEFAULT_ANCHOR_MODE);
        GUN_OFFHAND_ANCHOR_BONE = b
                .comment("The bone the OFFHAND item is mounted on - the left-hand counterpart of",
                        "gunAnchorBone. Default LeftHandLocator, the rig's own left locator (child of",
                        "LeftHand, same pivot pattern as the right one), which was unused before.",
                        "Fallback chain: this name -> LeftHandLocator -> LeftHand. A rig with none of",
                        "them gets a WARN naming the bones it does have, never a silent empty hand.")
                .define("gunOffhandAnchorBone", "LeftHandLocator");
        RENDER_OFFHAND_ITEM = b
                .comment("Draw the mob's offhand item on gunOffhandAnchorBone, the way vanilla's",
                        "ItemInHandLayer draws it in the left hand. Nothing is drawn when the offhand",
                        "is empty, so this only matters for a mob that carries something there.")
                .define("renderOffhandItem", true);
        GUN_TWO_HANDED_SUPPORT = b
                .comment("EXPERIMENTAL: also draw the MAIN-hand gun on the offhand anchor, so a",
                        "two-handed grip can be lined up when the rig's clips do not put the left hand",
                        "on the handguard. Off by default because it draws the gun TWICE - the two",
                        "copies must be made to coincide with gunMountOffhand*, and while they do not",
                        "they z-fight. The rig's own tac:hold:*/tac:aim:* clips already pose the left",
                        "arm across the weapon, so most rigs do not need this.")
                .define("gunTwoHandedSupport", false);
        GUN_MOUNT_OFFHAND_ROTATION = b
                .comment("Extra rotation for whatever is drawn on the offhand anchor, in degrees, [x, y, z]",
                        "applied in that order. Also used for the optional two-handed support copy.")
                .defineListAllowEmpty(List.of("gunMountOffhandRotation"), () -> DEFAULT_OFFHAND_ROTATION,
                        element -> element instanceof String);
        GUN_MOUNT_OFFHAND_OFFSET = b
                .comment("Extra offset for the offhand anchor, in blocks.")
                .defineListAllowEmpty(List.of("gunMountOffhandOffset"), () -> DEFAULT_OFFHAND_OFFSET,
                        element -> element instanceof String);
        GUN_MOUNT_OFFHAND_SCALE = b
                .comment("Extra uniform scale for the offhand anchor.")
                .defineInRange("gunMountOffhandScale", DEFAULT_OFFHAND_SCALE, 0.05D, 4.0D);
        LOG_GUN_MOUNT = b
                .comment("Log which bone the gun was mounted on and with which transform, once per mob.")
                .define("logGunMount", true);
        GUNNER_VILLAGER_AIM_ARM_PITCH = b
                .comment("Gunner villager pose: degrees the vanilla villager's crossed-arms block is swung UP",
                        "above its REST while the synced gun state says the weapon is raised",
                        "(ALERT/AIM/FIRE/SUPPRESS/BOLT/REPOSITION).",
                        "",
                        "An OFFSET, not an absolute angle - all four arm keys are. The model captures the arms",
                        "block's baked rotation from the vanilla mesh (PartPose.offsetAndRotation(0, 3, -1,",
                        "-0.75F, 0, 0), about -43 degrees, verified in the 1.20.1 client jar's bytecode) and",
                        "adds this key to it. So 0 = the arms where a normal villager's are, and more negative",
                        "= higher. The aiming silhouette the user confirmed in game was an absolute -100",
                        "degrees, i.e. an offset of -57. The held gun follows the arms through the vanilla",
                        "ItemInHandLayer. Applies on the next frame after /tarkovscav client reload.")
                .defineInRange("gunnerVillagerAimArmPitch", DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH,
                        -180.0D, 180.0D);
        GUNNER_VILLAGER_HOLD_ARM_PITCH = b
                .comment("Same, for a gun that is held but not being used: IDLE with a weapon in hand.",
                        "",
                        "OFFSET from the vanilla rest (see the aim key above). The shipped 0 = the arms sit",
                        "exactly where a normal villager's do, which is what the user asked for after the older",
                        "absolute-angle version laid them flat against the belly (2026: 'when he is idle his",
                        "hands lie flat against his body and clip into it'). Negative lifts the arms above the",
                        "rest, positive presses them down into the body.",
                        "",
                        "Because the gun is anchored on the arms, moving them moves the gun too;",
                        "gunnerVillagerIdleGunRotation takes the same amount back so the muzzle keeps the",
                        "direction the user already confirmed. Those two numbers are one pose: moving one",
                        "without the other turns the muzzle.",
                        "",
                        "The branch always writes the angle - even at 0 - because a ModelPart keeps its",
                        "rotation between frames: after aiming, not writing would leave the arms raised",
                        "forever. 0 therefore means 'the vanilla rest', not 'skip the write'.")
                .defineInRange("gunnerVillagerHoldArmPitch", DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH,
                        -180.0D, 180.0D);
        GUNNER_VILLAGER_RELOAD_ARM_PITCH = b
                .comment("Same, for RELOAD - its own silhouette so a reloading villager reads at a glance.",
                        "OFFSET from the vanilla rest like the other three: 0 (the shipped default) is the",
                        "normal villager arm position, which is what the user asked for; negative lifts them.")
                .defineInRange("gunnerVillagerReloadArmPitch", DEFAULT_GUNNER_VILLAGER_RELOAD_ARM_PITCH,
                        -180.0D, 180.0D);
        GUNNER_VILLAGER_HUNKER_ARM_PITCH = b
                .comment("Same, for RETREAT (breaking contact): shoulders in, weapon down, so 'this one is",
                        "leaving' reads from a distance. OFFSET from the vanilla rest: 0 keeps the normal",
                        "villager arms, positive presses them down.")
                .defineInRange("gunnerVillagerHunkerArmPitch", DEFAULT_GUNNER_VILLAGER_HUNKER_ARM_PITCH,
                        -180.0D, 180.0D);
        GUNNER_VILLAGER_GUN_OFFSET = b
                .comment("Where the held gun sits on the villager, in blocks, as [x, y, z] - applied in the",
                        "arm frame right before the vanilla ItemInHandLayer adds the standard hand frame",
                        "(mulPose XP -90, mulPose YP 180, translate +-1/16, 0.125, -0.625), i.e. the same",
                        "frame TaCZ's third-person gun positioning groups are authored for.",
                        "",
                        "Default [0, 0.06, 0]: a small lift and NO fore/aft slide. In this frame -Z is",
                        "forward (the same convention as the rig's gunMountRifleOffset and as",
                        "/tarkovscav client villagerpose forward=/back=), so 0 means 'sits where the arm",
                        "block puts it'. This is a USER-CONFIRMED value: the gun sat too far forward, the",
                        "user asked for it 'a bit further back', and the shipped default lost the -0.12",
                        "forward slide. A pure translation cannot change orientation, so this is safe to",
                        "nudge; use /tarkovscav client villagerpose back=0.05 for another small step.")
                .defineListAllowEmpty(List.of("gunnerVillagerGunOffset"),
                        () -> DEFAULT_GUNNER_VILLAGER_GUN_OFFSET, element -> element instanceof String);
        GUNNER_VILLAGER_GUN_ROTATION = b
                .comment("Rotation of the gun on the villager, degrees, as [pitch, yaw, roll], applied about X",
                        "then Y then Z inside the arm frame - the knob that turns the weapon from 'lying",
                        "across the chest' into 'held'.",
                        "",
                        "Default [5, 0, 0]: USER-CONFIRMED in game - the value the user calibrated on the",
                        "aiming silhouette. History: the factory value was -90 (the muzzle pointed at the",
                        "sky, so he asked for 50 degrees back: -90 + 50 = -40); he later settled on 5 in his",
                        "own instance and 5 is the release baseline. README 5j has the whole history.",
                        "",
                        "The ARM angle adds to this one - both are X rotations on the same pose stack, so the",
                        "gun's tilt is ARMS_REST + armOffset + gunPitch + poseGunPitch - 90 (ARMS_REST is the",
                        "vanilla crossed-arms bake, about -43). With the shipped defaults the four silhouettes",
                        "are -185 aiming, -130 idle, -128 reloading and -128 hunkered; README 5j's table is the",
                        "authoritative list. If one pose needs the gun lower, change that pose's OWN arm offset",
                        "or gun delta rather than this shared base, or split the difference live with",
                        "/tarkovscav client villagerpose pitch=-62. See README 5j.")
                .defineListAllowEmpty(List.of("gunnerVillagerGunRotation"),
                        () -> DEFAULT_GUNNER_VILLAGER_GUN_ROTATION, element -> element instanceof String);
        GUNNER_VILLAGER_IDLE_GUN_ROTATION = b
                .comment("Extra rotation of the held gun while the villager is IDLE (the LOWERED pose), in",
                        "degrees, as [pitch, yaw, roll], ADDED to gunnerVillagerGunRotation - it does NOT",
                        "apply while aiming, reloading or retreating.",
                        "",
                        "Default [-2, 0, 0]: the idle pose carries the gun on the arms and the gun follows",
                        "them, so the muzzle angle is ARMS_REST + armOffset + base + idle - 90. The idle arms",
                        "sit at the vanilla crossed-arms rest (offset 0), so with the shipped base 5 this -2",
                        "puts the idle muzzle at -43 + 0 + 5 - 2 - 90 = -130 - level, with the arm in the",
                        "normal villager position.",
                        "",
                        "If it is the AIMING pose that looks too high, this is NOT the key to change - that is",
                        "gunnerVillagerGunRotation (which moves every pose). Tune live with",
                        "/tarkovscav client villagerpose idlePitch=-2 idleYaw=0 idleRoll=0.")
                .defineListAllowEmpty(List.of("gunnerVillagerIdleGunRotation"),
                        () -> DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION, element -> element instanceof String);
        GUNNER_VILLAGER_RELOAD_GUN_ROTATION = b
                .comment("Extra rotation of the held gun while the villager is RELOADING, in degrees, as",
                        "[pitch, yaw, roll], ADDED to gunnerVillagerGunRotation - it applies to that one pose",
                        "and to nothing else.",
                        "",
                        "Default [0, 0, 0] = the base rotation, i.e. exactly the behaviour of every jar",
                        "shipped before this key existed. It is here because the user asked for a gun rotation",
                        "PER STATE (a separate angle for each pose): the four silhouettes are now four",
                        "independent deltas (RAISED = the base itself, LOWERED, RELOADING, HUNKERED).",
                        "",
                        "Tune live with /tarkovscav client villagerpose reloadPitch=... reloadYaw=...",
                        "reloadRoll=... - it writes the toml and applies on the next frame.")
                .defineListAllowEmpty(List.of("gunnerVillagerReloadGunRotation"),
                        () -> DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION, element -> element instanceof String);
        GUNNER_VILLAGER_HUNKER_GUN_ROTATION = b
                .comment("The same, for RETREAT (the HUNKERED pose): [pitch, yaw, roll] in degrees ADDED to",
                        "gunnerVillagerGunRotation while the villager is breaking contact.",
                        "",
                        "Default [0, 0, 0] - the retreat silhouette is untouched until it is tuned. See",
                        "gunnerVillagerReloadGunRotation for why the rotation is per pose at all.",
                        "",
                        "Tune live with /tarkovscav client villagerpose hunkerPitch=... hunkerYaw=...",
                        "hunkerRoll=...")
                .defineListAllowEmpty(List.of("gunnerVillagerHunkerGunRotation"),
                        () -> DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION, element -> element instanceof String);        GUNNER_VILLAGER_IDLE_GUN_OFFSET = b
                .comment("Extra POSITION of the held gun while the villager is IDLE (LOWERED), in blocks, as",
                        "[x, y, z], ADDED to gunnerVillagerGunOffset - it does not apply while aiming,",
                        "reloading or retreating.",
                        "",
                        "Default [0, 0, 0] = exactly the base offset, i.e. the behaviour of every jar before",
                        "this key existed. It is here because the base offset is shared by all four poses, so",
                        "'push the gun out of his body while idle' could not be done without moving the aiming",
                        "pose the user had already calibrated.",
                        "",
                        "Frame: the same arm frame as gunnerVillagerGunOffset - -Z is forward, +Y is up, +X is",
                        "the villager's right. Tune live with /tarkovscav client villagerpose idleX= idleY=",
                        "idleZ=.")
                .defineListAllowEmpty(List.of("gunnerVillagerIdleGunOffset"),
                        () -> DEFAULT_GUNNER_VILLAGER_IDLE_GUN_OFFSET, element -> element instanceof String);
        GUNNER_VILLAGER_RELOAD_GUN_OFFSET = b
                .comment("The same, for RELOADING: [x, y, z] in blocks ADDED to gunnerVillagerGunOffset.",
                        "Default [0, 0, 0]. Tune with /tarkovscav client villagerpose reloadX= reloadY= reloadZ=.")
                .defineListAllowEmpty(List.of("gunnerVillagerReloadGunOffset"),
                        () -> DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_OFFSET, element -> element instanceof String);
        GUNNER_VILLAGER_HUNKER_GUN_OFFSET = b
                .comment("The same, for RETREAT (HUNKERED): [x, y, z] in blocks ADDED to",
                        "gunnerVillagerGunOffset. Default [0, 0, 0]. Tune with",
                        "/tarkovscav client villagerpose hunkerX= hunkerY= hunkerZ=.")
                .defineListAllowEmpty(List.of("gunnerVillagerHunkerGunOffset"),
                        () -> DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_OFFSET, element -> element instanceof String);
        GUNNER_VILLAGER_GUN_SCALE = b
                .comment("Extra uniform scale for the villager's gun, multiplied on top of TaCZ's own 0.6 for",
                        "the third-person-hand context - so 1.0 is TaCZ's normal size and >1.0 is bigger. The",
                        "final visible gun size is roughly renderScale x this x 0.6.")
                .defineInRange("gunnerVillagerGunScale", DEFAULT_GUNNER_VILLAGER_GUN_SCALE, 0.05D, 4.0D);
        GUNNER_VILLAGER_GUN_ANCHOR = b
                .comment("Which model part the villager's gun hangs off:",
                        "  arms (default) - the animated crossed-arms block, so the gun rises and falls with",
                        "                  the aiming / reload / retreat pose;",
                        "  body          - the torso, so the gun stays put regardless of the arms.",
                        "Any other value falls back to arms with a warning.")
                .define("gunnerVillagerGunAnchor", DEFAULT_GUNNER_VILLAGER_GUN_ANCHOR);
        HIDE_GUN_WHEN_IDLE = b
                .comment("Whether the armed villager's held gun is hidden while it is standing still",
                        "(the LOWERED pose, i.e. gunnerVillagerHoldArmPitch = -20 and the arms carried up",
                        "20 degrees with the gun on them).",
                        "",
                        "false (the default) draws the gun on the crossed arms, which is where the vanilla",
                        "ItemInHandLayer puts a held item and is what the user has already seen. true is a",
                        "TASTE switch for 'the gun floating on crossed arms looks odd' - it changes nothing",
                        "else, and the gun comes back the instant the mob aims, reloads or retreats.")
                .define("hideGunWhenIdle", false);
        MODEL_RENDER_TYPE = b
                .comment("Which stock RenderType the rig is drawn with. A/B knob for the 'at one angle the",
                        "upper body shows the world behind it' family of reports:",
                        "",
                        "  cutout      - entityCutoutNoCull (default). Alpha < 0.1 is discarded, which is how",
                        "                the author hides spare faces - and those faces become see-through",
                        "                holes (12 of them at rest; see tools/scan_transparent_faces.js).",
                        "  zOffset     - entityCutoutNoCullZOffset. Same look, plus a depth bias towards the",
                        "                camera, so the rig wins depth ties against world geometry that",
                        "                INTERSECTS it (grass, flowers, item frames, dropped items) - which is",
                        "                the most plausible reading of 'through the mob I see the scenery'.",
                        "                Cost: nothing measurable; the bias is -1/-10, the same MC uses for",
                        "                its crumbling overlay. Side effect: on a depth tie the mob now wins,",
                        "                so blocks it is embedded in may be hidden slightly less often.",
                        "  translucent - entityTranslucent. Alpha 0 pixels stop punching holes, but the type",
                        "                does not write depth, so sorting against water/glass gets harder and",
                        "                the mob can be drawn behind translucent world geometry. Try it, but",
                        "                expect new artifacts rather than fewer.",
                        "  solid       - entitySolid: no alpha test at all. Silhouette becomes solid, but the",
                        "                author-emptied faces then draw their stored RGB, which is (0,0,0) for",
                        "                50236 of the 65536 texture pixels - expect black patches. Diagnostic only.",
                        "",
                        "Switch live with /tarkovscav client reload after editing, or /tarkovscav client state",
                        "to see which one is in force.")
                .define("modelRenderType", DEFAULT_MODEL_RENDER_TYPE);
        CULLING_BOX_PADDING = b
                .comment("Extra blocks around the entity's culling box used for frustum culling.",
                        "Vanilla culls with Entity#getBoundingBoxForCulling (a mob hitbox: 0.6 x 1.95 for a",
                        "raider) plus a 0.5 inflation, while the Bedrock rig renders 4 x 5.5 model units",
                        "scaled by renderScale = 0.7 - i.e. about 2.8 x 3.85 blocks of visible geometry",
                        "around a 0.6 x 1.95 box. Parts of the rig (and the held gun, which reaches",
                        "further still) therefore sit OUTSIDE the culled box, so near the screen edge the",
                        "whole model can be dropped while it is still visibly on screen. 1.0 block of padding",
                        "covers the difference; the cost is that a mob is submitted for drawing slightly",
                        "more often than vanilla would (a few extra culled-or-not tests, no extra draw).",
                        "Set 0 to go back to vanilla behaviour.")
                .defineInRange("cullingBoxPadding", DEFAULT_CULLING_BOX_PADDING, 0.0D, 8.0D);
        MODEL_LAYERING = b
                .comment("How the rig is animated: single | upperLower (default single).",
                        "",
                        "IMPORTANT: BOTH values are ONE geometry submission per frame - this mod never",
                        "draws the model twice, and there is no 'hide a bone set, draw, hide another set,",
                        "draw again' path anywhere in it. The setting only decides how many animation",
                        "CONTROLLERS drive the one model:",
                        "",
                        "  single      - one controller, one clip for the whole body. While a gun is held it",
                        "                plays the gun clip, and the rig's gun clips contain NO leg tracks",
                        "                (measured: tac:hold/aim/aim:fire/reload have 0 of the 8 leg bones;",
                        "                only tac:idle/walk/run animate legs), so an armed mob's legs hold",
                        "                their rest pose while it walks. Unarmed movement is unaffected.",
                        "  upperLower  - the layered scheme this mod shipped before: a movement controller",
                        "                for the legs and a gun controller for the upper body, so an armed",
                        "                mob walks and shoots at once. Same single submission.",
                        "",
                        "Default is single because that is what was asked for; switch to upperLower to get",
                        "armed walking back. Both are one draw, so this is purely a presentation choice.")
                .define("modelLayering", DEFAULT_MODEL_LAYERING);
        LOG_RENDER_STATS = b
                .comment("Log, every 5 seconds, how many times this mod submitted the rig geometry for",
                        "drawing and how many mobs were rendered. One submission per mob per frame is the",
                        "invariant; anything higher means something is re-rendering the model (the glow",
                        "outline pass is the one legitimate case) and the number is here so it can be seen",
                        "instead of believed. Off by default because it is a diagnostic.")
                .define("logRenderStats", false);
        LOG_GL_STATE = b
                .comment("GL-state debug for the 'part of a mob disappeared and I can see what is behind",
                        "it (looks like it borrowed the other entity's texture)' report.",
                        "",
                        "TaCZ's BedrockGunModel leaves the stencil state behind when it draws a gun: it",
                        "sets stencilFunc(GL_LEQUAL,127,255) / (GL_EQUAL,0,255) and never restores",
                        "GL_ALWAYS, and its enable/disable calls are raw GL11 calls that bypass",
                        "Minecraft's state cache (verified with javap; see README 5k and",
                        "tools/gl_state_audit.js). If anything enables the stencil test afterwards, only",
                        "fragments with stencil 0 are drawn - which is exactly 'the mob is half missing",
                        "and the golem behind it shows through'.",
                        "",
                        "When this is true every entity render and every held-item draw logs the stencil,",
                        "depth, blend, cull and texture state it found and left, and WARNs when the stencil",
                        "func changed underneath us. That WARN is the proof: it names the mob and the draw.",
                        "Off by default (it logs per frame); turn it on for one session.")
                .define("logGlState", false);
        POSE_SOURCE = b
                .comment("Who writes the aim pose - the rig's own clip keyframes, this mod's code, or the",
                        "per-bone decision auto: clips | code | auto (default auto).",
                        "",
                        "Why this key exists: the imported YSM rig expresses its aim pose in Molang that",
                        "references YSM-only variables (ysm.head_yaw, ysm.head_pitch, ...). GeckoLib",
                        "resolves an unknown variable to 0, so all 106 of those keyframes used to",
                        "collapse to constants and the authored aim was dead - this mod's code therefore",
                        "wrote UpperBody and Head itself. With client.molangVariables = true those",
                        "keyframes move again, and TWO writers on one bone is the failure this key",
                        "prevents: on one bone the two contributions add (the angle is larger than",
                        "either author intended) and the frame-to-frame winner can even alternate, which",
                        "reads as the upper body twisting while the mob walks.",
                        "",
                        "  auto  (default) - per bone: if the clip that is playing drives that bone from",
                        "                    the entity's look (its keyframes are Molang, not constants)",
                        "                    AND client.molangVariables is true, the clip owns it and the",
                        "                    code writes nothing there; otherwise the code keeps its",
                        "                    absolute fallback write. The code's TOTAL yaw gain stays 1.0",
                        "                    whatever is left to it, so reviving the author's tracking",
                        "                    cannot double the angle. With molangVariables = false nothing",
                        "                    is look-driven and auto behaves exactly like code.",
                        "  code            - the code owns UpperBody and Head, exactly as it did before",
                        "                    the author's Molang was revived. Combine with",
                        "                    client.molangVariables = false (or pitch) for the previous",
                        "                    behaviour byte for byte. NOT an equal A/B with molangVariables",
                        "                    = all: the author's live UpBody -head_yaw then cancels the",
                        "                    code's +head_yaw on the same chain, so the look stops being",
                        "                    tracked (measured gain 0 in tools/selftest_pose_writers.js).",
                        "  clips           - the code writes no pose bone at all; the rig is entirely the",
                        "                    author's. A rig whose clips are missing plays nothing, and",
                        "                    headRestPitchDegrees is inactive by definition.",
                        "",
                        "Switch live with /tarkovscav client pose auto|code|clips, or edit and run",
                        "/tarkovscav client reload. client.logPoseWriters prints which writer took which",
                        "bone on every frame, which is how the two-writer case is told apart from a",
                        "wrong-looking single one.")
                .define("poseSource", PoseSource.DEFAULT);
        MOLANG_VARIABLES = b
                .comment("Which of the rig's own aim variables are fed from the live entity, so the",
                        "author's keyframes evaluate to a real pose instead of collapsing to constants:",
                        "off | pitch | all (default pitch).",
                        "",
                        "  ysm.head_yaw         <- EntityModelData#netHeadYaw  (head yaw relative to the",
                        "                          body in degrees; GeckoLib hands the model this value",
                        "                          already negated, and that is what is fed here);",
                        "  ysm.head_pitch       <- EntityModelData#headPitch;",
                        "  query.head_y_rotation, query.head_x_rotation - the same two quantities, which is",
                        "                          what query-namespace consumers ask for;",
                        "  query.is_sneaking    <- pose == CROUCHING (false for these mobs unless a mod",
                        "                          crouches them).",
                        "",
                        "  pitch (default) - feed ysm.head_pitch and query.head_x_rotation only. The",
                        "                    author's PITCH keyframes come alive - the chest lean",
                        "                    (11.2282-0.2*head_pitch), the arm and shoulder angles",
                        "                    (-7.8865-0.75*head_pitch, math.min(...), math.abs(...)) - with",
                        "                    no measurable side effect on the yaw chain.",
                        "  all             - feed every symbol above.",
                        "  off             - feed nothing (every symbol is pinned to 0). This is what the",
                        "                    mod did before this key existed.",
                        "",
                        "Why the yaw symbols are NOT part of the default: the author's yaw keyframes sit",
                        "on UpBody and AllBody, and AllBody is the parent of the legs as well as of the",
                        "torso. They express the BODY's yaw ('the chest un-blades as the head turns'),",
                        "but GeckoLib already rotates the whole model by the entity's body yaw",
                        "(GeoEntityRenderer#applyRotations), so feeding them applies the body yaw a",
                        "second time: tools/selftest_pose_writers.js measures a 31.7 degree peak-to-peak",
                        "yaw of the lower body with 'all' that is 0.0 with 'pitch'. The yaw keyframes are",
                        "also a cancelling pair (UpBody -head_yaw, Head +head_yaw), so they cannot supply",
                        "the look tracking this mod's code does - they only redistribute the twist. Set",
                        "'all' to see that A/B; /tarkovscav client pose molang all|pitch|off switches it",
                        "live.",
                        "",
                        "  math.min / math.abs are NOT variables and are NOT missing: GeckoLib's own",
                        "  MolangParser#doCoreRemaps registers every mclib function under its math.* name",
                        "  (min -> math.min, abs -> math.abs, ...), so expressions such as",
                        "  math.min(-7.8865-0.75*ysm.head_pitch,20) already work, and with 'pitch' they",
                        "  now evaluate on a live value. They are evaluated, not defaulted to 0, and no",
                        "  warning is emitted for them.")
                .define("molangVariables", DEFAULT_MOLANG_VARIABLES);
        TORSO_YAW_SHARE = b
                .comment("How much of the look's yaw the code puts on the TORSO bone (UpperBody); the head",
                        "takes the rest, so the total is always 1.0 and the aim still lands on the target.",
                        "0.0 to 1.0, default 0.25.",
                        "",
                        "This is the knob for the 'the upper body twists about while the mob walks' report,",
                        "and the number is the size of that twist: the code writes",
                        "netHeadYaw * torsoYawShare on UpperBody, so at the old value 0.7 the chest pivoted",
                        "+/-17 degrees (45.3 degrees peak-to-peak over a +/-25 degree look swing) while the",
                        "legs did not move at all - which is exactly what 'the upper body twists' means.",
                        "",
                        "netHeadYaw is by definition the head's yaw RELATIVE TO THE BODY, so the head is",
                        "where the whole of it belongs; a torso share is a stylistic lean, and 0.7 was",
                        "most of the measured artifact. The default 0.25 keeps a little of the lean",
                        "(16.2 degrees peak-to-peak) and 0 removes it entirely; both keep the total gain",
                        "at 1.0, so neither changes where the head points.",
                        "",
                        "tools/selftest_pose_writers.js prints the measured torso swing for every value of",
                        "this share and for every poseSource/molangVariables combination, and asserts that",
                        "the total chain gain stays 1.0.")
                .defineInRange("torsoYawShare", DEFAULT_TORSO_YAW_SHARE, 0.0D, 1.0D);
        LOG_POSE_WRITERS = b
                .comment("Log one [pose] line per rendered frame: the netHeadYaw/headPitch the pose was",
                        "driven from, the writer of every pose bone (clips(molang), clips(fixed) or code)",
                        "and the resulting yaw of Root/AllBody/UpBody/UpperBody/Body/Head plus the head",
                        "chain total.",
                        "",
                        "This is the evidence tool for the 'the upper body twists while it walks' report,",
                        "and the two causes need different fixes:",
                        "  * two writers on one bone -> chainYaw differentiates to more than 1.0x the",
                        "    netHeadYaw difference (the angles add up);",
                        "  * two writers alternating -> chainYaw is not a scaled copy of netHeadYaw at all",
                        "    (its difference does not correlate with the input's).",
                        "Turn it on, walk a mob with a gun for about ten seconds, turn it off. Also",
                        "reports any frame in which one bone got both writers as a WARN - the arbiter",
                        "makes that impossible, so the WARN is a real defect signal, and",
                        "/tarkovscav client state prints the count. Off by default (one line per frame).")
                .define("logPoseWriters", false);
        // ---------------------------------------------------------------- player lean (FPS peek, README 5s)
        // Flat keys under [client], not a [client.lean] section, so the names are exactly the documented
        // client.leanEnabled / leanMaxOffset / leanRollDegrees / leanSpeedTicks / leanSuppressVanillaKeys.
        b.comment("Player leaning / peeking (README 5s): hold Q or E to lean the camera out to one side, like",
                "a first-person shooter. The camera moves (and rolls) on the CLIENT only; the player entity,",
                "its hitbox and its eye position are never changed, so leaning can never be used to see over a",
                "wall you are not actually behind, nor to dodge a hit by having moved a hitbox. What does move",
                "is the muzzle of your own shots, so you can shoot from the lean.");
        LEAN_ENABLED = b
                .comment("Master switch for the whole feature. false = the lean keys do nothing at all: no",
                        "camera change, no key suppression and no shot-origin offset.")
                .define("leanEnabled", true);
        LEAN_MAX_OFFSET = b
                .comment("How far the camera moves sideways at full lean, in blocks. This is also how far the",
                        "muzzle of your own shots moves, so the two can never disagree. 0.6 is a little over",
                        "half a block: enough to clear a corner and to read as a real step to the side.")
                .defineInRange("leanMaxOffset", 0.6D, 0.0D, 1.5D);
        LEAN_ROLL_DEGREES = b
                .comment("How far the view rolls at full lean, in degrees. This is the 'camera tilt' of a",
                        "peek: 12 degrees reads as a head tilt without making aiming hard. 0 = translate only.")
                .defineInRange("leanRollDegrees", 12.0D, 0.0D, 45.0D);
        LEAN_INVERT_OFFSET = b
                .comment("Flip ONLY the sideways movement (the camera and the muzzle). Set this if leaning right",
                        "visually moves you left. The roll is a separate switch on purpose: the two are",
                        "different axes, and 'the lean feels backwards' can come from either one.")
                .define("leanInvertOffset", false);
        LEAN_INVERT_ROLL = b
                .comment("Flip ONLY the view roll. Set this if the horizon tilts the wrong way while the",
                        "movement itself looks right.")
                .define("leanInvertRoll", false);
        LEAN_SPEED_TICKS = b
                .comment("Ticks to go from centred to fully leaned (and back). 5 ticks = a quarter second, which",
                        "is fast enough for a firefight and slow enough to look like a movement.")
                .defineInRange("leanSpeedTicks", 5, 1, 20);
        LEAN_SUPPRESS_VANILLA_KEYS = b
                .comment("Q and E are VANILLA keys (drop item, open inventory), and the lean keys USE them, so",
                        "while this is on those two actions are disabled completely - a short tap included. Not",
                        "'only while leaning': a tap of Q must not throw your weapon on the floor and a tap of E",
                        "must not open your backpack, or the keys are unusable.",
                        "",
                        "It is key-based, not global: only a vanilla key that one of the lean bindings actually",
                        "occupies is taken, so rebinding the lean keys away from Q/E gives Q and E back to the",
                        "game automatically. Nothing else that opens the inventory (a command, another mod) is",
                        "affected, because only the key's own click is consumed.",
                        "",
                        "Set false to leave the vanilla keys completely alone (then a lean will also drop your",
                        "item or open your backpack, which is why it is true by default).")
                .define("leanSuppressVanillaKeys", true);
        LEAN_TAP_THRESHOLD_TICKS = b
                .comment("How long Q/E has to be HELD before it counts as a peek instead of a tap, in ticks",
                        "(5 = 250 ms). Below it the press is replayed as the vanilla action on release; at or",
                        "above it the vanilla action is dropped and the key was a lean.")
                .defineInRange("tapThresholdTicks", 5, 1, 40);
        LEAN_START_MODE = b
                .comment("When the lean starts:",
                        "  immediate     - on press, so it feels instant (a quick tap shows a very short lean",
                        "                  flash, which snaps back the moment you let go);",
                        "  afterThreshold - only once the key has been held for tapThresholdTicks, so a tap",
                        "                  never leans at all.")
                .define("startMode", "immediate");
        LEAN_REPLAY_VANILLA_ON_TAP = b
                .comment("Whether a TAP (shorter than tapThresholdTicks) still does the vanilla action, done by",
                        "us on release: E opens the backpack, Q drops one item. false = a tap does nothing at",
                        "all (the previous behaviour). Either way the vanilla key itself is consumed, so the",
                        "action can never happen twice or at the wrong time.")
                .define("replayVanillaOnTap", true);
        b.pop();

        // ================================================================= kill feed
        b.comment("The kill feed (README 5u): the FPS-style 'killer [weapon] victim' lines at the top of the",
                "screen. The server decides WHO may see a line (see mode/radius); the client only draws what it",
                "was sent, so a long name can never become a formatted or clickable message.").push("killFeed");
        KILLFEED_ENABLED = b
                .comment("Master switch for the HUD element. false = no line is ever drawn (the server also stops",
                        "sending, so nothing is wasted on the wire).")
                .define("enabled", true);
        KILLFEED_MODE = b
                .comment("Who receives a line:",
                        "  involved - only players who are the killer, the victim, or within radius of the",
                        "             death (the default: a fight you are in, plus what happens next to you);",
                        "  all      - every player in the same dimension, whatever the distance;",
                        "  global   - every player on the server, radius ignored.")
                .define("mode", "involved");
        KILLFEED_RADIUS = b
                .comment("How close a player has to be to see a line about somebody else's death, in blocks.",
                        "Only used by mode = involved.")
                .defineInRange("radius", 64.0D, 8.0D, 512.0D);
        KILLFEED_SHOW_MOB_KILLS = b
                .comment("Announce kills where neither side is a player (our units killing each other, or a",
                        "village raid). false keeps the feed to fights a player is part of.")
                .define("showMobKills", true);
        KILLFEED_SHOW_PLAYER_KILLS = b
                .comment("Announce kills with a player on either side.")
                .define("showPlayerKills", true);
        KILLFEED_SHOW_ENVIRONMENT_DEATHS = b
                .comment("Announce deaths with no killer at all (falling, drowning, a cactus). They are shown as",
                        "'Environment' rather than being dropped, because 'what killed that guy?' is exactly the",
                        "question the feed answers.")
                .define("showEnvironmentDeaths", true);
        KILLFEED_LINE_DURATION_TICKS = b
                .comment("How long one line stays on screen, in ticks (100 = 5 seconds including its fades).")
                .defineInRange("lineDurationTicks", 100, 20, 1200);
        KILLFEED_MAX_LINES = b
                .comment("How many lines are kept; the oldest is dropped when a new one arrives.")
                .defineInRange("maxLines", 5, 1, 20);
        KILLFEED_POSITION = b
                .comment("Where the block of lines sits: top_center (default), top_left or top_right. It is",
                        "always at the TOP of the screen; only the horizontal anchor changes.")
                .define("position", "top_center");
        KILLFEED_SCALE = b
                .comment("Text scale. The lines are truncated to the available width instead of wrapping, so a",
                        "long name cannot push the block off the screen.")
                .defineInRange("scale", 1.0D, 0.5D, 2.0D);
        KILLFEED_MAX_PER_SECOND = b
                .comment("Throttle: at most this many lines per second reach one player. A grenade that kills six",
                        "mobs produces six records but not six simultaneous lines.")
                .defineInRange("maxPerSecond", 4, 1, 40);
        KILLFEED_DEDUP_TICKS = b
                .comment("Drop a record that repeats the same killer + victim + weapon within this many ticks",
                        "(a mob that dies twice to the same shotgun blast, a double death message).")
                .defineInRange("dedupTicks", 40, 0, 400);
        b.pop();

        // ================================================================= faction troops
        b.comment("The faction troops (README 5y): the USEC villager, the BEAR pillager and their elite",
                "counterparts. Same systems as the mobs they extend; what is new is 40 health, a rolled armor",
                "class and a voice of their own.").push("troops");
        ARMOR_ENABLED = b
                .comment("Master switch for the rolled armor class. false = the class is still rolled and",
                        "stored, but it reduces nothing (and /tarkovscav debug still shows it).")
                .define("armorEnabled", true);
        ARMOR_REDUCTION_PER_CLASS = b
                .comment("Damage reduction per armor class: 0.10 means class 1 takes 10 % less damage and",
                        "class 6 takes 60 % less. The mobs do NOT wear vanilla armor on purpose - vanilla",
                        "armor points would reduce damage a second time, and the total would exceed 60 %.")
                .defineInRange("armorReductionPerClass", 0.10D, 0.0D, 0.25D);
        ELITE_MIN_ARMOR_CLASS = b
                .comment("Lowest armor class a newly spawned troop can roll.")
                .defineInRange("minArmorClass", 1, 1, 6);
        ELITE_MAX_ARMOR_CLASS = b
                .comment("Highest armor class a newly spawned troop can roll.")
                .defineInRange("maxArmorClass", 6, 1, 6);
        USEC_VILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the USEC villager (the plain gunner villager is 2).",
                        "Kept EQUAL to the tarkovscav:usec_villager weight in the biome modifier.")
                .defineInRange("usecVillagerWeight", 1, 0, 1000);
        BEAR_PILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the BEAR pillager (the plain gunner pillager is 2).",
                        "Kept EQUAL to the tarkovscav:bear_pillager weight in the biome modifier.")
                .defineInRange("bearPillagerWeight", 1, 0, 1000);
        ELITE_VILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the elite villager - the rarest of the four on purpose.")
                .defineInRange("eliteVillagerWeight", 1, 0, 1000);
        ELITE_PILLAGER_SPAWN_WEIGHT = b
                .comment("Natural spawn weight of the elite pillager - the rarest of the four on purpose.")
                .defineInRange("elitePillagerWeight", 1, 0, 1000);
        b.pop();

        // ================================================================= hard targets / ricochet
        b.comment("Hard targets (README 5z): entities that soak up gunfire and sometimes bounce it. The list",
                "is a data-pack tag (data/tarkovscav/tags/entity_types/hard_target.json), so a pack can add a",
                "mech or a stone golem without a rebuild; only TaCZ BULLET damage is affected - melee,",
                "explosions, fall and fire are untouched.").push("ricochet");
        RICOCHET_ENABLED = b
                .comment("Master switch. false = the hard-target rule does nothing at all (bullet damage is",
                        "full again and nothing ever bounces).")
                .define("enabled", true);
        RICOCHET_GUN_DAMAGE_MULTIPLIER = b
                .comment("Damage multiplier for gunfire that does NOT ricochet: 0.2 = the user's 'reduce gun",
                        "damage by 80 %'. Applied to the blow as TaCZ delivered it, before vanilla armour")
                .defineInRange("gunDamageMultiplier", 0.2D, 0.0D, 1.0D);
        RICOCHET_CHANCE = b
                .comment("Chance that a hit on a hard target RICOCHETS: the blow then does NO damage at all",
                        "(the ricochet is rolled first, so a bounce is not 'reduced damage', it is zero), the",
                        "projectile is reflected and the metal-on-metal sound and sparks play. Otherwise the",
                        "multiplier above applies. 0 = never bounces, 1 = always.")
                .defineInRange("chance", 0.3D, 0.0D, 1.0D);
        RICOCHET_MAX_BOUNCES_PER_BULLET = b
                .comment("How many times ONE projectile may bounce off hard targets before it is treated",
                        "normally. 0 = the reduction still applies but nothing is ever reflected, which is",
                        "also the way to get the 80 % rule without any bullet changing direction.")
                .defineInRange("maxBouncesPerBullet", 1, 0, 8);
        RICOCHET_INCLUDE_ARROWS = b
                .comment("Whether vanilla arrows count as 'gunfire' for this rule (they are not part of",
                        "TaCZ's #tacz:bullets damage tag, so by default an arrow hits a hard target for full",
                        "damage and never bounces).")
                .define("includeArrows", false);
        RICOCHET_SOUND_VOLUME = b
                .comment("Volume of the ricochet sound (SoundEvents.TRIDENT_RICOCHET - the vanilla",
                        "metal-on-metal clang). 0 = silent, the sparks still play.")
                .defineInRange("soundVolume", 0.8D, 0.0D, 4.0D);
        RICOCHET_SPARKS = b
                .comment("Whether the ricochet spawns sparks (ParticleTypes.CRIT + ELECTRIC_SPARK at the",
                        "impact point).")
                .define("sparks", true);
        RICOCHET_LOG = b
                .comment("Log every ricochet (one line per bounced bullet, with the victim and the shooter).",
                        "Off by default: a firefight against a golem would otherwise fill the log.")
                .define("log", false);
        b.pop();

        // ================================================================= the VANT ballistic shield
        b.comment("The VANT ballistic shield (README 5zb): a craftable, passive shield that blocks most",
                "BULLET damage arriving from the holder's front while it is held (main or off hand).",
                "Grenades and every other explosion bypass it by default. Only TaCZ #tacz:bullets damage",
                "is ever reduced - melee, fall and fire are untouched, exactly like the ricochet rule.")
                .push("shield");
        SHIELD_ENABLED = b
                .comment("Master switch. false = the shield does nothing at all (no reduction, no",
                        "durability loss and nothing breaks), although the item still exists.")
                .define("enabled", true);
        SHIELD_BULLET_REDUCTION = b
                .comment("Fraction of the bullet damage a front-facing hit loses: 0.99 = the user's",
                        "'99 % reduction' (a 20-damage round lands as 0.2). 1.0 = a full block, 0.0 =",
                        "the shield stops nothing (and is therefore never charged).")
                .defineInRange("bulletReduction", 0.99D, 0.0D, 1.0D);
        SHIELD_FRONT_ANGLE_DEGREES = b
                .comment("Half-angle of the protected front cone, in degrees, measured from the",
                        "holder's look direction. 90 (the default) = a 180-degree front arc; 45 = a",
                        "narrow 90-degree wedge; 180 = every direction, front and back.")
                .defineInRange("frontAngleDegrees", 90.0D, 0.0D, 180.0D);
        SHIELD_DURABILITY_PER_BLOCKED_HIT = b
                .comment("How much blocked damage one point of durability pays for. The charge is",
                        "max(1, ceil(blockedDamage / this)), so 2.0 = a blocked 20-damage round costs",
                        "10 durability and the 500-durability shield survives 50 such hits.")
                .defineInRange("durabilityPerBlockedHit", 2.0D, 0.1D, 100.0D);
        SHIELD_PROTECT_FROM_EXPLOSIONS = b
                .comment("Whether an explosion (grenade, TNT, creeper) is reduced too. Default false:",
                        "the user's 'cannot defend against grenades' is the shipped behaviour. Setting",
                        "it true is the only way an explosion ever reaches the arc.")
                .define("protectFromExplosions", false);
        b.pop();

        // ================================================================= grenades
        b.comment("Grenades and flashbangs (README 5v): three grenades (frag / HE / smoke), two flashbangs",
                "(standard / short fuse), thrown by the player and by the gun mobs. By default an explosion",
                "does NOT break blocks: it uses the vanilla 'entities only' explosion, so the only thing a",
                "grenade changes about the world is who is still standing.").push("grenades");
        GRENADES_ENABLED = b
                .comment("Master switch. false = the items cannot be thrown (right-click does nothing), no mob",
                        "throws anything, and a fuse that is already burning is defused instead of exploding.")
                .define("enabled", true);
        GRENADES_TERRAIN_DAMAGE = b
                .comment("false (the default) = terrain is NEVER damaged: the blast is an",
                        "ExplosionInteraction.NONE vanilla explosion, which damages entities and leaves every",
                        "block alone. true = a normal block-breaking explosion (it will leave craters).")
                .define("terrainDamage", false);
        GRENADES_THROW_CHARGE_TICKS = b
                .comment("Hold right-click this long for a full-power throw. Shorter holds are a lob, and the",
                        "fuse burns while you hold it, so holding on to a grenade is 'cooking' it.")
                .defineInRange("throwChargeTicks", 20, 4, 100);
        GRENADES_MIN_THROW_SPEED = b
                .comment("Throw speed with no charge (a short lob), in blocks/tick.")
                .defineInRange("minThrowSpeed", 0.6D, 0.2D, 3.0D);
        GRENADES_MAX_THROW_SPEED = b
                .comment("Throw speed at full charge (a long throw).")
                .defineInRange("maxThrowSpeed", 1.6D, 0.4D, 4.0D);
        GRENADES_COOK_WHILE_HOLDING = b
                .comment("Whether the fuse burns down while the grenade is still in hand. true = you can cook a",
                        "grenade to make it airburst (and blow yourself up if you hold too long).")
                .define("cookWhileHolding", true);
        GRENADES_FRIENDLY_FIRE = b
                .comment("false (the default): a grenade never hurts the thrower or anybody in the same faction",
                        "(Faction.allies), so a squad cannot wipe itself out. true = it hurts everybody.",
                        "Mobs additionally refuse to throw at all when an ally is inside the landing zone.",
                        "",
                        "The blast still moves entities it does not hurt - the knockback is not filtered.")
                .define("friendlyFire", false);
        GRENADES_PLAYER_SELF_DAMAGE = b
                .comment("Whether the THROWER can be hurt by their own grenade. true (the default): a player is",
                        "hurt and flashed by their own grenade - it is their own action, and a grenade that",
                        "cannot hurt you is a free 'cook it and walk forward' button. A MOB is never hurt by",
                        "its own throw (its safety check refuses to throw at all when an ally is in the blast).",
                        "",
                        "false = you are immune to your own grenades. The two behaviours side by side:",
                        "  true  - cook it one tick too long and you die, like everybody else;",
                        "  false - you can throw at your own feet to clear a room with no risk. Pick per server.")
                .define("playerSelfDamage", true);
        GRENADE_BLAST_DAMAGE_PER_POWER = b
                .comment("Damage of the explosion itself at point-blank, per unit of blast power. The blast",
                        "radius stays blastPower * 2 blocks, so this key doubles the blast DAMAGE without",
                        "touching its range: power decides the size, this decides how hard it hits.",
                        "",
                        "It used to be a hard-coded 6.0 and is now 12.0 (the user's 'grenade damage is too low,",
                        "double it'), so the frag grenade's small power-1 blast and the HE grenade's power-4",
                        "blast both hit twice as hard at every distance inside their radius. If you want the",
                        "BLAST to be bigger rather than harder, raise grenades.he.blastPower instead - but note",
                        "that it also doubles the radius (and therefore changes where the falloff ends).")
                .defineInRange("blastDamagePerPower", 12.0D, 0.0D, 100.0D);
        GRENADE_IMPACT_SOUND = b
                .comment("Whether a thrown grenade plays the impact/bounce clip (README 5v) when it hits the",
                        "ground or a wall. The clip itself is shipped as sounds/effect/grenade_land.ogg and",
                        "registered from the voice manifest, so this key only decides whether it is used.")
                .define("impactSound", true);
        GRENADE_IMPACT_SOUND_MAX_PER_GRENADE = b
                .comment("How many times ONE grenade may play the impact clip over its flight. The clip is",
                        "about two seconds long, so an uncapped bounce would stack on itself; after this many",
                        "the grenade bounces silently (the small vanilla tick is still there).")
                .defineInRange("impactSoundMaxPerGrenade", 3, 1, 20);
        GRENADE_IMPACT_SOUND_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two impact clips from the same grenade, so a grenade rolling",
                        "down a slope does not machine-gun the sound.")
                .defineInRange("impactSoundCooldownTicks", 8, 0, 100);
        b.comment("--- frag: the damage IS the fragments ---").push("frag");
        GRENADE_FRAG_FUSE_TICKS = b
                .comment("Fuse, in ticks (20 = 1 second).")
                .defineInRange("fuseTicks", 60, 4, 400);
        GRENADE_FRAG_COUNT = b
                .comment("How many fragment rays are cast from the blast point. Each one is a straight line that",
                        "STOPS at the first block, which is what makes a wall real cover.")
                .defineInRange("fragmentCount", 24, 0, 200);
        GRENADE_FRAG_DAMAGE = b
                .comment("Damage of a fragment that hits at point-blank range, before falloff and armour. This was",
                        "7.0 and is now 14.0: the user's 'grenade damage is too low, double it'. Point-blank is",
                        "about 27 damage at 1 block and about 6 at 2 blocks (see the table in README 5v).")
                .defineInRange("fragmentDamage", 14.0D, 0.0D, 100.0D);
        GRENADE_FRAG_RADIUS = b
                .comment("How far the fragments fly, in blocks. Damage falls off linearly to 0 at this range.")
                .defineInRange("fragmentRadius", 10.0D, 1.0D, 32.0D);
        GRENADE_FRAG_ARMOR_PIERCE = b
                .comment("0..1: the share of fragment damage that ignores armour. 0.35 means a full diamond",
                        "suit still takes some damage from a frag grenade.")
                .defineInRange("fragmentArmorPierce", 0.35D, 0.0D, 1.0D);
        GRENADE_FRAG_STEP = b
                .comment("Step length of a fragment ray, in blocks. Smaller = more accurate and more expensive.")
                .defineInRange("fragmentStep", 0.5D, 0.2D, 2.0D);
        b.pop();
        b.comment("--- HE: the blast is the damage, the fragments are a garnish ---").push("he");
        GRENADE_HE_FUSE_TICKS = b
                .comment("Fuse, in ticks. HE grenades are a heavier throw, so they ship a longer fuse.")
                .defineInRange("fuseTicks", 80, 4, 400);
        GRENADE_HE_BLAST_POWER = b
                .comment("Vanilla explosion power for the entity damage (a creeper is 3, TNT is 4). It also sets",
                        "the blast radius (power * 2 blocks), and that is why the doubling above was done with",
                        "blastDamagePerPower and not here: 4.0 -> 8.0 would double the RANGE as well as the",
                        "damage. Raise this only if you want a bigger bang, not a harder one.")
                .defineInRange("blastPower", 4.0D, 0.0D, 20.0D);
        GRENADE_HE_FRAG_COUNT = b
                .comment("Fragments for the HE grenade: fewer than the frag grenade, because the blast does the",
                        "work.")
                .defineInRange("fragmentCount", 8, 0, 200);
        GRENADE_HE_FRAG_DAMAGE = b
                .comment("Damage per HE fragment at point-blank range. Doubled with the rest (4.0 -> 8.0); it is",
                        "a garnish next to the blast, but leaving it alone would have made the two halves of the",
                        "same grenade follow different rules.")
                .defineInRange("fragmentDamage", 8.0D, 0.0D, 100.0D);
        b.pop();
        b.comment("--- smoke: a cloud that takes eyes away ---").push("smoke");
        GRENADE_SMOKE_FUSE_TICKS = b
                .comment("Fuse, in ticks.")
                .defineInRange("fuseTicks", 40, 4, 400);
        GRENADE_SMOKE_RADIUS = b
                .comment("Cloud radius, in blocks.")
                .defineInRange("radius", 4.0D, 1.0D, 16.0D);
        GRENADE_SMOKE_DURATION_TICKS = b
                .comment("How long the cloud lasts, in ticks (300 = 15 seconds). Anything inside it is blinded,",
                        "which for the gun AI means 'lost the target and does not shoot'.")
                .defineInRange("durationTicks", 300, 20, 2400);
        b.pop();
        b.comment("--- flash: the long-fuse standard issue ---").push("flash");
        GRENADE_FLASH_FUSE_TICKS = b
                .comment("Fuse, in ticks (40 = 2 seconds: long enough to be thrown back in theory).")
                .defineInRange("fuseTicks", 40, 4, 400);
        GRENADE_FLASH_RADIUS = b
                .comment("How far the flash reaches, in blocks. Beyond this nothing happens at all.")
                .defineInRange("flashRadius", 12.0D, 1.0D, 48.0D);
        GRENADE_FLASH_INTENSITY = b
                .comment("Brightness of the white overlay at point-blank range, 0..1.")
                .defineInRange("flashIntensity", 1.0D, 0.0D, 1.0D);
        GRENADE_FLASH_PLAYER_BLIND_TICKS = b
                .comment("How long a PLAYER is blinded at point-blank range, in ticks (100 = 5 seconds). The",
                        "overlay fades out over that time; looking away multiplies the duration.")
                .defineInRange("playerBlindTicks", 100, 10, 1200);
        GRENADE_FLASH_MOB_BLIND_TICKS = b
                .comment("How long a MOB is blinded at point-blank range (vanilla Blindness, which the gun AI",
                        "treats as 'cannot see': it drops the target and holds fire).")
                .defineInRange("mobBlindTicks", 120, 10, 2400);
        GRENADE_FLASH_LOOK_AWAY_FACTOR = b
                .comment("Duration and intensity multiplier for somebody facing away from the blast (0.5 means",
                        "half as long). 0 = only a direct look is affected.")
                .defineInRange("lookAwayFactor", 0.5D, 0.0D, 1.0D);
        GRENADE_FLASH_BLINDS_MOBS = b
                .comment("Whether a flashbang blinds MOBS at all (vanilla Blindness, which the gun AI turns into",
                        "panic fire). false = only players are flashed, and the mobs behave as before.",
                        "",
                        "Note that this applies to EVERY mob, our own units included and regardless of faction:",
                        "a flashbang does not check whose side you are on. That is deliberate.")
                .define("blindsMobs", true);
        GRENADE_FLASH_PANIC_FIRE = b
                .comment("What a blinded mob does: true = PANIC FIRE (it keeps shooting, without needing a",
                        "target, at its last known target direction or a random one, with a much wider cone),",
                        "false = the old behaviour (drops the target and holds fire until it can see again).",
                        "",
                        "Panic fire CAN hit its own side. That is intentional: the bullets are real, and the",
                        "cost of being blinded should be real too.")
                .define("panicFire", true);
        GRENADE_FLASH_PANIC_SPREAD_MULTIPLIER = b
                .comment("How much wider the cone is while panic firing (6-10 is 'spraying').")
                .defineInRange("panicSpreadMultiplier", 8.0D, 1.0D, 40.0D);
        GRENADE_FLASH_PANIC_BURST_TICKS = b
                .comment("Ticks between panic shots. Smaller = a faster, more frantic burst (TaCZ's own rate of",
                        "fire still applies, so this cannot make a bolt-action rifle into a machine gun).")
                .defineInRange("panicBurstTicks", 4, 1, 40);
        b.pop();
        b.comment("--- the short-fuse flashbang: through the door, no warning ---").push("flashShort");
        GRENADE_FLASH_SHORT_FUSE_TICKS = b
                .comment("Fuse, in ticks (20 = 1 second). It used to be 8 ticks (0.4 s), which went off in the",
                        "thrower's face before it had travelled anywhere - the user reported exactly that ('the",
                        "short-fuse flashbang's fuse is too short'). 20 ticks is a full second of flight, still",
                        "short enough that it bursts about when it lands and cannot be thrown back.")
                .defineInRange("fuseTicks", 20, 2, 400);
        GRENADE_FLASH_SHORT_BLIND_FACTOR = b
                .comment("Blind duration as a FRACTION of the standard flashbang's (1.0 = identical, 0.5 = half).",
                        "It used to be a fixed 50 ticks = 50 %; the user asked for longer, so it is 0.75 now:",
                        "players are blind 100 * 0.75 = 75 ticks (3.75 s) and mobs 120 * 0.75 = 90 ticks at",
                        "point blank. Because it is a factor, retuning flash.playerBlindTicks moves both.",
                        "",
                        "If the report was really about the WHITE SCREEN being too short rather than the fuse,",
                        "this is the key to raise - not the fuse above.")
                .defineInRange("blindFactor", 0.75D, 0.05D, 1.5D);
        b.pop();
        b.comment("--- the mobs that throw them ---").push("mob");
        MOB_GRENADES_ENABLED = b
                .comment("Whether gun mobs carry and throw grenades at all.")
                .define("enabled", true);
        MOB_GRENADE_CARRY_CHANCE = b
                .comment("Chance per gun mob that it spawns with grenades.")
                .defineInRange("carryChance", 0.35D, 0.0D, 1.0D);
        MOB_GRENADE_MAX_PER_MOB = b
                .comment("How many one mob may carry.")
                .defineInRange("maxPerMob", 2, 1, 8);
        MOB_GRENADE_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two throws by the same mob.")
                .defineInRange("cooldownTicks", 200, 20, 2400);
        MOB_GRENADE_MIN_RANGE = b
                .comment("Never throw at a target closer than this (blocks): inside it a grenade is a way to",
                        "blow yourself up.")
                .defineInRange("minRange", 6.0D, 2.0D, 32.0D);
        MOB_GRENADE_MAX_RANGE = b
                .comment("Never throw at a target further than this (blocks): the throw would fall short.")
                .defineInRange("maxRange", 20.0D, 4.0D, 48.0D);
        MOB_GRENADE_ALLY_SAFETY_RADIUS = b
                .comment("Radius around the predicted landing point that is checked for allies before throwing.")
                .defineInRange("allySafetyRadius", 4.5D, 1.0D, 16.0D);
        MOB_GRENADE_ALLY_SAFETY_MAX = b
                .comment("How many allies may be inside that radius before the mob refuses to throw. 0 = it",
                        "refuses as soon as one ally is in the blast, which is the default: never bomb your own",
                        "side.")
                .defineInRange("allySafetyMax", 0, 0, 8);
        MOB_GRENADE_ARC_SAMPLES = b
                .comment("How many launch pitches the ballistic solver sweeps before a mob throws (README 5v).",
                        "13 pitches over 0..maxLaunchPitchDegrees is 3.75 degrees apart, which is coarse on",
                        "purpose - the landing point moves several blocks per step - so the solver then polishes",
                        "the best pitch with a short coarse-to-fine pass. Raising it costs a few hundred more",
                        "block lookups per solve (13 arcs x ~60 steps is already ~780); lowering it below 5",
                        "makes the first sweep miss the lob over a low wall.")
                .defineInRange("arcSamples", 13, 3, 64);
        MOB_GRENADE_REQUIRE_CLEAR_ARC = b
                .comment("Only throw when the solved arc really lands on the target (default true), or throw the",
                        "best-effort arc even when cover eats it (false). true is the fix for the 2026 report",
                        "that the AI's grenades are very easily eaten by cover: the mob keeps the grenade",
                        "instead of spending it on a wall. false restores the old throw-anyway behaviour, with",
                        "whichever pitch the solver found closest to the target.")
                .define("requireClearArc", true);
        MOB_GRENADE_MAX_LAUNCH_PITCH_DEGREES = b
                .comment("The steepest launch angle the arc solver may use (the sweep runs 0..this). 45 is the",
                        "classic grenade lob; the gate's cases show that at 18 blocks a 1 block wall is cleared",
                        "by a 5 degree lob, while a 2 or 3 block wall cannot be cleared and still land on the",
                        "target at any angle. Raise it to lob over taller cover; lower it to keep every throw",
                        "flat, which means more throws are held back as blocked.")
                .defineInRange("maxLaunchPitchDegrees", 45.0D, 5.0D, 80.0D);
        MOB_GRENADE_RETRY_COOLDOWN_TICKS = b
                .comment("After a held throw - a blocked arc, or an ally at the landing point - the mob waits",
                        "this long before solving again. Solving means simulating a dozen flights and the goal",
                        "selector asks every tick, so without this a mob re-solves 13+ arcs per tick and can",
                        "spam one held-throw log line per tick (that is exactly what the 2026 log showed).")
                .defineInRange("retryCooldownTicks", 40, 1, 600);
        GRENADES_RESUPPLY_ENABLED = b
                .comment("Whether an armed gun mob that is out of grenades walks to a weapon rack that is",
                        "holding one and takes it (README 5v). It only ever takes THROWABLES: a rack holding a",
                        "gun is left alone, because an armed unit is already armed.")
                .define("resupplyEnabled", true);
        GRENADES_RESUPPLY_RADIUS = b
                .comment("How far a mob will walk for a grenade, in blocks.",
                        "This is also the radius of the rack SEARCH, so it used to decide how much work an",
                        "idle armed mob did every tick (the search walks the chunks in this box and reads",
                        "each loaded chunk's block-entity map, so the cost is now proportional to the block",
                        "entities that exist rather than to the volume). 24 is what the mob needs to notice",
                        "a rack across a street; 8 keeps it to the building it is standing in. Lower it if",
                        "you run very many armed units at once and do not care about grenade resupply - a",
                        "mob will then walk past a rack it would previously have used.")
                .defineInRange("resupplyRadius", 24.0D, 4.0D, 96.0D);
        GRENADES_RESUPPLY_COOLDOWN_TICKS = b
                .comment("After taking one, the mob waits this long before going back for another, so a single",
                        "rack does not turn into a conga line.")
                .defineInRange("resupplyCooldownTicks", 200, 20, 2400);
        GRENADES_RESUPPLY_SEARCH_COOLDOWN_TICKS = b
                .comment("Minimum ticks between two rack searches for a mob that is out of grenades and has",
                        "nothing to shoot at. The goal selector polls canUse() every tick, and the search",
                        "was the single most expensive thing an armed unit did: 40,817 block-entity lookups",
                        "per tick per idle mob at the default radius, measured at ~1.25 ms of server tick",
                        "each (a crowd of 50 armed villagers alone exceeds the 50 ms tick budget). 40 is",
                        "invisible to the player - the rack is not going anywhere - and 1 restores the old",
                        "search-every-tick behaviour.")
                .defineInRange("resupplySearchCooldownTicks", 40, 1, 1200);
        GRENADES_RACK_PRIORITY = b
                .comment("Which rack a resupplying mob prefers when several are in range:",
                        "  grenade - a rack holding a throwable (the default, and what the mob came for);",
                        "  nearest - simply the closest one;",
                        "  weapon  - a rack holding a gun (it will walk there and then find nothing to take,",
                        "            which is only useful for testing the refusal).",
                        "",
                        "A rack holds ONE item, so 'a rack with a gun and a grenade' cannot exist: this setting",
                        "is about choosing between several racks, not about a rack holding both.")
                .define("rackPriority", "grenade");
        b.pop();
        b.pop();

        // ================================================================= client / head accessories
        b.comment("Head accessories of the imported Bedrock rig. These keys are INCREMENTAL to",
                "hiddenBones: both apply, and a bone is hidden when either mechanism asks for it.",
                "",
                "Why separate keys and not just hiddenBones: the older rig wore three hats at once,",
                "two pairs of eye gear and a cigarette, and they were stacked in the same few blocks",
                "of space, so they z-fought with each other and with the head mesh. Switching them",
                "needs a mode ('keep exactly one') that a plain list cannot express.",
                "",
                "AFTER THE 2026 RIG RE-EXPORT this rig has exactly ONE head accessory bone: Hat2.",
                "Glass, YanJing, Hat1, hat3 and Yan were deleted from the model, so the defaults below",
                "keep Hat2 and switch the eye gear off; the names that are left in the lists are the",
                "vocabulary of those switches, and a name the rig does not have is reported ONCE (per",
                "rig and bone, then summarised) - never silently, and never per frame.",
                "",
                "The bone names below were measured from geo/scav.geo.json (see",
                "tools/classify_head_parts.js, tools/selftest_rig_bones.js and the README section on",
                "the head accessories).").push("headAccessories");
        HAT_ACCESSORY = b
                .comment("What to do with the hats: keepOne | hideAll | showAll.",
                        "  keepOne (default) - hide every hat except keptHatBone",
                        "  hideAll           - hide all of them",
                        "  showAll           - hide none of them (the way the author rigged it)",
                        "The CURRENT rig has one hat (Hat2), so keepOne and showAll are the same thing",
                        "here; the older rig had three (Hat1, Hat2, hat3) all worn at once, which is",
                        "what the original report meant by 'too many hats'.")
                .define("hatAccessory", "keepOne");
        HAT_BONES = b
                .comment("The bones that count as hats. Only meaningful for keepOne and hideAll.",
                        "Default is the measured set of the older rig: Hat1, Hat2 and hat3. The current",
                        "rig only has Hat2 - the other two are reported once as 'not in this rig' and",
                        "then ignored, so an old .toml keeps working without spamming the log.")
                .defineListAllowEmpty(List.of("hatBones"), Config::defaultHatBones,
                        element -> element instanceof String);
        KEPT_HAT_BONE = b
                .comment("Which hat survives when hatAccessory = keepOne. Default Hat2:",
                        "  * it is the only one that covers the whole skull (crown at y 36..41, i.e.",
                        "    over the head mesh whose top is 39.55) AND comes down over the ears",
                        "    (to y 32.5) - so hiding the other two leaves no bare patch;",
                        "  * its faces sample one compact 45x40 block of the atlas, i.e. it is a",
                        "    dedicated garment, while Hat1's 42 faces are scattered over the whole",
                        "    atlas and hat3 stacks four slabs plus a knob up to y 45.4.",
                        "Name any bone you like instead; a name that is not in the rig gets a WARN.")
                .define("keptHatBone", "Hat2");
        EYE_GEAR_ACCESSORY = b
                .comment("Which eye gear is worn: a bone name from eyeGearBones, or 'none'.",
                        "Default NONE since the 2026 rig re-export: the user deleted both eye-gear",
                        "bones (Glass, the 4 near-black cubes that stopped at the head silhouette, and",
                        "YanJing, whose side frames sat in exactly the same box as Hat2's ear flaps).",
                        "Set a name here only if your rig has that bone; the names themselves stay in",
                        "eyeGearBones as the vocabulary, and a name the rig lacks is reported once.",
                        "Note: this is NOT the 'Eyes' bone. Eyes owns the eyebrows and the eyeballs -",
                        "the face itself - and stays protected.")
                .define("eyeGearAccessory", "none");
        EYE_GEAR_BONES = b
                .comment("The bones that count as eye gear. Default Glass and YanJing - the two the",
                        "older rig had; the current rig has neither, which is why eyeGearAccessory",
                        "defaults to none. Add names here for a rig that does have eye gear.")
                .defineListAllowEmpty(List.of("eyeGearBones"), Config::defaultEyeGearBones,
                        element -> element instanceof String);
        CIGARETTE = b
                .comment("Whether the cigarette in the rig's mouth is drawn. Default false: it is a",
                        "0.5 x 0.5 x 2.5 unit stick at the mouth that reads as clutter at the",
                        "distance these mobs are normally fought, and it is the same kind of author",
                        "prop as the rest of the default hiddenBones. Set true for the author's look.")
                .define("cigarette", false);
        CIGARETTE_BONE = b
                .comment("The bone that carries the cigarette. Default Yan (the author's bone name) -",
                        "note that the 2026 re-export deleted Yan, so this name is reported once as",
                        "'not in this rig' and then ignored. It stays as the default because the",
                        "cigarette switch above is off anyway, and because putting it back is how you",
                        "would re-enable a future rig's cigarette.")
                .define("cigaretteBone", "Yan");
        EXTRA_HIDDEN_BONES = b
                .comment("Extra bones to hide, on top of hiddenBones and the accessory switches.",
                        "",
                        "This exists for diagnosis: when something on the model looks wrong, hide one",
                        "candidate at a time with /tarkovscav client hide <bone> and see when it goes",
                        "away. It is a bisect tool, not a fix - nothing is hidden here by default.")
                .defineListAllowEmpty(List.of("extraHiddenBones"), List::of, element -> element instanceof String);
        HEAD_REST_PITCH_DEGREES = b
                .comment("Rest pitch added to the head bone, in degrees. POSITIVE = the head looks UP",
                        "(GeckoLib applies the bone's X rotation about +X with the rig's face on -Z,",
                        "which raises the face; this is the same sign as the headPitch GeckoLib's own",
                        "DefaultedEntityGeoModel writes), negative = looks down.",
                        "",
                        "Default 20: with the head at rest the imported rig sits about 20 degrees",
                        "lower than it should (the author's own head keyframes are YSM Molang that",
                        "GeckoLib evaluates to 0 - see tools/scan_head_keyframes.js), and the aiming",
                        "path is unaffected because it is driven by the look direction.",
                        "",
                        "Applied ONLY to the states listed in headRestPitchStates, and never to the",
                        "aiming path: the write is absolute (rest + share + this), which is what keeps",
                        "it idempotent - see RigSupport.applyAimTracking.")
                .defineInRange("headRestPitchDegrees", 20.0D, -90.0D, 90.0D);
        HEAD_REST_PITCH_STATES = b
                .comment("Which gun-AI states the head rest pitch applies to. Default [idle].",
                        "Values are GunAiState names: idle, alert, advance, aim, fire, suppress,",
                        "reload, bolt, reposition, retreat; 'any' means every state.",
                        "Keep this at idle: the report was that the head only looks wrong while the",
                        "mob is idle - as soon as it is alerted or shot at, the head follows the",
                        "target and is correct, and the aiming path must stay untouched.")
                .defineListAllowEmpty(List.of("headRestPitchStates"), () -> List.of("idle"),
                        element -> element instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }

    public static TierSettings tier(ScavTier tier) {
        return TIERS.get(tier);
    }

    /** The intelligence-tier knobs (README 5aa). Never null: every tier is built in the static block. */
    public static AiSettings ai(AiProfile.Tier tier) {
        return AI_TIERS.get(tier);
    }

    /**
     * One intelligence tier's knobs (README 5aa). Every {@code *Scale} MULTIPLIES the matching global
     * key, so a user who already tuned {@code [tactics]} keeps their numbers as the baseline:
     *
     * <pre>
     *   suppressChance        = tactics.suppressChance            x suppressChanceScale  (clamped 0..1)
     *   suppressTicks         = tactics.suppressTicks             x suppressTicksScale
     *   suppressAccuracy      = tactics.suppressAccuracyMultiplier x suppressAccuracyScale
     *   suppressBurst         = tactics.suppressBurstMultiplier   x suppressBurstScale
     *   advanceCoverStep      = tactics.advanceCoverStep          x advanceCoverScale (0 = no cover at all)
     *   coverSeekSpeed        = tactics.coverSeekSpeedModifier    x coverSeekSpeedScale
     *   coverSearchRadius     = tactics.coverSearchRadius         x coverRadiusScale
     *   coverCacheTicks       = tactics.coverCacheTicks           x coverCacheScale
     *   repositionTicks       = combat.repositionTicks            x repositionScale
     *   retreatHealthFraction = combat.retreatHealthFraction      x retreatHealthScale
     *   accuracy              = tiers.&lt;gun tier&gt;.accuracy       x accuracyScale
     *   engageRange           = tiers.&lt;gun tier&gt;.engageRange    x engageRangeScale
     * </pre>
     *
     * <p>{@code reactionMinTicks}/{@code reactionMaxTicks}, {@code holdPost}, {@code minHitChance},
     * {@code patienceTicks} and {@code coordination} are ABSOLUTE - there is no global key for them.
     * While {@code [ai] enabled} is false none of this is read: {@code AiProfile} answers with the raw
     * global value, which is the pre-feature behaviour.</p>
     *
     * <p>README 5ab's six keys are a third kind - ABSOLUTE values with an explicit "no override"
     * sentinel, so a tier can either name its own number or inherit the global rule exactly:
     * {@code exposedBurstShots} (0 = the gun tier's rule), {@code exposedBurstCooldownTicks}
     * (-1 = the gun tier's pause), {@code warmupShotsWhenExposed} (-1 = the global warm-up),
     * {@code retreatHealthFraction} (-1 = the scaled global), {@code hurtRetreatChance}
     * (-1 = the global chance) and {@code retreatHoldTicks} (0 = no hold). Every sentinel is also the
     * SCAV value, which is what makes the documented "copy [ai.scav] over the other three blocks"
     * recipe reproduce the pre-5ab behaviour for these keys as well.</p>
     *
     * <p>{@code tactics.retreatSprint} is deliberately NOT scaled: the user tuned sprinting off
     * globally ("running away = normal 1x") and no tier may quietly turn it back on. It stays the one
     * source of truth.</p>
     */
    public static final class AiSettings {
        public final ForgeConfigSpec.IntValue reactionMinTicks;
        public final ForgeConfigSpec.IntValue reactionMaxTicks;
        public final ForgeConfigSpec.DoubleValue accuracyScale;
        public final ForgeConfigSpec.DoubleValue coverChance;
        public final ForgeConfigSpec.DoubleValue coverRadiusScale;
        public final ForgeConfigSpec.DoubleValue coverCacheScale;
        public final ForgeConfigSpec.DoubleValue suppressChanceScale;
        public final ForgeConfigSpec.DoubleValue suppressTicksScale;
        public final ForgeConfigSpec.DoubleValue suppressAccuracyScale;
        public final ForgeConfigSpec.DoubleValue suppressBurstScale;
        public final ForgeConfigSpec.DoubleValue advanceCoverScale;
        public final ForgeConfigSpec.DoubleValue coverSeekSpeedScale;
        public final ForgeConfigSpec.DoubleValue repositionScale;
        public final ForgeConfigSpec.DoubleValue retreatHealthScale;
        public final ForgeConfigSpec.DoubleValue engageRangeScale;
        public final ForgeConfigSpec.BooleanValue holdPost;
        public final ForgeConfigSpec.DoubleValue minHitChance;
        public final ForgeConfigSpec.IntValue patienceTicks;
        public final ForgeConfigSpec.BooleanValue coordination;
        public final ForgeConfigSpec.DoubleValue partialCoverBonus;
        /** README 5ab: the exposed-target fire and hurt-reaction keys. */
        public final ForgeConfigSpec.IntValue exposedBurstShots;
        public final ForgeConfigSpec.IntValue exposedBurstCooldownTicks;
        public final ForgeConfigSpec.IntValue warmupShotsWhenExposed;
        public final ForgeConfigSpec.DoubleValue retreatHealthFraction;
        public final ForgeConfigSpec.DoubleValue hurtRetreatChance;
        public final ForgeConfigSpec.IntValue retreatHoldTicks;

        private AiSettings(ForgeConfigSpec.Builder b, AiProfile.Tier tier) {
            b.comment("--- " + tier.id() + " tier ---").push(tier.id());
            this.reactionMinTicks = b
                    .comment("Reaction time lower bound, in ticks (20 = 1 s). ABSOLUTE: while [ai]",
                            "enabled is true this replaces [combat] reactionTicks. The mob rolls a fresh",
                            "delay between min and max every time it acquires a target, so a squad does",
                            "not open fire on one tick. Scav 12..24 is the requested 0.6..1.2 s.")
                    .defineInRange("reactionMinTicks", switch (tier) {
                        case SCAV -> 12;
                        case SNIPER -> 16;
                        case TROOP -> 6;
                        case ELITE -> 4;
                    }, 0, 200);
            this.reactionMaxTicks = b
                    .comment("Reaction time upper bound, in ticks. Set it equal to reactionMinTicks to",
                            "remove the jitter. Scav 24 = 1.2 s (the slow end the user asked for); sniper",
                            "30 is 'slow but steady'; troop 10 and elite 8 are 'fast'.")
                    .defineInRange("reactionMaxTicks", switch (tier) {
                        case SCAV -> 24;
                        case SNIPER -> 30;
                        case TROOP -> 10;
                        case ELITE -> 8;
                    }, 0, 400);
            this.accuracyScale = b
                    .comment("Multiplies the gun tier's accuracy before the accuracy profile clamps the",
                            "hit chance (README 5o). Scav 0.80 makes the dumb tier worse than its own",
                            "0.75 rookie cap; 1.0 leaves troop/sniper/troop alone; elite 1.05 only",
                            "matters because its own cap is 0.90, i.e. the highest of the three.")
                    .defineInRange("accuracyScale", switch (tier) {
                        case SCAV -> 0.80D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.0D;
                        case ELITE -> 1.05D;
                    }, 0.0D, 2.0D);
            this.coverChance = b
                    .comment("0..1. Chance that a cover decision (advancing, repositioning) actually",
                            "looks for cover. Scav 0.15 = 'rarely uses cover'. Reloading and retreating",
                            "still use cover for every tier: that is survival, not tactics.")
                    .defineInRange("coverChance", switch (tier) {
                        case SCAV -> 0.15D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 0.90D;
                        case ELITE -> 0.75D;
                    }, 0.0D, 1.0D);
            this.coverRadiusScale = b
                    .comment("Multiplies tactics.coverSearchRadius. Scav 0.45 = a small radius (it only",
                            "notices the wall right next to it); elite 0.85 because it crosses the gap in",
                            "short rushes rather than hunting for the perfect spot.")
                    .defineInRange("coverRadiusScale", switch (tier) {
                        case SCAV -> 0.45D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.0D;
                        case ELITE -> 0.85D;
                    }, 0.1D, 3.0D);
            this.coverCacheScale = b
                    .comment("Multiplies tactics.coverCacheTicks. Scav 1.5 reuses a stale list longer (it",
                            "is slow to re-plan); elite 0.75 re-plans sooner (it is decisive).")
                    .defineInRange("coverCacheScale", switch (tier) {
                        case SCAV -> 1.5D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.0D;
                        case ELITE -> 0.75D;
                    }, 0.1D, 5.0D);
            this.suppressChanceScale = b
                    .comment("Multiplies tactics.suppressChance, clamped 0..1. Scav and sniper 0.0 = no",
                            "suppression at all (a scav only has short bursts; a sniper waits). Troop 1.6",
                            "with the default 0.6 gives 0.96 = 'strong suppression'; elite 1.0 keeps the",
                            "baseline 0.6 = medium.")
                    .defineInRange("suppressChanceScale", switch (tier) {
                        case SCAV -> 0.0D;
                        case SNIPER -> 0.0D;
                        case TROOP -> 1.6D;
                        case ELITE -> 1.0D;
                    }, 0.0D, 3.0D);
            this.suppressTicksScale = b
                    .comment("Multiplies tactics.suppressTicks. Troop 1.5 x 60 = 90 ticks = a long",
                            "suppression; scav/sniper 0.0 = none; elite 1.0 = the baseline 60.")
                    .defineInRange("suppressTicksScale", switch (tier) {
                        case SCAV -> 0.0D;
                        case SNIPER -> 0.0D;
                        case TROOP -> 1.5D;
                        case ELITE -> 1.0D;
                    }, 0.0D, 5.0D);
            this.suppressAccuracyScale = b
                    .comment("Multiplies tactics.suppressAccuracyMultiplier (blind fire is inaccurate",
                            "on purpose). Troop 0.8 x 0.45 = 0.36: its suppression is more accurate than",
                            "the baseline, which is what makes it dangerous. Others keep 1.0.")
                    .defineInRange("suppressAccuracyScale", switch (tier) {
                        case SCAV -> 1.0D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 0.80D;
                        case ELITE -> 1.0D;
                    }, 0.05D, 2.0D);
            this.suppressBurstScale = b
                    .comment("Multiplies tactics.suppressBurstMultiplier. Scav 0.5 = short bursts only.",
                            "Troop 1.2 = a longer belt while it keeps the target pinned.")
                    .defineInRange("suppressBurstScale", switch (tier) {
                        case SCAV -> 0.5D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.2D;
                        case ELITE -> 1.0D;
                    }, 0.1D, 5.0D);
            this.advanceCoverScale = b
                    .comment("Multiplies tactics.advanceCoverStep (the minimum gain a cover spot must",
                            "give while advancing). 0.0 = this tier does not use cover to advance at",
                            "all: the scav walks STRAIGHT at the target. Troop 0.6 x 3 = 1.8 = small",
                            "steps, cover to cover. Elite 1.5 x 3 = 4.5 = long steps / rushes.")
                    .defineInRange("advanceCoverScale", switch (tier) {
                        case SCAV -> 0.0D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 0.6D;
                        case ELITE -> 1.5D;
                    }, 0.0D, 5.0D);
            this.coverSeekSpeedScale = b
                    .comment("Multiplies tactics.coverSeekSpeedModifier. Elite 1.15 makes its rushes",
                            "slightly quicker than a walk; everyone else keeps the user's 1.0. Advancing",
                            "on a straight line (1.15 in GunBrain) is deliberately untouched.")
                    .defineInRange("coverSeekSpeedScale", switch (tier) {
                        case SCAV -> 1.0D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.0D;
                        case ELITE -> 1.15D;
                    }, 0.5D, 3.0D);
            this.repositionScale = b
                    .comment("Multiplies combat.repositionTicks (how long a mob spends moving to a new",
                            "firing position). Troop 0.6 and elite 0.5 = they do not linger in the open.")
                    .defineInRange("repositionScale", switch (tier) {
                        case SCAV -> 1.0D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 0.6D;
                        case ELITE -> 0.5D;
                    }, 0.1D, 3.0D);
            this.retreatHealthScale = b
                    .comment("Multiplies combat.retreatHealthFraction (below it a mob breaks contact).",
                            "Sniper 0.6 and elite 0.7 = they hold the post / press the rush longer than",
                            "the baseline; a scav keeps 1.0, i.e. it runs early.")
                    .defineInRange("retreatHealthScale", switch (tier) {
                        case SCAV -> 1.0D;
                        case SNIPER -> 0.6D;
                        case TROOP -> 1.0D;
                        case ELITE -> 0.7D;
                    }, 0.1D, 2.0D);
            this.engageRangeScale = b
                    .comment("Multiplies the gun tier's engageRange. Elite 0.6 = it closes to about",
                            "60 % of its weapon's range before shooting (short rushes, close",
                            "engagement). Everyone else fights at the tier's own range.")
                    .defineInRange("engageRangeScale", switch (tier) {
                        case SCAV -> 1.0D;
                        case SNIPER -> 1.0D;
                        case TROOP -> 1.0D;
                        case ELITE -> 0.6D;
                    }, 0.2D, 2.0D);
            this.holdPost = b
                    .comment("true = this tier holds its firing position instead of advancing when the",
                            "target is out of range (see SniperBehavior/SniperPost). Only the sniper",
                            "tier ships true; it is the 'does not advance toward the target' rule.")
                    .define("holdPost", tier == AiProfile.Tier.SNIPER);
            this.minHitChance = b
                    .comment("0..1, 0 = off. Only start a burst when the ESTIMATED hit chance is at",
                            "least this high. The estimate is AccuracyProfile.hitChanceFor on the same",
                            "error-cone model the shot uses - and deliberately the STEADY-STATE cone,",
                            "not the warm-up one: the warm-up penalty (README 5o) only clears by",
                            "firing, so comparing the floor against it would deadlock the rule.",
                            "",
                            "WHY 0.5 FOR THE SNIPER, measured with a 0.3-radius target on the veteran",
                            "0.85 cone: the steady estimate is 85 % at 20 blocks (capped), 72 % at 30,",
                            "59 % at 40 and 47 % at 52 (the sniper tier's own range). So 0.50 refuses",
                            "only the extreme-range shot beyond about 48 blocks - and even then the",
                            "mob repositions for up to patienceTicks x 3 and then takes the shot, so a",
                            "sniper is never left permanently harmless. Lower it (0.35) for 'always",
                            "shoot', raise it (0.6) for a sniper that only takes clean close shots.")
                    .defineInRange("minHitChance", tier == AiProfile.Tier.SNIPER ? 0.5D : 0.0D, 0.0D, 1.0D);
            this.patienceTicks = b
                    .comment("How long a mob may stay in AIM waiting for minHitChance before it gives",
                            "up on this position and re-decides, in ticks. This is the anti-stall bound",
                            "for the hold-fire rule: without it a sniper whose target is behind a",
                            "wall of range would aim for ever. 0 = no bound (only safe when",
                            "minHitChance is 0). Sniper 100 = 5 s.")
                    .defineInRange("patienceTicks", tier == AiProfile.Tier.SNIPER ? 100 : 0, 0, 600);
            this.coordination = b
                    .comment("true = this tier takes part in the squad layer (focus fire, overwatch,",
                            "flanking, cover claims). Troop and elite ship true; scav and sniper",
                            "false - a sniper works alone and a scav cannot cooperate.")
                    .define("coordination", tier == AiProfile.Tier.TROOP || tier == AiProfile.Tier.ELITE);
            this.partialCoverBonus = b
                    .comment("Extra score for a sniper post that is only PARTIALLY concealed (one of",
                            "eye/feet has line of sight blocked but not both) or that sits at least",
                            "one block above the target. The sniper already strongly prefers a fully",
                            "unseen post (the hard-coded 1000 in SniperBehavior.findPost); this is the",
                            "second-best band, so a rooftop with a firing slit beats an open street.",
                            "0.0 = only fully concealed posts get the bonus (the pre-5aa behaviour).")
                    .defineInRange("partialCoverBonus", 250.0D, 0.0D, 1000.0D);
            this.exposedBurstShots = b
                    .comment("README 5ab, the lethal-fire rule. How many shots a burst fires while the",
                            "target counts as EXPOSED, which means exactly: this mob can trace an",
                            "unobstructed line from its eyes to the target's eyes AND to the target's",
                            "feet, and the target is inside this mob's effective range (engageRange).",
                            "The test is CombatTactics#canSeeEyes/#canSeeFeet plus this mob's range,",
                            "combined by AiProfile#isExposed - the truth table the gate prints is",
                            "(eyes AND feet AND in range).",
                            "",
                            "  -1 = fire until the magazine is empty or the target dies",
                            "   0 = NO override: the gun tier's [tiers.<gun>] burstShots decides (this is",
                            "       the pre-5ab behaviour, and it is what keeps SCAV dumb)",
                            "  >0 = exactly that many shots",
                            "",
                            "WHY THIS EXISTS, measured on the shipped defaults: a [tiers.rifle] mob",
                            "fires burstShots = 6, then waits burstCooldownTicks = 20 (1 s) and then",
                            "spends repositionTicks 40 x repositionScale = 24 ticks (troop 0.6) walking",
                            "to a new spot before it may shoot again - so of every ~3.2 s only ~1.2 s is",
                            "spent firing, and the first 8 shots of the engagement are additionally",
                            "multiplied by accuracy.warmupMultiplier 0.45 (accuracy.warmupShots).",
                            "Against a player standing in the open that is a burst that cannot kill.",
                            "Troop/elite therefore ship -1 here and 0 for exposedBurstCooldownTicks, so",
                            "the trigger stays down while the target stays exposed and in range.",
                            "",
                            "Every other tier keeps its character: SCAV ships 0 (its gun tier decides,",
                            "short bursts only), SNIPER ships 1 (it never sprays - and because its",
                            "exposedBurstCooldownTicks stays -1 the single shot is still followed by",
                            "the gun tier's pause and a reposition, i.e. the hold-post rhythm).",
                            "",
                            "WHO WINS between this and [tiers.<gun>] burstShots: while the target is",
                            "exposed AND this key is not 0, THIS key wins; at every other moment the",
                            "gun tier's burstShots wins. Suppression still multiplies whatever came out",
                            "of that choice by tactics.suppressBurstMultiplier x suppressBurstScale,",
                            "clamped to the magazine. Both are resolved by GunBrain#burstSize and",
                            "asserted by tools/selftest_ai_fire.js.")
                    .defineInRange("exposedBurstShots", switch (tier) {
                        case SCAV -> 0;
                        case SNIPER -> 1;
                        case TROOP -> -1;
                        case ELITE -> -1;
                    }, -1, 200);
            this.exposedBurstCooldownTicks = b
                    .comment("README 5ab. The pause AFTER a burst, while the target is exposed and in",
                            "range, in ticks. -1 = no override (the gun tier's burstCooldownTicks is",
                            "used), 0 = no pause at all (go straight back into AIM for the next burst).",
                            "",
                            "This key is also the second half of the trigger: the exposed rule only",
                            "takes over the burst-ending decision when exposedBurstShots != 0 AND this",
                            "key is >= 0 (AiProfile#exposedRuleApplies). So SCAV (-1) and SNIPER (-1)",
                            "never change the post-burst behaviour, and TROOP/ELITE (0) keep firing.",
                            "",
                            "WHY 0 for troop/elite: the 20-tick rifle pause plus the 24-tick troop",
                            "reposition is the 'they only fire short bursts' the user reported. 0 skips",
                            "both - the mag dump - and the FIRE watchdog (fireStallTicks 100) plus the",
                            "RELOAD state still bound the state machine, so this cannot become a stall.",
                            "A user who wants a visible short break can raise it (e.g. 5 = a quarter",
                            "second), and a mob whose magazine runs dry goes to RELOAD, never to AIM.")
                    .defineInRange("exposedBurstCooldownTicks", switch (tier) {
                        case SCAV -> -1;
                        case SNIPER -> -1;
                        case TROOP -> 0;
                        case ELITE -> 0;
                    }, -1, 400);
            this.warmupShotsWhenExposed = b
                    .comment("README 5ab. The accuracy.warmupShots value that applies while the target is",
                            "exposed. -1 = keep the global accuracy.warmupShots (8), 0 = no wild shots at",
                            "all against an exposed target, N = the first N shots are still wild.",
                            "",
                            "WHY troop/elite ship 0: with warmupShots 8 x warmupMultiplier 0.45 the",
                            "whole first burst (rifle 6 shots) is fired at 45 percent of the tier's",
                            "accuracy, which is why 'the first burst of an engagement largely misses'.",
                            "An enemy standing in the open is not a hard shot, so the smart tiers get",
                            "their steady cone from the first round; the profile cap (rookie 0.75 /",
                            "veteran 0.85 / elite 0.90) and hardCeiling 0.95 still apply unchanged.",
                            "SCAV and SNIPER keep -1: the dumb tier stays wild, and the sniper's first",
                            "cold shot stays a cold shot (it fires one round at a time, so its 'first",
                            "burst' IS its first shot).",
                            "",
                            "This is read through AccuracyProfile#warmupShots, which asks the brain for",
                            "the current tick's exposure verdict (GunUser#targetExposedNow).")
                    .defineInRange("warmupShotsWhenExposed", switch (tier) {
                        case SCAV -> -1;
                        case SNIPER -> -1;
                        case TROOP -> 0;
                        case ELITE -> 0;
                    }, -1, 200);
            this.retreatHealthFraction = b
                    .comment("README 5ab. An ABSOLUTE health fraction (of max health) below which this",
                            "tier breaks contact, overriding the scaled global value. -1 = no override:",
                            "the existing combat.retreatHealthFraction x retreatHealthScale applies.",
                            "",
                            "WHY troop 0.5 and elite 0.55: the user's complaint was 'when they",
                            "themselves are being hit and beaten down they do not duck into cover'. The",
                            "old numbers were the opposite of that - troop 1.0 x 0.35 = 0.35 (the",
                            "baseline) and elite 0.7 x 0.35 = 0.245, i.e. the elite pressed the rush",
                            "until it was all but dead. Half health is the trade-losing point for a",
                            "26-health rifleman (13 hp) and 0.55 for the elite pair, and because the",
                            "absolute value also bypasses retreatHealthScale it cannot be re-scaled by",
                            "accident. SCAV and SNIPER ship -1: the scav keeps the low global 0.35",
                            "(dumb, and it runs early), the sniper keeps 0.6 x 0.35 = 0.21 (it holds",
                            "its post). Set 0.0 to make a tier never retreat on health alone.",
                            "",
                            "The hurt-triggered half of the same reaction is hurtRetreatChance below,",
                            "and once a tier with retreatHoldTicks > 0 has broken contact it stays in",
                            "cover for that long and re-engages by peeking.")
                    .defineInRange("retreatHealthFraction", switch (tier) {
                        case SCAV -> -1.0D;
                        case SNIPER -> -1.0D;
                        case TROOP -> 0.5D;
                        case ELITE -> 0.55D;
                    }, -1.0D, 1.0D);
            this.hurtRetreatChance = b
                    .comment("README 5ab. The chance that ONE hit sends this tier into RETREAT, overriding",
                            "combat.hurtRetreatChance. -1 = no override (use the global key).",
                            "",
                            "WHY troop 0.8 / elite 0.85 against a global of 0.5: a mob that only flinches",
                            "half the time eats a whole second burst while deciding. The smart tiers are",
                            "supposed to survive the first contact and come back, so they break contact",
                            "on 4 hits in 5 (troop) / 17 in 20 (elite) - and since being hit while",
                            "ALREADY retreating no longer rolls at all (it only re-arms the hold), the",
                            "reaction cannot bounce them back into the open: GunBrain#onHurt returns",
                            "early when the state is RETREAT.",
                            "",
                            "SCAV and SNIPER keep -1: the scav stays at the global 0.5 (dumb), and the",
                            "sniper keeps the global value too because its reaction is the retreatPoint",
                            "move in SniperBehavior, not this one.")
                    .defineInRange("hurtRetreatChance", switch (tier) {
                        case SCAV -> -1.0D;
                        case SNIPER -> -1.0D;
                        case TROOP -> 0.8D;
                        case ELITE -> 0.85D;
                    }, -1.0D, 1.0D);
            this.retreatHoldTicks = b
                    .comment("README 5ab. Once this tier has broken contact and reached cover it stays",
                            "there for at least this many ticks before it may re-engage, in ticks",
                            "(20 = 1 s). 0 = off: the pre-5ab rule (re-evaluate after 40 ticks in cover",
                            "and out of contact) applies unchanged.",
                            "",
                            "WHY 80 for troop / 60 for elite: combat.giveUpTicks is 160 (8 s), so a",
                            "hold of 60-80 ticks is a real break without eating the whole retreat",
                            "budget. Being hit while retreating re-arms the clock (GunBrain#onHurt), so",
                            "a mob under sustained fire keeps its head down instead of popping back up.",
                            "Three things keep the hold from becoming a stall: the no-progress watchdog",
                            "is stood down while the mob is deliberately holding IN cover",
                            "(GunBrain#retreatHolding), the mob cannot re-engage before the clock runs",
                            "out, and giveUpTicks still ends the whole RETREAT state.",
                            "",
                            "Re-engaging goes through the existing peek machinery: after the hold, a",
                            "tier with this key > 0 transitions to REPOSITION, which is the move that",
                            "uses CombatTactics#peekSpot to step out from behind the cover it is behind",
                            "(and then to AIM) - instead of walking back into the open. SCAV ships 0,",
                            "so the dumb tier keeps the old 're-evaluate after 40 ticks' behaviour.",
                            "",
                            "Set this and retreatHealthFraction/hurtRetreatChance to their sentinels",
                            "(0 / -1 / -1) to get the exact pre-5ab survival behaviour back.")
                    .defineInRange("retreatHoldTicks", switch (tier) {
                        case SCAV -> 0;
                        case SNIPER -> 60;
                        case TROOP -> 80;
                        case ELITE -> 60;
                    }, 0, 600);
            b.pop();
        }
    }

    /** Per-tier numbers. One instance per {@link ScavTier}, all living in the {@code tiers} section. */
    public static final class TierSettings {
        public final ForgeConfigSpec.IntValue spawnWeight;
        public final ForgeConfigSpec.DoubleValue health;
        public final ForgeConfigSpec.DoubleValue armor;
        public final ForgeConfigSpec.DoubleValue accuracy;
        public final ForgeConfigSpec.IntValue aimTicks;
        public final ForgeConfigSpec.IntValue burstShots;
        public final ForgeConfigSpec.IntValue burstCooldownTicks;

        private TierSettings(ForgeConfigSpec.Builder b, ScavTier tier) {
            String name = tier.id();
            b.comment("--- " + name + " ---").push(name);
            this.spawnWeight = b
                    .comment("Relative chance of this tier being picked when a mob spawns (0 disables it).")
                    .defineInRange("spawnWeight", switch (tier) {
                        case PISTOL -> 40;
                        case SHOTGUN -> 25;
                        case RIFLE -> 30;
                        case SNIPER -> 8;
                    }, 0, 1000);
            this.health = b
                    .comment("Max health of this tier.")
                    .defineInRange("health", switch (tier) {
                        case PISTOL -> 20.0D;
                        case SHOTGUN -> 24.0D;
                        case RIFLE -> 26.0D;
                        case SNIPER -> 22.0D;
                    }, 1.0D, 1024.0D);
            this.armor = b
                    .comment("Armour points of this tier (diamond chestplate is 8).")
                    .defineInRange("armor", switch (tier) {
                        case PISTOL -> 0.0D;
                        case SHOTGUN -> 2.0D;
                        case RIFLE -> 4.0D;
                        case SNIPER -> 1.0D;
                    }, 0.0D, 30.0D);
            this.accuracy = b
                    .comment("0..1. How much of the aim error is removed. 1.0 never misses.")
                    .defineInRange("accuracy", switch (tier) {
                        case PISTOL -> 0.45D;
                        case SHOTGUN -> 0.55D;
                        case RIFLE -> 0.6D;
                        case SNIPER -> 0.85D;
                    }, 0.0D, 1.0D);
            this.aimTicks = b
                    .comment("Extra ticks of aiming before the first shot of a burst, on top of TaCZ's",
                            "own per-gun aim time. This is the other half of the fairness window.")
                    .defineInRange("aimTicks", switch (tier) {
                        case PISTOL -> 6;
                        case SHOTGUN -> 8;
                        case RIFLE -> 12;
                        case SNIPER -> 26;
                    }, 0, 400);
            this.burstShots = b
                    .comment("Shots per burst. -1 means 'fire until the magazine is empty or the target",
                            "breaks line of sight' (what a machine gun should do).")
                    .defineInRange("burstShots", switch (tier) {
                        case PISTOL -> 3;
                        case SHOTGUN -> 2;
                        case RIFLE -> 6;
                        case SNIPER -> 1;
                    }, -1, 200);
            this.burstCooldownTicks = b
                    .comment("Pause between bursts, in ticks.")
                    .defineInRange("burstCooldownTicks", switch (tier) {
                        case PISTOL -> 16;
                        case SHOTGUN -> 30;
                        case RIFLE -> 20;
                        case SNIPER -> 45;
                    }, 0, 400);
            b.pop();
        }
    }

    // ------------------------------------------------------------------ defaults

    private static List<? extends String> defaultCityStructureIds() {
        return List.of("tarkovscav:city_small", "tarkovscav:city_a", "tarkovscav:city_b",
                "tarkovscav:city_c", "tarkovscav:city_strongpoint");
    }

    private static List<? extends String> defaultCityStructureTags() {
        return List.of("tarkovscav:city");
    }

    private static List<? extends String> defaultGunBlacklist() {
        // The default TaCZ pack ships tacz:rpg7 and tacz:m320 (both 'rpg', already excluded by type)
        // and tacz:minigun (an 'mg', so type filtering alone would let it through).
        return List.of("tacz:rpg7", "tacz:m320", "tacz:minigun");
    }

    /**
     * The author's reference props in the right hand, plus the placeholder rifle.
     *
     * <p><b>Empty by default since the 2026 rig re-export.</b> The user re-exported the model with the
     * unnecessary parts deleted: the placeholder rifle ({@code Gun3}) and all eight reference props
     * ({@code Ban}, {@code Lianru}, {@code Bao}, {@code Spwt}, {@code Jiu}, {@code Parrot}, {@code money})
     * are simply not in the geometry any more, so there is nothing to hide. The list stays as the
     * mechanism (and an existing {@code .toml} that still names them keeps working - a name that is not in
     * the rig is reported once and then ignored), but a fresh install hides nothing.</p>
     */
    private static List<? extends String> defaultHiddenBones() {
        return List.of();
    }

    /**
     * Bones that can never be hidden, whatever the config asks for.
     *
     * <p>This is the lesson from the sibling Girls' Frontline project: a rig's clothes and hair are
     * built from layered bones that sit just outside the body and are never animated by any clip -
     * which makes them look exactly like spare parts. Hiding one leaves a hole in the outfit or half
     * a hairstyle missing. Everything here is either the body itself, a layer of it, or a locator
     * bone that other code needs.</p>
     */
    private static final List<String> PROTECTED_BONES = List.of(
            // the body and its upper/lower split
            "Root", "MAllBody", "AllBody", "UpBody", "DownBody", "Body", "UpperBody", "Leg",
            // head and face layers
            "AllHead", "Head", "Head3", "Glass", "Eyes", "Eyebrows", "EyeBrowF",
            "EyeBrow_Left", "EyeBrow_Right", "EyeBalls", "LeftEyeball", "RightEyeball",
            "LeftIris", "RightIris", "YanJing", "Yan", "Ear", "Hat1", "Hat2", "hat3",
            // arms, hands and the sleeves around them (bone52/bone999 are forearm layers)
            "Arm", "RightArm", "RightForeArm", "bone52", "RightHand", "RightHandDMZ",
            "LeftArm", "LeftForeArm", "bone999", "LeftHand", "LeftHandDMZ",
            // clothing layers and the bag
            "fangdanyi_2", "bone4", "Bag",
            // legs and feet
            "LeftLeg", "LeftLowerLeg", "leftfoot", "RightLeg", "RightLowerLeg", "rightfoot",
            // locator bones other code reads
            "RightHandLocator", "LeftHandLocator", "RifleLocator", "PistolLocator");

    /** True when the config asked to hide a bone that must stay visible. */
    public static boolean isProtectedBone(String bone) {
        for (String protectedBone : PROTECTED_BONES) {
            if (protectedBone.equalsIgnoreCase(bone)) {
                return true;
            }
        }
        return false;
    }

    /** The bones to hide, minus anything protected. */
    public static List<String> hiddenBones() {
        List<String> result = new ArrayList<>(HIDDEN_BONES.get().size());
        for (String entry : HIDDEN_BONES.get()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String bone = entry.trim();
            if (isProtectedBone(bone)) {
                TarkovScav.LOGGER.warn("Ignoring hiddenBones entry '{}': that bone is part of the rig's"
                        + " body/clothing layers, not a reference prop - hiding it would break the model."
                        + " The head accessories have their own switches: client.hatAccessory,"
                        + " client.eyeGearAccessory, client.cigarette", bone);
                continue;
            }
            result.add(bone);
        }
        return result;
    }

    // ------------------------------------------------------------------ head accessories

    /** What {@code headAccessories.hatAccessory} can say. */
    public enum HatMode {
        /** Hide every hat except {@code keptHatBone}. */
        KEEP_ONE,
        /** Hide all hats. */
        HIDE_ALL,
        /** Hide no hat (the way the author rigged it). */
        SHOW_ALL,
        /** The config value could not be read; behave like the default. */
        UNKNOWN;

        public static HatMode parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "keepone", "keep_one", "keep" -> KEEP_ONE;
                case "hideall", "hide_all", "none", "hide" -> HIDE_ALL;
                case "showall", "show_all", "all", "show" -> SHOW_ALL;
                default -> UNKNOWN;
            };
        }
    }

    /** One bone to hide, with the reason it is being hidden (printed when logHiddenBones is on). */
    public record HiddenBone(String name, String reason) {
    }

    private static List<? extends String> defaultHatBones() {
        return List.of("Hat1", "Hat2", "hat3");
    }

    private static List<? extends String> defaultEyeGearBones() {
        return List.of("Glass", "YanJing");
    }

    /**
     * The head accessories to hide, as bone + reason pairs.
     *
     * <p>Deliberately <b>not</b> filtered through {@link #isProtectedBone}: the six bones these
     * switches name are measured accessories (three hats, two eye pieces, one cigarette), they are
     * exactly what the user asked to be able to take off, and each one is listed in the rig's own
     * head branch. {@code hiddenBones} keeps its old guard, so an operator cannot knock a body or
     * clothing layer out of the rig from the config by accident; these keys are the supported way to
     * remove the accessories.</p>
     */
    public static List<HiddenBone> accessoryHiddenBones() {
        Map<String, HiddenBone> plan = new LinkedHashMap<>();
        if (!SPEC.isLoaded()) {
            return List.of();
        }

        // ---- hats -------------------------------------------------------------------------
        List<String> hats = cleanList(HAT_BONES.get());
        HatMode mode = HatMode.parse(HAT_ACCESSORY.get());
        String kept = HAT_ACCESSORY.get() == null ? "" : String.valueOf(KEPT_HAT_BONE.get()).trim();
        if (mode == HatMode.UNKNOWN) {
            TarkovScav.LOGGER.warn("client.headAccessories.hatAccessory = '{}' is not one of keepOne,"
                    + " hideAll, showAll; using keepOne", HAT_ACCESSORY.get());
            mode = HatMode.KEEP_ONE;
        }
        switch (mode) {
            case HIDE_ALL -> {
                for (String hat : hats) {
                    plan.put(hat.toLowerCase(Locale.ROOT), new HiddenBone(hat, "hat: hideAll"));
                }
            }
            case KEEP_ONE -> {
                boolean found = false;
                for (String hat : hats) {
                    if (hat.equalsIgnoreCase(kept)) {
                        found = true;
                        continue;
                    }
                    plan.put(hat.toLowerCase(Locale.ROOT),
                            new HiddenBone(hat, "hat: keepOne (keeping " + kept + ")"));
                }
                if (!found && !hats.isEmpty()) {
                    TarkovScav.LOGGER.warn("client.headAccessories.keptHatBone = '{}' is not in"
                            + " hatBones {}; every hat will be hidden", kept, hats);
                }
            }
            case SHOW_ALL -> {
                // nothing
            }
            default -> {
                // UNKNOWN was already replaced above
            }
        }

        // ---- eye gear ---------------------------------------------------------------------
        List<String> gear = cleanList(EYE_GEAR_BONES.get());
        String worn = EYE_GEAR_ACCESSORY.get() == null ? "none" : String.valueOf(EYE_GEAR_ACCESSORY.get()).trim();
        boolean wearNone = worn.equalsIgnoreCase("none") || worn.equalsIgnoreCase("off")
                || worn.equalsIgnoreCase("false") || worn.isBlank();
        for (String piece : gear) {
            if (!wearNone && piece.equalsIgnoreCase(worn)) {
                continue;
            }
            plan.put(piece.toLowerCase(Locale.ROOT),
                    new HiddenBone(piece, wearNone ? "eyeGear: none" : "eyeGear: wearing " + worn));
        }

        // ---- cigarette --------------------------------------------------------------------
        String cigaretteBone = CIGARETTE_BONE.get() == null ? "" : String.valueOf(CIGARETTE_BONE.get()).trim();
        if (!Boolean.TRUE.equals(CIGARETTE.get()) && !cigaretteBone.isEmpty()) {
            plan.put(cigaretteBone.toLowerCase(Locale.ROOT), new HiddenBone(cigaretteBone, "cigarette: off"));
        }

        // ---- the diagnostic list ----------------------------------------------------------
        for (String extra : cleanList(EXTRA_HIDDEN_BONES.get())) {
            plan.put(extra.toLowerCase(Locale.ROOT), new HiddenBone(extra, "extraHiddenBones entry"));
        }

        return new ArrayList<>(plan.values());
    }

    /** Every bone name a config value or list in the {@code headAccessories} section names. */
    public static List<String> accessoryConfiguredBones() {
        List<String> names = new ArrayList<>();
        if (!SPEC.isLoaded()) {
            return names;
        }
        names.addAll(cleanList(HAT_BONES.get()));
        String kept = keptHatName();
        if (!kept.isEmpty()) {
            names.add(kept);
        }
        names.addAll(cleanList(EYE_GEAR_BONES.get()));
        String worn = EYE_GEAR_ACCESSORY.get() == null ? "" : String.valueOf(EYE_GEAR_ACCESSORY.get()).trim();
        if (!worn.isEmpty() && !worn.equalsIgnoreCase("none")) {
            names.add(worn);
        }
        String cigaretteBone = CIGARETTE_BONE.get() == null ? "" : String.valueOf(CIGARETTE_BONE.get()).trim();
        if (!cigaretteBone.isEmpty()) {
            names.add(cigaretteBone);
        }
        names.addAll(cleanList(EXTRA_HIDDEN_BONES.get()));
        return names;
    }

    private static String keptHatName() {
        return KEPT_HAT_BONE.get() == null ? "" : String.valueOf(KEPT_HAT_BONE.get()).trim();
    }

    /** The head rest pitch in degrees, or 0 when the config is not loaded yet. */
    public static float headRestPitchDegrees() {
        return SPEC.isLoaded() ? HEAD_REST_PITCH_DEGREES.get().floatValue() : 0.0F;
    }

    /** True when the head rest pitch applies to this AI state. */
    public static boolean headRestPitchAppliesTo(GunAiState state) {
        if (state == null || !SPEC.isLoaded()) {
            return false;
        }
        for (String entry : HEAD_REST_PITCH_STATES.get()) {
            if (entry == null) {
                continue;
            }
            String token = entry.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("any") || token.equals("*") || token.equalsIgnoreCase("all")) {
                return true;
            }
            try {
                if (GunAiState.valueOf(token.toUpperCase(Locale.ROOT)) == state) {
                    return true;
                }
            } catch (IllegalArgumentException unknownState) {
                TarkovScav.LOGGER.warn("client.headAccessories.headRestPitchStates entry '{}' is not a"
                        + " gun-AI state; ignoring it (valid: idle, alert, advance, aim, fire,"
                        + " suppress, reload, bolt, reposition, retreat, any)", token);
            }
        }
        return false;
    }

    /** The {@code ItemDisplayContext} name the held gun is rendered with, upper case. */
    public static String gunMountDisplayContext() {
        if (!SPEC.isLoaded()) {
            return "THIRD_PERSON_RIGHT_HAND";
        }
        String raw = GUN_MOUNT_DISPLAY_CONTEXT.get();
        return raw == null || raw.isBlank() ? "THIRD_PERSON_RIGHT_HAND" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** True when the anchor frame should be turned into vanilla's held-item hand frame. */
    public static boolean normalisedHandMode() {
        if (!SPEC.isLoaded()) {
            return true;
        }
        String raw = GUN_ANCHOR_MODE.get();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String token = raw.trim();
        if (token.equalsIgnoreCase("normalisedHand") || token.equalsIgnoreCase("normalizedHand")
                || token.equalsIgnoreCase("hand")) {
            return true;
        }
        if (token.equalsIgnoreCase("locatorAnimated") || token.equalsIgnoreCase("locator")) {
            return false;
        }
        TarkovScav.LOGGER.warn("client.gunAnchorMode = '{}' is neither locatorAnimated nor normalisedHand;"
                + " using normalisedHand", raw);
        return true;
    }

    /** The configured offhand anchor bone name, trimmed. */
    public static String gunOffhandAnchorBone() {
        if (!SPEC.isLoaded()) {
            return "LeftHandLocator";
        }
        String raw = GUN_OFFHAND_ANCHOR_BONE.get();
        return raw == null || raw.isBlank() ? "LeftHandLocator" : raw.trim();
    }

    /**
     * True when the whole rig is animated by ONE controller ({@code modelLayering = single}), false for the
     * layered legs/upper-body pair ({@code upperLower}, the shipped default - one controller freezes the
     * legs, because the rig's gun clips carry no leg tracks).
     *
     * <p>Both are a single geometry submission; see the key's comment for what actually differs.</p>
     */
    public static boolean singleControllerMode() {
        if (!SPEC.isLoaded()) {
            return DEFAULT_MODEL_LAYERING.equalsIgnoreCase("single");
        }
        String raw = MODEL_LAYERING.get();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODEL_LAYERING.equalsIgnoreCase("single");
        }
        String token = raw.trim();
        if (token.equalsIgnoreCase("single")) {
            return true;
        }
        if (token.equalsIgnoreCase("upperLower") || token.equalsIgnoreCase("upperlower")
                || token.equalsIgnoreCase("layered")) {
            return false;
        }
        TarkovScav.LOGGER.warn("client.modelLayering = '{}' is neither upperLower nor single; using {}",
                raw, DEFAULT_MODEL_LAYERING);
        return DEFAULT_MODEL_LAYERING.equalsIgnoreCase("single");
    }

    /**
     * Who owns the aim pose bones: {@link PoseSource#AUTO} (the shipped default), {@code code} or
     * {@code clips}. See the key's comment for why two writers on one bone is the thing to avoid.
     */
    public static PoseSource poseSource() {
        if (!SPEC.isLoaded()) {
            return PoseSource.parse(PoseSource.DEFAULT);
        }
        return PoseSource.parse(POSE_SOURCE.get());
    }

    /**
     * Which of the rig's own aim variables are fed from the live entity. See the
     * {@code client.molangVariables} key for why the yaw symbols are opt-in.
     */
    public enum MolangFeed {
        /** Nothing is fed: every symbol is pinned to 0, the pre-Molang behaviour. */
        OFF,
        /** Only the pitch symbols are fed (the default): the author's pitch poses come alive. */
        PITCH,
        /** Every symbol is fed, yaw included. */
        ALL;

        /** Parses the config value; an unreadable value is reported and treated as {@link #PITCH}. */
        public static MolangFeed parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PITCH;
            }
            String token = raw.trim();
            if (token.equalsIgnoreCase("off") || token.equalsIgnoreCase("false")
                    || token.equalsIgnoreCase("none")) {
                return OFF;
            }
            if (token.equalsIgnoreCase("all") || token.equalsIgnoreCase("true")
                    || token.equalsIgnoreCase("yaw")) {
                return ALL;
            }
            if (token.equalsIgnoreCase("pitch") || token.equalsIgnoreCase("pitchOnly")) {
                return PITCH;
            }
            TarkovScav.LOGGER.warn("client.molangVariables = '{}' is neither off, pitch nor all; using {}",
                    raw, DEFAULT_MOLANG_VARIABLES);
            return PITCH;
        }
    }

    /** Which of the rig's own aim variables are fed; see {@link MolangFeed}. */
    public static MolangFeed molangFeed() {
        if (!SPEC.isLoaded()) {
            return MolangFeed.parse(DEFAULT_MOLANG_VARIABLES);
        }
        return MolangFeed.parse(MOLANG_VARIABLES.get());
    }

    /**
     * True when the yaw symbols are fed, i.e. when a clip's yaw track can claim the aim bones at all.
     * With the yaw pinned to 0 a Molang yaw track is a constant, so nothing is look-driven and
     * {@code poseSource = auto} behaves exactly like {@code code} - which is what makes the two keys
     * independent switches instead of one coupled pair.
     */
    public static boolean feedsMolangYaw() {
        return molangFeed() == MolangFeed.ALL;
    }

    /**
     * The fraction of the look's yaw the code writes on the torso ({@code UpperBody}); the head takes
     * the rest, so the total gain is always 1.0. See the key's comment for the measured swing per value.
     */
    public static float torsoYawShare() {
        return SPEC.isLoaded() ? TORSO_YAW_SHARE.get().floatValue() : (float) DEFAULT_TORSO_YAW_SHARE;
    }
    /** The validated {@code client.modelRenderType} value: cutout | zOffset | translucent | solid. */
    public static String modelRenderType() {
        if (!SPEC.isLoaded()) {
            return DEFAULT_MODEL_RENDER_TYPE;
        }
        String raw = MODEL_RENDER_TYPE.get();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODEL_RENDER_TYPE;
        }
        String token = raw.trim();
        for (String valid : new String[]{"cutout", "zOffset", "translucent", "solid"}) {
            if (valid.equalsIgnoreCase(token)) {
                return valid;
            }
        }
        TarkovScav.LOGGER.warn("client.modelRenderType = '{}' is not one of cutout, zOffset, translucent,"
                + " solid; using {}", raw, DEFAULT_MODEL_RENDER_TYPE);
        return DEFAULT_MODEL_RENDER_TYPE;
    }

    /** Extra blocks of padding for the frustum-culling box; 0 keeps vanilla behaviour. */
    public static float cullingBoxPadding() {
        return SPEC.isLoaded() ? CULLING_BOX_PADDING.get().floatValue() : (float) DEFAULT_CULLING_BOX_PADDING;
    }

    /**
     * The model size actually used for rendering, clamped and NaN-guarded, read <b>per render</b> so
     * {@code /tarkovscav client scale} takes effect on the next frame. A hand-edited toml cannot break the
     * renderer with a silly value: anything outside {@link #RENDER_SCALE_MIN}..{@link #RENDER_SCALE_MAX} is
     * clamped, and a NaN falls back to the shipped default.
     */
    public static float renderScale() {
        if (!SPEC.isLoaded()) {
            return (float) DEFAULT_RENDER_SCALE;
        }
        double raw = RENDER_SCALE.get();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return (float) DEFAULT_RENDER_SCALE;
        }
        return (float) Mth.clamp(raw, RENDER_SCALE_MIN, RENDER_SCALE_MAX);
    }

    /**
     * The culling-box padding for the current model size. The rig renders about 2.8 x 3.85 blocks of
     * geometry at scale 0.7 while its hitbox is 0.6 x 1.95, so the padding is what keeps a partly visible
     * mob in the frustum; because the overhang grows with the scale, the padding does too
     * ({@code cullingBoxPadding x scale / 0.7}, i.e. 1.0 block at the baseline and 1.1 at 0.77).
     */
    public static float cullingBoxPaddingForScale() {
        return cullingBoxPadding() * (renderScale() / (float) DEFAULT_RENDER_SCALE);
    }

    /**
     * The render scale of the villager-based mobs, read per render like {@link #renderScale()}.
     *
     * <p><b>1.0 is the vanilla villager size</b> and is used as an absolute multiplier: the vanilla model is
     * authored at 1.0, so there is no "baseline" to divide by - unlike the rig, whose baseline is
     * {@link #DEFAULT_RENDER_SCALE}. That asymmetry is the bug this key fixes: the villager used to be
     * scaled by {@code renderScale / 0.7}, so enlarging the rig also enlarged every armed villager.</p>
     */
    public static float villagerRenderScale() {
        if (!SPEC.isLoaded()) {
            return (float) DEFAULT_VILLAGER_RENDER_SCALE;
        }
        double raw = VILLAGER_RENDER_SCALE.get();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return (float) DEFAULT_VILLAGER_RENDER_SCALE;
        }
        return (float) Mth.clamp(raw, RENDER_SCALE_MIN, RENDER_SCALE_MAX);
    }

    /** The culling-box padding for the villager family, following its own scale (1.0 = vanilla). */
    public static float cullingBoxPaddingForVillagerScale() {
        return cullingBoxPadding() * villagerRenderScale();
    }

    /** GL-state debug for the stencil leak (README 5k); false unless the user is chasing that bug. */
    public static boolean logGlState() {
        return SPEC.isLoaded() && LOG_GL_STATE.get();
    }

    /**
     * The lower end of the voice pitch band, read once per call and always usable.
     *
     * <p>The two ends are validated <b>together</b>, because a spec cannot express "min &lt;= max": a NaN,
     * an infinity, or a pair that is the wrong way round, or one that collapses to a single value after
     * clamping, all fall back to the shipped {@code 0.9/1.1} pair. That is why this method returns the
     * whole pair - the single-value getters below cannot see the other end.</p>
     */
    public static double[] voicePitchBand() {
        double min = DEFAULT_VOICE_PITCH_MIN;
        double max = DEFAULT_VOICE_PITCH_MAX;
        if (SPEC.isLoaded()) {
            min = clampPitch(VOICE_PITCH_MIN.get(), DEFAULT_VOICE_PITCH_MIN);
            max = clampPitch(VOICE_PITCH_MAX.get(), DEFAULT_VOICE_PITCH_MAX);
            if (min > max) {
                TarkovScav.LOGGER.warn("[voice] pitchMin={} > pitchMax={}; falling back to the shipped {}/{}",
                        VOICE_PITCH_MIN.get(), VOICE_PITCH_MAX.get(),
                        DEFAULT_VOICE_PITCH_MIN, DEFAULT_VOICE_PITCH_MAX);
                min = DEFAULT_VOICE_PITCH_MIN;
                max = DEFAULT_VOICE_PITCH_MAX;
            }
        }
        return new double[] {min, max};
    }

    /** Lower end of the voice pitch band (see {@link #voicePitchBand()}). */
    public static float voicePitchMin() {
        return (float) voicePitchBand()[0];
    }

    /** Upper end of the voice pitch band (see {@link #voicePitchBand()}). */
    public static float voicePitchMax() {
        return (float) voicePitchBand()[1];
    }

    /** Per-line pitch wobble, clamped to 0..0.5 and NaN-guarded. */
    public static float voicePitchJitter() {
        if (!SPEC.isLoaded()) {
            return (float) DEFAULT_VOICE_PITCH_JITTER;
        }
        return (float) Mth.clamp(clampPitch(VOICE_PITCH_JITTER.get(), DEFAULT_VOICE_PITCH_JITTER), 0.0D, 0.5D);
    }

    /** The families {@code voice.familyVolume} understands: the shared pool plus the three factions. */
    public static final List<String> VOICE_FAMILIES = List.of("shared", "usec", "bear", "elite");

    /** Bad {@code familyVolume} entries already reported, so a typo warns once per session. */
    private static final Set<String> WARNED_FAMILY_VOLUME = new HashSet<>();

    /**
     * The volume multiplier of one family (README 5l), read from the {@code familyVolume} entries.
     *
     * <p>Anything unreadable falls back to {@code 1.0} - and says so once: an unknown family name, a bad
     * number, or an entry without a {@code =}. The value is clamped to 0..4 like {@code voice.volume}, so a
     * silly toml cannot blow the mix up.</p>
     */
    public static double familyVolume(String family) {
        if (family == null || family.isBlank() || !SPEC.isLoaded()) {
            return 1.0D;
        }
        String wanted = family.trim().toLowerCase(Locale.ROOT);
        for (String entry : VOICE_FAMILY_VOLUME.get()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                warnFamilyVolume(entry, "no 'family=value' pair");
                continue;
            }
            String name = entry.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!VOICE_FAMILIES.contains(name)) {
                warnFamilyVolume(entry, "unknown family");
                continue;
            }
            if (!name.equals(wanted)) {
                continue;
            }
            try {
                return Mth.clamp(Double.parseDouble(entry.substring(equals + 1).trim()), 0.0D, 4.0D);
            } catch (NumberFormatException bad) {
                warnFamilyVolume(entry, "not a number");
                return 1.0D;
            }
        }
        return 1.0D;
    }

    /** Every family's resolved multiplier, in {@link #VOICE_FAMILIES} order, for the command readout. */
    public static List<String> familyVolumeSummary() {
        List<String> out = new ArrayList<>(VOICE_FAMILIES.size());
        for (String family : VOICE_FAMILIES) {
            out.add(family + "=" + String.format(Locale.ROOT, "%.2f", familyVolume(family)));
        }
        return out;
    }

    private static void warnFamilyVolume(String entry, String why) {
        if (WARNED_FAMILY_VOLUME.add(entry + "|" + why)) {
            TarkovScav.LOGGER.warn("[voice] familyVolume entry '{}' is ignored ({}); valid entries are"
                    + " 'family=value' with family one of {}", entry, why, VOICE_FAMILIES);
        }
    }

    /** Volume multiplier for the non-voice effect clips (the grenade impact; README 5v). */
    public static double effectVolume() {
        return SPEC.isLoaded() ? Mth.clamp(VOICE_EFFECT_VOLUME.get(), 0.0D, 4.0D) : 1.0D;
    }

    private static double clampPitch(double raw, double fallback) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return fallback;
        }
        return Mth.clamp(raw, VOICE_PITCH_FLOOR, VOICE_PITCH_CEILING);
    }

    /** Gunner villager arm pitch (degrees) while the weapon is raised. */
    public static float gunnerVillagerAimArmPitch() {
        return SPEC.isLoaded()
                ? GUNNER_VILLAGER_AIM_ARM_PITCH.get().floatValue()
                : (float) DEFAULT_GUNNER_VILLAGER_AIM_ARM_PITCH;
    }

    /** Gunner villager arm pitch (degrees) while a gun is held but not in use (IDLE with a weapon). */
    public static float gunnerVillagerHoldArmPitch() {
        return SPEC.isLoaded()
                ? GUNNER_VILLAGER_HOLD_ARM_PITCH.get().floatValue()
                : (float) DEFAULT_GUNNER_VILLAGER_HOLD_ARM_PITCH;
    }

    /** Whether the armed villager's held gun is hidden while it is idle (the LOWERED pose). */
    public static boolean hideGunWhenIdle() {
        return SPEC.isLoaded() && Boolean.TRUE.equals(HIDE_GUN_WHEN_IDLE.get());
    }

    /** Gunner villager arm pitch (degrees) while reloading - the middle silhouette. */
    public static float gunnerVillagerReloadArmPitch() {
        return SPEC.isLoaded()
                ? GUNNER_VILLAGER_RELOAD_ARM_PITCH.get().floatValue()
                : (float) DEFAULT_GUNNER_VILLAGER_RELOAD_ARM_PITCH;
    }

    /** Gunner villager arm pitch (degrees) while breaking contact - the lowest silhouette. */
    public static float gunnerVillagerHunkerArmPitch() {
        return SPEC.isLoaded()
                ? GUNNER_VILLAGER_HUNKER_ARM_PITCH.get().floatValue()
                : (float) DEFAULT_GUNNER_VILLAGER_HUNKER_ARM_PITCH;
    }

    /** The held-gun rotation on the gunner villager, as {pitch, yaw, roll} in degrees. */
    public static float[] gunnerVillagerGunRotation() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_GUN_ROTATION, 5.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_GUN_ROTATION.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /**
     * The EXTRA gun rotation that applies only while the villager is idle, as {pitch, yaw, roll}.
     *
     * <p>Added to {@link #gunnerVillagerGunRotation()} by the model when - and only when - the pose is
     * LOWERED (README 5j). The fallback is the shipped default, so a broken toml cannot silently remove the
     * idle correction and put the muzzle back in the air.</p>
     */
    public static float[] gunnerVillagerIdleGunRotation() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_IDLE_GUN_ROTATION, -2.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_IDLE_GUN_ROTATION.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /**
     * The gun rotation ADDED while the pose is RELOADING, on top of {@link #gunnerVillagerGunRotation()}
     * (README 5j). Empty-ish by default: {@code [0,0,0]} means "the base rotation, exactly as before".
     */
    public static float[] gunnerVillagerReloadGunRotation() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_ROTATION, 0.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_RELOAD_GUN_ROTATION.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /**
     * The gun rotation ADDED while the pose is HUNKERED (retreating), on top of
     * {@link #gunnerVillagerGunRotation()} (README 5j). {@code [0,0,0]} by default, like the reload delta.
     */
    public static float[] gunnerVillagerHunkerGunRotation() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_ROTATION, 0.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_HUNKER_GUN_ROTATION.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }
    /** Weapon position delta while IDLE (LOWERED), added to {@link #gunnerVillagerGunOffset()} (README 5j). */
    public static float[] gunnerVillagerIdleGunOffset() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_IDLE_GUN_OFFSET, 0.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_IDLE_GUN_OFFSET.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /** Weapon position delta while RELOADING, added to {@link #gunnerVillagerGunOffset()} (README 5j). */
    public static float[] gunnerVillagerReloadGunOffset() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_RELOAD_GUN_OFFSET, 0.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_RELOAD_GUN_OFFSET.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /** Weapon position delta while HUNKERED (retreating), added to {@link #gunnerVillagerGunOffset()}. */
    public static float[] gunnerVillagerHunkerGunOffset() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_HUNKER_GUN_OFFSET, 0.0F, 0.0F, 0.0F);
        return SPEC.isLoaded()
                ? triple(GUNNER_VILLAGER_HUNKER_GUN_OFFSET.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /** Extra scale for the gunner villager's gun (on top of TaCZ's own 0.6). */
    public static float gunnerVillagerGunScale() {
        return SPEC.isLoaded()
                ? GUNNER_VILLAGER_GUN_SCALE.get().floatValue()
                : (float) DEFAULT_GUNNER_VILLAGER_GUN_SCALE;
    }

    /** True when the villager's gun should hang off the torso instead of the animated arms block. */
    public static boolean gunnerVillagerGunOnBody() {
        if (!SPEC.isLoaded()) {
            return false;
        }
        String raw = GUNNER_VILLAGER_GUN_ANCHOR.get();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String token = raw.trim();
        if (token.equalsIgnoreCase("body")) {
            return true;
        }
        if (token.equalsIgnoreCase("arms")) {
            return false;
        }
        TarkovScav.LOGGER.warn("client.gunnerVillagerGunAnchor = '{}' is neither arms nor body; using arms",
                raw);
        return false;
    }
    /** The held-gun offset on the gunner villager, in the arm frame, in blocks. */
    public static float[] gunnerVillagerGunOffset() {
        float[] fallback = triple(DEFAULT_GUNNER_VILLAGER_GUN_OFFSET, 0.0F, 0.06F, -0.09F);
        return SPEC.isLoaded() ? triple(GUNNER_VILLAGER_GUN_OFFSET.get(), fallback[0], fallback[1], fallback[2])
                : fallback;
    }

    /** The offhand-anchor transform, as {rotX, rotY, rotZ, offX, offY, offZ, scale}. */
    public static float[] offhandMount() {
        if (!SPEC.isLoaded()) {
            return new float[]{0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F};
        }
        float[] rotation = triple(GUN_MOUNT_OFFHAND_ROTATION.get(), 0.0F, 0.0F, 0.0F);
        float[] offset = triple(GUN_MOUNT_OFFHAND_OFFSET.get(), 0.0F, 0.0F, 0.0F);
        return new float[]{rotation[0], rotation[1], rotation[2], offset[0], offset[1], offset[2],
                GUN_MOUNT_OFFHAND_SCALE.get().floatValue()};
    }

    private static List<String> cleanList(List<? extends String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return out;
    }

    /**
     * The script namespaces that may still reach mobs, lower-cased (README 5p). Empty means "trust nobody",
     * i.e. every Lua-scripted gun is kept out of the pool - the maximum-safety setting.
     */
    public static List<String> trustedScriptNamespaces() {
        if (!SPEC.isLoaded()) {
            return List.of("tacz");
        }
        List<String> out = new ArrayList<>();
        for (String value : cleanList(TRUSTED_SCRIPT_NAMESPACES.get())) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Whether the runtime scripted-gun guard runs, and how often; 0 = once at spawn only (README 5p). */
    public static int scriptedGunRescanTicks() {
        return SPEC.isLoaded() ? SCRIPTED_GUN_RESCAN_TICKS.get() : 40;
    }

    /** Whether the gun's own data file decides the issued stack's fire mode (README 5p). */
    public static boolean respectDeclaredFireModes() {
        return SPEC.isLoaded() && RESPECT_DECLARED_FIRE_MODES.get();
    }

    /** The three-element numeric config values, e.g. gunMountRotation. */
    public static float[] triple(List<? extends String> raw, float fallbackX, float fallbackY, float fallbackZ) {
        float[] out = {fallbackX, fallbackY, fallbackZ};
        if (raw.size() >= 3) {
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Float.parseFloat(String.valueOf(raw.get(i)).trim());
                } catch (NumberFormatException badEntry) {
                    TarkovScav.LOGGER.warn("Could not read '{}' as a number; using the default {}", raw.get(i), out[i]);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** True when the given gun id is allowed by the whitelist/blacklist, ignoring its type. */
    public static boolean gunIdAllowed(String gunId) {
        String normalised = gunId.toLowerCase(Locale.ROOT);
        for (String blocked : GUN_BLACKLIST.get()) {
            if (blocked != null && blocked.toLowerCase(Locale.ROOT).equals(normalised)) {
                return false;
            }
        }
        List<? extends String> whitelist = GUN_WHITELIST.get();
        if (whitelist.isEmpty()) {
            return true;
        }
        for (String allowed : whitelist) {
            if (allowed != null && allowed.toLowerCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    public static boolean gunTypeExcluded(String type) {
        if (type == null) {
            return false;
        }
        String normalised = type.toLowerCase(Locale.ROOT);
        for (String excluded : EXCLUDED_GUN_TYPES.get()) {
            if (excluded != null && excluded.toLowerCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    /** True when this TaCZ gun type uses the rig's one-handed (*_pistol) clips. */
    public static boolean usesPistolClips(String type) {
        if (type == null) {
            return false;
        }
        String normalised = type.toLowerCase(Locale.ROOT);
        for (String pistolType : PISTOL_CLIP_TYPES.get()) {
            if (pistolType != null && pistolType.toLowerCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot of the configured boxes; parsed lazily because it is only read during spawn checks. */
    /** How deep the district assembler guarantees the footing under every piece (README 7b). */
    public static int cityFoundationDepth() {
        return SPEC.isLoaded() ? CITY_FOUNDATION_DEPTH.get() : 5;
    }

    public static List<String> cityRegionEntries() {
        return new ArrayList<>(CITY_REGIONS.get());
    }
}
