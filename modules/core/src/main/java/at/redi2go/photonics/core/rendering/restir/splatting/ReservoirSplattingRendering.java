package at.redi2go.photonics.core.rendering.restir.splatting;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisPipeline;
import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformHolder;
import at.redi2go.photonics.core.iris.pipeline.uniform.IUniformUpdateFrequency;
import at.redi2go.photonics.core.rendering.RenderingComponent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Owns the screen-sized buffers and compute stages used by the single-splat
 * temporal reuse mode.
 */
public final class ReservoirSplattingRendering implements RenderingComponent {
    public static final int PIXEL_LOCAL_SIZE_X = 16;
    public static final int PIXEL_LOCAL_SIZE_Y = 16;
    public static final int SPATIAL_NEIGHBOR_SAMPLE_COUNT = 8192;

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
    public static final String SPATIAL_NEIGHBOR_OFFSETS_BUFFER_NAME =
            "ph_direct_spatial_neighbor_offsets";
    public static final String HISTORY_VALID_UNIFORM_NAME =
            "ph_reservoir_splatting_history_valid";

    private final IGpuDevice device;
    private final IrisFramebuffer viewportSource;
    private final long maximumShaderStorageBlockByteSize;
    private final IGpuBuffer spatialNeighborOffsetsBuffer;
    private final LongSupplier lightContentGeneration;
    private final LongSupplier worldContentGeneration;

    private ReservoirSplattingBufferLayout layout;
    private IGpuBuffer countersBuffer;
    private IGpuBuffer appendBuffer;
    private IGpuBuffer sortedBuffer;
    private IGpuBuffer previousReconnectionBuffer;
    private IGpuBuffer currentReconnectionBuffer;
    private IGpuBuffer spatialReconnectionBuffer;
    private boolean hasCompletedFrame;
    private boolean historyValid;
    private long previousLightContentGeneration;
    private long previousWorldContentGeneration;
    private boolean closed;

    public ReservoirSplattingRendering(
            IrisFramebuffer viewportSource,
            LongSupplier lightContentGeneration,
            LongSupplier worldContentGeneration
    ) {
        this(
            IRenderSystem.getDevice(),
            viewportSource,
            lightContentGeneration,
            worldContentGeneration
        );
    }

    ReservoirSplattingRendering(
            IGpuDevice device,
            IrisFramebuffer viewportSource,
            LongSupplier lightContentGeneration,
            LongSupplier worldContentGeneration
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.viewportSource = Objects.requireNonNull(viewportSource, "viewportSource");
        this.lightContentGeneration = Objects.requireNonNull(
            lightContentGeneration,
            "lightContentGeneration"
        );
        this.worldContentGeneration = Objects.requireNonNull(
            worldContentGeneration,
            "worldContentGeneration"
        );
        this.maximumShaderStorageBlockByteSize = device.ph$getMaxShaderStorageBlockSize();
        this.spatialNeighborOffsetsBuffer = createSpatialNeighborOffsetsBuffer();
        try {
            resizeToViewport();
        } catch (RuntimeException | Error exception) {
            spatialNeighborOffsetsBuffer.close();
            throw exception;
        }
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
        boolean resized = resizeToViewport();
        long currentLightContentGeneration = lightContentGeneration.getAsLong();
        long currentWorldContentGeneration = worldContentGeneration.getAsLong();
        historyValid = hasCompletedFrame &&
                !resized &&
                currentLightContentGeneration == previousLightContentGeneration &&
                currentWorldContentGeneration == previousWorldContentGeneration;
        hasCompletedFrame = true;
        previousLightContentGeneration = currentLightContentGeneration;
        previousWorldContentGeneration = currentWorldContentGeneration;

        IGpuBuffer previous = previousReconnectionBuffer;
        previousReconnectionBuffer = currentReconnectionBuffer;
        currentReconnectionBuffer = previous;
    }

    @Override
    public void registerUniforms(IUniformHolder uniforms) {
        uniforms.uniform1i(
                IUniformUpdateFrequency.perFrame(),
                HISTORY_VALID_UNIFORM_NAME,
                () -> historyValid ? 1 : 0
        );
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
        buffers.addDefaultBuffer(
                SPATIAL_NEIGHBOR_OFFSETS_BUFFER_NAME,
                () -> spatialNeighborOffsetsBuffer
        );
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        spatialNeighborOffsetsBuffer.close();
        closeIfPresent(spatialReconnectionBuffer);
        closeIfPresent(currentReconnectionBuffer);
        closeIfPresent(previousReconnectionBuffer);
        closeIfPresent(sortedBuffer);
        closeIfPresent(appendBuffer);
        closeIfPresent(countersBuffer);
    }

    private boolean resizeToViewport() {
        if (closed)
            throw new IllegalStateException("reservoir splatting rendering is closed");

        var viewportSize = viewportSource.viewportSize();
        int width = Math.max(viewportSize.x(), 1);
        int height = Math.max(viewportSize.y(), 1);
        if (layout != null && layout.matchesViewport(width, height)) {
            return false;
        }

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
        return true;
    }

    private static void closeIfPresent(IGpuBuffer buffer) {
        if (buffer != null)
            buffer.close();
    }

    private IGpuBuffer createSpatialNeighborOffsetsBuffer() {
        int[] packedOffsets = createSpatialNeighborOffsets();
        ByteBuffer data = ByteBuffer.allocateDirect(
                packedOffsets.length * Integer.BYTES
        ).order(ByteOrder.nativeOrder());
        for (int packedOffset : packedOffsets) {
            data.putInt(packedOffset);
        }
        data.flip();

        IGpuBuffer buffer = device.ph$createBuffer(
                () -> "Photonics Direct Spatial Neighbor Offsets",
                data.remaining(),
                BufferUsage.COPY_DST
        );
        try {
            device.ph$createCommandEncoder().ph$writeToBuffer(buffer, data);
            return buffer;
        } catch (RuntimeException | Error exception) {
            buffer.close();
            throw exception;
        }
    }

    static int[] createSpatialNeighborOffsets() {
        int[] offsets = new int[SPATIAL_NEIGHBOR_SAMPLE_COUNT];
        final float quantizationScale = 254.0f;
        final float plasticReciprocal = 1.0f / 1.3247179572447f;
        float u = 0.5f;
        float v = 0.5f;

        int accepted = 0;
        while (accepted < offsets.length) {
            u += plasticReciprocal;
            v += plasticReciprocal * plasticReciprocal;
            if (u >= 1.0f) u -= 1.0f;
            if (v >= 1.0f) v -= 1.0f;

            float x = u - 0.5f;
            float y = v - 0.5f;
            if (x * x + y * y > 0.25f) continue;

            int quantizedX = (int) (x * quantizationScale);
            int quantizedY = (int) (y * quantizationScale);
            offsets[accepted++] = quantizedX & 0xff |
                    (quantizedY & 0xff) << 8;
        }
        return offsets;
    }

    public void promoteSpatialOutput() {
        IGpuBuffer current = currentReconnectionBuffer;
        currentReconnectionBuffer = spatialReconnectionBuffer;
        spatialReconnectionBuffer = current;
    }
}
