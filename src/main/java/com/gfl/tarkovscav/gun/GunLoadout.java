package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.entity.ScavTier;
import net.minecraft.resources.ResourceLocation;

/**
 * What a single mob was actually issued: the TaCZ gun, the ammo it eats, and the facts about that
 * gun this mod needs for its own decisions (magazine size, whether it is bolt-action, its rpm).
 *
 * <p>All of it is read out of TaCZ's own index ({@code CommonGunIndex}), never guessed: a custom gun
 * pack therefore behaves correctly without any change here.</p>
 */
public final class GunLoadout {
    private final ScavTier tier;
    private final ResourceLocation gunId;
    private final ResourceLocation ammoId;
    private final String gunType;
    private final int magazineSize;
    private final int roundsPerMinute;
    private final boolean manualAction;

    public GunLoadout(ScavTier tier, ResourceLocation gunId, ResourceLocation ammoId, String gunType,
                      int magazineSize, int roundsPerMinute, boolean manualAction) {
        this.tier = tier;
        this.gunId = gunId;
        this.ammoId = ammoId;
        this.gunType = gunType;
        this.magazineSize = magazineSize;
        this.roundsPerMinute = roundsPerMinute;
        this.manualAction = manualAction;
    }

    public ScavTier tier() {
        return this.tier;
    }

    public ResourceLocation gunId() {
        return this.gunId;
    }

    public ResourceLocation ammoId() {
        return this.ammoId;
    }

    public String gunType() {
        return this.gunType;
    }

    public int magazineSize() {
        return this.magazineSize;
    }

    public int roundsPerMinute() {
        return this.roundsPerMinute;
    }

    /** Bolt-action / pump-action: TaCZ answers {@code NEED_BOLT} between shots. */
    public boolean manualAction() {
        return this.manualAction;
    }

    @Override
    public String toString() {
        return this.tier.id() + "/" + this.gunId + " (" + this.gunType + ", " + this.magazineSize + " rnd, "
                + this.roundsPerMinute + " rpm" + (this.manualAction ? ", bolt" : "") + ")";
    }
}
