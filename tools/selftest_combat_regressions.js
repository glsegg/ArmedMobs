// Executes the production health-restoration and gun-equipping methods against small stateful fixtures.
// No Minecraft runtime is needed; unlike a source-pattern gate, assertions check the methods' effects.
// Run: node tools/selftest_combat_regressions.js (JDK 17+ on PATH or JAVA_HOME).
const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');
const root = path.resolve(__dirname, '..');
const javaRoot = path.join(root, 'src/main/java/com/gfl/tarkovscav');

function method(source, name, occurrence = 0) {
  const stripped = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const pattern = new RegExp(`(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>.?]+\\s+${name}\\s*\\([^)]*\\)\\s*\\{`, 'g');
  const match = [...stripped.matchAll(pattern)][occurrence];
  if (!match) throw new Error(`Missing production method ${name}`);
  let end = match.index + match[0].length;
  let depth = 1;
  for (; end < stripped.length && depth; end++) {
    if (stripped[end] === '{') depth++;
    if (stripped[end] === '}') depth--;
  }
  if (depth) throw new Error(`Unbalanced method ${name}`);
  return stripped.slice(match.index, end);
}

const entities = ['ScavEntity', 'GunnerPillagerEntity', 'GunnerVillagerEntity'];
const entityClasses = entities.map(name => {
  const source = fs.readFileSync(path.join(javaRoot, 'entity', `${name}.java`), 'utf8');
  return `static class ${name} extends EntityFixture {
    ${method(source, 'applyTierAttributes')}
    ${method(source, 'readAdditionalSaveData')}
  }`;
}).join('\n');
const brain = fs.readFileSync(path.join(javaRoot, 'gun/GunBrain.java'), 'utf8');
const loot = fs.readFileSync(path.join(javaRoot, 'gun/GunLoot.java'), 'utf8');
const retryField = brain.match(/private\s+(?:int|long)\s+equipRetry\w*\s*;/);
if (!retryField) throw new Error('Missing gun retry state');
const source = `
import java.util.*;
import com.gfl.tarkovscav.block.WeaponRackTaker;
public class CombatRegressionTest {
  static int checks;
  static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
  static class Value {
    double value;
    Value(double value) { this.value = value; }
    Double get() { return value; }
  }
  static class Config {
    static final TierSettings settings = new TierSettings();
    static final Value GUN_DROP_CHANCE = new Value(0.5), AMMO_DROP_CHANCE = new Value(0.5);
    static TierSettings tier(ScavTier tier) { return settings; }
    static class TierSettings {
      Value health = new Value(40), armor = new Value(3);
    }
  }
  enum ScavTier {
    RIFLE;
    static ScavTier byId(String id) { return RIFLE; }
    String id() { return "rifle"; }
  }
  enum Attributes { MAX_HEALTH, ARMOR }
  static class Attribute {
    double value;
    void setBaseValue(double value) { this.value = value; }
  }
  static class CompoundTag {
    float health;
    boolean hasHealth = true, handItems;
    CompoundTag(float health) { this.health = health; }
    String getString(String key) { return ""; }
    boolean contains(String key) { return key.equals("HandItems") && handItems; }
    boolean contains(String key, int type) { return key.equals("Health") && type == 99 && hasHealth; }
  }
  static class Level {
    boolean isClientSide;
    long time;
    long getGameTime() { return time; }
  }
  static class ResourceLocation {
    static final ResourceLocation GUN = new ResourceLocation();
    static ResourceLocation tryParse(String value) { return null; }
  }
  static class VanillaEntity {
    float health;
    void readAdditionalSaveData(CompoundTag tag) { if (tag.hasHealth) health = tag.health; }
  }
  static class EntityFixture extends VanillaEntity {
    static final String TAG_TIER = "tier", TAG_GUN = "gun";
    ScavTier tier = ScavTier.RIFLE;
    Level world = new Level();
    Attribute maxHealth = new Attribute(), armor = new Attribute();
    double forcedHealth = -1;
    int brainLookups;
    Attribute getAttribute(Attributes attribute) { return attribute == Attributes.MAX_HEALTH ? maxHealth : armor; }
    float getHealth() { return health; }
    float getMaxHealth() { return (float) maxHealth.value; }
    void setHealth(float value) { health = Math.max(0, Math.min(value, getMaxHealth())); }
    Level level() { return world; }
    double forcedMaxHealth() { return forcedHealth; }
    double forcedArmorPoints() { return -1; }
    ScavTier forcedSpawnTier() { return null; }
    Brain gunBrain() { brainLookups++; return new Brain(); }
    RandomSource getRandom() { return new RandomSource(); }
  }
  ${entityClasses}
  enum InteractionHand { MAIN_HAND }
  static class RandomSource { float nextFloat() { return 0; } }
  enum WeaponRackArmament { BOW, CROSSBOW, MELEE, TACZ_GUN, NONE;
    static WeaponRackArmament armamentOf(ItemStack stack) { return stack.kind; }
  }
  static class ItemStack {
    static final ItemStack EMPTY = new ItemStack(true);
    boolean empty;
    boolean gun = true;
    int magazine = 3;
    String attachment = "scope";
    ResourceLocation id = ResourceLocation.GUN;
    WeaponRackArmament kind;
    ItemStack(boolean empty) { this.empty = empty; this.kind = empty ? WeaponRackArmament.NONE : WeaponRackArmament.TACZ_GUN; }
    boolean isEmpty() { return empty; }
    ItemStack copy() { ItemStack copy = new ItemStack(empty); copy.kind = kind; copy.magazine = magazine; return copy; }
  }
  static class Name { String getString() { return "fixture"; } }
  static class Mob {
    Level world = new Level();
    ItemStack held = ItemStack.EMPTY;
    Level level() { return world; }
    RandomSource getRandom() { return new RandomSource(); }
    Name getName() { return new Name(); }
    ItemStack getMainHandItem() { return held; }
    void setItemInHand(InteractionHand hand, ItemStack value) { held = value; }
  }
  static class User {
    ScavTier scavTier() { return ScavTier.RIFLE; }
    void setPistolClips(boolean pistol) {}
  }
  static class GunLoadout { ResourceLocation gunId() { return ResourceLocation.GUN; } }
  static class IGun {
    static IGun getIGunOrNull(ItemStack stack) { return stack.isEmpty() || !stack.gun ? null : new IGun(); }
    ResourceLocation getGunId(ItemStack stack) { return stack.id; }
  }
  static class ScriptedGuns {
    static boolean blocked;
    static boolean isBlocked(ResourceLocation id) { return blocked; }
    static String scriptOf(ResourceLocation id) { return "script"; }
  }
  static class GunClips {
    static final String FAMILY_PISTOL = "pistol";
    static String family(GunLoadout loadout) { return "rifle"; }
  }
  static class GunAttachments {
    static void refillToCapacity(ItemStack stack) { stack.magazine = 30; }
  }
  static class GunPool {
    static int rolls;
    static boolean available;
    static GunLoadout rollLoadout(ScavTier tier, RandomSource random) {
      rolls++;
      return available ? new GunLoadout() : null;
    }
    static GunLoadout loadoutFor(ScavTier tier, ResourceLocation id) { return null; }
    static ItemStack buildGun(GunLoadout loadout, RandomSource random, String name) { return new ItemStack(false); }
  }
  static class TarkovScav {
    static final Logger LOGGER = new Logger();
    static class Logger {
      void warn(String format, Object... args) {}
      void info(String format, Object... args) {}
    }
  }
  static class Brain {
    Mob mob = new Mob();
    User user = new User();
    GunLoadout loadout;
    ItemStack gunStack = ItemStack.EMPTY;
    int burstTarget, refills, initializations;
    ${retryField[0]}
    ${method(brain, 'hasGun')}
    ${method(brain, 'ensureEquipped')}
    ${method(brain, 'equip')}
    ${method(brain, 'equipLoadout', 0)}
    ${method(brain, 'equipLoadout', 1)}
    ${method(brain, 'restoreLoadout')}
    ${method(brain, 'applyLoadout', 0)}
    ${method(brain, 'applyLoadout', 1)}
    void refillAmmo(GunLoadout value) { refills++; }
    int burstSize(boolean exposed) { return 3; }
    String name() { return "fixture"; }
    void log(String format, Object... args) {}
    Operator operator() { return new Operator(); }
    class Operator { void initialData() { initializations++; } }
  }
  static class GunBrain extends Brain { GunLoadout loadout() { return this.loadout; } }
  static class DamageSource {}
  static class LivingEntity extends Mob {
    List<ItemStack> drops = new ArrayList<>();
    void spawnAtLocation(ItemStack stack) { drops.add(stack); }
  }
  static class MobAmmoInventory {
    List<ItemStack> stacks = new ArrayList<>(List.of(new ItemStack(false)));
    int getContainerSize() { return stacks.size(); }
    ItemStack getItem(int slot) { return stacks.get(slot); }
    void clear() { stacks.clear(); }
  }
  static class GunLoot {
    ${method(loot, 'dropGunAndAmmo')}
  }
  static void health(EntityFixture entity) throws Exception {
    Config.settings.health.value = 40;
    CompoundTag summon = new CompoundTag(0);
    summon.hasHealth = false;
    entity.health = 20;
    entity.readAdditionalSaveData(summon);
    check(entity.getHealth() == 40, entity.getClass() + " summon NBT without Health starts at full tier health");
    entity.readAdditionalSaveData(new CompoundTag(7));
    check(entity.getHealth() == 7, entity.getClass() + " must preserve wounded health on reload");
    entity.readAdditionalSaveData(new CompoundTag(0));
    check(entity.getHealth() == 0, entity.getClass() + " must not resurrect dead entities");
    Config.settings.health.value = 5;
    entity.readAdditionalSaveData(new CompoundTag(7));
    check(entity.getHealth() == 5, entity.getClass() + " must clamp to a lowered max health");
    Config.settings.health.value = 40;
    var apply = entity.getClass().getDeclaredMethod("applyTierAttributes", boolean.class);
    apply.setAccessible(true);
    apply.invoke(entity, true);
    check(entity.getHealth() == 40, entity.getClass() + " new spawns start at full health");
    if (!(entity instanceof ScavEntity)) {
      entity.forcedHealth = 60;
      entity.readAdditionalSaveData(new CompoundTag(12));
      check(entity.getHealth() == 12 && entity.getMaxHealth() == 60,
        entity.getClass() + " faction health override must preserve wounds");
    }
    WeaponRackTaker.rack.add(entity);
    int previousLookups = entity.brainLookups;
    CompoundTag rackSave = new CompoundTag(3);
    rackSave.handItems = true;
    entity.readAdditionalSaveData(rackSave);
    check(entity.getHealth() == 3 && entity.brainLookups == previousLookups && WeaponRackTaker.lastHandItemsPresent,
      entity.getClass() + " restores health before preserving rack weapon without equipping a gun");
    WeaponRackTaker.rack.remove(entity);
  }
  public static void main(String[] args) throws Exception {
    health(new ScavEntity());
    health(new GunnerPillagerEntity());
    health(new GunnerVillagerEntity());
    GunPool.rolls = 0;
    GunPool.available = false;
    Brain brain = new Brain();
    brain.mob.world.time = 1200;
    check(!brain.ensureEquipped() && GunPool.rolls == 1, "empty pool cannot equip");
    GunPool.available = true;
    brain.mob.world.time = 1299;
    check(!brain.ensureEquipped() && GunPool.rolls == 1, "pool retries must be throttled");
    brain.mob.world.time = 1300;
    check(brain.ensureEquipped() && GunPool.rolls == 2, "retry must recover while combat goal is idle");
    ItemStack rackWeapon = new ItemStack(false);
    brain.mob.held = rackWeapon;
    WeaponRackTaker.rack.add(brain.mob);
    check(!brain.hasGun(), "cached firearm must not suppress rack melee/ranged goal");
    check(!brain.ensureEquipped() && brain.mob.held == rackWeapon && GunPool.rolls == 2,
      "rack weapon must not be replaced by an automatically equipped firearm");
    brain.loadout = null;
    brain.gunStack = ItemStack.EMPTY;
    check(!brain.ensureEquipped() && GunPool.rolls == 2, "rack weapons also block first-time gun equips");
    WeaponRackTaker.rack.remove(brain.mob);
    check(brain.ensureEquipped() && GunPool.rolls == 3, "removing invalid rack metadata restores fallback");
    ItemStack issued = new ItemStack(false);
    check(brain.equipLoadout(new GunLoadout(), issued), "a valid rack firearm is accepted");
    check(brain.gunStack == issued && brain.mob.held == issued && issued.magazine == 3
      && issued.attachment.equals("scope"), "adoption must keep exact item data and cached reference");
    check(brain.initializations == 3 && brain.refills == 3,
      "adoption must initialize the TaCZ operator and matching reserves");
    brain.mob.held = issued;
    brain.restoreLoadout(new GunLoadout());
    check(brain.gunStack == issued && issued.magazine == 3,
      "a saved matching gun must not be regenerated or refilled");
    ItemStack invalid = new ItemStack(false);
    invalid.gun = false;
    check(!brain.equipLoadout(new GunLoadout(), invalid) && brain.gunStack == issued,
      "non-gun adoption must fail without mutating the old loadout");
    ItemStack wrongGun = new ItemStack(false);
    wrongGun.id = new ResourceLocation();
    check(!brain.equipLoadout(new GunLoadout(), wrongGun) && brain.gunStack == issued,
      "mismatched gun IDs must not be adopted");
    ScriptedGuns.blocked = true;
    check(!brain.equipLoadout(new GunLoadout(), new ItemStack(false)) && brain.gunStack == issued,
      "blocked scripted guns must be rejected without mutation");
    ScriptedGuns.blocked = false;
    brain.mob.held = ItemStack.EMPTY;
    brain.restoreLoadout(new GunLoadout());
    check(brain.hasGun() && brain.gunStack != issued && brain.gunStack.magazine == 30,
      "legacy saves without a hand stack still receive a generated gun");
    for (WeaponRackArmament kind : List.of(WeaponRackArmament.BOW, WeaponRackArmament.CROSSBOW, WeaponRackArmament.MELEE)) {
      LivingEntity rackMob = new LivingEntity();
      WeaponRackTaker.rack.add(rackMob);
      rackMob.held = new ItemStack(false);
      rackMob.held.kind = kind;
      GunBrain rackBrain = new GunBrain();
      MobAmmoInventory ammo = new MobAmmoInventory();
      GunLoot.dropGunAndAmmo(rackMob, rackBrain, ammo, new DamageSource(), 0);
      check(rackMob.drops.size() == 1 && rackMob.drops.get(0).kind == kind && ammo.stacks.isEmpty(),
        "restored rack weapon drops even without a firearm loadout, with no gun ammunition");
      rackMob.drops.clear();
      rackBrain.loadout = new GunLoadout();
      GunLoot.dropGunAndAmmo(rackMob, rackBrain, new MobAmmoInventory(), new DamageSource(), 0);
      check(rackMob.drops.size() == 1 && rackMob.drops.get(0).kind == kind,
        "spawn-time cached firearm loadout must not create rack-archer ammunition drops");
      rackMob.drops.clear();
      rackMob.held = ItemStack.EMPTY;
      GunLoot.dropGunAndAmmo(rackMob, rackBrain, new MobAmmoInventory(), new DamageSource(), 0);
      check(rackMob.drops.isEmpty(), "broken or disarmed rack mob drops neither old weapon nor ammunition");
    }
    LivingEntity gunMob = new LivingEntity(); gunMob.held = new ItemStack(false);
    GunBrain gunBrain = new GunBrain(); gunBrain.loadout = new GunLoadout();
    GunLoot.dropGunAndAmmo(gunMob, gunBrain, new MobAmmoInventory(), new DamageSource(), 0);
    check(gunMob.drops.size() == 2, "ordinary gunner still drops its firearm and reserve ammunition");
    System.out.println(checks + " production-method combat regression checks passed");
  }
}
`;
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-combat-test-'));
try {
  const stub = path.join(temp, 'com/gfl/tarkovscav/block');
  fs.mkdirSync(stub, { recursive: true });
  fs.writeFileSync(path.join(stub, 'WeaponRackTaker.java'), `package com.gfl.tarkovscav.block;
public class WeaponRackTaker {
  public static boolean lastHandItemsPresent;
  public static final java.util.Set<Object> rack = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  public static boolean hasRackWeapon(Object entity) { return rack.contains(entity); }
  public static void restoreArmament(Object entity, boolean handItemsPresent) { lastHandItemsPresent = handItemsPresent; }
}`);
  fs.writeFileSync(path.join(temp, 'CombatRegressionTest.java'), source);
  const bin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
  function run(command, args) {
    const result = cp.spawnSync(bin ? path.join(bin, command) : command, args, { encoding: 'utf8' });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(result.stdout + result.stderr);
    if (result.stdout) process.stdout.write(result.stdout);
  }
  run('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', temp,
    path.join(stub, 'WeaponRackTaker.java'), path.join(temp, 'CombatRegressionTest.java')]);
  run('java', ['-cp', temp, 'CombatRegressionTest']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
