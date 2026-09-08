package at.redi2go.photonics.core.rendering.world.bakery.texture;

import java.util.Objects;

public class Rgba8Texture implements CpuTexture {
    private final int width, height;

    private final int defaultValue;
    private final int[] color;

    public Rgba8Texture(
            int width, int height,
            int defaultValue,
            int[] data
    ) {
        Objects.requireNonNull(data);

        this.width = width;
        this.height = height;

        this.defaultValue = defaultValue;
        this.color = data;
    }

    @Override
    public int sample(float u, float v) {
        // Normalized nearest sampling selects a texel bin, then clamps to the
        // image edge. Rounding instead shifts bins with their atlas position.
        int realU = Math.max(0, Math.min(width - 1, (int) Math.floor(u * width)));
        int realV = Math.max(0, Math.min(height - 1, (int) Math.floor(v * height)));

        int index = (width * realV) + realU;
        if (index >= color.length) return defaultValue;

        return fromABGR(color[index]);
    }

    private static int fromABGR(int value) {
        return Integer.reverseBytes(Integer.rotateLeft(value, 8));
    }
}
