// Execute the production rack transaction, pickup selection and restore methods against stateful fixtures.
// Run with JDK 17+: node tools/selftest_rack_transactions.js
const fs = require('fs');
const path = require('path');
const os = require('os');
const cp = require('child_process');
const root = path.join(__dirname, '../src/main/java/com/gfl/tarkovscav/block');
function method(file, name) {
  const source = fs.readFileSync(path.join(root, file), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const match = new RegExp(`(?:public|private)\\s+static\\s+[\\w.<>?]+\\s+${name}\\s*\\([^)]*\\)\\s*\\{`).exec(source);
  if (!match) throw new Error(`Missing ${name}`);
  let end = match.index + match[0].length, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end).replace(/net\.minecraft\.world\.entity\.item\.ItemEntity/g, 'ItemEntity');
}
const java = `import java.util.*;
public class RackTransactionTest {
  static final String TAG_RACK_WEAPON = "rack";
  static int checks;
  static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  enum WeaponRackArmament { BOW, CROSSBOW, MELEE, TACZ_GUN, NONE;
    static WeaponRackArmament armamentOf(ItemStack stack) { return stack.kind; }
    static boolean accepts(ItemStack stack) { return !stack.isEmpty() && stack.kind != NONE; }
    String id() { return name(); }
  }
  static class ItemStack {
    static final ItemStack EMPTY = new ItemStack(WeaponRackArmament.NONE, 0);
    WeaponRackArmament kind; int damage;
    ItemStack(WeaponRackArmament kind, int damage) { this.kind = kind; this.damage = damage; }
    static ItemStack of(CompoundTag tag) { return tag.snapshot == null ? EMPTY : tag.snapshot; }
    boolean isEmpty() { return kind == WeaponRackArmament.NONE; }
    Name getHoverName() { return new Name(); }
  }
  static class Name { String getString() { return "fixture"; } }
  static class CompoundTag {
    ItemStack snapshot; boolean present;
    boolean contains(String key) { return present; }
    CompoundTag getCompound(String key) { return this; }
    void remove(String key) { present = false; }
  }
  enum InteractionHand { MAIN_HAND }
  enum MobSpawnType { CONVERSION }
  static class BlockPos {
    int getX() { return 0; } int getY() { return 0; } int getZ() { return 0; }
    String toShortString() { return "0,0,0"; }
  }
  static class Mob {
    boolean discarded; ItemStack held = ItemStack.EMPTY; CompoundTag data = new CompoundTag();
    double getX() { return 0; } double getY() { return 0; } double getZ() { return 0; }
    float getYRot() { return 0; } float getXRot() { return 0; } float getYHeadRot() { return 0; }
    void moveTo(double x, double y, double z, float yaw, float pitch) {}
    void setYHeadRot(float yaw) {}
    void finalizeSpawn(ServerLevel level, Object difficulty, MobSpawnType type, Object a, Object b) {}
    void setPersistenceRequired() {}
    void discard() { discarded = true; }
    Name getName() { return new Name(); }
    BlockPos blockPosition() { return new BlockPos(); }
    ItemStack getMainHandItem() { return held; }
    CompoundTag getPersistentData() { return data; }
    void setItemInHand(InteractionHand hand, ItemStack stack) { held = stack; }
  }
  static class Villager extends Mob {}
  static class EntityType<T extends Mob> {
    Mob last;
    Mob create(ServerLevel level) { return last = new Mob(); }
  }
  static class Registration {
    EntityType<Mob> type = new EntityType<>();
    EntityType<Mob> get() { return type; }
  }
  static class ModEntities {
    static final Registration GUNNER_VILLAGER = new Registration(), GUNNER_PILLAGER = new Registration();
  }
  static class BuiltInRegistries {
    static final BuiltInRegistries ENTITY_TYPE = new BuiltInRegistries();
    Object getKey(Object type) { return type; }
  }
  static class Level {
    List<ItemEntity> drops = new ArrayList<>();
    List<ItemEntity> getEntitiesOfClass(Class<ItemEntity> type, Object box, java.util.function.Predicate<ItemEntity> filter) {
      return drops.stream().filter(filter).toList();
    }
  }
  static class ServerLevel extends Level {
    boolean acceptsSpawn; int attempts;
    boolean addFreshEntity(Mob mob) { attempts++; return acceptsSpawn; }
    Object getCurrentDifficultyAt(BlockPos pos) { return pos; }
  }
  static class ItemEntity {
    int id; double distance; boolean removed; ItemStack item;
    ItemEntity(int id, double distance, ItemStack item) { this.id = id; this.distance = distance; this.item = item; }
    boolean isRemoved() { return removed; } ItemStack getItem() { return item; }
    int getId() { return id; }
    double distanceToSqr(double x, double y, double z) { return distance; }
  }
  static Object absorbBox(BlockPos pos) { return pos; }
  static class WeaponRackBlockEntity {
    ItemStack stored; boolean infinite;
    WeaponRackBlockEntity(ItemStack stack, boolean infinite) { stored = stack; this.infinite = infinite; }
    ItemStack claim() { ItemStack taken = stored; if (!infinite) stored = ItemStack.EMPTY; return taken; }
    boolean infinite() { return infinite; }
    void put(ItemStack stack) { stored = stack; }
    void setTakeCooldown(int ticks) {}
    BlockPos getBlockPos() { return new BlockPos(); }
  }
  static class Config {
    static final Config RACK_TAKE_COOLDOWN_TICKS = new Config(); int get() { return 10; }
  }
  static class TarkovScav {
    static final TarkovScav LOGGER = new TarkovScav();
    void info(String format, Object... args) {} void warn(String format, Object... args) {}
  }
  static boolean armamentAccepted = true;
  static boolean applyArmament(Mob armed, ItemStack taken, WeaponRackArmament kind) {
    armed.held = taken; return armamentAccepted;
  }
  ${method('WeaponRackTaker.java', 'convert')}
  ${method('WeaponRackTaker.java', 'restoreArmament')}
  ${method('WeaponRackBlockEntity.java', 'nearestDrop')}
  public static void main(String[] args) {
    ItemStack weapon = new ItemStack(WeaponRackArmament.BOW, 12);
    for (boolean infinite : new boolean[]{false, true}) {
      ServerLevel level = new ServerLevel(); Villager recruit = new Villager();
      WeaponRackBlockEntity rack = new WeaponRackBlockEntity(weapon, infinite);
      convert(level, rack, recruit, weapon, weapon.kind);
      check(!recruit.discarded && rack.stored == weapon, "spawn rejection retains recruit and rack weapon");
      check(ModEntities.GUNNER_VILLAGER.type.last.discarded, "failed replacement must be discarded");
      level.acceptsSpawn = true;
      convert(level, rack, recruit, weapon, weapon.kind);
      check(recruit.discarded, "successful conversion removes original recruit");
      check(infinite ? rack.stored == weapon : rack.stored.isEmpty(), "successful conversion consumes only normal rack");
    }
    ServerLevel rejectedArmament = new ServerLevel(); rejectedArmament.acceptsSpawn = true;
    Mob recruit = new Mob(); WeaponRackBlockEntity rack = new WeaponRackBlockEntity(weapon, false);
    armamentAccepted = false;
    convert(rejectedArmament, rack, recruit, weapon, weapon.kind);
    check(!recruit.discarded && rack.stored == weapon && rejectedArmament.attempts == 0,
      "unusable weapon rolls back before spawning replacement");
    Level level = new Level();
    ItemEntity junk = new ItemEntity(1, 0.1, ItemStack.EMPTY);
    ItemEntity farWeapon = new ItemEntity(3, 2.0, weapon);
    ItemEntity nearWeapon = new ItemEntity(2, 1.0, weapon);
    level.drops.addAll(List.of(junk, farWeapon, nearWeapon));
    check(nearestDrop(level, new BlockPos()) == nearWeapon, "nearby junk cannot hide accepted farther items");
    nearWeapon.removed = true;
    check(nearestDrop(level, new BlockPos()) == farWeapon, "removed weapon drops must be excluded");
    ItemEntity tie = new ItemEntity(2, 2.0, weapon); level.drops.add(tie);
    check(nearestDrop(level, new BlockPos()) == tie, "equal distances use deterministic lower entity ID");
    for (WeaponRackArmament kind : List.of(WeaponRackArmament.BOW, WeaponRackArmament.CROSSBOW, WeaponRackArmament.MELEE)) {
      Mob mob = new Mob(); mob.data.present = true; mob.data.snapshot = new ItemStack(kind, 0);
      ItemStack worn = new ItemStack(kind, 29); mob.held = worn;
      restoreArmament(mob, true);
      check(mob.held == worn && worn.damage == 29, "loaded weapon durability must survive rack restoration");
      mob.held = ItemStack.EMPTY; restoreArmament(mob, true);
      check(mob.held.isEmpty() && mob.data.present,
        "saved broken/disarmed weapon stays absent without enabling random gun equipment");
      restoreArmament(mob, false);
      check(mob.held == mob.data.snapshot, "legacy empty hand restores conversion snapshot");
    }
    Mob invalid = new Mob(); invalid.data.present = true; invalid.data.snapshot = ItemStack.EMPTY;
    restoreArmament(invalid, true);
    check(!invalid.data.present, "invalid rack tag must be cleared to permit ordinary gun recovery");
    System.out.println(checks + " production-method rack transaction checks passed");
  }
}`;
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-rack-test-'));
try {
  const file = path.join(temp, 'RackTransactionTest.java');
  fs.writeFileSync(file, java);
  const bin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
  function run(command, args) {
    const result = cp.spawnSync(bin ? path.join(bin, command) : command, args, { encoding: 'utf8' });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(result.stdout + result.stderr);
    if (result.stdout) process.stdout.write(result.stdout);
  }
  run('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', temp, file]);
  run('java', ['-cp', temp, 'RackTransactionTest']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
