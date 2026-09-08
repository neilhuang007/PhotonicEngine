#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/frag/frame_jitter.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"

#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform int ph_reservoir_splatting_history_valid;
#endif

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;

void main() {
    setup_frag_data(1);
    IndirectReservoir result = indirect_reservoir_empty();
    indirect_reservoir_encode(result, gi_reservoir_0, gi_reservoir_1);
    if (!frag_is_in_world) return;

    float selected_weight = 0.0f;
    IndirectReservoir canonical;
    indirect_reservoir_load_candidate(canonical, frag_tex_coord);
    indirect_reservoir_merge(result, canonical, 1.0f, selected_weight);

    vec3 previous = ph_reproject_player_pos(frag_player_pos, frag_is_hand, ph_previous_frame_jitter());
    bool valid_history = frameCounter > 0 && !any(isnan(previous)) && !any(isinf(previous)) &&
            all(greaterThanEqual(previous, vec3(0.0f))) && all(lessThan(previous, vec3(1.0f)));
#if defined PH_ENABLE_BLOCKLIGHT
    valid_history = valid_history && ph_reservoir_splatting_history_valid != 0;
#endif
    if (valid_history) {
        ivec2 previous_texel = ivec2(floor(previous.xy * PH_VIEW_SIZE));
        FragData previous_frag;
        frag_data_load_previous(previous_frag, previous_texel);
        IndirectReservoir temporal = indirect_reservoir_empty();
        if (frag_data_is_in_world(previous_frag) &&
                frag_data_is_hand(previous_frag) == frag_is_hand &&
                dot(frag_data_geo_normal(previous_frag), frag_geo_normal) >= 0.99f &&
                indirect_reservoir_load_previous(temporal, previous_texel, true)) {
            indirect_reservoir_reuse(result, temporal, previous_frag,
                    max_indirect_temporal_samples, 150.0f, selected_weight);
        }
    }

    // Failed reprojection removes only the old proposal, never current coverage.
    indirect_reservoir_clamp_samples(result);
    indirect_reservoir_finalize_weight(result, selected_weight);
    indirect_reservoir_encode(result, gi_reservoir_0, gi_reservoir_1);
}
