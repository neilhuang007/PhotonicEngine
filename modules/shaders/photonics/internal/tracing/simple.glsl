bool trace_light_vis(
    vec3 rt_pos,
    vec3 direction,
    vec3 light_rt_pos,
    int max_iterations,
    out vec3 tint_color,
    out float light_transmittance
) {
    tint_color = vec3(1.0f);
    light_transmittance = 1.0f;

    if (max_iterations <= 0) return false;

    float direction_length_squared = dot(direction, direction);
    if (!(direction_length_squared > 0.0f) ||
            isnan(direction_length_squared) ||
            isinf(direction_length_squared)) return false;

    vec3 ray_direction = direction * inversesqrt(direction_length_squared);

    RayIterator ray;
    ray_iter_begin(ray, rt_pos, ray_direction);
    ray.iterations = min(ray.iterations, max_iterations);

    RayResult result = missed_ray_result();

    vec4 running_tint_color = vec4(0.0f);

    while (true) {
        result = ray_iter_next_block(ray, light_rt_pos);

        if (ray_result_is_block(result, light_rt_pos)) break;

        if (ray_result_is_transparent(result)) {
            VoxelData voxel_data = ray_result_voxel_data(result);
            vec4 albedo = voxel_data_albedo(voxel_data);

            light_transmittance *= voxel_data_visibility_transmittance(voxel_data, albedo);
            ray_iter_accumulate_transparency_tint(
                ray,
                running_tint_color,
                voxel_data,
                albedo
            );

            ray_iter_skip_transparent(ray);
            ray_iter_offset_position(ray, ray.direction * 0.03f);

            continue;
        }

        break;
    }

    if (!ray_result_is_block(result, light_rt_pos)) return false;

    tint_color = running_tint_color.a == 0.0f ? vec3(1.0f) : running_tint_color.rgb;

    return true;
}

bool trace_segment_visibility(
    vec3 rt_pos,
    vec3 target_rt_pos,
    float minimum_hit_distance,
    int max_iterations
) {
    if (max_iterations <= 0 ||
            minimum_hit_distance < 0.0f ||
            isnan(minimum_hit_distance) ||
            isinf(minimum_hit_distance)) return false;

    vec3 to_target = target_rt_pos - rt_pos;
    float target_distance_squared = dot(to_target, to_target);
    if (!(target_distance_squared > 0.0f) ||
            isnan(target_distance_squared) ||
            isinf(target_distance_squared)) return false;

    float target_distance = sqrt(target_distance_squared);
    vec3 ray_direction = to_target / target_distance;
    float maximum_hit_distance = 0.999f * target_distance;
    if (minimum_hit_distance >= maximum_hit_distance) return true;

    RayIterator ray;
    ray_iter_begin(
        ray,
        rt_pos + ray_direction * minimum_hit_distance,
        ray_direction
    );
    ray.iterations = min(ray.iterations, max_iterations);

    while (true) {
        RayResult result = ray_iter_next(ray);
        if (!ray_result_is_hit(result)) {
            float traversed_distance = dot(
                ray.position - rt_pos,
                ray_direction
            );
            // Leaving the voxel world is a true miss. Exhausting the traversal
            // budget is accepted only after the iterator crossed Falcor's tMax.
            return !ray_iter_is_in_bounds(ray) ||
                    traversed_distance >= maximum_hit_distance;
        }

        float hit_distance = dot(
            ray_result_position(result) - rt_pos,
            ray_direction
        );
        if (hit_distance >= maximum_hit_distance) return true;
        return false;
    }
}
