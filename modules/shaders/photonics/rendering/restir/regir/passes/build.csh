#version 430

//ph_required: uniform int frameCounter;

#include "/photonics/rendering/restir/regir/mapping.glsl"
#include "/photonics/rendering/restir/regir/random.glsl"
#include "/photonics/rendering/restir/regir/bridge.glsl"
#include "/photonics/rendering/restir/power_ris/local_ris.glsl"

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

struct ReGIRBuildReservoir {
    int lightIndex;
    float selectedTarget;
    float weightSum;
};

ReGIRBuildReservoir regir_empty_build_reservoir() {
    return ReGIRBuildReservoir(-1, 0.0f, 0.0f);
}

void regir_stream_build_candidate(
    inout ReGIRBuildReservoir reservoir,
    int light_index,
    float target,
    float inv_source_pdf,
    float random
) {
    float ris_weight = target * inv_source_pdf;
    reservoir.weightSum += ris_weight;

    if (ris_weight > 0.0f &&
            random * reservoir.weightSum < ris_weight) {
        reservoir.lightIndex = light_index;
        reservoir.selectedTarget = target;
    }
}

void regir_finalize_reservoir(
    uint ris_buffer_pointer,
    ReGIRBuildReservoir reservoir
) {
    float inv_source_pdf = reservoir.selectedTarget > 0.0f
            ? reservoir.weightSum / reservoir.selectedTarget
            : 0.0f;
    if (reservoir.lightIndex < 0 ||
            !(inv_source_pdf > 0.0f) ||
            isnan(inv_source_pdf) ||
            isinf(inv_source_pdf)) {
        ph_regir_ris_records[ris_buffer_pointer] = uvec2(0u);
        return;
    }

    ph_regir_ris_records[ris_buffer_pointer] = uvec2(
        uint(reservoir.lightIndex),
        floatBitsToUint(inv_source_pdf)
    );
}

bool regir_build_uniform_candidate(
    inout ReGIRRandomSamplerState random_sampler,
    out int light_index,
    out float inv_source_pdf
) {
    if (light_list_size <= 0) {
        light_index = -1;
        inv_source_pdf = 0.0f;
        return false;
    }

    light_index = min(
        int(floor(regir_next_random(random_sampler) *
                float(light_list_size))),
        light_list_size - 1
    );
    inv_source_pdf = float(light_list_size);
    return true;
}

void main() {
    uint work_group_index = gl_WorkGroupID.x +
            gl_WorkGroupID.y * gl_NumWorkGroups.x;
    uint light_slot = work_group_index * gl_WorkGroupSize.x +
            gl_LocalInvocationID.x;
    if (light_slot >= uint(PH_REGIR_LIGHT_SLOT_COUNT)) {
        return;
    }

    uint ris_buffer_pointer =
            ph_regir.commonParams.risBufferOffset + light_slot;
    if (ph_regir.commonParams.numRegirBuildSamples == 0u ||
            light_list_size <= 0) {
        ph_regir_ris_records[ris_buffer_pointer] = uvec2(0u);
        return;
    }

    uint cell_index = light_slot / ph_regir.commonParams.lightsPerCell;
    vec3 cell_center;
    float cell_radius;
    if (!regir_cell_index_to_world_position(
            int(cell_index),
            cell_center,
            cell_radius
    )) {
        ph_regir_ris_records[ris_buffer_pointer] = uvec2(0u);
        return;
    }
    cell_radius *= ph_regir.commonParams.samplingJitter + 1.0f;

    ReGIRRandomSamplerState random_sampler = regir_initialize_random_sampler(
        uvec2(light_slot & 0xfffu, light_slot >> 12u),
        uint(frameCounter),
        1u
    );
    ReGIRRandomSamplerState coherent_random =
            regir_initialize_random_sampler(
                uvec2(light_slot >> 8u, 0u),
                uint(frameCounter),
                1u
            );

#if PH_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE == PH_REGIR_PRESAMPLING_POWER_RIS
    PhRegirLocalRisTile local_ris_tile = ph_regir_local_ris_select_tile(
        regir_next_random(coherent_random)
    );
#endif

    ReGIRBuildReservoir reservoir = regir_empty_build_reservoir();
    float inv_sample_count =
            1.0f / float(ph_regir.commonParams.numRegirBuildSamples);

    for (uint sample_index = 0u;
         sample_index < ph_regir.commonParams.numRegirBuildSamples;
         sample_index++) {
        int candidate_light;
        float inv_source_pdf;
        bool candidate_valid;

#if PH_REGIR_LOCAL_LIGHT_PRESAMPLING_MODE == PH_REGIR_PRESAMPLING_POWER_RIS
        candidate_valid = ph_regir_local_ris_sample(
            local_ris_tile,
            regir_next_random(random_sampler),
            candidate_light,
            inv_source_pdf
        );
#else
        candidate_valid = regir_build_uniform_candidate(
            random_sampler,
            candidate_light,
            inv_source_pdf
        );
#endif

        inv_source_pdf *= inv_sample_count;
        float target = candidate_valid
                ? regir_light_target_for_volume(
                    candidate_light,
                    cell_center,
                    cell_radius
                )
                : 0.0f;
        regir_stream_build_candidate(
            reservoir,
            candidate_light,
            target,
            inv_source_pdf,
            regir_next_random(random_sampler)
        );
    }

    regir_finalize_reservoir(ris_buffer_pointer, reservoir);
}
