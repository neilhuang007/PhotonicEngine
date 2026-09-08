#ifndef PH_PALETTE_INCLUDE
#define PH_PALETTE_INCLUDE

#include "/photonics/utility/color.glsl"
#include "/photonics/utility/normal_encoding.glsl"

#ifndef PH_VOXEL_COLOR_MODIFIER_DISABLED
#include "/photonics/modifiers/voxel_color_modifier.glsl"
#endif

// VoxelData common

#define VoxelData uvec4

const uint PH_LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG = 1u << 31u;
const uint PH_VOXEL_DATA_BLOCK_ID_MASK =
        ~PH_LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG;

VoxelData voxel_data_empty() {
    return uvec4(0);
}

void voxel_data_apply_tint(inout VoxelData voxel_data, uvec4 tint) {
     voxel_data.y = ph_pack_int_color(
        ph_apply_int_tint(
            ph_unpack_int_color(voxel_data.y),
            tint
        )
    );
}

int voxel_data_block_id(VoxelData voxel_data) {
    uint block_id = voxel_data.x & PH_VOXEL_DATA_BLOCK_ID_MASK;
    return block_id == PH_VOXEL_DATA_BLOCK_ID_MASK ? -1 : int(block_id);
}

bool voxel_data_is_light_transmissive(VoxelData voxel_data) {
    return (voxel_data.x & PH_LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG) != 0u;
}

vec4 voxel_data_albedo(VoxelData voxel_data) {
    const float rcp_255 = 1.0f / 255.0f;
    vec4 albedo = vec4(ph_unpack_int_color(voxel_data.y)) * rcp_255;

#if !defined PH_VOXEL_COLOR_MODIFIER_DISABLED

#if defined VOXEL_COLOR_MODIFIER_SIMPLE
    voxel_color_modifier(albedo);
#else
    vec3 temp;
    ivec3 temp2;

    voxel_color_modifier(albedo, temp, temp2);
#endif

#endif

    return albedo;
}

float voxel_data_visibility_transmittance(VoxelData voxel_data, vec4 albedo) {
    if (voxel_data_is_light_transmissive(voxel_data)) return 1.0f;

#if defined PH_FULL_TRANSPARENCY
    return clamp(1.0f - albedo.a, 0.0f, 1.0f);
#else
    return 1.0f;
#endif
}

vec3 voxel_data_visibility_tint(VoxelData voxel_data, vec4 albedo) {
    vec4 clamped_albedo = clamp(albedo, 0.0f, 1.0f);
    if (!voxel_data_is_light_transmissive(voxel_data)) {
        return clamped_albedo.rgb;
    }

    // Alpha is surface coverage. Uncovered area transmits white, while the
    // covered fraction filters radiance by the material's linear albedo.
    return mix(vec3(1.0f), clamped_albedo.rgb, clamped_albedo.a);
}

vec4 voxel_data_normal(VoxelData voxel_data) {
    const float rcp_255 = 1.0f / 255.0f;
    return vec4(ph_unpack_int_color(voxel_data.z)) * rcp_255;
}

vec4 voxel_data_specular(VoxelData voxel_data) {
    const float rcp_255 = 1.0f / 255.0f;
    return vec4(ph_unpack_int_color(voxel_data.w)) * rcp_255;
}



layout (std430) restrict readonly buffer ph_palette_texture {
    uvec4 ph_palette_buffer[];
};

VoxelData ph_fetch_voxel_data(uint palette_entry, VoxelNormal normal) {
    return ph_palette_buffer[palette_entry + normal];
}


#endif
