package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * What a dead gun mob leaves behind: the TaCZ gun and its spare ammunition, each on its own
 * configurable chance. Shared by both mobs so their loot tables cannot drift apart.
 *
 * <p>Vanilla's own equipment roll is disabled for the main hand by each mob's
 * {@code getEquipmentDropChance} override, otherwise a second gun could drop from that roll.</p>
 */
public final class GunLoot {
    private GunLoot() {
    }

    public static void dropGunAndAmmo(LivingEntity mob, GunBrain brain, MobAmmoInventory inventory,
                                      DamageSource source, int lootingLevel) {
        if (mob.level().isClientSide) {
            return;
        }

        GunLoadout loadout = brain.loadout();
        float gunChance = Config.GUN_DROP_CHANCE.get().floatValue() + lootingLevel * 0.05F;
        if (loadout != null && mob.getRandom().nextFloat() < gunChance) {
            ItemStack gun = mob.getMainHandItem().copy();
            if (!gun.isEmpty()) {
                mob.spawnAtLocation(gun);
            }
        }

        if (mob.getRandom().nextFloat() < Config.AMMO_DROP_CHANCE.get()) {
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) {
                    mob.spawnAtLocation(stack.copy());
                }
            }
        }
        inventory.clear();
    }
}
