#ifndef PH_RESERVOIR_SPLATTING_BUFFERS_INCLUDE
#define PH_RESERVOIR_SPLATTING_BUFFERS_INCLUDE

layout(std430) buffer ph_reservoir_splatting_counters {
    uint ph_reservoir_splatting_counter_words[];
};

layout(std430) buffer ph_reservoir_splatting_append {
    uint ph_reservoir_splatting_append_words[];
};

layout(std430) buffer ph_reservoir_splatting_sorted {
    uint ph_reservoir_splatting_sorted_words[];
};

const uint ph_splat_data_count_word = 0u;
const uint ph_splat_prefix_sum_word = 1u;
const uint ph_splat_cell_counter_word_offset = 2u;

uint ph_splat_pixel_count() {
    return uint(PH_VIEW_SIZE.x) * uint(PH_VIEW_SIZE.y);
}

bool ph_splat_pixel_in_bounds(ivec2 pixel) {
    return all(greaterThanEqual(pixel, ivec2(0))) &&
            all(lessThan(pixel, ivec2(PH_VIEW_SIZE)));
}

uint ph_splat_pixel_index(ivec2 pixel) {
    return uint(pixel.y) * uint(PH_VIEW_SIZE.x) + uint(pixel.x);
}

ivec2 ph_splat_pixel_from_index(uint index) {
    uint width = uint(PH_VIEW_SIZE.x);
    return ivec2(index % width, index / width);
}

uint ph_splat_cell_count(uint cell_index) {
    return ph_reservoir_splatting_counter_words[
        ph_splat_cell_counter_word_offset + cell_index
    ];
}

uint ph_splat_cell_offset(uint cell_index) {
    return ph_reservoir_splatting_sorted_words[cell_index];
}

uint ph_splat_sorted_source(uint sorted_index) {
    return ph_reservoir_splatting_sorted_words[
        ph_splat_pixel_count() + sorted_index
    ];
}

#endif
