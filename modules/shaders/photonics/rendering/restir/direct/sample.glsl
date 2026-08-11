#include "/photonics/light_list.glsl"
#include "/photonics/tracing.glsl"

#include "/photonics/utility/random.glsl"

struct DirectSample {
    int light_index;
    vec2 uv;
};

DirectSample direct_sample_empty() {
    return DirectSample(-1, vec2(0.0f));
}

bool direct_sample_is_empty(DirectSample smple) {
    return smple.light_index == -1;
}

float direct_sample_weight(vec3 color) {
    return max(0.0f, ph_luminance(color));
}

Light direct_sample_get_light(DirectSample smple) {
    if (direct_sample_is_empty(smple))
        return new_invalid_light();

    return light_list_get(int(smple.light_index));
}

vec3 direct_sample_get_position(
    DirectSample smple,
    Light light
) {
#ifdef PH_RESTIR_SOFT_SHADOWS
    vec3 light_position = floor(light.position) + 0.5f;
    float sample_z = 1.0f - 2.0f * smple.uv.x;
    float sample_radius = sqrt(max(0.0f, 1.0f - sample_z * sample_z));
    float point_angle = smple.uv.y * 2.0f * 3.14159265f;
    vec3 sphere_point = vec3(
        sample_radius * cos(point_angle),
        sample_z,
        sample_radius * sin(point_angle)
    );
    return light_position + ph_light_jitter_radius * sphere_point;
#else
    return light.position;
#endif
}

void direct_sample_orient_shading_normals(
    vec3 light_position,
    vec3 sample_pos,
    bool light_transmissive_surface,
    inout vec3 geo_normal,
    inout vec3 tex_normal
) {
    vec3 to_light = light_position - sample_pos;
    if (light_transmissive_surface &&
            dot(geo_normal, to_light) < 0.0f) {
        geo_normal = -geo_normal;
        tex_normal = -tex_normal;
    }
}

bool direct_sample_get_visible_color_at_position(
    DirectSample smple,
    vec3 light_position,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool light_transmissive_surface,
    out vec3 color
);

bool direct_sample_get_visible_color(
    DirectSample smple,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool light_transmissive_surface,
    out vec3 color
) {
    color = vec3(0.0f);
    if (direct_sample_is_empty(smple)) return false;

    Light light = direct_sample_get_light(smple);
    if (!light_is_valid(light)) return false;

    vec3 light_position = direct_sample_get_position(
        smple,
        light
    );
    return direct_sample_get_visible_color_at_position(
        smple,
        light_position,
        sample_pos,
        geo_normal,
        tex_normal,
        light_transmissive_surface,
        color
    );
}

bool direct_sample_get_visible_color_at_position(
    DirectSample smple,
    vec3 light_position,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal,
    bool light_transmissive_surface,
    out vec3 color
) {
    color = vec3(0.0f);
    if (direct_sample_is_empty(smple)) return false;

    Light light = direct_sample_get_light(smple);
    if (!light_is_valid(light)) return false;

    vec3 tint_color;
    float transmittance;
    if (!trace_light_vis(
            sample_pos,
            light_position - sample_pos,
            light_position,
            40,
            tint_color,
            transmittance
    )) return false;

    direct_sample_orient_shading_normals(
        light_position,
        sample_pos,
        light_transmissive_surface,
        geo_normal,
        tex_normal
    );
    color = light_sample_at(
        light,
        sample_pos,
        light_position,
        geo_normal,
        tex_normal
    ) * tint_color * transmittance;
    return true;
}

bool direct_sample_reproject(inout DirectSample smple) {
    if (smple.light_index < 0) return false;

    smple.light_index = light_list_map_index(smple.light_index);
    if (smple.light_index < 0 || smple.light_index >= light_list_size) {
        smple.light_index = -1;
        return false;
    }

    return true;
}
