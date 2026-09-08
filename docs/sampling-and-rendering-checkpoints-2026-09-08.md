# Sampling and rendering checkpoints, 2026-09-08

## Candidate budget is different from reservoir count

A reservoir retains a selected sample and its resampling statistics; its
candidate count is not the number of independent samples finally shaded.
Adding reservoir slots and drawing more initial candidates are different
changes. The native Photon configuration tested here already draws 32 initial
direct-light candidates per pixel, uses four spatial-reuse samples and allows
32 accumulation frames. The engine default for initial candidates is four;
Photon overrides it. The SOTA Reservoir Splatting estimator is retained.

The controlled native Photon room sweep used the combined traversal/candidate
and chroma changes, frozen ticks, 854×480, render distance 8, combined GI and
five denoiser passes on an RTX 4070 Laptop GPU. Only the ordinary Iris
`PHOTONICS_RESTIR_INITIAL_SAMPLES` setting changed. This sweep preceded the
independent CPU atlas sampler correction. GPU medians in milliseconds:

| Initial candidates | Initial DI | Total DI | Denoiser | Placed endpoint relative RMS |
| --- | ---: | ---: | ---: | ---: |
| 32 | 11.397 | 20.901 | 1.884 | 21.14% |
| 16 | 6.217 | 15.762 | 1.883 | 21.26% |
| 8 | 3.533 | 14.124 | 2.098 | 22.42% |

These are serial single runs, not controlled-clock confidence intervals. The
32→16 reduction saved 5.14 ms of direct lighting in this scene. The shorter
candidate pass at eight also reduced cost, but other unchanged passes varied.
All budgets passed the controlled response/endpoint/paired-edge gates and all
**failed** the unchanged-region noise-growth gate (79.7%, 69.9%, 79.3%; limit
10%). They have separately selected lighting masks and short raw references.
This supports testing 16 as a useful performance setting, not reducing the
production default or claiming universal quality equivalence. No persistent
sample-budget change was made.

Reports, logs and actual `.zip.txt` settings are retained in
`tmp/rendering-improvements-20260908/final-room-p5{,-samples16,-samples8}/`.
For the 16/8 runs, the already prepared native fixture's adjacent settings file
was changed and `-x :modules:versions:mc12111:fabric:prepareShaderGameTestFixture`
was appended to the controlled command in
[traversal validation](traversal-validation-2026-09-08.md), so preparation did not
overwrite that ordinary option. Subsequent normal preparation restores the
fixture settings. Pack archives were never rewritten.

## Why more samples cannot repair every symptom

The prior spatial filter was blind to isoluminant color detail; independent
chroma uncertainty now distinguishes stable color boundaries from temporal
color noise. Its executed GPU regression and native limitations are recorded
in [chroma validation](chroma-denoiser-validation-2026-09-08.md).

The native stained-glass sprite is largely uniform RGB with different alpha in
its border and a few diagonal texels. Soft-shadow sampling uses a sphere of
radius 1/16 block. Area-source integration therefore softens those tiny features
before denoising. In the tested floor geometry, some rays enter the glass's
bottom face; a copied front-face texture is not a correct expected image. The
current native endpoint references are insufficient to measure exact glass
texel preservation, even though the GPU transmission fixture preserves sampled
per-texel color/alpha.

The CPU atlas investigation subsequently confirmed incorrect normalized
texel selection and edge clamping. Those are fixed and covered through real
quad baking with alpha-only texture detail; see
[CPU sampling evidence](cpu-texture-sampling-diagnosis-2026-09-08.md). Centered
native cube UVs can avoid those bad bins, so this is a proven sampling fix
without attributing all remaining native blur to it.

World edits are another major limitation. Real redstone changes invalidated
temporal history on 109/128 measured active-world baseline frames and 122/128
combined-change frames. This repeatedly discards the temporal evidence that
would make spatial filtering both cleaner and more selective. More initial
candidates help fresh estimates but do not correct that architectural cost.

## Dynamic-world correctness boundary

Reservoir Splatting's temporal MIS needs a current canonical sample evaluated
after its inverse shift into the previous frame's target domain. The retained
old reservoir target describes its selected old sample, which is a different
quantity. See equations 10–13 of the
[Reservoir Splatting paper](https://research.nvidia.com/labs/rtr/publication/liu2025splatting/).

In this implementation, `direct/temporal_reuse.glsl` requests that reverse
evaluation through `splatting/shift.glsl`, but the shift selects the previous
camera while continuing to use current world/material/light data. Retained
reconnection data also lacks previous primary-surface identity/version and
reverse light identity mapping. Consequently the global generation check is
currently necessary; removing it would use inconsistent MIS targets.

A correct extension needs either previous tracing/material/light state and
bidirectional correspondence, or a complete conservative edit journal that
certifies every affected camera/primary/light dependency. Such a certificate
must define identical forward/inverse eligibility, remove rejected historical
support from reverse MIS, and fall back on overflow or untracked modifiers.
Receiver distance or one unchanged visibility ray alone is insufficient.
SVGF also needs its own full-lighting change-response policy; valid reuse of
one path does not certify unchanged total irradiance. This architecture was
reviewed, but these larger mechanisms were not implemented in this checkpoint.

## Reflections in game engines

Reflection appearance starts with material roughness and metalness. Practical
engines also control which surfaces receive dedicated rays, tracing resolution,
history/reconstruction budgets and the lighting used at reflected hits.
For example, Lumen starts with screen traces and falls back to scene tracing;
its default dedicated reflection threshold is roughness below 0.4, with a
rough-specular approximation above it. It supports full, checkerboard and
quarter-resolution reflection tracing. These are representative configuration
choices, not a claim that every game uses Lumen or ReSTIR. Sources:
[Epic technical details](https://dev.epicgames.com/documentation/en-us/unreal-engine/lumen-technical-details-in-unreal-engine),
[Epic performance guide](https://dev.epicgames.com/documentation/en-us/unreal-engine/lumen-performance-guide-for-unreal-engine).

In this project the iron-door image comes from the native Photon's separate
screen-space reflection/material path. Increasing Photonics DI reservoirs
cannot change its hardcoded iron roughness or repair SSR's screen-space
limitations. The controlled reflection toggle and opaque G-buffer evidence are
in [the iron-door diagnosis](iron-door-diagnosis-2026-09-08.md).

## Review record

All diagnosis, algorithm decisions and reviews used Astra, as requested. Two
independent reviews compared the changes with the starting `77809021` revision
and the user's requirements; preexisting local resize/test-harness edits were
excluded from the implementation scope.

**Standards:** no blocking architecture or hygiene finding. Optional attachments
use existing ownership/lifecycle interfaces. The reviewer identified an obsolete
luminance-only helper and a misleading local name; both were cleaned up.

**Spec:** targeted chroma reconstruction, exact tracing work reduction and door
pipeline diagnosis are implemented/tested. No approximate traversal or algorithm
replacement was introduced. Native glass sharpness and consistently clean
active-world lighting remain unproven/unresolved. The review identified missing
fractional-reprojection coverage, which was added and executed successfully.
Native results and memory/bandwidth costs are retained rather than treating
synthetic passes as completion of the visual requirements.

The subsequent CPU sampler change also received an independent Astra review:
no blockers were found in its indexing, clamping, fallback or actual-bakery
tests. Ten targeted tests changed from eight failures to zero. Additional
non-power-of-two boundary coverage was suggested as optional; the current
suite includes a three-wide texture and the production power-of-two atlas
cases. The final full local JVM suite passed all 100 tests, including the
user's preexisting common resize tests, and Fabric compilation passed.

Native Shrimple at four passes and Photon at zero passes passed their standard
stationary/moving-camera protocols. The final all-changes Photon controlled
edit run passed response/edge/endpoint gates but still failed noise growth
(83.7%). At 32 candidates its total DI median was 22.543 ms, versus 21.933 ms
for the original baseline; denoising was 1.890 versus 1.217 ms. A general
same-budget frame-rate improvement and consistently clean native output are
therefore **not established**. Full results, the earlier failed alternate-view
zero-pass run, and reproduction commands remain in the linked validation docs.
