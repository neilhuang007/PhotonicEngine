package at.redi2go.photonics.core.rendering.restir.splatting;

/**
 * Checked std430 layout for the single-splat destination binning buffers.
 *
 * <p>Each block is exposed to GLSL as one packed {@code uint[]} and uses the
 * word offsets below.</p>
 */
public record ReservoirSplattingBufferLayout(
        int width,
        int height,
        long pixelCount,
        long countersByteSize,
        long cellCountersWordOffset,
        long appendByteSize,
        long appendMetadataWordOffset,
        long appendSourceIdsWordOffset,
        long sortedByteSize,
        long cellOffsetsWordOffset,
        long sortedSourceIdsWordOffset,
        long reconnectionByteSize
) {
    public static final int WORD_BYTE_SIZE = Integer.BYTES;

    public static final int GLOBAL_COUNTER_COUNT = 2;
    public static final int DATA_COUNT_WORD_OFFSET = 0;
    public static final int PREFIX_SUM_WORD_OFFSET = 1;

    public static final int APPEND_METADATA_WORD_STRIDE = 2;
    public static final int APPEND_TARGET_CELL_WORD_LANE = 0;
    public static final int APPEND_LOCAL_CELL_INDEX_WORD_LANE = 1;
    public static final int SOURCE_ID_WORD_STRIDE = 1;
    public static final int RECONNECTION_WORD_STRIDE = 8;

    public static ReservoirSplattingBufferLayout plan(
            int width,
            int height,
            long maximumShaderStorageBlockByteSize
    ) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("reservoir splatting viewport dimensions must be positive");
        if (maximumShaderStorageBlockByteSize <= 0)
            throw new IllegalArgumentException("maximum shader storage block size must be positive");

        long pixelCount = Math.multiplyExact((long) width, height);
        if (pixelCount > 0xffff_ffffL)
            throw new IllegalArgumentException("reservoir splatting requires one uint-addressable record per pixel");

        long cellCountersWordOffset = GLOBAL_COUNTER_COUNT;
        long countersWordCount = Math.addExact(cellCountersWordOffset, pixelCount);
        long countersByteSize = wordsToBytes(countersWordCount);

        long appendMetadataWordOffset = 0;
        long appendSourceIdsWordOffset = Math.multiplyExact(
                pixelCount,
                APPEND_METADATA_WORD_STRIDE
        );
        long appendWordCount = Math.addExact(
                appendSourceIdsWordOffset,
                Math.multiplyExact(pixelCount, SOURCE_ID_WORD_STRIDE)
        );
        if (appendWordCount > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    "reservoir splatting packed blocks must remain uint-addressable"
            );
        }
        long appendByteSize = wordsToBytes(appendWordCount);

        long cellOffsetsWordOffset = 0;
        long sortedSourceIdsWordOffset = pixelCount;
        long sortedWordCount = Math.addExact(sortedSourceIdsWordOffset, pixelCount);
        long sortedByteSize = wordsToBytes(sortedWordCount);

        long reconnectionByteSize = wordsToBytes(Math.multiplyExact(
                pixelCount,
                RECONNECTION_WORD_STRIDE
        ));

        requireBlockFits("counter", countersByteSize, maximumShaderStorageBlockByteSize);
        requireBlockFits("append", appendByteSize, maximumShaderStorageBlockByteSize);
        requireBlockFits("sorted", sortedByteSize, maximumShaderStorageBlockByteSize);
        requireBlockFits("reconnection", reconnectionByteSize, maximumShaderStorageBlockByteSize);

        return new ReservoirSplattingBufferLayout(
                width,
                height,
                pixelCount,
                countersByteSize,
                cellCountersWordOffset,
                appendByteSize,
                appendMetadataWordOffset,
                appendSourceIdsWordOffset,
                sortedByteSize,
                cellOffsetsWordOffset,
                sortedSourceIdsWordOffset,
                reconnectionByteSize
        );
    }

    public boolean matchesViewport(int width, int height) {
        return this.width == width && this.height == height;
    }

    public long cellCountersByteOffset() {
        return wordsToBytes(cellCountersWordOffset);
    }

    public long appendMetadataByteOffset() {
        return wordsToBytes(appendMetadataWordOffset);
    }

    public long appendSourceIdsByteOffset() {
        return wordsToBytes(appendSourceIdsWordOffset);
    }

    public long cellOffsetsByteOffset() {
        return wordsToBytes(cellOffsetsWordOffset);
    }

    public long sortedSourceIdsByteOffset() {
        return wordsToBytes(sortedSourceIdsWordOffset);
    }

    private static long wordsToBytes(long wordCount) {
        return Math.multiplyExact(wordCount, WORD_BYTE_SIZE);
    }

    private static void requireBlockFits(String name, long byteSize, long maximumByteSize) {
        if (byteSize > maximumByteSize) {
            throw new IllegalArgumentException(
                    "reservoir splatting " + name + " block requires " + byteSize +
                            " bytes, exceeding the GPU shader storage block limit of " +
                            maximumByteSize + " bytes"
            );
        }
    }
}
