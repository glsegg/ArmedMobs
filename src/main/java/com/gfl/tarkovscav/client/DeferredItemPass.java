package com.gfl.tarkovscav.client;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Keeps item renderers that may flush buffers outside the model's bone traversal. */
final class DeferredItemPass {
    private final List<Runnable> draws = new ArrayList<>(2);

    void clear() {
        this.draws.clear();
    }

    void add(PoseStack source, Consumer<PoseStack> draw) {
        // The traversal mutates and pops its stack before this pass runs. Preserve both matrices:
        // normals cannot be reconstructed from a position matrix containing non-uniform scale.
        PoseStack captured = new PoseStack();
        captured.last().pose().set(source.last().pose());
        captured.last().normal().set(source.last().normal());
        this.draws.add(() -> draw.accept(captured));
    }

    void render() {
        try {
            for (Runnable draw : this.draws) {
                draw.run();
            }
        } finally {
            clear();
        }
    }
}
