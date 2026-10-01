package com.gfl.tarkovscav.gun;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The tiny inventory a gun-armed mob carries: matching TaCZ ammunition, and nothing else.
 *
 * <p>It exists because of a hard fact about TaCZ: reloading and firing both go through
 * {@code LivingEntity#getCapability(ForgeCapabilities.ITEM_HANDLER)}. A {@code ServerPlayer} has
 * that capability (its inventory), a vanilla {@code Mob} does not - so a mob would fire whatever
 * was in the magazine and then answer {@code NO_AMMO} for ever. Handing the mob this provider makes
 * TaCZ's own reload path work unchanged. See {@link GunCapabilities} for the attachment.</p>
 */
public class MobAmmoInventory extends SimpleContainer implements ICapabilityProvider {
    private static final String TAG_AMMO = "TarkovScavAmmo";
    /** Enough for several magazines of any gun in the default pack. */
    public static final int SIZE = 9;

    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> new InvWrapper(this));

    public MobAmmoInventory() {
        super(SIZE);
    }

    /** Save reserves separately from vanilla's hand stack, including a completely empty inventory. */
    public void saveTo(CompoundTag tag, GunLoadout loadout) {
        CompoundTag saved = new CompoundTag();
        saved.putString("Gun", loadout.gunId().toString());
        ListTag items = new ListTag();
        for (int slot = 0; slot < getContainerSize(); slot++) {
            ItemStack stack = getItem(slot);
            if (!stack.isEmpty()) {
                CompoundTag item = stack.save(new CompoundTag());
                item.putInt("Slot", slot);
                items.add(item);
            }
        }
        saved.put("Items", items);
        tag.put(TAG_AMMO, saved);
    }

    /** Restore after weapon initialization; replacement guns must keep their own matching ammunition. */
    public void restoreFrom(CompoundTag tag, @Nullable GunLoadout loadout) {
        if (loadout == null) {
            return;
        }
        String gunId;
        ListTag items;
        if (tag.contains(TAG_AMMO, Tag.TAG_COMPOUND)) {
            CompoundTag saved = tag.getCompound(TAG_AMMO);
            if (!saved.contains("Items", Tag.TAG_LIST)) {
                return;
            }
            gunId = saved.getString("Gun");
            items = saved.getList("Items", Tag.TAG_COMPOUND);
        } else if (tag.contains("BlackFoxAmmo", Tag.TAG_LIST)) {
            // The first Black Fox release saved the same slots under its own legacy key.
            gunId = tag.getString("TarkovScavGun");
            items = tag.getList("BlackFoxAmmo", Tag.TAG_COMPOUND);
        } else {
            return; // Old saves never recorded reserves; keep their issued ammunition.
        }
        if (!loadout.gunId().toString().equals(gunId)) {
            return;
        }
        clear();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag item = items.getCompound(i);
            int slot = item.getInt("Slot");
            if (slot >= 0 && slot < getContainerSize()) {
                setItem(slot, ItemStack.of(item));
            }
        }
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return this.itemHandler.cast();
        }
        return LazyOptional.empty();
    }

    /** Drops every stack (called when the mob dies, so its ammo is not deleted with it). */
    public void clear() {
        this.clearContent();
    }
}
