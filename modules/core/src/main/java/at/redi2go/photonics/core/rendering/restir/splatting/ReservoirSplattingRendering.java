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
 * Owns the screen-sized buffers and compute stages used by the single-splat
 * temporal reuse mode.
 */
public final class ReservoirSplattingRendering implements RenderingComponent {
    public static final int PIXEL_LOCAL_SIZE_X = 16;
    public static final int PIXEL_LOCAL_SIZE_Y = 16;

    public static final float FULL_VIEW_WIDTH_SCALE = 1.0f / PIXEL_LOCAL_SIZE_X;
    public static final float FULL_VIEW_HEIGHT_SCALE = 1.0f / PIXEL_LOCAL_SIZE_Y;

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
    public static final String PREVIOUS_RECONNECTION_BUFFER_NAME =
            "ph_direct_previous_reconnections";
    public static final String CURRENT_RECONNECTION_BUFFER_NAME =
            "ph_direct_current_reconnections";
    public static final String SPATIAL_RECONNECTION_BUFFER_NAME =
            "ph_direct_spatial_reconnections";

    private final IGpuDevice device;
    private final IrisFramebuffer viewportSource;
    private final long maximumShaderStorageBlockByteSize;

    private ReservoirSplattingBufferLayout layout;
    private IGpuBuffer countersBuffer;
    private IGpuBuffer appendBuffer;
    private IGpuBuffer sortedBuffer;
    private IGpuBuffer previousReconnectionBuffer;
    private IGpuBuffer currentReconnectionBuffer;
    private IGpuBuffer spatialReconnectionBuffer;
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
     * view; shaders reject padded invocations before accessing the packed
     * buffers.
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

        IGpuBuffer previous = previousReconnectionBuffer;
        previousReconnectionBuffer = currentReconnectionBuffer;
        currentReconnectionBuffer = previous;
    }

    @Override
    public void registerBuffers(IBufferHolder buffers) {
        buffers.addDefaultBuffer(COUNTERS_BUFFER_NAME, () -> countersBuffer);
        buffers.addDefaultBuffer(APPEND_BUFFER_NAME, () -> appendBuffer);
        buffers.addDefaultBuffer(SORTED_BUFFER_NAME, () -> sortedBuffer);
        buffers.addDefaultBuffer(
                PREVIOUS_RECONNECTION_BUFFER_NAME,
                () -> previousReconnectionBuffer
        );
        buffers.addDefaultBuffer(
                CURRENT_RECONNECTION_BUFFER_NAME,
                () -> currentReconnectionBuffer
        );
        buffers.addDefaultBuffer(
                SPATIAL_RECONNECTION_BUFFER_NAME,
                () -> spatialReconnectionBuffer
        );
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        closeIfPresent(spatialReconnectionBuffer);
        closeIfPresent(currentReconnectionBuffer);
        closeIfPresent(previousReconnectionBuffer);
        closeIfPresent(sortedBuffer);
        closeIfPresent(appendBuffer);
        closeIfPresent(countersBuffer);
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
        IGpuBuffer newPreviousReconnectionBuffer = null;
        IGpuBuffer newCurrentReconnectionBuffer = null;
        IGpuBuffer newSpatialReconnectionBuffer = null;
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
            newPreviousReconnectionBuffer = device.ph$createBuffer(
                    () -> "Photonics Previous Direct Reconnection Data",
                    newLayout.reconnectionByteSize(),
                    0
            );
            newCurrentReconnectionBuffer = device.ph$createBuffer(
                    () -> "Photonics Current Direct Reconnection Data",
                    newLayout.reconnectionByteSize(),
                    0
            );
            newSpatialReconnectionBuffer = device.ph$createBuffer(
                    () -> "Photonics Spatial Direct Reconnection Data",
                    newLayout.reconnectionByteSize(),
                    0
            );
        } catch (RuntimeException | Error exception) {
            closeIfPresent(newSpatialReconnectionBuffer);
            closeIfPresent(newCurrentReconnectionBuffer);
            closeIfPresent(newPreviousReconnectionBuffer);
            closeIfPresent(newSortedBuffer);
            closeIfPresent(newAppendBuffer);
            closeIfPresent(newCountersBuffer);
            throw exception;
        }

        IGpuBuffer oldCountersBuffer = countersBuffer;
        IGpuBuffer oldAppendBuffer = appendBuffer;
        IGpuBuffer oldSortedBuffer = sortedBuffer;
        IGpuBuffer oldPreviousReconnectionBuffer = previousReconnectionBuffer;
        IGpuBuffer oldCurrentReconnectionBuffer = currentReconnectionBuffer;
        IGpuBuffer oldSpatialReconnectionBuffer = spatialReconnectionBuffer;

        countersBuffer = newCountersBuffer;
        appendBuffer = newAppendBuffer;
        sortedBuffer = newSortedBuffer;
        previousReconnectionBuffer = newPreviousReconnectionBuffer;
        currentReconnectionBuffer = newCurrentReconnectionBuffer;
        spatialReconnectionBuffer = newSpatialReconnectionBuffer;
        layout = newLayout;

        closeIfPresent(oldSpatialReconnectionBuffer);
        closeIfPresent(oldCurrentReconnectionBuffer);
        closeIfPresent(oldPreviousReconnectionBuffer);
        closeIfPresent(oldSortedBuffer);
        closeIfPresent(oldAppendBuffer);
        closeIfPresent(oldCountersBuffer);
    }

    private static void closeIfPresent(IGpuBuffer buffer) {
        if (buffer != null)
            buffer.close();
    }

    public void promoteSpatialOutput() {
        IGpuBuffer current = currentReconnectionBuffer;
        currentReconnectionBuffer = spatialReconnectionBuffer;
        spatialReconnectionBuffer = current;
    }
}
