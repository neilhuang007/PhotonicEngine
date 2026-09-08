#ifndef PH_RESERVOIR_SPLATTING_TEMPORAL_REUSE_INCLUDE
#define PH_RESERVOIR_SPLATTING_TEMPORAL_REUSE_INCLUDE

#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"
#define PH_DIRECT_RECONNECTION_FRAG_DATA
#include "/photonics/rendering/restir/reservoir_splatting/reconnection.glsl"
#include "/photonics/rendering/restir/reservoir_splatting/shift.glsl"

//ph_required: uniform int ph_reservoir_splatting_history_valid;

bool direct_splat_history_is_valid() {
    return ph_reservoir_splatting_history_valid != 0;
}

float direct_splat_balance_heuristic(float numerator, float competing) {
    float denominator = numerator + competing;
    return denominator != 0.0f
            ? numerator / denominator
            : 0.0f;
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
    if (!direct_splat_history_is_valid()) {
        return min(max_direct_temporal_samples, current_confidence);
    }
    vec2 previous_uv = ph_reproject_player_pos(
        current_reconnection.player_pos,
        direct_reconnection_is_hand(current_reconnection),
        // Reservoir ownership is the unjittered film-pixel domain, unlike
        // SVGF's jittered raster history. Match both retained-path shifts.
        vec2(0.0f)
    ).xy;
    if (any(lessThan(previous_uv, vec2(0.0f))) ||
            any(greaterThanEqual(previous_uv, vec2(1.0f)))) {
        return min(max_direct_temporal_samples, current_confidence);
    }

    vec2 previous_texel = previous_uv * PH_VIEW_SIZE - 0.5f;
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
        _frag_data,
        vec2(0.5f)
    );
    vec2 projected_current_pixel;
    if (direct_splat_project_primary_unchecked(
            current_reconnection,
            false,
            projected_current_pixel
    ) && all(equal(
            ivec2(floor(projected_current_pixel)),
            frag_tex_coord
    ))) {
        current_reconnection.subpixel = fract(projected_current_pixel);
    }
    float current_target = 0.0f;
    direct_splat_initialize_path_data(
        current_reconnection,
        current_reservoir.smple,
        current_target
    );
    current_reservoir.target_pdf = current_target;

    result = direct_reservoir_empty();
    selected_reconnection = current_reconnection;

    float current_mis = 1.0f;
    if (current_reservoir.target_pdf > 0.0f) {
        float m1 = current_reservoir.target_pdf *
                current_reservoir.total_samples;
        float m2 = 0.0f;
        DirectReconnection reverse_reconnection;
        vec2 reverse_fractional_pixel;
        float reverse_target;
        float reverse_jacobian;
        if (direct_splat_history_is_valid() &&
                !direct_reconnection_is_hand(current_reconnection) &&
                direct_splat_shift_and_evaluate_retained_path(
                    current_reservoir.smple,
                    current_reconnection,
                    false,
                    reverse_reconnection,
                    reverse_fractional_pixel,
                    reverse_target,
                    reverse_jacobian
            )) {
            ivec2 reverse_pixel = ivec2(floor(reverse_fractional_pixel));
            m2 = reverse_target * reverse_jacobian;
            m2 = isnan(m2) ? 0.0f : m2;
            m2 *= direct_splat_previous_confidence(reverse_pixel);
        }
        current_mis = direct_splat_balance_heuristic(m1, m2);
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
        DirectReconnection shifted_reconnection = previous_reconnection;

        float previous_mis = 0.0f;
        float shifted_target = 0.0f;
        float shifted_jacobian = 1.0f;
        DirectSample shifted_sample = previous_reservoir.smple;
        if (previous_reservoir.target_pdf > 0.0f &&
                direct_sample_reproject(shifted_sample)) {
            vec2 shifted_fractional_pixel;
            if (direct_splat_shift_and_evaluate_retained_path(
                    shifted_sample,
                    previous_reconnection,
                    true,
                    shifted_reconnection,
                    shifted_fractional_pixel,
                    shifted_target,
                    shifted_jacobian
            )) {
                float m1 = shifted_target * shifted_jacobian *
                        current_reservoir.total_samples;
                bool m1_is_nan = isnan(m1);
                shifted_target = m1_is_nan ? 0.0f : shifted_target;
                shifted_jacobian = m1_is_nan ? 1.0f : shifted_jacobian;
                float m2 = previous_reservoir.target_pdf *
                        previous_reservoir.total_samples;
                previous_mis = direct_splat_balance_heuristic(m2, m1);
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
            selected_reconnection = shifted_reconnection;
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
