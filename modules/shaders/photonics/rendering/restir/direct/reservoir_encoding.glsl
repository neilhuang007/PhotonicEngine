#ifndef PH_DIRECT_RESERVOIR_ENCODING_INCLUDE
#define PH_DIRECT_RESERVOIR_ENCODING_INCLUDE

const uint direct_reservoir_light_valid_bit = 0x80000000u;
const uint direct_reservoir_light_index_mask = 0x7fffffffu;

bool direct_reservoir_data_is_finite(vec3 reservoir_data) {
    return all(greaterThanEqual(reservoir_data, vec3(0.0f))) &&
            !any(isnan(reservoir_data)) &&
            !any(isinf(reservoir_data));
}

bool direct_reservoir_encoding_is_reusable(
    uvec3 sample_data,
    vec3 reservoir_data
) {
    return (sample_data.x & direct_reservoir_light_valid_bit) != 0u &&
            direct_reservoir_data_is_finite(reservoir_data) &&
            all(greaterThan(reservoir_data, vec3(0.0f)));
}

#endif
