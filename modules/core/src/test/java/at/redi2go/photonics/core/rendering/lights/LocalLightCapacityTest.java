package at.redi2go.photonics.core.rendering.lights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLightCapacityTest {
    @Test
    void clampsEveryLocalLightSsboToTheRuntimeStorageBlockLimit() {
        long storageBlockLimit = 128L * 1024L * 1024L;

        LocalLightCapacity capacity = LocalLightCapacity.resolve(
                3_000_000,
                storageBlockLimit
        );

        assertEquals(3_000_000, capacity.requestedMaxLights());
        assertEquals(2_796_202, capacity.effectiveMaxLights());
        assertEquals(134_217_696L, capacity.lightListByteSize());
        assertEquals(11_184_808L, capacity.mappingByteSize());
        assertEquals(22_369_620L, capacity.powerPdfByteSize());
        assertTrue(capacity.fitsStorageBlockLimit());
    }
}
