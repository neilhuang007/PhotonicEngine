package at.redi2go.photonics.core.iris.rendering.restir;

import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.api.shaders.ReGIRMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.core.iris.properties.PhotonicsProperties;
import at.redi2go.photonics.core.iris.properties.PropertyDefines;
import at.redi2go.photonics.core.iris.properties.annotations.DefaultValue;
import at.redi2go.photonics.core.iris.properties.annotations.FloatRange;
import at.redi2go.photonics.core.iris.properties.annotations.IntRange;
import at.redi2go.photonics.core.iris.properties.annotations.Key;
import at.redi2go.photonics.core.rendering.restir.regir.ReGIRConfiguration;
import at.redi2go.photonics.core.rendering.restir.regir.ReGIRContext;

public interface ReGIRProperties extends PropertyDefines {
    @DefaultValue("ONION")
    @Key(value = "mode", legacy = "photonics.regir.mode")
    ReGIRMode getReGIRMode();

    @DefaultValue("POWER_RIS")
    @Key(value = "localLightPresamplingMode", legacy = "photonics.regir.localLightPresamplingMode")
    ReGIRLocalLightPresamplingMode getReGIRLocalLightPresamplingMode();

    @DefaultValue("POWER_RIS")
    @Key(value = "localLightSamplingFallbackMode", legacy = "photonics.regir.localLightSamplingFallbackMode")
    ReGIRLocalLightFallbackMode getReGIRLocalLightFallbackMode();

    @DefaultValue("16")
    @Key(value = "gridSizeX", legacy = "photonics.regir.gridSizeX")
    @IntRange(min = 1, max = 32)
    int getReGIRGridSizeX();

    @DefaultValue("16")
    @Key(value = "gridSizeY", legacy = "photonics.regir.gridSizeY")
    @IntRange(min = 1, max = 32)
    int getReGIRGridSizeY();

    @DefaultValue("16")
    @Key(value = "gridSizeZ", legacy = "photonics.regir.gridSizeZ")
    @IntRange(min = 1, max = 32)
    int getReGIRGridSizeZ();

    @DefaultValue("5")
    @Key(value = "onionDetailLayers", legacy = "photonics.regir.onionDetailLayers")
    @IntRange(min = 0, max = 8)
    int getReGIROnionDetailLayers();

    @DefaultValue("10")
    @Key(value = "onionCoverageLayers", legacy = "photonics.regir.onionCoverageLayers")
    @IntRange(min = 0, max = 64)
    int getReGIROnionCoverageLayers();

    @DefaultValue("512")
    @Key(value = "lightsPerCell", legacy = "photonics.regir.lightsPerCell")
    @IntRange(min = 1, max = 512)
    int getReGIRLightsPerCell();

    @DefaultValue("1.0")
    @Key(value = "cellSize", legacy = "photonics.regir.cellSize")
    @FloatRange(min = Float.MIN_NORMAL)
    float getReGIRCellSize();

    @DefaultValue("1.0")
    @Key(value = "samplingJitter", legacy = "photonics.regir.samplingJitter")
    @FloatRange(min = 0)
    float getReGIRSamplingJitter();

    @DefaultValue("8")
    @Key(value = "buildSamples", legacy = "photonics.regir.buildSamples")
    @IntRange(min = 0, max = 64)
    int getReGIRBuildSamples();

    @Override
    default void defineProperties(PhotonicsProperties properties) {
        ReGIRContext reGIRContext = new ReGIRContext(
                ReGIRConfiguration.from(this),
                IRenderSystem.getDevice().ph$getMaxShaderStorageBlockSize()
        );
        ReGIRConfiguration reGIRConfiguration = reGIRContext.configuration();
        enumDefine("PH_REGIR_MODE", reGIRConfiguration.mode());
        enumDefine(
                "PH_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE",
                reGIRConfiguration.localLightPresamplingMode()
        );
        enumDefine(
                "PH_REGIR_LOCAL_LIGHT_SAMPLING_FALLBACK_MODE",
                reGIRConfiguration.localLightFallbackMode()
        );
        intDefine("PH_REGIR_GRID_SIZE_X", reGIRConfiguration.gridSizeX());
        intDefine("PH_REGIR_GRID_SIZE_Y", reGIRConfiguration.gridSizeY());
        intDefine("PH_REGIR_GRID_SIZE_Z", reGIRConfiguration.gridSizeZ());
        intDefine("PH_REGIR_ONION_DETAIL_LAYERS", reGIRConfiguration.onionDetailLayers());
        intDefine("PH_REGIR_ONION_COVERAGE_LAYERS", reGIRConfiguration.onionCoverageLayers());
        intDefine("PH_REGIR_LIGHTS_PER_CELL", reGIRConfiguration.lightsPerCell());
        floatDefine("PH_REGIR_CELL_SIZE", reGIRConfiguration.cellSize());
        floatDefine("PH_REGIR_SAMPLING_JITTER", reGIRConfiguration.samplingJitter());
        intDefine("PH_REGIR_BUILD_SAMPLES", reGIRConfiguration.buildSamples());
        intDefine("PH_REGIR_LIGHT_SLOT_COUNT", reGIRContext.lightSlotCount());

    }
}
