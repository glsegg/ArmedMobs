// Runs production lifecycle methods with stateful level/event fixtures (JDK 17+).
// Tests world switches, dimension isolation and expiry; no assets or game saves are changed.
const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');
const root = path.resolve(__dirname, '..');
const javaRoot = path.join(root, 'src/main/java/com/gfl/tarkovscav');
function read(file) { return fs.readFileSync(path.join(javaRoot, file + '.java'), 'utf8'); }
function method(source, name) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const found = new RegExp(`(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>.?]+\\s+${name}\\s*\\([^)]*\\)\\s*\\{`).exec(clean);
  if (!found) throw new Error(`Missing production method ${name}`);
  let end = found.index + found[0].length, depth = 1;
  for (; end < clean.length && depth; end++) {
    if (clean[end] === '{') depth++;
    if (clean[end] === '}') depth--;
  }
  if (depth) throw new Error(`Unbalanced production method ${name}`);
  return clean.slice(found.index, end);
}
function methods(source, names) { return names.map(name => method(source, name)).join('\n'); }
function field(source, name) {
  const found = source.match(new RegExp(`private static final [^;=]+\\b${name}\\s*=[^;]+;`));
  if (!found) throw new Error(`Missing production field ${name}`);
  return found[0];
}
function record(source, name) {
  const found = source.match(new RegExp(`private record ${name}\\([^)]*\\)\\s*\\{\\s*\\}`));
  if (!found) throw new Error(`Missing production record ${name}`);
  return found[0];
}
const squad = read('gun/SquadCoordinator');
const smoke = read('grenade/GrenadeEvents');
const travel = read('world/WastelandTravel');
const armor = read('entity/ArmorClass');
const grenadeItem = read('grenade/GrenadeItem');
const source = `
import java.util.*;
public class StateLifecycleTest {
  static int checks;
  static boolean forbidServerCollectionAccess;
  static class WeakHashMap<K, V> extends java.util.WeakHashMap<K, V> {
    @Override public V remove(Object key) {
      if (forbidServerCollectionAccess) throw new AssertionError("client event touched server claim map");
      return super.remove(key);
    }
  }
  static class ArrayList<E> extends java.util.ArrayList<E> {
    @Override public boolean removeIf(java.util.function.Predicate<? super E> predicate) {
      if (forbidServerCollectionAccess) throw new AssertionError("client event traversed server cloud list");
      return super.removeIf(predicate);
    }
  }
  static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  @interface Nullable {}
  record BlockPos(int x, int y, int z) { BlockPos immutable() { return this; } }
  static class Vec3 { double x, y, z; }
  static class Level {
    long time; boolean isClientSide, acceptsEntity = true; int spawns, sounds; GrenadeEntity lastSpawn;
    long getGameTime() { return time; }
    boolean addFreshEntity(GrenadeEntity entity) { spawns++; lastSpawn = entity; return acceptsEntity; }
    void playSound(Object... values) { sounds++; }
  }
  static class Server {
    int ticks; ServerLevel overworld;
    int getTickCount() { return ticks; }
    ServerLevel overworld() { return overworld; }
  }
  static class ServerLevel extends Level {
    final Server server; int puffs; List<LivingEntity> entities = new ArrayList<>();
    ServerLevel(Server server, long time) { this.server = server; this.time = time; }
    Server getServer() { return server; }
    void sendParticles(Object particle, double x, double y, double z, int count,
        double dx, double dy, double dz, double speed) { puffs++; }
  }
  static class Mob {
    final Level level; final int id; boolean coordinates = true;
    Mob(Level level, int id) { this.level = level; this.id = id; }
    Level level() { return level; } int getId() { return id; }
  }
  static class Name { String getString() { return "unit"; } }
  static class LivingEntity {
    int blind; void addEffect(MobEffectInstance effect) { blind++; }
    Name getName() { return new Name(); }
  }
  static class Abilities { boolean instabuild; }
  static class Player extends LivingEntity {
    Abilities abilities = new Abilities(); Abilities getAbilities() { return abilities; }
    double getX() { return 0; } double getY() { return 0; } double getZ() { return 0; }
    double getEyeY() { return 1.6; } Vec3 getLookAngle() { return new Vec3(); }
  }
  static class ItemStack { int count = 3; void shrink(int amount) { count -= amount; } }
  static class SoundEvents { static final Object SNOWBALL_THROW = new Object(); }
  static class SoundSource { static final Object PLAYERS = new Object(); }
  static class ServerPlayer {
    final UUID uuid; final Level level;
    ServerPlayer(UUID uuid, Level level) { this.uuid = uuid; this.level = level; }
    UUID getUUID() { return uuid; } Level level() { return level; }
  }
  static class Value<T> { T value; Value(T value) { this.value = value; } T get() { return value; } }
  static class Spec { boolean isLoaded() { return true; } }
  static class Config {
    static final Spec SPEC = new Spec();
    static final Value<Boolean> AI_COORD_COVER_CLAIMS = new Value<>(true);
    static final Value<Integer> AI_COORD_COVER_CLAIM_TICKS = new Value<>(40);
    static final Value<Boolean> GRENADES_ENABLED = new Value<>(true);
    static final Value<Integer> GRENADES_THROW_CHARGE_TICKS = new Value<>(20);
    static final Value<Double> GRENADES_MIN_THROW_SPEED = new Value<>(0.5), GRENADES_MAX_THROW_SPEED = new Value<>(1.5);
    static final Value<Boolean> GRENADES_COOK_WHILE_HOLDING = new Value<>(true);
    static final Value<Double> ARMOR_REDUCTION_PER_CLASS = new Value<>(0.1);
    static final Value<Boolean> ARMOR_ENABLED = new Value<>(true), LOG_GUN_AI = new Value<>(false);
  }
  static class AiProfile { static boolean coordination(Mob mob) { return mob.coordinates; } }
  static class TickEvent {
    enum Phase { START, END }
    static class ServerTickEvent {
      final Phase phase; final Server server;
      ServerTickEvent(Server server, Phase phase) { this.server = server; this.phase = phase; }
      Server getServer() { return server; }
    }
  }
  static class LevelEvent {
    static class Unload { final Level level; Unload(Level level) { this.level = level; } Level getLevel() { return level; } }
  }
  static class ServerStoppedEvent {
    final Server server; ServerStoppedEvent(Server server) { this.server = server; } Server getServer() { return server; }
  }
  static class ParticleTypes { static final Object CAMPFIRE_COSY_SMOKE = new Object(); }
  static class MobEffects { static final Object BLINDNESS = new Object(); }
  static class MobEffectInstance { MobEffectInstance(Object effect, int ticks, int amp, boolean ambient, boolean visible) {} }
  static class GrenadeEntity {
    boolean discarded; int fuse;
    GrenadeEntity(Level level, Player player, GrenadeKind kind, int fuse) { this.fuse = fuse; }
    void setPos(double x, double y, double z) {} void shoot(double x, double y, double z, float speed, float error) {}
    void discard() { discarded = true; }
    static List<LivingEntity> candidates(ServerLevel level, Vec3 centre, double radius) { return level.entities; }
  }
  static class GrenadeKind { int fuseTicks() { return 60; } }
  static class GrenadeItem {
    GrenadeKind kind = new GrenadeKind(); int getUseDuration(ItemStack stack) { return 100; }
    ${method(grenadeItem, 'releaseUsing')}
  }
  static class Logger { void info(Object... values) {} }
  static class TarkovScav { static final Logger LOGGER = new Logger(); }
  static class LivingHurtEvent {
    float amount; LivingHurtEvent(float amount) { this.amount = amount; }
    LivingEntity getEntity() { return new LivingEntity(); }
    float getAmount() { return amount; } void setAmount(float amount) { this.amount = amount; }
  }
  static class ArmorClass {
    static boolean isTroop(LivingEntity entity) { return true; }
    static int of(LivingEntity entity) { return 6; }
    ${methods(armor, ['reductionFor', 'onHurt'])}
  }
  static class SquadCoordinator {
    ${record(squad, 'Claim')}
    ${field(squad, 'CLAIMS')}
    final Mob mob; SquadCoordinator(Mob mob) { this.mob = mob; }
    ${methods(squad, ['claimFor', 'claim', 'isFree', 'release', 'claimIsFree', 'isCoverFree', 'pruneClaims', 'onLevelUnload'])}
  }
  static class GrenadeEvents {
    ${record(smoke, 'Cloud')}
    ${field(smoke, 'CLOUDS')}
    ${methods(smoke, ['onServerTick', 'puff', 'cloudCount', 'clearClouds', 'onLevelUnload', 'onServerStopped'])}
    static void seed(ServerLevel level, long until, long last) { CLOUDS.add(new Cloud(level, new Vec3(), 4, until, last)); }
  }
  static class WastelandTravel {
    enum Target { INTO_WASTELAND }
    static final int CAST_TICKS = 40;
    ${record(travel, 'Cast')}
    ${field(travel, 'CASTS')}
    ${field(travel, 'COOLDOWN_UNTIL')}
    ${methods(travel, ['onServerStopped', 'cooldownSeconds', 'onServerTick'])}
  }
  static TickEvent.ServerTickEvent end(Server server) { return new TickEvent.ServerTickEvent(server, TickEvent.Phase.END); }
  static void coverClaims() {
    Server server = new Server();
    ServerLevel overworld = new ServerLevel(server, 1000), nether = new ServerLevel(server, 10);
    BlockPos pos = new BlockPos(4, 60, 7);
    Mob first = new Mob(overworld, 1), neighbor = new Mob(overworld, 2), otherDimension = new Mob(nether, 3);
    SquadCoordinator one = new SquadCoordinator(first), two = new SquadCoordinator(neighbor), three = new SquadCoordinator(otherDimension);
    check(two.isFree(pos), "empty cover is available");
    one.claim(pos);
    check(one.isFree(pos), "cover owner can reuse its cover");
    check(!two.isFree(pos), "another mob in the same level cannot reuse cover");
    check(three.isFree(pos), "same coordinates in another dimension are independent");
    three.claim(pos);
    three.release();
    check(!two.isFree(pos), "release in another dimension must not remove this reservation");
    nether.time = 5000;
    SquadCoordinator.pruneClaims(nether, nether.time);
    check(!two.isFree(pos), "pruning another clock must not expire this level's claim");
    overworld.time = 1039;
    check(!two.isFree(pos), "reservation lasts through its final live tick");
    overworld.time = 1040;
    check(two.isFree(pos), "reservation expires exactly at deadline");
    SquadCoordinator.claimFor(first, pos);
    check(!two.isFree(pos), "garrison reservation uses the same scoped table");
    forbidServerCollectionAccess = true;
    try {
      SquadCoordinator.onLevelUnload(new LevelEvent.Unload(new Level()));
    } finally { forbidServerCollectionAccess = false; }
    check(!two.isFree(pos), "client unload leaves server claim state alone");
    SquadCoordinator.onLevelUnload(new LevelEvent.Unload(nether));
    check(!two.isFree(pos), "unloading another dimension leaves this claim intact");
    SquadCoordinator.onLevelUnload(new LevelEvent.Unload(overworld));
    check(two.isFree(pos), "level unload discards its claims");
    one.claim(pos);
    ServerLevel newSave = new ServerLevel(new Server(), 0);
    check(new SquadCoordinator(new Mob(newSave, 2)).isFree(pos), "another save cannot inherit high-clock claims");
    one.release(); check(two.isFree(pos), "owner release frees its claim");
    Level clientLevel = new Level();
    new SquadCoordinator(new Mob(clientLevel, 4)).claim(pos);
    check(!SquadCoordinator.CLAIMS.containsKey(clientLevel), "client entities create no server claims");
    Config.AI_COORD_COVER_CLAIMS.value = false; one.claim(pos);
    check(two.isFree(pos), "disabled cover coordination does not reserve cover");
    Config.AI_COORD_COVER_CLAIMS.value = true;
    SquadCoordinator.CLAIMS.clear();
  }
  static void smokeClouds() {
    Server server = new Server();
    ServerLevel overworld = new ServerLevel(server, 10), nether = new ServerLevel(server, 1000);
    LivingEntity mob = new LivingEntity(); Player player = new Player();
    nether.entities.add(mob); nether.entities.add(player);
    GrenadeEvents.seed(overworld, 100, 10);
    GrenadeEvents.seed(nether, 2000, 990);
    GrenadeEvents.onServerTick(end(server));
    check(overworld.puffs == 0, "new cloud waits five local ticks before puffing");
    check(nether.puffs == 1, "cloud uses its own world's clock");
    check(mob.blind == 1 && player.blind == 0, "smoke affects mobs but leaves player controls alone");
    overworld.time = 100; nether.time = 1001;
    GrenadeEvents.onServerTick(end(server));
    check(GrenadeEvents.cloudCount() == 1, "first cloud expires exactly at its own deadline");
    check(nether.puffs == 1, "another world's expiry cannot advance the puff timer");
    GrenadeEvents.seed(overworld, 500, 100);
    forbidServerCollectionAccess = true;
    try {
      GrenadeEvents.onLevelUnload(new LevelEvent.Unload(new Level()));
    } finally { forbidServerCollectionAccess = false; }
    check(GrenadeEvents.cloudCount() == 2, "client unload leaves server smoke state alone");
    GrenadeEvents.onLevelUnload(new LevelEvent.Unload(nether));
    check(GrenadeEvents.cloudCount() == 1 && GrenadeEvents.CLOUDS.get(0).level() == overworld,
        "unloaded level loses only its own smoke");
    Server nextServer = new Server(); ServerLevel nextLevel = new ServerLevel(nextServer, 5);
    GrenadeEvents.seed(nextLevel, 100, 0);
    GrenadeEvents.onServerStopped(new ServerStoppedEvent(server));
    check(GrenadeEvents.cloudCount() == 1 && GrenadeEvents.CLOUDS.get(0).level() == nextLevel,
        "stopped server releases all old ServerLevel references");
    GrenadeEvents.seed(overworld, 500, 0);
    int oldPuffs = overworld.puffs;
    GrenadeEvents.onServerTick(end(nextServer));
    check(GrenadeEvents.cloudCount() == 1 && overworld.puffs == oldPuffs,
        "even stale clouds cannot tick an earlier server");
    Config.GRENADES_ENABLED.value = false;
    GrenadeEvents.onServerTick(new TickEvent.ServerTickEvent(nextServer, TickEvent.Phase.START));
    check(GrenadeEvents.cloudCount() == 1, "START phase does not advance state");
    GrenadeEvents.onServerTick(end(nextServer));
    check(GrenadeEvents.cloudCount() == 0, "disabling grenades clears live smoke");
    Config.GRENADES_ENABLED.value = true;
    GrenadeEvents.onServerTick(end(nextServer));
    check(GrenadeEvents.cloudCount() == 0, "empty server tick is safe");
  }
  static void deploymentState() {
    Server oldServer = new Server(); ServerLevel oldLevel = new ServerLevel(oldServer, 100000);
    oldServer.overworld = oldLevel;
    UUID playerId = UUID.randomUUID();
    WastelandTravel.COOLDOWN_UNTIL.put(playerId, 100200L);
    WastelandTravel.CASTS.put(playerId, new WastelandTravel.Cast(oldLevel, new BlockPos(0, 64, 0),
        WastelandTravel.Target.INTO_WASTELAND, 40));
    check(WastelandTravel.cooldownSeconds(new ServerPlayer(playerId, oldLevel)) == 10, "current-world cooldown is unchanged");
    WastelandTravel.onServerStopped(new ServerStoppedEvent(oldServer));
    Server newServer = new Server(); ServerLevel newLevel = new ServerLevel(newServer, 0);
    check(WastelandTravel.cooldownSeconds(new ServerPlayer(playerId, newLevel)) == 0,
        "returning player inherits no 5000-second cooldown from earlier save");
    check(WastelandTravel.CASTS.isEmpty(), "server stop releases in-flight casts and old world references");
    WastelandTravel.COOLDOWN_UNTIL.put(playerId, 100000L);
    WastelandTravel.onServerTick(end(oldServer));
    check(WastelandTravel.COOLDOWN_UNTIL.isEmpty(), "normal expired cooldown cleanup still works");
  }
  static void damageBoundaries() {
    for (int tier = 0; tier <= 6; tier++) {
      check(Math.abs(ArmorClass.reductionFor(tier) - tier * 0.1) < 1e-9, "default armor balance remains unchanged");
    }
    Config.ARMOR_REDUCTION_PER_CLASS.value = 0.25;
    LivingHurtEvent event = new LivingHurtEvent(10);
    ArmorClass.onHurt(event);
    check(event.amount == 0, "legal high armor config cannot produce negative damage");
    Config.ARMOR_REDUCTION_PER_CLASS.value = Double.NaN;
    check(ArmorClass.reductionFor(6) == 0, "nonfinite runtime value cannot poison damage");
    Config.ARMOR_REDUCTION_PER_CLASS.value = 0.1;
  }
  static void cancelledThrows() {
    GrenadeItem item = new GrenadeItem(); Level level = new Level();
    Player player = new Player(); ItemStack stack = new ItemStack();
    level.acceptsEntity = false;
    item.releaseUsing(stack, level, player, 80);
    check(level.spawns == 1 && level.lastSpawn.discarded, "rejected projectile is released");
    check(stack.count == 3 && level.sounds == 0, "rejected throw consumes no grenade or success sound");
    level.acceptsEntity = true;
    item.releaseUsing(stack, level, player, 80);
    check(stack.count == 2 && level.sounds == 1 && !level.lastSpawn.discarded,
        "accepted survival throw consumes exactly one grenade");
    check(level.lastSpawn.fuse == 40, "cooked fuse is preserved");
    player.abilities.instabuild = true;
    item.releaseUsing(stack, level, player, 80);
    check(stack.count == 2, "creative throw preserves inventory");
    level.isClientSide = true; int spawns = level.spawns;
    item.releaseUsing(stack, level, player, 80);
    check(level.spawns == spawns, "client cannot create a duplicate grenade");
    level.isClientSide = false; Config.GRENADES_ENABLED.value = false;
    item.releaseUsing(stack, level, player, 80);
    check(level.spawns == spawns && stack.count == 2, "disabled grenade use stays inert");
    Config.GRENADES_ENABLED.value = true;
  }
  public static void main(String[] args) {
    coverClaims(); smokeClouds(); deploymentState(); damageBoundaries(); cancelledThrows();
    System.out.println("StateLifecycleTest: " + checks + " checks passed");
  }
}
`;
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-lifecycle-'));
const java = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
try {
  fs.writeFileSync(path.join(temp, 'StateLifecycleTest.java'), source);
  for (const [command, args] of [[java('javac'), ['-encoding', 'UTF-8', '-d', temp, path.join(temp, 'StateLifecycleTest.java')]],
    [java('java'), ['-cp', temp, 'StateLifecycleTest']]]) {
    const result = cp.spawnSync(command, args, { encoding: 'utf8' });
    process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
    if (result.error) throw result.error;
    if (result.status !== 0) process.exitCode = 1;
    if (result.status !== 0) break;
  }
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
