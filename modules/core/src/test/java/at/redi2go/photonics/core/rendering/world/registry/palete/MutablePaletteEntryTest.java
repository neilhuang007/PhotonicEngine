package at.redi2go.photonics.core.rendering.world.registry.palete;

import at.redi2go.photonics.core.rendering.world.block.TextureData;
import at.redi2go.photonics.core.rendering.world.block.VoxelColor;
import at.redi2go.photonics.core.rendering.world.registry.block.builder.VoxelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MutablePaletteEntryTest {
    @Test
    void lightTransmissiveOpaqueFaceMarksPaletteEntryTransparent() {
        VoxelData voxel = new VoxelData();
        voxel.normal = 0;
        voxel.textureData = new TextureData(
                42,
                VoxelColor.WHITE,
                TextureData.DEFAULT_NORMAL,
                TextureData.DEFAULT_SPECULAR,
                true
        );

        MutablePaletteEntry paletteEntry = new MutablePaletteEntry();
        paletteEntry.update(voxel);

        assertTrue(paletteEntry.hasTransparentFace());
    }
}
