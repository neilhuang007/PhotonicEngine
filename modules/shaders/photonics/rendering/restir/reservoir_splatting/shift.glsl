#ifndef PH_RESERVOIR_SPLATTING_SHIFT_INCLUDE
#define PH_RESERVOIR_SPLATTING_SHIFT_INCLUDE

//ph_required: uniform mat4 gbufferModelView;
//ph_required: uniform mat4 gbufferProjection;
//ph_required: uniform mat4 gbufferPreviousModelView;
//ph_required: uniform mat4 gbufferPreviousProjection;

bool direct_splat_evaluate_retained_path(
    inout DirectReconnection reconnection,
    DirectSample smple,
    out float target
) {
    vec3 integrand;
    bool visible = direct_sample_get_visible_color_at_position(
        smple,
        reconnection.light_rt_pos,
        direct_reconnection_rt_pos(reconnection),
        direct_reconnection_geo_normal(reconnection),
        direct_reconnection_tex_normal(reconnection),
        direct_reconnection_is_light_transmissive(reconnection),
        integrand
    );
    if (!visible ||
            !direct_reconnection_vector_is_finite(integrand)) {
        reconnection.integrand = vec3(0.0f);
        reconnection.secondary_path_jacobian = 0.0f;
        target = 0.0f;
        return false;
    }
    reconnection.integrand = max(integrand, vec3(0.0f));
    target = direct_sample_weight(reconnection.integrand);
    if (!(target > 0.0f) ||
            !direct_reservoir_is_valid_measure(target)) {
        reconnection.integrand = vec3(0.0f);
        reconnection.secondary_path_jacobian = 0.0f;
        target = 0.0f;
        return false;
    }

#if defined PH_RESTIR_SOFT_SHADOWS
    Light light = direct_sample_get_light(smple);
    if (!light_is_valid(light) ||
            !direct_reconnection_vector_is_finite(
                reconnection.light_rt_pos
            )) {
        reconnection.secondary_path_jacobian = 0.0f;
        return false;
    }
    // UV maps uniformly to a fixed sphere in area measure. Converting the
    // uniform area density to the retained light vertex therefore produces a
    // source-independent suffix measure; its ratio is exactly one, while the
    // explicit density documents the measure used by the shift.
    const float sphere_area = 4.0f * 3.14159265f *
            ph_light_jitter_radius * ph_light_jitter_radius;
    reconnection.secondary_path_jacobian = 1.0f / sphere_area;
#else
    reconnection.secondary_path_jacobian = 1.0f;
#endif

    return direct_reservoir_is_valid_measure(
                reconnection.secondary_path_jacobian
            ) &&
            reconnection.secondary_path_jacobian > 0.0f;
}

bool direct_splat_initialize_path_data(
    inout DirectReconnection reconnection,
    DirectSample smple,
    out float target
) {
    Light light = direct_sample_get_light(smple);
    if (!light_is_valid(light)) {
        reconnection.integrand = vec3(0.0f);
        reconnection.light_rt_pos = vec3(0.0f);
        reconnection.secondary_path_jacobian = 0.0f;
        target = 0.0f;
        return false;
    }
    reconnection.light_rt_pos = direct_sample_get_position(
        smple,
        light
    );
    return direct_splat_evaluate_retained_path(
        reconnection,
        smple,
        target
    );
}

bool direct_splat_apply_secondary_jacobian(
    float source_secondary_jacobian,
    float target_secondary_jacobian,
    inout float jacobian
) {
    if (!(source_secondary_jacobian > 0.0f) ||
            !direct_reservoir_is_valid_measure(
                source_secondary_jacobian
            ) ||
            !direct_reservoir_is_valid_measure(
                target_secondary_jacobian
            )) return false;

    jacobian *= target_secondary_jacobian /
            source_secondary_jacobian;
    return direct_reservoir_is_valid_measure(jacobian);
}

bool direct_splat_project_primary_unchecked(
    DirectReconnection reconnection,
    bool previous_frame,
    out vec2 fractional_pixel
) {
    vec3 player_pos = reconnection.player_pos;
    if (previous_frame) {
        player_pos += cameraPosition - previousCameraPosition;
    }

    mat4 model_view = previous_frame
            ? gbufferPreviousModelView
            : gbufferModelView;
    mat4 projection = previous_frame
            ? gbufferPreviousProjection
            : gbufferProjection;
    vec4 clip_position = projection * model_view * vec4(player_pos, 1.0f);
    if (clip_position.w <= 0.0f) return false;

    fractional_pixel = (
        clip_position.xy / clip_position.w * 0.5f + 0.5f
    ) * PH_VIEW_SIZE;
    return true;
}

bool direct_splat_project_primary(
    DirectReconnection reconnection,
    bool previous_frame,
    out vec2 fractional_pixel
) {
    if (!direct_splat_project_primary_unchecked(
            reconnection,
            previous_frame,
            fractional_pixel
    )) return false;
    return all(greaterThanEqual(fractional_pixel, vec2(0.0f))) &&
            all(lessThan(fractional_pixel, PH_VIEW_SIZE));
}

bool direct_splat_camera_sees_primary(
    DirectReconnection reconnection,
    bool previous_frame
) {
    vec3 camera_rt_pos = rt_camera_position;
    if (previous_frame) {
        camera_rt_pos += previousCameraPosition - cameraPosition;
    }

    vec3 primary_rt_pos = direct_reconnection_visibility_target(reconnection);
    vec3 unused_tint;
    float unused_transmittance;
    return trace_light_vis(
        camera_rt_pos,
        primary_rt_pos - camera_rt_pos,
        primary_rt_pos,
        100,
        unused_tint,
        unused_transmittance
    );
}

float direct_splat_subpixel_jacobian(
    DirectReconnection reconnection,
    bool previous_frame
) {
    vec3 camera_player_pos = previous_frame
            ? previousCameraPosition - cameraPosition
            : vec3(0.0f);
    vec3 camera_to_primary = reconnection.player_pos - camera_player_pos;
    float distance_squared = dot(camera_to_primary, camera_to_primary);
    if (!(distance_squared > 0.0f) ||
            isnan(distance_squared) || isinf(distance_squared)) {
        return 0.0f;
    }
    vec3 ray_direction = camera_to_primary * inversesqrt(distance_squared);
    mat4 model_view_inverse = inverse(previous_frame
            ? gbufferPreviousModelView
            : gbufferModelView);
    vec3 camera_forward = normalize(
        mat3(model_view_inverse) * vec3(0.0f, 0.0f, -1.0f)
    );

    float cos_normal = dot(
        -ray_direction,
        direct_reconnection_geo_normal(reconnection)
    );
    float cos_sensor = dot(camera_forward, ray_direction);
    float sensor_factor = abs(pow(cos_sensor, 3.0f));
    if (!(sensor_factor > 0.0f) ||
            isnan(sensor_factor) || isinf(sensor_factor)) {
        return 0.0f;
    }
    float result = abs(cos_normal / distance_squared) / sensor_factor;
    return direct_reservoir_is_valid_measure(result) ? result : 0.0f;
}

bool direct_splat_shift_primary(
    DirectReconnection reconnection,
    bool source_previous_frame,
    out vec2 fractional_pixel,
    out float jacobian
) {
    bool target_previous_frame = !source_previous_frame;
    if (!direct_splat_project_primary(
            reconnection,
            target_previous_frame,
            fractional_pixel
    )) return false;
    if (!direct_splat_camera_sees_primary(
            reconnection,
            target_previous_frame
    )) return false;

    float source_jacobian = direct_splat_subpixel_jacobian(
        reconnection,
        source_previous_frame
    );
    float target_jacobian = direct_splat_subpixel_jacobian(
        reconnection,
        target_previous_frame
    );
    if (!(source_jacobian > 0.0f)) return false;

    // This is the primary hit/subpixel factor. Callers combine it with the
    // retained direct-light suffix factor for the complete path Jacobian.
    jacobian = target_jacobian / source_jacobian;
    return direct_reservoir_is_valid_measure(jacobian);
}

// Complete retained-path shift for the static, pinhole, finite block-light
// specialization used by Minecraft. A valid temporal history guarantees that
// block geometry and the light list have not changed. The mapped DirectSample
// therefore identifies the target-domain light vertex; general animated path
// suffixes would require historical light/path state that this representation
// intentionally does not claim to support.
bool direct_splat_shift_and_evaluate_retained_path(
    DirectSample target_sample,
    DirectReconnection source_reconnection,
    bool source_previous_frame,
    out DirectReconnection shifted_reconnection,
    out vec2 shifted_fractional_pixel,
    out float shifted_target,
    out float shifted_jacobian
) {
    shifted_reconnection = source_reconnection;
    shifted_fractional_pixel = vec2(0.0f);
    shifted_target = 0.0f;
    shifted_jacobian = 1.0f;

    float source_secondary_path_jacobian =
            source_reconnection.secondary_path_jacobian;
    if (!direct_splat_shift_primary(
            source_reconnection,
            source_previous_frame,
            shifted_fractional_pixel,
            shifted_jacobian
    )) return false;

    Light target_light = direct_sample_get_light(target_sample);
    if (!light_is_valid(target_light)) return false;
    shifted_reconnection.light_rt_pos = direct_sample_get_position(
        target_sample,
        target_light
    );
    if (!direct_reconnection_vector_is_finite(
            shifted_reconnection.light_rt_pos
    )) return false;

    if (!direct_splat_evaluate_retained_path(
            shifted_reconnection,
            target_sample,
            shifted_target
    )) return false;
    if (!direct_splat_apply_secondary_jacobian(
            source_secondary_path_jacobian,
            shifted_reconnection.secondary_path_jacobian,
            shifted_jacobian
    )) {
        shifted_target = 0.0f;
        shifted_jacobian = 1.0f;
        return false;
    }

    shifted_reconnection.subpixel = fract(shifted_fractional_pixel);
    return true;
}

#endif
