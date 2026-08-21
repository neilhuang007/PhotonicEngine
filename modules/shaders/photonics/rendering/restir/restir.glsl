#ifndef PH_SHARED_INCLUDE
#define PH_SHARED_INCLUDE

#define MINIMUM_RESERVOIR_WEIGHT 0.000001f

#include "/photonics/utility/color.glsl"

#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"

#include "/photonics/utility/projection.glsl"
#include "/photonics/utility/normal_encoding.glsl"

#define RESTIR_LIGHTING_OUT 1
#define RESTIR_LIGHTING_VARIANCE_OUT 2

//ph_required: uniform sampler2D restir_lighting;
//ph_required: uniform sampler2D restir_lighting_variance;

//ph_required: uniform sampler2D prev_restir_lighting;
//ph_required: uniform sampler2D prev_restir_lighting_variance;

struct SampleHistory {
    vec4 lighting;
    vec4 variance;
};

const float INVALID_SAMPLE_COMPONENT = -999.0f;
const SampleHistory INVALID_HISTORY = SampleHistory(vec4(INVALID_SAMPLE_COMPONENT), vec4(INVALID_SAMPLE_COMPONENT));

bool sample_history_is_valid(SampleHistory history) {
    return history.lighting.x != INVALID_SAMPLE_COMPONENT;
}

void sample_history_load(out SampleHistory smple) {
    smple.lighting = texelFetch(restir_lighting, frag_tex_coord, 0),
    smple.variance = vec4(0f);
}

SampleHistory sample_history_reproject_single(ivec2 texel) {
    FragData prev_frag;
    frag_data_load_previous(prev_frag, texel);

    if (!frag_data_is_in_world(prev_frag)) return INVALID_HISTORY;

    vec3 n = frag_data_geo_normal(prev_frag);
    if (dot(n, frag_geo_normal) < 0.99f) return INVALID_HISTORY;

    vec3 distance_from_plane =
            frag_player_pos - frag_data_player_pos(prev_frag);
    if (abs(dot(distance_from_plane, frag_geo_normal)) > 0.25f)
        return INVALID_HISTORY;

    vec4 lighting = texelFetch(prev_restir_lighting, ivec2(texel), 0);
    if (any(isnan(lighting))) return INVALID_HISTORY;

    vec4 variance = texelFetch(prev_restir_lighting_variance, ivec2(texel), 0);
    if (any(isnan(variance))) return INVALID_HISTORY;

    return SampleHistory(lighting, variance);
}

void sample_history_reproject(out SampleHistory smple) {
    vec2 center = ph_reproject_player_pos(
            frag_player_pos,
            frag_is_hand,
            get_taa_jitter()
    ).xy * PH_VIEW_SIZE - 0.5f;

    ivec2 base_texel = ivec2(floor(center));
    vec2 mix_factors = fract(center);
    ivec2 view_size = ivec2(PH_VIEW_SIZE);

    const ivec2 offsets[4] = ivec2[](
            ivec2(0, 0),
            ivec2(1, 0),
            ivec2(0, 1),
            ivec2(1, 1)
    );
    const vec2 weight_origins[4] = vec2[](
            vec2(1.0f, 1.0f),
            vec2(0.0f, 1.0f),
            vec2(1.0f, 0.0f),
            vec2(0.0f, 0.0f)
    );

    smple = SampleHistory(vec4(0.0f), vec4(0.0f));
    float weight_sum = 0.0f;

    for (int i = 0; i < offsets.length(); ++i) {
        ivec2 texel = base_texel + offsets[i];
        if (any(lessThan(texel, ivec2(0))) ||
                any(greaterThanEqual(texel, view_size))) continue;

        SampleHistory history = sample_history_reproject_single(texel);
        if (!sample_history_is_valid(history)) continue;

        vec2 bilinear_weight = abs(weight_origins[i] - mix_factors);
        float weight = bilinear_weight.x * bilinear_weight.y;

        smple.lighting += history.lighting * weight;
        smple.variance += history.variance * weight;
        weight_sum += weight;
    }

    if (weight_sum > 0.0f) {
        smple.lighting /= weight_sum;
        smple.variance /= weight_sum;
    }
}

void sample_history_combine_lighting(inout SampleHistory history, in SampleHistory smple) {
#if PH_RESTIR_DENOISER_PASSES != 0
    history.lighting.w = min(history.lighting.w, PH_RESTIR_ACCUMULATION_FRAMES);
    history.lighting.rgb = mix(history.lighting.rgb, smple.lighting.rgb, 1f / (++history.lighting.w));
#else
    if (history.lighting.a >= PH_RESTIR_ACCUMULATION_FRAMES - 1f)
        history.lighting *= ((PH_RESTIR_ACCUMULATION_FRAMES - 1f) / history.lighting.a);

    history.lighting.rgb+= smple.lighting.rgb;
    history.lighting.a++;
#endif
}

void sample_history_combine_moment(inout SampleHistory history, in SampleHistory smple) {
    float moment_alpha = 1f / history.lighting.a;
    vec2 moments = vec2(0f);

    moments.x = dot(smple.lighting.rgb, vec3(0.299, 0.587, 0.114));
    moments.y = moments.x * moments.x;

    history.variance.xy = mix(history.variance.xy, moments, moment_alpha);
    history.variance.w = 1f;
}

#if PH_RESTIR_ACCUMULATION_FRAMES < 4
float sample_history_min_variance(float samples) {
    return 1.0f;
}
#else
float sample_history_min_variance(float samples) {
    const float high_variance = 10.0f;

    if (samples > 4f) return 0.0001f;
    if (samples > 2f) return 0.01f;
    if (frag_is_hand) return high_variance;

    const float padding = 0.13f;
    const vec2 min = vec2(0 + padding) * PH_RENDER_SCALE;
    const vec2 max = vec2(1.0 - padding) * PH_RENDER_SCALE;

    vec2 uv = gl_FragCoord.xy * (vec2(1.0f) / vec2(viewWidth, viewHeight));
    return clamp(uv, min, max) != uv ? high_variance : 0.01f;
}
#endif

void sample_history_compute_variance(inout SampleHistory history, in SampleHistory smple) {
    float samples = history.lighting.a;
    float sample_variance = max(
        history.variance.y - (history.variance.x * history.variance.x),

        // With few samples, variance estimate is unreliable — use a high floor
            sample_history_min_variance(history.lighting.a)
    );

    history.variance.z = sample_variance / samples;
}

#endif
