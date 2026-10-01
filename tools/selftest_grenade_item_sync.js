// Runs the production constructor, load and item-sync methods with vanilla's mapped setItem contract.
// Run with JDK 17+: node tools/selftest_grenade_item_sync.js
const fs = require('fs');
const path = require('path');
const os = require('os');
const cp = require('child_process');
const source = fs.readFileSync(path.join(__dirname,
  '../src/main/java/com/gfl/tarkovscav/grenade/GrenadeEntity.java'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
function method(signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const open = source.indexOf('{', start);
  let end = open + 1, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-grenade-sync-'));
const files = {
  'net/minecraft/world/item/Item.java': `package net.minecraft.world.item;
public record Item(String id) {}`,
  'net/minecraft/nbt/CompoundTag.java': `package net.minecraft.nbt;
public class CompoundTag {
  public String kind; public int fuse;
  public String getString(String key) { return kind; }
  public boolean contains(String key) { return true; }
  public int getInt(String key) { return fuse; }
}`,
  'com/gfl/tarkovscav/registry/ModItems.java': `package com.gfl.tarkovscav.registry;
public class ModItems {
  public static net.minecraft.world.item.Item grenadeItem(Object kind) {
    return new net.minecraft.world.item.Item(kind.toString());
  }
}`,
  'GrenadeItemSyncTest.java': `import net.minecraft.world.item.Item;
public class GrenadeItemSyncTest {
  static int checks;
  static void check(boolean result, String message) {
    checks++; if (!result) throw new AssertionError(message);
  }
  enum GrenadeKind { FRAG, HE, FLASH, SMOKE, FLASH_SHORT;
    static GrenadeKind byId(String id) { try { return valueOf(id); } catch (IllegalArgumentException e) { return null; } }
    int fuseTicks() { return 60; }
  }
  static class Level {}
  static class LivingEntity {}
  static class ItemStack {
    Item item;
    ItemStack(Item item) { this.item = item; }
    boolean is(Item other) { return item.equals(other); }
    boolean hasTag() { return false; }
  }
  static class ModEntities {
    static final ModEntities GRENADE = new ModEntities();
    Object get() { return this; }
  }
  static abstract class ProjectileFixture {
    ItemStack raw;
    ProjectileFixture() {}
    ProjectileFixture(Object type, LivingEntity thrower, Level level) {}
    protected abstract Item getDefaultItem();
    // Verified against ThrowableItemProjectile in the project's 1.20.1 mapped jar.
    void setItem(ItemStack stack) {
      if (!stack.is(getDefaultItem()) || stack.hasTag()) raw = stack;
    }
    ItemStack getItem() { return raw == null ? new ItemStack(getDefaultItem()) : raw; }
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
  }
  static class GrenadeEntity extends ProjectileFixture {
    static final String TAG_KIND = "kind", TAG_FUSE = "fuse";
    GrenadeKind kind = GrenadeKind.FRAG;
    int fuse;
    GrenadeEntity() {}
    ${method('public GrenadeEntity(Level level, LivingEntity thrower, GrenadeKind kind, int fuse)')}
    ${method('protected Item getDefaultItem()')}
    ${method('private void syncItem()')}
    ${method('public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag)')}
  }
  public static void main(String[] args) {
    for (GrenadeKind kind : GrenadeKind.values()) {
      GrenadeEntity server = new GrenadeEntity(new Level(), new LivingEntity(), kind, 17);
      GrenadeEntity client = new GrenadeEntity();
      client.raw = server.raw; // Only synced item data crosses the network, not ordinary kind fields.
      check(client.getItem().item.equals(new Item(kind.name())), kind + " must render its own item remotely");
      check(server.fuse == 17, "sync must preserve the cooked fuse");
      net.minecraft.nbt.CompoundTag oldSave = new net.minecraft.nbt.CompoundTag();
      oldSave.kind = kind.name(); oldSave.fuse = 9;
      GrenadeEntity restored = new GrenadeEntity();
      restored.readAdditionalSaveData(oldSave);
      client.raw = restored.raw;
      check(client.getItem().item.equals(new Item(kind.name())), kind + " legacy save must sync its item");
      check(restored.fuse == 9, "load must retain the saved fuse");
    }
    System.out.println(checks + " production-method grenade synchronization checks passed");
  }
}`
};
try {
  for (const [file, content] of Object.entries(files)) {
    const output = path.join(temp, file);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, content);
  }
  const bin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
  function run(command, args) {
    const result = cp.spawnSync(bin ? path.join(bin, command) : command, args, { encoding: 'utf8' });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(result.stdout + result.stderr);
    if (result.stdout) process.stdout.write(result.stdout);
  }
  run('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', temp,
    ...Object.keys(files).map(file => path.join(temp, file))]);
  run('java', ['-cp', temp, 'GrenadeItemSyncTest']);
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
