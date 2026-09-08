"""Run real tree traversal and light visibility against a striped voxel pane.

uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py
The synthetic tree uses the runtime's actual 64-way node/leaf/palette layout.
This isolates tracing, transmission and the direct-light integrand, excluding
bakery, reservoir sampling and denoising. One fixture light replaces the light list.
"""
from pathlib import Path
import argparse
import math
import runpy
import struct
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / "modules/shaders/photonics"
function = runpy.run_path(str(Path(__file__).with_name("test-traversal-neighborhood.py")))["function"]


def read(relative):
    return (SHADERS / relative).read_text()


def strip_includes(source):
    return "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#include"))


def scene():
    voxels = {}
    # Three texture values plus actual cutout holes. Colors have partial alpha.
    colors = [0x8020E040, 0xC0E03020, 0x404040E0, 0]
    palette = [(0x8000002A, color, 0, 0) for color in colors for _ in range(6)]
    palette += [(0x8000002A, 0xA080C0F0, 0, 0)] * 6
    palette += [(42, 0xFFFFFFFF, 0, 0)] * 6
    for x in range(16):
        for y in range(16):
            stripe = x % 4
            # Front/back surfaces in the same material block: tint only once.
            if colors[stripe]:
                for z in (160, 175):
                    voxels[(512 + x, 512 + y, z)] = (stripe * 6, True)
            # A distinct second pane must contribute its own color filter.
            if y >= 8:
                for z in (192, 207):
                    voxels[(512 + x, 512 + y, z)] = (24, True)
            # Opaque target block occupied across all ray endpoints.
            voxels[(512 + x, 512 + y, 224)] = (30, False)
    buffer = [0, 0, 0]

    def node(index, entries, depth):
        shift = 8 - depth * 2
        groups = {}
        for position, material in entries:
            x, y, z = ((component >> shift) & 3 for component in position)
            groups.setdefault(x + 4 * z + 16 * y, []).append((position, material))
        children = sorted(groups)
        mask = sum(1 << child for child in children)
        pointer = len(buffer)
        leaf = depth == 4
        stride = 1 if leaf else (5 if depth == 2 else 3)
        buffer.extend([0] * (len(children) * stride))
        buffer[index:index + 3] = [(pointer << 1) | int(leaf), mask & 0xffffffff, mask >> 32]
        for offset, child in enumerate(children):
            child_pointer = pointer + offset * stride
            if leaf:
                palette_entry, transparent = groups[child][0][1]
                buffer[child_pointer] = (palette_entry << 1) | int(transparent)
            else:
                node(child_pointer, groups[child], depth + 1)

    node(0, list(voxels.items()), 0)
    return buffer, palette, colors


def shader_source(mode, mutate, reference, initial, reference_evaluation):
    common = read("internal/tracing/common.glsl")
    common = common.replace(function(common, "ray_result_light_data"), "")
    iterator = read("internal/tracing/ph_iterator.glsl")
    if reference:
        iterator = iterator.replace("ph_neighborhood_is_empty(node.child_mask, child_index)",
                                    "((ph_shift_right_64(node.child_mask, child_index & 42u).x & 0x00330033u) == 0u)")
    light = read("light.glsl")
    direct = read("rendering/restir/direct/sample.glsl")
    light_struct = light[light.index("struct Light"):light.index("};", light.index("struct Light")) + 2]
    # Skip the forward declaration/call and select the actual function body.
    visible_definition = direct[direct.rindex("bool direct_sample_get_visible_color_at_position("):]
    trace = function(read("internal/tracing/ph_simple.glsl"), "trace_light_vis")
    trace = trace.replace("    tint_color = vec3(1.0f);", "    atomicAdd(trace_calls[gl_GlobalInvocationID.x],1u);\n    tint_color = vec3(1.0f);")
    initial_helper = ""
    if initial and not reference_evaluation and "vec3 direct_sample_get_integrand(" in direct:
        initial_helper = function(direct, "direct_sample_get_position") + "\n" + function(direct, "direct_sample_get_integrand")
    evaluate = """bool visible=direct_sample_get_visible_color_at_position(
        DirectSample(int(variant),vec2(0.5)),target,origin,geo_normal,tex_normal,variant==2u,color);"""
    if initial_helper:
        evaluate = """color=direct_sample_get_integrand(DirectSample(int(variant),vec2(0.5)),origin,geo_normal,tex_normal,variant==2u);
        bool visible=true;"""
    if mutate:
        # Model the reported failure: replace each pane's texel with one color.
        iterator = iterator.replace("accumulator.rgb *= voxel_data_visibility_tint(voxel_data, albedo);",
                                    "accumulator.rgb *= vec3(0.75);")
    return "\n".join([
        "#version 430\n#define PH_VOXEL_COLOR_MODIFIER_DISABLED",
        "layout(std430,binding=3) buffer TraceCounts { uint trace_calls[]; };",
        "#define PH_USE_TRANSPARENCY" if mode != "NONE" else "",
        "#define PH_FULL_TRANSPARENCY" if mode == "VOXEL" else "",
        "const float world_tree_size=64.0; const int world_block_scale_exp=17;",
        "const vec3 world_min_block=vec3(0); const vec3 world_max_block=vec3(64);",
        "const int ph_world_scene_ready=1;",
        read("utility/color.glsl"), read("utility/normal_encoding.glsl"),
        strip_includes(read("palette.glsl")), strip_includes(common),
        read("internal/tracing/types.glsl"), strip_includes(iterator),
        trace,
        "#define LIGHT_TYPE_INVALID 0\n#define PH_ATTENUATION_MODIFIER_DISABLED",
        light_struct, function(light, "new_invalid_light"), function(light, "light_is_valid"),
        strip_includes(read("internal/impl/attenuation.glsl")), function(light, "light_sample_at"),
        """struct DirectSample { int light_index; vec2 uv; };
vec3 fixture_target;
Light light_list_get(int index) {
    vec3 energy=vec3(2,3,4);
    if(index==4) energy=vec3(0);
    if(index==6) energy*=1e-8;
    if(index==7) energy=vec3(uintBitsToFloat(0x7fc00000u));
    return Light(2,index,42,fixture_target,energy,1.0,vec2(1,0),1.0,0.0);
}""",
        function(direct, "direct_sample_is_empty"), function(direct, "direct_sample_get_light"),
        function(direct, "direct_sample_orient_shading_normals"),
        function(visible_definition, "direct_sample_get_visible_color_at_position"),
        initial_helper,
        """layout(local_size_x=64) in;
layout(std430,binding=2) writeonly buffer Results { vec4 result[]; };
void main() {
    uint i=gl_GlobalInvocationID.x;
    uint x=i%16u, y=(i/16u)%16u, variant=i/256u;
    vec3 origin=vec3(32.0+(float(x)+0.5)/16.0, 32.0+(float(y)+0.5)/16.0, 8.5);
    vec3 target=origin+vec3(0.001,0.002,6.0);
    fixture_target=target;
    vec3 normal=normalize(target-origin);
    vec3 geo_normal=(variant==1u || variant==2u) ? -normal : normal;
    vec3 tex_normal=(variant==2u || variant==3u) ? -normal : normal;
    if(variant==5u) geo_normal=normalize(cross(normal,vec3(1,0,0)));
    vec3 color;
""" + evaluate + """
    result[i]=vec4(color, float(visible));
}
""",
    ])


def tint(color):
    alpha = ((color >> 24) & 255) / 255
    return [1 - alpha + alpha * ((color >> shift) & 255) / 255 for shift in (16, 8, 0)]


def same_component(actual, expected):
    if math.isnan(expected):
        return math.isnan(actual)
    return math.isfinite(actual) and abs(actual - expected) <= max(1e-13, abs(expected) * 1e-6)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mutate", action="store_true", help="Deliberately flatten texture to test failure sensitivity")
    parser.add_argument("--reference", action="store_true", help="Use the previous generic 64-bit neighborhood query")
    parser.add_argument("--initial-integrand", action="store_true", help="Check color-only initial candidate evaluation and require exact-zero rays to skip traversal")
    parser.add_argument("--reference-evaluation", action="store_true", help="Use the original visibility-first evaluator for the initial-integrand cases")
    args = parser.parse_args()
    context = moderngl.create_standalone_context(require=430)
    world, palette, colors = scene()
    world_buffer = context.buffer(struct.pack(f"{len(world)}I", *world))
    palette_buffer = context.buffer(b"".join(struct.pack("4I", *texel) for texel in palette))
    count = 2048 if args.initial_integrand else 256
    output = context.buffer(reserve=count * 16)
    trace_counts = context.buffer(reserve=count * 4)
    world_buffer.bind_to_storage_buffer(0)
    palette_buffer.bind_to_storage_buffer(1)
    output.bind_to_storage_buffer(2)
    trace_counts.bind_to_storage_buffer(3)
    for mode in ("BLOCK", "VOXEL", "NONE"):
        trace_counts.write(bytes(count * 4))
        shader = context.compute_shader(shader_source(mode, args.mutate, args.reference, args.initial_integrand, args.reference_evaluation))
        shader["ph_world_voxel_buffer"].binding = 0
        shader["ph_palette_texture"].binding = 1
        shader.run(group_x=count // 64)
        context.memory_barrier()
        actual = list(struct.iter_unpack("4f", output.read()))
        failures = []
        for i, sample in enumerate(actual):
            x, y, variant = i % 16, (i // 16) % 16, i // 256
            visible = mode != "NONE" or (x % 4 == 3 and y < 8)
            expected = tint(colors[x % 4])
            if y >= 8:
                expected = [a * b for a, b in zip(expected, tint(0xA080C0F0))]
            expected = [component * energy for component, energy in zip(expected, (2, 3, 4))] if visible else [0, 0, 0]
            if variant in (1, 3, 4, 5):
                expected = [0, 0, 0]
            if variant == 6:
                expected = [component * 1e-8 for component in expected]
            if variant == 7 and visible:
                expected = [math.nan] * 3
            wrong_color = any(not same_component(a, b) for a, b in zip(sample[:3], expected))
            if (not args.initial_integrand and sample[3] != float(visible)) or wrong_color:
                failures.append((x, y, sample, expected, visible))
        assert not failures, f"{mode}: {len(failures)} incorrect ray results; first={failures[:3]}"
        calls = struct.unpack(f"{count}I", trace_counts.read())
        if args.initial_integrand:
            expected_calls = 2048 if args.reference_evaluation else 1024
            assert sum(calls) == expected_calls, f"{mode}: radiance correct but expected {expected_calls} traces, got {sum(calls)}"
        print(f"PASS {mode}: {count} rays match expected RGB throughput; {sum(calls)} visibility traces")
    print(f"GPU: {context.info['GL_RENDERER']}; scene={len(world)} uints")


if __name__ == "__main__":
    main()
