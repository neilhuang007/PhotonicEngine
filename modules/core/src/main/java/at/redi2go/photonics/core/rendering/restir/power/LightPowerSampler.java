package at.redi2go.photonics.core.rendering.restir.power;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisPipeline;
import at.redi2go.photonics.core.rendering.RenderingComponent;

import java.util.function.BooleanSupplier;

/**
 * Owns the global power proposal used to build and fall back from ReGIR.
 *
 * <p>The implementation preserves RTXDI's two-stage proposal: a rectangular,
 * Morton-addressed PDF mip pyramid followed by coherently selected local RIS
 * tiles. Callers only need to register this rendering component and insert its
 * preparation passes before the ReGIR build pass.</p>
 */
public final class LightPowerSampler implements RenderingComponent {
    public static final int LOCAL_RIS_TILE_SIZE = 1024;
    public static final int LOCAL_RIS_TILE_COUNT = 128;

    private static final int PRESAMPLING_GROUP_SIZE = 256;
    private static final int RIS_ENTRY_BYTE_SIZE = 2 * Integer.BYTES;

    private static final String BUILD_POWER_PDF_SHADER =
            "/photonics/rendering/restir/power_ris/passes/p0_build_power_pdf.csh";
    private static final String PRESAMPLE_LOCAL_RIS_SHADER =
            "/photonics/rendering/restir/power_ris/passes/p1_presample_local_ris.csh";

    private final IGpuBuffer powerPdfBuffer;
    private final IGpuBuffer localRisBuffer;

    public LightPowerSampler(int maxLights) {
        PowerPdfLayout layout = PowerPdfLayout.forMaxLights(maxLights);
        int localRisByteSize = checkedLocalRisByteSize();
        var device = IRenderSystem.getDevice();

        powerPdfBuffer = device.ph$createBuffer(
                () -> "Photonics ReGIR Light Power PDF",
                layout.byteSize(),
                0
        );
        localRisBuffer = device.ph$createBuffer(
                () -> "Photonics ReGIR Local Light RIS",
                localRisByteSize,
                0
        );
    }

    /**
     * Inserts the two dependent compute passes. They deliberately remain
     * separate so Iris places an SSBO memory barrier between them.
     */
    public IrisPipeline.Builder addPreparationPasses(
            IrisPipeline.Builder builder,
            BooleanSupplier condition
    ) {
        return builder
                .computePass(
                        "build local light power PDF",
                        BUILD_POWER_PDF_SHADER,
                        1,
                        1,
                        1,
                        condition
                )
                .computePass(
                        "presample local light power RIS",
                        PRESAMPLE_LOCAL_RIS_SHADER,
                        LOCAL_RIS_TILE_SIZE / PRESAMPLING_GROUP_SIZE,
                        LOCAL_RIS_TILE_COUNT,
                        1,
                        condition
                );
    }

    @Override
    public void registerBuffers(IBufferHolder buffers) {
        buffers.addDefaultBuffer("ph_regir_power_pdf", () -> powerPdfBuffer);
        buffers.addDefaultBuffer("ph_regir_local_ris", () -> localRisBuffer);
    }

    @Override
    public void close() {
        localRisBuffer.close();
        powerPdfBuffer.close();
    }

    private static int checkedLocalRisByteSize() {
        if (Integer.bitCount(LOCAL_RIS_TILE_SIZE) != 1
                || Integer.bitCount(LOCAL_RIS_TILE_COUNT) != 1
                || LOCAL_RIS_TILE_SIZE % PRESAMPLING_GROUP_SIZE != 0) {
            throw new IllegalStateException("Local RIS dimensions must be dispatch-aligned powers of two");
        }

        long entries = Math.multiplyExact((long) LOCAL_RIS_TILE_SIZE, LOCAL_RIS_TILE_COUNT);
        return Math.toIntExact(Math.multiplyExact(entries, RIS_ENTRY_BYTE_SIZE));
    }
}
