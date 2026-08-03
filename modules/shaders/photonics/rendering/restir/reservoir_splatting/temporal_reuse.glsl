#ifndef PH_RESERVOIR_SPLATTING_TEMPORAL_REUSE_INCLUDE
#define PH_RESERVOIR_SPLATTING_TEMPORAL_REUSE_INCLUDE

#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"
#define PH_DIRECT_RECONNECTION_FRAG_DATA
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/shift.glsl"

float direct_splat_evaluate_target(
    DirectSample smple,
    DirectReconnection reconnection
) {
    vec3 integrand;
    direct_sample_get_visible_color(
        smple,
        direct_reconnection_rt_pos(reconnection),
        direct_reconnection_geo_normal(reconnection),
        direct_reconnection_tex_normal(reconnection),
        integrand
    );
    return direct_sample_weight(integrand);
}

float direct_splat_previous_confidence(ivec2 pixel) {
    DirectReservoir reservoir;
    direct_reservoir_load_previous(reservoir, pixel, false);
    return reservoir.total_samples;
}

float direct_splat_update_confidence(
    DirectReconnection current_reconnection,
    float current_confidence
) {
    vec2 previous_fractional_pixel;
    if (!direct_splat_project_primary_unchecked(
            current_reconnection,
            true,
            previous_fractional_pixel
    )) return min(max_direct_temporal_samples, current_confidence);

    vec2 previous_texel = previous_fractional_pixel - 0.5f;
    ivec2 top_left = ivec2(floor(previous_texel));
    vec2 fractional_coord = clamp(fract(previous_texel), 0.0f, 1.0f);
    float confidence = current_confidence;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 neighbor = top_left + ivec2(x, y);
            if (!ph_splat_pixel_in_bounds(neighbor)) continue;

            float bilinear_weight = mix(
                1.0f - float(x),
                float(x),
                fractional_coord.x
            ) * mix(
                1.0f - float(y),
                float(y),
                fractional_coord.y
            );
            confidence += bilinear_weight *
                    direct_splat_previous_confidence(neighbor);
        }
    }
    return min(max_direct_temporal_samples, confidence);
}

void direct_splat_temporal_reuse(
    out DirectReservoir result,
    out DirectReconnection selected_reconnection
) {
    uint destination_index = ph_splat_pixel_index(frag_tex_coord);
    DirectReservoir current_reservoir;
    direct_reservoir_load_candidate(current_reservoir, frag_tex_coord);
    DirectReconnection current_reconnection = direct_reconnection_from_frag(
        _frag_data
    );

    result = direct_reservoir_empty();
    selected_reconnection = current_reconnection;

    float current_mis = 1.0f;
    if (current_reservoir.target_pdf > 0.0f) {
        float m1 = current_reservoir.target_pdf *
                current_reservoir.total_samples;
        float m2 = 0.0f;
        vec2 reverse_fractional_pixel;
        float reverse_jacobian;
        if (!direct_reconnection_is_hand(current_reconnection) &&
                direct_splat_shift_primary(
                    current_reconnection,
                    false,
                    reverse_fractional_pixel,
                    reverse_jacobian
                )) {
            float shifted_target = direct_splat_evaluate_target(
                current_reservoir.smple,
                current_reconnection
            );
            m2 = shifted_target * reverse_jacobian;
            m2 = isnan(m2) ? 0.0f : m2;

            ivec2 reverse_pixel = ivec2(floor(reverse_fractional_pixel));
            m2 *= direct_splat_previous_confidence(reverse_pixel);
        }
        current_mis = (m1 + m2 == 0.0f)
                ? 0.0f
                : m1 / (m1 + m2);
    }

    bool current_selected = direct_reservoir_add_sample(
        result,
        current_reservoir,
        current_reservoir.smple,
        current_reservoir.target_pdf,
        current_mis,
        1.0f,
        ph_rand_next_float(frag_rnd_state)
    );
    if (current_selected) selected_reconnection = current_reconnection;

    float new_confidence = result.total_samples;
    uint contributor_count = ph_splat_cell_count(destination_index);
    uint contributor_offset = ph_splat_cell_offset(destination_index);
    for (uint i = 0u; i < contributor_count; i++) {
        uint source_index = ph_splat_sorted_source(contributor_offset + i);
        ivec2 source_pixel = ph_splat_pixel_from_index(source_index);

        DirectReservoir previous_reservoir;
        direct_reservoir_load_previous(
            previous_reservoir,
            source_pixel,
            false
        );
        DirectReconnection previous_reconnection =
                direct_reconnection_load_previous(source_index);

        float previous_mis = 0.0f;
        float shifted_target = 0.0f;
        float shifted_jacobian = 1.0f;
        DirectSample shifted_sample = previous_reservoir.smple;
        if (previous_reservoir.target_pdf > 0.0f &&
                direct_sample_reproject(shifted_sample)) {
            vec2 shifted_fractional_pixel;
            if (direct_splat_shift_primary(
                    previous_reconnection,
                    true,
                    shifted_fractional_pixel,
                    shifted_jacobian
            )) {
                shifted_target = direct_splat_evaluate_target(
                    shifted_sample,
                    previous_reconnection
                );
                float m1 = shifted_target * shifted_jacobian *
                        current_reservoir.total_samples;
                if (isnan(m1)) {
                    shifted_target = 0.0f;
                    shifted_jacobian = 1.0f;
                    m1 = 0.0f;
                }
                float m2 = previous_reservoir.target_pdf *
                        previous_reservoir.total_samples;
                previous_mis = (m1 + m2 == 0.0f)
                        ? 0.0f
                        : m2 / (m1 + m2);
            }
        }

        bool previous_selected = direct_reservoir_add_sample(
            result,
            previous_reservoir,
            shifted_sample,
            shifted_target,
            previous_mis,
            shifted_jacobian,
            ph_rand_next_float(frag_rnd_state)
        );
        if (previous_selected) {
            selected_reconnection = previous_reconnection;
        }
    }

    result.total_samples = direct_splat_update_confidence(
        current_reconnection,
        new_confidence
    );
    direct_reconnection_store_current(
        destination_index,
        selected_reconnection
    );
}

#endif
