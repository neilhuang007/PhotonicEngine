#version 430

layout(local_size_x = 16, local_size_y = 16) in;

#define PH_VIEW_SIZE (vec2(viewWidth, viewHeight) * PH_RENDER_SCALE)

//ph_required: uniform float viewWidth;
//ph_required: uniform float viewHeight;

#include "/photonics/rendering/restir/reservoir_splatting/buffers.glsl"

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!ph_splat_pixel_in_bounds(pixel)) return;

    uint cell_index = ph_splat_pixel_index(pixel);
    uint cell_offset = atomicAdd(
        ph_reservoir_splatting_counter_words[ph_splat_prefix_sum_word],
        ph_splat_cell_count(cell_index)
    );
    ph_reservoir_splatting_sorted_words[cell_index] = cell_offset;
}
