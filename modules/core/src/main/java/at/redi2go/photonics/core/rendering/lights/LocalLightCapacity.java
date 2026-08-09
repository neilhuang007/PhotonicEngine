package at.redi2go.photonics.core.rendering.lights;

/**
 * Runtime-capacity plan for the local light buffers that are addressed with
 * the same light index in Java and GLSL.
 */
public record LocalLightCapacity(
        int requestedMaxLights,
        int effectiveMaxLights,
        long lightListByteSize,
        long mappingByteSize,
        long powerPdfByteSize,
        boolean fitsStorageBlockLimit
) {
    public static final int LIGHT_DATA_FLOAT_COUNT = 12;
    public static final int LIGHT_BYTE_SIZE =
            LIGHT_DATA_FLOAT_COUNT * Float.BYTES;
    public static final int MAPPING_BYTE_SIZE = Integer.BYTES;

    private static final int POWER_PDF_TEXEL_BYTE_SIZE = Float.BYTES;
    private static final int MAX_POWER_PDF_DIMENSION = 16 * 1024;

    public static LocalLightCapacity resolve(
            int requestedMaxLights,
            long maximumShaderStorageBlockByteSize
    ) {
        if (requestedMaxLights <= 0)
            throw new IllegalArgumentException("maxLights must be positive");
        if (maximumShaderStorageBlockByteSize <= 0)
            throw new IllegalArgumentException(
                    "maximum shader storage block size must be positive"
            );

        int effectiveMaxLights = Math.min(
                requestedMaxLights,
                highestFittingMaxLights(
                        requestedMaxLights,
                        maximumShaderStorageBlockByteSize
                )
        );
        if (effectiveMaxLights <= 0) {
            throw new IllegalArgumentException(
                    "shader storage block limit is too small for one local light"
            );
        }

        long lightListByteSize = lightListByteSize(effectiveMaxLights);
        long mappingByteSize = mappingByteSize(effectiveMaxLights);
        long powerPdfByteSize = powerPdfByteSize(effectiveMaxLights);
        boolean fitsStorageBlockLimit =
                lightListByteSize <= maximumShaderStorageBlockByteSize &&
                mappingByteSize <= maximumShaderStorageBlockByteSize &&
                powerPdfByteSize <= maximumShaderStorageBlockByteSize;

        return new LocalLightCapacity(
                requestedMaxLights,
                effectiveMaxLights,
                lightListByteSize,
                mappingByteSize,
                powerPdfByteSize,
                fitsStorageBlockLimit
        );
    }

    private static int highestFittingMaxLights(
            int requestedMaxLights,
            long maximumShaderStorageBlockByteSize
    ) {
        int upperBound = (int) Math.min(
                requestedMaxLights,
                maximumShaderStorageBlockByteSize / LIGHT_BYTE_SIZE
        );

        int low = 0;
        int high = upperBound;
        while (low < high) {
            int candidate = low + ((high - low + 1) >>> 1);
            if (fits(candidate, maximumShaderStorageBlockByteSize)) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }

        return low;
    }

    private static boolean fits(
            int maxLights,
            long maximumShaderStorageBlockByteSize
    ) {
        return lightListByteSize(maxLights) <= maximumShaderStorageBlockByteSize
                && mappingByteSize(maxLights) <= maximumShaderStorageBlockByteSize
                && powerPdfByteSize(maxLights) <= maximumShaderStorageBlockByteSize;
    }

    private static long lightListByteSize(int maxLights) {
        return Math.multiplyExact((long) maxLights, LIGHT_BYTE_SIZE);
    }

    private static long mappingByteSize(int maxLights) {
        return Math.multiplyExact((long) maxLights, MAPPING_BYTE_SIZE);
    }

    private static long powerPdfByteSize(int maxLights) {
        int width = nextPowerOfTwo(ceilSquareRoot(maxLights));
        int height = nextPowerOfTwo(ceilDiv(maxLights, width));
        if (width > MAX_POWER_PDF_DIMENSION ||
                height > MAX_POWER_PDF_DIMENSION) {
            return Long.MAX_VALUE;
        }

        int mipLevels =
                Integer.numberOfTrailingZeros(Math.max(width, height)) + 1;
        long texelCount = 0;
        for (int mipLevel = 0; mipLevel < mipLevels; mipLevel++) {
            texelCount = Math.addExact(
                    texelCount,
                    Math.multiplyExact(
                            (long) mipWidth(width, mipLevel),
                            mipHeight(height, mipLevel)
                    )
            );
        }

        return Math.multiplyExact(texelCount, POWER_PDF_TEXEL_BYTE_SIZE);
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
        if (value > (1 << 30))
            throw new IllegalArgumentException(
                    "Power PDF dimension is too large: " + value
            );

        return Integer.highestOneBit(value - 1) << 1;
    }
}
