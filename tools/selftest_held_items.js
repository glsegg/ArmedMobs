'use strict';
// Runs the shipped layer's selection/context methods with item/entity API doubles.
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root,
  'src/main/java/com/gfl/tarkovscav/client/GunInHandGeoLayer.java'), 'utf8');
function method(signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`Production method missing: ${signature}`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    else if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}
const output = path.join(root, 'build/audit/held-items');
fs.mkdirSync(output, { recursive: true });
const test = `import java.util.*;
public class HeldItemSelectionTest<T> {
  private String anchorBone="Right", offhandAnchorBone="Left";
  private static final Set<String> WARNED_CONTEXTS=new HashSet<>();
  static final class ItemStack { final String name; final boolean gun;
    ItemStack(String n,boolean g){name=n;gun=g;} boolean isEmpty(){return name.isEmpty();} }
  static final ItemStack EMPTY=new ItemStack("",false);
  static final class LivingEntity { ItemStack main=EMPTY,off=EMPTY;
    ItemStack getMainHandItem(){return main;} ItemStack getOffhandItem(){return off;} }
  record GeoBone(String getName) {}
  static final class Setting<T> { T value; Setting(T v){value=v;} T get(){return value;} }
  static final class Spec { boolean isLoaded(){return true;} }
  static final class Config {
    static final Spec SPEC=new Spec();
    static final Setting<Boolean> RENDER_OFFHAND_ITEM=new Setting<>(true),GUN_TWO_HANDED_SUPPORT=new Setting<>(false);
    static final Setting<String> GUN_MOUNT_DISPLAY_CONTEXT=new Setting<>("FIXED");
    static String gunMountDisplayContext(){return GUN_MOUNT_DISPLAY_CONTEXT.get();}
  }
  static final class TarkovScav { static final Log LOGGER=new Log(); }
  static final class Log { void warn(String s,Object...args){} }
  enum ItemDisplayContext { NONE,FIXED,GUI,GROUND,THIRD_PERSON_LEFT_HAND,THIRD_PERSON_RIGHT_HAND,
    FIRST_PERSON_LEFT_HAND,FIRST_PERSON_RIGHT_HAND }
  private static boolean isGun(ItemStack stack){return !stack.isEmpty()&&stack.gun;}
  private void logMountDecision(GeoBone b,T e,boolean o,ItemStack s){}
  ${method('protected ItemStack getStackForBone(')}
  ${method('private static ItemDisplayContext displayContext(')}
  static int checks;
  static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
  public static void main(String[]args){
    HeldItemSelectionTest<LivingEntity> layer=new HeldItemSelectionTest<>();
    LivingEntity entity=new LivingEntity(); GeoBone right=new GeoBone("Right"),left=new GeoBone("Left");
    for(String name:List.of("bow","crossbow","sword","axe")){
      entity.main=new ItemStack(name,false);
      check(layer.getStackForBone(right,entity)==entity.main,name+" from weapon rack must be visible");
      check(displayContext(false,entity.main)==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
        name+" must use vanilla hand context even when gun context is FIXED");
    }
    entity.main=EMPTY;check(layer.getStackForBone(right,entity)==null,"empty main hand stays empty");
    entity.main=new ItemStack("tacz:ak47",true);
    check(layer.getStackForBone(right,entity)==entity.main,"TaCZ main hand remains visible");
    check(displayContext(false,entity.main)==ItemDisplayContext.FIXED,"guns retain configured context");
    Config.GUN_MOUNT_DISPLAY_CONTEXT.value="FIRST_PERSON_RIGHT_HAND";
    check(displayContext(false,entity.main)==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
      "unsupported first person TaCZ context falls back to visible third person");
    Config.GUN_TWO_HANDED_SUPPORT.value=true;
    check(layer.getStackForBone(left,entity)==entity.main,"empty support hand can display gun copy");
    entity.off=new ItemStack("shield",false);
    check(layer.getStackForBone(left,entity)==entity.off,"real offhand item takes priority over gun copy");
    check(displayContext(true,entity.off)==ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
      "ordinary offhand uses vanilla left hand context");
    check(layer.getStackForBone(new GeoBone("Head"),entity)==null,"unrelated bone never draws an item");
    Config.GUN_MOUNT_DISPLAY_CONTEXT.value="broken";
    check(displayContext(false,entity.main)==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
      "invalid gun context has a visible fallback");
    System.out.println("PASS "+checks+" production held-item selection/context assertions");
  }
}`;
const file = path.join(output, 'HeldItemSelectionTest.java');
fs.writeFileSync(file, test);
const java = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + '.exe') : name;
for (const [command, args] of [[java('javac'), ['--release', '17', '-encoding', 'UTF-8', '-d', output, file]],
                             [java('java'), ['-cp', output, 'HeldItemSelectionTest']]]) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
