#version 430

uniform int atrous_iteration;

#include "/photonics/rendering/frag/world_interface.glsl"
#include "/photonics/rendering/frag/frag_data.glsl"

#include "/photonics/rendering/restir/common.glsl"
#include "/photonics/rendering/restir/svgf/common.glsl"

layout(location = SVGF_DENOISE_OUT) out uvec4 denoise_out;

uniform sampler2D visibility_history;

const float SVGF_ATROUS_PHI_PLANE = 0.025f;

bool svgf_atrous_same_surface_class(FragData center_frag, FragData sample_frag) {
    return frag_data_is_in_world(sample_frag) &&
            frag_data_is_hand(sample_frag) == frag_data_is_hand(center_frag) &&
            frag_data_is_light_transmissive(sample_frag) ==
                    frag_data_is_light_transmissive(center_frag);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    SvgfSample center_sample = svgf_sample_empty();
    svgf_sample_load(center_sample, texel);

    FragData center_frag;
    frag_data_load(center_frag, texel);
    if (!svgf_sample_is_finite(center_sample) || center_sample.depth >= 1.0f ||
            !frag_data_is_in_world(center_frag) ||
            center_sample.is_hand != frag_data_is_hand(center_frag)) {
        denoise_out = uvec4(0u);
        return;
    }

    vec3 center_color = center_sample.color;
    float center_variance = max(center_sample.variance, 0.0f);
    float center_luma = ph_luminance(center_color);
    uint center_shading_normal_packed = center_sample.packed_normal;
    vec3 center_shading_normal = svgf_sample_get_normal(center_sample);
    uint center_geo_normal_packed = center_frag.data1.y;
    vec3 center_geo_normal = frag_data_geo_normal(center_frag);
    vec3 center_pos = frag_data_player_pos(center_frag);
    float center_visibility = texelFetch(visibility_history, texel, 0).r;
    if (isnan(center_visibility) || isinf(center_visibility)) center_visibility = 1.0f;

    // Anchor the center explicitly. This preserves isolated valid features
    // and avoids another texture/guide fetch for the center tap.
    vec3 color_sum = center_color;
    float weight_sum = 1.0f;
    float variance_sum = center_variance;

    int step_width = 1 << atrous_iteration;
    float phi_luminance = 6.0f * sqrt(max(center_variance, 0.0000000001f));
    const float phi_shadow = 0.1f;
    float shadow_mix = min(
            (center_sample.age / PH_RESTIR_ACCUMULATION_FRAMES) * 3.0f,
            1.0f
    );

    ivec2 image_size = textureSize(prev_denoise_result, 0);
    for (int i = 0; i < 9; ++i) {
        if (i == SVGF_CENTER_INDEX) continue;

        ivec2 p = texel + step_width * offset[i];
        if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, image_size)))
            continue;

        SvgfSample sample_data = svgf_sample_empty();
        svgf_sample_load(sample_data, p);
        if (!svgf_sample_is_finite(sample_data) || sample_data.depth >= 1.0f ||
                sample_data.is_hand != center_sample.is_hand)
            continue;

        FragData sample_frag;
        frag_data_load(sample_frag, p);
        if (!svgf_atrous_same_surface_class(center_frag, sample_frag) ||
                sample_data.is_hand != frag_data_is_hand(sample_frag))
            continue;

        float luma_weight = center_sample.is_hand
                ? 1.0f
                : svgf_luma_edge_stopping_weight(
                        center_luma,
                        ph_luminance(sample_data.color),
                        phi_luminance
                );
        float detail_normal_weight = svgf_packed_normal_edge_stopping_weight(
                center_shading_normal,
                center_shading_normal_packed,
                sample_data.packed_normal
        );
        uint sample_geo_normal_packed = sample_frag.data1.y;
        vec3 sample_pos = frag_data_player_pos(sample_frag);
        float plane_weight;
        if (sample_geo_normal_packed == center_geo_normal_packed) {
            plane_weight = svgf_plane_edge_stopping_weight(
                    center_pos,
                    sample_pos,
                    center_geo_normal,
                    SVGF_ATROUS_PHI_PLANE
            );
        } else {
            plane_weight = svgf_plane_edge_stopping_weight(
                    center_pos,
                    sample_pos,
                    center_geo_normal,
                    frag_data_geo_normal(sample_frag),
                    SVGF_ATROUS_PHI_PLANE
            );
        }

        float sample_visibility = texelFetch(visibility_history, p, 0).r;
        if (isnan(sample_visibility) || isinf(sample_visibility)) continue;
        float shadow_weight = mix(
                1.0f,
                svgf_shadow_stopping_weight(
                        center_visibility,
                        sample_visibility,
                        phi_shadow
                ),
                shadow_mix
        );

        float weight = kernel[i] * luma_weight * detail_normal_weight *
                plane_weight * shadow_weight;
        if (weight <= 0.0f || isnan(weight) || isinf(weight)) continue;

        weight_sum += weight;
        color_sum += sample_data.color * weight;
        variance_sum += max(sample_data.variance, 0.0f) * weight * weight;
    }

    float filtered_variance = max(
            variance_sum / (weight_sum * weight_sum),
            0.0f
    );
    center_sample.color = clamp(
            color_sum / weight_sum,
            -65504.0f,
            65504.0f
    );
    center_sample.variance = clamp(
            filtered_variance,
            0.0f,
            65504.0f
    );

    svgf_sample_encode(center_sample, denoise_out);
}
