#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/rendering/restir/regir/sampling.glsl"

layout(location = DIRECT_RESERVOIR_0) out vec3 di_reservoir_0;

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    DirectReservoir reservoir = direct_reservoir_empty();

    float sample_weight = 0.0f;

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
            if (!regir_select_next_light(
                    light_selection,
                    proposal_random,
                    light_index,
                    inv_source_pdf
            )) {
                direct_reservoir_update(
                    reservoir,
                    direct_sample_empty(),
                    0.0f,
                    1.0f,
                    regir_next_random(random_sampler)
                );
                continue;
            }

            DirectSample smple = DirectSample(light_index);
            float target = direct_sample_get_weight(
                    smple,
                    frag_rt_pos,
                    frag_geo_normal,
                    frag_tex_normal
            );
            float ris_weight = target * inv_source_pdf;

            if (direct_reservoir_update(
                    reservoir,
                    smple,
                    ris_weight,
                    1.0f,
                    regir_next_random(random_sampler)
            )) {
                sample_weight = target;
            }
        }
    }

    direct_reservoir_finalize_weight(reservoir, sample_weight);
    if (light_list_size > 0) {
        // RTXDI collapses the completed initial RIS stage to one effective
        // sample before temporal and spatial reservoir reuse.
        reservoir.total_samples = 1.0f;
    }
    direct_reservoir_encode(reservoir, di_reservoir_0);
}
