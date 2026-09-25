package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gun-modding pool: the "gun-modder" mob - one that spawns with a randomly kitted-out gun (README 5p).
 *
 * <h2>The one rule: TaCZ decides what is legal, we only ask</h2>
 * <p>An attachment is installed only when <b>TaCZ itself</b> says it fits:</p>
 * <ol>
 *   <li>{@code IGun.allowAttachmentType(gun, type)} - the gun has that slot at all;</li>
 *   <li>{@code IGun.allowAttachment(gun, attachment)} - this attachment fits this gun;</li>
 *   <li>and only then {@code IGun.installAttachment(gun, attachment)} - which also puts it in the right slot,
 *       replacing whatever was there (so one slot can never hold two attachments).</li>
 * </ol>
 * <p>Because the legality question is asked of TaCZ rather than reimplemented here, the "0 illegal
 * combinations" claim is true by construction: there is no code path that writes an attachment without
 * {@code allowAttachment} having answered yes.</p>
 *
 * <h2>Where the candidates come from (2026 fix: "no mob ever had mods")</h2>
 * <p>The first version asked {@code BuiltInRegistries.ITEM} for attachment items, once per type, and cached
 * the answer - including an <b>empty</b> one. On a real instance the first scan ran while TaCZ was still
 * scanning its gun packs (the log shows {@code GunPackFinder: Start scanning... Found 8 possible gunpack(s)}
 * a few seconds before), so all six pools were cached empty and <b>every gun stayed bare for the whole
 * session</b>: {@code [mods] 0 attachment(s) of type SCOPE available} at 22:11:31 and every gun after it
 * reporting "no legal attachment ... the gun is left unchanged".</p>
 * <p>Two things changed, and they are the whole fix:</p>
 * <ol>
 *   <li>the candidates are built from <b>TaCZ's own index</b>
 *       ({@code TimelessAPI.getAllCommonAttachmentIndex()}: id + {@code getType()}), turned into ItemStacks by
 *       TaCZ's {@code AttachmentItemBuilder} - so the pool does not depend on the item registry being
 *       populated or on a capability test at scan time. The registry sweep is kept only as a
 *       <b>fallback</b> and as the second diagnostic number;</li>
 *   <li>an <b>empty pool is never cached</b>, and a pool is trusted only while it was built from the index we
 *       can see right now ({@code indexSize()}). TaCZ has no "gun pack reloaded" event in its public API (its
 *       {@code api/event} package only carries gun/attachment runtime events), so the reload is detected by
 *       the index size changing - and {@code /tarkovscav client reload} drops the pools explicitly.</li>
 * </ol>
 *
 * <h2>The magazine, after the mod</h2>
 * <p>An extended magazine changes the gun's capacity, and the capacity is <b>not</b> a number this mod knows:
 * {@code GunPool} builds the gun with {@code loadout.magazineSize()} from TaCZ's own index, so after
 * installing anything the capacity is <b>re-read from the item</b> via {@link #capacityOf} - see the ordering
 * note there. A hard-coded "+10 rounds" is exactly the bug this avoids.</p>
 */
public final class GunAttachments {
    /** Every slot TaCZ can put something in, in the order we roll them (SCOPE first: most visible). */
    private static final AttachmentType[] SLOTS = {
            AttachmentType.SCOPE, AttachmentType.MUZZLE, AttachmentType.STOCK,
            AttachmentType.GRIP, AttachmentType.LASER, AttachmentType.EXTENDED_MAG,
    };

    /** How many random candidates one slot tries before giving up. */
    private static final int PICK_ATTEMPTS = 16;

    /**
     * One type's pool: the ItemStacks to try, and the TaCZ index size they were built from.
     *
     * <p>The size is the reload detector: a pool whose {@code indexSize} no longer matches
     * {@link #indexSize()} is stale and gets rebuilt, which is how a gun pack that finishes loading after the
     * first request is picked up without an event.</p>
     */
    private record Pool(List<ItemStack> candidates, int indexSize) {
    }

    /** attachment type -> the pool built from TaCZ's attachment index. Never holds an empty list. */
    private static final Map<AttachmentType, Pool> BY_TYPE = new ConcurrentHashMap<>();
    /** The last availability count logged per type, so the six INFO lines only appear when it changes. */
    private static final Map<AttachmentType, Integer> LAST_LOGGED = new ConcurrentHashMap<>();
    /** Guns whose refusal has already been logged, so a bad pack cannot spam the log. */
    private static final Map<String, Integer> REFUSED = new ConcurrentHashMap<>();

    private GunAttachments() {
    }

    /**
     * Mods one gun stack. Returns the number of attachments installed.
     *
     * <p>Two rolls happen: with probability {@code mods.fullModChance} the mob is a "gun-modder" and every slot
     * the gun allows is filled; otherwise each slot is filled independently with probability
     * {@code mods.perSlotChance}, and the total stops at {@code mods.maxPerGun}.</p>
     */
    public static int apply(ItemStack gun, RandomSource random, @Nullable String owner) {
        if (!Config.MODS_ENABLED.get() || gun.isEmpty()) {
            return 0;
        }
        IGun igun = IGun.getIGunOrNull(gun);
        if (igun == null) {
            return 0;
        }
        boolean full = random.nextFloat() < Config.MODS_FULL_MOD_CHANCE.get();
        double perSlot = full ? 1.0D : Config.MODS_PER_SLOT_CHANCE.get();
        int max = Math.max(1, Config.MODS_MAX_PER_GUN.get());
        List<String> installed = new ArrayList<>();
        int refused = 0;
        for (AttachmentType type : SLOTS) {
            if (installed.size() >= max) {
                break;
            }
            if (type == AttachmentType.EXTENDED_MAG && !Config.MODS_ALLOW_EXTENDED_MAG.get()) {
                continue;
            }
            if (random.nextDouble() >= perSlot) {
                continue;
            }
            if (!igun.allowAttachmentType(gun, type)) {
                // The gun has no such slot (many pistols have no stock). Not an error: just skip it.
                continue;
            }
            ItemStack candidate = pick(igun, gun, type, random);
            if (candidate.isEmpty()) {
                refused++;
                continue;
            }
            // The gun's capacity BEFORE the install, so the after/before comparison below is real.
            int before = capacityOf(gun);
            igun.installAttachment(gun, candidate);
            int after = capacityOf(gun);
            installed.add(type.name().toLowerCase(java.util.Locale.ROOT));
            if (after != before && Config.LOG_GUN_AI.get()) {
                TarkovScav.LOGGER.info("[mods] {} {} capacity {} -> {} (re-read from the item, not assumed)",
                        owner == null ? "gun" : owner, type.name().toLowerCase(java.util.Locale.ROOT),
                        before, after);
            }
        }
        if (refused > 0) {
            warnRefused(gun, refused);
        }
        if (!installed.isEmpty() && Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[mods] {} kitted: {} ({} attachment(s){})",
                    owner == null ? "mob" : owner, installed, installed.size(),
                    full ? ", full-mod roll" : "");
        }
        return installed.size();
    }

    /**
     * One attachment item of this type that TaCZ says fits this gun.
     *
     * <p>Candidates come from {@link #candidates} (TaCZ's index first), and the fit question is TaCZ's. A few
     * random candidates are tried rather than all of them: a pool can be large (a default pack ships dozens of
     * scopes) and one fit is enough - but enough of them are tried that a gun with a narrow mount still gets a
     * fair chance.</p>
     */
    private static ItemStack pick(IGun igun, ItemStack gun, AttachmentType type, RandomSource random) {
        List<ItemStack> candidates = candidates(type);
        if (candidates.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int attempts = Math.min(candidates.size(), PICK_ATTEMPTS);
        int start = random.nextInt(candidates.size());
        for (int i = 0; i < attempts; i++) {
            ItemStack candidate = candidates.get((start + i) % candidates.size());
            try {
                if (igun.allowAttachment(gun, candidate)) {
                    return candidate.copy();
                }
            } catch (RuntimeException exception) {
                // A third-party attachment whose data is broken must not take the mob's spawn down.
                TarkovScav.LOGGER.warn("[mods] allowAttachment threw for {}: {}",
                        candidate.getItem(), exception.toString());
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * The candidate pool for one type, rebuilt when it is stale.
     *
     * <p><b>An empty result is never cached.</b> That was the 2026 bug: the first request happened while TaCZ
     * was still scanning its gun packs, the scan found nothing, and the empty list was remembered for the
     * whole session - so no mob ever got an attachment and every gun logged "no legal attachment". A pool is
     * now stored only when it is non-empty, and only while the TaCZ index size it was built from still
     * matches, so the next request after a pack finishes loading rebuilds it.</p>
     */
    private static List<ItemStack> candidates(AttachmentType type) {
        int indexSize = indexSize();
        Pool cached = BY_TYPE.get(type);
        if (cached != null && !cached.candidates().isEmpty() && cached.indexSize() == indexSize) {
            return cached.candidates();
        }
        List<ItemStack> fresh = scan(type);
        logAvailability(type, fresh.size());
        if (fresh.isEmpty()) {
            BY_TYPE.remove(type);
        } else {
            BY_TYPE.put(type, new Pool(List.copyOf(fresh), indexSize));
        }
        return fresh;
    }

    /**
     * TaCZ's attachment index size right now, or 0 when it cannot be read.
     *
     * <p>This is the reload detector: TaCZ's public API has no gun-pack-reload event (its {@code api/event}
     * package carries gun/attachment runtime events only - checked with {@code javap} against 1.1.7 and
     * 1.1.8), so "the packs changed under us" is observed as this number changing.</p>
     */
    public static int indexSize() {
        try {
            return TimelessAPI.getAllCommonAttachmentIndex().size();
        } catch (RuntimeException | LinkageError unavailable) {
            // A TaCZ build without the index API, or a half-loaded one: report 0 and let the caller fall back
            // to the registry instead of throwing into a mob's spawn.
            return 0;
        }
    }

    /** The pool for a type: TaCZ's index first, the item registry only as a fallback. */
    private static List<ItemStack> scan(AttachmentType type) {
        List<ItemStack> fromIndex = scanIndex(type);
        if (!fromIndex.isEmpty()) {
            return fromIndex;
        }
        return scanRegistry(type);
    }

    /**
     * The PRIMARY source: every attachment in TaCZ's own index whose type matches, as ItemStacks built by
     * TaCZ's own builder ({@code AttachmentItemBuilder.create().setId(id).build()}).
     */
    static List<ItemStack> scanIndex(AttachmentType type) {
        List<ItemStack> found = new ArrayList<>();
        try {
            for (Map.Entry<ResourceLocation, CommonAttachmentIndex> entry
                    : TimelessAPI.getAllCommonAttachmentIndex()) {
                CommonAttachmentIndex index = entry.getValue();
                if (index == null || index.getType() != type) {
                    continue;
                }
                ItemStack stack = AttachmentItemBuilder.create().setId(entry.getKey()).build();
                if (!stack.isEmpty()) {
                    found.add(stack);
                }
            }
        } catch (RuntimeException | LinkageError failed) {
            TarkovScav.LOGGER.warn("[mods] reading TaCZ's attachment index failed for {} ({}); falling back to"
                    + " the item registry", type, failed.toString());
        }
        return found;
    }

    /**
     * The fallback/diagnostic source: attachment items found in the item registry.
     *
     * <p>It is kept because a TaCZ build whose index is not populated yet can still have the items registered,
     * and because {@code /tarkovscav test mods} prints both numbers - that is what tells "the index is empty"
     * apart from "we looked too early".</p>
     */
    static List<ItemStack> scanRegistry(AttachmentType type) {
        List<ItemStack> found = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            IAttachment attachment = IAttachment.getIAttachmentOrNull(stack);
            if (attachment != null && attachment.getType(stack) == type) {
                found.add(stack);
            }
        }
        return found;
    }

    /** The six availability INFO lines, printed only when the number for that type CHANGES. */
    private static void logAvailability(AttachmentType type, int count) {
        Integer previous = LAST_LOGGED.put(type, count);
        if (previous == null || previous != count) {
            TarkovScav.LOGGER.info("[mods] {} attachment(s) of type {} available (TaCZ index: {})",
                    count, type, indexSize());
        }
    }

    /**
     * One line per slot for {@code /tarkovscav test mods}: the index count, the item-registry count and the
     * number this mod would actually try. Two numbers, one glance, and "is it empty or did we look too early"
     * is answered without a re-run.
     */
    public static List<String> diagnostics() {
        List<String> lines = new ArrayList<>();
        for (AttachmentType type : SLOTS) {
            int indexed = scanIndex(type).size();
            int registry = scanRegistry(type).size();
            lines.add(type.name().toLowerCase(Locale.ROOT) + ": index=" + indexed
                    + " registry-scan=" + registry + " candidates=" + (indexed > 0 ? indexed : registry));
        }
        return lines;
    }

    /** True when TaCZ's attachment index has nothing in it at all (the packs define no attachments). */
    public static boolean indexEmpty() {
        return indexSize() == 0;
    }

    /**
     * The gun's current ammunition capacity, <b>read from the item</b> after any attachment change.
     *
     * <p>Order matters and is the whole point: {@code GunPool.buildGun} sets the magazine from TaCZ's index
     * <em>before</em> this class installs anything, so the recorded {@code before} value is the vanilla
     * magazine and the {@code after} value is the modded one. TaCZ's own accessors are used
     * ({@code getMaxDummyAmmoAmount} when the gun uses dummy ammo, otherwise {@code getCurrentAmmoCount}),
     * so an extended magazine is reflected without this mod knowing what "+10" means.</p>
     */
    public static int capacityOf(ItemStack gun) {
        IGun igun = IGun.getIGunOrNull(gun);
        if (igun == null) {
            return 0;
        }
        if (igun.useDummyAmmo(gun) && igun.hasMaxDummyAmmo(gun)) {
            return igun.getMaxDummyAmmoAmount(gun);
        }
        return igun.getCurrentAmmoCount(gun);
    }

    /**
     * Tops the gun up to its (possibly modded) capacity, using TaCZ's own fields. Called after the mods are
     * installed so a mob does not spawn with an extended magazine that is still half empty - and, more
     * importantly, so the ammo the mob thinks it has matches the ammo the gun thinks it has.
     */
    public static void refillToCapacity(ItemStack gun) {
        IGun igun = IGun.getIGunOrNull(gun);
        if (igun == null) {
            return;
        }
        int capacity = capacityOf(gun);
        if (capacity <= 0) {
            return;
        }
        if (igun.useDummyAmmo(gun)) {
            igun.setDummyAmmoAmount(gun, capacity);
        } else if (igun.getCurrentAmmoCount(gun) < capacity) {
            igun.setCurrentAmmoCount(gun, capacity);
        }
    }

    /** WARNs once per gun id about attachments TaCZ refused, so a broken pack is visible and not noisy. */
    private static void warnRefused(ItemStack gun, int refused) {
        IGun igun = IGun.getIGunOrNull(gun);
        // getGunId is an instance method on the capability, not a static helper.
        ResourceLocation id = igun == null ? null : igun.getGunId(gun);
        String key = String.valueOf(id);
        if (REFUSED.merge(key, refused, Integer::sum) == refused) {
            // Once per gun ID (not per spawn), and with the diagnosis spelled out when the index is empty: a
            // user whose packs ship no attachments should read one clear line, not a wall of them.
            TarkovScav.LOGGER.warn("[mods] no legal attachment of the rolled type(s) for gun {} ({} refusal(s));"
                            + " the gun is left unmodded in those slots.{}", id, refused,
                    indexEmpty()
                            ? " TaCZ's attachment index is EMPTY - your gun packs define no attachments, so"
                                    + " there is nothing to install. Run /tarkovscav test mods to confirm."
                            : " This is not an error unless it happens for every gun - run /tarkovscav test"
                                    + " mods to see the index and registry counts.");
        }
    }

    /** Test/debug hook: how many attachments does this gun currently carry? */
    public static int countOn(ItemStack gun) {
        IGun igun = IGun.getIGunOrNull(gun);
        if (igun == null) {
            return 0;
        }
        int count = 0;
        for (AttachmentType type : SLOTS) {
            if (!igun.getAttachment(gun, type).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** Test/debug hook: which slots are filled, as a readable string. */
    public static String describe(ItemStack gun) {
        IGun igun = IGun.getIGunOrNull(gun);
        if (igun == null) {
            return "not a gun";
        }
        List<String> filled = new ArrayList<>();
        for (AttachmentType type : SLOTS) {
            ResourceLocation id = igun.getAttachmentId(gun, type);
            if (id != null) {
                filled.add(type.name().toLowerCase(java.util.Locale.ROOT) + "=" + id.getPath());
            }
        }
        return "capacity=" + capacityOf(gun) + " attachments=" + (filled.isEmpty() ? "none" : filled);
    }

    /**
     * Drops every cached pool and every once-per-gun log guard.
     *
     * <p>Called from {@code /tarkovscav client reload} (the "re-read everything" hook) and safe to call at any
     * time: the pools are rebuilt on the next request. It is not the reload detector itself - TaCZ exposes no
     * gun-pack-reload event - that is the index-size check in {@link #candidates}.</p>
     */
    public static void invalidate() {
        BY_TYPE.clear();
        LAST_LOGGED.clear();
        REFUSED.clear();
    }
}
