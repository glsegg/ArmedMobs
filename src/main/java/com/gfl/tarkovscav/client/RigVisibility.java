package com.gfl.tarkovscav.client;

import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Tracks only hidden flags this mod changed; a rebaked model is free to be collected. */
final class RigVisibility {
    // Baked bones are shared between renderer instances, so this must not be instance-local.
    // GeoBone.equals compares bone names, so a WeakHashMap would mix different rigs/rebakes.
    private static final List<HiddenBone> ORIGINAL = new ArrayList<>();

    private RigVisibility() {
    }

    static void restore(Iterable<? extends CoreGeoBone> bones) {
        Set<CoreGeoBone> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CoreGeoBone bone : bones) {
            current.add(bone);
        }
        for (var iterator = ORIGINAL.iterator(); iterator.hasNext();) {
            HiddenBone original = iterator.next();
            CoreGeoBone bone = original.bone().get();
            if (bone == null) {
                iterator.remove();
            } else if (current.contains(bone)) {
                bone.setHidden(original.hidden());
                iterator.remove();
            }
        }
    }

    static void hide(CoreGeoBone bone) {
        ORIGINAL.removeIf(original -> original.bone().get() == null);
        if (ORIGINAL.stream().noneMatch(original -> original.bone().get() == bone)) {
            ORIGINAL.add(new HiddenBone(new WeakReference<>(bone), bone.isHidden()));
        }
        bone.setHidden(true);
    }

    private record HiddenBone(WeakReference<CoreGeoBone> bone, boolean hidden) {
    }
}
