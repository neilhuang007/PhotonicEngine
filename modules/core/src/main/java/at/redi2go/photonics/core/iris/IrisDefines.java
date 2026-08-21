package at.redi2go.photonics.core.iris;

import at.redi2go.photonics.api.shaders.LightingMode;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.pipeline.DefineHolder;
import at.redi2go.photonics.core.rendering.lights.LocalLightCapacity;
import at.redi2go.photonics.core.rendering.restir.regir.ReGIRConfiguration;
import at.redi2go.photonics.core.rendering.restir.regir.ReGIRContext;

public class IrisDefines {
    public static void registerVersionDefines(DefineHolder defines) {
        defines.stringDefine("PHOTONICS", "");
        defines.stringDefine("PHOTONICS_VERSION", Photonics.getVersionString());
    }

    public static void registerDefines(DefineHolder defines, PhotonicsProperties phProperties) {
        long maximumShaderStorageBlockByteSize =
                IRenderSystem.getDevice().ph$getMaxShaderStorageBlockSize();
        LocalLightCapacity localLightCapacity = LocalLightCapacity.resolve(
                phProperties.getMaxLights(),
                maximumShaderStorageBlockByteSize
        );

        defines.floatDefine("PH_RENDER_SCALE", phProperties.getRenderScale());
        defines.intDefine(
                "PH_MAX_LIGHTS",
                localLightCapacity.effectiveMaxLights()
        );
        defines.intDefine("PH_MAX_GI_BOUNCES", phProperties.getMaxGiBounces());

        switch (phProperties.getAlphaMode()) {
            case BLOCK -> defines.stringDefine("PH_USE_TRANSPARENCY", "");
            case VOXEL -> {
                defines.stringDefine("PH_USE_TRANSPARENCY", "");
                defines.stringDefine("PH_FULL_TRANSPARENCY", "");
            }
        }

        if (phProperties.isBlockLightEnabled())
            defines.stringDefine("PH_ENABLE_BLOCKLIGHT", "");

        if (phProperties.isGiEnabled())
            defines.stringDefine("PH_ENABLE_GI", "");

        if (phProperties.isBlockLightGiEnabled())
            defines.stringDefine("PH_ENABLE_BLOCKLIGHT_GI", "");

        if (phProperties.isHandheldLightEnabled())
            defines.stringDefine("PH_ENABLE_HANDHELD_LIGHT", "");

        if (phProperties.isLightBinningEnabled() || phProperties.getLightingMode() == LightingMode.BASIC)
            defines.stringDefine("PH_ENABLE_LIGHT_BINNING", "");

        if (phProperties.useSeparateHandheldRays())
            defines.stringDefine("PH_SEPARATE_HANDHELD_RAYS", "");

        defines.enumDefine("PH_LIGHTING_MODE", phProperties.getLightingMode());

        defines.intDefine("PH_RESTIR_INITIAL_SAMPLES", phProperties.getRestirInitialSamples());
        defines.intDefine("PH_RESTIR_SPATIAL_REUSE_SAMPLES", phProperties.getRestirSpatialReuseSamples());
        defines.floatDefine("PH_RESTIR_SPATIAL_REUSE_RADIUS", phProperties.getRestirSpatialReuseRadius());
        defines.intDefine("PH_RESTIR_ACCUMULATION_FRAMES", phProperties.getRestirAccumulationFrames());
        int requestedDenoiserPasses = phProperties.getRestirDenoiserPasses();
        defines.intDefine(
                "PH_RESTIR_DENOISER_PASSES",
                requestedDenoiserPasses
        );

        if (phProperties.useRestirSoftShadows())
            defines.stringDefine("PH_RESTIR_SOFT_SHADOWS", "");

        if (phProperties.getLightingMode() == LightingMode.RESTIR && phProperties.useRestirCombinedGi())
            defines.stringDefine("PH_RESTIR_COMBINED_GI", "");

        ReGIRContext reGIRContext = new ReGIRContext(
                ReGIRConfiguration.from(phProperties),
                maximumShaderStorageBlockByteSize
        );
        ReGIRConfiguration reGIRConfiguration = reGIRContext.configuration();
        defines.enumDefine("PH_REGIR_MODE", reGIRConfiguration.mode());
        defines.enumDefine(
                "PH_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE",
                reGIRConfiguration.localLightPresamplingMode()
        );
        defines.enumDefine(
                "PH_REGIR_LOCAL_LIGHT_SAMPLING_FALLBACK_MODE",
                reGIRConfiguration.localLightFallbackMode()
        );
        defines.intDefine("PH_REGIR_GRID_SIZE_X", reGIRConfiguration.gridSizeX());
        defines.intDefine("PH_REGIR_GRID_SIZE_Y", reGIRConfiguration.gridSizeY());
        defines.intDefine("PH_REGIR_GRID_SIZE_Z", reGIRConfiguration.gridSizeZ());
        defines.intDefine("PH_REGIR_ONION_DETAIL_LAYERS", reGIRConfiguration.onionDetailLayers());
        defines.intDefine("PH_REGIR_ONION_COVERAGE_LAYERS", reGIRConfiguration.onionCoverageLayers());
        defines.intDefine("PH_REGIR_LIGHTS_PER_CELL", reGIRConfiguration.lightsPerCell());
        defines.floatDefine("PH_REGIR_CELL_SIZE", reGIRConfiguration.cellSize());
        defines.floatDefine("PH_REGIR_SAMPLING_JITTER", reGIRConfiguration.samplingJitter());
        defines.intDefine("PH_REGIR_BUILD_SAMPLES", reGIRConfiguration.buildSamples());
        defines.intDefine("PH_REGIR_LIGHT_SLOT_COUNT", reGIRContext.lightSlotCount());

        defines.intDefine("PH_MAX_SAMPLES",  phProperties.getMaxSamples());
    }
}
