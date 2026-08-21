package at.redi2go.photonics.core.rendering;

import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionManagerTest {
    @Test
    void trackedVolumeAppliesRenderDistanceToEverySectionAxis() {
        Vector3i camera = new Vector3i(10, -3, 20);

        assertTrue(SectionManager.isInsideRenderDistance(
                new Vector3i(12, -5, 18),
                camera,
                2
        ));
        assertFalse(SectionManager.isInsideRenderDistance(
                new Vector3i(10, 0, 20),
                camera,
                2
        ));
        assertFalse(SectionManager.isInsideRenderDistance(
                new Vector3i(13, -3, 20),
                camera,
                2
        ));
        assertFalse(SectionManager.isInsideRenderDistance(
                new Vector3i(10, -3, 23),
                camera,
                2
        ));
    }
}
