#ifndef PH_RESERVOIR_SPLATTING_RECONNECTION_INCLUDE
#define PH_RESERVOIR_SPLATTING_RECONNECTION_INCLUDE

#include "/photonics/utility/normal_encoding.glsl"

layout(std430) buffer ph_direct_previous_reconnections {
    uint ph_direct_previous_reconnection_words[];
};

layout(std430) buffer ph_direct_current_reconnections {
    uint ph_direct_current_reconnection_words[];
};

layout(std430) buffer ph_direct_spatial_reconnections {
    uint ph_direct_spatial_reconnection_words[];
};

const uint ph_direct_reconnection_word_stride = 8u;

struct DirectReconnection {
    vec3 player_pos;
    float rt_offset_magnitude;
    uint rt_offset_direction;
    uint geo_normal;
    uint tex_normal;
    uint flags;
};

#if defined PH_DIRECT_RECONNECTION_FRAG_DATA
DirectReconnection direct_reconnection_from_frag(FragData frag) {
    return DirectReconnection(
        frag.data0.xyz,
        frag.data0.w,
        frag.data1.x,
        frag.data1.y,
        frag.data1.z,
        frag.data1.w
    );
}
#endif

vec3 direct_reconnection_rt_pos(DirectReconnection reconnection) {
    return reconnection.player_pos + rt_camera_position +
            ph_unpack_normal(reconnection.rt_offset_direction) *
            reconnection.rt_offset_magnitude;
}

vec3 direct_reconnection_visibility_target(DirectReconnection reconnection) {
    return reconnection.player_pos + rt_camera_position -
            direct_reconnection_geo_normal(reconnection) * 0.01f;
}

vec3 direct_reconnection_geo_normal(DirectReconnection reconnection) {
    return ph_unpack_normal(reconnection.geo_normal);
}

vec3 direct_reconnection_tex_normal(DirectReconnection reconnection) {
    return ph_unpack_normal(reconnection.tex_normal);
}

bool direct_reconnection_is_in_world(DirectReconnection reconnection) {
    return (reconnection.flags & (1u << 0u)) != 0u;
}

bool direct_reconnection_is_hand(DirectReconnection reconnection) {
    return (reconnection.flags & (1u << 2u)) != 0u;
}

DirectReconnection direct_reconnection_load_previous(uint pixel_index) {
    uint offset = pixel_index * ph_direct_reconnection_word_stride;
    DirectReconnection reconnection = DirectReconnection(
        uintBitsToFloat(uvec3(
            ph_direct_previous_reconnection_words[offset],
            ph_direct_previous_reconnection_words[offset + 1u],
            ph_direct_previous_reconnection_words[offset + 2u]
        )),
        uintBitsToFloat(ph_direct_previous_reconnection_words[offset + 3u]),
        ph_direct_previous_reconnection_words[offset + 4u],
        ph_direct_previous_reconnection_words[offset + 5u],
        ph_direct_previous_reconnection_words[offset + 6u],
        ph_direct_previous_reconnection_words[offset + 7u]
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
        uintBitsToFloat(ph_direct_current_reconnection_words[offset + 3u]),
        ph_direct_current_reconnection_words[offset + 4u],
        ph_direct_current_reconnection_words[offset + 5u],
        ph_direct_current_reconnection_words[offset + 6u],
        ph_direct_current_reconnection_words[offset + 7u]
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
            reconnection.rt_offset_direction;
    ph_direct_current_reconnection_words[offset + 5u] = reconnection.geo_normal;
    ph_direct_current_reconnection_words[offset + 6u] = reconnection.tex_normal;
    ph_direct_current_reconnection_words[offset + 7u] = reconnection.flags;
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
            reconnection.rt_offset_direction;
    ph_direct_spatial_reconnection_words[offset + 5u] = reconnection.geo_normal;
    ph_direct_spatial_reconnection_words[offset + 6u] = reconnection.tex_normal;
    ph_direct_spatial_reconnection_words[offset + 7u] = reconnection.flags;
}

#endif
