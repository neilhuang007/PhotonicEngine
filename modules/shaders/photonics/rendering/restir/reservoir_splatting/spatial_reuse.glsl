#ifndef PH_RESERVOIR_SPLATTING_SPATIAL_REUSE_INCLUDE
#define PH_RESERVOIR_SPLATTING_SPATIAL_REUSE_INCLUDE

#define PH_DIRECT_RECONNECTION_FRAG_DATA
#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/shift.glsl"

const uint direct_spatial_neighbor_count = 8192u;

layout(std430) readonly buffer ph_direct_spatial_neighbor_offsets {
    uint ph_direct_spatial_neighbor_offset_data[];
};

vec2 direct_spatial_neighbor_offset(uint index) {
    uint packed_offset = ph_direct_spatial_neighbor_offset_data[
        index % direct_spatial_neighbor_count
    ];
    int x = int(packed_offset & 255u);
    int y = int((packed_offset >> 8u) & 255u);
    if (x >= 128) x -= 256;
    if (y >= 128) y -= 256;
    return vec2(float(x), float(y)) / 127.0f;
}

float direct_spatial_pairwise_mis(float numerator, float competing) {
    numerator = direct_reservoir_sanitize_weight(numerator);
    competing = direct_reservoir_sanitize_weight(competing);
    float denominator = numerator + competing;
    return denominator > 0.0f ? numerator / denominator : 0.0f;
}

vec3 direct_spatial_camera_ray_direction(vec2 fractional_pixel) {
    vec2 ndc = fractional_pixel / PH_VIEW_SIZE * 2.0f - 1.0f;
    vec4 view_position = inverse(gbufferProjection) *
            vec4(ndc, 1.0f, 1.0f);
    vec3 view_direction = normalize(
        view_position.xyz / view_position.w
    );
    return normalize(
        mat3(inverse(gbufferModelView)) * view_direction
    );
}

bool direct_spatial_trace_primary(
    vec2 fractional_pixel,
    out DirectReconnection reconnection
) {
    ivec2 target_pixel = ivec2(floor(fractional_pixel));
    if (!ph_splat_pixel_in_bounds(target_pixel)) return false;

    FragData target_frag;
    frag_data_load(target_frag, target_pixel);
    if (frag_data_is_hand(target_frag)) return false;

    RayIterator primary_ray;
    ray_iter_begin(
        primary_ray,
        rt_camera_position,
        direct_spatial_camera_ray_direction(fractional_pixel)
    );
    RayResult primary_hit = ray_iter_next(primary_ray);
    if (!ray_result_is_hit(primary_hit)) return false;

    vec3 hit_position = ray_result_position(primary_hit);
    vec3 geometry_normal = ray_result_normal(primary_hit);
    vec3 texture_normal = geometry_normal;
    VoxelData primary_voxel_data = ray_result_voxel_data(primary_hit);
    uint primary_flags = frag_is_in_world_bit;
    if (voxel_data_is_light_transmissive(primary_voxel_data)) {
        primary_flags |= frag_is_light_transmissive_bit;
    }

    // The raster normal is exact for the same resolved primary surface and
    // preserves shader-pack normal mapping. At a disocclusion edge the traced
    // surface is authoritative and falls back to its geometric normal.
    if (frag_data_is_in_world(target_frag)) {
        vec3 raster_position = frag_data_rt_pos(target_frag);
        vec3 raster_geometry_normal = frag_data_geo_normal(target_frag);
        vec3 position_delta = raster_position - hit_position;
        if (dot(position_delta, position_delta) < 0.25f &&
                dot(raster_geometry_normal, geometry_normal) > 0.99f) {
            texture_normal = frag_data_tex_normal(target_frag);
        }
    }

    reconnection = DirectReconnection(
        hit_position - rt_camera_position,
        0.01f,
        ph_pack_normal(geometry_normal),
        ph_pack_normal(texture_normal),
        primary_flags,
        fract(fractional_pixel),
        1.0f,
        vec3(0.0f),
        vec3(0.0f)
    );
    return true;
}

bool direct_spatial_shift(
    DirectSample smple,
    DirectReconnection source_reconnection,
    vec2 target_fractional_pixel,
    out DirectReconnection shifted_reconnection,
    out float shifted_target,
    out float shifted_jacobian
) {
    shifted_reconnection = direct_reconnection_empty();
    shifted_target = 0.0f;
    shifted_jacobian = 1.0f;
    if (direct_sample_is_empty(smple) ||
            !direct_reconnection_is_finite(source_reconnection) ||
            !direct_reconnection_is_in_world(source_reconnection) ||
            direct_reconnection_is_hand(source_reconnection) ||
            !direct_spatial_trace_primary(
                target_fractional_pixel,
                shifted_reconnection
            )) return false;

    shifted_reconnection.light_rt_pos = source_reconnection.light_rt_pos;
    if (!direct_splat_evaluate_retained_path(
            shifted_reconnection,
            smple,
            shifted_target
    )) return false;
    return direct_splat_apply_secondary_jacobian(
        source_reconnection.secondary_path_jacobian,
        shifted_reconnection.secondary_path_jacobian,
        shifted_jacobian
    );
}

void direct_splat_spatial_reuse(
    out DirectReservoir result,
    out DirectReconnection result_reconnection
) {
    uint pixel_index = ph_splat_pixel_index(frag_tex_coord);
    DirectReservoir central_reservoir;
    direct_reservoir_load_previous(
        central_reservoir,
        frag_tex_coord,
        false
    );
    DirectReconnection central_reconnection =
            direct_reconnection_load_current(pixel_index);

    result = direct_reservoir_empty();
    result_reconnection = central_reconnection;

    float central_mis = 1.0f;
    float central_weight = direct_reservoir_sanitize_weight(
        central_reservoir.target_pdf /
                float(PH_RESTIR_SPATIAL_REUSE_SAMPLES) *
                central_reservoir.total_samples
    );

    int valid_neighbors = 0;
    uint start_index = uint(
        ph_rand_next_float(frag_rnd_state) *
                float(direct_spatial_neighbor_count)
    );
    for (int i = 0; i < PH_RESTIR_SPATIAL_REUSE_SAMPLES; i++) {
        uint offset_index = (
            start_index + uint(i)
        ) % direct_spatial_neighbor_count;
        vec2 neighbor_offset =
                direct_spatial_neighbor_offset(offset_index);
        ivec2 neighbor_pixel = ivec2(round(
            vec2(frag_tex_coord) +
                    PH_RESTIR_SPATIAL_REUSE_RADIUS *
                    PH_RENDER_SCALE * neighbor_offset
        ));
        if (!ph_splat_pixel_in_bounds(neighbor_pixel)) continue;
        valid_neighbors++;

        uint neighbor_index = ph_splat_pixel_index(neighbor_pixel);
        DirectReservoir neighbor_reservoir;
        direct_reservoir_load_previous(
            neighbor_reservoir,
            neighbor_pixel,
            false
        );
        DirectReconnection neighbor_reconnection =
                direct_reconnection_load_current(neighbor_index);

        DirectReconnection shifted_central_reconnection;
        float shifted_central_target;
        float shifted_central_jacobian;
        float central_competing_weight = 0.0f;
        if (direct_spatial_shift(
                central_reservoir.smple,
                central_reconnection,
                vec2(neighbor_pixel) + central_reconnection.subpixel,
                shifted_central_reconnection,
                shifted_central_target,
                shifted_central_jacobian
        )) {
            central_competing_weight = direct_reservoir_sanitize_weight(
                shifted_central_target * shifted_central_jacobian *
                        neighbor_reservoir.total_samples
            );
        }
        central_mis += 1.0f - direct_spatial_pairwise_mis(
            central_competing_weight,
            central_weight
        );

        DirectReconnection shifted_neighbor_reconnection;
        float shifted_neighbor_target;
        float shifted_neighbor_jacobian;
        float neighbor_weight = 0.0f;
        if (direct_spatial_shift(
                neighbor_reservoir.smple,
                neighbor_reconnection,
                vec2(frag_tex_coord) + neighbor_reconnection.subpixel,
                shifted_neighbor_reconnection,
                shifted_neighbor_target,
                shifted_neighbor_jacobian
        )) {
            neighbor_weight = direct_reservoir_sanitize_weight(
                shifted_neighbor_target * shifted_neighbor_jacobian *
                        central_reservoir.total_samples
            ) / float(PH_RESTIR_SPATIAL_REUSE_SAMPLES);
        }
        float neighbor_original_weight =
                direct_reservoir_sanitize_weight(
                    neighbor_reservoir.target_pdf *
                            neighbor_reservoir.total_samples
                );
        float neighbor_mis = direct_spatial_pairwise_mis(
            neighbor_original_weight,
            neighbor_weight
        );

        bool neighbor_selected = direct_reservoir_add_sample(
            result,
            neighbor_reservoir,
            neighbor_reservoir.smple,
            shifted_neighbor_target,
            neighbor_mis,
            shifted_neighbor_jacobian,
            ph_rand_next_float(frag_rnd_state)
        );
        if (neighbor_selected) {
            result_reconnection = shifted_neighbor_reconnection;
        }
    }

    bool central_selected = direct_reservoir_add_sample(
        result,
        central_reservoir,
        central_reservoir.smple,
        central_reservoir.target_pdf,
        direct_reservoir_sanitize_weight(central_mis),
        1.0f,
        ph_rand_next_float(frag_rnd_state)
    );
    if (central_selected) {
        result_reconnection = central_reconnection;
    }
    result.weight_sum /= float(valid_neighbors + 1);
}

#endif
