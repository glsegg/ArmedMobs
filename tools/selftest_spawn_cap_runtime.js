// Compiles the shipped cap classes against level fixtures and executes world-switch/lifecycle cases.
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'armedmobs-spawncap-'));
const files = {
  'com/gfl/tarkovscav/Config.java': `package com.gfl.tarkovscav; public class Config {
    public static class V<T> { public T value; public V(T v) { value=v; } public T get() { return value; } }
    public static class Spec { public boolean isLoaded() { return true; } }
    public static final Spec SPEC=new Spec();
    public static final V<Boolean> CITY_FACTION_CAP_ENABLED=new V<>(true), CITY_FACTION_CAP_IGNORE_MANUAL=new V<>(false), LOG_SPAWN_GATE=new V<>(false),
      GATE_COMMAND_SPAWNS=new V<>(false), SCAV_CITY_ONLY=new V<>(true);
    public static final V<Integer> CITY_FACTION_CAP=new V<>(2), CITY_FACTION_CAP_COUNT_TICKS=new V<>(20); }`,
  'com/gfl/tarkovscav/TarkovScav.java': `package com.gfl.tarkovscav; public class TarkovScav {
    public static final String MOD_ID="tarkovscav"; public static final Logger LOGGER=new Logger();
    public static class Logger { public void info(String s,Object... values) {} } }`,
  'com/gfl/tarkovscav/faction/Faction.java': `package com.gfl.tarkovscav.faction; public enum Faction { VILLAGE,ILLAGER;
    public static Faction of(net.minecraft.world.entity.Mob mob) { return mob.faction; } }`,
  'com/gfl/tarkovscav/gun/GunUser.java': 'package com.gfl.tarkovscav.gun; public interface GunUser {}',
  'com/gfl/tarkovscav/world/CityGate.java': `package com.gfl.tarkovscav.world; public class CityGate {
    public record Area(String key,String name,net.minecraft.world.level.levelgen.structure.BoundingBox box) {}
    public static boolean gateAllowed=true; public record Result(boolean allowed) {}
    public static Result test(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos pos) { return new Result(gateAllowed); }
    public static Area areaAt(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos pos) { return level.city; } }`,
  'com/gfl/tarkovscav/world/CityFactions.java': `package com.gfl.tarkovscav.world; public class CityFactions {
    public static String name(com.gfl.tarkovscav.faction.Faction faction) { return faction.name(); } }`,
  'net/minecraft/core/BlockPos.java': `package net.minecraft.core; public record BlockPos(int x,int y,int z) {
    public static BlockPos containing(double x,double y,double z) { return new BlockPos((int)x,(int)y,(int)z); } }`,
  'net/minecraft/world/entity/MobSpawnType.java': 'package net.minecraft.world.entity; public enum MobSpawnType { NATURAL,SPAWNER,SPAWN_EGG,COMMAND }',
  'net/minecraft/world/entity/Mob.java': `package net.minecraft.world.entity; public class Mob implements com.gfl.tarkovscav.gun.GunUser {
    public com.gfl.tarkovscav.faction.Faction faction=com.gfl.tarkovscav.faction.Faction.VILLAGE;
    public boolean discarded; public void discard() { discarded=true; }
    public boolean isAlive() { return true; } public Mob getType() { return this; } public String toShortString() { return "scav"; } }`,
  'net/minecraft/world/level/levelgen/structure/BoundingBox.java': `package net.minecraft.world.level.levelgen.structure;
    public record BoundingBox(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {}`,
  'net/minecraft/world/phys/AABB.java': 'package net.minecraft.world.phys; public record AABB(double x0,double y0,double z0,double x1,double y1,double z1) {}',
  'net/minecraft/server/level/ServerLevel.java': `package net.minecraft.server.level; public class ServerLevel {
    public long time=1000; public int live,queries; public com.gfl.tarkovscav.world.CityGate.Area city;
    public long getGameTime() { return time; } public ServerLevel dimension() { return this; } public String location() { return "minecraft:overworld"; }
    public java.util.List<net.minecraft.world.entity.Mob> getEntitiesOfClass(Class<net.minecraft.world.entity.Mob> type,
      net.minecraft.world.phys.AABB area,java.util.function.Predicate<net.minecraft.world.entity.Mob> predicate) {
      queries++; var mobs=new java.util.ArrayList<net.minecraft.world.entity.Mob>();
      for(int i=0;i<live;i++) { var mob=new net.minecraft.world.entity.Mob(); if(predicate.test(mob)) mobs.add(mob); } return mobs; } }`,
  'net/minecraftforge/event/level/LevelEvent.java': `package net.minecraftforge.event.level; public class LevelEvent {
    public record Unload(Object level) { public Object getLevel() { return level; } } }`,
  'net/minecraftforge/event/server/ServerStoppedEvent.java': 'package net.minecraftforge.event.server; public class ServerStoppedEvent {}',
  'net/minecraftforge/event/entity/living/MobSpawnEvent.java': `package net.minecraftforge.event.entity.living; public class MobSpawnEvent {
    public static class FinalizeSpawn { public final net.minecraft.world.entity.Mob mob=new net.minecraft.world.entity.Mob();
      public final Object level; public final net.minecraft.world.entity.MobSpawnType type; public boolean cancelled;
      public FinalizeSpawn(Object level,net.minecraft.world.entity.MobSpawnType type) { this.level=level; this.type=type; }
      public Object getLevel() { return level; } public net.minecraft.world.entity.Mob getEntity() { return mob; }
      public net.minecraft.world.entity.MobSpawnType getSpawnType() { return type; }
      public double getX() { return 1; } public double getY() { return 60; } public double getZ() { return 1; }
      public boolean isSpawnCancelled() { return cancelled; }
      public void setSpawnCancelled(boolean value) { cancelled=value; } } }`,
  'net/minecraftforge/eventbus/api/SubscribeEvent.java': 'package net.minecraftforge.eventbus.api; public @interface SubscribeEvent {}',
  'net/minecraftforge/fml/common/Mod.java': 'package net.minecraftforge.fml.common; public @interface Mod { public @interface EventBusSubscriber { String modid(); } }',
  'org/jetbrains/annotations/Nullable.java': 'package org.jetbrains.annotations; public @interface Nullable {}',
  'SpawnCapRuntimeTest.java': `import com.gfl.tarkovscav.Config; import com.gfl.tarkovscav.world.*;
    import com.gfl.tarkovscav.faction.Faction; import net.minecraft.server.level.ServerLevel;
    import net.minecraftforge.event.level.LevelEvent; import net.minecraftforge.event.server.ServerStoppedEvent;
    import net.minecraft.world.entity.MobSpawnType; import net.minecraftforge.event.entity.living.MobSpawnEvent;
    public class SpawnCapRuntimeTest {
      static int checks; static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
      static boolean veto(ServerLevel level) { return CitySpawnCap.vetoGarrison(level,level.city,Faction.VILLAGE); }
      public static void main(String[] args) {
        var city=new CityGate.Area("same-city","City",new net.minecraft.world.level.levelgen.structure.BoundingBox(0,0,0,20,100,20));
        var oldSave=new ServerLevel(); oldSave.city=city; oldSave.live=2;
        var newSave=new ServerLevel(); newSave.city=city;
        check(veto(oldSave),"old world is full");
        check(!veto(newSave),"same dimension, city and gameTime in a different save must get its own count");
        check(oldSave.queries==1 && newSave.queries==1,"each world measured independently");
        check(!veto(newSave) && veto(newSave),"two accepted spawns fill exactly this world's cap");
        check(newSave.queries==1,"fresh window reuses its count");
        check(!CitySpawnCap.vetoGarrison(newSave,city,Faction.ILLAGER),"other faction keeps its own window");
        CitySpawnCap.describe(); check(newSave.queries==2,"diagnostic never recounts");
        CitySpawnCap.onLevelUnload(new LevelEvent.Unload(new Object()));
        check(veto(newSave),"client unload does not clear server counters");
        CitySpawnCap.onLevelUnload(new LevelEvent.Unload(oldSave)); oldSave.live=0;
        check(!veto(oldSave) && oldSave.queries==2,"unloaded level forgets its count");
        check(veto(newSave),"unloading one level preserves the other level");
        check(CitySpawnCap.reset()==3,"reset reports city/faction windows, not level count");
        check(!veto(newSave) && newSave.queries==3,"reset recounts");
        newSave.time+=20; newSave.live=2; check(veto(newSave),"expired window recounts actual living units");
        var mob=new net.minecraft.world.entity.Mob(); var pos=new net.minecraft.core.BlockPos(1,60,1);
        check(!CitySpawnCap.vetoSpawn(newSave,pos,mob,net.minecraft.world.entity.MobSpawnType.SPAWN_EGG),"manual spawn remains exempt by default");
        Config.CITY_FACTION_CAP_IGNORE_MANUAL.value=true;
        check(CitySpawnCap.vetoSpawn(newSave,pos,mob,net.minecraft.world.entity.MobSpawnType.SPAWN_EGG),"manual cap toggle uses actual cap rule");
        CitySpawnCap.onServerStopped(new ServerStoppedEvent()); check(CitySpawnCap.reset()==0,"server stop clears all windows");
        Config.CITY_FACTION_CAP_IGNORE_MANUAL.value=false;
        var egg=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        ManualSpawnProbe.onFinalizeSpawn(egg); check(!egg.cancelled && !egg.mob.discarded,"manual handler remains exempt by default");
        Config.CITY_FACTION_CAP_IGNORE_MANUAL.value=true;
        var cappedEgg=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        ManualSpawnProbe.onFinalizeSpawn(cappedEgg);
        check(cappedEgg.cancelled && cappedEgg.mob.discarded,"manual cap works with city gate disabled");
        var command=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.COMMAND);
        ManualSpawnProbe.onFinalizeSpawn(command); check(command.cancelled,"ordinary summon uses manual cap too");
        CitySpawnCap.reset(); newSave.live=0;
        var admittedEgg=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        ManualSpawnProbe.onFinalizeSpawn(admittedEgg);
        check(!admittedEgg.cancelled && !veto(newSave) && veto(newSave),"manual spawn reserves once when admitted");
        CitySpawnCap.reset(); Config.GATE_COMMAND_SPAWNS.value=true; CityGate.gateAllowed=false;
        var outsideCity=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        ManualSpawnProbe.onFinalizeSpawn(outsideCity);
        check(outsideCity.cancelled && CitySpawnCap.reset()==0,"city refusal happens before a cap reservation");
        CityGate.gateAllowed=true; Config.SCAV_CITY_ONLY.value=false; newSave.live=2;
        var alreadyCancelled=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        alreadyCancelled.setSpawnCancelled(true); ManualSpawnProbe.onFinalizeSpawn(alreadyCancelled);
        check(CitySpawnCap.reset()==0,"an earlier spawn cancellation never reserves a cap slot");
        var notCityOnly=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.SPAWN_EGG);
        ManualSpawnProbe.onFinalizeSpawn(notCityOnly);
        check(notCityOnly.cancelled,"manual cap is independent of this mob's city-only flag");
        var natural=new MobSpawnEvent.FinalizeSpawn(newSave,MobSpawnType.NATURAL);
        ManualSpawnProbe.onFinalizeSpawn(natural); check(!natural.cancelled,"FinalizeSpawn does not double count the natural path");
        System.out.println("Spawn-cap lifecycle: "+checks+" checks passed");
      } }`,
};
function method(source, name) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const found = new RegExp(`(?:public|private)\\s+(?:static\\s+)?[\\w<>.?]+\\s+${name}\\s*\\([^)]*\\)\\s*\\{`).exec(clean);
  if (!found) throw new Error(`Missing production method ${name}`);
  let end = found.index + found[0].length, depth = 1;
  for (; end < clean.length && depth; end++) {
    if (clean[end] === '{') depth++; if (clean[end] === '}') depth--;
  }
  return clean.slice(found.index, end);
}
const events = fs.readFileSync(path.join(root, 'src/main/java/com/gfl/tarkovscav/world/CitySpawnEvents.java'), 'utf8');
files['com/gfl/tarkovscav/world/ManualSpawnProbe.java'] = `package com.gfl.tarkovscav.world;
  import com.gfl.tarkovscav.Config; import net.minecraft.core.BlockPos; import net.minecraft.server.level.ServerLevel;
  import net.minecraft.world.entity.Mob; import net.minecraft.world.entity.MobSpawnType;
  import net.minecraftforge.event.entity.living.MobSpawnEvent;
  public class ManualSpawnProbe {
    private static Boolean cityOnlyFor(Mob mob) { return Config.SCAV_CITY_ONLY.get(); }
    private static void log(Mob mob,MobSpawnType type,BlockPos pos,CityGate.Result result) {}
    ${method(events, 'isManualSpawn')}
    ${method(events, 'onFinalizeSpawn')}
  }`;
for (const name of ['CitySpawnCap', 'SpawnCapMath']) {
  files[`com/gfl/tarkovscav/world/${name}.java`] = fs.readFileSync(path.join(root,
    'src/main/java/com/gfl/tarkovscav/world', name + '.java'), 'utf8');
}
const sources = Object.entries(files).map(([name, source]) => {
  const file = path.join(work, name); fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, source, 'utf8'); return file;
});
const bin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
execFileSync(bin ? path.join(bin, 'javac.exe') : 'javac', ['-encoding', 'UTF-8', '-d', work, ...sources], {stdio: 'pipe'});
console.log(execFileSync(bin ? path.join(bin, 'java.exe') : 'java', ['-cp', work, 'SpawnCapRuntimeTest'], {encoding: 'utf8'}).trim());
