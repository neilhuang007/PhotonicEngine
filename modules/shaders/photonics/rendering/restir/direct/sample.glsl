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
    Light light,
    vec3 sample_pos
) {
#ifdef PH_RESTIR_SOFT_SHADOWS
    vec3 light_position = floor(light.position) + 0.5f;
    vec3 sample_direction = light_position - sample_pos;
    float sample_distance_squared =
            dot(sample_direction, sample_direction);
    if (!(sample_distance_squared > 1e-12f)) {
        return light_position;
    }

    vec3 sample_normal = sample_direction *
            inversesqrt(sample_distance_squared);
    vec3 basis_axis = abs(sample_normal.y) < 0.999f
            ? vec3(0.0f, 1.0f, 0.0f)
            : vec3(1.0f, 0.0f, 0.0f);
    vec3 sample_tangent = normalize(cross(
        basis_axis,
        sample_normal
    ));
    vec3 sample_bitangent = cross(
        sample_normal,
        sample_tangent
    );

    float point_radius = ph_light_jitter_radius * sqrt(smple.uv.x);
    float point_angle = smple.uv.y * 2.0f * 3.14159265f;
    vec2 disk_point = vec2(
        point_radius * cos(point_angle),
        point_radius * sin(point_angle)
    );

    return light_position +
            disk_point.x * sample_tangent +
            disk_point.y * sample_bitangent;
#else
    return light.position;
#endif
}

vec3 direct_sample_get_color(
    DirectSample smple,
    Light light,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (!light_is_valid(light))
        return vec3(0.0f);

    vec3 light_position = direct_sample_get_position(
        smple,
        light,
        sample_pos
    );
    return light_sample_at(
        light,
        sample_pos,
        light_position,
        geo_normal,
        tex_normal
    );
}

float direct_sample_get_weight(
    DirectSample smple,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(smple))
        return 0.0f;

    Light light = direct_sample_get_light(smple);
    vec3 color = direct_sample_get_color(smple, light, sample_pos, geo_normal, tex_normal);

    return direct_sample_weight(color);
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
