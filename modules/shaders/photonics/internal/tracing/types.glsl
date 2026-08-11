uint ph_get_node_cell_index(vec3 pos, int scale) {
    uvec3 cell_pos = (floatBitsToUint(pos) >> scale) & 3u;
    return cell_pos.x + (cell_pos.z << 2) + (cell_pos.y << 4);
}

vec3 ph_floor_scale(vec3 pos, int scale) {
    uint mask = ~0u << scale;
    return uintBitsToFloat(floatBitsToUint(pos) & mask);
}

uvec2 ph_shift_right_64(uvec2 value, uint shift) {
    if (shift == 0u) {
        return value;
    }

    if (shift < 32u) {
        return uvec2(
            (value.x >> shift) | (value.y << (32u - shift)),
            value.y >> shift
        );
    }

    if (shift < 64u) {
        return uvec2(value.y >> (shift - 32u), 0u);
    }

    return uvec2(0u);
}

uint ph_bitCount_64(uvec2 mask, uint width) {
    if (width == 0u) {
        return 0u;
    }

    if (width <= 32u) {
        if (width == 32u) {
            return bitCount(mask.x);
        }

        return bitCount(mask.x & ((1u << width) - 1u));
    }

    uint count = bitCount(mask.x);
    uint upper_width = width - 32u;

    if (upper_width >= 32u) {
        return count + bitCount(mask.y);
    }

    return count + bitCount(mask.y & ((1u << upper_width) - 1u));
}

vec3 ph_get_mirrored_pos(vec3 pos, vec3 dir, bool range_check) {
    vec3 mirrored = uintBitsToFloat(floatBitsToUint(pos) ^ 0x7FFFFFu);

    if (range_check && (any(lessThan(pos, vec3(1.0f))) || any(greaterThanEqual(pos, vec3(2.0f)))))
        mirrored = 3.0 - pos;

    return vec3(
        dir.x > 0.0f ? mirrored.x : pos.x,
        dir.y > 0.0f ? mirrored.y : pos.y,
        dir.z > 0.0f ? mirrored.z : pos.z
    );
}

vec3 ph_to_norm_pos(vec3 position, vec3 ray_direction) {
    return ph_get_mirrored_pos(
        (position / world_tree_size) + 1.0f
        , ray_direction,
        true
    );
}

bool ph_is_target(vec3 pos, vec3 target) {
    return ph_floor_scale(pos, world_block_scale_exp) == target;
}


struct RtNode {
    uint data0;
    uvec2 child_mask;
};

bool rt_node_is_leaf(RtNode node) {
    return (node.data0 & 1u) != 0;
}

uint rt_node_child_ptr(RtNode node) {
    return node.data0 >> 1u;
}

bool rt_node_has_child(RtNode node, uint child_index) {
    if (child_index < 32u) {
        return ((node.child_mask.x >> child_index) & 1u) != 0u;
    }

    child_index -= 32u;
    return ((node.child_mask.y >> child_index) & 1u) != 0u;
}

uint rt_node_get_child(RtNode node, uint child_index, int scale_exp) {
    uint multiplier = scale_exp == world_block_scale_exp ? 5u : 3u;

    return rt_node_child_ptr(node) + (ph_bitCount_64(node.child_mask, child_index) * multiplier);
}

RtNode load_rt_node(uint index) {
    return RtNode(
        ph_world_buffer[index],
        uvec2(
            ph_world_buffer[index + 1],
            ph_world_buffer[index + 2]
        )
    );
}

struct LeafNode {
    uint data0;
};

bool leaf_node_is_transparent(LeafNode node) {
#if defined PH_USE_TRANSPARENCY
    return (node.data0 & 1u) != 0;
#else
    return false;
#endif
}

uint leaf_node_palette_entry(LeafNode node) {
    return node.data0 >> 1u;
}

LeafNode load_leaf_node(RtNode node, uint child_index) {
    return LeafNode(ph_world_buffer[rt_node_child_ptr(node) + ph_bitCount_64(node.child_mask, child_index)]);
}
