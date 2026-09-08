#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/temporal_reuse.glsl"

layout(location = DIRECT_RESERVOIR_0) out uvec3 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;

void main() {
    setup_frag_data(0);
    DirectReservoir result = direct_reservoir_empty();
    DirectReconnection reconnection = direct_reconnection_empty();
    if (frag_is_in_world) {
        direct_splat_temporal_reuse(result, reconnection);
    } else {
        direct_reconnection_store_current(ph_splat_pixel_index(frag_tex_coord), reconnection);
    }
    direct_reservoir_encode(result, di_reservoir_0, di_reservoir_1);
}
