package com.gfl.tarkovscav.gun;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;

/**
 * The small set of gun state the <em>client</em> needs, because {@link GunBrain} only runs on the
 * server and an animation controller cannot look at it.
 *
 * <ul>
 *   <li>{@code aiming} - the weapon is up (drawing, aiming, advancing, repositioning);</li>
 *   <li>{@code firing} - a shot is being taken this tick (drives the recoil clip);</li>
 *   <li>{@code reloading} - TaCZ is mid-reload (drives the reloading clip, which only exists on the
 *       upper body - the legs keep walking);</li>
 *   <li>{@code pistolClips} - which clip family this gun uses; the rig has a one-handed and a
 *       two-handed pose and the client cannot ask the server which one applies.</li>
 * </ul>
 *
 * <p>{@code SynchedEntityData.defineId} insists on the owning {@link Entity} class and the ids must be
 * created exactly once per class, so each mob keeps one static {@link Keys} instance (see
 * {@link #create}) and forwards everything to it.</p>
 */
public final class GunPose {
    /** The accessors belonging to one entity class. */
    public record Keys(EntityDataAccessor<Boolean> aiming, EntityDataAccessor<Boolean> firing,
                       EntityDataAccessor<Boolean> reloading, EntityDataAccessor<Boolean> pistolClips,
                       EntityDataAccessor<Byte> state) {
        public void defineOn(SynchedEntityData data) {
            data.define(this.aiming, false);
            data.define(this.firing, false);
            data.define(this.reloading, false);
            data.define(this.pistolClips, false);
            data.define(this.state, (byte) GunAiState.IDLE.ordinal());
        }

        public void set(Entity entity, boolean aiming, boolean firing, boolean reloading) {
            entity.getEntityData().set(this.aiming, aiming);
            entity.getEntityData().set(this.firing, firing);
            entity.getEntityData().set(this.reloading, reloading);
        }

        public void setPistolClips(Entity entity, boolean pistolClips) {
            entity.getEntityData().set(this.pistolClips, pistolClips);
        }

        public boolean isAiming(Entity entity) {
            return entity.getEntityData().get(this.aiming);
        }

        public boolean isFiring(Entity entity) {
            return entity.getEntityData().get(this.firing);
        }

        public boolean isReloading(Entity entity) {
            return entity.getEntityData().get(this.reloading);
        }

        public boolean usesPistolClips(Entity entity) {
            return entity.getEntityData().get(this.pistolClips);
        }

        /**
         * Pushes the state machine's own state to the client.
         *
         * <p>Set from {@code GunBrain#transition}, i.e. from the same place that sets the three
         * booleans above, so the client's copy of the state can never disagree with them about which
         * state the mob is in. The client cannot run the brain (it needs the server's world), so this
         * value - not a re-derived guess - is what the renderers branch on.</p>
         */
        public void setState(Entity entity, GunAiState next) {
            entity.getEntityData().set(this.state, (byte) (next == null ? GunAiState.IDLE : next).ordinal());
        }

        /** The synced AI state, or {@link GunAiState#IDLE} if the byte is out of range. */
        public GunAiState state(Entity entity) {
            byte ordinal = entity.getEntityData().get(this.state);
            GunAiState[] values = GunAiState.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : GunAiState.IDLE;
        }
    }

    private GunPose() {
    }

    /** Call once, from a static field of the owning entity class. */
    public static Keys create(Class<? extends Entity> owner) {
        return new Keys(
                SynchedEntityData.defineId(owner, EntityDataSerializers.BOOLEAN),
                SynchedEntityData.defineId(owner, EntityDataSerializers.BOOLEAN),
                SynchedEntityData.defineId(owner, EntityDataSerializers.BOOLEAN),
                SynchedEntityData.defineId(owner, EntityDataSerializers.BOOLEAN),
                SynchedEntityData.defineId(owner, EntityDataSerializers.BYTE));
    }
}
