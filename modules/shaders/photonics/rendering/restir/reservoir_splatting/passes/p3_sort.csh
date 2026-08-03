#version 430

layout(local_size_x = 16, local_size_y = 16) in;

#define PH_VIEW_SIZE (vec2(viewWidth, viewHeight) * PH_RENDER_SCALE)

//ph_required: uniform float viewWidth;
//ph_required: uniform float viewHeight;

#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!ph_splat_pixel_in_bounds(pixel)) return;

    uint append_index = ph_splat_pixel_index(pixel);
    if (append_index >= ph_reservoir_splatting_counter_words[
            ph_splat_data_count_word
    ]) return;

    uint metadata_offset = append_index * 2u;
    uint target_cell = ph_reservoir_splatting_append_words[metadata_offset];
    uint local_cell_index = ph_reservoir_splatting_append_words[
        metadata_offset + 1u
    ];
    uint source_index = ph_reservoir_splatting_append_words[
        ph_splat_pixel_count() * 2u + append_index
    ];
    ph_reservoir_splatting_sorted_words[
        ph_splat_pixel_count() +
        ph_splat_cell_offset(target_cell) +
        local_cell_index
    ] = source_index;
}
