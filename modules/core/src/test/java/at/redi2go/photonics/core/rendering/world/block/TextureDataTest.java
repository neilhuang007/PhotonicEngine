package at.redi2go.photonics.core.rendering.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureDataTest {
    @Test
    void unmappedBlockIdCannotSetTheTransmissionMetadataBit() {
        TextureData opaque = new TextureData(-1, VoxelColor.WHITE, 0, 0, false);
        TextureData glass = opaque.withLightTransmissive(true);
        assertEquals(0, opaque.packedBlockId() & TextureData.LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG);
        assertEquals(TextureData.BLOCK_ID_MASK, opaque.packedBlockId());
        assertEquals(TextureData.LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG,
                glass.packedBlockId() & TextureData.LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG);
        assertEquals(TextureData.BLOCK_ID_MASK, glass.packedBlockId() & TextureData.BLOCK_ID_MASK);
    }

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
