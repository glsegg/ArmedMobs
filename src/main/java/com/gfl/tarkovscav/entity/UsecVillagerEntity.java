package com.gfl.tarkovscav.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The USEC villager (README 5y): a gunner <b>villager</b> of the
 * <b>usec</b> voice, with 40 health and a rolled armor class.
 *
 * <p>The parent is {@link GunnerVillagerEntity} and not the pillager one, and that is load-bearing: the
 * villager family is registered with {@code GunnerVillagerRenderer} in ClientSetup, so an entity of this name
 * that extended the pillager would be drawn by the villager renderer and die with a
 * {@code ClassCastException}. Being a {@code Villager} is also what gives it the vanilla profession in its
 * name, the village faction and the villager sounds - i.e. the "村民" half of "部队村民".</p>
 *
 * <p>Everything else is inherited on purpose: the model, the renderer, the arm pose, the gun brain, the cover
 * and reload tactics, the accuracy machinery, the faction layer, the weapon rack and the kill feed all treat
 * this mob as what it is (a villager with a gun). What this class
 * adds is exactly three things:</p>
 * <ul>
 *   <li><b>40 health</b> - {@link #forcedMaxHealth()} replaces the per-tier health the base class would
 *       apply;</li>
 *   <li><b>a rolled armor class</b> - {@link ArmorClass} rolls 1..6 on spawn, stores it in NBT and reduces
 *       damage by 10 % per class (the mob wears no armor item, so nothing reduces it twice);</li>
 *   <li><b>its own voice pool</b> - {@link com.gfl.tarkovscav.voice.VoicePools} maps this entity to the
 *       {@code usec} pool family, so it never speaks another faction's lines.</li>
 * </ul>
 *
 * <p>Accuracy: veteran (0.85) (see {@link FactionTierProfile}).</p>
 */
public class UsecVillagerEntity extends GunnerVillagerEntity {
    public UsecVillagerEntity(EntityType<? extends UsecVillagerEntity> type, Level level) {
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
     * past 60 % (see {@link GunnerVillagerEntity#forcedArmorPoints()}).
     */
    @Override
    protected double forcedArmorPoints() {
        return 0.0D;
    }

    /** The voice family this mob uses (README 5y). */
    @Override
    public String voiceFamily() {
        return "usec";
    }
}
