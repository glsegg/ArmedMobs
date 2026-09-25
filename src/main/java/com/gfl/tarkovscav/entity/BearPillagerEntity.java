package com.gfl.tarkovscav.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The BEAR pillager (README 5y): a gunner pillager of the
 * <b>bear</b> voice, with 40 health and a rolled armor class.
 *
 * <p>Everything else is inherited on purpose: the model, the renderer, the arm pose, the gun brain, the cover
 * and reload tactics, the accuracy machinery, the faction layer, the weapon rack and the kill feed all treat
 * this mob as what it is (a pillager with a gun). What this class
 * adds is exactly three things:</p>
 * <ul>
 *   <li><b>40 health</b> - {@link #forcedMaxHealth()} replaces the per-tier health the base class would
 *       apply;</li>
 *   <li><b>a rolled armor class</b> - {@link ArmorClass} rolls 1..6 on spawn, stores it in NBT and reduces
 *       damage by 10 % per class (the mob wears no armor item, so nothing reduces it twice);</li>
 *   <li><b>its own voice pool</b> - {@link com.gfl.tarkovscav.voice.VoicePools} maps this entity to the
 *       {@code bear} pool family, so it never speaks another faction's lines.</li>
 * </ul>
 *
 * <p>Accuracy: veteran (0.85) (see {@link FactionTierProfile}).</p>
 */
public class BearPillagerEntity extends GunnerPillagerEntity {
    public BearPillagerEntity(EntityType<? extends BearPillagerEntity> type, Level level) {
        super(type, level);
    }

    /** 40 health, as asked for (README 5y). */
    @Override
    protected double forcedMaxHealth() {
        return 40;
    }

    /**
     * Zero vanilla armour points (README 5y). The rolled armor class is the ONLY reduction this mob gets, so
     * class 6 is exactly 60 %: a tier's armour points would absorb part of the blow first and push the total
     * past 60 % (see {@link GunnerPillagerEntity#forcedArmorPoints()}).
     */
    @Override
    protected double forcedArmorPoints() {
        return 0.0D;
    }

    /** The voice family this mob uses (README 5y). */
    @Override
    public String voiceFamily() {
        return "bear";
    }
}
