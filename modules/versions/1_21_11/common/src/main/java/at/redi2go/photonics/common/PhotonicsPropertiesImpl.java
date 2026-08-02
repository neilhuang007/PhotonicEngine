package at.redi2go.photonics.common;

import at.redi2go.photonics.api.shaders.AlphaMode;
import at.redi2go.photonics.api.shaders.LightingMode;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRMode;

public class PhotonicsPropertiesImpl implements PhotonicsProperties {
    public boolean enabled = PhotonicsProperties.DEFAULT_ENABLED;
    public float renderScale = PhotonicsProperties.DEFAULT_RENDER_SCALE;
    public int maxLights = PhotonicsProperties.DEFAULT_MAX_LIGHTS;
    public int maxGiBounces = PhotonicsProperties.DEFAULT_MAX_GI_BOUNCES;
    public AlphaMode alphaMode = PhotonicsProperties.DEFAULT_ALPHA_MODE;
    public float enchantmentGlintStrength = PhotonicsProperties.DEFAULT_ENCHANTMENT_GLINT_STRENGTH;
    public boolean useSeparateHandheldRays = PhotonicsProperties.DEFAULT_SEPARATE_HANDHELD_RAYS;
    public boolean blockLightEnabled = PhotonicsProperties.DEFAULT_IS_BLOCK_LIGHT_ENABLED;
    public boolean giEnabled = PhotonicsProperties.DEFAULT_IS_GI_ENABLED;
    public boolean blockLightGiEnabled = PhotonicsProperties.DEFAULT_IS_BLOCK_LIGHT_GI_ENABLED;
    public boolean handheldLightEnabled = PhotonicsProperties.DEFAULT_IS_HANDHELD_LIGHT_ENABLED;
    public boolean lightBinningEnabled = PhotonicsProperties.DEFAULT_IS_LIGHT_BINNING_ENABLED;
    public LightingMode lightingMode = PhotonicsProperties.DEFAULT_LIGHTING_MODE;
    public int restirInitialSamples = PhotonicsProperties.DEFAULT_RESTIR_INITIAL_SAMPLES;
    public int restirSpatialReuseSamples = PhotonicsProperties.DEFAULT_RESTIR_SPATIAL_REUSE_SAMPLES;
    public float restirRestirSpatialReuseRadius = PhotonicsProperties.DEFAULT_RESTIR_SPATIAL_REUSE_RADIUS;
    public int restirAccumulationFrames = PhotonicsProperties.DEFAULT_RESTIR_ACCUMULATION_FRAMES;
    public boolean restirSoftShadows = PhotonicsProperties.DEFAULT_USE_RESTIR_SOFT_SHADOWS;
    public boolean restirCombinedGi = PhotonicsProperties.DEFAULT_USE_RESTIR_COMBINED_GI;
    public int restirDenoiserPasses = PhotonicsProperties.DEFAULT_RESTIR_DENOISER_PASSES;
    public int maxSamples = PhotonicsProperties.DEFAULT_MAX_SAMPLES;
    public ReGIRMode reGIRMode = PhotonicsProperties.DEFAULT_REGIR_MODE;
    public ReGIRLocalLightPresamplingMode reGIRLocalLightPresamplingMode =
            PhotonicsProperties.DEFAULT_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE;
    public ReGIRLocalLightFallbackMode reGIRLocalLightFallbackMode =
            PhotonicsProperties.DEFAULT_REGIR_LOCAL_LIGHT_FALLBACK_MODE;
    public int reGIRGridSizeX = PhotonicsProperties.DEFAULT_REGIR_GRID_SIZE_X;
    public int reGIRGridSizeY = PhotonicsProperties.DEFAULT_REGIR_GRID_SIZE_Y;
    public int reGIRGridSizeZ = PhotonicsProperties.DEFAULT_REGIR_GRID_SIZE_Z;
    public int reGIROnionDetailLayers = PhotonicsProperties.DEFAULT_REGIR_ONION_DETAIL_LAYERS;
    public int reGIROnionCoverageLayers = PhotonicsProperties.DEFAULT_REGIR_ONION_COVERAGE_LAYERS;
    public int reGIRLightsPerCell = PhotonicsProperties.DEFAULT_REGIR_LIGHTS_PER_CELL;
    public float reGIRCellSize = PhotonicsProperties.DEFAULT_REGIR_CELL_SIZE;
    public float reGIRSamplingJitter = PhotonicsProperties.DEFAULT_REGIR_SAMPLING_JITTER;
    public int reGIRBuildSamples = PhotonicsProperties.DEFAULT_REGIR_BUILD_SAMPLES;

    @Override
    public boolean isPhotonicsEnabled() {
        return enabled;
    }

    @Override
    public float getRenderScale() {
        return renderScale;
    }

    @Override
    public int getMaxLights() {
        return maxLights;
    }

    @Override
    public int getMaxGiBounces() {
        return maxGiBounces;
    }

    @Override
    public AlphaMode getAlphaMode() {
        return alphaMode;
    }

    @Override
    public float getEnchantmentGlintStrength() {
        return enchantmentGlintStrength;
    }

    @Override
    public boolean useSeparateHandheldRays() {
        return useSeparateHandheldRays;
    }

    @Override
    public boolean isBlockLightEnabled() {
        return blockLightEnabled;
    }

    @Override
    public boolean isGiEnabled() {
        return giEnabled;
    }

    @Override
    public boolean isBlockLightGiEnabled() {
        return blockLightGiEnabled;
    }

    @Override
    public boolean isHandheldLightEnabled() {
        return handheldLightEnabled;
    }

    @Override
    public LightingMode getLightingMode() {
        return lightingMode;
    }

    @Override
    public boolean isLightBinningEnabled() {
        return lightBinningEnabled;
    }

    @Override
    public int getMaxSamples() {
        return maxSamples;
    }

    @Override
    public int getRestirInitialSamples() {
        return restirInitialSamples;
    }

    @Override
    public int getRestirSpatialReuseSamples() {
        return restirSpatialReuseSamples;
    }

    @Override
    public float getRestirSpatialReuseRadius() {
        return restirRestirSpatialReuseRadius;
    }

    @Override
    public int getRestirAccumulationFrames() {
        return restirAccumulationFrames;
    }

    @Override
    public boolean useRestirSoftShadows() {
        return restirSoftShadows;
    }

    @Override
    public boolean useRestirCombinedGi() {
        return restirCombinedGi;
    }

    @Override
    public int getRestirDenoiserPasses() {
        return restirDenoiserPasses;
    }

    @Override
    public ReGIRMode getReGIRMode() {
        return reGIRMode;
    }

    @Override
    public ReGIRLocalLightPresamplingMode getReGIRLocalLightPresamplingMode() {
        return reGIRLocalLightPresamplingMode;
    }

    @Override
    public ReGIRLocalLightFallbackMode getReGIRLocalLightFallbackMode() {
        return reGIRLocalLightFallbackMode;
    }

    @Override
    public int getReGIRGridSizeX() {
        return reGIRGridSizeX;
    }

    @Override
    public int getReGIRGridSizeY() {
        return reGIRGridSizeY;
    }

    @Override
    public int getReGIRGridSizeZ() {
        return reGIRGridSizeZ;
    }

    @Override
    public int getReGIROnionDetailLayers() {
        return reGIROnionDetailLayers;
    }

    @Override
    public int getReGIROnionCoverageLayers() {
        return reGIROnionCoverageLayers;
    }

    @Override
    public int getReGIRLightsPerCell() {
        return reGIRLightsPerCell;
    }

    @Override
    public float getReGIRCellSize() {
        return reGIRCellSize;
    }

    @Override
    public float getReGIRSamplingJitter() {
        return reGIRSamplingJitter;
    }

    @Override
    public int getReGIRBuildSamples() {
        return reGIRBuildSamples;
    }
}
