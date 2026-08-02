package at.redi2go.photonics.common.iris.buffers;

import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingPointAllocatorTest {
    @Test
    void allocatesOnlyWithinTheDeviceLimitAndAvoidsOccupiedPoints() {
        int[] allocated = BindingPointAllocator.allocate(
                8,
                IntSet.of(0, 4, 7),
                3,
                "shader storage buffers"
        );

        assertArrayEquals(new int[]{6, 5, 3}, allocated);
    }

    @Test
    void allocatesNothingForAProgramWithoutActivePhotonicsBlocks() {
        int[] allocated = BindingPointAllocator.allocate(
                2,
                IntSet.of(0, 1),
                0,
                "shader storage buffers"
        );

        assertArrayEquals(new int[0], allocated);
    }

    @Test
    void failsOnlyWhenTheRequestedActiveBlocksExceedFreeCapacity() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> BindingPointAllocator.allocate(
                        4,
                        IntSet.of(0, 2),
                        3,
                        "shader storage buffers"
                )
        );

        assertTrue(error.getMessage().contains("requested 3"));
        assertTrue(error.getMessage().contains("2 free"));
        assertTrue(error.getMessage().contains("device limit 4"));
    }
}
