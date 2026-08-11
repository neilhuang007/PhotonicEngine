#version 430

#define USE_FRAG_RT_POS
#define USE_FRAG_GEO_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#if defined PH_ENABLE_RESTIR_GI
#include "/photonics/rendering/restir/neighbor/reservoir.glsl"
#endif

#if defined PH_ENABLE_BLOCKLIGHT
#include "/photonics/rendering/restir/reservoir_splatting/spatial_reuse.glsl"

layout(location = DIRECT_RESERVOIR_0) out uvec2 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
#endif


#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

void main() {
    setup_frag_data(961);
    if (!frag_is_in_world) {
#if defined PH_ENABLE_BLOCKLIGHT
        DirectReservoir direct_result = direct_reservoir_empty();
        direct_reservoir_encode(
            direct_result,
            di_reservoir_0,
            di_reservoir_1
        );
        direct_reconnection_store_spatial(
            ph_splat_pixel_index(frag_tex_coord),
            direct_reconnection_empty()
        );
#endif
#if defined PH_ENABLE_RESTIR_GI
        IndirectReservoir indirect_result = indirect_reservoir_empty();
        indirect_reservoir_encode(
            indirect_result,
            gi_reservoir_0,
            gi_reservoir_1
        );
#endif
        return;
    }

#if defined PH_ENABLE_RESTIR_GI
    uvec4 samples;
    neighbor_load_samples(frag_tex_coord, samples);
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_result;
    DirectReconnection direct_reconnection;
    direct_splat_spatial_reuse(
        direct_result,
        direct_reconnection
    );
#endif


#if defined PH_ENABLE_RESTIR_GI
    float indirect_sample_weight = 0.0f;
    IndirectReservoir indirect_result = indirect_reservoir_empty();
    IndirectReservoir sample_indirect = indirect_reservoir_empty();
#endif

#if defined PH_ENABLE_RESTIR_GI
    for (int i = 0; i < PH_RESTIR_SPATIAL_REUSE_SAMPLES; i++) {
        if (samples[i] != 0) {
            ivec2 sample_texel = neighbor_next_sample(samples[i]);

            FragData sample_frag;
            frag_data_load(sample_frag, sample_texel);

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
        }
    }
#endif

#if defined PH_ENABLE_BLOCKLIGHT
    direct_reservoir_encode(
        direct_result,
        di_reservoir_0,
        di_reservoir_1
    );
    direct_reconnection_store_spatial(
        ph_splat_pixel_index(frag_tex_coord),
        direct_reconnection
    );
#endif

#if defined PH_ENABLE_RESTIR_GI
    indirect_reservoir_finalize_weight(indirect_result, indirect_sample_weight);
    indirect_reservoir_encode(indirect_result, gi_reservoir_0, gi_reservoir_1);
#endif
}
