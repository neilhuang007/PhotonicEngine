#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"
#include "/photonics/modifiers/restir_gi_modifier.glsl"

layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
layout(location = INDIRECT_OUT) out vec4 gi_output;

void main() {
    setup_frag_data(3);
    gi_output = vec4(0.0f, 0.0f, 0.0f, 1.0f);
    IndirectReservoir result = indirect_reservoir_empty();
    if (frag_is_in_world && indirect_reservoir_load_previous(result, frag_tex_coord, false)) {
        indirect_reservoir_validate_visiblity(result, frag_rt_pos, gi_output.a);
        gi_output.rgb = indirect_reservoir_get_final_color(result);
#ifndef PH_RESTIR_GI_MODIFIER_DISABLED
        modify_restir_gi(gi_output.rgb);
#endif
    }
    indirect_reservoir_encode(result, gi_reservoir_0, gi_reservoir_1);
}
