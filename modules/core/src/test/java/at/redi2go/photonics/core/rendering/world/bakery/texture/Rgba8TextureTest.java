package at.redi2go.photonics.core.rendering.world.bakery.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Rgba8TextureTest {
    // OpenGL 4.6 section 8.14.2 and table 8.20: NEAREST selects floor(u * size),
    // then CLAMP_TO_EDGE limits that integer to [0, size - 1].
    // https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf
    @Test
    void normalizedSubtexelPositionsStayInTheirTexelBins() {
        int width = 16;
        int height = 8;
        Rgba8Texture texture = indexedTexture(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (float fraction : new float[]{0.125f, 0.5f, 0.875f}) {
                    assertEquals(colorAt(x, y), texture.sample(
                            (x + fraction) / width, (y + fraction) / height),
                            "texel " + x + "," + y + " at subtexel fraction " + fraction);
                }
            }
        }
    }

    @Test
    void eachAxisSelectsTheNextTexelExactlyAtItsNormalizedBoundary() {
        Rgba8Texture texture = indexedTexture(16, 8);
        for (int x = 1; x < 16; x++) {
            float u = x / 16.0f;
            assertEquals(colorAt(x - 1, 0), texture.sample(Math.nextDown(u), 0.0625f));
            assertEquals(colorAt(x, 0), texture.sample(u, 0.0625f));
            assertEquals(colorAt(x, 0), texture.sample(Math.nextUp(u), 0.0625f));
        }
        for (int y = 1; y < 8; y++) {
            float v = y / 8.0f;
            assertEquals(colorAt(0, y - 1), texture.sample(0.03125f, Math.nextDown(v)));
            assertEquals(colorAt(0, y), texture.sample(0.03125f, v));
            assertEquals(colorAt(0, y), texture.sample(0.03125f, Math.nextUp(v)));
        }
    }

    @Test
    void normalizedEdgesAndOutsideCoordinatesClampToEdgeTexels() {
        Rgba8Texture texture = indexedTexture(16, 8);
        for (float low : new float[]{-100.0f, -0.01f, 0.0f}) {
            for (float high : new float[]{1.0f, 1.01f, 100.0f}) {
                assertEquals(colorAt(0, 0), texture.sample(low, low));
                assertEquals(colorAt(15, 0), texture.sample(high, low));
                assertEquals(colorAt(0, 7), texture.sample(low, high));
                assertEquals(colorAt(15, 7), texture.sample(high, high));
            }
        }
    }

    @Test
    void subtexelSelectionDoesNotDependOnWhereTheSpriteLivesInTheAtlas() {
        int[] pixels = new int[64];
        for (int start : new int[]{0, 16, 32, 48}) {
            for (int x = 0; x < 16; x++) pixels[start + x] = toAbgr(colorAt(x, 0));
        }
        Rgba8Texture texture = new Rgba8Texture(64, 1, 0, pixels);
        for (int start : new int[]{0, 16, 32, 48}) {
            for (int x = 0; x < 16; x++) {
                assertEquals(colorAt(x, 0), texture.sample((start + x + 0.75f) / 64, 0.5f),
                        "sprite offset " + start + ", local texel " + x);
            }
        }
    }

    @Test
    void convertsDownloadedAbgrToArgbWithoutChangingAlphaOrChannelValues() {
        Rgba8Texture texture = new Rgba8Texture(3, 1, 0,
                new int[]{0x00442211, 0x80442211, 0xff442211});
        assertEquals(0x00112244, texture.sample(0.5f / 3, 0.5f));
        assertEquals(0x80112244, texture.sample(1.5f / 3, 0.5f));
        assertEquals(0xff112244, texture.sample(2.5f / 3, 0.5f));
    }

    @Test
    void absentLastTexelReturnsDefaultAtTheExactBufferLengthBoundary() {
        // The existing fallback permits partially populated backing data.
        // The first absent texel is index == length, not only index > length.
        Rgba8Texture texture = new Rgba8Texture(2, 1, 0x7f123456, new int[]{0xff332211});
        assertEquals(0xff112233, texture.sample(0.25f, 0.5f));
        assertEquals(0x7f123456, texture.sample(0.75f, 0.5f));
    }

    private static Rgba8Texture indexedTexture(int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) pixels[y * width + x] = toAbgr(colorAt(x, y));
        }
        return new Rgba8Texture(width, height, 0, pixels);
    }

    private static int colorAt(int x, int y) {
        return 0xff000040 | (x << 16) | (y << 8);
    }

    private static int toAbgr(int argb) {
        return (argb & 0xff00ff00) | ((argb >>> 16) & 0xff) | ((argb & 0xff) << 16);
    }
}
