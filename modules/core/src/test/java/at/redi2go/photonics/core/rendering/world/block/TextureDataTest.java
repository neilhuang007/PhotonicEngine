package at.redi2go.photonics.core.rendering.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureDataTest {
    @Test
    void packedBlockIdCarriesLightTransmissionWithoutChangingBlockId() {
        TextureData data = new TextureData(
                42,
                VoxelColor.WHITE,
                TextureData.DEFAULT_NORMAL,
                TextureData.DEFAULT_SPECULAR,
                true
        );

        assertEquals(42, data.blockId());
        assertTrue(data.isLightTransmissive());
        assertEquals(
                42,
                data.packedBlockId() & TextureData.BLOCK_ID_MASK
        );
        assertEquals(
                TextureData.LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG,
                data.packedBlockId() & TextureData.LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG
        );
    }

    @Test
    void tintPreservesMaterialLightTransmission() {
        TextureData data = new TextureData(
                7,
                VoxelColor.WHITE,
                TextureData.DEFAULT_NORMAL,
                TextureData.DEFAULT_SPECULAR,
                true
        );

        assertTrue(data.withTint(VoxelColor.from(128, 255, 255, 255))
                .isLightTransmissive());
    }

    @Test
    void fastEqualsIncludesMaterialLightTransmission() {
        TextureData opaque = new TextureData(
                7,
                VoxelColor.WHITE,
                TextureData.DEFAULT_NORMAL,
                TextureData.DEFAULT_SPECULAR,
                false
        );
        TextureData transmissive = new TextureData(
                7,
                VoxelColor.WHITE,
                TextureData.DEFAULT_NORMAL,
                TextureData.DEFAULT_SPECULAR,
                true
        );

        assertNotEquals(0, TextureData.fastEquals(opaque, transmissive));
    }
}
