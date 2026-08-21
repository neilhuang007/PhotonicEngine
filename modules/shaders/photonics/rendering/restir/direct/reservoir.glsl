#include "/photonics/rendering/restir/direct/sample.glsl"
#include "/photonics/rendering/restir/direct/reservoir_encoding.glsl"

#define DIRECT_RESERVOIR_0 3
#define DIRECT_RESERVOIR_1 4

#if defined PH_ENABLE_GI && defined PH_RESTIR_COMBINED_GI
#define DIRECT_CANDIDATE_RESERVOIR 7
#else
#define DIRECT_CANDIDATE_RESERVOIR 5
#endif

//ph_required: uniform usampler2D restir_direct_reservoirs0;
//ph_required: uniform sampler2D restir_direct_reservoirs1;
//ph_required: uniform usampler2D prev_restir_direct_reservoirs0;
//ph_required: uniform sampler2D prev_restir_direct_reservoirs1;
//ph_required: uniform usampler2D restir_direct_candidates;

const float max_direct_temporal_samples = 20.0f;
struct DirectReservoir {
    DirectSample smple;

    // GRIS totalWeight, selected target value, and confidence respectively.
    float weight_sum;
    float target_pdf;
    float total_samples;
};

DirectReservoir direct_reservoir_empty() {
    return DirectReservoir(
        direct_sample_empty(),
        0.0f,
        0.0f,
        0.0f
    );
}

bool direct_reservoir_is_empty(DirectReservoir reservoir) {
    return direct_sample_is_empty(reservoir.smple);
}

bool direct_reservoir_is_valid_measure(float value) {
    return value >= 0.0f && !isnan(value) && !isinf(value);
}

float direct_reservoir_sanitize_weight(float weight) {
    return direct_reservoir_is_valid_measure(weight) ? weight : 0.0f;
}

bool direct_reservoir_stream_sample(
    inout DirectReservoir reservoir,
    DirectSample smple,
    float target_pdf,
    float inv_source_pdf,
    float random
) {
    float sanitized_target_pdf =
            direct_reservoir_sanitize_weight(target_pdf);
    float sanitized_inv_source_pdf =
            direct_reservoir_sanitize_weight(inv_source_pdf);
    float ris_weight = direct_reservoir_sanitize_weight(
        sanitized_target_pdf * sanitized_inv_source_pdf
    );
    reservoir.weight_sum = direct_reservoir_sanitize_weight(
        reservoir.weight_sum + ris_weight
    );
    reservoir.total_samples += 1.0f;

    if (ris_weight > 0.0f &&
            random * reservoir.weight_sum < ris_weight) {
        reservoir.smple = smple;
        reservoir.target_pdf = sanitized_target_pdf;
        return true;
    }

    return false;
}

float direct_reservoir_compute_ucw(DirectReservoir reservoir) {
    return reservoir.target_pdf == 0.0f
            ? 0.0f
            : reservoir.weight_sum / reservoir.target_pdf;
}

void direct_reservoir_finalize_initial_candidate(
    inout DirectReservoir reservoir
) {
    if (reservoir.total_samples > 0.0f) {
        reservoir.weight_sum /= reservoir.total_samples;
        reservoir.total_samples = 1.0f;
    }
}

bool direct_reservoir_add_sample(
    inout DirectReservoir result,
    DirectReservoir other,
    DirectSample shifted_sample,
    float shifted_target_pdf,
    float mis_weight,
    float jacobian,
    float random
) {
    float weight = mis_weight * shifted_target_pdf *
            direct_reservoir_compute_ucw(other) * jacobian;
    result.weight_sum += weight;
    result.total_samples = min(
        result.total_samples + other.total_samples,
        max_direct_temporal_samples
    );

    if (random * result.weight_sum < weight) {
        result.smple = shifted_sample;
        result.target_pdf = shifted_target_pdf;
        return true;
    }

    return false;
}

bool direct_reservoir_merge(
    inout DirectReservoir result,
    DirectReservoir other
) {
    vec3 shifted_integrand;
    direct_sample_get_visible_color(
        other.smple,
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal,
        frag_is_light_transmissive,
        shifted_integrand
    );
    return direct_reservoir_add_sample(
        result,
        other,
        other.smple,
        direct_sample_weight(shifted_integrand),
        1.0f,
        1.0f,
        ph_rand_next_float(frag_rnd_state)
    );
}

void direct_reservoir_validate_visibility(inout DirectReservoir reservoir, vec3 sample_pos) {
    if (direct_sample_is_empty(reservoir.smple)) return;

    float ucw = direct_reservoir_compute_ucw(reservoir);
    vec3 integrand;
    if (!direct_sample_get_visible_color(
        reservoir.smple,
        sample_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal,
        frag_is_light_transmissive,
        integrand
    )) {
        reservoir.smple = direct_sample_empty();
        reservoir.target_pdf = 0.0f;
        reservoir.weight_sum = 0.0f;
        return;
    }

    reservoir.target_pdf = direct_sample_weight(integrand);
    reservoir.weight_sum = reservoir.target_pdf * ucw;
}

vec3 direct_reservoir_get_final_color(
    DirectReservoir reservoir,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(reservoir.smple))
        return vec3(0.0f);

    vec3 integrand;
    direct_sample_get_visible_color(
        reservoir.smple,
        sample_pos,
        geo_normal,
        tex_normal,
        frag_is_light_transmissive,
        integrand
    );
    return integrand * direct_reservoir_compute_ucw(reservoir);
}

void direct_reservoir_encode(
    DirectReservoir reservoir,
    out uvec3 sample_data,
    out vec3 reservoir_data
);
void direct_reservoir_decode(
    out DirectReservoir reservoir,
    uvec3 sample_data,
    vec3 reservoir_data
);
bool direct_reservoir_is_finite(DirectReservoir reservoir);

uvec4 direct_reservoir_encode_candidate(DirectReservoir reservoir) {
    uvec3 sample_data;
    vec3 reservoir_data;
    direct_reservoir_encode(reservoir, sample_data, reservoir_data);
    return uvec4(
        sample_data,
        floatBitsToUint(reservoir_data.x)
    );
}

void direct_reservoir_decode_candidate(
    out DirectReservoir reservoir,
    uvec4 data
) {
    direct_reservoir_decode(
        reservoir,
        data.xyz,
        // The receiver evaluates the selected sample's target before reuse.
        vec3(uintBitsToFloat(data.w), 0.0f, 1.0f)
    );
}

bool direct_reservoir_load_candidate(
    out DirectReservoir reservoir,
    ivec2 tex_coord
) {
    direct_reservoir_decode_candidate(
        reservoir,
        texelFetch(restir_direct_candidates, tex_coord, 0)
    );
    if (!direct_reservoir_is_finite(reservoir)) {
        reservoir = direct_reservoir_empty();
        return false;
    }
    return true;
}

void direct_reservoir_encode(
    DirectReservoir reservoir,
    out uvec3 sample_data,
    out vec3 reservoir_data
) {
    sample_data = uvec3(0u);
    if (!direct_sample_is_empty(reservoir.smple)) {
        sample_data.x = uint(reservoir.smple.light_index) |
                direct_reservoir_light_valid_bit;
        sample_data.yz = floatBitsToUint(reservoir.smple.uv);
    }

    reservoir_data = vec3(
        reservoir.weight_sum,
        reservoir.target_pdf,
        reservoir.total_samples
    );
}

void direct_reservoir_decode(
    out DirectReservoir reservoir,
    uvec3 sample_data,
    vec3 reservoir_data
) {
    reservoir.smple = direct_sample_empty();
    if ((sample_data.x & direct_reservoir_light_valid_bit) != 0u) {
        reservoir.smple.light_index = int(
            sample_data.x & direct_reservoir_light_index_mask
        );
        reservoir.smple.uv = uintBitsToFloat(sample_data.yz);
    }

    reservoir.weight_sum = reservoir_data.x;
    reservoir.target_pdf = reservoir_data.y;
    reservoir.total_samples = reservoir_data.z;
}

bool direct_reservoir_is_finite(DirectReservoir reservoir) {
    return direct_reservoir_data_is_finite(vec3(
        reservoir.weight_sum,
        reservoir.target_pdf,
        reservoir.total_samples
    ));
}

bool direct_reservoir_load(out DirectReservoir reservoir, ivec2 tex_coord) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(restir_direct_reservoirs0, tex_coord, 0).rgb,
        texelFetch(restir_direct_reservoirs1, tex_coord, 0).rgb
    );

    if (!direct_reservoir_is_finite(reservoir)) {
        reservoir = direct_reservoir_empty();
        return false;
    }
    return true;
}

bool direct_reservoir_load_previous(out DirectReservoir reservoir, ivec2 tex_coord, bool reprojected) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(prev_restir_direct_reservoirs0, tex_coord, 0).rgb,
        texelFetch(prev_restir_direct_reservoirs1, tex_coord, 0).rgb
    );

    if (!direct_reservoir_is_finite(reservoir)) {
        reservoir = direct_reservoir_empty();
        return false;
    }

    if (!reprojected)
        return true;

    return direct_sample_reproject(reservoir.smple);
}
