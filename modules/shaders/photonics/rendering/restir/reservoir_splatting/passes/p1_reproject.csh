#version 430

layout(local_size_x = 16, local_size_y = 16) in;

#define PH_VIEW_SIZE (vec2(viewWidth, viewHeight) * PH_RENDER_SCALE)

//ph_required: uniform float viewWidth;
//ph_required: uniform float viewHeight;
//ph_required: uniform int frameCounter;
//ph_required: uniform int ph_reservoir_splatting_history_valid;
//ph_required: uniform vec3 cameraPosition;
//ph_required: uniform vec3 previousCameraPosition;
//ph_required: uniform mat4 gbufferModelView;
//ph_required: uniform mat4 gbufferProjection;
//ph_required: uniform usampler2D prev_restir_direct_reservoirs0;
//ph_required: uniform sampler2D prev_restir_direct_reservoirs1;

#define PH_VOXEL_COLOR_MODIFIER_DISABLED
#define PH_LIGHT_MODIFIER_DISABLED
#define PH_ATTENUATION_MODIFIER_DISABLED
#include "/photonics/tracing.glsl"
#include "/photonics/rendering/restir/direct/reservoir_encoding.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"

bool direct_splat_project_reconnection_to_current_frame(
    DirectReconnection reconnection,
    out vec2 fractional_pixel
) {
    vec4 view_position = gbufferModelView * vec4(
        reconnection.player_pos,
        1.0f
    );
    vec4 clip_position = gbufferProjection * view_position;
    if (clip_position.w <= 0.0f) return false;

    fractional_pixel = (
        clip_position.xy / clip_position.w * 0.5f + 0.5f
    ) * PH_VIEW_SIZE;
    return all(greaterThanEqual(fractional_pixel, vec2(0.0f))) &&
            all(lessThan(fractional_pixel, PH_VIEW_SIZE));
}

bool direct_splat_current_camera_sees_primary_hit(
    DirectReconnection reconnection
) {
    vec3 primary_rt_pos = direct_reconnection_primary_rt_pos(reconnection);
    float primary_distance = distance(rt_camera_position, primary_rt_pos);
    return trace_segment_visibility(
        rt_camera_position,
        primary_rt_pos,
        0.001f * primary_distance,
        100
    );
}

void main() {
    if (ph_reservoir_splatting_history_valid == 0) return;

    ivec2 source_pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!ph_splat_pixel_in_bounds(source_pixel)) return;

    uvec3 previous_sample_data = texelFetch(
        prev_restir_direct_reservoirs0,
        source_pixel,
        0
    ).rgb;
    vec3 previous_reservoir_data = texelFetch(
        prev_restir_direct_reservoirs1,
        source_pixel,
        0
    ).rgb;
    if (!direct_reservoir_encoding_is_reusable(
            previous_sample_data,
            previous_reservoir_data
    )) return;

    uint source_index = ph_splat_pixel_index(source_pixel);
    DirectReconnection reconnection = direct_reconnection_load_previous(
        source_index
    );
    if (!direct_reconnection_is_finite(reconnection) ||
            !direct_reconnection_is_in_world(reconnection) ||
            direct_reconnection_is_hand(reconnection)) return;

    vec2 fractional_pixel;
    if (!direct_splat_project_reconnection_to_current_frame(
            reconnection,
            fractional_pixel
    )) return;
    if (!direct_splat_current_camera_sees_primary_hit(reconnection)) return;

    uint target_cell = ph_splat_pixel_index(ivec2(floor(fractional_pixel)));
    uint append_index = atomicAdd(
        ph_reservoir_splatting_counter_words[ph_splat_data_count_word],
        1u
    );
    uint local_cell_index = atomicAdd(
        ph_reservoir_splatting_counter_words[
            ph_splat_cell_counter_word_offset + target_cell
        ],
        1u
    );

    uint metadata_offset = append_index * 2u;
    ph_reservoir_splatting_append_words[metadata_offset] = target_cell;
    ph_reservoir_splatting_append_words[
        metadata_offset + 1u
    ] = local_cell_index;
    ph_reservoir_splatting_append_words[
        ph_splat_pixel_count() * 2u + append_index
    ] = source_index;
}
