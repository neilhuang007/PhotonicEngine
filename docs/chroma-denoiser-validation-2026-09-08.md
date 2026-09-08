# Chroma denoiser validation, 2026-09-08

## Scope and diagnosis

The previous spatial filter used receiver geometry, visibility, and luminance to stop taps. A stable projected color feature can share all of those guides with its surroundings. The initial actual `sv2_atrous.fsh` OpenGL fixture retained only 17.35% of a three-pixel colored stripe after five passes. This demonstrates a missing chromatic edge guide, not a defect in reservoir splatting or the mixed-light estimator.

An initial chroma guide using the existing luminance variance preserved the stripe but also retained 99.96% of an isolated chroma outlier. That experiment was insufficient: scalar luminance moments cannot measure fluctuations in their color nullspace. The implemented experiment therefore adds independent temporal chroma moments rather than relying on that retune.

## State and filtering semantics

- Optional `chroma_history`, RGBA32F, stores raw Co/Cg first moments in XY and their raw second moments in ZW. It uses the same accepted reprojection taps, normalized weights, age, and accumulation alpha as the existing history. Nonfinite chroma state rejects the complete corresponding history tap.
- Exposure changes multiply first moments by the exposure ratio and second moments by its square. Color clipping leaves raw moments untouched. Anti-lag resets blend chroma moments toward the fresh observation with exactly the same reset amount used for luminance moments and history confidence.
- Chroma output variance is `max(M2 - M1*M1, 0) / max(age, 1)`. An optional RG32F `denoise_chroma_variance` attachment carries the two variances through the spatial passes. A-trous variance propagation uses squared filter weights, matching the existing scalar variance convention.
- The spatial color guide uses the largest normalized luminance/Co/Cg difference; each chroma component has its own variance-derived bandwidth. Geometry, hand/surface classification, visibility guides, packed sample metadata, and raw luminance moments retain their existing meanings.
- For history younger than four frames, the prefilter first estimates geometry-compatible neighborhood moments before applying color weights. This avoids an outlier rejecting all neighbors using its own unreliable one-sample variance. That uncertainty also informs the existing fresh luminance reconstruction. Mature history keeps its center color unchanged in this prefilter.
- Both new attachments use the existing framebuffer flip/resize/destruction lifecycle and are enabled only when denoising is enabled. The undo-exposure pass explicitly initializes its auxiliary output. Zero-pass accumulation emits no chroma output and requires no chroma-history sampler.

Two ping-ponged RGBA32F/RG32F attachments add **48 bytes per rendered pixel**: about 18.8 MiB at 854×480, or 94.9 MiB at 1920×1080. This is a material memory/bandwidth cost. No native GPU speedup is claimed.

The conservative global world/light revision validity check is unchanged. Active clocks can still invalidate every temporal history repeatedly. Fresh reconstruction improves that fallback's outlier handling, but cannot recover temporal evidence or guarantee preservation of real fine lighting detail while the world continually resets. A validated local replay/change signal remains separate work; reservoir validity must not be relaxed casually.

## Actual GPU regression

Run from the repository root:

```powershell
uv run --python 3.12 --with moderngl --with numpy python scripts/test-svgf-gpu.py
```

The approximately two-second fixture executes production accumulation, variance-prefilter, and five a-trous fragment shaders in a hidden OpenGL 4.3 context. Receiver geometry, reprojection coordinates, and exposure are synthetic adapters. The exercised GPU is **Intel Arc Graphics**, not the NVIDIA RTX 4070 Laptop GPU used by the native game fixture. Preexisting `1f`/`4f` literal spellings in `history.glsl` were made valid decimal float literals because this stricter compiler rejected them.

Observed results:

| Check | Result |
| --- | ---: |
| Stable three-pixel projected chroma contrast retention | 99.968% |
| Fluctuating isoluminant noise MSE, spatial / temporal-only | 0.002903 |
| Fresh bright outlier residual contrast after filtering | 0.0618% |
| Cross-plane leakage, 1/16-block offset | 0.01326 absolute radiance |
| Different-normal leakage | -0.0000488 absolute radiance (half-float rounding) |
| Raw chroma recurrence against CPU reference | Pass, tolerance 0.00002 |
| Exposure scaling and global invalidation | Pass |
| Anti-lag reset moment error against confidence inferred from packed age | 0.0000486 |
| Rejected reprojection tap rejects matching chroma state | Pass |
| Fractional bilinear reprojection with one/two rejected taps | Maximum moment error 0.00000000272 |
| Zero-pass accumulation and undo-exposure shader linking | Pass |

The noise fixture fluctuates in a direction orthogonal to both spatial Rec709 luminance and anti-lag YCoCg Y, allowing a separate NumPy raw-moment reference without incidental anti-lag resets. A separate decisive colored-light step exercises coherent anti-lag reset. Fractional reprojection uses nonuniform moments, a (0.25, 0.375)-pixel offset, and independent CPU renormalization after rejecting one or two bilinear taps. The geometry fixture checks a parallel thin offset and a different normal, not arbitrary Minecraft thin geometry or camera motion.

Both explicit shader fault injections were run and failed on the intended image symptom:

```powershell
uv run --python 3.12 --with moderngl --with numpy python scripts/test-svgf-gpu.py --fault luma-only
# AssertionError: Converged narrow colored projection lost 81.4% contrast

uv run --python 3.12 --with moderngl --with numpy python scripts/test-svgf-gpu.py --fault chroma-self-lock
# AssertionError: Isoluminant temporal noise was locked as detail: MSE ratio 1.0
```

The fault switches alter only the compiled test shader source, not repository shader files. The final full-pipeline luma-only fault differs from the initial a-trous-only fixture, so their retention figures should not be treated as identical measurements.

## Native Photon validation

The actual native Photon pack compiled and ran the combined traversal/candidate
and chroma changes on the NVIDIA RTX 4070 Laptop GPU at 854×480. The controlled
fixture used frozen ticks, 32 initial candidates, four spatial-reuse samples,
combined GI, five denoiser passes, render distance 8, yaw 180 and pitch 20.
Pack archives and test thresholds were unchanged.
The timing/quality sweep in this section preceded the independent CPU atlas
sampler fix; final compatibility checks below include that fix as well.

| Per-pass GPU median, ms | Baseline | Combined changes |
| --- | ---: | ---: |
| Initial direct candidates | 12.229 | 11.397 |
| Total direct lighting | 21.933 | 20.901 |
| Total denoiser | 1.217 | 1.884 |

The added denoising state/guide costs approximately 0.67 ms in this comparison.
Separate isolated traversal measurements varied, including unchanged passes;
this is not proof of a general frame-rate improvement. The captures use
synchronous ROI readback, so reported FPS is not a suitable benchmark.

**Both native quality runs failed.** The unchanged-region temporal noise-growth
guard reported 95.9% for baseline and 79.7% for the combined changes (limit 10%).
The absolute reported before/placed variances decreased from
2.430e-7/4.759e-7 to 1.956e-7/3.515e-7, but each report selected its own lighting
mask. All other controlled edit-response, endpoint-error and paired-edge gates
passed. These short-window results do not establish overall clean lighting.

A separate comparison used bit-identical receiver geometry and the same pooled
raw endpoint reference for both runs (31,802 common pixels). RGB RMS was
essentially unchanged: 0.008382→0.008391 before and 0.008647→0.008655 placed.
Fine raw chroma gradients had only 0.098–0.131 cosine agreement between runs;
after 9×9 smoothing this rose to 0.744–0.796. Sixteen-frame endpoint means are
too noisy to establish preservation of native glass texels. Coarse chroma
gradient changes were mixed. The synthetic detail result must not be presented
as a demonstrated native glass-texture fix.

With normal world ticking, both builds also failed. Baseline had insufficient
changed pixels/paired edges, seven-frame removal response (limit three), and
35.8% endpoint error (limit 25%). Combined changes had 69.5% placement-step
retention (minimum 75%), six-frame removal response, insufficient paired edges,
and 25.6% endpoint error. Noise changes were mixed. Real redstone-clock edits
invalidated history on 109/128 measured baseline frames and 122/128 combined
frames, versus 2/128 for the frozen baseline. Identical section-input and light
list suppression already exist; these are not simply duplicate uploads.

Evidence is retained under `tmp/rendering-improvements-20260908/` in
`baseline-room-p5`, `final-room-p5`, `baseline-room-active-p5`,
`final-room-active-p5`, and `native-quality-comparison.{md,json}`. The latter
analysis and its Python script use a shared geometry mask and record reference
uncertainty explicitly. Existing finite-window noise and penumbra limitations
also apply, as described in `denoiser-validation-2026-09-07.md`.

## Integration and compatibility checks

The complete JVM suite and Fabric compilation passed after the sampler fix:

```powershell
.\gradlew.bat :modules:core:test :modules:versions:mc12111:common:test :modules:versions:mc12111:fabric:compileJava --console=plain
```

The local suite contained 88 core tests and 12 Minecraft-version common tests,
with no failures or skips. The common suite includes preexisting uncommitted
resize tests; those tests and their associated user changes are not part of
this checkpoint's commits.

Native `Shrimple-ph-0.4.zip` **passed** its standard stationary/moving-camera
test with four denoiser passes. Native Photon **passed** the same standard
camera protocol with zero denoiser passes, finite/bounded outputs and reservoir
confidence accumulation. These runs exercised the packaged engine shaders,
the new CPU sampler, the alternate pack's attenuation/material hooks, and the
zero-pass path with optional chroma attachments disabled. Their archive hashes
were respectively `34415770bbcaa97ce4dbbab10a467fbb2cb3aa3e95f87dc86af0d21a6e111ed2`
and `f462652e47068765f4043ec1cf3c46bfd70b5588c8549a1f5b343d57b526fb9f`.

An earlier zero-pass run using the separate floor-room camera override compiled
and produced finite/bounded output, but **failed** the camera-translation
chromaticity gate. Its evidence is retained as `final-photon-zero-pass`, not
reclassified as a success. The passing runs are `final-shrimple-standard` and
`final-photon-zero-pass-standard-camera` in the same evidence root.

The standard camera is (-4.1200105786, -47, 49.5862486405), yaw 180, pitch 20,
followed by a 1.5-block translation. Commands:

```powershell
.\scripts\run-shader-game-test.ps1 -TimeoutSeconds 420 -ShaderPack Shrimple-ph-0.4.zip -GradleArgs '-PshaderGameTestScenario=standard','-PshaderGameTestRenderDistance=8','-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestFreezeTicks=true'
.\scripts\run-shader-game-test.ps1 -TimeoutSeconds 420 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestScenario=standard','-PshaderGameTestDenoiserPasses=0','-PshaderGameTestRenderDistance=8','-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestFreezeTicks=true'
```

The final controlled five-pass Photon run included **all** changes, including
the CPU sampler (`final-all-changes-room-p5`). It again compiled and passed
response, paired-edge and endpoint gates: placement/removal response took
1/0 frames after GPU-visible geometry, and placed endpoint relative RMS was
18.78%. It still **failed** the noise-growth guard at 83.7%. GPU medians were
11.796 ms initial DI, 22.543 ms total DI and 1.890 ms denoising. The final
total-DI time exceeds the original 21.933 ms observation; there is no supported
claim of a general speedup at the unchanged 32-candidate budget. The final run
left the normal Photon settings at 32 candidates and five denoiser passes.
