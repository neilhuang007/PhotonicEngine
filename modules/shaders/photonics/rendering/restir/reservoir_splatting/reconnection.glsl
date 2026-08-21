#ifndef PH_RESERVOIR_SPLATTING_RECONNECTION_INCLUDE
#define PH_RESERVOIR_SPLATTING_RECONNECTION_INCLUDE

#include "/photonics/utility/normal_encoding.glsl"
#include "/photonics/rendering/frag/flags.glsl"

layout(std430) buffer ph_direct_previous_reconnections {
    uint ph_direct_previous_reconnection_words[];
};

layout(std430) buffer ph_direct_current_reconnections {
    uint ph_direct_current_reconnection_words[];
};

layout(std430) buffer ph_direct_spatial_reconnections {
    uint ph_direct_spatial_reconnection_words[];
};

const uint ph_direct_reconnection_word_stride = 16u;

struct DirectReconnection {
    vec3 player_pos;
    float rt_offset_magnitude;
    uint geo_normal;
    uint tex_normal;
    uint flags;
    vec2 subpixel;
    float secondary_path_jacobian;
    vec3 integrand;
    vec3 light_rt_pos;
};

DirectReconnection direct_reconnection_empty() {
    return DirectReconnection(
        vec3(0.0f),
        0.0f,
        0u,
        0u,
        0u,
        vec2(0.5f),
        1.0f,
        vec3(0.0f),
        vec3(0.0f)
    );
}

#if defined PH_DIRECT_RECONNECTION_FRAG_DATA
DirectReconnection direct_reconnection_from_frag(
    FragData frag,
    vec2 subpixel
) {
    return DirectReconnection(
        frag.data0.xyz,
        frag.data0.w,
        frag.data1.y,
        frag.data1.z,
        frag.data1.w,
        subpixel,
        1.0f,
        vec3(0.0f),
        vec3(0.0f)
    );
}
#endif

vec3 direct_reconnection_geo_normal(DirectReconnection reconnection) {
    return ph_unpack_normal(reconnection.geo_normal);
}

vec3 direct_reconnection_rt_pos(DirectReconnection reconnection) {
    return reconnection.player_pos + rt_camera_position +
            direct_reconnection_geo_normal(reconnection) *
            reconnection.rt_offset_magnitude;
}

vec3 direct_reconnection_primary_rt_pos(DirectReconnection reconnection) {
    return reconnection.player_pos + rt_camera_position;
}

vec3 direct_reconnection_tex_normal(DirectReconnection reconnection) {
    return ph_unpack_normal(reconnection.tex_normal);
}

bool direct_reconnection_is_in_world(DirectReconnection reconnection) {
    return (reconnection.flags & frag_is_in_world_bit) != 0u;
}

bool direct_reconnection_is_hand(DirectReconnection reconnection) {
    return (reconnection.flags & frag_is_hand_bit) != 0u;
}

bool direct_reconnection_is_light_transmissive(
    DirectReconnection reconnection
) {
    return (reconnection.flags & frag_is_light_transmissive_bit) != 0u;
}

bool direct_reconnection_vector_is_finite(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool direct_reconnection_is_finite(DirectReconnection reconnection) {
    return direct_reconnection_vector_is_finite(reconnection.player_pos) &&
            !isnan(reconnection.rt_offset_magnitude) &&
            !isinf(reconnection.rt_offset_magnitude) &&
            all(greaterThanEqual(reconnection.subpixel, vec2(0.0f))) &&
            all(lessThan(reconnection.subpixel, vec2(1.0f))) &&
            !any(isnan(reconnection.subpixel)) &&
            !any(isinf(reconnection.subpixel)) &&
            reconnection.secondary_path_jacobian > 0.0f &&
            !isnan(reconnection.secondary_path_jacobian) &&
            !isinf(reconnection.secondary_path_jacobian) &&
            direct_reconnection_vector_is_finite(reconnection.integrand) &&
            all(greaterThanEqual(reconnection.integrand, vec3(0.0f))) &&
            direct_reconnection_vector_is_finite(reconnection.light_rt_pos);
}

DirectReconnection direct_reconnection_load_previous(uint pixel_index) {
    uint offset = pixel_index * ph_direct_reconnection_word_stride;
    DirectReconnection reconnection = DirectReconnection(
        uintBitsToFloat(uvec3(
            ph_direct_previous_reconnection_words[offset],
            ph_direct_previous_reconnection_words[offset + 1u],
            ph_direct_previous_reconnection_words[offset + 2u]
        )),
        uintBitsToFloat(
            ph_direct_previous_reconnection_words[offset + 3u]
        ),
        ph_direct_previous_reconnection_words[offset + 4u],
        ph_direct_previous_reconnection_words[offset + 5u],
        ph_direct_previous_reconnection_words[offset + 6u],
        uintBitsToFloat(uvec2(
            ph_direct_previous_reconnection_words[offset + 7u],
            ph_direct_previous_reconnection_words[offset + 8u]
        )),
        uintBitsToFloat(
            ph_direct_previous_reconnection_words[offset + 9u]
        ),
        uintBitsToFloat(uvec3(
            ph_direct_previous_reconnection_words[offset + 10u],
            ph_direct_previous_reconnection_words[offset + 11u],
            ph_direct_previous_reconnection_words[offset + 12u]
        )),
        uintBitsToFloat(uvec3(
            ph_direct_previous_reconnection_words[offset + 13u],
            ph_direct_previous_reconnection_words[offset + 14u],
            ph_direct_previous_reconnection_words[offset + 15u]
        ))
    );
    reconnection.player_pos -= cameraPosition - previousCameraPosition;
    return reconnection;
}

DirectReconnection direct_reconnection_load_current(uint pixel_index) {
    uint offset = pixel_index * ph_direct_reconnection_word_stride;
    return DirectReconnection(
        uintBitsToFloat(uvec3(
            ph_direct_current_reconnection_words[offset],
            ph_direct_current_reconnection_words[offset + 1u],
            ph_direct_current_reconnection_words[offset + 2u]
        )),
        uintBitsToFloat(
            ph_direct_current_reconnection_words[offset + 3u]
        ),
        ph_direct_current_reconnection_words[offset + 4u],
        ph_direct_current_reconnection_words[offset + 5u],
        ph_direct_current_reconnection_words[offset + 6u],
        uintBitsToFloat(uvec2(
            ph_direct_current_reconnection_words[offset + 7u],
            ph_direct_current_reconnection_words[offset + 8u]
        )),
        uintBitsToFloat(
            ph_direct_current_reconnection_words[offset + 9u]
        ),
        uintBitsToFloat(uvec3(
            ph_direct_current_reconnection_words[offset + 10u],
            ph_direct_current_reconnection_words[offset + 11u],
            ph_direct_current_reconnection_words[offset + 12u]
        )),
        uintBitsToFloat(uvec3(
            ph_direct_current_reconnection_words[offset + 13u],
            ph_direct_current_reconnection_words[offset + 14u],
            ph_direct_current_reconnection_words[offset + 15u]
        ))
    );
}

void direct_reconnection_store_current(
    uint pixel_index,
    DirectReconnection reconnection
) {
    uint offset = pixel_index * ph_direct_reconnection_word_stride;
    uvec3 player_pos = floatBitsToUint(reconnection.player_pos);
    ph_direct_current_reconnection_words[offset] = player_pos.x;
    ph_direct_current_reconnection_words[offset + 1u] = player_pos.y;
    ph_direct_current_reconnection_words[offset + 2u] = player_pos.z;
    ph_direct_current_reconnection_words[offset + 3u] =
            floatBitsToUint(reconnection.rt_offset_magnitude);
    ph_direct_current_reconnection_words[offset + 4u] =
            reconnection.geo_normal;
    ph_direct_current_reconnection_words[offset + 5u] =
            reconnection.tex_normal;
    ph_direct_current_reconnection_words[offset + 6u] = reconnection.flags;
    ph_direct_current_reconnection_words[offset + 7u] =
            floatBitsToUint(reconnection.subpixel.x);
    ph_direct_current_reconnection_words[offset + 8u] =
            floatBitsToUint(reconnection.subpixel.y);
    ph_direct_current_reconnection_words[offset + 9u] =
            floatBitsToUint(reconnection.secondary_path_jacobian);
    uvec3 integrand = floatBitsToUint(reconnection.integrand);
    ph_direct_current_reconnection_words[offset + 10u] = integrand.x;
    ph_direct_current_reconnection_words[offset + 11u] = integrand.y;
    ph_direct_current_reconnection_words[offset + 12u] = integrand.z;
    uvec3 light_rt_pos = floatBitsToUint(reconnection.light_rt_pos);
    ph_direct_current_reconnection_words[offset + 13u] = light_rt_pos.x;
    ph_direct_current_reconnection_words[offset + 14u] = light_rt_pos.y;
    ph_direct_current_reconnection_words[offset + 15u] = light_rt_pos.z;
}

void direct_reconnection_store_spatial(
    uint pixel_index,
    DirectReconnection reconnection
) {
    uint offset = pixel_index * ph_direct_reconnection_word_stride;
    uvec3 player_pos = floatBitsToUint(reconnection.player_pos);
    ph_direct_spatial_reconnection_words[offset] = player_pos.x;
    ph_direct_spatial_reconnection_words[offset + 1u] = player_pos.y;
    ph_direct_spatial_reconnection_words[offset + 2u] = player_pos.z;
    ph_direct_spatial_reconnection_words[offset + 3u] =
            floatBitsToUint(reconnection.rt_offset_magnitude);
    ph_direct_spatial_reconnection_words[offset + 4u] =
            reconnection.geo_normal;
    ph_direct_spatial_reconnection_words[offset + 5u] =
            reconnection.tex_normal;
    ph_direct_spatial_reconnection_words[offset + 6u] = reconnection.flags;
    ph_direct_spatial_reconnection_words[offset + 7u] =
            floatBitsToUint(reconnection.subpixel.x);
    ph_direct_spatial_reconnection_words[offset + 8u] =
            floatBitsToUint(reconnection.subpixel.y);
    ph_direct_spatial_reconnection_words[offset + 9u] =
            floatBitsToUint(reconnection.secondary_path_jacobian);
    uvec3 integrand = floatBitsToUint(reconnection.integrand);
    ph_direct_spatial_reconnection_words[offset + 10u] = integrand.x;
    ph_direct_spatial_reconnection_words[offset + 11u] = integrand.y;
    ph_direct_spatial_reconnection_words[offset + 12u] = integrand.z;
    uvec3 light_rt_pos = floatBitsToUint(reconnection.light_rt_pos);
    ph_direct_spatial_reconnection_words[offset + 13u] = light_rt_pos.x;
    ph_direct_spatial_reconnection_words[offset + 14u] = light_rt_pos.y;
    ph_direct_spatial_reconnection_words[offset + 15u] = light_rt_pos.z;
}

#endif
