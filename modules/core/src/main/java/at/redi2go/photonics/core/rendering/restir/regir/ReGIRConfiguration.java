package at.redi2go.photonics.core.rendering.restir.regir;

import at.redi2go.photonics.core.iris.rendering.restir.ReGIRProperties;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRMode;
import at.redi2go.photonics.api.shaders.ReGIRLimits;

/**
 * Validated, immutable inputs used to construct the CPU and GPU ReGIR state.
 */
public record ReGIRConfiguration(
        ReGIRMode mode,
        ReGIRLocalLightPresamplingMode localLightPresamplingMode,
        ReGIRLocalLightFallbackMode localLightFallbackMode,
        int gridSizeX,
        int gridSizeY,
        int gridSizeZ,
        int onionDetailLayers,
        int onionCoverageLayers,
        int lightsPerCell,
        float cellSize,
        float samplingJitter,
        int buildSamples
) {
    public ReGIRConfiguration {
        if (mode == null || localLightPresamplingMode == null || localLightFallbackMode == null) {
            throw new IllegalArgumentException("ReGIR modes must be specified");
        }
        requireRange(
                "gridSizeX",
                gridSizeX,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MIN,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MAX
        );
        requireRange(
                "gridSizeY",
                gridSizeY,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MIN,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MAX
        );
        requireRange(
                "gridSizeZ",
                gridSizeZ,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MIN,
                ReGIRLimits.SUPPORTED_GRID_AXIS_MAX
        );
        requireRange("onionDetailLayers", onionDetailLayers, 0, ReGIRContext.MAX_ONION_LAYER_GROUPS);
        requireRange("onionCoverageLayers", onionCoverageLayers, 0, 64);
        requireRange(
                "lightsPerCell",
                lightsPerCell,
                ReGIRLimits.SUPPORTED_LIGHTS_PER_CELL_MIN,
                ReGIRLimits.SUPPORTED_LIGHTS_PER_CELL_MAX
        );
        requireRange("buildSamples", buildSamples, 0, 64);

        if (!Float.isFinite(cellSize) || cellSize <= 0.0f) {
            throw new IllegalArgumentException("cellSize must be finite and positive");
        }
        if (!Float.isFinite(samplingJitter) || samplingJitter < 0.0f) {
            throw new IllegalArgumentException("samplingJitter must be finite and non-negative");
        }
    }

    public static ReGIRConfiguration from(ReGIRProperties properties) {
        return new ReGIRConfiguration(
                properties.getReGIRMode(),
                properties.getReGIRLocalLightPresamplingMode(),
                properties.getReGIRLocalLightFallbackMode(),
                properties.getReGIRGridSizeX(),
                properties.getReGIRGridSizeY(),
                properties.getReGIRGridSizeZ(),
                properties.getReGIROnionDetailLayers(),
                properties.getReGIROnionCoverageLayers(),
                properties.getReGIRLightsPerCell(),
                properties.getReGIRCellSize(),
                properties.getReGIRSamplingJitter(),
                properties.getReGIRBuildSamples()
        );
    }

    public ReGIRConfiguration withLightsPerCell(int effectiveLightsPerCell) {
        if (effectiveLightsPerCell == lightsPerCell) {
            return this;
        }

        return new ReGIRConfiguration(
                mode,
                localLightPresamplingMode,
                localLightFallbackMode,
                gridSizeX,
                gridSizeY,
                gridSizeZ,
                onionDetailLayers,
                onionCoverageLayers,
                effectiveLightsPerCell,
                cellSize,
                samplingJitter,
                buildSamples
        );
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
        }
    }
}
