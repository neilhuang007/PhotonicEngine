#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/common.glsl"
#include "/photonics/rendering/restir/svgf/common.glsl"

layout(location = SVGF_DENOISE_OUT) out uvec4 denoise_out;
layout(location = SVGF_CHROMA_VARIANCE_OUT) out vec2 denoise_chroma_variance;

void main() {
    setup_frag_data(0);
    denoise_out = uvec4(0u);
    denoise_chroma_variance = vec2(0.0f);
    if (!frag_is_in_world) return;

    SvgfSample denoised_result = svgf_sample_empty();
    svgf_sample_load(denoised_result, frag_tex_coord);

    denoise_out = uvec4(floatBitsToUint(denoised_result.color / get_exposure()), 0u);
}
