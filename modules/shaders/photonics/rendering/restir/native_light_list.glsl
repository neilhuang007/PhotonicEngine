#ifndef PH_RESTIR_NATIVE_LIGHT_LIST_INCLUDE
#define PH_RESTIR_NATIVE_LIGHT_LIST_INCLUDE

#include "/photonics/uniforms.glsl"

// Compute-only ReSTIR light data. Shader-pack light modifiers are a surface
// shading concern and may depend on fragment-only pack settings, so compute
// passes read the Photonics buffer representation directly.
struct PhRestirNativeLight {
    vec3 position;
    vec3 color;
    vec2 attenuation;
    float falloff;
};

layout (std140) restrict readonly buffer ph_light_list {
// vec 1: position (xyz) + block_id (w)
// vec 2: color (xyz) + intensity (w)
// vec 3: attenuation (xy) + falloff (z) + block_radius (w)
    vec4 ph_lights_array[];
};

PhRestirNativeLight ph_restir_native_light_list_get(int index) {
    int base_index = index * 3;
    vec4 position_full = ph_lights_array[base_index + 0];
    vec4 color_full = ph_lights_array[base_index + 1];
    vec4 attenuation_full = ph_lights_array[base_index + 2];

    return PhRestirNativeLight(
        position_full.xyz - light_list_offset,
        color_full.xyz,
        attenuation_full.xy,
        attenuation_full.z
    );
}

#endif
