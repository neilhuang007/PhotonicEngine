package at.redi2go.photonics.core.rendering.world.bakery;

import at.redi2go.photonics.core.rendering.world.block.palette.TextureData;

public interface VoxelConsumer {
    void acceptVoxel(
            int x, int y, int z,
            short region,
            int normal,
            int tint,
            TextureData textureData
    );
}
