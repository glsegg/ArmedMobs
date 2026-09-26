package com.gfl.tarkovscav.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.List;

/** Executes production queue/visibility logic with the real Minecraft matrices and GeckoLib bones. */
public final class RenderLifecycleTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void vertex(BufferBuilder builder, float x) {
        builder.vertex(x, 0, 0).color(255, 255, 255, 255).endVertex();
    }

    public static void main(String[] args) {
        DeferredItemPass pass = new DeferredItemPass();
        PoseStack model = new PoseStack();
        model.translate(3, 4, 5);
        model.scale(2, 3, 4);
        Matrix4f position = new Matrix4f(model.last().pose());
        Matrix3f normal = new Matrix3f(model.last().normal());
        int[] draws = {0};
        pass.add(model, captured -> {
            draws[0]++;
            check(captured.last().pose().equals(position), "item must retain the animated hand matrix");
            check(captured.last().normal().equals(normal), "non-uniform-scale normal matrix must survive");
            captured.translate(99, 0, 0);
        });
        check(draws[0] == 0, "bone traversal must not draw the foreign item");
        model.translate(20, 0, 0);
        Matrix4f modelAfterTraversal = new Matrix4f(model.last().pose());
        pass.render();
        check(draws[0] == 1, "completed model renders its queued item exactly once");
        check(model.last().pose().equals(modelAfterTraversal), "item renderer must not mutate the caller stack");
        pass.render();
        check(draws[0] == 1, "next entity must not inherit a stale hand draw");

        pass.add(model, ignored -> draws[0]++);
        pass.clear();
        pass.render();
        check(draws[0] == 1, "cancelled/restarted render drops pending items");
        RuntimeException expected = new RuntimeException("broken third-party renderer");
        pass.add(model, ignored -> { throw expected; });
        pass.add(model, ignored -> draws[0]++);
        try {
            pass.render();
            throw new AssertionError("renderer failure must not be swallowed");
        } catch (RuntimeException actual) {
            check(actual == expected, "preserve the useful third-party exception");
        }
        pass.render();
        check(draws[0] == 1, "failure must also discard the queued items");

        // TaCZ/shader passes can finish a shared buffer. Demonstrate why doing that between bones
        // breaks the old consumer, then run the same foreign flush after the full body submission.
        BufferBuilder buffer = new BufferBuilder(256);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(buffer, 0);
        buffer.end().release();
        try {
            vertex(buffer, 1);
            throw new AssertionError("expected the prematurely ended model buffer to reject a bone");
        } catch (IllegalStateException expectedBufferFailure) {
            check(expectedBufferFailure.getMessage().contains("not started"), "reproduce BufferBuilder failure");
        }
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(buffer, 0);
        pass.add(model, ignored -> buffer.end().release());
        vertex(buffer, 1);
        vertex(buffer, 2);
        vertex(buffer, 3);
        check(buffer.building(), "remaining body bones can submit before the foreign flush");
        pass.render();
        check(!buffer.building(), "foreign draw may safely finish the completed body buffer");

        GeoBone visible = new GeoBone(null, "accessory", false, 0D, false, false);
        GeoBone authoredHidden = new GeoBone(null, "authored", false, 0D, false, false);
        GeoBone unrelated = new GeoBone(null, "unrelated", false, 0D, false, false);
        authoredHidden.setHidden(true);
        unrelated.setHidden(true);
        RigVisibility.hide(visible);
        RigVisibility.hide(visible); // A second renderer can share this same baked bone.
        RigVisibility.hide(authoredHidden);
        check(visible.isHidden(), "config hides its selected accessory");
        RigVisibility.restore(List.of(visible, authoredHidden, unrelated));
        check(!visible.isHidden(), "removing a hide setting restores visible geometry without F3+T");
        check(authoredHidden.isHidden(), "authored hidden state is preserved");
        check(unrelated.isHidden(), "a bone this mod never modified is left alone");
        RigVisibility.hide(visible);
        GeoBone replacement = new GeoBone(null, "accessory", false, 0D, false, false);
        RigVisibility.restore(List.of(replacement));
        check(!replacement.isHidden(), "a rebaked bone does not inherit another bone's state");
        RigVisibility.restore(List.of(visible));
        check(!visible.isHidden(), "shared original can still be restored independently");
        System.out.println("PASS " + checks + " render lifecycle assertions (real matrices, buffer and bones)");
    }
}
