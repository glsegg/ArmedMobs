package com.gfl.tarkovscav.mixin;

import com.gfl.tarkovscav.worldgen.WastelandSpreadPlacement;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/** Supplies the level to our two-grid placement without changing vanilla or another mod's placements. */
@Mixin(value = ChunkGenerator.class, remap = false)
public abstract class CityLocateMixin {
    // Both names are explicit because this project does not generate a Mixin refmap. Each group must
    // match exactly one call: official names in development, SRG names in the reobfuscated Forge JAR.
    @Group(name = "tarkovscav$locateSpacing", min = 1, max = 1)
    @Redirect(method = {
            "getNearestGeneratedStructure(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;",
            "m_223188_(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;spacing()I"),
            require = 0, expect = 0, remap = false)
    private static int tarkovscav$spacingDevelopment(RandomSpreadStructurePlacement placement,
            Set<Holder<Structure>> structures, LevelReader level, StructureManager manager,
            int x, int z, int radius, boolean skipKnown, long seed, RandomSpreadStructurePlacement searched) {
        return tarkovscav$spacing(placement, level);
    }

    @Group(name = "tarkovscav$locateSpacing", min = 1, max = 1)
    @Redirect(method = {
            "getNearestGeneratedStructure(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;",
            "m_223188_(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;m_205003_()I"),
            require = 0, expect = 0, remap = false)
    private static int tarkovscav$spacingProduction(RandomSpreadStructurePlacement placement,
            Set<Holder<Structure>> structures, LevelReader level, StructureManager manager,
            int x, int z, int radius, boolean skipKnown, long seed, RandomSpreadStructurePlacement searched) {
        return tarkovscav$spacing(placement, level);
    }

    @Group(name = "tarkovscav$locateCandidate", min = 1, max = 1)
    @Redirect(method = {
            "getNearestGeneratedStructure(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;",
            "m_223188_(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;getPotentialStructureChunk(JII)Lnet/minecraft/world/level/ChunkPos;"),
            require = 0, expect = 0, remap = false)
    private static ChunkPos tarkovscav$candidateDevelopment(RandomSpreadStructurePlacement placement,
            long candidateSeed, int candidateX, int candidateZ, Set<Holder<Structure>> structures,
            LevelReader level, StructureManager manager, int x, int z, int radius, boolean skipKnown,
            long seed, RandomSpreadStructurePlacement searched) {
        return tarkovscav$candidate(placement, level, candidateSeed, candidateX, candidateZ);
    }

    @Group(name = "tarkovscav$locateCandidate", min = 1, max = 1)
    @Redirect(method = {
            "getNearestGeneratedStructure(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;",
            "m_223188_(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/StructureManager;IIIZJLnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)Lcom/mojang/datafixers/util/Pair;"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;m_227008_(JII)Lnet/minecraft/world/level/ChunkPos;"),
            require = 0, expect = 0, remap = false)
    private static ChunkPos tarkovscav$candidateProduction(RandomSpreadStructurePlacement placement,
            long candidateSeed, int candidateX, int candidateZ, Set<Holder<Structure>> structures,
            LevelReader level, StructureManager manager, int x, int z, int radius, boolean skipKnown,
            long seed, RandomSpreadStructurePlacement searched) {
        return tarkovscav$candidate(placement, level, candidateSeed, candidateX, candidateZ);
    }

    private static int tarkovscav$spacing(RandomSpreadStructurePlacement placement, LevelReader level) {
        return placement instanceof WastelandSpreadPlacement city && level instanceof ServerLevel server
                ? city.spacingFor(server) : placement.spacing();
    }

    private static ChunkPos tarkovscav$candidate(RandomSpreadStructurePlacement placement, LevelReader level,
                                                long seed, int x, int z) {
        return placement instanceof WastelandSpreadPlacement city && level instanceof ServerLevel server
                ? city.potentialChunkFor(server, seed, x, z) : placement.getPotentialStructureChunk(seed, x, z);
    }
}
