#ifndef PH_REGIR_LOCAL_RIS_INCLUDE
#define PH_REGIR_LOCAL_RIS_INCLUDE

#include "/photonics/uniforms.glsl"

const uint ph_regir_local_ris_tile_size = 1024u;
const uint ph_regir_local_ris_tile_count = 128u;

layout(std430) restrict buffer ph_regir_local_ris {
    uvec2 ph_regir_local_ris_entries[];
};

struct PhRegirLocalRisTile {
    uint offset;
    uint size;
};

PhRegirLocalRisTile ph_regir_local_ris_select_tile(float coherent_random) {
    uint tile_index = min(
            uint(floor(coherent_random * float(ph_regir_local_ris_tile_count))),
            ph_regir_local_ris_tile_count - 1u
    );

    return PhRegirLocalRisTile(
            tile_index * ph_regir_local_ris_tile_size,
            ph_regir_local_ris_tile_size
    );
}

bool ph_regir_local_ris_sample(
        PhRegirLocalRisTile tile,
        float random,
        out int light_index,
        out float inv_source_pdf
) {
    uint sample_index = min(
            uint(floor(random * float(tile.size))),
            tile.size - 1u
    );
    uvec2 entry = ph_regir_local_ris_entries[tile.offset + sample_index];

    light_index = int(entry.x);
    inv_source_pdf = uintBitsToFloat(entry.y);

    return light_index >= 0
            && light_index < light_list_size
            && inv_source_pdf > 0.0f
            && !isnan(inv_source_pdf)
            && !isinf(inv_source_pdf);
}

#endif
