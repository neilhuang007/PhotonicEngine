package at.redi2go.photonics.api.shaders;

public interface PhotonicsProperties {
    boolean isPhotonicsEnabled();
    boolean DEFAULT_ENABLED = false;
    String ENABLED_KEY = "photonics.enabled";
    String SUPPORTED_KEY = "photonics.supported";

    float getRenderScale();
    float DEFAULT_RENDER_SCALE = 1f;
    String RENDER_SCALE_KEY = "photonics.renderScale";

    int getMaxLights();
    int DEFAULT_MAX_LIGHTS = 1000;
    String MAX_LIGHTS_KEY = "photonics.maxLights";

    int getMaxGiBounces();
    int DEFAULT_MAX_GI_BOUNCES = 1;
    String MAX_GI_BOUNCES_KEY = "photonics.maxGiBounces";

    AlphaMode getAlphaMode();
    AlphaMode DEFAULT_ALPHA_MODE = AlphaMode.NONE;
    String ALPHA_MODE_KEY = "photonics.alphaMode";

    float getEnchantmentGlintStrength();
    float DEFAULT_ENCHANTMENT_GLINT_STRENGTH = 0.2f;
    String ENCHANTMENT_GLINT_STRENGTH_KEY = "photonics.enchantmentGlintStrength";

    boolean useSeparateHandheldRays();
    boolean DEFAULT_SEPARATE_HANDHELD_RAYS = false;
    String SEPARATE_HANDHELD_RAYS_KEY = "photonics.useSeparateHandheldRays";

    boolean isBlockLightEnabled();
    boolean DEFAULT_IS_BLOCK_LIGHT_ENABLED = true;
    String IS_BLOCK_LIGHT_ENABLED_KEY = "photonics.enableBlockLight";

    boolean isGiEnabled();
    boolean DEFAULT_IS_GI_ENABLED = true;
    String IS_GI_ENABLED_KEY = "photonics.enableGi";

    boolean isBlockLightGiEnabled();
    boolean DEFAULT_IS_BLOCK_LIGHT_GI_ENABLED = false;
    String IS_BLOCK_LIGHT_GI_ENABLED_KEY = "photonics.enableBlockLightGi";

    boolean isHandheldLightEnabled();
    boolean DEFAULT_IS_HANDHELD_LIGHT_ENABLED = true;
    String IS_HANDHELD_LIGHT_ENABLED_KEY = "photonics.enableHandheldLight";

    LightingMode getLightingMode();
    LightingMode DEFAULT_LIGHTING_MODE = LightingMode.BASIC;
    String LIGHTING_MODE_KEY = "photonics.lightingMode";

     // Forced on when getLightingMode() == LightingMode.BASIC
     boolean isLightBinningEnabled();
     boolean DEFAULT_IS_LIGHT_BINNING_ENABLED = false;
     String IS_LIGHT_BINNING_ENABLED_KEY = "photonics.enableLightBinning";

    int getMaxSamples();
    int DEFAULT_MAX_SAMPLES = 20;
    String MAX_SAMPLES_KEY = "photonics.maxSamples";

    int getRestirInitialSamples();
    int DEFAULT_RESTIR_INITIAL_SAMPLES = 32;
    String RESTIR_INITIAL_SAMPLES_KEY = "photonics.restirInitialSamples";

    int getRestirSpatialReuseSamples();
    int DEFAULT_RESTIR_SPATIAL_REUSE_SAMPLES = 4;
    String RESTIR_SPATIAL_REUSE_SAMPLES_KEY = "photonics.restirSpatialReuseSamples";

    float getRestirSpatialReuseRadius();
    float DEFAULT_RESTIR_SPATIAL_REUSE_RADIUS = 25;
    String RESTIR_SPATIAL_REUSE_RADIUS_KEY = "photonics.restirSpatialReuseRadius";

    int getRestirAccumulationFrames();
    int DEFAULT_RESTIR_ACCUMULATION_FRAMES = 32;
    String RESTIR_ACCUMULATION_FRAMES_KEY = "photonics.restirAccumulationFrames";

    boolean useRestirSoftShadows();
    boolean DEFAULT_USE_RESTIR_SOFT_SHADOWS = true;
    String RESTIR_SOFT_SHADOWS_KEY = "photonics.restirSoftShadows";

    boolean useRestirCombinedGi();
    boolean DEFAULT_USE_RESTIR_COMBINED_GI = false;
    String RESTIR_COMBINED_GI_KEY = "photonics.restirCombinedGi";

    int getRestirDenoiserPasses();
    int DEFAULT_RESTIR_DENOISER_PASSES = 7;
    String RESTIR_DENOISER_PASSES_KEY = "photonics.restirDenoiserPasses";

    ReGIRMode getReGIRMode();
    ReGIRMode DEFAULT_REGIR_MODE = ReGIRMode.ONION;
    String REGIR_MODE_KEY = "photonics.regir.mode";

    ReGIRLocalLightPresamplingMode getReGIRLocalLightPresamplingMode();
    ReGIRLocalLightPresamplingMode DEFAULT_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE =
            ReGIRLocalLightPresamplingMode.POWER_RIS;
    String REGIR_LOCAL_LIGHT_PRESAMPLING_MODE_KEY = "photonics.regir.localLightPresamplingMode";

    ReGIRLocalLightFallbackMode getReGIRLocalLightFallbackMode();
    ReGIRLocalLightFallbackMode DEFAULT_REGIR_LOCAL_LIGHT_FALLBACK_MODE =
            ReGIRLocalLightFallbackMode.POWER_RIS;
    String REGIR_LOCAL_LIGHT_FALLBACK_MODE_KEY = "photonics.regir.localLightSamplingFallbackMode";

    int getReGIRGridSizeX();
    int DEFAULT_REGIR_GRID_SIZE_X = 16;
    String REGIR_GRID_SIZE_X_KEY = "photonics.regir.gridSizeX";

    int getReGIRGridSizeY();
    int DEFAULT_REGIR_GRID_SIZE_Y = 16;
    String REGIR_GRID_SIZE_Y_KEY = "photonics.regir.gridSizeY";

    int getReGIRGridSizeZ();
    int DEFAULT_REGIR_GRID_SIZE_Z = 16;
    String REGIR_GRID_SIZE_Z_KEY = "photonics.regir.gridSizeZ";

    int getReGIROnionDetailLayers();
    int DEFAULT_REGIR_ONION_DETAIL_LAYERS = 5;
    String REGIR_ONION_DETAIL_LAYERS_KEY = "photonics.regir.onionDetailLayers";

    int getReGIROnionCoverageLayers();
    int DEFAULT_REGIR_ONION_COVERAGE_LAYERS = 10;
    String REGIR_ONION_COVERAGE_LAYERS_KEY = "photonics.regir.onionCoverageLayers";

    int getReGIRLightsPerCell();
    int DEFAULT_REGIR_LIGHTS_PER_CELL = 512;
    String REGIR_LIGHTS_PER_CELL_KEY = "photonics.regir.lightsPerCell";

    float getReGIRCellSize();
    float DEFAULT_REGIR_CELL_SIZE = 1.0f;
    String REGIR_CELL_SIZE_KEY = "photonics.regir.cellSize";

    float getReGIRSamplingJitter();
    float DEFAULT_REGIR_SAMPLING_JITTER = 1.0f;
    String REGIR_SAMPLING_JITTER_KEY = "photonics.regir.samplingJitter";

    int getReGIRBuildSamples();
    int DEFAULT_REGIR_BUILD_SAMPLES = 8;
    String REGIR_BUILD_SAMPLES_KEY = "photonics.regir.buildSamples";
}
