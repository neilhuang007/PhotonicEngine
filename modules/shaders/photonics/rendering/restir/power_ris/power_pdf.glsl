#ifndef PH_REGIR_POWER_PDF_INCLUDE
#define PH_REGIR_POWER_PDF_INCLUDE

layout(std430) restrict buffer ph_regir_power_pdf {
    float ph_regir_power_pdf_values[];
};

uint ph_power_ris_integer_explode(uint value) {
    value = (value | (value << 8u)) & 0x00ff00ffu;
    value = (value | (value << 4u)) & 0x0f0f0f0fu;
    value = (value | (value << 2u)) & 0x33333333u;
    return (value | (value << 1u)) & 0x55555555u;
}

uint ph_power_ris_integer_compact(uint value) {
    value = (value & 0x11111111u) | ((value & 0x44444444u) >> 1u);
    value = (value & 0x03030303u) | ((value & 0x30303030u) >> 2u);
    value = (value & 0x000f000fu) | ((value & 0x0f0f0f00u) >> 4u);
    return (value & 0x000000ffu) | ((value & 0x00ff0000u) >> 8u);
}

uint ph_power_ris_morton_encode(uvec2 position) {
    return ph_power_ris_integer_explode(position.x)
            | (ph_power_ris_integer_explode(position.y) << 1u);
}

uvec2 ph_power_ris_morton_decode(uint index) {
    return uvec2(
            ph_power_ris_integer_compact(index),
            ph_power_ris_integer_compact(index >> 1u)
    );
}

uint ph_regir_power_pdf_width() {
    uint max_items = uint(PH_MAX_LIGHTS);
    uint width = 1u;

    while (width < 1u + (max_items - 1u) / width) {
        width <<= 1u;
    }

    return width;
}

uint ph_regir_power_pdf_height() {
    uint max_items = uint(PH_MAX_LIGHTS);
    uint width = ph_regir_power_pdf_width();
    uint required_height = 1u + (max_items - 1u) / width;
    uint height = 1u;

    while (height < required_height) {
        height <<= 1u;
    }

    return height;
}

uvec2 ph_regir_power_pdf_dimensions(uint mip_level) {
    return max(
            uvec2(1u),
            uvec2(ph_regir_power_pdf_width(), ph_regir_power_pdf_height()) >> mip_level
    );
}

uint ph_regir_power_pdf_mip_levels() {
    return uint(findMSB(max(ph_regir_power_pdf_width(), ph_regir_power_pdf_height()))) + 1u;
}

uint ph_regir_power_pdf_mip_offset(uint mip_level) {
    uint offset = 0u;

    for (uint level = 0u; level < mip_level; level++) {
        uvec2 dimensions = ph_regir_power_pdf_dimensions(level);
        offset += dimensions.x * dimensions.y;
    }

    return offset;
}

float ph_regir_power_pdf_load(ivec2 position, uint mip_level) {
    if (any(lessThan(position, ivec2(0)))) {
        return 0.0f;
    }

    uvec2 dimensions = ph_regir_power_pdf_dimensions(mip_level);
    if (any(greaterThanEqual(uvec2(position), dimensions))) {
        return 0.0f;
    }

    uint index = ph_regir_power_pdf_mip_offset(mip_level)
            + uint(position.y) * dimensions.x
            + uint(position.x);
    return ph_regir_power_pdf_values[index];
}

void ph_regir_power_pdf_store(uvec2 position, uint mip_level, float value) {
    uvec2 dimensions = ph_regir_power_pdf_dimensions(mip_level);
    uint index = ph_regir_power_pdf_mip_offset(mip_level)
            + position.y * dimensions.x
            + position.x;
    ph_regir_power_pdf_values[index] = value;
}

#endif
