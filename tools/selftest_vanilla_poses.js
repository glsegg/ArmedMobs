// Executes both production vanilla models with minimal entity/mesh API doubles.
// Matrix checks cover gun direction; they cannot certify hand contact with every third-party gun mesh.
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build/audit/vanilla-poses');
const configSource = fs.readFileSync(path.join(root, 'src/main/java/com/gfl/tarkovscav/Config.java'), 'utf8');
const scalar = key => {
 const match = new RegExp(`DEFAULT_GUNNER_VILLAGER_${key} = (-?[\\d.]+)D`).exec(configSource);
 if (!match) throw new Error(`Missing villager default ${key}`);
 return Number(match[1]);
};
const triple = key => {
 const match = new RegExp(`DEFAULT_GUNNER_VILLAGER_${key} = List\\.of\\(([^)]+)\\)`).exec(configSource);
 if (!match) throw new Error(`Missing villager default ${key}`);
 return JSON.parse(`[${match[1]}]`).map(Number).map(value => value + 'f').join(',');
};
const fixtures = {
 'net/minecraft/util/Mth.java': `package net.minecraft.util; public class Mth { public static final float PI=(float)Math.PI, DEG_TO_RAD=PI/180; }`,
 'net/minecraft/world/entity/HumanoidArm.java': `package net.minecraft.world.entity; public enum HumanoidArm { LEFT,RIGHT }`,
 'net/minecraft/world/item/ItemStack.java': `package net.minecraft.world.item;
public class ItemStack { public boolean gun=true,empty; public boolean isEmpty() { return empty; } }`,
 'com/tacz/guns/api/item/IGun.java': `package com.tacz.guns.api.item;
public class IGun { public static IGun getIGunOrNull(net.minecraft.world.item.ItemStack stack) { return stack.gun?new IGun():null; } }`,
 'com/mojang/math/Axis.java': `package com.mojang.math;
public record Axis(char axis) { public static final Axis XP=new Axis('x'),YP=new Axis('y'),ZP=new Axis('z');
 public record Rotation(char axis,double radians) {} public Rotation rotationDegrees(float value) { return new Rotation(axis,Math.toRadians(value)); }
 public Rotation rotation(float value) { return new Rotation(axis,value); } }`,
 'com/mojang/blaze3d/vertex/PoseStack.java': `package com.mojang.blaze3d.vertex;
public class PoseStack { double[] matrix={1,0,0,0,1,0,0,0,1}; double[] translation=new double[3];
 public void translate(double x,double y,double z) { for(int r=0;r<3;r++) translation[r]+=matrix[r*3]*x+matrix[r*3+1]*y+matrix[r*3+2]*z; }
 public double[] origin() { return translation.clone(); }
 void multiply(double[] b) { double[] out=new double[9]; for(int r=0;r<3;r++) for(int c=0;c<3;c++) for(int k=0;k<3;k++) out[r*3+c]+=matrix[r*3+k]*b[k*3+c]; matrix=out; }
 public void scale(float x,float y,float z) { multiply(new double[]{x,0,0,0,y,0,0,0,z}); }
 public void mulPose(com.mojang.math.Axis.Rotation rot) { double c=Math.cos(rot.radians()),s=Math.sin(rot.radians());
 switch(rot.axis()) { case 'x' -> multiply(new double[]{1,0,0,0,c,-s,0,s,c}); case 'y' -> multiply(new double[]{c,0,s,0,1,0,-s,0,c}); case 'z' -> multiply(new double[]{c,-s,0,s,c,0,0,0,1}); } }
 public double[] forward() { return new double[]{-matrix[2],-matrix[5],-matrix[8]}; } }`,
 'net/minecraft/client/model/geom/ModelPart.java': `package net.minecraft.client.model.geom;
public class ModelPart { public float x,y,z,xRot,yRot,zRot; public final java.util.Map<String,ModelPart> children=new java.util.HashMap<>();
 public boolean hasChild(String name) { return children.containsKey(name); } public ModelPart getChild(String name) { return children.get(name); }
 public void translateAndRotate(com.mojang.blaze3d.vertex.PoseStack pose) {
 pose.translate(x/16f,y/16f,z/16f);
 pose.mulPose(com.mojang.math.Axis.ZP.rotation(zRot)); pose.mulPose(com.mojang.math.Axis.YP.rotation(yRot)); pose.mulPose(com.mojang.math.Axis.XP.rotation(xRot)); } }`,
 'net/minecraft/client/model/ArmedModel.java': `package net.minecraft.client.model;
public interface ArmedModel { void translateToHand(net.minecraft.world.entity.HumanoidArm arm,com.mojang.blaze3d.vertex.PoseStack pose); }`,
 'net/minecraft/client/model/VillagerModel.java': `package net.minecraft.client.model;
public class VillagerModel<T> { private final net.minecraft.client.model.geom.ModelPart root;
 public VillagerModel(net.minecraft.client.model.geom.ModelPart root) { this.root=root; }
 public net.minecraft.client.model.geom.ModelPart root() { return root; }
 public void setupAnim(T entity,float swing,float amount,float time,float yaw,float pitch) { } }`,
 'net/minecraft/client/model/IllagerModel.java': `package net.minecraft.client.model;
public class IllagerModel<T> implements ArmedModel { private final net.minecraft.client.model.geom.ModelPart root;
 public IllagerModel(net.minecraft.client.model.geom.ModelPart root) { this.root=root; }
 public net.minecraft.client.model.geom.ModelPart getHead() { return root.getChild("head"); }
 public void setupAnim(T entity,float swing,float amount,float time,float yaw,float pitch) {
  getHead().xRot=pitch*net.minecraft.util.Mth.DEG_TO_RAD; getHead().yRot=yaw*net.minecraft.util.Mth.DEG_TO_RAD;
  var right=root.getChild("right_arm"); var left=root.getChild("left_arm");
  right.xRot=0.125f; left.xRot=-0.125f; right.yRot=left.yRot=0; right.zRot=0.1f; left.zRot=-0.1f;
 }
 public void translateToHand(net.minecraft.world.entity.HumanoidArm arm,com.mojang.blaze3d.vertex.PoseStack pose) {
 root.getChild(arm==net.minecraft.world.entity.HumanoidArm.RIGHT?"right_arm":"left_arm").translateAndRotate(pose); } }`,
 'com/gfl/tarkovscav/entity/GunnerVillagerEntity.java': `package com.gfl.tarkovscav.entity;
public class GunnerVillagerEntity { public com.gfl.tarkovscav.gun.GunAiState state=com.gfl.tarkovscav.gun.GunAiState.IDLE;
 public final net.minecraft.world.item.ItemStack stack=new net.minecraft.world.item.ItemStack();
 public com.gfl.tarkovscav.gun.GunAiState gunAiState() { return state; } public net.minecraft.world.item.ItemStack getMainHandItem() { return stack; } }`,
 'com/gfl/tarkovscav/entity/GunnerPillagerEntity.java': `package com.gfl.tarkovscav.entity;
public class GunnerPillagerEntity { public com.gfl.tarkovscav.gun.GunAiState state=com.gfl.tarkovscav.gun.GunAiState.IDLE;
 public final net.minecraft.world.item.ItemStack stack=new net.minecraft.world.item.ItemStack(); public boolean pistol;
 public net.minecraft.world.item.ItemStack getMainHandItem() { return stack; } public boolean usesPistolClips() { return pistol; }
 public net.minecraft.world.entity.HumanoidArm arm=net.minecraft.world.entity.HumanoidArm.RIGHT;
 public net.minecraft.world.entity.HumanoidArm getMainArm() { return arm; } }`,
 'com/gfl/tarkovscav/client/RigSupport.java': `package com.gfl.tarkovscav.client;
public class RigSupport { public static ArmPose armPose(com.gfl.tarkovscav.entity.GunnerPillagerEntity entity) { return ArmPose.forState(entity.state); } }`,
 'com/gfl/tarkovscav/Config.java': `package com.gfl.tarkovscav;
public class Config {
 public static float gunnerVillagerAimArmPitch() { return ${scalar('AIM_ARM_PITCH')}f; } public static float gunnerVillagerHoldArmPitch() { return ${scalar('HOLD_ARM_PITCH')}f; }
 public static float gunnerVillagerReloadArmPitch() { return ${scalar('RELOAD_ARM_PITCH')}f; } public static float gunnerVillagerHunkerArmPitch() { return ${scalar('HUNKER_ARM_PITCH')}f; }
 public static boolean body; public static float scale=1; public static float[] rotation={${triple('GUN_ROTATION')}};
 public static boolean gunnerVillagerGunOnBody() { return body; } public static float gunnerVillagerGunScale() { return scale; }
 public static float[] gunnerVillagerGunRotation() { return rotation.clone(); }
 public static float[] gunnerVillagerIdleGunRotation() { return new float[]{${triple('IDLE_GUN_ROTATION')}}; }
 public static float[] gunnerVillagerReloadGunRotation() { return new float[]{${triple('RELOAD_GUN_ROTATION')}}; }
 public static float[] gunnerVillagerHunkerGunRotation() { return new float[]{${triple('HUNKER_GUN_ROTATION')}}; }
 public static float[] gunnerVillagerGunOffset() { return new float[3]; }
 public static float[] gunnerVillagerIdleGunOffset() { return new float[3]; }
 public static float[] gunnerVillagerReloadGunOffset() { return new float[3]; }
 public static float[] gunnerVillagerHunkerGunOffset() { return new float[3]; }
}`,
 'VanillaPosesTest.java': `import com.gfl.tarkovscav.client.*; import com.gfl.tarkovscav.entity.*; import com.gfl.tarkovscav.gun.GunAiState;
import net.minecraft.client.model.geom.ModelPart; import net.minecraft.world.entity.HumanoidArm;
import com.mojang.blaze3d.vertex.PoseStack; import com.mojang.math.Axis;
public class VanillaPosesTest {
 static int checks; static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
 static void near(double actual,double expected,String message) { check(Math.abs(actual-expected)<0.000001,message+": "+actual+" != "+expected); }
 static ModelPart root() { var root=new ModelPart(); for(String name:new String[]{"arms","head","right_arm","left_arm"}) root.children.put(name,new ModelPart()); root.getChild("arms").xRot=-0.75f; root.getChild("arms").y=3; root.getChild("arms").z=-1; return root; }
 static ModelPart arm(ModelPart root,HumanoidArm side) { return root.getChild(side==HumanoidArm.RIGHT?"right_arm":"left_arm"); }
 static double radians(float degrees) { return degrees*Math.PI/180; }
 static void assertForward(PoseStack pose,double yaw,double pitch,String label) { double[] actual=pose.forward();
  near(actual[0],-Math.sin(yaw)*Math.cos(pitch),label+" X"); near(actual[1],Math.sin(pitch),label+" Y"); near(actual[2],-Math.cos(yaw)*Math.cos(pitch),label+" Z"); }
 static void handFrame(PoseStack pose) { // Local MC ItemInHandLayer plus TaCZ XY conversion, omitting translations.
  pose.mulPose(Axis.XP.rotationDegrees(-90)); pose.mulPose(Axis.YP.rotationDegrees(180)); pose.scale(-1,-1,1); }
 static void pillager() {
  var root=root(); var model=new GunnerPillagerArmModel(root); var mob=new GunnerPillagerEntity();
  for(int weapon=0;weapon<4;weapon++) for(var side:HumanoidArm.values()) for(var state:GunAiState.values()) for(float yaw:new float[]{-45,0,45}) for(float pitch:new float[]{-45,0,45}) {
   mob.stack.empty=weapon==3; mob.stack.gun=weapon<2; mob.pistol=weapon==1; boolean longGun=weapon==0;
   mob.arm=side; mob.state=state; ModelPart main=arm(root,side), support=arm(root,side==HumanoidArm.RIGHT?HumanoidArm.LEFT:HumanoidArm.RIGHT);
   float sign=side==HumanoidArm.RIGHT?1:-1;
   for(int frame=0;frame<5;frame++) {
    // Poison all model rotations first: setupAnim must be frame-independent.
    main.xRot=99; main.yRot=98; main.zRot=97; support.xRot=96; support.yRot=95; support.zRot=94;
    model.setupAnim(mob,0,0,frame,yaw,pitch);
    switch(ArmPose.forState(state)) {
     case RAISED -> {
      near(main.xRot,-Math.PI/2+radians(pitch),"full main pitch"); near(main.yRot,radians(yaw),"main yaw matches head");
      near(support.xRot,-Math.PI/2+radians(pitch),"support full pitch"); near(support.yRot,radians(yaw)+sign*0.5,"mirrored support yaw");
      near(main.zRot,0,"gun is free of melee roll"); near(support.zRot,0,"support melee roll cleared");
      var pose=new PoseStack(); model.translateToHand(side,pose); handFrame(pose);
      assertForward(pose,radians(yaw),radians(pitch),"gun direction");
     }
     case RELOADING -> {
      near(main.xRot,longGun?radians(-65)+radians(pitch)*0.15:-0.5+radians(pitch)*0.3,"reload dominant pitch"); near(main.yRot,radians(yaw)*0.4,"reload dominant yaw");
      near(support.xRot,longGun?radians(-65):-1,"reload support pitch"); near(support.yRot,sign*0.6,"reload mirrored support yaw");
     }
     case HUNKERED -> { near(main.xRot,longGun?radians(-65):-0.35,"hunker main"); near(support.xRot,main.xRot,"hunker support"); near(main.yRot,0,"hunker yaw"); near(support.yRot,longGun?sign*0.5:0,"hunker support mirrors handedness"); }
     case LOWERED -> {
      if(longGun) { near(main.xRot,radians(-65),"long gun low ready"); near(main.yRot,0,"carry follows body");
       near(support.xRot,main.xRot,"support carries long gun"); near(support.yRot,sign*0.5,"carry support mirrors handedness");
      } else { near(root.getChild("right_arm").xRot,0.125,"pistol/ordinary/empty idle vanilla right preserved"); near(root.getChild("left_arm").xRot,-0.125,"pistol/ordinary/empty idle vanilla left preserved"); }
     }
    }
    if(longGun && ArmPose.forState(state)!=ArmPose.RAISED) {
     near(main.zRot,0,"long gun carry clears old melee roll"); near(support.zRot,0,"long gun carry support clears roll");
     var pose=new PoseStack(); model.translateToHand(side,pose); handFrame(pose);
     double downward=pose.forward()[1]; check(downward>=0 && downward<0.55,"long-gun muzzle stays within 33 degrees below horizontal");
    }
   }
  }
 }
 static void villager() {
  var root=root(); var model=new GunnerVillagerModel(root); var mob=new GunnerVillagerEntity(); var arms=root.getChild("arms");
  for(var state:GunAiState.values()) for(float yaw:new float[]{-45,0,45}) for(float pitch:new float[]{-45,0,45}) {
   mob.state=state;
   for(int frame=0;frame<5;frame++) {
    arms.xRot=99; arms.yRot=98;
    model.setupAnim(mob,0,0,frame,yaw,pitch);
    if(ArmPose.forState(state)==ArmPose.RAISED) {
     near(arms.xRot,-0.75+radians(com.gfl.tarkovscav.Config.gunnerVillagerAimArmPitch()+pitch),"villager full aim pitch"); near(arms.yRot,radians(yaw),"villager aim yaw follows head");
     var pose=new PoseStack(); model.translateToGunGrip(HumanoidArm.RIGHT,pose); handFrame(pose); model.applyGunGripTransform(pose);
     // Ten degrees rounds the exact neutral calibration (9.971835 degrees) to within 0.03 degrees.
     double baseline=-0.75+radians(com.gfl.tarkovscav.Config.gunnerVillagerAimArmPitch()+com.gfl.tarkovscav.Config.gunnerVillagerGunRotation()[0]+90);
     assertForward(pose,radians(yaw),radians(pitch)+baseline,"villager calibrated direction follows target");
    } else {
     float armPitch=switch(ArmPose.forState(state)) {
      case LOWERED -> com.gfl.tarkovscav.Config.gunnerVillagerHoldArmPitch();
      case RELOADING -> com.gfl.tarkovscav.Config.gunnerVillagerReloadArmPitch();
      case HUNKERED -> com.gfl.tarkovscav.Config.gunnerVillagerHunkerArmPitch(); default -> throw new AssertionError(); };
     float[] delta=switch(ArmPose.forState(state)) {
      case LOWERED -> com.gfl.tarkovscav.Config.gunnerVillagerIdleGunRotation();
      case RELOADING -> com.gfl.tarkovscav.Config.gunnerVillagerReloadGunRotation();
      case HUNKERED -> com.gfl.tarkovscav.Config.gunnerVillagerHunkerGunRotation(); default -> throw new AssertionError(); };
     near(arms.xRot,-0.75+radians(armPitch),"non-aim villager pose uses configured arm rest"); near(arms.yRot,0,"non-aim resets stale yaw");
     var pose=new PoseStack(); model.translateToGunGrip(HumanoidArm.RIGHT,pose); handFrame(pose); model.applyGunGripTransform(pose);
     double direction=-0.75+radians(armPitch+com.gfl.tarkovscav.Config.gunnerVillagerGunRotation()[0]+delta[0]+90);
     assertForward(pose,0,direction,"non-aim villager weapon direction uses pose delta");
    }
   }
  }
  mob.state=GunAiState.AIM; model.setupAnim(mob,0,0,0,45,45); mob.stack.empty=true; model.setupAnim(mob,0,0,0,-45,-45);
  near(arms.xRot,-0.75,"unarmed villager restores rest pitch"); near(arms.yRot,0,"unarmed villager restores rest yaw");
 }
 static void grip() {
  var root=root(); var model=new GunnerVillagerModel(root); var mob=new GunnerVillagerEntity();
  for(var state:GunAiState.values()) for(var hand:HumanoidArm.values()) for(float pitch:new float[]{-45,0,45}) for(boolean body:new boolean[]{false,true}) {
   mob.state=state; com.gfl.tarkovscav.Config.body=body; model.setupAnim(mob,0,0,0,45,pitch);
   double ax=body?-0.75:root.getChild("arms").xRot, ay=body?0:root.getChild("arms").yRot;
   double x=hand==HumanoidArm.RIGHT?-0.125:0.125;
   double y=Math.cos(ax)*0.25+Math.sin(ax)*0.125, z=Math.sin(ax)*0.25-Math.cos(ax)*0.125;
   double[] expected={Math.cos(ay)*x+Math.sin(ay)*z,3/16.0+y,-1/16.0-Math.sin(ay)*x+Math.cos(ay)*z};
   for(float rotation:new float[]{-90,0,90}) for(float scale:new float[]{0.25f,1,3}) {
    com.gfl.tarkovscav.Config.rotation=new float[]{rotation,rotation/2,rotation/3}; com.gfl.tarkovscav.Config.scale=scale;
    var pose=new PoseStack(); model.translateToGunGrip(hand,pose); handFrame(pose); model.applyGunGripTransform(pose);
    double[] actual=pose.origin(); for(int i=0;i<3;i++) near(actual[i],expected[i],"rotating/scaling keeps grip on crossed-arm front face");
   }
  }
  com.gfl.tarkovscav.Config.body=false; com.gfl.tarkovscav.Config.rotation=new float[]{${triple('GUN_ROTATION')}}; com.gfl.tarkovscav.Config.scale=1;
 }
 public static void main(String[] args) { pillager(); villager(); grip(); System.out.println("PASS "+checks+" production vanilla pose/frame/grip checks"); }
}`
};
fs.mkdirSync(output, { recursive: true });
const sources = [];
for (const [name, text] of Object.entries(fixtures)) {
 const target = path.join(output, 'sources', name); fs.mkdirSync(path.dirname(target), { recursive: true });
 fs.writeFileSync(target, text, 'utf8'); sources.push(target);
}
for (const name of ['GunnerPillagerArmModel', 'GunnerVillagerModel', 'GunGripModel', 'ArmPose']) sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/client', `${name}.java`));
sources.push(path.join(root, 'src/main/java/com/gfl/tarkovscav/gun/GunAiState.java'));
const classes = path.join(output, 'classes'); fs.mkdirSync(classes, { recursive: true });
const executable = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
for (const [command, args] of [['javac', ['-encoding', 'UTF-8', '-d', classes, ...sources]], ['java', ['-cp', classes, 'VanillaPosesTest']]]) {
 const result = spawnSync(executable(command), args, { cwd: root, encoding: 'utf8' });
 if (result.stdout) process.stdout.write(result.stdout); if (result.stderr) process.stderr.write(result.stderr);
 if (result.error) throw result.error; if (result.status !== 0) process.exit(result.status || 1);
}
