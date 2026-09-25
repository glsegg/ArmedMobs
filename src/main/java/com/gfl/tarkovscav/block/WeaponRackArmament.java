package com.gfl.tarkovscav.block;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.tacz.guns.api.item.IGun;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the weapon rack accepts, and what a mob does with the thing it took (README 5n).
 *
 * <h2>"Only weapons, or anything?" - and the answer is a tag, not a guess</h2>
 * <p>The rack accepts a stack when it is one of:</p>
 * <ul>
 *   <li>a <b>TaCZ gun</b> - {@code IGun.getIGunOrNull(stack) != null}, i.e. TaCZ's own item capability
 *       answers, so this covers every gun of every gun pack without naming one;</li>
 *   <li>a <b>bow</b> or <b>crossbow</b> - {@code Items.BOW} / {@code Items.CROSSBOW} (1.20.1 has no
 *       {@code minecraft:bows} item tag; the two items are the whole set);</li>
 *   <li>a <b>sword</b> or <b>axe</b> - {@code ItemTags.SWORDS} / {@code ItemTags.AXES}, i.e. the vanilla
 *       {@code minecraft:swords} and {@code minecraft:axes} tags, so a modded sword is accepted without
 *       this class knowing it exists.</li>
 * </ul>
 * <p>{@code rack.acceptsAnyItem = true} drops the predicate and lets anything on the rack - useful when a
 * pack wants its own convention. An item that gets that far and is still not one of the five kinds above
 * is <b>not</b> silently ignored: {@link #armamentOf} names it, and the taker logs it once per item id
 * (see {@link #warnUnsupported}).</p>
 */
public enum WeaponRackArmament {
    /** A TaCZ gun: the converted mob fights with the shared {@code GunBrain} path. */
    TACZ_GUN("tacz_gun"),
    /** A bow: the converted mob shoots arrows from range. */
    BOW("bow"),
    /** A crossbow: same ranged path, but drawn longer (see ArmedRangedGoal). */
    CROSSBOW("crossbow"),
    /** A sword or an axe: the converted mob closes in and melees. */
    MELEE("melee"),
    /**
     * A throwable (README 5v): <b>not</b> a weapon for an unarmed mob - it never converts anybody. It is what an
     * <em>already armed</em> unit comes back for, and the rack treats it on its own path
     * ({@code WeaponRackTaker}), which is the whole reason {@link #usable()} is false for it: "usable" means
     * "a mob can be armed with this", and a grenade does not arm anybody.
     */
    THROWABLE("throwable"),
    /** Anything else: refused at placement, and named + WARNed if a pack forced it onto the rack. */
    UNSUPPORTED("unsupported");

    private final String id;

    /** Item ids already warned about, so a full rack cannot spam the log once per tick. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    WeaponRackArmament(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /** True when a mob can actually be ARMED with it. A throwable cannot arm anybody (see THROWABLE). */
    public boolean usable() {
        return this != UNSUPPORTED && this != THROWABLE;
    }

    /** True when an already armed unit may come back for it (README 5v). */
    public boolean resupply() {
        return this == THROWABLE;
    }

    /** The one-line list the command and the refusal message print; keeps the docs and the code in step. */
    public static String acceptedList() {
        return "a TaCZ gun (IGun), a bow, a crossbow, an item in #minecraft:swords / #minecraft:axes,"
                + " or a throwable (a grenade of this mod)";
    }

    /** Classifies a stack. Never null; an empty stack is UNSUPPORTED. */
    public static WeaponRackArmament armamentOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return UNSUPPORTED;
        }
        if (IGun.getIGunOrNull(stack) != null) {
            return TACZ_GUN;
        }
        if (stack.getItem() instanceof com.gfl.tarkovscav.grenade.GrenadeItem) {
            return THROWABLE;
        }
        if (stack.is(Items.BOW)) {
            return BOW;
        }
        if (stack.is(Items.CROSSBOW)) {
            return CROSSBOW;
        }
        if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) {
            return MELEE;
        }
        return UNSUPPORTED;
    }

    /** Whether the rack lets this stack on at all. {@code acceptsAnyItem} is the escape hatch. */
    public static boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // A throwable is accepted outright (it is ours, and it is what the resupply path needs); anything else
        // has to be a weapon a mob can be armed with, unless the escape hatch is open.
        return Config.RACK_ACCEPTS_ANY_ITEM.get() || armamentOf(stack).usable() || armamentOf(stack).resupply();
    }

    /**
     * WARNs once per item id about a stack that got onto the rack but has no combat path. Called by the
     * taker when {@code acceptsAnyItem} let it through, so the operator is told by name instead of
     * wondering why the rack did nothing.
     */
    public static void warnUnsupported(ItemStack stack, @Nullable String context) {
        String id = stack.getItem().builtInRegistryHolder().key().location().toString();
        if (WARNED.add(id)) {
            TarkovScav.LOGGER.warn("[rack] unsupported item {} ({}) - {}. It is left on the rack; a mob"
                            + " cannot use it. Accepted: {}", id, context == null ? "no context" : context,
                    acceptedList(), stack.getHoverName().getString());
        }
    }
}
