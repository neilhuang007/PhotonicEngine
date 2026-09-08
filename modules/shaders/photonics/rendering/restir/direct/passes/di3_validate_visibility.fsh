#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL
#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"

layout(location = DIRECT_RESERVOIR_0) out uvec3 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
layout(location = DIRECT_OUT) out vec4 di_output;
// Surface emission belongs to the native pack's material shader; this pass
// resolves incident irradiance only. Adding Le here double-counts emitters.

void main() {
    setup_frag_data(3);
    di_output = vec4(0.0f, 0.0f, 0.0f, 1.0f);
    DirectReservoir reservoir = direct_reservoir_empty();
    if (frag_is_in_world) {
        bool valid = direct_reservoir_load_previous(reservoir, frag_tex_coord, false);
        DirectReconnection reconnection = direct_reconnection_load_current(
                uint(frag_tex_coord.y) * uint(PH_VIEW_SIZE.x) + uint(frag_tex_coord.x));
        float ucw = direct_reservoir_compute_ucw(reservoir);
        if (valid && !direct_reservoir_is_empty(reservoir) && ucw > 0.0f &&
                direct_reconnection_is_in_world(reconnection) &&
                direct_reconnection_is_finite(reconnection)) {
            di_output.rgb += reconnection.integrand * ucw * get_exposure();
        }
    }
    direct_reservoir_encode(reservoir, di_reservoir_0, di_reservoir_1);
}
