package at.redi2go.photonics.core.rendering.restir.regir;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.api.gpu.buffers.heap.MemoryView;
import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.core.rendering.RenderingComponent;

/**
 * Owns the exact RTXDI-shaped ReGIR parameter and RIS storage buffers.
 */
public final class ReGIRRendering implements RenderingComponent {
    public static final int BUILD_LOCAL_SIZE = 256;
    private static final int MAX_PORTABLE_WORK_GROUP_COUNT = 65_535;
    public static final String BUILD_SHADER =
            "/photonics/rendering/restir/regir/passes/build.csh";

    private final ReGIRContext context;
    private final IGpuBufferHeap parameterHeap;
    private final IGpuBuffer risBuffer;
    private boolean parameterUploadPending = true;

    public ReGIRRendering(PhotonicsProperties properties) {
        IGpuDevice device = IRenderSystem.getDevice();
        this.context = new ReGIRContext(
                ReGIRConfiguration.from(properties),
                device.ph$getMaxShaderStorageBlockSize()
        );
        if (context.wasLightsPerCellNormalized()) {
            Photonics.LOGGER.warn(
                    "ReGIR requested {} lights per cell ({} byte RIS buffer), " +
                            "but this GPU supports a {} byte shader storage block; " +
                            "using {} lights per cell and keeping {} ReGIR enabled",
                    context.requestedLightsPerCell(),
                    context.requestedRisBufferByteSize(),
                    context.shaderStorageBlockByteLimit(),
                    context.configuration().lightsPerCell(),
                    context.configuration().mode()
            );
        }

        this.parameterHeap = device.ph$createBufferHeap(
                () -> "Photonics ReGIR Parameters",
                ReGIRContext.PARAMETER_BYTE_SIZE,
                0
        );
        MemoryView parameterView = parameterHeap.allocateOrThrow(ReGIRContext.PARAMETER_BYTE_SIZE);
        parameterView.buffer().clear();
        context.writeParameters(parameterView.buffer());
        parameterView.upload();

        this.risBuffer = device.ph$createBuffer(
                () -> "Photonics ReGIR RIS",
                Math.max(context.risBufferByteSize(), ReGIRContext.RIS_RECORD_BYTE_SIZE),
                0
        );
    }

    public ReGIRContext context() {
        return context;
    }

    public int buildWorkGroupsX() {
        return Math.min(buildWorkGroupCount(), MAX_PORTABLE_WORK_GROUP_COUNT);
    }

    public int buildWorkGroupsY() {
        int workGroupsX = buildWorkGroupsX();
        return Math.toIntExact(
                ((long) buildWorkGroupCount() + workGroupsX - 1L) /
                        workGroupsX
        );
    }

    @Override
    public void onFrameBegin() {
        if (parameterUploadPending) {
            parameterHeap.upload();
            parameterUploadPending = false;
        }
    }

    @Override
    public void registerBuffers(IBufferHolder buffers) {
        buffers.addDefaultBufferHeap("ph_regir_parameters", () -> parameterHeap);
        buffers.addDefaultBuffer("ph_regir_ris", () -> risBuffer);
    }

    @Override
    public void close() {
        risBuffer.close();
        parameterHeap.close();
    }

    private int buildWorkGroupCount() {
        return Math.max(
                1,
                Math.toIntExact(
                        ((long) context.lightSlotCount() + BUILD_LOCAL_SIZE - 1L) /
                                BUILD_LOCAL_SIZE
                )
        );
    }
}
