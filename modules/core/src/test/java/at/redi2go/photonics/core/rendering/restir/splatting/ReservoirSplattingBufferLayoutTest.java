package at.redi2go.photonics.core.rendering.restir.splatting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservoirSplattingBufferLayoutTest {
    @Test
    void allocatesOneExactRecordAtMinimumViewportSize() {
        ReservoirSplattingBufferLayout layout =
                ReservoirSplattingBufferLayout.plan(1, 1, 64);

        assertEquals(1, layout.pixelCount());

        assertEquals(12, layout.countersByteSize());
        assertEquals(8, layout.cellCountersByteOffset());

        assertEquals(12, layout.appendByteSize());
        assertEquals(0, layout.appendMetadataByteOffset());
        assertEquals(8, layout.appendSourceIdsByteOffset());

        assertEquals(8, layout.sortedByteSize());
        assertEquals(0, layout.cellOffsetsByteOffset());
        assertEquals(4, layout.sortedSourceIdsByteOffset());
        assertEquals(64, layout.reconnectionByteSize());
    }

    @Test
    void plansPackedFullHdBlocksWithoutPadding() {
        ReservoirSplattingBufferLayout layout =
                ReservoirSplattingBufferLayout.plan(1920, 1080, 128L * 1024L * 1024L);

        assertEquals(2_073_600, layout.pixelCount());
        assertEquals(8_294_408, layout.countersByteSize());
        assertEquals(24_883_200, layout.appendByteSize());
        assertEquals(16_588_800, layout.sortedByteSize());
        assertEquals(132_710_400, layout.reconnectionByteSize());
        assertEquals(16_588_800, layout.appendSourceIdsByteOffset());
        assertEquals(8_294_400, layout.sortedSourceIdsByteOffset());
    }

    @Test
    void documentsTheExact128MiBReconnectionLimit() {
        long maximumBlockSize = 128L * 1024L * 1024L;
        ReservoirSplattingBufferLayout maximumLayout =
                ReservoirSplattingBufferLayout.plan(
                        2048,
                        1024,
                        maximumBlockSize
                );

        assertEquals(
                64,
                ReservoirSplattingBufferLayout.RECONNECTION_RECORD_BYTE_SIZE
        );
        assertEquals(maximumBlockSize, maximumLayout.reconnectionByteSize());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ReservoirSplattingBufferLayout.plan(
                        2048,
                        1025,
                        maximumBlockSize
                )
        );
        assertTrue(error.getMessage().contains("reconnection block"));
        assertTrue(error.getMessage().contains("134348800 bytes"));
        assertTrue(error.getMessage().contains("134217728 bytes"));
    }

    @Test
    void reportsWhetherAViewportResizeNeedsAReplacementPlan() {
        ReservoirSplattingBufferLayout layout =
                ReservoirSplattingBufferLayout.plan(1280, 720, Long.MAX_VALUE);

        assertTrue(layout.matchesViewport(1280, 720));
        assertFalse(layout.matchesViewport(720, 1280));
        assertFalse(layout.matchesViewport(1920, 1080));
    }

    @Test
    void validatesEveryPackedBlockAgainstTheDeviceLimit() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ReservoirSplattingBufferLayout.plan(2, 1, 23)
        );

        assertTrue(error.getMessage().contains("append block"));
        assertTrue(error.getMessage().contains("24 bytes"));
    }

    @Test
    void rejectsLayoutsWhosePackedWordIndicesWouldWrapInGlsl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ReservoirSplattingBufferLayout.plan(
                        1_431_655_766,
                        1,
                        Long.MAX_VALUE
                )
        );

        assertTrue(error.getMessage().contains("uint-addressable"));
    }

    @Test
    void usesFalcorCompatiblePixelAndLinearSortDispatches() {
        assertEquals(16, ReservoirSplattingRendering.PIXEL_LOCAL_SIZE_X);
        assertEquals(16, ReservoirSplattingRendering.PIXEL_LOCAL_SIZE_Y);
        assertEquals(1.0f / 16.0f, ReservoirSplattingRendering.FULL_VIEW_WIDTH_SCALE);
        assertEquals(1.0f / 16.0f, ReservoirSplattingRendering.FULL_VIEW_HEIGHT_SCALE);
    }
}
