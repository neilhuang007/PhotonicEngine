package at.redi2go.photonics.impl.mc.world.level;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Objects;

public class SingleBlockLevelReader implements LevelReader {
    private final BlockState blockState;

    public SingleBlockLevelReader(BlockState blockState) {
        this.blockState = Objects.requireNonNull(blockState);
    }

    @Override
    public boolean hasChunk(int i, int j) {
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        return blockState;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos blockPos) {
        return null;
    }

    @Override
    public RegistryAccess registryAccess() {
        return Objects.requireNonNull(Minecraft.getInstance().level).registryAccess();
    }

    @Override
    public ChunkAccess getChunk(int i, int j, ChunkStatus chunkStatus, boolean bl) {
        throw new UnsupportedOperationException("getChunk");
    }

    @Override
    public int getHeight(Heightmap.Types types, int i, int j) {
        throw new UnsupportedOperationException("getHeight");
    }

    @Override
    public int getSkyDarken() {
        throw new UnsupportedOperationException("getSkyDarken");
    }

    @Override
    public BiomeManager getBiomeManager() {
        throw new UnsupportedOperationException("getBiomeManager");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int i, int j, int k) {
        throw new UnsupportedOperationException("getUncachedNoiseBiome");
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public int getSeaLevel() {
        throw new UnsupportedOperationException("getSeaLevel");
    }

    @Override
    public DimensionType dimensionType() {
        throw new UnsupportedOperationException("dimensionType");
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        throw new UnsupportedOperationException("enabledFeatures");
    }

    @Override
    public float getShade(Direction direction, boolean bl) {
        throw new UnsupportedOperationException("getShade");
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("getLightEngine");
    }

    @Override
    public WorldBorder getWorldBorder() {
        throw new UnsupportedOperationException("getWorldBorder");
    }

    @Override
    public List<VoxelShape> getEntityCollisions(Entity entity, AABB aABB) {
        throw new UnsupportedOperationException("getEntityCollisions");
    }

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        throw new UnsupportedOperationException("getFluidState");
    }
}
