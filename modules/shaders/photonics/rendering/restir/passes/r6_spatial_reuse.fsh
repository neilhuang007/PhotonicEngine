#version 430

#define USE_FRAG_RT_POS
#define USE_FRAG_GEO_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#include "/photonics/rendering/restir/neighbor/reservoir.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
#define PH_DIRECT_RECONNECTION_FRAG_DATA
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"

layout(location = DIRECT_RESERVOIR_0) out uvec2 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
#endif


#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

void main() {
    setup_frag_data(961);
    if (!frag_is_in_world) discard;

    uvec4 samples;
    neighbor_load_samples(frag_tex_coord, samples);

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_result = direct_reservoir_empty();
    DirectReservoir temp_direct = direct_reservoir_empty();
    uint direct_pixel_index = uint(frag_tex_coord.y) *
            uint(PH_VIEW_SIZE.x) + uint(frag_tex_coord.x);
    DirectReconnection direct_reconnection =
            direct_reconnection_from_frag(_frag_data);

    direct_reservoir_load_previous(temp_direct, frag_tex_coord, false);
    DirectReconnection temporal_reconnection =
            direct_reconnection_load_current(direct_pixel_index);
    if (direct_reservoir_add_sample(
            direct_result,
            temp_direct,
            temp_direct.smple,
            temp_direct.target_pdf,
            1.0f,
            1.0f,
            ph_rand_next_float(frag_rnd_state)
    )) {
        direct_reconnection = temporal_reconnection;
    }
#endif


#if defined PH_ENABLE_RESTIR_GI
    float indirect_sample_weight = 0.0f;
    IndirectReservoir indirect_result = indirect_reservoir_empty();
    IndirectReservoir sample_indirect = indirect_reservoir_empty();
#endif

    for (int i = 0; i < PH_RESTIR_SPATIAL_REUSE_SAMPLES; i++) {
        if (samples[i] != 0) {
            ivec2 sample_texel = neighbor_next_sample(samples[i]);

#if defined PH_ENABLE_RESTIR_GI

            FragData sample_frag;
            frag_data_load(sample_frag, sample_texel);
#endif

#if defined PH_ENABLE_BLOCKLIGHT
            if (direct_reservoir_load_previous(temp_direct, sample_texel, false)) {
                if (direct_reservoir_merge(direct_result, temp_direct)) {
                    direct_reconnection = direct_reconnection_from_frag(
                        _frag_data
                    );
                }
            }
#endif

#if defined PH_ENABLE_RESTIR_GI
            if (indirect_reservoir_load_previous(sample_indirect, sample_texel, false)) {
                sample_indirect.total_samples = min(sample_indirect.total_samples, max_indirect_reservoir_samples);
                float shift = indirect_sample_compute_shift(sample_indirect.smple, frag_rt_pos, frag_data_rt_pos(sample_frag));

                indirect_reservoir_merge(
                        indirect_result,
                        sample_indirect,
                        shift,
                        indirect_sample_weight
                );
            }
#endif
        }
    }

#if defined PH_ENABLE_BLOCKLIGHT
    direct_reservoir_encode(
        direct_result,
        di_reservoir_0,
        di_reservoir_1
    );
    direct_reconnection_store_spatial(
        direct_pixel_index,
        direct_reconnection
    );
#endif

#if defined PH_ENABLE_RESTIR_GI
    indirect_reservoir_finalize_weight(indirect_result, indirect_sample_weight);
    indirect_reservoir_encode(indirect_result, gi_reservoir_0, gi_reservoir_1);
#endif
}
