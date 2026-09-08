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
#if PH_RESTIR_DENOISER_PASSES > 0
#if defined PH_ENABLE_BLOCKLIGHT
    // A changed GPU-visible block/light scene invalidates radiance history as
    // well as reservoir history. Do not trail old shadows after a block edit.
    if (ph_reservoir_splatting_history_valid != 0)
#endif
    sample_history_reproject(temporal_history, svgf_fast_history);
#endif

#if PH_RESTIR_DENOISER_PASSES > 0
    float exposure_ratio = get_exposure() / max(get_previous_exposure(), 1e-10f);
    temporal_history.lighting.rgb *= exposure_ratio;
    svgf_fast_history.rgb *= exposure_ratio;
    temporal_history.variance.x *= exposure_ratio;
    temporal_history.variance.yz *= exposure_ratio * exposure_ratio;
#endif

    sample_history_add_sample(temporal_history, svgf_fast_history,
            vec4(di_output.rgb + gi_output.rgb, min(di_output.a, gi_output.a)));
    sample_history_encode(temporal_history, svgf_history, svgf_visiblity_history);
}
