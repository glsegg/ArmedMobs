package com.gfl.tarkovscav.grenade;

import com.gfl.tarkovscav.Config;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The five throwables (README 5v), and the numbers that make them different from each other.
 *
 * <p>Everything here is read from the config at the moment it is used, so a live server can retune a grenade
 * without a restart: the enum names the <em>shape</em> of the effect (a fragment model, a blast, a cloud, a
 * flash) and the config supplies the numbers. That split is what keeps "add a sixth grenade" from touching the
 * explosion code.</p>
 */
public enum GrenadeKind {
    /** Damage is the fragments: a small blast, many rays. */
    FRAG("frag_grenade", "frag"),
    /** Damage is the blast: a big explosion, few rays. */
    HE("he_grenade", "he"),
    /** No damage: a cloud that takes eyes away. */
    SMOKE("smoke_grenade", "smoke"),
    /** Long fuse, long blind. */
    FLASH("flash_grenade", "flash"),
    /** Short fuse, three quarters of the blind (README 5v): not throwable back. */
    FLASH_SHORT("flash_grenade_short", "flash_short");

    private final String itemPath;
    private final String id;

    GrenadeKind(String itemPath, String id) {
        this.itemPath = itemPath;
        this.id = id;
    }

    /** The item id path, e.g. {@code frag_grenade} (the item is {@code tarkovscav:frag_grenade}). */
    public String itemPath() {
        return this.itemPath;
    }

    /** The short name used in the config, the command and the log, e.g. {@code frag}. */
    public String id() {
        return this.id;
    }

    /** The item's translation key (the display name the kill feed shows). */
    public String translationKey() {
        return switch (this) {
            case FRAG -> "item.tarkovscav.frag_grenade";
            case HE -> "item.tarkovscav.he_grenade";
            case SMOKE -> "item.tarkovscav.smoke_grenade";
            case FLASH -> "item.tarkovscav.flash_grenade";
            case FLASH_SHORT -> "item.tarkovscav.flash_grenade_short";
        };
    }

    /**
     * The tooltip key. Literal, not {@code "item.tarkovscav." + itemPath}: the asset gate resolves the keys the
     * code mentions, and a concatenated key is invisible to it (it caught exactly that twice).
     */
    public String tooltipKey() {
        return switch (this) {
            case FRAG -> "item.tarkovscav.frag_grenade.tooltip";
            case HE -> "item.tarkovscav.he_grenade.tooltip";
            case SMOKE -> "item.tarkovscav.smoke_grenade.tooltip";
            case FLASH -> "item.tarkovscav.flash_grenade.tooltip";
            case FLASH_SHORT -> "item.tarkovscav.flash_grenade_short.tooltip";
        };
    }

    public boolean isFlash() {
        return this == FLASH || this == FLASH_SHORT;
    }

    public boolean isSmoke() {
        return this == SMOKE;
    }

    /** Fuse from the moment it leaves the hand, in ticks. */
    public int fuseTicks() {
        return switch (this) {
            case FRAG -> Config.GRENADE_FRAG_FUSE_TICKS.get();
            case HE -> Config.GRENADE_HE_FUSE_TICKS.get();
            case SMOKE -> Config.GRENADE_SMOKE_FUSE_TICKS.get();
            case FLASH -> Config.GRENADE_FLASH_FUSE_TICKS.get();
            case FLASH_SHORT -> Config.GRENADE_FLASH_SHORT_FUSE_TICKS.get();
        };
    }

    /** Vanilla explosion power for the entity damage; 0 means "this kind does not blast". */
    public double blastPower() {
        return switch (this) {
            case FRAG -> 1.0D;
            case HE -> Config.GRENADE_HE_BLAST_POWER.get();
            default -> 0.0D;
        };
    }

    public int fragmentCount() {
        return switch (this) {
            case FRAG -> Config.GRENADE_FRAG_COUNT.get();
            case HE -> Config.GRENADE_HE_FRAG_COUNT.get();
            default -> 0;
        };
    }

    public double fragmentDamage() {
        return switch (this) {
            case FRAG -> Config.GRENADE_FRAG_DAMAGE.get();
            case HE -> Config.GRENADE_HE_FRAG_DAMAGE.get();
            default -> 0.0D;
        };
    }

    /** Fragments only exist on the two lethal kinds, and both share the frag radius. */
    public double fragmentRadius() {
        return Config.GRENADE_FRAG_RADIUS.get();
    }

    /** Flash: how far it reaches. */
    public double flashRadius() {
        return Config.GRENADE_FLASH_RADIUS.get();
    }

    /** Flash: brightness at point blank, 0..1. */
    public double flashIntensity() {
        return Config.GRENADE_FLASH_INTENSITY.get();
    }

    /**
     * Flash: how long a player is blind at point blank. The short-fuse one is a <b>fraction</b> of the standard
     * one ({@code flashShort.blindFactor}, README 5v), not a tick count of its own, so retuning the standard
     * flashbang moves both and the trade between them stays the size it was.
     */
    public int playerBlindTicks() {
        int standard = Config.GRENADE_FLASH_PLAYER_BLIND_TICKS.get();
        return this == FLASH_SHORT ? scaleBlind(standard) : standard;
    }

    /** Flash: how long a mob is blind at point blank (the same fraction of the standard mob duration). */
    public int mobBlindTicks() {
        int standard = Config.GRENADE_FLASH_MOB_BLIND_TICKS.get();
        return this == FLASH_SHORT ? scaleBlind(standard) : standard;
    }

    /** The short-fuse duration: the standard one times {@code flashShort.blindFactor}, never below 10 ticks. */
    private static int scaleBlind(int standard) {
        double factor = Config.GRENADE_FLASH_SHORT_BLIND_FACTOR.get();
        return Math.max(10, (int) Math.round(standard * factor));
    }

    public double smokeRadius() {
        return Config.GRENADE_SMOKE_RADIUS.get();
    }

    public int smokeDurationTicks() {
        return Config.GRENADE_SMOKE_DURATION_TICKS.get();
    }

    /** The kind for a short name (the command and the log), or null. */
    @Nullable
    public static GrenadeKind byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.toLowerCase(Locale.ROOT);
        for (GrenadeKind kind : values()) {
            if (kind.id.equals(wanted) || kind.itemPath.equals(wanted)) {
                return kind;
            }
        }
        return null;
    }

    /** All short names, for the command's suggestions and the README table. */
    public static String ids() {
        StringBuilder out = new StringBuilder();
        for (GrenadeKind kind : values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(kind.id);
        }
        return out.toString();
    }
}
