// Executes the production HUD code against small API doubles. No game or GL context is needed.
// JAVA_HOME must point to JDK 17+. The doubles model client worlds, text width, pose transforms and ARGB fills;
// this does not replace checking Forge event dispatch or visual behaviour in the game.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build', 'audit', 'client-runtime');
const fixtures = {
  'com/gfl/tarkovscav/TarkovScav.java': `package com.gfl.tarkovscav;
public class TarkovScav { public static final String MOD_ID="test"; public static final Log LOGGER=new Log();
 public static class Log { public void debug(String s,Object... args) {} public void error(String s,Object... args) {} } }`,
  'com/gfl/tarkovscav/world/CaptureHudNetwork.java': `package com.gfl.tarkovscav.world;
public class CaptureHudNetwork { public record Bar(String faction,int strength,int max,boolean captured) {}
 public record CaptureHudMessage(String cityKey,String cityName,java.util.List<Bar> bars,boolean hide) {} }`,
  'net/minecraft/world/entity/Mob.java': `package net.minecraft.world.entity; public class Mob {}`,
  'net/minecraft/world/entity/EntityType.java': `package net.minecraft.world.entity; public class EntityType<T> {}`,
  'net/minecraft/world/item/TooltipFlag.java': `package net.minecraft.world.item; public interface TooltipFlag {}`,
  'net/minecraft/world/item/Item.java': `package net.minecraft.world.item;
public class Item { public static class Properties {} public String descriptionId;
 public String getDescriptionId() { return descriptionId; }
 public void appendHoverText(ItemStack stack,net.minecraft.world.level.Level level,
   java.util.List<net.minecraft.network.chat.Component> tooltip,TooltipFlag flag) {} }`,
  'net/minecraftforge/common/ForgeSpawnEggItem.java': `package net.minecraftforge.common;
public class ForgeSpawnEggItem extends net.minecraft.world.item.Item {
 public ForgeSpawnEggItem(java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type,
 int background,int highlight,Properties properties) {} }`,
  'org/jetbrains/annotations/Nullable.java': `package org.jetbrains.annotations; public @interface Nullable {}`,
  'net/minecraftforge/client/gui/overlay/ForgeGui.java': `package net.minecraftforge.client.gui.overlay; public class ForgeGui {}`,
  'net/minecraftforge/client/gui/overlay/IGuiOverlay.java': `package net.minecraftforge.client.gui.overlay;
public interface IGuiOverlay { void render(ForgeGui gui,net.minecraft.client.gui.GuiGraphics graphics,float tick,int width,int height); }`,
  'net/minecraftforge/client/event/ClientPlayerNetworkEvent.java': `package net.minecraftforge.client.event;
public class ClientPlayerNetworkEvent { public static class LoggingOut {} }`,
  'net/minecraftforge/api/distmarker/Dist.java': `package net.minecraftforge.api.distmarker; public enum Dist { CLIENT }`,
  'net/minecraftforge/fml/common/Mod.java': `package net.minecraftforge.fml.common;
public class Mod { public @interface EventBusSubscriber { String modid(); Bus bus(); net.minecraftforge.api.distmarker.Dist value();
 public enum Bus { FORGE,MOD } } }`
};
Object.assign(fixtures, {
  'com/gfl/tarkovscav/client/RigSupport.java': `package com.gfl.tarkovscav.client;
public class RigSupport { public static int invalidations; public static void invalidateConfig() { invalidations++; } }`,
  'net/minecraftforge/fml/config/ModConfig.java': `package net.minecraftforge.fml.config;
public record ModConfig(Object getSpec) { }`,
  'net/minecraftforge/fml/event/config/ModConfigEvent.java': `package net.minecraftforge.fml.event.config;
public class ModConfigEvent { public record Reloading(net.minecraftforge.fml.config.ModConfig getConfig) { } }`,
  'com/gfl/tarkovscav/client/RenderStats.java': `package com.gfl.tarkovscav.client;
public class RenderStats { public static int clears; public static void clear() { clears++; } }`,
  'com/gfl/tarkovscav/Config.java': `package com.gfl.tarkovscav;
public class Config {
 public static class Value<T> { public T value; public Value(T v) { value=v; } public T get() { return value; } }
 public static class Spec { public boolean loaded=true; public boolean isLoaded() { return loaded; } }
 public static final Spec SPEC=new Spec();
 public static final Value<Boolean> CAPTURE_HUD_ENABLED=new Value<>(true), GRENADES_ENABLED=new Value<>(true), KILLFEED_ENABLED=new Value<>(true);
 public static final Value<Integer> CAPTURE_HUD_HIDE_DELAY_SECONDS=new Value<>(8), KILLFEED_MAX_LINES=new Value<>(5), KILLFEED_LINE_DURATION_TICKS=new Value<>(100);
 public static final Value<Double> KILLFEED_SCALE=new Value<>(1.0);
 public static final Value<String> KILLFEED_POSITION=new Value<>("top_center");
}`,
  'com/gfl/tarkovscav/faction/Faction.java': `package com.gfl.tarkovscav.faction;
public enum Faction { SCAV,ILLAGER,VILLAGE; public static Faction of(net.minecraft.world.entity.Entity entity) { return null; } }`,
  'com/gfl/tarkovscav/killfeed/KillFeedNetwork.java': `package com.gfl.tarkovscav.killfeed;
public class KillFeedNetwork { public record KillFeedMessage(String killer,String victim,net.minecraft.world.item.ItemStack weapon,KillFeedSource source) {} }`,
  'net/minecraft/client/Minecraft.java': `package net.minecraft.client;
public class Minecraft { private static final Minecraft INSTANCE=new Minecraft(); public static Minecraft getInstance() { return INSTANCE; }
 public net.minecraft.world.entity.player.Player player=new net.minecraft.world.entity.player.Player(); public Object screen; public boolean paused;
 public net.minecraft.client.multiplayer.ClientLevel level=new net.minecraft.client.multiplayer.ClientLevel();
 public final Options options=new Options(); public final net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font();
 public final java.util.Queue<Runnable> tasks=new java.util.ArrayDeque<>(); public void execute(Runnable task) { tasks.add(task); }
 public boolean isPaused() { return paused; } public static class Options { public boolean hideGui; } }`,
  'net/minecraft/world/level/Level.java': `package net.minecraft.world.level;
public class Level { public static final String OVERWORLD="overworld"; public String dimension=OVERWORLD; public String dimension() { return dimension; } }`,
  'net/minecraft/client/multiplayer/ClientLevel.java': `package net.minecraft.client.multiplayer;
public class ClientLevel extends net.minecraft.world.level.Level {
 public final net.minecraft.world.scores.Scoreboard scoreboard=new net.minecraft.world.scores.Scoreboard();
 public net.minecraft.world.scores.Scoreboard getScoreboard() { return scoreboard; }
 public java.util.List<net.minecraft.world.entity.Entity> entitiesForRendering() { return java.util.List.of(); } }`,
  'net/minecraft/world/entity/Entity.java': `package net.minecraft.world.entity;
public class Entity { public net.minecraft.network.chat.Component getDisplayName() { return net.minecraft.network.chat.Component.literal("Entity"); } }`,
  'net/minecraft/world/entity/player/Player.java': `package net.minecraft.world.entity.player;
public class Player extends net.minecraft.world.entity.Entity { public String getScoreboardName() { return "Player"; } }`,
  'net/minecraft/ChatFormatting.java': `package net.minecraft;
public enum ChatFormatting { GRAY,DARK_GRAY,RED; public int getId() { return -1; } }`,
  'net/minecraft/network/chat/Style.java': `package net.minecraft.network.chat;
public class Style { public Style withColor(int color) { return this; } }`,
  'net/minecraft/network/chat/FormattedText.java': `package net.minecraft.network.chat;
public interface FormattedText { String getString(); int styles();
 static FormattedText composite(FormattedText... parts) { MutableComponent out=Component.literal(""); for(var part:parts) out.append(part); return out; } }`,
  'net/minecraft/network/chat/Component.java': `package net.minecraft.network.chat;
public class Component implements FormattedText { public String text; public int styled;
 Component(String value) { text=value; } public String getString() { return text; } public int styles() { return styled; }
 public String key() { return text; } public static MutableComponent literal(String text) { return new MutableComponent(text); }
 public static MutableComponent translatable(String key) { return new MutableComponent(key); }
 public MutableComponent copy() { var copy=new MutableComponent(text); copy.styled=styled; return copy; }
 public MutableComponent withStyle(net.minecraft.ChatFormatting color) { var copy=copy(); copy.styled++; return copy; } }`,
  'net/minecraft/network/chat/MutableComponent.java': `package net.minecraft.network.chat;
public class MutableComponent extends Component { public MutableComponent(String s) { super(s); }
 public MutableComponent withStyle(java.util.function.UnaryOperator<Style> f) { f.apply(new Style()); styled++; return this; }
 public MutableComponent withStyle(net.minecraft.ChatFormatting f) { styled++; return this; }
 public MutableComponent append(FormattedText other) { text+=other.getString(); styled+=other.styles(); return this; } }`,
  'net/minecraft/util/FormattedCharSequence.java': `package net.minecraft.util;
public record FormattedCharSequence(String text,int styles) { }`,
  'net/minecraft/locale/Language.java': `package net.minecraft.locale;
public class Language { private static final Language INSTANCE=new Language(); public static Language getInstance() { return INSTANCE; }
 public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) {
 return new net.minecraft.util.FormattedCharSequence(text.getString(),text.styles()); } }`,
  'net/minecraft/client/gui/Font.java': `package net.minecraft.client.gui;
public class Font { public int lineHeight=9; public int width(String text) { return text.length()*6; }
 public int width(net.minecraft.network.chat.FormattedText text) { return width(text.getString()); }
 public String plainSubstrByWidth(String text,int width) { return text.substring(0,Math.min(text.length(),Math.max(0,width/6))); }
 public net.minecraft.network.chat.FormattedText substrByWidth(net.minecraft.network.chat.FormattedText text,int width) {
 var out=net.minecraft.network.chat.Component.literal(plainSubstrByWidth(text.getString(),width)); out.styled=text.styles(); return out; } }`,
  'net/minecraft/world/item/ItemStack.java': `package net.minecraft.world.item;
public class ItemStack { public String name="Rifle"; public static final ItemStack EMPTY=new ItemStack();
 public boolean isEmpty() { return this==EMPTY; } public ItemStack copy() { var copy=new ItemStack(); copy.name=name; return copy; }
 public net.minecraft.network.chat.Component getHoverName() { return net.minecraft.network.chat.Component.literal(name); } }`,
  'net/minecraft/world/scores/PlayerTeam.java': `package net.minecraft.world.scores;
public class PlayerTeam { public net.minecraft.ChatFormatting getColor() { return net.minecraft.ChatFormatting.GRAY; }
 public static net.minecraft.network.chat.Component formatNameForTeam(PlayerTeam t,net.minecraft.network.chat.Component name) { return name; } }`,
  'net/minecraft/world/scores/Score.java': `package net.minecraft.world.scores;
public record Score(String getOwner,int getScore) { }`,
  'net/minecraft/world/scores/Objective.java': `package net.minecraft.world.scores;
public class Objective { public net.minecraft.network.chat.Component getDisplayName() { return net.minecraft.network.chat.Component.literal("Scores"); } }`,
  'net/minecraft/world/scores/Scoreboard.java': `package net.minecraft.world.scores;
public class Scoreboard { public Objective objective; public java.util.List<Score> scores=new java.util.ArrayList<>();
 public PlayerTeam getPlayersTeam(String name) { return null; } public Objective getDisplayObjective(int slot) { return objective; }
 public java.util.List<Score> getPlayerScores(Objective objective) { return scores; } }`,
  'net/minecraft/client/gui/GuiGraphics.java': `package net.minecraft.client.gui;
public class GuiGraphics {
 public record Rect(double left,double top,double right,double bottom,int argb) { }
 public record Text(String text,double x,double y,int styles) { }
 public final java.util.List<Rect> fills=new java.util.ArrayList<>(); public final java.util.List<Text> texts=new java.util.ArrayList<>();
 public static class Pose { double x,y,scale=1; public java.util.Stack<double[]> saved=new java.util.Stack<>();
 public void pushPose() { saved.push(new double[]{x,y,scale}); } public void popPose() { var s=saved.pop(); x=s[0]; y=s[1]; scale=s[2]; }
 public void translate(double a,double b,double c) { x+=a*scale; y+=b*scale; } public void scale(float a,float b,float c) { scale*=a; } }
 private final Pose pose=new Pose(); public Pose pose() { return pose; }
 public void fill(int a,int b,int c,int d,int argb) { fills.add(new Rect(pose.x+a*pose.scale,pose.y+b*pose.scale,pose.x+c*pose.scale,pose.y+d*pose.scale,argb)); }
 public void drawString(Font f,String text,int x,int y,int color,boolean shadow) { texts.add(new Text(text,pose.x+x*pose.scale,pose.y+y*pose.scale,0)); }
 public void drawString(Font f,net.minecraft.util.FormattedCharSequence text,int x,int y,int color,boolean shadow) {
 texts.add(new Text(text.text(),pose.x+x*pose.scale,pose.y+y*pose.scale,text.styles())); } }`,
  'com/mojang/blaze3d/platform/Window.java': `package com.mojang.blaze3d.platform;
public record Window(int getGuiScaledWidth,int getGuiScaledHeight) { }`,
  'net/minecraftforge/client/event/RenderGuiEvent.java': `package net.minecraftforge.client.event;
public class RenderGuiEvent { public record Pre(com.mojang.blaze3d.platform.Window getWindow) { } }`,
  'net/minecraftforge/client/event/CustomizeGuiOverlayEvent.java': `package net.minecraftforge.client.event;
public class CustomizeGuiOverlayEvent {
 public record Boss(net.minecraft.network.chat.Component getName) { }
 public record BossEventProgress(int getX,int getY,Boss getBossEvent) { } }`,
  'net/minecraftforge/event/TickEvent.java': `package net.minecraftforge.event;
public class TickEvent { public enum Phase { START,END } public static class ClientTickEvent { public Phase phase=Phase.END; } }`,
  'net/minecraftforge/eventbus/api/EventPriority.java': `package net.minecraftforge.eventbus.api; public enum EventPriority { NORMAL,LOWEST }`,
  'net/minecraftforge/eventbus/api/SubscribeEvent.java': `package net.minecraftforge.eventbus.api;
public @interface SubscribeEvent { EventPriority priority() default EventPriority.NORMAL; }`,
  'ClientRuntimeTest.java': `import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.client.*;
import com.gfl.tarkovscav.world.CaptureHudNetwork;
import com.gfl.tarkovscav.killfeed.KillFeedSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.event.TickEvent;
public class ClientRuntimeTest {
 static final Minecraft MC=Minecraft.getInstance(); static int checks;
 static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
 static void reset() {
  ClientHudEvents.onLoggingOut(null); MC.level=new net.minecraft.client.multiplayer.ClientLevel();
  MC.player=new net.minecraft.world.entity.player.Player(); MC.screen=null; MC.paused=false; MC.options.hideGui=false;
  Config.SPEC.loaded=true; Config.KILLFEED_ENABLED.value=Config.CAPTURE_HUD_ENABLED.value=Config.GRENADES_ENABLED.value=true;
  Config.KILLFEED_MAX_LINES.value=5; Config.KILLFEED_LINE_DURATION_TICKS.value=100; Config.CAPTURE_HUD_HIDE_DELAY_SECONDS.value=8;
  Config.KILLFEED_SCALE.value=1.0; Config.KILLFEED_POSITION.value="top_center";
 }
 static void tick(int count) { for(int i=0;i<count;i++) ClientHudEvents.onClientTick(new TickEvent.ClientTickEvent()); }
 static void frame(int width,int height) { ClientHudEvents.onRenderGui(new net.minecraftforge.client.event.RenderGuiEvent.Pre(
  new com.mojang.blaze3d.platform.Window(width,height))); }
 static CaptureHudNetwork.CaptureHudMessage message() {
  return new CaptureHudNetwork.CaptureHudMessage("city","City",java.util.List.of(
   new CaptureHudNetwork.Bar("village",20,100,false),new CaptureHudNetwork.Bar("illager",40,100,false)),false);
 }
 static void feed(int count) { for(int i=0;i<count;i++) KillFeedHud.add("Killer"+i,"Victim",new net.minecraft.world.item.ItemStack(),KillFeedSource.ITEM); }
 static void populated() { CaptureHud.accept(message()); feed(5); FlashOverlay.accept(1,100); }
 static boolean empty() { return CaptureHud.bars().isEmpty() && KillFeedHud.lineCount()==0 && FlashOverlay.ticksLeft()==0; }
 static void lifecycleTests() {
  reset(); populated(); tick(1); check(KillFeedHud.lineCount()==5 && CaptureHud.bars().size()==2,"first world packet survives its first tick");
  MC.paused=true; String before=KillFeedHud.describe().toString(); tick(200);
  check(before.equals(KillFeedHud.describe().toString()) && FlashOverlay.ticksLeft()==99 && CaptureHud.bars().size()==2,"all HUD timers pause with game");
  MC.level=new net.minecraft.client.multiplayer.ClientLevel(); tick(1); check(empty(),"world identity change clears all HUD state even paused");
  populated(); ClientHudEvents.onLoggingOut(null); check(empty(),"logout clears all HUDs");
  reset(); populated(); MC.level=null; tick(1); check(empty(),"missing level clears HUDs");
  populated(); check(empty(),"packets with no current level are ignored");
  reset(); populated(); MC.player=null; frame(320,180); check(empty(),"missing player clears before render");
  reset(); populated(); MC.paused=true;
  Config.KILLFEED_ENABLED.value=Config.CAPTURE_HUD_ENABLED.value=Config.GRENADES_ENABLED.value=false;
  tick(1); check(empty(),"disable clears pending data even paused");
  Config.KILLFEED_ENABLED.value=Config.CAPTURE_HUD_ENABLED.value=Config.GRENADES_ENABLED.value=true;
  tick(1); check(empty(),"reenable does not revive old HUDs");
  reset(); populated(); MC.level=new net.minecraft.client.multiplayer.ClientLevel(); MC.level.dimension="nether";
  frame(320,180); CaptureHud.accept(message()); check(CaptureHud.bars().isEmpty(),"capture packet outside overworld stays hidden");
  reset(); populated(); MC.level=new net.minecraft.client.multiplayer.ClientLevel(); CaptureHud.accept(message()); tick(1);
  check(CaptureHud.bars().size()==2 && KillFeedHud.lineCount()==0 && FlashOverlay.ticksLeft()==0,"new-world packet is accepted after resetting previous world");
  reset(); populated(); MC.screen=new Object(); tick(101); check(KillFeedHud.lineCount()==0 && FlashOverlay.ticksLeft()==0,"open GUI without pause does not freeze HUD timers");
  tick(60); check(CaptureHud.bars().isEmpty(),"capture expires using configured client clock");
  reset(); populated(); Config.KILLFEED_MAX_LINES.value=2; tick(1); check(KillFeedHud.lineCount()==2,"lowering max lines trims pending lines");
  reset(); populated(); Config.SPEC.loaded=false; tick(1); check(empty(),"config unload clears pending HUDs");
 }
 static boolean overlap(GuiGraphics.Rect a,GuiGraphics.Rect b) { return a.left()<b.right() && a.right()>b.left() && a.top()<b.bottom() && a.bottom()>b.top(); }
 static void layoutTests() {
  for(int width:new int[]{100,320,640}) for(int height:new int[]{180,360}) for(double scale:new double[]{0.5,1,2}) {
   reset(); CaptureHud.accept(message()); feed(5); tick(5); Config.KILLFEED_SCALE.value=scale; frame(width,height);
   var capture=new GuiGraphics(); CaptureHud.INSTANCE.render(null,capture,0,width,height);
   var feed=new GuiGraphics(); KillFeedHud.INSTANCE.render(null,feed,0,width,height);
   check(capture.fills.size()==5,"two capture bars remain visible on "+width+"x"+height);
   check((capture.fills.get(2).argb()>>>24)==255 && (capture.fills.get(4).argb()>>>24)==255,"capture fills have full alpha");
   for(var rect:feed.fills) {
    check(!overlap(capture.fills.get(0),rect),"feed avoids capture plate at scale "+scale);
    check(rect.left()>=0 && rect.right()<=width && rect.top()>=0 && rect.bottom()<height/2-10,"feed stays on screen and above crosshair");
   }
   for(var text:feed.texts) check(text.styles()>0,"trimmed feed retains Component styles");
   if(feed.texts.size()>1) check(feed.texts.get(1).y()-feed.texts.get(0).y()==Math.ceil(11*scale),"row spacing is scaled exactly once");
   check(feed.pose().saved.isEmpty(),"render restores pose stack");
  }
  reset(); populated(); tick(5); frame(640,360);
  ClientHudEvents.onBossBar(new net.minecraftforge.client.event.CustomizeGuiOverlayEvent.BossEventProgress(229,12,
   new net.minecraftforge.client.event.CustomizeGuiOverlayEvent.Boss(net.minecraft.network.chat.Component.literal("Boss"))));
  var capture=new GuiGraphics(); CaptureHud.INSTANCE.render(null,capture,0,640,360);
  check(capture.fills.get(0).top()>=21 || capture.fills.get(0).right()<229 || capture.fills.get(0).left()>411,"capture avoids actual boss bar");
  reset(); feed(5); tick(5); Config.KILLFEED_POSITION.value="top_right";
  MC.level.scoreboard.objective=new net.minecraft.world.scores.Objective();
  for(int i=0;i<15;i++) MC.level.scoreboard.scores.add(new net.minecraft.world.scores.Score("Player"+i,100));
  frame(320,180); var feed=new GuiGraphics(); KillFeedHud.INSTANCE.render(null,feed,0,320,180);
  var side=new GuiGraphics.Rect(243,-10,319,135,0);
  check(!feed.fills.isEmpty(),"feed finds space with large scoreboard");
  for(var rect:feed.fills) check(!overlap(rect,side),"feed avoids vanilla scoreboard");
  reset(); feed(1); tick(5); Config.KILLFEED_SCALE.value=2.0;
  KillFeedHud.add("X".repeat(300),"Y".repeat(300),new net.minecraft.world.item.ItemStack(),KillFeedSource.ITEM); tick(5);
  frame(320,180); feed=new GuiGraphics(); KillFeedHud.INSTANCE.render(null,feed,0,320,180);
  check(feed.texts.get(0).text().endsWith("..."),"long styled line has ellipsis");
  check(feed.fills.get(0).right()<320,"long names stay inside viewport at 2x");
  reset(); populated(); MC.options.hideGui=true; frame(640,360);
  var hidden=new GuiGraphics(); CaptureHud.INSTANCE.render(null,hidden,0,640,360); KillFeedHud.INSTANCE.render(null,hidden,0,640,360); FlashOverlay.INSTANCE.render(null,hidden,0,640,360);
  check(hidden.fills.isEmpty() && hidden.texts.isEmpty(),"F1 hides every HUD");
  tick(101); check(KillFeedHud.lineCount()==0 && FlashOverlay.ticksLeft()==0,"F1 does not suspend timing");
  reset(); populated(); frame(40,40); hidden=new GuiGraphics(); CaptureHud.INSTANCE.render(null,hidden,0,40,40); KillFeedHud.INSTANCE.render(null,hidden,0,40,40);
  check(hidden.fills.isEmpty(),"undersized viewport suppresses panels instead of covering crosshair");
 }
 static void dataTests() {
  reset(); var bars=new java.util.ArrayList<>(message().bars());
  CaptureHud.accept(new CaptureHudNetwork.CaptureHudMessage("city","City",bars,false)); bars.clear();
  check(CaptureHud.bars().size()==2,"capture state copies packet list");
  var weapon=new net.minecraft.world.item.ItemStack(); KillFeedHud.add("A","B",weapon,KillFeedSource.ITEM); weapon.name="Changed";
  check(KillFeedHud.describe().get(0).contains("Rifle"),"kill feed snapshots mutable item stack");
  FlashOverlay.accept(1,100); tick(90); FlashOverlay.accept(0.2,20); var graphics=new GuiGraphics(); FlashOverlay.INSTANCE.render(null,graphics,0,640,360);
  check((graphics.fills.get(0).argb()>>>24)==51,"second weak flash uses current brightness");
  FlashOverlay.accept(Double.NaN,1000); check(Double.isFinite(FlashOverlay.intensity()) && FlashOverlay.ticksLeft()==20,"NaN flash is ignored");
 }
 static void tooltipTests() {
  for(String name:new String[]{"usec_villager","bear_pillager","elite_villager","elite_pillager"}) {
   var egg=new com.gfl.tarkovscav.item.FactionSpawnEggItem(()->new net.minecraft.world.entity.EntityType<>(),0,0,new net.minecraft.world.item.Item.Properties());
   egg.descriptionId="item.tarkovscav."+name+"_spawn_egg"; var tooltip=new java.util.ArrayList<net.minecraft.network.chat.Component>();
   egg.appendHoverText(new net.minecraft.world.item.ItemStack(),null,tooltip,null);
   check(tooltip.size()==1 && tooltip.get(0).key().equals(egg.descriptionId+".tooltip"),"spawn egg tooltip "+name);
  }
 }
 static void configReloadTests() {
  MC.tasks.clear(); RigSupport.invalidations=0;
  ClientConfigEvents.onReload(new net.minecraftforge.fml.event.config.ModConfigEvent.Reloading(
   new net.minecraftforge.fml.config.ModConfig(new Object())));
  check(MC.tasks.isEmpty() && RigSupport.invalidations==0,"other config specs leave rig caches unchanged");
  ClientConfigEvents.onReload(new net.minecraftforge.fml.event.config.ModConfigEvent.Reloading(
   new net.minecraftforge.fml.config.ModConfig(Config.SPEC)));
  check(MC.tasks.size()==1,"our config reload queues one client task");
  check(RigSupport.invalidations==0,"reload callback does not mutate rig caches before client execution");
  MC.tasks.remove().run();
  check(RigSupport.invalidations==1 && MC.tasks.isEmpty(),"client execution invalidates rig caches exactly once");
 }
 public static void main(String[] args) { lifecycleTests(); layoutTests(); dataTests(); tooltipTests(); configReloadTests(); System.out.println("PASS "+checks+" production HUD layout/lifecycle/config/tooltip checks"); }
}`
});

fs.mkdirSync(output, { recursive: true });
const sources = [];
for (const [name, source] of Object.entries(fixtures)) {
  const target = path.join(output, 'sources', name);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, source, 'utf8');
  sources.push(target);
}
for (const name of ['HudLayout', 'ClientHudEvents', 'ClientConfigEvents', 'CaptureHud', 'KillFeedHud', 'FlashOverlay']) {
  sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/client', `${name}.java`));
}
sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/item/FactionSpawnEggItem.java'));
sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/killfeed/KillFeedSource.java'));
for (const language of ['en_us', 'zh_cn']) {
  const translations = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/assets/tarkovscav/lang', `${language}.json`), 'utf8'));
  for (const name of ['usec_villager', 'bear_pillager', 'elite_villager', 'elite_pillager']) {
    const key = `item.tarkovscav.${name}_spawn_egg.tooltip`;
    if (!translations[key]) throw new Error(`Missing ${language} translation ${key}`);
  }
}
const classes = path.join(output, 'classes');
fs.mkdirSync(classes, { recursive: true });
const executable = name => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
for (const [command, args] of [
  ['javac', ['-encoding', 'UTF-8', '-d', classes, ...sources]],
  ['java', ['-cp', classes, 'ClientRuntimeTest']]
]) {
  const result = spawnSync(executable(command), args, { cwd: root, encoding: 'utf8' });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
