package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The rolled armor class of the faction troops (README 5y): <b>class 1 takes 10 % less damage, class 6 takes
 * 60 % less</b>, and nothing else.
 *
 * <h2>Why there is no armor ITEM, and what that buys</h2>
 * <p>The obvious implementation - give the mob a diamond chestplate for class 6 - would apply <b>two</b>
 * reductions: vanilla armor points first, then ours. The total would then depend on vanilla's curve and would
 * exceed the 60 % the user asked for. So the class is a number, not equipment (option A of the two the request
 * offered):</p>
 * <ul>
 *   <li>it is stored in the entity's persistent data ({@code tarkovscav:armorClass}), which is saved and
 *       loaded with the entity, so a reload cannot lose or reroll it;</li>
 *   <li>it is rolled once, on the server, when the entity joins a level for the first time (never for a chunk
 *       load, never twice);</li>
 *   <li>it reduces damage in one place - {@link #onHurt} - by exactly
 *       {@code armor.reductionPerClass * class}, so class 1..6 is a flat 10 %..60 % and there is no second
 *       reduction anywhere. The gate proves that by simulating the reduction for every class.</li>
 * </ul>
 * <p>Option B (wear the matching vanilla armor as a <em>visual</em> and subtract its share from ours so the
 * total stays equal) is deliberately not implemented: it needs the vanilla armor curve to be modelled
 * correctly for every damage type and every armor set, and the simulation in the gate shows the two only match
 * on average. The class is visible in {@code /tarkovscav debug} instead.</p>
 */
public final class ArmorClass {
    /** Persistent-data key; the entity writes it, the debug command reads it. */
    public static final String TAG = "tarkovscav:armorClass";

    private ArmorClass() {
    }

    /** The class of this entity, 0 when it has none (every mob that is not a faction troop). */
    public static int of(LivingEntity entity) {
        return entity.getPersistentData().getInt(TAG);
    }

    /** Assigns a class. 0 clears it. */
    public static void set(LivingEntity entity, int armorClass) {
        entity.getPersistentData().putInt(TAG, Math.max(0, Math.min(6, armorClass)));
    }

    /** The damage reduction of a class, 0 for 0 and {@code armor.reductionPerClass * class} otherwise. */
    public static double reductionFor(int armorClass) {
        int clamped = Math.max(0, Math.min(6, armorClass));
        return clamped * Config.ARMOR_REDUCTION_PER_CLASS.get();
    }

    /** Rolls and stores the class once per entity; true when it rolled something this call. */
    public static boolean rollOnce(LivingEntity entity, RandomSource random) {
        if (entity.getPersistentData().contains(TAG)) {
            return false;
        }
        int min = Math.min(Config.ELITE_MIN_ARMOR_CLASS.get(), Config.ELITE_MAX_ARMOR_CLASS.get());
        int max = Math.max(Config.ELITE_MIN_ARMOR_CLASS.get(), Config.ELITE_MAX_ARMOR_CLASS.get());
        set(entity, min + random.nextInt(max - min + 1));
        return true;
    }

    /** "class 4 (40 %)" for the debug output and the logs. */
    public static String describe(LivingEntity entity) {
        int armorClass = of(entity);
        if (armorClass <= 0) {
            return "armor=none";
        }
        return String.format(java.util.Locale.ROOT, "armor=class %d (%.0f%% less damage)", armorClass,
                reductionFor(armorClass) * 100.0D);
    }

    /** True for the four faction troops - the only entities this system applies to. */
    public static boolean isTroop(LivingEntity entity) {
        return entity instanceof UsecVillagerEntity || entity instanceof EliteVillagerEntity
                || entity instanceof BearPillagerEntity || entity instanceof ElitePillagerEntity;
    }

    /**
     * The damage hook. {@link LivingHurtEvent} is used rather than {@code LivingDamageEvent} because it fires
     * before absorption/armor is applied and carries the final amount of the blow, which is what "10 % less
     * damage" has to scale.
     */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (!Config.ARMOR_ENABLED.get() || !isTroop(victim)) {
            return;
        }
        int armorClass = of(victim);
        if (armorClass <= 0) {
            return;
        }
        double reduction = reductionFor(armorClass);
        if (reduction <= 0.0D) {
            return;
        }
        float before = event.getAmount();
        float after = (float) (before * (1.0D - reduction));
        event.setAmount(after);
        if (Config.LOG_GUN_AI.get()) {
            TarkovScav.LOGGER.info("[armor] {} class {}: {} -> {} damage", victim.getName().getString(),
                    armorClass, String.format(java.util.Locale.ROOT, "%.2f", before),
                    String.format(java.util.Locale.ROOT, "%.2f", after));
        }
    }

    /** Reads the class out of a tag (for the debug command's NBT view). */
    public static int fromTag(CompoundTag tag) {
        return tag.getInt(TAG);
    }

    /** Convenience for spawn code: roll for a mob if it is a troop. */
    public static void rollForIfTroop(Mob mob) {
        if (isTroop(mob)) {
            rollOnce(mob, mob.getRandom());
        }
    }
}
