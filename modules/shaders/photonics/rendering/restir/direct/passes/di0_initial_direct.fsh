#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/regir/sampling.glsl"

layout(location = DIRECT_CANDIDATE_RESERVOIR) out uvec4 direct_candidate;

void regir_stream_local_light_sample(
    inout DirectReservoir reservoir,
    int light_index,
    vec2 uv,
    float inv_source_pdf,
    float random
) {
    if (light_index < 0 ||
            !(inv_source_pdf > 0.0f) ||
            isnan(inv_source_pdf) ||
            isinf(inv_source_pdf)) {
        direct_reservoir_stream_sample(
            reservoir,
            direct_sample_empty(),
            0.0f,
            0.0f,
            random
        );
        return;
    }

    DirectSample smple = DirectSample(light_index, uv);
    vec3 integrand = direct_sample_get_integrand(
        smple,
        frag_rt_pos,
        frag_geo_normal,
        frag_tex_normal,
        frag_is_light_transmissive
    );
    float target = direct_sample_weight(integrand);
    direct_reservoir_stream_sample(
        reservoir,
        smple,
        target,
        inv_source_pdf,
        random
    );
}

void regir_finalize_initial_reservoir(inout DirectReservoir reservoir) {
    direct_reservoir_finalize_initial_candidate(reservoir);
}

void main() {
    setup_frag_data(0);
    DirectReservoir reservoir = direct_reservoir_empty();
    if (!frag_is_in_world) {
        direct_candidate = direct_reservoir_encode_candidate(reservoir);
        return;
    }

    if (light_list_size > 0) {
        ReGIRRandomSamplerState random_sampler =
                regir_initialize_random_sampler(
                    uvec2(gl_FragCoord.xy),
                    uint(frameCounter),
                    1u
                );
        ReGIRRandomSamplerState coherent_random =
                regir_initialize_random_sampler(
                    uvec2(gl_FragCoord.xy) / 16u,
                    uint(frameCounter),
                    1u
                );
        ReGIRLightSelectionContext light_selection =
                regir_initialize_light_selection_context(
                    coherent_random,
                    frag_rt_pos
                );

        for (int i = 0; i < PH_RESTIR_INITIAL_SAMPLES; i++) {
            float proposal_random =
                    (regir_next_random(random_sampler) + float(i)) /
                    float(PH_RESTIR_INITIAL_SAMPLES);
            int light_index;
            float inv_source_pdf;
            bool proposal_valid = regir_select_next_light(
                    light_selection,
                    proposal_random,
                    light_index,
                    inv_source_pdf
            );
            vec2 sample_uv = vec2(
                regir_next_random(random_sampler),
                regir_next_random(random_sampler)
            );

            regir_stream_local_light_sample(
                    reservoir,
                    proposal_valid ? light_index : -1,
                    sample_uv,
                    proposal_valid ? inv_source_pdf : 0.0f,
                    regir_next_random(random_sampler)
            );
        }
    }

    regir_finalize_initial_reservoir(reservoir);
    direct_candidate = direct_reservoir_encode_candidate(reservoir);
}
