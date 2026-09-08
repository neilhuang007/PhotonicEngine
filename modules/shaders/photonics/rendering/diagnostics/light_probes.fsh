#version 430
// Opt-in, read-only diagnostic. No downstream rendering pass consumes these
// attachments. Each row is one light; each column is a fixed surface probe.
#include "/photonics/rendering/frag/common.glsl"
#include "/photonics/rendering/restir/direct/reservoir.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"
#include "/photonics/rendering/restir/indirect/reservoir.glsl"
#include "/photonics/interface/lighting_interface.glsl"
layout(location = 0) out vec4 unoccluded;
layout(location = 1) out vec4 visible;
layout(location = 2) out vec4 first_hit;
layout(location = 3) out vec4 sky_visibility;
layout(location = 4) out vec4 roof_hit;
layout(location = 5) out vec4 sun_hit;
layout(location = 6) out vec4 sun_direction_probe;
layout(location = 7) out vec4 primary_hit_probe;
void main() {
    unoccluded = visible = first_hit = sky_visibility = roof_hit = vec4(0.0);
    sun_hit = sun_direction_probe = primary_hit_probe = vec4(0.0);
    int probe = int(gl_FragCoord.x);
    int light_index = int(gl_FragCoord.y);
    // A separate world-space grid remains comparable while raster chunks load.
    // Only row zero performs these rays; they do not depend on the light list.
    if (light_index == 0) {
        vec3 origin = cameraPosition - world_offset
                + vec3(float(probe % 3 - 1) * 4.0, 0.0, float(probe / 3 - 1) * 4.0);
        RayIterator roof_ray;
        ray_iter_begin(roof_ray, origin, vec3(0.0, 1.0, 0.0));
        RayResult roof = ray_iter_next(roof_ray);
        roof_hit = ray_result_is_hit(roof)
                ? vec4(ray_result_position(roof) + world_offset, ray_result_is_transparent(roof) ? 2.0 : 1.0)
                : vec4(roof_ray.position + world_offset, ray_iter_is_in_bounds(roof_ray) ? -1.0 : 0.0);
    }
    if (light_index >= light_list_size) return;
    ivec2 pixel = textureSize(frag_data0, 0) * ivec2(probe % 3 + 1, probe / 3 + 1) / 4;
    frag_data_load(_frag_data, pixel);
    if (!frag_is_in_world) return;
    if (light_index == 0) {
        vec3 direction = normalize(get_sun_direction());
        vec3 ignored_color = vec3(0.0);
        sun_direction_probe = vec4(direction, float(sample_sun_color(frag_player_pos, frag_geo_normal, ignored_color)));
        RayIterator sun_ray;
        ray_iter_begin(sun_ray, frag_rt_pos, direction);
        RayResult sun_result = ray_iter_next(sun_ray);
        sun_hit = ray_result_is_hit(sun_result)
                ? vec4(ray_result_position(sun_result) + world_offset, ray_result_is_transparent(sun_result) ? 2.0 : 1.0)
                : vec4(sun_ray.position + world_offset, ray_iter_is_in_bounds(sun_ray) ? -1.0 : 0.0);
        RayIterator primary_ray;
        ray_iter_begin(primary_ray, frag_player_pos + rt_camera_position - frag_geo_normal * 0.002, -frag_geo_normal);
        RayResult primary = ray_iter_next(primary_ray);
        if (ray_result_is_hit(primary)) primary_hit_probe = vec4(ray_result_position(primary) + world_offset,
                voxel_data_is_light_transmissive(ray_result_voxel_data(primary)) ? 2.0 : 1.0);
    }
    if (light_index == 1) {
        DirectReservoir initial, final_reservoir;
        direct_reservoir_load_candidate(initial, pixel);
        vec3 initial_integrand;
        direct_sample_get_visible_color(initial.smple, frag_rt_pos, frag_geo_normal,
                frag_tex_normal, frag_is_light_transmissive, initial_integrand);
        initial.target_pdf = direct_sample_weight(initial_integrand);
        direct_reservoir_load(final_reservoir, pixel);
        DirectReconnection retained = direct_reconnection_load_current(ph_splat_pixel_index(pixel));
        roof_hit = vec4(initial_integrand * direct_reservoir_compute_ucw(initial), direct_reservoir_compute_ucw(initial));
        sun_hit = vec4(retained.integrand * direct_reservoir_compute_ucw(final_reservoir), direct_reservoir_compute_ucw(final_reservoir));
        sun_direction_probe = vec4(float(ph_splat_cell_count(ph_splat_pixel_index(pixel))),
                final_reservoir.total_samples, final_reservoir.target_pdf, final_reservoir.weight_sum);
        primary_hit_probe = vec4(retained.player_pos - frag_player_pos, float(retained.flags));
    }
    DirectSample sample_value = DirectSample(light_index, vec2(0.5));
    Light light = direct_sample_get_light(sample_value);
    vec3 source = direct_sample_get_position(sample_value, light);
    vec3 normal = frag_geo_normal, tex_normal = frag_tex_normal;
    direct_sample_orient_shading_normals(source, frag_rt_pos, frag_is_light_transmissive, normal, tex_normal);
    unoccluded = vec4(light_sample_at(light, frag_rt_pos, source, normal, tex_normal), 1.0);
    vec3 color;
    bool success = direct_sample_get_visible_color(sample_value, frag_rt_pos, normal, tex_normal, frag_is_light_transmissive, color);
    visible = vec4(color, float(success));
    // Integrate a stratified UV grid to distinguish an estimator energy loss
    // from legitimate soft-shadow visibility at the single midpoint sample.
    visible = vec4(0.0);
    for (int s = 0; s < 16; s++) {
        DirectSample reference_sample = DirectSample(light_index,
                (vec2(s % 4, s / 4) + 0.5) / 4.0);
        vec3 reference_color;
        bool reference_success = direct_sample_get_visible_color(reference_sample,
                frag_rt_pos, frag_geo_normal, frag_tex_normal, frag_is_light_transmissive, reference_color);
        visible += vec4(reference_color, float(reference_success)) / 16.0;
    }
    RayIterator ray;
    ray_iter_begin(ray, frag_rt_pos, normalize(source - frag_rt_pos));
    RayResult hit = ray_iter_next_block(ray, source);
    if (ray_result_is_hit(hit)) first_hit = vec4(ray_result_position(hit) + world_offset,
            ray_result_is_transparent(hit) ? 2.0 : 1.0);
    // A sky ray through an opaque emitter is occluded, including when a glass
    // pane is encountered first. Exercise the actual GI visibility routine.
    IndirectReservoir sky = indirect_reservoir_empty();
    sky.smple.hit_point = frag_player_pos + normalize(source - frag_rt_pos) * indirect_sky_distance;
    sky.smple.color = vec3(1.0);
    sky.weight = sky.total_samples = 1.0;
    float visibility;
    indirect_reservoir_validate_visiblity(sky, frag_rt_pos, visibility);
    sky_visibility = vec4(visibility, sky.weight, first_hit.w, 1.0);
}
