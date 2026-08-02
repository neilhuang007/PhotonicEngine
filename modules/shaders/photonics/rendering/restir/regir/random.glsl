#ifndef PH_REGIR_RANDOM_INCLUDE
#define PH_REGIR_RANDOM_INCLUDE

struct ReGIRRandomSamplerState {
    uint seed;
    uint index;
};

uint regir_integer_explode(uint value) {
    value = (value | (value << 8u)) & 0x00ff00ffu;
    value = (value | (value << 4u)) & 0x0f0f0f0fu;
    value = (value | (value << 2u)) & 0x33333333u;
    value = (value | (value << 1u)) & 0x55555555u;
    return value;
}

uint regir_z_curve_to_linear(uvec2 position) {
    return regir_integer_explode(position.x) |
            (regir_integer_explode(position.y) << 1u);
}

uint regir_jenkins_hash(uint value) {
    value = (value + 0x7ed55d16u) + (value << 12u);
    value = (value ^ 0xc761c23cu) ^ (value >> 19u);
    value = (value + 0x165667b1u) + (value << 5u);
    value = (value + 0xd3a2646cu) ^ (value << 9u);
    value = (value + 0xfd7046c5u) + (value << 3u);
    value = (value ^ 0xb55a4f09u) ^ (value >> 16u);
    return value;
}

ReGIRRandomSamplerState regir_initialize_random_sampler(
    uvec2 position,
    uint frame_index,
    uint pass
) {
    return ReGIRRandomSamplerState(
        regir_jenkins_hash(regir_z_curve_to_linear(position)) +
                frame_index + pass * 31u,
        1u
    );
}

uint regir_murmur3(inout ReGIRRandomSamplerState random_sampler) {
    uint hash = random_sampler.seed;
    uint value = random_sampler.index++;

    value *= 0xcc9e2d51u;
    value = (value << 15u) | (value >> 17u);
    value *= 0x1b873593u;

    hash ^= value;
    hash = ((hash << 13u) | (hash >> 19u)) * 5u + 0xe6546b64u;
    hash ^= 4u;
    hash ^= hash >> 16u;
    hash *= 0x85ebca6bu;
    hash ^= hash >> 13u;
    hash *= 0xc2b2ae35u;
    hash ^= hash >> 16u;
    return hash;
}

float regir_next_random(inout ReGIRRandomSamplerState random_sampler) {
    uint value = regir_murmur3(random_sampler);
    return uintBitsToFloat((value & 0x007fffffu) | floatBitsToUint(1.0f)) - 1.0f;
}

#endif
