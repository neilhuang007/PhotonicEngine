package at.redi2go.photonics.core.rendering.restir.splatting;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisPipeline;
import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import at.redi2go.photonics.core.rendering.RenderingComponent;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Owns the screen-sized buffers and compute stages used to bin exact
 * single-splat reservoir reprojections.
 *
 * <p>This component intentionally stops at the adaptation boundary. The pass
 * shaders will consume full path reservoirs and reconnection data once the
 * complete algorithm is installed; no legacy GI reservoir is adapted here.</p>
 */
public final class ReservoirSplattingRendering implements RenderingComponent {
    public static final int PIXEL_LOCAL_SIZE_X = 16;
    public static final int PIXEL_LOCAL_SIZE_Y = 16;
    public static final int SORT_LOCAL_SIZE_X = 256;

    public static final float FULL_VIEW_WIDTH_SCALE = 1.0f;
    public static final float FULL_VIEW_HEIGHT_SCALE = 1.0f;

    public static final String CLEAR_SHADER =
            "/photonics/rendering/restir/reservoir_splatting/passes/p0_clear_bins.csh";
    public static final String REPROJECT_SHADER =
            "/photonics/rendering/restir/reservoir_splatting/passes/p1_reproject.csh";
    public static final String COMPUTE_CELL_OFFSETS_SHADER =
            "/photonics/rendering/restir/reservoir_splatting/passes/p2_compute_cell_offsets.csh";
    public static final String SORT_SHADER =
            "/photonics/rendering/restir/reservoir_splatting/passes/p3_sort.csh";

    public static final String COUNTERS_BUFFER_NAME = "ph_reservoir_splatting_counters";
    public static final String APPEND_BUFFER_NAME = "ph_reservoir_splatting_append";
    public static final String SORTED_BUFFER_NAME = "ph_reservoir_splatting_sorted";

    private final IGpuDevice device;
    private final IrisFramebuffer viewportSource;
    private final long maximumShaderStorageBlockByteSize;

    private ReservoirSplattingBufferLayout layout;
    private IGpuBuffer countersBuffer;
    private IGpuBuffer appendBuffer;
    private IGpuBuffer sortedBuffer;
    private boolean closed;

    public ReservoirSplattingRendering(IrisFramebuffer viewportSource) {
        this(IRenderSystem.getDevice(), viewportSource);
    }

    ReservoirSplattingRendering(
            IGpuDevice device,
            IrisFramebuffer viewportSource
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.viewportSource = Objects.requireNonNull(viewportSource, "viewportSource");
        this.maximumShaderStorageBlockByteSize = device.ph$getMaxShaderStorageBlockSize();

        resizeToViewport();
    }

    public ReservoirSplattingBufferLayout layout() {
        return layout;
    }

    /**
     * Inserts the four ordered binning stages. Iris places an SSBO memory
     * barrier between each compute pass. All stages dispatch over the complete
     * view; shaders must reject padded invocations before accessing the packed
     * buffers. The 256x1 sort stage must flatten both dispatch axes.
     */
    public IrisPipeline.Builder addPasses(
            IrisPipeline.Builder builder,
            BooleanSupplier condition
    ) {
        return builder
                .relativeComputePass(
                        "clear reservoir splatting bins",
                        CLEAR_SHADER,
                        FULL_VIEW_WIDTH_SCALE,
                        FULL_VIEW_HEIGHT_SCALE,
                        condition
                )
                .relativeComputePass(
                        "reproject previous reservoirs",
                        REPROJECT_SHADER,
                        FULL_VIEW_WIDTH_SCALE,
                        FULL_VIEW_HEIGHT_SCALE,
                        condition
                )
                .relativeComputePass(
                        "compute reservoir splatting cell offsets",
                        COMPUTE_CELL_OFFSETS_SHADER,
                        FULL_VIEW_WIDTH_SCALE,
                        FULL_VIEW_HEIGHT_SCALE,
                        condition
                )
                .relativeComputePass(
                        "sort reprojected reservoirs",
                        SORT_SHADER,
                        FULL_VIEW_WIDTH_SCALE,
                        FULL_VIEW_HEIGHT_SCALE,
                        condition
                );
    }

    @Override
    public void onFrameBegin() {
        viewportSource.recalculateSizes();
        resizeToViewport();
    }

    @Override
    public void registerBuffers(IBufferHolder buffers) {
        buffers.addDefaultBuffer(COUNTERS_BUFFER_NAME, () -> countersBuffer);
        buffers.addDefaultBuffer(APPEND_BUFFER_NAME, () -> appendBuffer);
        buffers.addDefaultBuffer(SORTED_BUFFER_NAME, () -> sortedBuffer);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        sortedBuffer.close();
        appendBuffer.close();
        countersBuffer.close();
    }

    private void resizeToViewport() {
        if (closed)
            throw new IllegalStateException("reservoir splatting rendering is closed");

        var viewportSize = viewportSource.viewportSize();
        int width = Math.max(viewportSize.x(), 1);
        int height = Math.max(viewportSize.y(), 1);
        if (layout != null && layout.matchesViewport(width, height)) return;

        var newLayout = ReservoirSplattingBufferLayout.plan(
                width,
                height,
                maximumShaderStorageBlockByteSize
        );

        IGpuBuffer newCountersBuffer = null;
        IGpuBuffer newAppendBuffer = null;
        IGpuBuffer newSortedBuffer = null;
        try {
            newCountersBuffer = device.ph$createBuffer(
                    () -> "Photonics Reservoir Splatting Counters",
                    newLayout.countersByteSize(),
                    0
            );
            newAppendBuffer = device.ph$createBuffer(
                    () -> "Photonics Reservoir Splatting Append Data",
                    newLayout.appendByteSize(),
                    0
            );
            newSortedBuffer = device.ph$createBuffer(
                    () -> "Photonics Reservoir Splatting Sorted Data",
                    newLayout.sortedByteSize(),
                    0
            );
        } catch (RuntimeException | Error exception) {
            closeIfPresent(newSortedBuffer);
            closeIfPresent(newAppendBuffer);
            closeIfPresent(newCountersBuffer);
            throw exception;
        }

        IGpuBuffer oldCountersBuffer = countersBuffer;
        IGpuBuffer oldAppendBuffer = appendBuffer;
        IGpuBuffer oldSortedBuffer = sortedBuffer;

        countersBuffer = newCountersBuffer;
        appendBuffer = newAppendBuffer;
        sortedBuffer = newSortedBuffer;
        layout = newLayout;

        closeIfPresent(oldSortedBuffer);
        closeIfPresent(oldAppendBuffer);
        closeIfPresent(oldCountersBuffer);
    }

    private static void closeIfPresent(IGpuBuffer buffer) {
        if (buffer != null)
            buffer.close();
    }
}
