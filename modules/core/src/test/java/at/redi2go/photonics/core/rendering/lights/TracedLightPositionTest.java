package at.redi2go.photonics.core.rendering.lights;

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TracedLightPositionTest {
    @Test
    void blockIdentityUsesContainingVoxelAcrossOriginAndSectionBoundaries() {
        for (int coordinate : new int[]{-39, -17, -16, -1, 0, 15, 16}) {
            var light = new TracedLightPosition(1,
                    new Vector3d(coordinate + 0.5, coordinate + 0.5, coordinate + 0.5), null, null);
            assertEquals(new Vector3i(coordinate), light.blockPos(),
                    "Light-list remapping must use the source voxel, not truncate its center");
        }
    }
}
