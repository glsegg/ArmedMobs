// Scratch: the four faction-troop classes, the accuracy tier table, the voice pools and the wiring.
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/';
const JAVA = R + 'src/main/java/com/gfl/tarkovscav/';
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, t) => fs.writeFileSync(p, t);
let misses = 0;
const sub = (file, from, to) => {
  const text = read(file);
  if (!text.includes(from)) {
    console.log('MISS in', file.split('/').pop(), ':', from.slice(0, 60));
    misses++;
    return;
  }
  write(file, text.replace(from, to));
};

// ---------------------------------------------------------------- the four entity classes -------------
const villagers = [
  ['UsecVillagerEntity', 'usec_villager', 'USEC villager', 'UsEC', 'usec'],
  ['EliteVillagerEntity', 'elite_villager', 'elite villager', 'Elite', 'elite'],
];
const pillagers = [
  ['BearPillagerEntity', 'bear_pillager', 'BEAR pillager', 'BeAR', 'bear'],
  ['ElitePillagerEntity', 'elite_pillager', 'elite pillager', 'Elite', 'elite'],
];
const template = (cls, id, label, gunner, faction, poolFamily, health, tier) => `package com.gfl.tarkovscav.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The ${label} (README 5y): a ${gunner === 'villager' ? 'gunner villager' : 'gunner pillager'} of the
 * <b>${poolFamily}</b> voice, with 40 health and a rolled armor class.
 *
 * <p>Everything else is inherited on purpose: the model, the renderer, the arm pose, the gun brain, the cover
 * and reload tactics, the accuracy machinery, the faction layer, the weapon rack and the kill feed all treat
 * this mob as what it is (a ${gunner === 'villager' ? 'villager' : 'pillager'} with a gun). What this class
 * adds is exactly three things:</p>
 * <ul>
 *   <li><b>40 health</b> - {@link #forcedMaxHealth()} replaces the per-tier health the base class would
 *       apply;</li>
 *   <li><b>a rolled armor class</b> - {@link ArmorClass} rolls 1..6 on spawn, stores it in NBT and reduces
 *       damage by 10 % per class (the mob wears no armor item, so nothing reduces it twice);</li>
 *   <li><b>its own voice pool</b> - {@link com.gfl.tarkovscav.voice.VoicePools} maps this entity to the
 *       {@code ${poolFamily}} pool family, so it never speaks another faction's lines.</li>
 * </ul>
 *
 * <p>Accuracy: ${tier} (see {@link FactionTierProfile}).</p>
 */
public class ${cls} extends ${gunner === 'villager' ? 'GunnerVillagerEntity' : 'GunnerPillagerEntity'} {
    public ${cls}(EntityType<? extends ${cls}> type, Level level) {
        super(type, level);
    }

    /** 40 health, as asked for (README 5y). */
    @Override
    protected double forcedMaxHealth() {
        return ${health};
    }

    /** The voice family this mob uses (README 5y). */
    @Override
    public String voiceFamily() {
        return "${poolFamily}";
    }
}
`;
for (const [cls, id, label, gunner, family] of [...villagers.map((v) => [...v, 'villager', 40, 'veteran']),
  ...pillagers.map((p) => [...p, 'pillager', 40, 'elite'])]) {
  const tier = cls.startsWith('Elite') ? 'elite (0.90)' : 'veteran (0.85)';
  write(JAVA + 'entity/' + cls + '.java', template(cls, id, label, gunner, family, family, 40, tier));
}

// ---------------------------------------------------------------- the base hooks ----------------------
sub(JAVA + 'entity/GunnerPillagerEntity.java',
  '    private void applyTierAttributes() {',
  `    /**
     * The health this mob always has, or a negative number to use the tier's (README 5y). The faction troops
     * override it with 40; everything else keeps the per-tier value.
     */
    protected double forcedMaxHealth() {
        return -1.0D;
    }

    /** The voice pool family this mob speaks (README 5y); "shared" is the original pool set. */
    public String voiceFamily() {
        return "shared";
    }

    private void applyTierAttributes() {`);
sub(JAVA + 'entity/GunnerPillagerEntity.java',
  `            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(settings.health.get());
        }
        this.setHealth(settings.health.get().floatValue());`,
  `            this.getAttribute(Attributes.MAX_HEALTH)
                    .setBaseValue(forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get());
        }
        this.setHealth((float) (forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get()));`);
sub(JAVA + 'entity/GunnerVillagerEntity.java',
  '    private void applyTierAttributes() {',
  `    /** See GunnerPillagerEntity#forcedMaxHealth: the faction troops pin their health to 40 (README 5y). */
    protected double forcedMaxHealth() {
        return -1.0D;
    }

    /** The voice pool family this mob speaks (README 5y); "shared" is the original pool set. */
    public String voiceFamily() {
        return "shared";
    }

    private void applyTierAttributes() {`);
sub(JAVA + 'entity/GunnerVillagerEntity.java',
  `            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(settings.health.get());
        }
        this.setHealth(settings.health.get().floatValue());`,
  `            this.getAttribute(Attributes.MAX_HEALTH)
                    .setBaseValue(forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get());
        }
        this.setHealth((float) (forcedMaxHealth() > 0.0D ? forcedMaxHealth() : settings.health.get()));`);

// ---------------------------------------------------------------- wiring in TarkovScav ----------------
sub(JAVA + 'TarkovScav.java',
  '        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.grenade.GrenadeEvents.class);',
  `        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.grenade.GrenadeEvents.class);
        // The faction troops (README 5y): the rolled armor class and its damage reduction.
        MinecraftForge.EVENT_BUS.register(com.gfl.tarkovscav.entity.ArmorClass.class);
        // Their armor is rolled once, when they first join a level (never on a chunk load).
        MinecraftForge.EVENT_BUS.addListener(com.gfl.tarkovscav.entity.ArmorClassSpawn::onJoinLevel);`);

// ---------------------------------------------------------------- MobVoice: per-entity pools ----------
for (const category of ['CHATTER', 'CONTACT', 'IDLE', 'GRENADE', 'DEATH']) {
  const lower = category.toLowerCase();
  sub(JAVA + 'voice/MobVoice.java',
    `say(level, ModSounds.${category}, "${lower}")`,
    `say(level, com.gfl.tarkovscav.voice.VoicePools.pool(this.mob, ModSounds.${category}, "${lower}"), "${lower}")`);
}
console.log('done, misses =', misses);
