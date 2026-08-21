#version 430

#define FRAG_USE_RT_POS
#define FRAG_USE_GEO_NORMAL
#define FRAG_USE_TEX_NORMAL

#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/restir.glsl"
#if defined PH_ENABLE_BLOCKLIGHT
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"
#endif

#if !defined PH_RESTIR_GI_MODIFIER_DISABLED
#include "/photonics/modifiers/restir_gi_modifier.glsl"
#endif

#if defined PH_ENABLE_BLOCKLIGHT
layout(location = DIRECT_RESERVOIR_0) out uvec3 di_reservoir_0;
layout(location = DIRECT_RESERVOIR_1) out vec3 di_reservoir_1;
#endif

#if defined PH_ENABLE_RESTIR_GI
layout(location = INDIRECT_RESERVOIR_0) out vec4 gi_reservoir_0;
layout(location = INDIRECT_RESERVOIR_1) out uvec3 gi_reservoir_1;
#endif

layout(location = RESTIR_LIGHTING_OUT) out vec4 lighting;

#if defined PH_ENABLE_BLOCKLIGHT
const int primary_emission_max_iterations = 40;
const float primary_emission_max_distance = 64.0f;

vec3 sample_visible_primary_emission() {
    vec3 surface_position = frag_player_pos + rt_camera_position;
    vec3 interior_position =
            surface_position - frag_geo_normal * 0.01f;

    RayIterator surface_ray;
    ray_iter_begin(surface_ray, interior_position, -frag_geo_normal);
    RayResult surface_hit = ray_iter_next_block(
        surface_ray,
        floor(interior_position) + 0.5f
    );
    Light surface_light = ray_result_light_data(surface_hit);
    if (light_is_valid(surface_light)) return surface_light.color;

    if (!frag_is_light_transmissive) return vec3(0.0f);

    vec3 camera_ray_direction = normalize(
        surface_position - rt_camera_position
    );

    RayIterator primary_ray;
    ray_iter_begin(primary_ray, frag_rt_pos, camera_ray_direction);
    primary_ray.iterations = min(
        primary_ray.iterations,
        primary_emission_max_iterations
    );

    vec4 running_tint = vec4(0.0f);
    float transmittance = 1.0f;
    while (primary_ray.iterations > 0) {
        RayResult primary_hit = ray_iter_next(primary_ray);
        if (!ray_result_is_hit(primary_hit)) break;
        float continuation_distance = dot(
            ray_result_position(primary_hit) - frag_rt_pos,
            camera_ray_direction
        );
        if (continuation_distance < 0.0f ||
                continuation_distance > primary_emission_max_distance) break;

        Light primary_light = ray_result_light_data(primary_hit);
        vec3 primary_throughput = transmittance * (
            running_tint.a == 0.0f
                    ? vec3(1.0f)
                    : running_tint.rgb
        );
        if (light_is_valid(primary_light)) {
            return primary_light.color * primary_throughput;
        }

        VoxelData voxel_data = ray_result_voxel_data(primary_hit);
        if (!voxel_data_is_light_transmissive(voxel_data)) break;

        vec4 albedo = voxel_data_albedo(voxel_data);
        transmittance *= voxel_data_visibility_transmittance(
            voxel_data,
            albedo
        );
        ray_iter_accumulate_transparency_tint(
            primary_ray,
            running_tint,
            voxel_data,
            albedo
        );
        if (!(transmittance > 0.0f)) break;

        ray_iter_skip_transparent(primary_ray);
        ray_iter_offset_position(
            primary_ray,
            primary_ray.direction * 0.03f
        );
    }

    return vec3(0.0f);
}
#endif

void main() {
    lighting = vec4(0.0f, 0.0f, 0.0f, 1.0f);

    setup_frag_data(0);
    if (!frag_is_in_world) {
#if defined PH_ENABLE_BLOCKLIGHT
        DirectReservoir direct_reservoir = direct_reservoir_empty();
        direct_reservoir_encode(
            direct_reservoir,
            di_reservoir_0,
            di_reservoir_1
        );
#endif
#if defined PH_ENABLE_RESTIR_GI
        IndirectReservoir indirect_reservoir = indirect_reservoir_empty();
        indirect_reservoir_encode(
            indirect_reservoir,
            gi_reservoir_0,
            gi_reservoir_1
        );
#endif
        return;
    }

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
        lighting.rgb += sample_visible_primary_emission() * get_exposure();
    }

    DirectReservoir direct_reservoir = direct_reservoir_empty();
    bool direct_reservoir_valid = direct_reservoir_load_previous(
        direct_reservoir,
        frag_tex_coord,
        false
    );
    DirectReconnection direct_reconnection =
            direct_reconnection_load_current(
                uint(frag_tex_coord.y) * uint(PH_VIEW_SIZE.x) +
                        uint(frag_tex_coord.x)
            );

    float direct_ucw = direct_reservoir_compute_ucw(direct_reservoir);
    if (direct_reservoir_valid &&
            !direct_reservoir_is_empty(direct_reservoir) &&
            direct_ucw > 0.0f &&
            direct_reconnection_is_in_world(direct_reconnection) &&
            direct_reconnection_is_finite(direct_reconnection)) {
        lighting.rgb += direct_reconnection.integrand * direct_ucw *
                get_exposure();
    }

    direct_reservoir_encode(
        direct_reservoir,
        di_reservoir_0,
        di_reservoir_1
    );
#endif
}
