#ifndef PH_REGIR_BRIDGE_INCLUDE
#define PH_REGIR_BRIDGE_INCLUDE

#include "/photonics/rendering/restir/native_light_list.glsl"
#include "/photonics/utility/color.glsl"

float regir_average_distance_to_volume(
    float distance_to_center,
    float volume_radius
) {
    const float nonlinear_factor = 1.1547f;
    float denominator = distance_to_center +
            volume_radius * nonlinear_factor;
    return distance_to_center +
            volume_radius * volume_radius * volume_radius /
                    (denominator * denominator);
}

// ReGIR construction deliberately excludes visibility and surface normals.
// This is the fitted RTXDI spherical-volume proxy evaluated with Photonics'
// physical distance attenuation.
float regir_light_target_for_volume(
    int light_index,
    vec3 volume_center,
    float volume_radius
) {
    if (light_index < 0 || light_index >= light_list_size) {
        return 0.0f;
    }

    PhRestirNativeLight light =
            ph_restir_native_light_list_get(light_index);
    float distance = regir_average_distance_to_volume(
        length(light.position - volume_center),
        volume_radius
    );
    float distance_squared = distance * distance;
    float denominator = distance_squared * light.falloff *
            light.attenuation.y + light.attenuation.x;
    if (!(denominator > 0.0f)) {
        return 0.0f;
    }

    return max(0.0f, ph_luminance(light.color) / denominator);
}

#endif
