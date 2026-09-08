package at.redi2go.photonics.core.rendering.world.block;

public record TextureData(
        int blockId,
        int color,
        int normal,
        int specular,
        boolean lightTransmissive
) {
    public static final int LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG = 1 << 31;
    public static final int BLOCK_ID_MASK = ~LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG;

    public static final int DEFAULT_NORMAL = 2139095039;
    public static final int DEFAULT_SPECULAR = 0;

    public TextureData(
            int blockId,
            int color,
            int normal,
            int specular
    ) {
        this(blockId, color, normal, specular, false);
    }

    public boolean gt(TextureData other) {
        return VoxelColor.gt(color, other.color());
    }

    public boolean isLightTransmissive() {
        return lightTransmissive;
    }

    public int packedBlockId() {
        return (blockId & BLOCK_ID_MASK) | (lightTransmissive
                ? LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG
                : 0);
    }

    public TextureData withLightTransmissive(boolean lightTransmissive) {
        if (this.lightTransmissive == lightTransmissive) return this;

        return new TextureData(
                blockId,
                color,
                normal,
                specular,
                lightTransmissive
        );
    }

    public TextureData withTint(int tint) {
        return new TextureData(
                blockId,
                VoxelColor.applyTint(color, tint),
                normal,
                specular,
                lightTransmissive
        );
    }

    public static int fastEquals(
            TextureData p1,
            TextureData p2
    ) {
        return (p1.blockId ^ p2.blockId) |
                (p1.color ^ p2.color) |
                (p1.normal ^ p2.normal) |
                (p1.specular ^ p2.specular) |
                ((p1.lightTransmissive ^ p2.lightTransmissive) ? 1 : 0);
    }
}
