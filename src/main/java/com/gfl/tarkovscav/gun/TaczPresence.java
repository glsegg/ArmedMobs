package com.gfl.tarkovscav.gun;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * The one place that answers "is TaCZ here?" and "is this stack one of its guns?" - and the only place whose
 * callers may use it without risking a {@link NoClassDefFoundError}.
 *
 * <h2>Why the mod can now load without TaCZ</h2>
 * <p>{@code mods.toml} used to declare {@code tacz} as {@code mandatory=true}, so a pack without it was refused
 * at launch. It is optional now, and the mod falls back to bows and crossbows (see
 * {@link FallbackEquipment} and README 5ac), which means every TaCZ reference has to survive the class being
 * absent. Java resolves a referenced class <b>lazily</b>, at the first execution of the instruction that
 * mentions it, so the rule is not "no TaCZ types anywhere" - it is:</p>
 *
 * <ul>
 *   <li>a method that mentions a TaCZ type must never be <b>executed</b> without TaCZ, and</li>
 *   <li>a class with a TaCZ-typed <b>field initialiser or static initialiser</b> must never be <b>loaded</b>
 *       without TaCZ, because that runs during class preparation, before any guard inside a method can help.
 *       {@code GunAttachments} is exactly that case ({@code private static final AttachmentType[] SLOTS}), so
 *       it may only be reached from the equipment path that TaCZ presence already guards.</li>
 * </ul>
 *
 * <p>That is why this class exists rather than a bare {@code ModList.get().isLoaded("tacz")} at each site: the
 * check and the {@code IGun} call have to sit in <b>one</b> method, with the check first, so that a caller like
 * a renderer (which runs every frame) can ask "is this a gun" through a single call that short-circuits before
 * the TaCZ class is ever touched.</p>
 *
 * <h2>Cost</h2>
 * <p>The loaded flag is cached: {@code ModList} cannot change after startup. {@code isGun} is a single cached
 * boolean plus one capability lookup on the TaCZ side - the same work the call sites did before this class
 * existed, minus the crash when TaCZ is missing.</p>
 */
public final class TaczPresence {
    /** Null until first asked; TaCZ either loaded at startup or it never will. */
    private static Boolean loaded;

    private TaczPresence() {
    }

    /** True when TaCZ is on the mod list. Safe to call before the mod list exists (that is "not loaded"). */
    public static boolean loaded() {
        if (loaded == null) {
            ModList modList = ModList.get();
            loaded = modList != null && modList.isLoaded("tacz");
        }
        return loaded;
    }

    /**
     * True when this stack is one of TaCZ's guns.
     *
     * <p>The {@code loaded()} test is deliberately first and this is the ONLY method in the mod that may be
     * called without knowing whether TaCZ is present: {@code &&} short-circuits, so the {@code IGun} reference
     * in the second half is never resolved when TaCZ is absent.</p>
     */
    public static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && loaded()
                && com.tacz.guns.api.item.IGun.getIGunOrNull(stack) != null;
    }

    /** One line for the debug output and the startup log: which weapon family this install will use. */
    public static String describe() {
        return loaded()
                ? "TaCZ is loaded: units are issued firearms"
                : "TaCZ is NOT loaded: units fall back to bows/crossbows (README 5ac)";
    }
}
