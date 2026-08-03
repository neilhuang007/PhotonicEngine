#version 430

#define FRAG_USE_PLAYER_POS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#if defined PH_ENABLE_BLOCKLIGHT
#include "/photonics/rendering/restir/reservoir_splatting/temporal_reuse.glsl"
#endif

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out uvec2 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

#if defined PH_ENABLE_BLOCKLIGHT
    DirectReservoir direct_result;
    DirectReconnection selected_reconnection;
    direct_splat_temporal_reuse(
        direct_result,
        selected_reconnection
    );
    direct_reservoir_encode(
        direct_result,
        di_reservoir_0,
        di_reservoir_1
    );

#endif

#if defined PH_ENABLE_RESTIR_GI
    float indirect_sample_weight = 0.0f;
    IndirectReservoir indirect_result = indirect_reservoir_empty();
    IndirectReservoir temp_indirect = indirect_reservoir_empty();

    vec2 previous_uv = ph_reproject_player_pos(
        frag_player_pos,
        frag_is_hand,
        get_taa_jitter()
    ).xy;
    if (all(greaterThanEqual(previous_uv, vec2(0.0f))) &&
            all(lessThan(previous_uv, vec2(1.0f)))) {
        ivec2 previous_texel = ivec2(previous_uv * PH_VIEW_SIZE);
        FragData previous_frag;
        frag_data_load_previous(previous_frag, previous_texel);
        if (dot(
                frag_data_geo_normal(previous_frag),
                frag_geo_normal
        ) >= 0.99f && indirect_reservoir_load_previous(
                temp_indirect,
                previous_texel,
                true
        )) {
            temp_indirect.total_samples = min(
                temp_indirect.total_samples,
                max_indirect_temporal_samples
            );
            float shift = indirect_sample_compute_shift(
                temp_indirect.smple,
                frag_rt_pos,
                frag_data_rt_pos(previous_frag)
            );
            indirect_reservoir_merge(
                    indirect_result,
                    temp_indirect,
                    shift,
                    indirect_sample_weight
            );
        }
    }

    indirect_reservoir_load(temp_indirect, frag_tex_coord);
    indirect_reservoir_merge(indirect_result, temp_indirect, 1.0f, indirect_sample_weight);

    indirect_reservoir_finalize_weight(indirect_result, indirect_sample_weight);
    indirect_reservoir_encode(indirect_result, gi_reservoir_0, gi_reservoir_1);
#endif
}
