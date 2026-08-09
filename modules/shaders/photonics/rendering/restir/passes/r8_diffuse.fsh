#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"

#if !defined PH_RESTIR_GI_MODIFIER_DISABLED
#include "/photonics/modifiers/restir_gi_modifier.glsl"
#endif

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out uvec2 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) return;

#if defined PH_ENABLE_RESTIR_GI
    // INDIRECT LIGHTING

    IndirectReservoir indirect_reservoir = indirect_reservoir_empty();
    indirect_reservoir_load(indirect_reservoir, frag_tex_coord);

    lighting.rgb = indirect_reservoir_get_final_color(indirect_reservoir);

#ifndef PH_RESTIR_GI_MODIFIER_DISABLED
    modify_restir_gi(lighting.rgb);
#endif

    indirect_reservoir_encode(indirect_reservoir, gi_reservoir_0, gi_reservoir_1);
#endif


#if defined PH_ENABLE_BLOCKLIGHT
    // DIRECT LIGHTING

    if (!frag_is_hand) {
        vec3 surface_position = frag_player_pos + rt_camera_position;
        vec3 interior_position = surface_position - frag_geo_normal * 0.01f;

        RayIterator primary_ray;
        ray_iter_begin(primary_ray, interior_position, -frag_geo_normal);

        RayResult primary_hit = ray_iter_next_block(
            primary_ray,
            floor(interior_position) + 0.5f
        );
        Light primary_light = ray_result_light_data(primary_hit);
        if (light_is_valid(primary_light)) {
            lighting.rgb += primary_light.color * get_exposure();
        }
    }

    DirectReservoir direct_reservoir = direct_reservoir_empty();
    direct_reservoir_load_previous(direct_reservoir, frag_tex_coord, false);

    lighting.rgb += direct_reservoir_get_final_color(
        direct_reservoir,
        frag_rt_pos,
        frag_geo_normal,
        frag_tex_normal
    ) * get_exposure();

    direct_reservoir_encode(
        direct_reservoir,
        di_reservoir_0,
        di_reservoir_1
    );
#endif
}
