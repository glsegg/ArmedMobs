package com.gfl.tarkovscav.gun;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps Lua-scripted guns away from mobs (README 5p) - the structural half of the 2026-09-23 crash.
 *
 * <h2>The crash, and why this is the only kind of fix available</h2>
 * <p>A gun pack can attach Lua to a gun. TaCZ runs that script <b>inside its own tick</b>
 * ({@code ModernKineticGunItem.tickBolt} → {@code LuaClosure.call}), so a script that throws takes the whole
 * server down: the crash report reads
 * {@code org.luaj.vm2.LuaError: hamster_win1894_gun_logic:32 attempt to perform arithmetic __mul on nil and
 * number at ... ModernKineticGunItem.lambda$tickBolt$4}, with {@code Entity Type: tarkovscav:usec_villager}.
 * There is no try/catch we could wrap around another mod's tick, and TaCZ has no global "disable scripts"
 * switch, so the defence has to be <b>"do not give a mob such a gun"</b>.</p>
 *
 * <h2>The judgement, from TaCZ's own index</h2>
 * <p>The accessor used is {@code GunData#getScript()} → {@code ResourceLocation}: the script the gun
 * <b>declares</b> (e.g. {@code tacz:xmag_reload_logic}, or the crashing {@code hamster:win1894_gun_logic}).
 * Both TaCZ <b>1.1.7</b> (the user's) and <b>1.1.8</b> (the dev lib) were checked with {@code javap}, and both
 * also expose {@code CommonGunIndex#getScript()} → {@code org.luaj.vm2.LuaTable} - the <b>compiled</b> script,
 * the authoritative "TaCZ will really run Lua for this gun" signal. That call is deliberately <b>not</b> used:
 * luaj is not a compile-time dependency of this mod (TaCZ is {@code compileOnly}), so referring to the
 * {@code LuaTable} return type would not compile. The declared id carries the namespace, which is what the
 * policy needs; the compiled table would only add a second, redundant signal.</p>
 *
 * <h2>Why the namespace decides, and not "has a script at all"</h2>
 * <p>Measured on the user's own default pack: <b>19 of 47</b> guns declare a script, and every one of them is
 * {@code tacz:*_gun_logic} or {@code tacz:xmag_reload_logic} - guns that have been carried by these mobs for
 * months without a problem. Excluding "anything with a script" would therefore cut the pool by ~40 % for no
 * safety gain. The crash came from a <b>third-party</b> namespace ({@code hamster:}), so the default policy is:
 * <b>a scripted gun is blocked unless its script namespace is trusted</b>
 * ({@code guns.trustedScriptNamespaces}, default {@code ["tacz"]}). Setting that list to {@code []} is the
 * literal "exclude every scripted gun" mode, and it says so in the log.</p>
 *
 * <p>Nothing here is silent: the first time a gun is blocked its id and script are logged once, and the list is
 * available to {@code /tarkovscav test mods} and {@code /tarkovscav gunpool}.</p>
 */
public final class ScriptedGuns {
    /** gun id -> its declared script id, "" for none. Built from TaCZ's index and cached per gun id. */
    private static final Map<ResourceLocation, String> SCRIPT_OF = new ConcurrentHashMap<>();
    /** Ids already reported, so a rebuild cannot spam the log. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    /** The ids currently kept out of the pools, in the order they were first seen. */
    private static final List<String> BLOCKED = new CopyOnWriteArrayList<>();

    private ScriptedGuns() {
    }

    /**
     * The script a gun declares, as {@code namespace:path}, or {@code ""} when it declares none.
     *
     * <p>A gun TaCZ does not know at all answers {@code ""} <b>without being remembered</b>. That is the same
     * lesson as the attachment pool's empty-cache bug in reverse: a gun-pack reload empties TaCZ's index for a
     * moment, and caching that "no script" answer would silently let every scripted gun through for the rest
     * of the session (the runtime guard reads this cache too). The lookup is a single map query, so asking
     * again is cheap; a real "this gun has no script" answer <em>is</em> cached, so the hot path is unchanged.
     * </p>
     */
    public static String scriptOf(@Nullable ResourceLocation gunId) {
        if (gunId == null) {
            return "";
        }
        String cached = SCRIPT_OF.get(gunId);
        if (cached != null) {
            return cached;
        }
        try {
            Optional<CommonGunIndex> index = TimelessAPI.getCommonGunIndex(gunId);
            if (index.isEmpty()) {
                // Not known (yet): do NOT cache. See the comment above.
                return "";
            }
            GunData data = index.get().getGunData();
            ResourceLocation declared = data == null ? null : data.getScript();
            String script = declared == null ? "" : declared.toString();
            SCRIPT_OF.put(gunId, script);
            return script;
        } catch (RuntimeException | LinkageError unavailable) {
            // A TaCZ build without the script accessor: no script information, so nothing is blocked - and
            // again nothing is cached, so the answer is re-asked once the index can answer.
            return "";
        }
    }

    /**
     * True when this gun must not be handed to (or kept on) a mob under the current config.
     *
     * <p>The first blocked id is logged once with its script, so "why is this gun missing from the pool?" is
     * answerable from the log, and the command output carries the whole list.</p>
     */
    public static boolean isBlocked(@Nullable ResourceLocation gunId) {
        if (gunId == null || !Config.EXCLUDE_SCRIPTED_GUNS.get()) {
            return false;
        }
        String script = scriptOf(gunId);
        if (script.isEmpty()) {
            return false;
        }
        String namespace = namespaceOf(script);
        if (Config.trustedScriptNamespaces().contains(namespace)) {
            return false;
        }
        if (LOGGED.add(gunId.toString())) {
            TarkovScav.LOGGER.info("[gunsafety] {} declares the Lua script '{}' (namespace '{}'); it is kept"
                            + " out of the mob pool. A broken script crashes the server inside TaCZ's own tick,"
                            + " which we cannot catch. Trust a namespace with"
                            + " guns.trustedScriptNamespaces, or turn the rule off with"
                            + " guns.excludeScriptedGuns=false. Trusted now: {}",
                    gunId, script, namespace, Config.trustedScriptNamespaces());
            BLOCKED.add(gunId.toString());
        }
        return true;
    }

    /** The namespace of a script id ({@code hamster:win1894_gun_logic} → {@code hamster}). */
    static String namespaceOf(String script) {
        int colon = script.indexOf(':');
        return (colon <= 0 ? script : script.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    /** Every gun id this rule has kept out of the pool, most recent last. */
    public static List<String> blocked() {
        return List.copyOf(BLOCKED);
    }

    /**
     * One line for the commands: how many guns are excluded, the first {@code max} of them, and which
     * namespaces are trusted - so "why is my favourite gun missing?" has an answer in game.
     */
    public static String describe(int max) {
        List<String> ids = blocked();
        if (ids.isEmpty()) {
            return Config.EXCLUDE_SCRIPTED_GUNS.get()
                    ? "none (no scripted gun with an untrusted namespace is in TaCZ's index)"
                    : "none (guns.excludeScriptedGuns = false: Lua-scripted guns are issued again)";
        }
        StringBuilder out = new StringBuilder();
        out.append(ids.size()).append(" gun(s): ");
        out.append(String.join(", ", ids.subList(0, Math.min(max, ids.size()))));
        if (ids.size() > max) {
            out.append(" (+").append(ids.size() - max).append(" more)");
        }
        out.append(" [trusted namespaces: ").append(Config.trustedScriptNamespaces()).append(']');
        return out.toString();
    }

    /** Drops the per-id script cache and the once-only log guards; called on a config reload. */
    public static void invalidate() {
        SCRIPT_OF.clear();
        LOGGED.clear();
        BLOCKED.clear();
    }
}
