#include "/photonics/utility/normal_encoding.glsl"

#define SVGF_DENOISE_OUT 0
#define SVGF_CHROMA_VARIANCE_OUT 1
uniform sampler2D prev_denoise_chroma_variance;
//ph_required: uniform usampler2D prev_denoise_result;

// 3×3 Gaussian Kernel & Offsets
const float kernel[9] = float[](
        1.0 / 6., 2.0 / 3., 1.0 / 6.,
        2.0 / 3., 1.0, 2.0 / 3.,
        1.0 / 6., 2.0 / 3., 1.0 / 6.
);

const ivec2 offset[9] = ivec2[](
        ivec2(-1, -1), ivec2(0, -1), ivec2(1, -1),
        ivec2(-1, 0), ivec2(0, 0), ivec2(1, 0),
        ivec2(-1, 1), ivec2(0, 1), ivec2(1, 1)
);

// Variance is non-negative, leaving its half-float sign bit available for
// sample metadata without sacrificing a color channel's sign.
const uint SVGF_HAND_BIT = 0x80000000u;

#define SVGF_CENTER_INDEX 4

struct SvgfSample {
    vec3 color;
    float variance;

    float depth;
    float age;

    uint packed_normal;
    bool is_hand;
};

SvgfSample svgf_sample_empty() {
    return SvgfSample(vec3(0.0f), 0.0f, 1.0f, 0.0f, 0u, false);
}

vec3 svgf_sample_get_normal(SvgfSample smple) {
    return ph_unpack_normal(smple.packed_normal);
}

void svgf_sample_decode(out SvgfSample smple, uvec4 value) {
    vec2 unpacked = unpackHalf2x16(value.x);
    smple.color.rg = unpacked;

    unpacked = unpackHalf2x16(value.y & ~SVGF_HAND_BIT);
    smple.color.b = unpacked.x;
    smple.variance = unpacked.y;

    unpacked = unpackUnorm2x16(value.z);
    smple.depth = unpacked.x;
    smple.age = unpacked.y * PH_RESTIR_ACCUMULATION_FRAMES;

    smple.packed_normal = value.w;
    smple.is_hand = (value.y & SVGF_HAND_BIT) != 0u;
}

void svgf_sample_encode(SvgfSample smple, out uvec4 value) {
    value.x = packHalf2x16(smple.color.rg);
    value.y = packHalf2x16(vec2(
            smple.color.b,
            clamp(smple.variance, 0.0f, 65504.0f)
    )) | (smple.is_hand ? SVGF_HAND_BIT : 0u);
    value.z = packUnorm2x16(vec2(smple.depth, smple.age / PH_RESTIR_ACCUMULATION_FRAMES));
    value.w = smple.packed_normal;
}

void svgf_sample_load(out SvgfSample smple, ivec2 tex_coord) {
    svgf_sample_decode(smple, texelFetch(prev_denoise_result, tex_coord, 0));
}

bool svgf_sample_is_finite(SvgfSample smple) {
    return !any(isnan(smple.color)) && !any(isinf(smple.color)) &&
            !isnan(smple.variance) && !isinf(smple.variance) &&
            !isnan(smple.depth) && !isinf(smple.depth) &&
            !isnan(smple.age) && !isinf(smple.age);
}

vec3 svgf_rgb_to_ycocg(vec3 rgb) {
    return vec3(
            dot(rgb, vec3(0.25f, 0.5f, 0.25f)),
            dot(rgb, vec3(0.5f, 0.0f, -0.5f)),
            dot(rgb, vec3(-0.25f, 0.5f, -0.25f))
    );
}

vec3 svgf_ycocg_to_rgb(vec3 ycocg) {
    return vec3(
            ycocg.x + ycocg.y - ycocg.z,
            ycocg.x + ycocg.z,
            ycocg.x - ycocg.y - ycocg.z
    );
}

float svgf_normal_edge_stopping_weight(vec3 center_normal, vec3 sample_normal)
{
    float weight = clamp(dot(center_normal, sample_normal), 0.0f, 1.0f);
    weight *= weight;
    weight *= weight;
    weight *= weight;
    weight *= weight;
    weight *= weight;
    weight *= weight;
    return weight * weight;
}

float svgf_packed_normal_edge_stopping_weight(
        vec3 center_normal,
        uint center_packed_normal,
        uint sample_packed_normal
) {
    // Equal encodings produce the same decoded unit normal, whose weight is 1.
    if (sample_packed_normal == center_packed_normal) return 1.0f;

    return svgf_normal_edge_stopping_weight(
            center_normal,
            ph_unpack_normal(sample_packed_normal)
    );
}

float svgf_depth_edge_stopping_weight(float center_depth, float sample_depth, float phi)
{
    return exp(-abs(center_depth - sample_depth) / phi);
}

float svgf_plane_edge_stopping_weight(
        vec3 center_pos,
        vec3 sample_pos,
        vec3 center_geo_normal,
        vec3 sample_geo_normal,
        float phi
) {
    if (any(isnan(center_pos)) || any(isinf(center_pos)) ||
            any(isnan(sample_pos)) || any(isinf(sample_pos)) ||
            any(isnan(center_geo_normal)) || any(isinf(center_geo_normal)) ||
            any(isnan(sample_geo_normal)) || any(isinf(sample_geo_normal)))
        return 0.0f;

    float normal_alignment = clamp(dot(center_geo_normal, sample_geo_normal), 0.0f, 1.0f);
    float normal_weight = smoothstep(0.8f, 0.99f, normal_alignment);

    vec3 center_to_sample = sample_pos - center_pos;
    float plane_distance = max(
            abs(dot(center_to_sample, center_geo_normal)),
            abs(dot(center_to_sample, sample_geo_normal))
    );
    return normal_weight * exp(-plane_distance / max(phi, 0.0001f));
}

float svgf_plane_edge_stopping_weight(
        vec3 center_pos,
        vec3 sample_pos,
        vec3 shared_geo_normal,
        float phi
) {
    // This overload is equivalent to the two-normal form when both packed
    // geometric normals are equal: alignment is 1 and both plane dots match.
    if (any(isnan(center_pos)) || any(isinf(center_pos)) ||
            any(isnan(sample_pos)) || any(isinf(sample_pos)) ||
            any(isnan(shared_geo_normal)) || any(isinf(shared_geo_normal)))
        return 0.0f;

    return exp(-abs(dot(sample_pos - center_pos, shared_geo_normal)) /
            max(phi, 0.0001f));
}

// Independent chroma uncertainty prevents a luminance-null color fluctuation
// from becoming a false edge. Stable color projections have low chroma variance
// and retain their boundaries even when their luminance matches the receiver.
float svgf_color_edge_stopping_weight(
        vec3 center, vec3 sample_color, float phi_luma, vec2 chroma_variance)
{
    vec2 chroma_delta = abs(svgf_rgb_to_ycocg(center - sample_color).yz);
    vec2 phi_chroma = 6.0f * sqrt(max(chroma_variance, vec2(0.0000000001f)));
    vec2 chroma_distance = chroma_delta / phi_chroma;
    float luma_distance = abs(ph_luminance(center) - ph_luminance(sample_color)) /
            max(phi_luma, 0.000001f);
    return exp(-max(luma_distance, max(chroma_distance.x, chroma_distance.y)));
}

float svgf_shadow_stopping_weight(float center_vis, float sample_vis, float phi)
{
    return exp(-abs(center_vis - sample_vis) / phi);
}
