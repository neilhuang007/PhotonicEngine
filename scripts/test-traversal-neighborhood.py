"""Execute the production GLSL neighborhood query against an independent oracle.

Run: uv run --python 3.12 --with moderngl python scripts/test-traversal-neighborhood.py
Requires an OpenGL 4.3 context. This tests occupancy decisions, not frame time.
"""
from pathlib import Path
import argparse
import random
import struct
import moderngl

ROOT = Path(__file__).resolve().parents[1]
TYPES = ROOT / "modules/shaders/photonics/internal/tracing/types.glsl"


def function(source, name):
    start = source.rfind("\n", 0, source.index(name)) + 1
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", action="store_true", help="Execute the previous generic 64-bit query")
    parser.add_argument("--mutate", action="store_true", help="Test sensitivity: deliberately ignore the upper child-mask word")
    args = parser.parse_args()
    source = TYPES.read_text()
    production = function(source, "ph_shift_right_64")
    if not args.reference:
        production += "\n" + function(source, "ph_neighborhood_is_empty")
        expression = "ph_neighborhood_is_empty(mask, child)"
    else:
        expression = "(ph_shift_right_64(mask, child & 42u).x & 0x00330033u) == 0u"
    if args.mutate:
        expression = expression.replace("mask,", "uvec2(mask.x),")
    rng = random.Random(78125)
    masks = [0, (1 << 64) - 1]
    masks += [1 << bit for bit in range(64)]
    masks += [rng.getrandbits(64) for _ in range(512)]
    # Sparse random masks exercise empty neighborhoods; dense masks rarely do.
    masks += [sum(1 << bit for bit in rng.sample(range(64), rng.randrange(1, 9))) for _ in range(512)]
    cases = [(mask, child) for mask in masks for child in range(64)]
    expected = []
    for mask, child in cases:
        x, y, z = child & 3, child >> 4, (child >> 2) & 3
        occupied = any(mask & (1 << (xx + 4 * zz + 16 * yy))
                       for xx in range(x & ~1, (x & ~1) + 2)
                       for yy in range(y & ~1, (y & ~1) + 2)
                       for zz in range(z & ~1, (z & ~1) + 2))
        expected.append(int(not occupied))
    context = moderngl.create_standalone_context(require=430)
    inputs = context.buffer(b"".join(struct.pack("4I", mask & 0xffffffff, mask >> 32, child, 0) for mask, child in cases))
    outputs = context.buffer(reserve=len(cases) * 4)
    inputs.bind_to_storage_buffer(0)
    outputs.bind_to_storage_buffer(1)
    shader = context.compute_shader("""#version 430
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer Inputs { uvec4 cases[]; };
layout(std430,binding=1) writeonly buffer Outputs { uint answers[]; };
""" + production + """
void main() {
    uint i=gl_GlobalInvocationID.x;
    if (i >= cases.length()) return;
    uvec2 mask=cases[i].xy;
    uint child=cases[i].z;
    answers[i]=uint(""" + expression + """);
}
""")
    shader.run(group_x=(len(cases) + 63) // 64)
    context.memory_barrier()
    actual = struct.unpack(f"{len(cases)}I", outputs.read())
    wrong = [(cases[i], expected[i], actual[i]) for i in range(len(cases)) if expected[i] != actual[i]]
    assert not wrong, f"{len(wrong)} incorrect skip decisions; first={wrong[:3]}"
    print(f"PASS: {len(cases)} actual GLSL neighborhood decisions on {context.info['GL_RENDERER']}")


if __name__ == "__main__":
    main()
