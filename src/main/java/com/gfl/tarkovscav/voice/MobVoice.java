package com.gfl.tarkovscav.voice;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.gfl.tarkovscav.gun.GunAiState;
import com.gfl.tarkovscav.gun.GunUser;
import com.gfl.tarkovscav.registry.ModSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.Random;

/**
 * Per-mob voice: the five triggers from README 5l, all server-side and all positional.
 *
 * <p>Every line is played with {@code level.playSound(null, x, y, z, ...)} <b>at the mob's position</b>,
 * never with the overload that takes a player - that is what makes it spatialised: you hear which
 * building it came from. The clips are mono for the same reason (see {@code tools/voice_report.js}).</p>
 *
 * <p>The idle interval is deliberately its own config key and <b>not</b> vanilla's
 * {@code getAmbientSoundInterval} (about six seconds): the user asked for roughly one line every twenty
 * seconds, so this class owns the timer and the entities do not override the vanilla ambient path for
 * voice at all.</p>
 *
 * <p><b>Pitch</b> is per mob and persistent: see {@link #basePitch()} (drawn once, kept in the mob's
 * saved data) and {@link #nextPitch()} (that pitch plus a small per-line wobble, clamped to the band).
 * Nothing here uses a literal pitch: {@code voice.pitchMin}/{@code pitchMax}/{@code pitchJitter} own it,
 * and {@code tools/selftest_voice.js} fails the build if a hard-coded pitch comes back.</p>
 */
public final class MobVoice {
    private final Mob mob;
    private final Random random = new Random();

    private int idleCooldown;
    private int contactCooldown;
    private int chatterCooldown;
    private int grenadeCooldown;
    /**
     * The SHOUT cooldown: how often this mob may shout about throwing a grenade. Deliberately NOT the same
     * field as {@link #grenadeCooldown}: that one is the reaction timer (a grenade landed near me), and while
     * the two shared one timer a reaction in a firefight swallowed the shout - half of the "grenade shouts
     * got rarer" report (README 5l).
     */
    private int grenadeShoutCooldown;
    private int markCooldown;
    private int lastTargetId = -1;
    private boolean saidContactForTarget;
    /** This mob's own pitch, or -1 until it first speaks (see {@link #basePitch()}). */
    private float basePitch = -1.0F;

    public MobVoice(Mob mob) {
        this.mob = mob;
    }

    /** Server-side, once per tick, from the entity's own {@code tick()}. */
    public void tick(LivingEntity target) {
        if (!(this.mob.level() instanceof ServerLevel level) || !Config.VOICE_ENABLED.get()) {
            return;
        }
        tickDown();
        trackTarget(target);

        if (target != null) {
            tryContact(level, target);
            tryChatter(level);
        } else {
            tryIdle(level);
        }
        tryGrenade(level);
    }

    private void tickDown() {
        if (this.idleCooldown > 0) {
            this.idleCooldown--;
        }
        if (this.contactCooldown > 0) {
            this.contactCooldown--;
        }
        if (this.chatterCooldown > 0) {
            this.chatterCooldown--;
        }
        if (this.grenadeCooldown > 0) {
            this.grenadeCooldown--;
        }
        if (this.grenadeShoutCooldown > 0) {
            this.grenadeShoutCooldown--;
        }
        if (this.markCooldown > 0) {
            this.markCooldown--;
        }
    }

    /**
     * "Same target only once": the contact line is keyed on the target's entity id, so re-acquiring the
     * same mob does not repeat it, but a new attacker does. The cooldown is the second half of the
     * anti-spam rule.
     */
    private void trackTarget(LivingEntity target) {
        int id = target == null ? -1 : target.getId();
        if (id != this.lastTargetId) {
            if (this.lastTargetId != -1 && id == -1) {
                sayTargetLost();
            }
            this.lastTargetId = id;
            this.saidContactForTarget = false;
        }
    }

    private void tryContact(ServerLevel level, LivingEntity target) {
        if (!Config.VOICE_CONTACT.get() || this.saidContactForTarget || this.contactCooldown > 0) {
            return;
        }
        // Only once the brain has actually engaged (ALERT/AIM/FIRE...), not while it is still idle.
        GunAiState state = state();
        if (state == GunAiState.IDLE) {
            return;
        }
        this.saidContactForTarget = true;
        this.contactCooldown = Config.VOICE_CONTACT_COOLDOWN_TICKS.get();
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.CHATTER, "contact"), "contact");
    }

    private void tryChatter(ServerLevel level) {
        if (!Config.VOICE_CHATTER.get() || this.chatterCooldown > 0) {
            return;
        }
        if (state() != GunAiState.FIRE && state() != GunAiState.SUPPRESS) {
            return;
        }
        this.chatterCooldown = randomBetween(Config.VOICE_CHATTER_MIN_TICKS.get(),
                Config.VOICE_CHATTER_MAX_TICKS.get());
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.CHATTER, "chatter"), "chatter");
    }

    private void tryIdle(ServerLevel level) {
        if (!Config.VOICE_IDLE.get() || this.idleCooldown > 0) {
            return;
        }
        this.idleCooldown = Config.VOICE_IDLE_INTERVAL_TICKS.get()
                + this.random.nextInt(Math.max(1, Config.VOICE_IDLE_JITTER_TICKS.get()));
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.IDLE, "idle"), "idle");
    }

    /**
     * Anything explosive close by: vanilla primed TNT, plus any entity whose registry path contains
     * "grenade" (which is how a TaCZ or other mod's grenade is recognised without depending on its
     * class). Firing the line is the whole reaction here - the brain's own hurt/retreat logic decides
     * what to do about it.
     */
    private void tryGrenade(ServerLevel level) {
        if (!Config.VOICE_GRENADE.get() || this.grenadeCooldown > 0) {
            return;
        }
        double radius = Config.VOICE_GRENADE_RADIUS.get();
        List<Entity> nearby = level.getEntities(this.mob, this.mob.getBoundingBox().inflate(radius));
        for (Entity entity : nearby) {
            boolean explosive = entity instanceof PrimedTnt
                    || entity.getType().builtInRegistryHolder().key().location().getPath().contains("grenade");
            if (explosive) {
                this.grenadeCooldown = Config.VOICE_GRENADE_COOLDOWN_TICKS.get();
                say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.GRENADE, "grenade"), "grenade");
                return;
            }
        }
    }

    /**
     * The thrower's own shout (README 5v): the same {@code grenade_1}/{@code grenade_2} pool the reaction
     * above uses, so "I am throwing one" and "one landed next to me" sound like the same squad.
     */
    public void sayGrenade() {
        if (!Config.VOICE_ENABLED.get() || !Config.VOICE_GRENADE.get() || this.grenadeShoutCooldown > 0) {
            return;
        }
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        this.grenadeShoutCooldown = Config.VOICE_GRENADE_SHOUT_COOLDOWN_TICKS.get();
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.GRENADE, "grenade"), "grenade");
    }

    /** Called when the brain loses its target (the "where did you run off to" line). */    public void sayTargetLost() {
        if (!(this.mob.level() instanceof ServerLevel level)
                || !Config.VOICE_ENABLED.get() || !Config.VOICE_MARK.get() || this.markCooldown > 0) {
            return;
        }
        this.markCooldown = Config.VOICE_CONTACT_COOLDOWN_TICKS.get();
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.MARK, "mark"), "mark");
    }

    /** Called from {@code Mob#die}. */
    public void sayDeath() {
        if (!(this.mob.level() instanceof ServerLevel level)
                || !Config.VOICE_ENABLED.get() || !Config.VOICE_DEATH.get()) {
            return;
        }
        say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.DEATH, "death"), "death");
    }

    private GunAiState state() {
        return this.mob instanceof GunUser user ? user.gunAiState() : GunAiState.IDLE;
    }

    private int randomBetween(int min, int max) {
        return max <= min ? min : min + this.random.nextInt(max - min);
    }

    /**
     * This mob's own pitch: drawn once from {@code [voice.pitchMin, voice.pitchMax]}, then written into the
     * mob's persistent data so it survives a chunk unload, a save and a server restart. Two scavs therefore
     * sound like two people rather than one recording, and the <b>same</b> scav sounds the same every time
     * you meet it - which is the part vanilla's own {@code getVoicePitch()} does for its mobs (0.8-1.2,
     * drawn once in the entity constructor).
     *
     * <p>A stored value that is no longer inside the band (the user narrowed it in the toml) is replaced by
     * a fresh draw, so the band is always honoured even for mobs that were already in the world.</p>
     */
    public float basePitch() {
        if (this.basePitch > 0.0F) {
            return this.basePitch;
        }
        double[] band = Config.voicePitchBand();
        float stored = Float.NaN;
        CompoundTag data = this.mob.getPersistentData();
        Tag tag = data.get(Config.NBT_VOICE_PITCH);
        if (tag instanceof NumericTag number) {
            stored = number.getAsFloat();
        }
        if (Float.isNaN(stored) || stored < (float) band[0] || stored > (float) band[1]) {
            stored = (float) (band[0] + this.random.nextDouble() * (band[1] - band[0]));
            data.putFloat(Config.NBT_VOICE_PITCH, stored);
        }
        this.basePitch = stored;
        return this.basePitch;
    }

    /**
     * The pitch of the <b>next</b> line: the mob's own pitch plus a small per-line wobble, clamped back
     * into the configured band so a single line can never leave it. The wobble is what stops a mob that
     * speaks five times in a row from sounding like a loop.
     */
    public float nextPitch() {
        double[] band = Config.voicePitchBand();
        double wobble = (this.random.nextDouble() * 2.0D - 1.0D) * Config.voicePitchJitter();
        return (float) Mth.clamp(basePitch() + wobble, band[0], band[1]);
    }

    private void say(ServerLevel level, java.util.List<SoundEvent> pool, String kind) {
        if (pool.isEmpty()) {
            return;
        }
        SoundEvent sound = pool.get(this.random.nextInt(pool.size()));
        float pitch = nextPitch();
        level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(), sound,
                this.mob.getSoundSource(), voiceVolume(), pitch);
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[voice] {} {} -> {} ({} line(s) in pool) pitch={} voice={} volume={}",
                    this.mob.getName().getString(), kind, sound.getLocation(), pool.size(),
                    String.format("%.3f", pitch), String.format("%.3f", basePitch()),
                    String.format("%.2f", voiceVolume()));
        }
    }

    /**
     * The volume of this mob's next line: {@code voice.volume} times its family's multiplier (README 5l).
     *
     * <p>The family multiplier exists because "the usec/bear/elite voices are quieter than the scav's" is a
     * per-family judgement: the clips are now rendered to the same measured loudness (see the shipped
     * {@code voice_levels.json}), so 1.0 is the calibrated value and this is the escape hatch for taste or
     * for a single clip the user still finds quiet - without re-cutting any audio.</p>
     */
    public float voiceVolume() {
        return VoicePools.volumeFor(this.mob);
    }
}
