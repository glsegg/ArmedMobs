package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.gun.AccuracyProfile;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * The accuracy profile of the faction troops (README 5y).
 *
 * <p>The request: the USEC/BEAR troops shoot like <b>veterans</b> (0.85) and the elite pair like the
 * <b>elite</b> (0.90), both above the plain scav/villager's rookie 0.75. The profile is a property of the mob
 * here rather than another branch inside {@link AccuracyProfile}, so adding a troop later is one line - and the
 * gate can assert the mapping (and the caps it resolves to) without a client.</p>
 */
public final class FactionTierProfile {
    private FactionTierProfile() {
    }

    /** The profile override for a mob, or null when the normal by-type rule should decide. */
    @Nullable
    public static AccuracyProfile.Profile profileFor(Mob mob) {
        if (mob instanceof EliteVillagerEntity || mob instanceof ElitePillagerEntity) {
            return AccuracyProfile.Profile.ELITE;
        }
        if (mob instanceof UsecVillagerEntity || mob instanceof BearPillagerEntity) {
            return AccuracyProfile.Profile.VETERAN;
        }
        return null;
    }

    /** The id of the override for the debug output, or "by-type". */
    public static String describe(Mob mob) {
        AccuracyProfile.Profile profile = profileFor(mob);
        return profile == null ? "by-type" : "troop:" + profile.id();
    }
}
