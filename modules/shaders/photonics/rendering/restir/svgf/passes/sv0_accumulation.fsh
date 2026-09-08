#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL

#define REPROJECT_PASS

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/common.glsl"
#include "/photonics/rendering/restir/svgf/common.glsl"
#include "/photonics/rendering/restir/svgf/history.glsl"

uniform sampler2D di_output;
uniform sampler2D gi_output;
#if defined PH_ENABLE_BLOCKLIGHT
//ph_required: uniform int ph_reservoir_splatting_history_valid;
#endif

layout(location = SVGF_HISTORY_OUT) out uvec4 svgf_history;
layout(location = SVGF_FAST_HISTORY_OUT) out vec4 svgf_fast_history;
layout(location = SVGF_VISIBILITY_HISTORY_OUT) out float svgf_visiblity_history;

// Compact, independently implemented adaptation of the statistical history
// clamping and anti-lag ideas described by RELAX. The responsive history and
// current noisy input are compared only on compatible receiver geometry.
// Raw temporal luminance moments remain raw moments; color clipping does not
// rewrite them. A statistically significant reset updates moments and ages as
// one coherent state transition.
// See docs/denoiser-primary-sources-2026-09-07.md.
const float SVGF_HISTORY_CLAMP_SIGMA = 2.0f;
const float SVGF_HISTORY_RESET_SIGMA = 2.0f;
const float SVGF_TEMPORAL_PHI_PLANE = 0.025f;

struct SvgfResponsiveStatistics {
    vec3 mean_ycocg;
    vec3 sigma_ycocg;
    bool valid;
};

struct SvgfNoisyStatistics {
    vec3 mean_rgb;
    float sigma_y;
    bool valid;
};

vec3 svgf_stat_center_shading_normal;
uint svgf_stat_center_shading_packed;
uint svgf_stat_center_geo_packed;
vec3 svgf_stat_noisy_center;

vec3 svgf_load_noisy_lighting(ivec2 texel) {
    vec3 lighting = vec3(0.0f);
#if defined PH_ENABLE_BLOCKLIGHT
    lighting += texelFetch(di_output, texel, 0).rgb;
#endif
#if defined PH_ENABLE_RESTIR_GI
    lighting += texelFetch(gi_output, texel, 0).rgb;
#endif
    return lighting;
}

float svgf_temporal_geometry_weight(
        FragData sample_frag,
        vec3 center_shading_normal,
        uint center_shading_packed,
        uint center_geo_packed
) {
    if (!frag_data_is_in_world(sample_frag) ||
            frag_data_is_hand(sample_frag) != frag_is_hand ||
            frag_data_is_light_transmissive(sample_frag) != frag_is_light_transmissive)
        return 0.0f;

    uint sample_shading_packed = frag_data_is_hand(sample_frag)
            ? sample_frag.data1.y
            : sample_frag.data1.z;
    float shading_normal_weight = svgf_packed_normal_edge_stopping_weight(
            center_shading_normal,
            center_shading_packed,
            sample_shading_packed
    );
    float plane_weight;
    if (center_geo_packed == sample_frag.data1.y) {
        plane_weight = svgf_plane_edge_stopping_weight(
                frag_player_pos,
                frag_data_player_pos(sample_frag),
                frag_geo_normal,
                SVGF_TEMPORAL_PHI_PLANE
        );
    } else {
        plane_weight = svgf_plane_edge_stopping_weight(
                frag_player_pos,
                frag_data_player_pos(sample_frag),
                frag_geo_normal,
                frag_data_geo_normal(sample_frag),
                SVGF_TEMPORAL_PHI_PLANE
        );
    }
    return shading_normal_weight * plane_weight;
}

SvgfResponsiveStatistics svgf_gather_responsive_statistics(
        vec2 previous_pixel,
        float exposure_ratio
) {
    vec3 first_moment = vec3(0.0f);
    vec3 second_moment = vec3(0.0f);
    float weight_sum = 0.0f;
    ivec2 center = ivec2(floor(previous_pixel + 0.5f));
    ivec2 history_size = textureSize(prev_fast_diffuse_history, 0);

    for (int i = 0; i < 9; ++i) {
        ivec2 p = center + offset[i];
        if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, history_size)))
            continue;

        FragData previous_frag;
        frag_data_load_previous(previous_frag, p);
        float weight = kernel[i] * svgf_temporal_geometry_weight(
                previous_frag,
                svgf_stat_center_shading_normal,
                svgf_stat_center_shading_packed,
                svgf_stat_center_geo_packed
        );
        vec4 responsive = texelFetch(prev_fast_diffuse_history, p, 0);
        responsive.rgb *= exposure_ratio;
        if (weight <= 0.0f || responsive.w <= 0.0f ||
                any(isnan(responsive)) || any(isinf(responsive)))
            continue;

        vec3 ycocg = svgf_rgb_to_ycocg(responsive.rgb);
        first_moment += ycocg * weight;
        second_moment += ycocg * ycocg * weight;
        weight_sum += weight;
    }

    if (weight_sum <= 0.0001f)
        return SvgfResponsiveStatistics(vec3(0.0f), vec3(0.0f), false);

    first_moment /= weight_sum;
    second_moment /= weight_sum;
    return SvgfResponsiveStatistics(
            first_moment,
            sqrt(max(second_moment - first_moment * first_moment, vec3(0.0f))),
            true
    );
}

SvgfNoisyStatistics svgf_gather_noisy_statistics() {
    vec3 first_moment = vec3(0.0f);
    float second_luma_moment = 0.0f;
    float weight_sum = 0.0f;
    ivec2 history_size = textureSize(diffuse_history, 0);

    for (int i = 0; i < 9; ++i) {
        ivec2 p = frag_tex_coord + offset[i];
        if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, history_size)))
            continue;

        FragData sample_frag;
        vec3 noisy;
        if (i == SVGF_CENTER_INDEX) {
            sample_frag = _frag_data;
            noisy = svgf_stat_noisy_center;
        } else {
            frag_data_load(sample_frag, p);
            noisy = svgf_load_noisy_lighting(p);
        }
        float weight = kernel[i] * svgf_temporal_geometry_weight(
                sample_frag,
                svgf_stat_center_shading_normal,
                svgf_stat_center_shading_packed,
                svgf_stat_center_geo_packed
        );
        if (weight <= 0.0f || any(isnan(noisy)) || any(isinf(noisy)))
            continue;

        float luma = svgf_rgb_to_ycocg(noisy).x;
        first_moment += noisy * weight;
        second_luma_moment += luma * luma * weight;
        weight_sum += weight;
    }

    if (weight_sum <= 0.0001f)
        return SvgfNoisyStatistics(vec3(0.0f), 0.0f, false);

    first_moment /= weight_sum;
    second_luma_moment /= weight_sum;
    float mean_luma = svgf_rgb_to_ycocg(first_moment).x;
    return SvgfNoisyStatistics(
            first_moment,
            sqrt(max(second_luma_moment - mean_luma * mean_luma, 0.0f)),
            true
    );
}

void svgf_apply_history_clamp(
        inout SampleHistory history,
        inout vec4 fast_history,
        vec3 noisy_center,
        float noisy_visibility,
        SvgfResponsiveStatistics responsive,
        SvgfNoisyStatistics noisy
) {
    if (!responsive.valid || !noisy.valid || history.lighting.w <= fast_history.w)
        return;

    vec3 slow_ycocg = svgf_rgb_to_ycocg(history.lighting.rgb);
    vec3 fast_center_ycocg = svgf_rgb_to_ycocg(fast_history.rgb);
    vec3 color_min = responsive.mean_ycocg -
            SVGF_HISTORY_CLAMP_SIGMA * responsive.sigma_ycocg;
    vec3 color_max = responsive.mean_ycocg +
            SVGF_HISTORY_CLAMP_SIGMA * responsive.sigma_ycocg;

    // Including the responsive center avoids clipping a real bright feature
    // merely because its compatible neighborhood is dark.
    color_min = min(color_min, fast_center_ycocg);
    color_max = max(color_max, fast_center_ycocg);
    vec3 clamped_ycocg = clamp(slow_ycocg, color_min, color_max);
    history.lighting.rgb = max(svgf_ycocg_to_rgb(clamped_ycocg), vec3(0.0f));

    float slow_luma = svgf_rgb_to_ycocg(history.lighting.rgb).x;
    float noisy_mean_luma = svgf_rgb_to_ycocg(noisy.mean_rgb).x;
    float uncertainty = SVGF_HISTORY_RESET_SIGMA *
            (responsive.sigma_ycocg.x + noisy.sigma_y);
    float discrepancy = max(abs(slow_luma - noisy_mean_luma) - uncertainty, 0.0f);
    float reset_amount = clamp(
            discrepancy / max(max(abs(slow_luma), abs(noisy_mean_luma)) + uncertainty, 0.0001f),
            0.0f,
            1.0f
    );
    // Preserve decisive lighting steps while suppressing weak resets caused
    // by a bright center inside an otherwise dark statistical neighborhood.
    reset_amount *= reset_amount;

    if (reset_amount <= 0.0f)
        return;

    history.lighting.rgb = mix(history.lighting.rgb, noisy_center, reset_amount);
    fast_history.rgb = mix(fast_history.rgb, noisy_center, reset_amount);
    history.lighting.w = max(mix(history.lighting.w, 1.0f, reset_amount), 1.0f);
    fast_history.w = max(mix(fast_history.w, 1.0f, reset_amount), 1.0f);
    history.visibility = mix(history.visibility, noisy_visibility, reset_amount);

    // M1/M2 describe raw luminance samples. Preserve them for clipping-only
    // changes and reinitialize them only as part of a confidence reset.
    vec2 fresh_moments = sample_history_moments(noisy_center);
    history.variance.xy = mix(history.variance.xy, fresh_moments, reset_amount);
    history.variance.z = mix(
            history.variance.z,
            sample_history_min_variance(1.0f),
            reset_amount
    );
}

void main() {
    svgf_history = uvec4(0u);
    svgf_fast_history = vec4(0.0f);
    svgf_visiblity_history = 1.0f;

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_BLOCKLIGHT
    vec4 di_output = texelFetch(di_output, frag_tex_coord, 0);
#else
    const vec4 di_output = vec4(0.0f, 0.0f, 0.0f, 1.0f);
#endif

#if defined PH_ENABLE_RESTIR_GI
    vec4 gi_output = texelFetch(gi_output, frag_tex_coord, 0);
#else
    const vec4 gi_output = vec4(0.0f, 0.0f, 0.0f, 1.0f);
#endif

    SampleHistory temporal_history = sample_history_empty();
    bool history_reprojected = false;
    vec2 previous_pixel = vec2(-1.0f);
#if PH_RESTIR_DENOISER_PASSES > 0
#if defined PH_ENABLE_BLOCKLIGHT
    // A changed GPU-visible block/light scene invalidates radiance history as
    // well as reservoir history. Do not trail old shadows after a block edit.
    if (ph_reservoir_splatting_history_valid != 0)
#endif
    history_reprojected = sample_history_reproject(
            temporal_history,
            svgf_fast_history,
            previous_pixel
    );
#endif

    float exposure_ratio = 1.0f;
#if PH_RESTIR_DENOISER_PASSES > 0
    exposure_ratio = get_exposure() / max(get_previous_exposure(), 1e-10f);
    temporal_history.lighting.rgb *= exposure_ratio;
    svgf_fast_history.rgb *= exposure_ratio;
    temporal_history.variance.x *= exposure_ratio;
    temporal_history.variance.yz *= exposure_ratio * exposure_ratio;
#endif

    vec4 noisy_center = vec4(
            di_output.rgb + gi_output.rgb,
            min(di_output.a, gi_output.a)
    );

    SvgfResponsiveStatistics responsive_statistics = SvgfResponsiveStatistics(
            vec3(0.0f),
            vec3(0.0f),
            false
    );
    SvgfNoisyStatistics noisy_statistics = SvgfNoisyStatistics(
            vec3(0.0f),
            0.0f,
            false
    );
#if PH_RESTIR_DENOISER_PASSES > 0 && PH_RESTIR_ACCUMULATION_FRAMES >= 12
    if (history_reprojected && temporal_history.lighting.w >= 4.0f) {
        svgf_stat_center_shading_packed = frag_is_hand
                ? _frag_data.data1.y
                : _frag_data.data1.z;
        svgf_stat_center_shading_normal = ph_unpack_normal(
                svgf_stat_center_shading_packed
        );
        svgf_stat_center_geo_packed = _frag_data.data1.y;
        svgf_stat_noisy_center = noisy_center.rgb;
        responsive_statistics = svgf_gather_responsive_statistics(previous_pixel, exposure_ratio);
        noisy_statistics = svgf_gather_noisy_statistics();
    }
#endif

    sample_history_add_sample(temporal_history, svgf_fast_history,
            noisy_center);
#if PH_RESTIR_DENOISER_PASSES > 0 && PH_RESTIR_ACCUMULATION_FRAMES >= 12
    svgf_apply_history_clamp(
            temporal_history,
            svgf_fast_history,
            noisy_center.rgb,
            noisy_center.a,
            responsive_statistics,
            noisy_statistics
    );
#endif
    sample_history_encode(temporal_history, svgf_history, svgf_visiblity_history);
}
