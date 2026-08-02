#ifndef PH_REGIR_MAPPING_INCLUDE
#define PH_REGIR_MAPPING_INCLUDE

#include "/photonics/uniforms.glsl"
#include "/photonics/rendering/restir/regir/parameters.glsl"

const float REGIR_PI = 3.1415926535f;

// Photonics uploads lights and G-buffer positions in render-tree-local space.
// The ReGIR center follows the same rt_camera_position every frame.
vec3 regir_center() {
    return rt_camera_position;
}

vec3 regir_spherical_to_cartesian(float radius, float azimuth, float elevation) {
    float cosine_elevation = cos(elevation);
    return vec3(
        radius * cos(azimuth) * cosine_elevation,
        radius * sin(elevation),
        radius * sin(azimuth) * cosine_elevation
    );
}

#if PH_REGIR_MODE == PH_REGIR_GRID

float regir_get_jitter_scale(vec3 world_position) {
    return ph_regir.commonParams.samplingJitter * ph_regir.commonParams.cellSize;
}

int regir_world_position_to_cell_index(vec3 world_position) {
    ivec3 cell_count = ivec3(
        ph_regir.gridParams.cellsX,
        ph_regir.gridParams.cellsY,
        ph_regir.gridParams.cellsZ
    );
    vec3 grid_origin = regir_center() -
            vec3(cell_count) * (ph_regir.commonParams.cellSize * 0.5f);
    ivec3 cell = ivec3(floor(
        (world_position - grid_origin) / ph_regir.commonParams.cellSize
    ));

    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, cell_count))) {
        return -1;
    }

    return cell.x + (cell.y + cell.z * cell_count.y) * cell_count.x;
}

bool regir_cell_index_to_world_position(
    int cell_index,
    out vec3 cell_center,
    out float cell_radius
) {
    uvec3 cell_count = uvec3(
        ph_regir.gridParams.cellsX,
        ph_regir.gridParams.cellsY,
        ph_regir.gridParams.cellsZ
    );
    vec3 grid_origin = regir_center() -
            vec3(cell_count) * (ph_regir.commonParams.cellSize * 0.5f);

    uvec3 cell_position;
    cell_position.x = uint(cell_index);
    cell_position.y = cell_position.x / cell_count.x;
    cell_position.x %= cell_count.x;
    cell_position.z = cell_position.y / cell_count.y;
    cell_position.y %= cell_count.y;

    if (cell_position.z >= cell_count.z) {
        cell_center = vec3(0.0f);
        cell_radius = 0.0f;
        return false;
    }

    cell_center = (vec3(cell_position) + 0.5f) *
            ph_regir.commonParams.cellSize + grid_origin;
    cell_radius = ph_regir.commonParams.cellSize * sqrt(3.0f);
    return true;
}

#elif PH_REGIR_MODE == PH_REGIR_ONION

float regir_get_jitter_scale(vec3 world_position) {
    float distance_to_center = length(world_position - regir_center()) /
            ph_regir.commonParams.cellSize;
    float jitter_scale = max(1.0f, max(
        pow(distance_to_center, 1.0f / 3.0f) *
                ph_regir.onionParams.cubicRootFactor,
        distance_to_center * ph_regir.onionParams.linearFactor
    ));
    return jitter_scale * ph_regir.commonParams.samplingJitter *
            ph_regir.commonParams.cellSize;
}

int regir_world_position_to_cell_index(vec3 world_position) {
    vec3 translated_position = world_position - regir_center();
    float radius = length(translated_position);
    if (radius <= ph_regir.onionParams.layers[0].innerRadius) {
        return 0;
    }

    vec3 direction = translated_position / radius;
    float azimuth = atan(direction.z, direction.x) + REGIR_PI;
    float elevation = asin(clamp(direction.y, -1.0f, 1.0f));

    ReGIROnionLayerGroup layer_group;
    int group_index;
    for (group_index = 0;
         group_index < int(ph_regir.onionParams.numLayerGroups);
         group_index++) {
        if (radius <= ph_regir.onionParams.layers[group_index].outerRadius) {
            layer_group = ph_regir.onionParams.layers[group_index];
            break;
        }
    }
    if (group_index >= int(ph_regir.onionParams.numLayerGroups)) {
        return -1;
    }

    uint layer_index = uint(floor(max(
        0.0f,
        log(radius / layer_group.innerRadius) *
                layer_group.invLogLayerScale
    )));
    layer_index = min(layer_index, uint(layer_group.layerCount - 1));

    uint ring_index = uint(floor(
        abs(elevation) * layer_group.invEquatorialCellAngle + 0.5f
    ));
    ReGIROnionRing ring = ph_regir.onionParams.rings[
        layer_group.ringOffset + int(ring_index)
    ];

    if ((layer_index & 1u) != 0u) {
        azimuth -= ring.cellAngle * 0.5f;
        if (azimuth < 0.0f) {
            azimuth += 2.0f * REGIR_PI;
        }
    }

    int ring_cell_offset = ring.cellOffset;
    if (elevation < 0.0f && ring_index > 0u) {
        ring_cell_offset += ring.cellCount;
    }

    return int(floor(azimuth * ring.invCellAngle)) +
            ring_cell_offset +
            int(layer_index) * layer_group.cellsPerLayer +
            layer_group.layerCellOffset;
}

bool regir_cell_index_to_world_position(
    int cell_index,
    out vec3 cell_center,
    out float cell_radius
) {
    cell_center = vec3(0.0f);
    cell_radius = 0.0f;

    if (cell_index < 0) {
        return false;
    }
    if (cell_index == 0) {
        cell_center = regir_center();
        cell_radius = ph_regir.onionParams.layers[0].innerRadius;
        return true;
    }

    cell_index -= 1;
    ReGIROnionLayerGroup layer_group;
    int group_index;
    for (group_index = 0;
         group_index < int(ph_regir.onionParams.numLayerGroups);
         group_index++) {
        layer_group = ph_regir.onionParams.layers[group_index];
        int cells_per_group =
                layer_group.cellsPerLayer * layer_group.layerCount;
        if (cell_index < cells_per_group) {
            break;
        }
        cell_index -= cells_per_group;
    }
    if (group_index >= int(ph_regir.onionParams.numLayerGroups)) {
        return false;
    }

    int layer_index = cell_index / layer_group.cellsPerLayer;
    cell_index -= layer_index * layer_group.cellsPerLayer;

    ReGIROnionRing ring;
    int ring_index;
    for (ring_index = 0; ring_index < layer_group.ringCount; ring_index++) {
        ring = ph_regir.onionParams.rings[layer_group.ringOffset + ring_index];
        int ring_cells = ring.cellCount * (ring_index > 0 ? 2 : 1);
        if (cell_index < ring.cellOffset + ring_cells) {
            break;
        }
    }
    if (ring_index >= layer_group.ringCount) {
        return false;
    }

    cell_index -= ring.cellOffset;
    float elevation = float(ring_index) * layer_group.equatorialCellAngle;
    if (cell_index >= ring.cellCount) {
        elevation = -elevation;
    }

    float azimuth = (float(cell_index) + 0.5f) * ring.cellAngle;
    if ((layer_index & 1) != 0) {
        azimuth += ring.cellAngle * 0.5f;
    }
    azimuth -= REGIR_PI;

    float inner_radius = layer_group.innerRadius *
            pow(layer_group.layerScale, float(layer_index));
    float outer_radius = inner_radius * layer_group.layerScale;
    float radius = (inner_radius + outer_radius) * 0.5f;

    cell_center = regir_spherical_to_cartesian(radius, azimuth, elevation);
    azimuth += ring.cellAngle * 0.5f;
    elevation = elevation == 0.0f
            ? layer_group.equatorialCellAngle * 0.5f
            : (abs(elevation) -
                    layer_group.equatorialCellAngle * 0.5f) * sign(elevation);
    vec3 cell_corner = regir_spherical_to_cartesian(
        outer_radius,
        azimuth,
        elevation
    );

    cell_radius = length(cell_corner - cell_center);
    cell_center += regir_center();
    return true;
}

#else

float regir_get_jitter_scale(vec3 world_position) {
    return 0.0f;
}

int regir_world_position_to_cell_index(vec3 world_position) {
    return -1;
}

bool regir_cell_index_to_world_position(
    int cell_index,
    out vec3 cell_center,
    out float cell_radius
) {
    cell_center = vec3(0.0f);
    cell_radius = 0.0f;
    return false;
}

#endif

#endif
