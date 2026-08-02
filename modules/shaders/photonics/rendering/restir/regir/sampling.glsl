#ifndef PH_REGIR_SAMPLING_INCLUDE
#define PH_REGIR_SAMPLING_INCLUDE

#include "/photonics/rendering/restir/regir/mapping.glsl"
#include "/photonics/rendering/restir/regir/random.glsl"
#include "/photonics/rendering/restir/power_ris/local_ris.glsl"

#define PH_REGIR_SELECTION_UNIFORM 0
#define PH_REGIR_SELECTION_CELL_RIS 1
#define PH_REGIR_SELECTION_POWER_RIS 2

struct ReGIRLightSelectionContext {
    int mode;
    uint offset;
    uint size;
};

ReGIRLightSelectionContext regir_initialize_light_selection_context(
    inout ReGIRRandomSamplerState coherent_random,
    vec3 surface_position
) {
#if PH_REGIR_MODE == PH_REGIR_DISABLED
    return ReGIRLightSelectionContext(PH_REGIR_SELECTION_UNIFORM, 0u, 0u);
#else
    vec3 cell_jitter = vec3(
        regir_next_random(coherent_random),
        regir_next_random(coherent_random),
        regir_next_random(coherent_random)
    ) - 0.5f;
    vec3 sampling_position = surface_position +
            cell_jitter * regir_get_jitter_scale(surface_position);
    int cell_index = regir_world_position_to_cell_index(sampling_position);

    if (cell_index >= 0) {
        return ReGIRLightSelectionContext(
            PH_REGIR_SELECTION_CELL_RIS,
            uint(cell_index) * ph_regir.commonParams.lightsPerCell +
                    ph_regir.commonParams.risBufferOffset,
            ph_regir.commonParams.lightsPerCell
        );
    }

#if PH_REGIR_LOCAL_LIGHT_SAMPLING_FALLBACK_MODE == PH_REGIR_PRESAMPLING_POWER_RIS
    PhRegirLocalRisTile tile = ph_regir_local_ris_select_tile(
        regir_next_random(coherent_random)
    );
    return ReGIRLightSelectionContext(
        PH_REGIR_SELECTION_POWER_RIS,
        tile.offset,
        tile.size
    );
#else
    return ReGIRLightSelectionContext(PH_REGIR_SELECTION_UNIFORM, 0u, 0u);
#endif
#endif
}

bool regir_sample_uniform_light(
    float random,
    out int light_index,
    out float inv_source_pdf
) {
    if (light_list_size <= 0) {
        light_index = -1;
        inv_source_pdf = 0.0f;
        return false;
    }

    light_index = min(
        int(floor(random * float(light_list_size))),
        light_list_size - 1
    );
    inv_source_pdf = float(light_list_size);
    return true;
}

bool regir_select_next_light(
    ReGIRLightSelectionContext context,
    float random,
    out int light_index,
    out float inv_source_pdf
) {
    if (context.mode == PH_REGIR_SELECTION_CELL_RIS) {
        uint sample_index = min(
            uint(floor(random * float(context.size))),
            context.size - 1u
        );
        uvec2 record = ph_regir_ris_records[context.offset + sample_index];
        light_index = int(record.x & 0x7fffffffu);
        inv_source_pdf = uintBitsToFloat(record.y);

        return light_index >= 0
                && light_index < light_list_size
                && inv_source_pdf > 0.0f
                && !isnan(inv_source_pdf)
                && !isinf(inv_source_pdf);
    }

    if (context.mode == PH_REGIR_SELECTION_POWER_RIS) {
        PhRegirLocalRisTile tile = PhRegirLocalRisTile(
            context.offset,
            context.size
        );
        if (ph_regir_local_ris_sample(
                tile,
                random,
                light_index,
                inv_source_pdf
        )) {
            return true;
        }
        return false;
    }

    return regir_sample_uniform_light(
        random,
        light_index,
        inv_source_pdf
    );
}

#endif
