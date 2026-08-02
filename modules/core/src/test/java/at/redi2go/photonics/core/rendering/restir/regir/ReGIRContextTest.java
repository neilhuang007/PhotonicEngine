package at.redi2go.photonics.core.rendering.restir.regir;

import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRLimits;
import at.redi2go.photonics.api.shaders.ReGIRMode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReGIRContextTest {
    @Test
    void constructsRtxdiDefaultOnionTablesAndSlotCount() {
        ReGIRContext context = new ReGIRContext(defaultConfiguration(ReGIRMode.ONION));

        assertEquals(5, context.onionLayerGroups().size());
        assertEquals(25, context.onionRings().size());
        assertEquals(2229, context.onionCellCount());
        assertEquals(1_141_248, context.lightSlotCount());
        assertEquals(9_129_984L, context.risBufferByteSize());
        assertFalse(context.wasLightsPerCellNormalized());
    }

    @Test
    void computesCheckedDefaultGridSlotCount() {
        ReGIRContext context = new ReGIRContext(defaultConfiguration(ReGIRMode.GRID));

        assertEquals(4096, context.gridCellCount());
        assertEquals(2_097_152, context.lightSlotCount());
    }

    @Test
    void serializesExactStd430ParameterLayout() {
        ReGIRContext context = new ReGIRContext(defaultConfiguration(ReGIRMode.ONION));
        ByteBuffer parameters = ByteBuffer
                .allocate(ReGIRContext.PARAMETER_BYTE_SIZE)
                .order(ByteOrder.nativeOrder());

        context.writeParameters(parameters);

        assertEquals(ReGIRContext.PARAMETER_BYTE_SIZE, parameters.position());
        assertEquals(1, parameters.getInt(0));
        assertEquals(512, parameters.getInt(20));
        assertEquals(0.5f, parameters.getFloat(24));
        assertEquals(2.0f, parameters.getFloat(28));
        assertEquals(1, parameters.getInt(32));
        assertEquals(8, parameters.getInt(36));
        assertEquals(5, parameters.getInt(1280));
    }

    @Test
    void normalizesSlotCountsThatWouldOverflowGpuIndexSpace() {
        ReGIRConfiguration oversized = new ReGIRConfiguration(
                ReGIRMode.GRID,
                ReGIRLocalLightPresamplingMode.UNIFORM,
                ReGIRLocalLightFallbackMode.UNIFORM,
                256,
                256,
                256,
                5,
                10,
                8192,
                1.0f,
                1.0f,
                8
        );

        ReGIRContext context = new ReGIRContext(oversized, Long.MAX_VALUE);

        assertEquals(127, context.configuration().lightsPerCell());
        assertEquals(2_130_706_432, context.lightSlotCount());
        assertEquals(17_045_651_456L, context.risBufferByteSize());
        assertTrue(context.wasLightsPerCellNormalized());
    }

    @Test
    void acceptsPortableUiMaximumAtExactSsboLimit() {
        int gridAxis = ReGIRLimits.PORTABLE_UI_GRID_AXIS_MAX;
        ReGIRConfiguration portableMaximum = gridConfiguration(
                gridAxis,
                gridAxis,
                gridAxis,
                ReGIRLimits.PORTABLE_UI_LIGHTS_PER_CELL_MAX
        );

        ReGIRContext context = new ReGIRContext(
                portableMaximum,
                ReGIRContext.PORTABLE_SHADER_STORAGE_BLOCK_BYTE_SIZE
        );

        assertEquals(16_777_216, context.lightSlotCount());
        assertEquals(134_217_728L, context.risBufferByteSize());
        assertEquals(512, context.configuration().lightsPerCell());
        assertFalse(context.wasLightsPerCellNormalized());
    }

    @Test
    void keepsPortableUiOnionMaximumWithinSsboLimit() {
        ReGIRConfiguration portableMaximum = new ReGIRConfiguration(
                ReGIRMode.ONION,
                ReGIRLocalLightPresamplingMode.POWER_RIS,
                ReGIRLocalLightFallbackMode.POWER_RIS,
                ReGIRLimits.PORTABLE_UI_GRID_AXIS_MAX,
                ReGIRLimits.PORTABLE_UI_GRID_AXIS_MAX,
                ReGIRLimits.PORTABLE_UI_GRID_AXIS_MAX,
                ReGIRContext.MAX_ONION_LAYER_GROUPS,
                64,
                ReGIRLimits.PORTABLE_UI_LIGHTS_PER_CELL_MAX,
                1.0f,
                1.0f,
                8
        );

        ReGIRContext context = new ReGIRContext(
                portableMaximum,
                ReGIRLimits.PORTABLE_SHADER_STORAGE_BLOCK_BYTES
        );

        assertEquals(27_405, context.onionCellCount());
        assertEquals(112_250_880L, context.risBufferByteSize());
        assertFalse(context.wasLightsPerCellNormalized());
    }

    @Test
    void normalizesLightsPerCellToRuntimeSsboLimitWithoutDisablingRegir() {
        ReGIRConfiguration requested = gridConfiguration(16, 16, 16, 512);
        long fourMebibytes = 4L * 1024L * 1024L;

        ReGIRContext context = new ReGIRContext(requested, fourMebibytes);

        assertEquals(ReGIRMode.GRID, context.configuration().mode());
        assertEquals(128, context.configuration().lightsPerCell());
        assertEquals(524_288, context.lightSlotCount());
        assertEquals(fourMebibytes, context.risBufferByteSize());
        assertTrue(context.wasLightsPerCellNormalized());
    }

    @Test
    void rejectsRuntimeLimitThatCannotStoreOneRecordPerActiveCell() {
        ReGIRConfiguration requested = gridConfiguration(256, 256, 256, 1);
        long tooSmallForOneRecordPerCell =
                (long) 256 * 256 * 256 * ReGIRContext.RIS_RECORD_BYTE_SIZE - 1L;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ReGIRContext(requested, tooSmallForOneRecordPerCell)
        );

        assertTrue(exception.getMessage().contains("shader storage block limit"));
    }

    @Test
    void keepsFallbackAndBuildPresamplingModesIndependent() {
        ReGIRConfiguration configuration = new ReGIRConfiguration(
                ReGIRMode.ONION,
                ReGIRLocalLightPresamplingMode.UNIFORM,
                ReGIRLocalLightFallbackMode.POWER_RIS,
                16,
                16,
                16,
                0,
                10,
                512,
                1.0f,
                1.0f,
                0
        );
        ReGIRContext context = new ReGIRContext(configuration);
        ByteBuffer parameters = ByteBuffer
                .allocate(ReGIRContext.PARAMETER_BYTE_SIZE)
                .order(ByteOrder.nativeOrder());

        context.writeParameters(parameters);

        assertEquals(1, context.onionLayerGroups().size());
        assertEquals(1, parameters.getInt(0));
        assertEquals(0, parameters.getInt(32));
        assertEquals(0, parameters.getInt(36));
    }

    private static ReGIRConfiguration defaultConfiguration(ReGIRMode mode) {
        return new ReGIRConfiguration(
                mode,
                ReGIRLocalLightPresamplingMode.POWER_RIS,
                ReGIRLocalLightFallbackMode.POWER_RIS,
                16,
                16,
                16,
                5,
                10,
                512,
                1.0f,
                1.0f,
                8
        );
    }

    private static ReGIRConfiguration gridConfiguration(
            int sizeX,
            int sizeY,
            int sizeZ,
            int lightsPerCell
    ) {
        return new ReGIRConfiguration(
                ReGIRMode.GRID,
                ReGIRLocalLightPresamplingMode.POWER_RIS,
                ReGIRLocalLightFallbackMode.POWER_RIS,
                sizeX,
                sizeY,
                sizeZ,
                5,
                10,
                lightsPerCell,
                1.0f,
                1.0f,
                8
        );
    }
}
