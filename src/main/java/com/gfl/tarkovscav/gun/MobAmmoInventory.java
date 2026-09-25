package com.gfl.tarkovscav.gun;

import net.minecraft.core.Direction;
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
    /** Enough for several magazines of any gun in the default pack. */
    public static final int SIZE = 9;

    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> new InvWrapper(this));

    public MobAmmoInventory() {
        super(SIZE);
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
