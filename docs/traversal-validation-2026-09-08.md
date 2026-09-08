# Traversal and transmission validation — 2026-09-08

The traversal change specializes an existing empty-neighborhood query. It does
not change occupied voxels, ray budgets, skip distances, material handling,
ReSTIR weights, or the Reservoir Splatting algorithm. Native GPU measurements
below do not establish an overall speedup; fewer source operations alone are
not a performance result.

## Exact occupancy specialization

The 64 children of a node are ordered `x + 4*z + 16*y`, with each coordinate
in `[0,3]`. The traversal tests an aligned `2×2×2` neighborhood before advancing
at the next coarser scale. Its original query was:

```glsl
(ph_shift_right_64(mask, child_index & 42u).x & 0x00330033u) == 0u
```

The shift is one of `0,2,8,10,32,34,40,42`. The pattern's highest set bit is
21. Its selected neighborhood therefore fits entirely within one 32-bit word:
an aligned pair of y coordinates cannot straddle the boundary between y=1 and
y=2. The equivalent query selects `mask[child_index >> 5u]`, shifts it by
`child_index & 10u`, and tests the same pattern. Every shift remains below 32.

`ph_neighborhood_is_empty` implements that query in `internal/tracing/types.glsl`.
Both `iterator.glsl` and `ph_iterator.glsl` use it. The generic 64-bit shift
helper remains available. There is no cube classification or normal-based
geometry shortcut: doors, thin surfaces, cutouts and transmissive leaves take
the same traversal and material paths as before.

## Executed GLSL checks

These commands ran actual OpenGL 4.3 compute shaders on **Intel Arc Graphics**
using `moderngl`. This is correctness evidence, not performance evidence for the
NVIDIA GPU used by the native Minecraft runs.

```powershell
uv run --python 3.12 --with moderngl python scripts/test-traversal-neighborhood.py
uv run --python 3.12 --with moderngl python scripts/test-traversal-neighborhood.py --reference
uv run --python 3.12 --with moderngl python scripts/test-traversal-neighborhood.py --mutate
uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py
uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py --reference
uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py --mutate
```

The neighborhood fixture extracts the production GLSL helper and compares
69,760 decisions against an independent CPU oracle that enumerates the eight
xyz cells. Cases include every single occupied bit against every child, empty
and full masks, and deterministically seeded sparse/dense masks. Both current
and reference implementations pass. The mutation deliberately duplicates the
low word in place of the upper word; it fails with **8,648 wrong skip decisions**.
This mutation is an intentional test-sensitivity check, not a discovered bug in
the prior implementation.

The transmission fixture constructs a 1,115-word sparse world in the actual
node/leaf/palette buffer layout. It executes production ray traversal,
`trace_light_vis`, palette decoding, transmission accumulation, and
`direct_sample_get_visible_color_at_position` with the production default
attenuation function. Only the light-list lookup is replaced by one known
fixture light. Three packed RGBA stripes and empty cutout columns form a pane
with front/back voxel surfaces; half the rays also cross a second material
block. The independent expected result uses per-texel alpha/color filtering,
once-per-block tinting, and multiplication across separate panes.

Both current and reference traversal pass **256 rays in each of BLOCK, VOXEL
and NONE modes** (768 per run), including expected zero integrands when
transparency is disabled and expected white transmission through cutout holes.
The flattening mutation replaces sampled tint with uniform gray; it fails on
**224 of 256 BLOCK rays**. Thus the fixture detects loss of projected texture
at this seam. With the production shaders, the per-texel RGB pattern reaches
the direct-light integrand.

## Glass-detail diagnosis limits

No transmission formula was changed. These results rule out unconditional
texture flattening in the tested traversal/palette/transmission/integrand path;
they do not establish that the user's native glass-detail symptom is fixed.
The fixture bypasses CPU triangle voxelization and atlas sampling, native
shader-pack color/attenuation modifiers, reservoir sampling/reuse, denoising,
and final pack compositing. It samples one near-perpendicular direction and
does not establish coverage for every incidence angle or textured model.

A native comparison of raw direct light and filtered/final output remains
necessary to distinguish incorrect voxelized texture data from sampling,
area-light integration, filtering or compositing. No texture sharpening or
unverified material change was introduced to hide this unresolved distinction.

## Exact-zero initial-candidate rejection

The initial candidate pass previously traced visibility before evaluating
unoccluded light attenuation, even when that attenuation was exactly zero.
The new color-only `direct_sample_get_integrand` evaluates the same sampled
light position, two-sided normal orientation and pack attenuation first. Only
componentwise exact zero returns before tracing. Every nonzero, NaN or infinite
result keeps the visibility path. No hemisphere threshold independent of the
pack, small-value clamp, sample-count change or approximate visibility was added.

The existing boolean visibility API remains unchanged. That distinction matters:
`direct_reservoir_validate_visibility` clears a selected sample when visibility
is false, whereas a visible zero-color sample has different metadata behavior.
The new helper is used only by `regir_stream_local_light_sample`, which previously
ignored the boolean. It still streams each candidate with its original source
PDF and random value. `direct_reservoir_stream_sample` increments candidate count
even when the exact target weight is zero; initial normalization is unchanged.

Before changing the shader, the actual GLSL fixture was extended with six equal
groups of rays: front-facing, opaque back-facing, two-sided transmissive
back-facing, reversed shading normal, zero light color, and tangent geometric
normal. It failed the work-count assertion with **1,536 visibility calls instead
of 512**, while already producing the correct radiance. After the change, the
same fixture produced identical expected radiance with 512 calls in BLOCK,
VOXEL and NONE modes.

The final fixture additionally tests tiny nonzero (`1e-8`) light color and NaN
light color, ensuring neither is silently classified as exact zero. Commands:

```powershell
uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py --initial-integrand
uv run --python 3.12 --with moderngl python scripts/test-transmission-pattern.py --initial-integrand --reference-evaluation
```

Both evaluators pass all **2,048 expected RGB/blocked/NaN outcomes per mode**.
The color-only evaluator makes **1,024 visibility calls**; the original
visibility-first evaluator makes **2,048**. These are deliberately balanced
synthetic cases, not an estimate of the native scene's rejection rate or speedup.
The original transmission fixture still passes its 256 rays per mode with the
boolean API, retaining visibility semantics independently of the new helper.

Reordering requires material throughput to be finite for `0 × throughput = 0`,
and attenuation evaluation to have no externally visible side effects. The
tested default attenuation is pure; decoded/clamped ordinary material inputs
provide finite throughput. Inspection of the native Photon archive finds no
attenuation override and only local light-color scaling. Native Shrimple's
attenuation override and its attenuation/specular callees use local math and
read-only texture inputs, with no random-state mutation, global writes or ray
dependence. Its nonzero specular cases are not rejected by an independent normal
test: the actual complete attenuation result decides. This inspection is not
coverage of every third-party modifier or invalid texture input.

## Native timing comparison

The native Photon room fixture ran serially at 854×480 on the NVIDIA RTX 4070
Laptop GPU with render distance 8, frozen ticks, 32 initial candidates, four
spatial-reuse samples, combined GI, and five denoiser passes. A detached worktree
at `77809021` isolated these changes from the chroma denoiser. The shader-pack
archive was unchanged. Values are per-pass GPU medians in milliseconds, not FPS.

| Isolated build | Initial DI | Spatial DI | Total DI | Denoiser |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 12.373 | 6.776 | 22.030 | 1.217 |
| Occupancy specialization | 12.545 | 6.722 | 22.485 | 1.217 |
| Specialization and exact-zero rejection | 11.887 | 7.550 | 22.575 | 1.422 |

The candidate pass was faster in the rejection run, but unchanged passes also
varied substantially. Total DI did not improve in this isolated sequence. GPU
clock/power variation was not controlled, so neither a general speedup nor a
regression is established. The changes remove unnecessary work with the exact
equivalence covered above; the native benefit requires a stable benchmark.

All three native runs compiled the real pack and exercised placement/removal.
They still **failed** the existing unchanged-region noise-growth guard: 95.4%,
81.1%, and 80.7% growth respectively, against a 10% limit. Other controlled
edit-response, endpoint and edge guards passed. These short, independently
sampled runs do not establish a cross-run quality improvement.

Reports and logs are retained under
`tmp/rendering-improvements-20260908/traversal-isolated-baseline/`,
`traversal-isolated-specialized/`, and `traversal-zero-contribution/`.
The common test invocation was:

```powershell
.\scripts\run-shader-game-test.ps1 -TimeoutSeconds 420 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestScenario=denoiserResponsiveness','-PshaderGameTestDenoiserPasses=5','-PshaderGameTestRenderDistance=8','-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestFreezeTicks=true','-PprofileGpu=true'
```
