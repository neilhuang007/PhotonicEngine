#ifndef PH_REGIR_PARAMETERS_INCLUDE
#define PH_REGIR_PARAMETERS_INCLUDE

#define PH_REGIR_DISABLED 0
#define PH_REGIR_GRID 1
#define PH_REGIR_ONION 2

#define PH_REGIR_PRESAMPLING_UNIFORM 0
#define PH_REGIR_PRESAMPLING_POWER_RIS 1

#define PH_REGIR_MAX_ONION_LAYER_GROUPS 8
#define PH_REGIR_MAX_ONION_RINGS 52

struct ReGIROnionLayerGroup {
    float innerRadius;
    float outerRadius;
    float invLogLayerScale;
    int layerCount;

    float invEquatorialCellAngle;
    int cellsPerLayer;
    int ringOffset;
    int ringCount;

    float equatorialCellAngle;
    float layerScale;
    int layerCellOffset;
    int padding;
};

struct ReGIROnionRing {
    float cellAngle;
    float invCellAngle;
    int cellOffset;
    int cellCount;
};

struct ReGIRCommonParameters {
    uint localLightSamplingFallbackMode;
    float centerX;
    float centerY;
    float centerZ;

    uint risBufferOffset;
    uint lightsPerCell;
    float cellSize;
    float samplingJitter;

    uint localLightPresamplingMode;
    uint numRegirBuildSamples;
    uint padding1;
    uint padding2;
};

struct ReGIRGridParameters {
    uint cellsX;
    uint cellsY;
    uint cellsZ;
    uint padding;
};

struct ReGIROnionParameters {
    ReGIROnionLayerGroup layers[PH_REGIR_MAX_ONION_LAYER_GROUPS];
    ReGIROnionRing rings[PH_REGIR_MAX_ONION_RINGS];

    uint numLayerGroups;
    float cubicRootFactor;
    float linearFactor;
    float padding;
};

struct ReGIRParameters {
    ReGIRCommonParameters commonParams;
    ReGIRGridParameters gridParams;
    ReGIROnionParameters onionParams;
};

layout(std430) restrict readonly buffer ph_regir_parameters {
    ReGIRParameters ph_regir;
};

layout(std430) restrict buffer ph_regir_ris {
    uvec2 ph_regir_ris_records[];
};

#endif
