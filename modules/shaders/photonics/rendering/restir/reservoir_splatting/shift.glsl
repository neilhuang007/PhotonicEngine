#ifndef PH_RESERVOIR_SPLATTING_SHIFT_INCLUDE
#define PH_RESERVOIR_SPLATTING_SHIFT_INCLUDE

//ph_required: uniform mat4 gbufferModelView;
//ph_required: uniform mat4 gbufferProjection;
//ph_required: uniform mat4 gbufferPreviousModelView;
//ph_required: uniform mat4 gbufferPreviousProjection;

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
    return abs(cos_normal / distance_squared) /
            abs(pow(cos_sensor, 3.0f));
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

    // The finite block-light sample is retained in its discrete-light/disk-UV
    // domain, so the direct path suffix mapping is the identity.
    jacobian = target_jacobian / source_jacobian;
    return true;
}

#endif
