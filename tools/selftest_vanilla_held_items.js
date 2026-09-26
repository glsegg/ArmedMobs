// Execute the production TaCZ layer with TaCZ's observed LEFT-arm cancellation in the superclass.
// A mere context change followed by super.renderArmWithItem must fail this regression.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build/audit/vanilla-held-items');
const fixtures = {
  'com/mojang/blaze3d/vertex/PoseStack.java': `package com.mojang.blaze3d.vertex;
public class PoseStack { public int depth; public java.util.List<String> events=new java.util.ArrayList<>();
 public void pushPose() { depth++; } public void popPose() { depth--; }
 public void translate(float x,float y,float z) { events.add("translate:"+x+","+y+","+z); }
 public void mulPose(String rotation) { events.add(rotation); } }`,
  'com/mojang/math/Axis.java': `package com.mojang.math;
public record Axis(String name) { public static final Axis XP=new Axis("X"),YP=new Axis("Y"); public String rotationDegrees(float value) { return name+value; } }`,
  'net/minecraft/world/entity/LivingEntity.java': 'package net.minecraft.world.entity; public class LivingEntity { public boolean mainGun; }',
  'net/minecraft/world/entity/HumanoidArm.java': 'package net.minecraft.world.entity; public enum HumanoidArm { RIGHT,LEFT }',
  'net/minecraft/world/item/ItemDisplayContext.java': `package net.minecraft.world.item;
public enum ItemDisplayContext { THIRD_PERSON_LEFT_HAND,THIRD_PERSON_RIGHT_HAND,FIRST_PERSON_LEFT_HAND,FIRST_PERSON_RIGHT_HAND,GUI }`,
  'net/minecraft/world/item/ItemStack.java': `package net.minecraft.world.item;
public class ItemStack { public final boolean gun; public boolean empty; public ItemStack(boolean gun) { this.gun=gun; } public boolean isEmpty() { return empty; } }`,
  'com/tacz/guns/api/item/IGun.java': `package com.tacz.guns.api.item;
public class IGun { public static IGun getIGunOrNull(net.minecraft.world.item.ItemStack stack) { return stack.gun?new IGun():null; } }`,
  'net/minecraft/client/model/EntityModel.java': `package net.minecraft.client.model;
public abstract class EntityModel<T extends net.minecraft.world.entity.LivingEntity> { }`,
  'net/minecraft/client/model/ArmedModel.java': `package net.minecraft.client.model;
public interface ArmedModel { void translateToHand(net.minecraft.world.entity.HumanoidArm arm,com.mojang.blaze3d.vertex.PoseStack pose); }`,
  'net/minecraft/client/renderer/ItemInHandRenderer.java': `package net.minecraft.client.renderer;
public class ItemInHandRenderer { public static int calls; public static boolean left,fail; public static net.minecraft.world.item.ItemDisplayContext context;
 public record Call(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.item.ItemStack stack,
 com.mojang.blaze3d.vertex.PoseStack pose,MultiBufferSource buffers,int light) {} public static Call last;
 public void renderItem(net.minecraft.world.entity.LivingEntity e,net.minecraft.world.item.ItemStack s,net.minecraft.world.item.ItemDisplayContext c,boolean l,
 com.mojang.blaze3d.vertex.PoseStack p,MultiBufferSource b,int light) {
 calls++; left=l; context=c; last=new Call(e,s,p,b,light); p.events.add("draw"); if(fail) throw new IllegalStateException("test draw failure"); } }`,
  'net/minecraft/client/renderer/MultiBufferSource.java': 'package net.minecraft.client.renderer; public interface MultiBufferSource {}',
  'net/minecraft/client/renderer/entity/RenderLayerParent.java': `package net.minecraft.client.renderer.entity;
public interface RenderLayerParent<T extends net.minecraft.world.entity.LivingEntity,M extends net.minecraft.client.model.EntityModel<T>> { M getModel(); }`,
  'net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java': `package net.minecraft.client.renderer.entity.layers;
import net.minecraft.world.entity.*; import net.minecraft.world.item.*; import net.minecraft.client.model.*;
import net.minecraft.client.renderer.*; import net.minecraft.client.renderer.entity.RenderLayerParent;
import com.mojang.blaze3d.vertex.PoseStack;
public class ItemInHandLayer<T extends LivingEntity,M extends EntityModel<T> & ArmedModel> {
 public record Call(LivingEntity entity,ItemStack stack,ItemDisplayContext context,HumanoidArm arm,PoseStack pose,MultiBufferSource buffers,int light) {}
 public static Call last; public static int calls,cancelled,draws;
 private final RenderLayerParent<T,M> parent;
 public ItemInHandLayer(RenderLayerParent<T,M> parent,ItemInHandRenderer renderer) { this.parent=parent; }
 protected M getParentModel() { return parent==null?null:parent.getModel(); }
 protected void renderArmWithItem(LivingEntity e,ItemStack s,ItemDisplayContext c,HumanoidArm a,PoseStack p,MultiBufferSource b,int light) {
 last=new Call(e,s,c,a,p,b,light); calls++;
 // TaCZ 1.1.8 ItemInHandLayerMixin.renderArmWithItemHead cancels by physical arm, not context.
 if(e.mainGun && a==HumanoidArm.LEFT) { cancelled++; return; }
 if(!s.isEmpty()) { draws++; p.events.add("vanilla"); } } }`,
  'VanillaHeldItemsTest.java': `import com.gfl.tarkovscav.client.TaczItemInHandLayer;
import net.minecraft.world.entity.*; import net.minecraft.world.item.*; import net.minecraft.client.model.*;
import net.minecraft.client.renderer.*; import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import com.mojang.blaze3d.vertex.PoseStack;
public class VanillaHeldItemsTest {
 static class Model extends EntityModel<LivingEntity> implements ArmedModel {
  public void translateToHand(HumanoidArm arm,PoseStack pose) { pose.events.add("hand:"+arm); }
 }
 static class GripModel extends Model implements com.gfl.tarkovscav.client.GunGripModel {
  public void translateToGunGrip(HumanoidArm arm,PoseStack pose) { pose.events.add("grip:"+arm); }
  public void applyGunGripTransform(PoseStack pose) { pose.events.add("scale"); }
 }
 static class Layer extends TaczItemInHandLayer<LivingEntity,Model> {
  Layer(Model model) { super(()->model,new ItemInHandRenderer()); }
  void render(LivingEntity e,ItemStack s,ItemDisplayContext c,HumanoidArm a,PoseStack p,MultiBufferSource b,int light) {
   renderArmWithItem(e,s,c,a,p,b,light); }
 }
 static int checks; static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
 public static void main(String[] args) {
  LivingEntity mob=new LivingEntity(); PoseStack pose=new PoseStack(); MultiBufferSource buffers=new MultiBufferSource(){};
  for(boolean grip:new boolean[]{false,true}) for(HumanoidArm arm:HumanoidArm.values()) for(ItemDisplayContext context:ItemDisplayContext.values()) {
   Layer layer=new Layer(grip?new GripModel():new Model()); var stack=new ItemStack(true); mob.mainGun=true;
   int before=ItemInHandLayer.calls, beforeDraw=ItemInHandRenderer.calls; pose.events.clear();
   layer.render(mob,stack,context,arm,pose,buffers,15728880);
   var call=ItemInHandRenderer.last;
   check(ItemInHandLayer.calls==before,"TaCZ guns never enter the superclass cancelled by its mixin");
   check(ItemInHandRenderer.calls==beforeDraw+1,"exactly one direct gun draw");
   check(ItemInHandRenderer.context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,"gun gets the visible TaCZ third-person context");
   check(ItemInHandRenderer.left==(arm==HumanoidArm.LEFT),"actual hand mirror is preserved");
   check(call.entity()==mob && call.stack()==stack,"entity and exact gun stack are preserved");
   check(call.pose()==pose && call.buffers()==buffers,"hand pose and render buffers are preserved");
   check(call.light()==15728880,"original packed light");
   var expected=grip?java.util.List.of("grip:"+arm,"X-90.0","Y180.0","scale","draw")
    :java.util.List.of("hand:"+arm,"X-90.0","Y180.0","translate:"+(arm==HumanoidArm.LEFT?-0.0625f:0.0625f)+",0.125,-0.625","draw");
   check(pose.events.equals(expected),"correct hand frame; crossed-arm socket skips humanoid shoulder translation");
   check(pose.depth==0,"direct gun draw balances its pose stack");
   ItemInHandRenderer.fail=true;
   try { layer.render(mob,stack,context,arm,pose,buffers,15728880); throw new AssertionError("expected test renderer failure"); }
   catch(IllegalStateException expectedFailure) { check(pose.depth==0,"renderer failure also restores pose stack"); }
   finally { ItemInHandRenderer.fail=false; }
  }
  for(boolean grip:new boolean[]{false,true}) for(var arm:HumanoidArm.values()) for(var context:ItemDisplayContext.values()) {
   Layer layer=new Layer(grip?new GripModel():new Model()); var stack=new ItemStack(false); mob.mainGun=false;
   int prior=ItemInHandLayer.calls, priorDraw=ItemInHandRenderer.calls; layer.render(mob,stack,context,arm,pose,buffers,15728880);
   var call=ItemInHandLayer.last;
   check(ItemInHandLayer.calls==prior+1 && ItemInHandRenderer.calls==priorDraw,"ordinary equipment still delegates only to vanilla");
   check(call.arm()==arm && call.context()==context,"ordinary equipment preserves hand and context");
   stack.empty=true; priorDraw=ItemInHandRenderer.calls; layer.render(mob,stack,context,arm,pose,buffers,15728880);
   check(ItemInHandRenderer.calls==priorDraw && pose.depth==0,"empty equipment causes no custom render");
  }
  // Prove the negative control: passing RIGHT context to TaCZ's mixed-in LEFT arm still cancels.
  mob.mainGun=true; var ordinary=new ItemStack(false); int cancelled=ItemInHandLayer.cancelled;
  new Layer(new Model()).render(mob,ordinary,ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,HumanoidArm.LEFT,pose,buffers,15728880);
  check(ItemInHandLayer.cancelled==cancelled+1,"fixture reproduces actual mixin cancellation independent of display context");
  System.out.println("PASS "+checks+" production vanilla held-item compatibility checks");
 }
}`
};
fs.mkdirSync(output, { recursive: true });
const sources = [];
for (const [name, text] of Object.entries(fixtures)) {
  const target = path.join(output, 'sources', name);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, text, 'utf8');
  sources.push(target);
}
sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/client/TaczItemInHandLayer.java'));
sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/client/GunGripModel.java'));
const classes = path.join(output, 'classes');
fs.mkdirSync(classes, { recursive: true });
const executable = name => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
for (const [command, args] of [
  ['javac', ['-encoding', 'UTF-8', '-d', classes, ...sources]],
  ['java', ['-cp', classes, 'VanillaHeldItemsTest']]
]) {
  const result = spawnSync(executable(command), args, { cwd: root, encoding: 'utf8' });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
const read = name => fs.readFileSync(path.join(root, 'src/main/java/com/gfl/tarkovscav/client', name), 'utf8');
if (!read('GunnerPillagerRenderer.java').includes('new TaczItemInHandLayer<>')) throw new Error('Pillager compatibility layer is not wired');
if (!read('GunnerVillagerRenderer.java').includes('extends TaczItemInHandLayer<')) throw new Error('Villager compatibility layer is not wired');
console.log('PASS both vanilla renderer families use the compatibility layer');
