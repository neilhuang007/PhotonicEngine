#version 430

//ph_required: uniform int frameCounter;

#include "/photonics/uniforms.glsl"
#include "/photonics/rendering/restir/power_ris/power_pdf_sampling.glsl"
#include "/photonics/rendering/restir/power_ris/local_ris.glsl"

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

void main() {
    uint sample_in_tile = gl_GlobalInvocationID.x;
    uint tile_index = gl_GlobalInvocationID.y;
    if (sample_in_tile >= ph_regir_local_ris_tile_size
            || tile_index >= ph_regir_local_ris_tile_count) {
        return;
    }

    uint ris_buffer_index = sample_in_tile
            + tile_index * ph_regir_local_ris_tile_size;
    PhPowerRisRandom random = ph_power_ris_random_init(
            uvec2(sample_in_tile, tile_index),
            uint(frameCounter),
            0u
    );

    uvec2 texel_position = uvec2(0u);
    float pdf = 0.0f;
    uvec2 entry = uvec2(0u);

    if (light_list_size > 0
            && ph_regir_power_pdf_sample(random, texel_position, pdf)) {
        uint light_index = ph_power_ris_morton_encode(texel_position);
        float inv_source_pdf = 1.0f / pdf;

        if (light_index < uint(light_list_size)
                && inv_source_pdf > 0.0f
                && !isnan(inv_source_pdf)
                && !isinf(inv_source_pdf)) {
            entry = uvec2(light_index, floatBitsToUint(inv_source_pdf));
        }
    }

    ph_regir_local_ris_entries[ris_buffer_index] = entry;
}
