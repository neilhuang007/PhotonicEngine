package at.redi2go.photonics.core.rendering.world.bakery.impl;

import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasTexture;
import at.redi2go.photonics.core.rendering.world.bakery.texture.Rgba8Texture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBakeryTextureSamplingTest {
    @ParameterizedTest
    @CsvSource({"0,0", "16,0", "0,32", "48,48"})
    void bakingPreservesAlphaOnlyDetailAcrossAtlasPositionsAndUvRotations(int atlasX, int atlasY)
            throws InterruptedException {
        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xffee00ee);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int alpha = patternAlpha(x, y);
                pixels[(atlasY + y) * 64 + atlasX + x] = (alpha << 24) | 0x337766;
            }
        }
        AtlasTexture atlas = new AtlasTexture(new Rgba8Texture(64, 64, 0, pixels), null, null);

        for (int rotation = 0; rotation < 4; rotation++) {
            // Both offsets stay inside the same source texel. The second catches
            // atlas-dependent rounding that a texel-center-only test would miss.
            for (float subtexelShift : new float[]{0.0f, 0.2f}) {
                int[] baked = bakeFace(atlas, atlasX, atlasY, rotation, subtexelShift);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int u = x;
                        int v = y;
                        for (int turn = 0; turn < rotation; turn++) {
                            int previousU = u;
                            u = v;
                            v = 15 - previousU;
                        }
                        assertEquals((patternAlpha(u, v) << 24) | 0x667733, baked[y * 16 + x],
                                "atlas " + atlasX + "," + atlasY + ", rotation " + rotation +
                                        ", shift " + subtexelShift + ", voxel " + x + "," + y);
                    }
                }
            }
        }
    }

    private static int[] bakeFace(AtlasTexture atlas, int atlasX, int atlasY,
                                 int rotation, float subtexelShift) throws InterruptedException {
        // This public seam accepts an already downloaded atlas; no Minecraft
        // objects or downloader calls are needed to exercise actual voxelization.
        BlockBakeryImpl bakery = new BlockBakeryImpl(null);
        try (var mesh = bakery.new MeshResultImpl(new int[1024])) {
            mesh.useTexture(atlas).useBlockId(42).useLightTransmissive(true);
            float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (float[] corner : corners) {
                float u = corner[0];
                float v = corner[1];
                for (int turn = 0; turn < rotation; turn++) {
                    float previousU = u;
                    u = v;
                    v = 1.0f - previousU;
                }
                mesh.addVertex(corner[0], corner[1], 1.0f).setUv(
                        (atlasX + 16 * u + subtexelShift) / 64,
                        (atlasY + 16 * v + subtexelShift) / 64);
            }
            int[] baked = new int[16 * 16];
            mesh.bake((x, y, z, normal, data) -> {
                assertTrue(x >= 0 && x < 16 && y >= 0 && y < 16);
                assertEquals(15, z);
                assertEquals(42, data.blockId());
                assertTrue(data.isLightTransmissive());
                baked[y * 16 + x] = data.color();
            });
            return baked;
        }
    }

    private static int patternAlpha(int x, int y) {
        if (x == 0 || y == 0 || x == 15 || y == 15) return 170;
        if ((x >= 3 && x <= 5 && y == 8 - x) || (x == 11 && y == 12)) return 145;
        return 95;
    }
}
