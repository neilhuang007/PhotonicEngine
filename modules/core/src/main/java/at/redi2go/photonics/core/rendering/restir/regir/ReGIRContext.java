package at.redi2go.photonics.core.rendering.restir.regir;

import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRLimits;
import at.redi2go.photonics.api.shaders.ReGIRMode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CPU adaptation of RTXDI's ReGIR context construction.
 *
 * <p>All spatial values are in Photonics render-tree-local block coordinates.
 * The serialized center remains zero because the GLSL bridge supplies the
 * current {@code rt_camera_position} to both mappings every frame.</p>
 */
public final class ReGIRContext {
    public static final int MAX_ONION_LAYER_GROUPS = 8;
    public static final int MAX_ONION_RINGS = 52;
    public static final int PARAMETER_BYTE_SIZE = 1296;
    public static final int RIS_RECORD_BYTE_SIZE = ReGIRLimits.RIS_RECORD_BYTES;
    public static final long PORTABLE_SHADER_STORAGE_BLOCK_BYTE_SIZE =
            ReGIRLimits.PORTABLE_SHADER_STORAGE_BLOCK_BYTES;

    private static final float PI = 3.1415926535f;

    private final ReGIRConfiguration configuration;
    private final List<OnionLayerGroup> onionLayerGroups;
    private final List<OnionRing> onionRings;
    private final int gridCellCount;
    private final int onionCellCount;
    private final int lightSlotCount;
    private final int requestedLightsPerCell;
    private final long requestedRisBufferByteSize;
    private final long shaderStorageBlockByteLimit;
    private final float onionCubicRootFactor;
    private final float onionLinearFactor;

    public ReGIRContext(ReGIRConfiguration configuration) {
        this(configuration, PORTABLE_SHADER_STORAGE_BLOCK_BYTE_SIZE);
    }

    public ReGIRContext(
            ReGIRConfiguration requestedConfiguration,
            long shaderStorageBlockByteLimit
    ) {
        if (shaderStorageBlockByteLimit < RIS_RECORD_BYTE_SIZE) {
            throw new IllegalArgumentException(
                    "ReGIR shader storage block limit must be at least " +
                            RIS_RECORD_BYTE_SIZE + " bytes"
            );
        }

        this.shaderStorageBlockByteLimit = shaderStorageBlockByteLimit;
        this.gridCellCount = multiplyExact(
                multiplyExact(
                        requestedConfiguration.gridSizeX(),
                        requestedConfiguration.gridSizeY(),
                        "ReGIR grid cell count"
                ),
                requestedConfiguration.gridSizeZ(),
                "ReGIR grid cell count"
        );

        var onion = initializeOnion(requestedConfiguration);
        this.onionLayerGroups = List.copyOf(onion.layerGroups());
        this.onionRings = List.copyOf(onion.rings());
        this.onionCellCount = onion.cellCount();

        var jitterCurve = computeOnionJitterCurve(onionLayerGroups, onionRings);
        this.onionCubicRootFactor = jitterCurve.cubicRootFactor();
        this.onionLinearFactor = jitterCurve.linearFactor();

        int activeCellCount = switch (requestedConfiguration.mode()) {
            case DISABLED -> 0;
            case GRID -> gridCellCount;
            case ONION -> onionCellCount;
        };
        this.requestedLightsPerCell = requestedConfiguration.lightsPerCell();
        this.requestedRisBufferByteSize = Math.multiplyExact(
                Math.multiplyExact(
                        (long) activeCellCount,
                        requestedLightsPerCell
                ),
                RIS_RECORD_BYTE_SIZE
        );

        int effectiveLightsPerCell = resolveLightsPerCell(
                activeCellCount,
                requestedLightsPerCell,
                shaderStorageBlockByteLimit
        );
        this.configuration =
                requestedConfiguration.withLightsPerCell(effectiveLightsPerCell);
        this.lightSlotCount = multiplyExact(
                activeCellCount,
                effectiveLightsPerCell,
                "ReGIR light slot count"
        );
    }

    public ReGIRConfiguration configuration() {
        return configuration;
    }

    public int gridCellCount() {
        return gridCellCount;
    }

    public int onionCellCount() {
        return onionCellCount;
    }

    public int lightSlotCount() {
        return lightSlotCount;
    }

    public long risBufferByteSize() {
        return Math.multiplyExact((long) lightSlotCount, RIS_RECORD_BYTE_SIZE);
    }

    public int requestedLightsPerCell() {
        return requestedLightsPerCell;
    }

    public long requestedRisBufferByteSize() {
        return requestedRisBufferByteSize;
    }

    public long shaderStorageBlockByteLimit() {
        return shaderStorageBlockByteLimit;
    }

    public boolean wasLightsPerCellNormalized() {
        return requestedLightsPerCell != configuration.lightsPerCell();
    }

    public List<OnionLayerGroup> onionLayerGroups() {
        return onionLayerGroups;
    }

    public List<OnionRing> onionRings() {
        return onionRings;
    }

    public float onionCubicRootFactor() {
        return onionCubicRootFactor;
    }

    public float onionLinearFactor() {
        return onionLinearFactor;
    }

    /**
     * Writes the exact std430 ReGIR_Parameters layout used by RTXDI.
     */
    public void writeParameters(ByteBuffer target) {
        if (target.remaining() < PARAMETER_BYTE_SIZE) {
            throw new IllegalArgumentException("ReGIR parameter buffer is smaller than " + PARAMETER_BYTE_SIZE);
        }

        int start = target.position();
        int presamplingMode = configuration.localLightPresamplingMode() ==
                ReGIRLocalLightPresamplingMode.POWER_RIS ? 1 : 0;
        int fallbackMode = configuration.localLightFallbackMode() ==
                ReGIRLocalLightFallbackMode.POWER_RIS ? 1 : 0;

        // ReGIR_CommonParameters (48 bytes). The GLSL bridge replaces this
        // static zero center with rt_camera_position in all spatial mappings.
        target.putInt(fallbackMode);
        target.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        target.putInt(0);
        target.putInt(configuration.lightsPerCell());
        target.putFloat(configuration.mode() == ReGIRMode.ONION
                ? configuration.cellSize() * 0.5f
                : configuration.cellSize());
        target.putFloat(configuration.samplingJitter() * 2.0f);
        target.putInt(presamplingMode);
        target.putInt(configuration.buildSamples());
        target.putInt(0).putInt(0);

        // ReGIR_GridParameters (16 bytes).
        target.putInt(configuration.gridSizeX());
        target.putInt(configuration.gridSizeY());
        target.putInt(configuration.gridSizeZ());
        target.putInt(0);

        float onionCellSize = configuration.mode() == ReGIRMode.ONION
                ? configuration.cellSize() * 0.5f
                : configuration.cellSize();

        for (int index = 0; index < MAX_ONION_LAYER_GROUPS; index++) {
            if (index < onionLayerGroups.size()) {
                OnionLayerGroup group = onionLayerGroups.get(index);
                target.putFloat(group.innerRadius() * onionCellSize);
                target.putFloat(group.outerRadius() * onionCellSize);
                target.putFloat(group.invLogLayerScale());
                target.putInt(group.layerCount());
                target.putFloat(group.invEquatorialCellAngle());
                target.putInt(group.cellsPerLayer());
                target.putInt(group.ringOffset());
                target.putInt(group.ringCount());
                target.putFloat(group.equatorialCellAngle());
                target.putFloat(group.layerScale());
                target.putInt(group.layerCellOffset());
                target.putInt(0);
            } else {
                putZeroes(target, 48);
            }
        }

        for (int index = 0; index < MAX_ONION_RINGS; index++) {
            if (index < onionRings.size()) {
                OnionRing ring = onionRings.get(index);
                target.putFloat(ring.cellAngle());
                target.putFloat(ring.invCellAngle());
                target.putInt(ring.cellOffset());
                target.putInt(ring.cellCount());
            } else {
                putZeroes(target, 16);
            }
        }

        target.putInt(onionLayerGroups.size());
        target.putFloat(onionCubicRootFactor);
        target.putFloat(onionLinearFactor);
        target.putFloat(0.0f);

        if (target.position() - start != PARAMETER_BYTE_SIZE) {
            throw new IllegalStateException("ReGIR parameter layout size mismatch");
        }
    }

    private static OnionData initializeOnion(ReGIRConfiguration configuration) {
        int groupCount = Math.max(1, Math.min(MAX_ONION_LAYER_GROUPS, configuration.onionDetailLayers()));
        List<OnionLayerGroup> layerGroups = new ArrayList<>(groupCount);
        List<OnionRing> rings = new ArrayList<>(MAX_ONION_RINGS);

        float innerRadius = 1.0f;
        int totalCells = 1;

        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            int partitions = groupIndex * 4 + 8;
            int layerCount = groupIndex < groupCount - 1
                    ? 1
                    : configuration.onionCoverageLayers() + 1;
            float radiusRatio = (partitions + PI) / (partitions - PI);
            float outerRadius = innerRadius * (float) Math.pow(radiusRatio, layerCount);
            float equatorialAngle = 2.0f * PI / partitions;
            int ringOffset = rings.size();
            int ringCount = partitions / 4 + 1;

            int cellsPerLayer = partitions;
            rings.add(new OnionRing(
                    2.0f * PI / partitions,
                    partitions / (2.0f * PI),
                    0,
                    partitions
            ));

            for (int ringIndex = 1; ringIndex < ringCount; ringIndex++) {
                int cellCount = Math.max(
                        1,
                        (int) Math.floor(partitions * Math.cos(ringIndex * equatorialAngle))
                );
                rings.add(new OnionRing(
                        2.0f * PI / cellCount,
                        cellCount / (2.0f * PI),
                        cellsPerLayer,
                        cellCount
                ));
                cellsPerLayer = Math.addExact(cellsPerLayer, Math.multiplyExact(cellCount, 2));
            }

            if (rings.size() > MAX_ONION_RINGS) {
                throw new IllegalArgumentException("ReGIR onion configuration exceeds " + MAX_ONION_RINGS + " rings");
            }

            layerGroups.add(new OnionLayerGroup(
                    innerRadius,
                    outerRadius,
                    1.0f / (float) Math.log(radiusRatio),
                    layerCount,
                    1.0f / equatorialAngle,
                    cellsPerLayer,
                    ringOffset,
                    ringCount,
                    equatorialAngle,
                    radiusRatio,
                    totalCells
            ));

            totalCells = Math.addExact(
                    totalCells,
                    Math.multiplyExact(cellsPerLayer, layerCount)
            );
            innerRadius = outerRadius;
        }

        return new OnionData(layerGroups, rings, totalCells);
    }

    private static JitterCurve computeOnionJitterCurve(
            List<OnionLayerGroup> layerGroups,
            List<OnionRing> rings
    ) {
        List<Float> cubicRootFactors = new ArrayList<>();
        List<Float> linearFactors = new ArrayList<>();

        for (int groupIndex = 0; groupIndex < layerGroups.size(); groupIndex++) {
            OnionLayerGroup group = layerGroups.get(groupIndex);

            for (int layerIndex = 0; layerIndex < group.layerCount(); layerIndex++) {
                float innerRadius = group.innerRadius() *
                        (float) Math.pow(group.layerScale(), layerIndex);
                float outerRadius = innerRadius * group.layerScale();
                float middleRadius = (innerRadius + outerRadius) * 0.5f;
                float maxCellRadius = 0.0f;

                for (int ringIndex = 0; ringIndex < group.ringCount(); ringIndex++) {
                    OnionRing ring = rings.get(group.ringOffset() + ringIndex);
                    float middleElevation = group.equatorialCellAngle() * ringIndex;
                    float vertexElevation = ringIndex == 0
                            ? group.equatorialCellAngle() * 0.5f
                            : middleElevation - group.equatorialCellAngle() * 0.5f;

                    Vec3 middle = sphericalToCartesian(middleRadius, 0.0f, middleElevation);
                    Vec3 vertex = sphericalToCartesian(outerRadius, ring.cellAngle(), vertexElevation);
                    maxCellRadius = Math.max(maxCellRadius, distance(middle, vertex));
                }

                if (groupIndex < layerGroups.size() - 1) {
                    cubicRootFactors.add(maxCellRadius *
                            (float) Math.pow(middleRadius, -1.0f / 3.0f));
                } else {
                    linearFactors.add(maxCellRadius / middleRadius);
                }
            }
        }

        cubicRootFactors.sort(Comparator.naturalOrder());
        float cubicRootFactor = cubicRootFactors.isEmpty()
                ? 0.0f
                : cubicRootFactors.get(cubicRootFactors.size() / 2);

        float linearFactor = 0.0f;
        for (float factor : linearFactors) {
            linearFactor += factor;
        }
        linearFactor /= Math.max(linearFactors.size(), 1);

        return new JitterCurve(cubicRootFactor, linearFactor);
    }

    private static Vec3 sphericalToCartesian(float radius, float azimuth, float elevation) {
        float cosineElevation = (float) Math.cos(elevation);
        return new Vec3(
                radius * (float) Math.cos(azimuth) * cosineElevation,
                radius * (float) Math.sin(elevation),
                radius * (float) Math.sin(azimuth) * cosineElevation
        );
    }

    private static float distance(Vec3 first, Vec3 second) {
        float x = first.x() - second.x();
        float y = first.y() - second.y();
        float z = first.z() - second.z();
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static int multiplyExact(int first, int second, String description) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(description + " exceeds the supported 32-bit index space", exception);
        }
    }

    private static int resolveLightsPerCell(
            int activeCellCount,
            int requestedLightsPerCell,
            long shaderStorageBlockByteLimit
    ) {
        if (activeCellCount == 0) {
            return requestedLightsPerCell;
        }

        long maximumLightSlots = Math.min(
                Integer.MAX_VALUE,
                shaderStorageBlockByteLimit / RIS_RECORD_BYTE_SIZE
        );
        long maximumLightsPerCell = maximumLightSlots / activeCellCount;
        if (maximumLightsPerCell < 1L) {
            long minimumByteSize =
                    (long) activeCellCount * RIS_RECORD_BYTE_SIZE;
            throw new IllegalArgumentException(
                    "ReGIR requires at least " + minimumByteSize +
                            " bytes to store one light per active cell, but the " +
                            "shader storage block limit is " +
                            shaderStorageBlockByteLimit + " bytes"
            );
        }

        return (int) Math.min(requestedLightsPerCell, maximumLightsPerCell);
    }

    private static void putZeroes(ByteBuffer target, int byteCount) {
        for (int index = 0; index < byteCount; index += Integer.BYTES) {
            target.putInt(0);
        }
    }

    public record OnionLayerGroup(
            float innerRadius,
            float outerRadius,
            float invLogLayerScale,
            int layerCount,
            float invEquatorialCellAngle,
            int cellsPerLayer,
            int ringOffset,
            int ringCount,
            float equatorialCellAngle,
            float layerScale,
            int layerCellOffset
    ) {}

    public record OnionRing(
            float cellAngle,
            float invCellAngle,
            int cellOffset,
            int cellCount
    ) {}

    private record OnionData(
            List<OnionLayerGroup> layerGroups,
            List<OnionRing> rings,
            int cellCount
    ) {}

    private record JitterCurve(float cubicRootFactor, float linearFactor) {}

    private record Vec3(float x, float y, float z) {}
}
