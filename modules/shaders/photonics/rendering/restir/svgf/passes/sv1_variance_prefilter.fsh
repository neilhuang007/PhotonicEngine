#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/common.glsl"
#include "/photonics/rendering/restir/svgf/common.glsl"
#include "/photonics/rendering/restir/svgf/history.glsl"

layout(location = SVGF_DENOISE_OUT) out uvec4 denoise_out;

const float SVGF_FRESH_HISTORY_MAX = 4.0f;
const float SVGF_SPATIAL_PHI_PLANE = 0.025f;

bool svgf_prefilter_same_surface_class(FragData center_frag, FragData sample_frag) {
    return frag_data_is_in_world(sample_frag) &&
            frag_data_is_hand(sample_frag) == frag_data_is_hand(center_frag) &&
            frag_data_is_light_transmissive(sample_frag) ==
                    frag_data_is_light_transmissive(center_frag);
}

void main() {
    setup_frag_data(0);

    SvgfSample smple = svgf_sample_empty();
    if (frag_is_in_world) {
        SampleHistory center;
        sample_history_load(center, frag_tex_coord);
        float depth = load_depth();
        if (!sample_history_is_valid(center) || isnan(depth) || isinf(depth)) {
            svgf_sample_encode(smple, denoise_out);
            return;
        }

        smple.depth = clamp(depth, 0.0f, 1.0f);
        smple.packed_normal = frag_is_hand ? _frag_data.data1.y : _frag_data.data1.z;
        smple.is_hand = frag_is_hand;
        smple.age = clamp(center.lighting.a, 0.0f, PH_RESTIR_ACCUMULATION_FRAMES);

        vec3 center_pos = frag_data_player_pos(_frag_data);
        uint center_geo_normal_packed = _frag_data.data1.y;
        vec3 center_geo_normal = frag_data_geo_normal(_frag_data);
        uint center_shading_normal_packed = smple.packed_normal;
        vec3 center_shading_normal = ph_unpack_normal(center_shading_normal_packed);
        float center_luma = ph_luminance(center.lighting.rgb);
        float phi_luminance = max(
                0.05f,
                6.0f * sqrt(max(center.variance.z, 0.000001f))
        );

        vec3 color_sum = vec3(0.0f);
        vec2 moment_sum = vec2(0.0f);
        float variance_sum = 0.0f;
        float weight_sum = 0.0f;

        ivec2 history_size = textureSize(diffuse_history, 0);
        for (int i = 0; i < 9; ++i) {
            ivec2 p = frag_tex_coord + offset[i];
            if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, history_size)))
                continue;

            SampleHistory history;
            if (i == SVGF_CENTER_INDEX) {
                history = center;
            } else {
                sample_history_load(history, p);
            }
            if (!sample_history_is_valid(history)) continue;

            FragData sample_frag;
            if (i == SVGF_CENTER_INDEX) {
                sample_frag = _frag_data;
            } else {
                frag_data_load(sample_frag, p);
            }
            if (!svgf_prefilter_same_surface_class(_frag_data, sample_frag)) continue;

            uint sample_geo_normal_packed = sample_frag.data1.y;
            vec3 sample_pos = frag_data_player_pos(sample_frag);
            float plane_weight;
            if (sample_geo_normal_packed == center_geo_normal_packed) {
                plane_weight = svgf_plane_edge_stopping_weight(
                        center_pos,
                        sample_pos,
                        center_geo_normal,
                        SVGF_SPATIAL_PHI_PLANE
                );
            } else {
                plane_weight = svgf_plane_edge_stopping_weight(
                        center_pos,
                        sample_pos,
                        center_geo_normal,
                        frag_data_geo_normal(sample_frag),
                        SVGF_SPATIAL_PHI_PLANE
                );
            }
            uint sample_shading_normal_packed = smple.is_hand
                    ? sample_frag.data1.y
                    : sample_frag.data1.z;
            float detail_weight = svgf_packed_normal_edge_stopping_weight(
                    center_shading_normal,
                    center_shading_normal_packed,
                    sample_shading_normal_packed
            );
            float luma_weight = svgf_luma_edge_stopping_weight(
                    center_luma,
                    ph_luminance(history.lighting.rgb),
                    phi_luminance
            );
            float weight = kernel[i] * plane_weight * detail_weight * luma_weight;
            if (weight <= 0.0f || isnan(weight) || isinf(weight)) continue;

            color_sum += history.lighting.rgb * weight;
            moment_sum += history.variance.xy * weight;
            variance_sum += max(history.variance.z, 0.0f) * weight;
            weight_sum += weight;
        }

        // The center is always a compatible finite sample, so this guard is
        // only a last-resort protection against malformed fragment guides.
        if (weight_sum <= 0.000001f) {
            smple.color = clamp(center.lighting.rgb, -65504.0f, 65504.0f);
            smple.variance = clamp(center.variance.z, 0.0f, 65504.0f);
        } else if (smple.age < SVGF_FRESH_HISTORY_MAX) {
            vec3 reconstructed_color = color_sum / weight_sum;
            vec2 reconstructed_moments = moment_sum / weight_sum;
            float reconstructed_variance = max(
                    reconstructed_moments.y -
                            reconstructed_moments.x * reconstructed_moments.x,
                    0.0f
            );
            float fresh_variance_boost = 4.0f / max(smple.age, 1.0f);
            float accumulated_output_variance = variance_sum / weight_sum;

            smple.color = clamp(reconstructed_color, -65504.0f, 65504.0f);
            smple.variance = clamp(
                    max(
                            reconstructed_variance * fresh_variance_boost,
                            accumulated_output_variance
                    ),
                    0.0f,
                    65504.0f
            );
        } else {
            // Mature .z already stores variance scaled by temporal mix_factor.
            // Keep the center lighting untouched and only prefilter that value.
            smple.color = clamp(center.lighting.rgb, -65504.0f, 65504.0f);
            smple.variance = clamp(variance_sum / weight_sum, 0.0f, 65504.0f);
        }
    }

    svgf_sample_encode(smple, denoise_out);
}
