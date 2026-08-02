#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

layout(location = DIRECT_RESERVOIR_0) out uvec2 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;

void main() {
    setup_frag_data(0);
    if (!frag_is_in_world) discard;

    DirectReservoir reservoir = direct_reservoir_empty();
    direct_reservoir_load(reservoir, frag_tex_coord);

    direct_reservoir_validate_visibility(reservoir, frag_rt_pos);
    direct_reservoir_encode(
        reservoir,
        di_reservoir_0,
        di_reservoir_1
    );
}
