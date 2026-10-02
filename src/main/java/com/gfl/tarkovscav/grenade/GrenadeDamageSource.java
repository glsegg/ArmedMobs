package com.gfl.tarkovscav.grenade;

import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Carries grenade identity on the damage call itself, including vanilla terrain explosions. */
public final class GrenadeDamageSource extends DamageSource {
    private final GrenadeKind kind;
    private boolean explosionCancelled;

    public GrenadeDamageSource(Holder<DamageType> type, @Nullable Entity projectile,
                               @Nullable Entity thrower, Vec3 centre, GrenadeKind kind) {
        super(type, projectile, thrower, centre);
        this.kind = kind;
    }

    public GrenadeKind kind() {
        return this.kind;
    }

    void setExplosionCancelled(boolean cancelled) {
        this.explosionCancelled = cancelled;
    }

    boolean explosionCancelled() {
        return this.explosionCancelled;
    }
}
