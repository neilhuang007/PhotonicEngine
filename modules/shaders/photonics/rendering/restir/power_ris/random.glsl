#ifndef PH_REGIR_POWER_RIS_RANDOM_INCLUDE
#define PH_REGIR_POWER_RIS_RANDOM_INCLUDE

struct PhPowerRisRandom {
    uint seed;
    uint index;
};

uint ph_power_ris_morton_encode(uvec2 position);

uint ph_power_ris_jenkins_hash(uint value) {
    value = (value + 0x7ed55d16u) + (value << 12u);
    value = (value ^ 0xc761c23cu) ^ (value >> 19u);
    value = (value + 0x165667b1u) + (value << 5u);
    value = (value + 0xd3a2646cu) ^ (value << 9u);
    value = (value + 0xfd7046c5u) + (value << 3u);
    return (value ^ 0xb55a4f09u) ^ (value >> 16u);
}

PhPowerRisRandom ph_power_ris_random_init(uvec2 position, uint frame_index, uint pass_index) {
    return PhPowerRisRandom(
            ph_power_ris_jenkins_hash(ph_power_ris_morton_encode(position))
                    + frame_index
                    + pass_index * 31u,
            1u
    );
}

uint ph_power_ris_random_next_uint(inout PhPowerRisRandom random) {
    uint value = random.index++ * 0xcc9e2d51u;
    value = (value << 15u) | (value >> 17u);
    value *= 0x1b873593u;

    uint hash = random.seed ^ value;
    hash = ((hash << 13u) | (hash >> 19u)) * 5u + 0xe6546b64u;
    hash ^= 4u;
    hash ^= hash >> 16u;
    hash *= 0x85ebca6bu;
    hash ^= hash >> 13u;
    hash *= 0xc2b2ae35u;
    return hash ^ (hash >> 16u);
}

float ph_power_ris_random_next(inout PhPowerRisRandom random) {
    const uint mantissa_mask = (1u << 23u) - 1u;
    return uintBitsToFloat((ph_power_ris_random_next_uint(random) & mantissa_mask) | floatBitsToUint(1.0f)) - 1.0f;
}

#endif
