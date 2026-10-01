import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import com.tacz.guns.client.resource.pojo.TransformScale;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

/** Tests TaCZ's real inverse positioning and real Bedrock node matrices, without a GL context. */
public final class TaczGripFrameTest {
    private static int checks;
    private static final Gson GSON = new Gson();
    private static Method position;
    private static Method scale;
    private static Field thirdPerson;
    private static final List<Matrix4f> productionMounts = new ArrayList<>();
    private static final List<Vector3f> locatorOrigins = new ArrayList<>();

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static final class ProbeModel extends BedrockModel {
        ProbeModel(JsonObject raw) { super(GSON.fromJson(raw, BedrockModelPOJO.class), BedrockVersion.NEW); }
        List<BedrockPart> path(String name) { return getPath(modelMap.get(name)); }
    }

    private static Matrix4f drawGrip(List<BedrockPart> path, Vector3f size) throws Exception {
        PoseStack pose = new PoseStack();
        // Exact ItemRenderer.render + GunItemRendererWrapper.lambda$renderByItem$6 prefix.
        // modern_kinetic_gun.json has only parent=builtin/entity: camera transform is identity.
        pose.translate(-.5F, -.5F, -.5F);
        pose.translate(.5, 2, .5);
        pose.scale(-1, -1, 1);
        position.invoke(null, path, pose, size);
        TransformScale transform = new TransformScale();
        thirdPerson.set(transform, size);
        scale.invoke(null, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, transform, pose);
        for (BedrockPart part : path) part.translateAndRotateAndScale(pose);
        return new Matrix4f(pose.last().pose());
    }

    private static void verify(String name, List<BedrockPart> path, Vector3f size) throws Exception {
        check(path != null && !path.isEmpty(), name + ": missing thirdperson_hand");
        Matrix4f actual = drawGrip(path, size);
        Matrix4f expected = new Matrix4f().scaling(-size.x, -size.y, size.z);
        check(actual.equals(expected, .00002F), name + ": grip frame differs from Fxy * scale:\n" + actual);
        check(actual.transformPosition(new Vector3f()).length() < .00002F,
                name + ": final grip must be item input origin, not y=1.5");
        for (int i = 0; i < productionMounts.size(); i++) {
            Vector3f worldGrip = new Matrix4f(productionMounts.get(i)).mul(actual).transformPosition(new Vector3f());
            check(worldGrip.distance(locatorOrigins.get(i)) < .00003F,
                    name + ": actual TaCZ grip misses Gecko locator in animation sample " + i);
        }
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        position = GunItemRendererWrapper.class.getDeclaredMethod("applyPositioningNodeTransform",
                List.class, PoseStack.class, Vector3f.class);
        position.setAccessible(true);
        scale = GunItemRendererWrapper.class.getDeclaredMethod("applyScaleTransform",
                ItemDisplayContext.class, TransformScale.class, PoseStack.class);
        scale.setAccessible(true);
        thirdPerson = TransformScale.class.getDeclaredField("thirdPerson");
        thirdPerson.setAccessible(true);
        Path samplesFile = Path.of("matrix-samples.json");
        check(Files.isRegularFile(samplesFile), "Run node tools/mount_matrix.js before this cross-renderer test");
        for (var entry : JsonParser.parseString(Files.readString(samplesFile)).getAsJsonArray()) {
            JsonObject row = entry.getAsJsonObject();
            float[] matrix = new float[16];
            for (int i = 0; i < 16; i++) matrix[i] = row.getAsJsonArray("productionMatrixColumnMajor").get(i).getAsFloat();
            productionMounts.add(new Matrix4f().set(matrix));
            var locator = row.getAsJsonArray("locator");
            locatorOrigins.add(new Vector3f(locator.get(0).getAsFloat(), locator.get(1).getAsFloat(), locator.get(2).getAsFloat()));
        }
        check(!productionMounts.isEmpty(), "No Gecko animation poses checked");
        Path assets = Path.of(args[0]);
        try (var itemJson = GunItemRendererWrapper.class.getResourceAsStream("/assets/tacz/models/item/modern_kinetic_gun.json")) {
            check(itemJson != null, "Missing real TaCZ item model JSON");
            JsonObject item = JsonParser.parseString(new String(itemJson.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            check(item.get("parent").getAsString().equals("builtin/entity") && !item.has("display"),
                    "TaCZ camera transform changed; include its actual item display matrix in this test");
        }
        int displays = 0;
        try (var paths = Files.walk(assets.resolve("display/guns"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject display = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (display.has("transform") && display.getAsJsonObject("transform").has("scale")
                        && display.getAsJsonObject("transform").getAsJsonObject("scale").has("thirdperson")) {
                    var values = display.getAsJsonObject("transform").getAsJsonObject("scale").getAsJsonArray("thirdperson");
                    check(values.size() == 3 && values.get(0).getAsFloat() == values.get(1).getAsFloat()
                                    && values.get(1).getAsFloat() == values.get(2).getAsFloat(),
                            "Installed pack uses nonuniform thirdperson scale: " + file);
                }
                displays++;
            }
        }
        check(displays > 0, "No installed display scales checked");
        int models = 0;
        try (var paths = Files.walk(assets.resolve("geo_models/gun"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject raw = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                // Cubes do not contribute to the grip chain; avoid a UV adapter and GL allocation.
                // Bone pivots/rotations/parentage are untouched and baked by the shipped TaCZ class.
                for (var bone : raw.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                        .getAsJsonArray("bones")) bone.getAsJsonObject().remove("cubes");
                List<BedrockPart> path = new ProbeModel(raw).path("thirdperson_hand");
                if (path == null) throw new AssertionError("Missing grip in installed model: " + file);
                for (float size : new float[]{.6F, 1F, 1.3F})
                    verify(file.getFileName().toString(), path, new Vector3f(size));
                models++;
            }
        }
        // Exercise inverse order with nonzero rotations on every path node, rather than only
        // the mostly unrotated stock positioning groups. Uniform scaling matches the installed pack.
        BedrockPart root = new BedrockPart("positioning");
        root.setPos(8, 25, -1); root.xRot = .21F; root.yRot = -.34F; root.zRot = .41F;
        BedrockPart grip = new BedrockPart("thirdperson_hand");
        grip.setPos(-8, -5.075F, .85F); grip.xRot = -.28F; grip.yRot = .33F; grip.zRot = -.17F;
        root.addChild(grip);
        Field parent = BedrockPart.class.getDeclaredField("parent");
        parent.setAccessible(true);
        parent.set(grip, root); // addChild only populates children; BedrockModel also sets this field.
        for (float size : new float[]{.6F, 1F, 1.3F}) verify("rotated synthetic chain", List.of(root, grip), new Vector3f(size));
        check(models > 0, "No installed gun models checked");
        System.out.println("PASS " + checks + " TaCZ grip assertions across " + models
                + " installed high/low-detail models, " + displays + " gun displays, plus rotated chains. Final grip origin=(0,0,0), frame=Fxy*S.");
        System.out.println("Cross-renderer grip coincides with Gecko locator across " + productionMounts.size()
                + " production animation poses for every model/scale chain.");
        System.out.println("LIMIT: uniform pack scale and rest positioning nodes; replacement item JSON display transforms, animated positioning nodes and nonuniform scale need separate checks.");
    }
}
