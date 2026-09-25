package com.gfl.tarkovscav.killfeed;

import net.minecraft.network.chat.Component;

/**
 * What killed somebody, when it was not an item in the killer's hand (README 5u).
 *
 * <p>This is an enum rather than a string on purpose: the record travels to the client, and the client is the
 * only side that knows the player's language. So the wire carries a <b>category</b> and the client turns it
 * into text with its own translation; only the two entity names (and, for {@link #ITEM}, the weapon stack) are
 * sent as data. Nothing on the wire is ever a pre-formatted or clickable message.</p>
 */
public enum KillFeedSource {
    /** The killer was holding this item (gun, bow, sword...). The stack travels with the record. */
    ITEM("item"),
    /** Empty hand: "fists". */
    FISTS("fists"),
    /** An explosion, ours or anybody else's (grenades from the next batch land here). */
    EXPLOSION("explosion"),
    FALL("fall"),
    DROWNING("drowning"),
    FIRE("fire"),
    MAGIC("magic"),
    /** Nothing identifiable killed it (cactus, suffocation, the void...). */
    ENVIRONMENT("environment"),
    /** A weapon we could not name: the server WARNs once per id, the client says "unknown weapon". */
    UNKNOWN("unknown");

    private final String id;

    KillFeedSource(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /**
     * The client-side text for a category that has no item.
     *
     * <p>Written as a switch with <b>literal</b> translation keys rather than {@code "..." + id}: the asset
     * gate resolves every key the code mentions, and a key built by concatenation is invisible to it (it caught
     * exactly that). A category added without a row here is a compile error, which is the other reason.</p>
     */
    public Component text() {
        return switch (this) {
            case ITEM -> Component.translatable("tarkovscav.killfeed.weapon.item");
            case FISTS -> Component.translatable("tarkovscav.killfeed.weapon.fists");
            case EXPLOSION -> Component.translatable("tarkovscav.killfeed.weapon.explosion");
            case FALL -> Component.translatable("tarkovscav.killfeed.weapon.fall");
            case DROWNING -> Component.translatable("tarkovscav.killfeed.weapon.drowning");
            case FIRE -> Component.translatable("tarkovscav.killfeed.weapon.fire");
            case MAGIC -> Component.translatable("tarkovscav.killfeed.weapon.magic");
            case ENVIRONMENT -> Component.translatable("tarkovscav.killfeed.weapon.environment");
            case UNKNOWN -> Component.translatable("tarkovscav.killfeed.weapon.unknown");
        };
    }

    /** The clickable-free string form, used by the test command and the gates. */
    public String display() {
        return text().getString();
    }
}
