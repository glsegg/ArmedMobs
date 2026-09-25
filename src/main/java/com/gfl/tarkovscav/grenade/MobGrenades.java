package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;

import java.util.Locale;

/**
 * A mob's grenade pouch (README 5v), stored in its persistent data exactly like {@code MobAmmoInventory} stores
 * ammo.
 *
 * <p>One counter per kind, keyed by {@link GrenadeKind#id()}, so "I picked up two frags and a flashbang" is a
 * real state and the mob throws what it actually collected rather than a generic "grenade". Everything is
 * server-side and saved with the entity, so a reload does not refill anybody.</p>
 */
public final class MobGrenades {
    private static final String TAG = "tarkovscav:grenades";

    private MobGrenades() {
    }

    private static CompoundTag pouch(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(TAG)) {
            data.put(TAG, new CompoundTag());
        }
        return data.getCompound(TAG);
    }

    public static int count(Mob mob, GrenadeKind kind) {
        return pouch(mob).getInt(kind.id());
    }

    /** How many it is carrying altogether. */
    public static int total(Mob mob) {
        int sum = 0;
        for (GrenadeKind kind : GrenadeKind.values()) {
            sum += count(mob, kind);
        }
        return sum;
    }

    /** True when it still has room for another one. */
    public static boolean hasRoom(Mob mob) {
        return total(mob) < Config.MOB_GRENADE_MAX_PER_MOB.get();
    }

    /** Adds one and returns whether it fitted. */
    public static boolean add(Mob mob, GrenadeKind kind) {
        if (!hasRoom(mob)) {
            return false;
        }
        CompoundTag pouch = pouch(mob);
        pouch.putInt(kind.id(), pouch.getInt(kind.id()) + 1);
        return true;
    }

    /** Takes one of that kind, or returns false when there is none. */
    public static boolean take(Mob mob, GrenadeKind kind) {
        CompoundTag pouch = pouch(mob);
        int have = pouch.getInt(kind.id());
        if (have <= 0) {
            return false;
        }
        pouch.putInt(kind.id(), have - 1);
        return true;
    }

    /** The kind it will throw next: the one it has most of, or null when the pouch is empty. */
    public static GrenadeKind next(Mob mob) {
        GrenadeKind best = null;
        int bestCount = 0;
        for (GrenadeKind kind : GrenadeKind.values()) {
            int have = count(mob, kind);
            if (have > bestCount) {
                bestCount = have;
                best = kind;
            }
        }
        return best;
    }

    /** "frag x2, flash x1" for the log and the test command. */
    public static String describe(Mob mob) {
        StringBuilder out = new StringBuilder();
        for (GrenadeKind kind : GrenadeKind.values()) {
            int have = count(mob, kind);
            if (have > 0) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(kind.id()).append(" x").append(have);
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** Fills the pouch up to the configured cap, used by the test command and the resupply path. */
    public static void fill(Mob mob, GrenadeKind kind) {
        while (add(mob, kind)) {
            // keep adding until full
        }
    }

    static String logLine(Mob mob) {
        return String.format(Locale.ROOT, "%s (%d/%d)", describe(mob), total(mob),
                Config.MOB_GRENADE_MAX_PER_MOB.get());
    }
}
