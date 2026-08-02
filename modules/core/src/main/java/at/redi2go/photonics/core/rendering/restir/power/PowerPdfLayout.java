package at.redi2go.photonics.core.rendering.restir.power;

/**
 * Packed SSBO representation of RTXDI's rectangular local-light PDF texture.
 *
 * <p>Each mip stores only its physical rectangle. Shader loads outside that
 * rectangle return zero, matching out-of-bounds texture loads in the RTXDI
 * implementation.</p>
 */
record PowerPdfLayout(
        int width,
        int height,
        int mipLevels,
        int texelCount,
        int byteSize
) {
    private static final int MAX_PDF_DIMENSION = 16 * 1024;

    static PowerPdfLayout forMaxLights(int maxLights) {
        if (maxLights <= 0) {
            throw new IllegalArgumentException("maxLights must be positive");
        }

        int width = nextPowerOfTwo(ceilSquareRoot(maxLights));
        int height = nextPowerOfTwo(ceilDiv(maxLights, width));
        if (width > MAX_PDF_DIMENSION || height > MAX_PDF_DIMENSION) {
            throw new IllegalArgumentException(
                    "Power PDF dimensions exceed the RTXDI 16k limit: " + width + "x" + height
            );
        }

        int mipLevels = Integer.numberOfTrailingZeros(Math.max(width, height)) + 1;
        if ((long) width * height < maxLights
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1
                || mipWidth(width, mipLevels - 1) != 1
                || mipHeight(height, mipLevels - 1) != 1) {
            throw new IllegalStateException("Invalid power PDF layout");
        }

        long texelCount = 0;
        for (int mipLevel = 0; mipLevel < mipLevels; mipLevel++) {
            texelCount = Math.addExact(
                    texelCount,
                    Math.multiplyExact((long) mipWidth(width, mipLevel), mipHeight(height, mipLevel))
            );
        }

        int checkedTexelCount = Math.toIntExact(texelCount);
        int byteSize = Math.toIntExact(Math.multiplyExact(texelCount, Float.BYTES));

        return new PowerPdfLayout(width, height, mipLevels, checkedTexelCount, byteSize);
    }

    int mipWidth(int mipLevel) {
        checkMipLevel(mipLevel);
        return mipWidth(width, mipLevel);
    }

    int mipHeight(int mipLevel) {
        checkMipLevel(mipLevel);
        return mipHeight(height, mipLevel);
    }

    int mipOffset(int mipLevel) {
        checkMipLevel(mipLevel);

        int offset = 0;
        for (int level = 0; level < mipLevel; level++) {
            offset = Math.addExact(offset, Math.multiplyExact(mipWidth(level), mipHeight(level)));
        }

        return offset;
    }

    private void checkMipLevel(int mipLevel) {
        if (mipLevel < 0 || mipLevel >= mipLevels) {
            throw new IndexOutOfBoundsException("Invalid power PDF mip level " + mipLevel);
        }
    }

    private static int mipWidth(int width, int mipLevel) {
        return Math.max(1, width >> mipLevel);
    }

    private static int mipHeight(int height, int mipLevel) {
        return Math.max(1, height >> mipLevel);
    }

    private static int ceilSquareRoot(int value) {
        int root = (int) Math.sqrt(value);
        return (long) root * root == value ? root : root + 1;
    }

    private static int ceilDiv(int dividend, int divisor) {
        return 1 + (dividend - 1) / divisor;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        if (value > (1 << 30)) {
            throw new IllegalArgumentException("Power PDF dimension is too large: " + value);
        }

        return Integer.highestOneBit(value - 1) << 1;
    }
}
