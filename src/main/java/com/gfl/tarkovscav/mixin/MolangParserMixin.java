package com.gfl.tarkovscav.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Function;

/** GeckoLib parses animation files concurrently while sharing one fastutil variable registry. */
@Mixin(targets = "software.bernie.geckolib.core.molang.MolangParser", remap = false)
public abstract class MolangParserMixin {
    @Redirect(method = "getVariable(Ljava/lang/String;)Lsoftware/bernie/geckolib/core/molang/LazyVariable;",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"),
            require = 1, remap = false)
    private Object tarkovscav$getVariable(Map<Object, Object> registry, Object name,
                                          Function<Object, Object> factory) {
        synchronized (registry) {
            return registry.computeIfAbsent(name, factory);
        }
    }

    @Redirect(method = "register(Lcom/eliotlash/mclib/math/Variable;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 1, remap = false)
    private Object tarkovscav$register(Map<Object, Object> registry, Object name, Object variable) {
        synchronized (registry) {
            return registry.put(name, variable);
        }
    }

    @Redirect(method = "parseOneLine(Ljava/lang/String;Lsoftware/bernie/geckolib/core/molang/expressions/MolangCompoundValue;)Lsoftware/bernie/geckolib/core/molang/expressions/MolangValue;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z", ordinal = 0),
            require = 1, remap = false)
    private static boolean tarkovscav$containsVariable(Map<Object, Object> registry, Object name) {
        // Ordinal 0 is the shared VARIABLES map. The next call reads one statement's local map.
        synchronized (registry) {
            return registry.containsKey(name);
        }
    }
}
