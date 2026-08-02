#version 430

//ph_required: uniform int frameCounter;

#include "/photonics/rendering/restir/regir/mapping.glsl"
#include "/photonics/rendering/restir/regir/random.glsl"
#include "/photonics/rendering/restir/regir/bridge.glsl"
#include "/photonics/rendering/restir/power_ris/local_ris.glsl"

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

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

    int selected_light = 0;
    float selected_target = 0.0f;
    float weight_sum = 0.0f;
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

        float target = candidate_valid
                ? regir_light_target_for_volume(
                    candidate_light,
                    cell_center,
                    cell_radius
                )
                : 0.0f;
        float ris_weight = target * inv_source_pdf * inv_sample_count;
        weight_sum += ris_weight;

        if (regir_next_random(random_sampler) * weight_sum < ris_weight) {
            selected_light = candidate_light;
            selected_target = target;
        }
    }

    float reservoir_weight = selected_target > 0.0f
            ? weight_sum / selected_target
            : 0.0f;
    if (!(reservoir_weight > 0.0f) ||
            isnan(reservoir_weight) ||
            isinf(reservoir_weight)) {
        ph_regir_ris_records[ris_buffer_pointer] = uvec2(0u);
        return;
    }

    ph_regir_ris_records[ris_buffer_pointer] = uvec2(
        uint(selected_light),
        floatBitsToUint(reservoir_weight)
    );
}
