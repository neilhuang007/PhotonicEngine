#version 430

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"
#include "/photonics/rendering/indirect_lighting.glsl"

layout(location = 3) out vec4 gi_reservoir_0;
layout(location = 4) out uvec3 gi_reservoir_1;

void main() {
    setup_frag_data(0);
    indirect_reservoir_encode(indirect_reservoir_empty(), gi_reservoir_0, gi_reservoir_1);
    if (!frag_is_in_world) return;

    vec3 indirect_result = vec3(0.0f);
    vec3 hit_normal = frag_tex_normal;
    vec3 hit_position = frag_rt_pos;

    // Needs this for compatability
    uint rnd_state = frag_rnd_state;
    sample_indirect(
            indirect_result,
            frag_rt_pos,
            frag_tex_normal,
            rnd_state,

            hit_position,
            hit_normal
    );

    indirect_result *= get_exposure();

    IndirectReservoir reservoir = indirect_reservoir_empty();
    indirect_sample_set_color(reservoir.smple, indirect_result);
    indirect_sample_set_hit_normal(reservoir.smple, hit_normal);
    indirect_sample_set_hit_point(reservoir.smple, hit_position, frag_rt_pos, frag_tex_normal, frag_rnd_state);

    reservoir.weight = indirect_sample_weight(reservoir.smple);
    reservoir.total_samples = 1.0f;

    indirect_reservoir_finalize_weight(reservoir, reservoir.weight);
    indirect_reservoir_encode(reservoir, gi_reservoir_0, gi_reservoir_1);
}
