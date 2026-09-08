#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/common.glsl"
#include "/photonics/rendering/restir/svgf/common.glsl"
#include "/photonics/rendering/restir/svgf/history.glsl"

layout(location = SVGF_DENOISE_OUT) out uvec4 denoise_out;
layout(location = SVGF_CHROMA_VARIANCE_OUT) out vec2 denoise_chroma_variance;

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
    denoise_chroma_variance = vec2(0.0f);

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

        vec4 center_chroma = texelFetch(chroma_history, frag_tex_coord, 0);
        if (any(isnan(center_chroma)) || any(isinf(center_chroma))) {
            svgf_sample_encode(smple, denoise_out);
            return;
        }
        vec2 center_chroma_variance = svgf_chroma_output_variance(center_chroma, smple.age);
        vec3 center_pos = frag_data_player_pos(_frag_data);
        uint center_geo_normal_packed = _frag_data.data1.y;
        vec3 center_geo_normal = frag_data_geo_normal(_frag_data);
        uint center_shading_normal_packed = smple.packed_normal;
        vec3 center_shading_normal = ph_unpack_normal(center_shading_normal_packed);
        float phi_luminance = max(
                0.05f,
                6.0f * sqrt(max(center.variance.z, 0.000001f))
        );

        vec3 color_sum = vec3(0.0f);
        vec2 moment_sum = vec2(0.0f);
        float variance_sum = 0.0f;
        float weight_sum = 0.0f;
        vec2 chroma_variance_sum = vec2(0.0f);
        SampleHistory neighbors[9];
        vec4 neighbor_chroma[9];
        float geometry_weights[9];
        vec4 spatial_chroma_moments = vec4(0.0f);
        vec2 spatial_luma_moments = vec2(0.0f);
        float geometry_weight_sum = 0.0f;

        ivec2 history_size = textureSize(diffuse_history, 0);
        for (int i = 0; i < 9; ++i) {
            geometry_weights[i] = 0.0f;
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
            vec4 chroma = i == SVGF_CENTER_INDEX ? center_chroma :
                    texelFetch(chroma_history, p, 0);
            if (any(isnan(chroma)) || any(isinf(chroma))) continue;
            float weight = kernel[i] * plane_weight * detail_weight;
            if (weight <= 0.0f || isnan(weight) || isinf(weight)) continue;
            neighbors[i] = history;
            neighbor_chroma[i] = chroma;
            geometry_weights[i] = weight;
            spatial_chroma_moments += chroma * weight;
            spatial_luma_moments += history.variance.xy * weight;
            geometry_weight_sum += weight;
        }

        // A one-sample temporal moment has zero chroma variance. Estimate its
        // uncertainty before applying color weights, otherwise an outlier
        // rejects every neighbor and incorrectly declares itself noise-free.
        vec2 local_chroma_variance = center_chroma_variance;
        if (smple.age < SVGF_FRESH_HISTORY_MAX && geometry_weight_sum > 0.000001f) {
            spatial_chroma_moments /= geometry_weight_sum;
            spatial_luma_moments /= geometry_weight_sum;
            float boost = 4.0f / max(smple.age, 1.0f);
            local_chroma_variance = max(local_chroma_variance, boost * max(
                    spatial_chroma_moments.zw -
                            spatial_chroma_moments.xy * spatial_chroma_moments.xy,
                    vec2(0.0f)));
            float local_luma_variance = boost * max(spatial_luma_moments.y -
                    spatial_luma_moments.x * spatial_luma_moments.x, 0.0f);
            phi_luminance = max(phi_luminance, 6.0f * sqrt(local_luma_variance));
        }
        for (int i = 0; i < 9; ++i) {
            if (geometry_weights[i] <= 0.0f) continue;
            SampleHistory history = neighbors[i];
            vec2 sample_chroma_variance = svgf_chroma_output_variance(
                    neighbor_chroma[i], history.lighting.a);
            float color_weight = svgf_color_edge_stopping_weight(
                    center.lighting.rgb, history.lighting.rgb, phi_luminance,
                    max(local_chroma_variance, sample_chroma_variance));
            float weight = geometry_weights[i] * color_weight;
            color_sum += history.lighting.rgb * weight;
            moment_sum += history.variance.xy * weight;
            variance_sum += max(history.variance.z, 0.0f) * weight;
            chroma_variance_sum += sample_chroma_variance * weight;
            weight_sum += weight;
        }

        // The center is always a compatible finite sample, so this guard is
        // only a last-resort protection against malformed fragment guides.
        if (weight_sum <= 0.000001f) {
            smple.color = clamp(center.lighting.rgb, -65504.0f, 65504.0f);
            smple.variance = clamp(center.variance.z, 0.0f, 65504.0f);
            denoise_chroma_variance = center_chroma_variance;
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
            denoise_chroma_variance = max(local_chroma_variance,
                    chroma_variance_sum / weight_sum);
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
            denoise_chroma_variance = chroma_variance_sum / weight_sum;
        }
    }

    svgf_sample_encode(smple, denoise_out);
}
