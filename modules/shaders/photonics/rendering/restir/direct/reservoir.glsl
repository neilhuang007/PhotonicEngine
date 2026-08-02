#include "/photonics/rendering/restir/direct/sample.glsl"

#define DIRECT_RESERVOIR_0 3
#define DIRECT_RESERVOIR_1 4

//ph_required: uniform usampler2D restir_direct_reservoirs0;
//ph_required: uniform sampler2D restir_direct_reservoirs1;
//ph_required: uniform usampler2D prev_restir_direct_reservoirs0;
//ph_required: uniform sampler2D prev_restir_direct_reservoirs1;

const float max_direct_temporal_samples = 20.0f;
const float max_direct_reservoir_samples = 128.0f;
const uint direct_reservoir_light_valid_bit = 0x80000000u;
const uint direct_reservoir_light_index_mask = 0x7fffffffu;

struct DirectReservoir {
    DirectSample smple;

    // RTXDI weightSum: an unfinalized streaming sum while candidates are
    // merged, then the selected sample's inverse PDF after finalization.
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

bool direct_reservoir_stream_sample(
    inout DirectReservoir reservoir,
    DirectSample smple,
    float target_pdf,
    float inv_source_pdf,
    float random
) {
    float ris_weight = target_pdf * inv_source_pdf;
    reservoir.weight_sum += ris_weight;
    reservoir.total_samples += 1.0f;

    if (ris_weight > 0.0f &&
            random * reservoir.weight_sum < ris_weight) {
        reservoir.smple = smple;
        reservoir.target_pdf = target_pdf;
        return true;
    }

    return false;
}

bool direct_reservoir_merge(
    inout DirectReservoir result,
    DirectReservoir other
) {
    float target_pdf = direct_sample_get_weight(
        other.smple,
        frag_rt_pos,
        frag_geo_normal,
        frag_is_hand ? frag_geo_normal : frag_tex_normal
    );

    float ris_weight =
            target_pdf * other.weight_sum * other.total_samples;
    result.weight_sum += ris_weight;
    result.total_samples += other.total_samples;

    if (ris_weight > 0.0f &&
            ph_rand_next_float(frag_rnd_state) * result.weight_sum <
                    ris_weight) {
        result.smple = other.smple;
        result.target_pdf = target_pdf;
        return true;
    }

    return false;
}

void direct_reservoir_clamp_samples(inout DirectReservoir reservoir) {
    if (reservoir.total_samples <= max_direct_reservoir_samples) return;

    // This is called on r6's unfinalized streaming sum. Scale the numerator
    // together with M so the later finalized inverse PDF is unchanged.
    reservoir.weight_sum *= max_direct_reservoir_samples /
            reservoir.total_samples;
    reservoir.total_samples = max_direct_reservoir_samples;
}

void direct_reservoir_validate_visibility(inout DirectReservoir reservoir, vec3 sample_pos) {
    if (direct_sample_is_empty(reservoir.smple)) return;

    Light light = direct_sample_get_light(reservoir.smple);
    vec3 light_position = direct_sample_get_position(
        reservoir.smple,
        light,
        sample_pos
    );
    vec3 to_light = light_position - sample_pos;

    vec3 unused0;
    float unused1;

    if (!trace_light_vis(
            sample_pos,
            to_light,
            light_position,
            40,
            unused0,
            unused1
    )) {
        reservoir.smple = direct_sample_empty();
        reservoir.target_pdf = 0.0f;
        reservoir.weight_sum = 0.0f;
    }
}

void direct_reservoir_finalize_weight(inout DirectReservoir reservoir) {
    float denominator =
            reservoir.target_pdf * reservoir.total_samples;
    if (!(denominator > 0.0f)) {
        reservoir.weight_sum = 0.0f;
        return;
    }

    reservoir.weight_sum /= denominator;
}

vec3 direct_reservoir_get_final_color(
    DirectReservoir reservoir,
    vec3 sample_pos,
    vec3 geo_normal,
    vec3 tex_normal
) {
    if (direct_sample_is_empty(reservoir.smple))
        return vec3(0.0f);

    Light light = direct_sample_get_light(reservoir.smple);
    vec3 light_position = direct_sample_get_position(
        reservoir.smple,
        light,
        sample_pos
    );
    vec3 to_light = light_position - sample_pos;

    vec3 tint_color;
    float light_transmittance;

    if (!trace_light_vis(
            sample_pos,
            to_light,
            light_position,
            40,
            tint_color,
            light_transmittance
    )) {
        return vec3(0.0f);
    }

    vec3 sampled_color = direct_sample_get_color(reservoir.smple, light, sample_pos, geo_normal, tex_normal);
    vec3 final_color = sampled_color * tint_color.rgb * light_transmittance;

    return final_color * reservoir.weight_sum;
}

void direct_reservoir_encode(
    DirectReservoir reservoir,
    out uvec2 sample_data,
    out vec3 reservoir_data
) {
    sample_data = uvec2(0u);
    if (!direct_sample_is_empty(reservoir.smple)) {
        sample_data.x = uint(reservoir.smple.light_index) |
                direct_reservoir_light_valid_bit;
        sample_data.y = uint(clamp(
            reservoir.smple.uv.x,
            0.0f,
            1.0f
        ) * 65535.0f) | (
            uint(clamp(
                reservoir.smple.uv.y,
                0.0f,
                1.0f
            ) * 65535.0f) << 16u
        );
    }

    reservoir_data = vec3(
        reservoir.weight_sum,
        reservoir.target_pdf,
        reservoir.total_samples
    );
}

void direct_reservoir_decode(
    out DirectReservoir reservoir,
    uvec2 sample_data,
    vec3 reservoir_data
) {
    reservoir.smple = direct_sample_empty();
    if ((sample_data.x & direct_reservoir_light_valid_bit) != 0u) {
        reservoir.smple.light_index = int(
            sample_data.x & direct_reservoir_light_index_mask
        );
        reservoir.smple.uv = vec2(
            sample_data.y & 0xffffu,
            sample_data.y >> 16u
        ) / 65535.0f;
    }

    reservoir.weight_sum = reservoir_data.x;
    reservoir.target_pdf = reservoir_data.y;
    reservoir.total_samples = reservoir_data.z;
}

bool direct_reservoir_is_finite(DirectReservoir reservoir) {
    return !isnan(reservoir.weight_sum)
            && !isinf(reservoir.weight_sum)
            && !isnan(reservoir.target_pdf)
            && !isinf(reservoir.target_pdf)
            && !isnan(reservoir.total_samples)
            && !isinf(reservoir.total_samples);
}

bool direct_reservoir_load(out DirectReservoir reservoir, ivec2 tex_coord) {
    direct_reservoir_decode(
        reservoir,
        texelFetch(restir_direct_reservoirs0, tex_coord, 0).rg,
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
        texelFetch(prev_restir_direct_reservoirs0, tex_coord, 0).rg,
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
