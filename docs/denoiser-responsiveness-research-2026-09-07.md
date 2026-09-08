# Denoiser responsiveness and sharpness research

Research date: 2026-09-07. Repository inspected at HEAD `78af85e5515d485c9ae369672e53f5f6e1b830c9`, with concurrent uncommitted work. This document records the initial research and test-design pass, before implementation. Source line numbers below describe that inspected snapshot. The subsequently authorized RELAX-style implementation and actual measurements are recorded in [the validation report](denoiser-validation-2026-09-07.md); use [the game-test guide](denoiser-game-test.md) for the implemented commands.

## Recommendation

Build a **compact RELAX-inspired diffuse radiance denoiser, with RTXDI-style lighting-change confidence and Minecraft edit-driven invalidation**. Keep the existing SVGF infrastructure as the starting point. Separate direct lighting from indirect lighting so sharp shadows can react immediately while noisier GI accumulates longer. Compare a REBLUR-inspired alternative only through matched quality/performance measurements.

The most directly relevant algorithm to borrow for responsiveness is already in this repository: [RTXDI's denoiser-confidence explanation](../reference/repos/RTXDI/Doc/Confidence.md) and its [ComputeGradients](../reference/repos/RTXDI/Samples/FullSample/Shaders/DenoisingPasses/ComputeGradients.hlsl), [FilterGradients](../reference/repos/RTXDI/Samples/FullSample/Shaders/DenoisingPasses/FilterGradientsPass.hlsl), and [ConfidencePass](../reference/repos/RTXDI/Samples/FullSample/Shaders/DenoisingPasses/ConfidencePass.hlsl). These adapt A-SVGF's temporal gradients to retained light reservoirs. Replaying a light sample is more appropriate here than replaying only an RNG seed: the same seed does not reproduce a sample after reservoirs or the light list change. The [official RTXDI explanation](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/Confidence.md) describes this distinction and the required scene state.

This is an engineering recommendation, not a demonstrated fastest/highest-quality result for this renderer. A library's benchmark on another scene, API, GPU, and signal cannot establish that. The companion [primary-source assessment](denoiser-primary-sources-2026-09-07.md) compares RELAX, REBLUR, SIGMA, FidelityFX, A-SVGF, and neural alternatives, with source and version details.

SIGMA and AMD's shadow denoiser are useful for a **separate per-light shadow/penumbra signal**. They cannot directly replace denoising of mixed RGB ReSTIR many-light irradiance. Colored glass and several differently colored emitters make that distinction particularly important here. Current NRD/RTXDI source license terms must be checked at the chosen revision before literal code copying; implement the published ideas independently where appropriate.

## What the current code actually does

| Finding | Evidence and implication |
| --- | --- |
| Changed GPU scene data already resets history | [sv0_accumulation.fsh](../modules/shaders/photonics/rendering/restir/svgf/passes/sv0_accumulation.fsh), lines 43–50, skips reprojection when `ph_reservoir_splatting_history_valid == 0`. The missing feature is not simply a global reset on block edits. |
| The reset is global and shared with reservoir validity | [ReservoirSplattingRendering.java](../modules/core/src/main/java/at/redi2go/photonics/core/rendering/restir/splatting/ReservoirSplattingRendering.java), lines 174–195, compares whole world/light generations. The prior [rendering audit](rendering-audit-2026-09-07.md) documents distant redstone clocks repeatedly resetting history. Those observations are earlier evidence, not a new measurement from this research pass. |
| Direct shadow visibility is not populated in the guide | [di3_validate_visibility.fsh](../modules/shaders/photonics/rendering/restir/direct/passes/di3_validate_visibility.fsh) initializes `di_output.a` to 1 and only changes RGB. Accumulation uses `min(di_output.a, gi_output.a)`. With GI disabled, the shadow guide is always 1 and the spatial shadow-stopping weight is always 1. With GI enabled, it is a GI guide, not DI shadow visibility. Actual DI occlusion is present in the RGB integrand. |
| DI and GI share filtering | `sv0_accumulation.fsh`, lines 61–62, adds DI and GI before moments, history, and spatial filtering. Their different response/noise requirements cannot be tuned independently. |
| There is already a fast history, but it is not an immediate change detector | [history.glsl](../modules/shaders/photonics/rendering/restir/svgf/history.glsl), lines 174–211: with configured history 32, mature main history uses alpha `1/33`; fast history uses `1/9`. The main RGB is clamped to `[fast/1.5, fast*1.5]`. This multiplicative box does not use local variance or identify affected shadows. |
| Spatial filtering has little adaptation | [sv2_atrous.fsh](../modules/shaders/photonics/rendering/restir/svgf/passes/sv2_atrous.fsh) uses 9 taps per iteration at strides 1, 2, 4, etc. `get_pass_weight()` returns 1 whenever iteration < configured pass count, which is every scheduled iteration. Thus its apparent age-based fallback is not exercised by normal scheduling. |
| The spatial depth guide is view-dependent | The same pass uses `exp(-abs(linearDepth0-linearDepth1)/0.5)` with no slope/footprint scaling. On a receding flat floor, same-plane neighbors differ in view depth. Test plane-distance guidance instead. Temporal reprojection already has a geometric normal and plane-distance test; that is a different stage. |
| Fresh-history variance deserves attention | [sv1_variance_prefilter.fsh](../modules/shaders/photonics/rendering/restir/svgf/passes/sv1_variance_prefilter.fsh) averages existing variances across a 3x3 neighborhood without surface compatibility, rather than estimating new local moments for insufficient history. It preserves the center RGB. Compare the reference's history-under-4 spatial moment reconstruction in [SVGFFilterMoments.ps.slang](../reference/repos/Reservoir-Splatting/Source/RenderPasses/SVGFPass/SVGFFilterMoments.ps.slang), lines 51–122. A larger blur alone is not a solution. |
| The actual fixture is already using fewer passes | [RestirProperties.java](../modules/core/src/main/java/at/redi2go/photonics/core/iris/rendering/restir/RestirProperties.java) defaults to history 32 and 5 passes. The inspected Photon fixture and existing report use **2** passes; Shrimple's configuration is different. Do not advertise five-to-two as a new optimization already proved by this pass. |

An analytical scalar step experiment makes the temporal limitation concrete: mature main history alone takes 75 frames to reach 90% of a sudden change at alpha 1/33. Simulating the current fast-history clamp gives 64 frames for 0→1 and 23 for 1→0. This assumes **no invalidation, constant exposure, no noise, and no spatial filtering**. It is not the measured block-placement delay: the existing GPU-scene reset should bypass this tail for recognized edits. Its purpose is to show why unrecognized changes cannot rely on the fast clamp alone.

The existing estimator already resolves incident light separately from source emission, and the native pack applies the surface material. Preserve that arrangement; do not denoise the final albedo-textured image to suppress lighting noise. Spatial denoising currently stores the texture normal for world surfaces (`data1.z`) while temporal validity uses geometry normals. A cube-face optimization needs an explicit geometric guide, while retaining mapped-normal handling where shading requires it.

## The response budget spans more than the denoiser

The path to measure is:

`block action → server/client block state → section rebuild → voxel/light upload → raw lighting → denoised lighting → shaderpack temporal resolve → final frame`

[SectionManager.java](../modules/core/src/main/java/at/redi2go/photonics/core/rendering/SectionManager.java) queues changed sections. [WorldCompiler.java](../modules/core/src/main/java/at/redi2go/photonics/core/rendering/world/compiler/WorldCompiler.java), lines 114–140 and 268–293, processes batches and advances content generation after a completed upload is submitted at frame begin. A denoiser cannot cast a correct shadow from geometry that is not in the traced scene yet. If this stage dominates, prioritize the locally edited section, coalesce repeated rebuilds, or evaluate a small edit overlay for ray queries before redesigning filtering. An overlay is an architecture option, not implemented functionality.

Primary sun shadows are also partly owned by the native shaderpack; `DOCUMENTATION.md` describes the sun/shadow interface and its GI use. The audit records a native shadow-map coverage problem at a two-chunk render distance. Test native sun, block-light DI, GI, and handheld lighting as distinct sources. Clearing the Photonics history will not necessarily clear the pack's TAA history.

## Minecraft-specific design

### 1. Give edits an immediate, spatially meaningful response

Publish changed block bounds, light identity/state changes, an edit ID, and the GPU-visible generation/frame for those changes. Attach this information to actual uploaded changes, not just client requests or an unrelated generation increment.

Use it to construct a **receiver-space reactive mask**. A caster can be off screen and its shadow can land many blocks away. Projecting only the edited block's screen rectangle, or checking only the receiving section's version, misses this case. For localized direct lights, conservatively cover receivers within affected light influence and changed caster-to-light shadow volumes. For a directional light, cover the projected extrusion of the edited bounds along the light direction. Include old and new bounds for removals and moving objects; expand for area-light penumbrae and temporal/spatial filter footprints. GI requires broader conservative treatment because several transport segments may be affected.

On confirmed affected pixels, replace old DI radiance immediately and reset/update its moments, age, and fast history coherently. Keep a short recovery window (an initial experiment: 2–4 frames) in which current data receives high weight and extra fresh samples can be allocated. This prevents a single noisy reset frame from dominating the next long accumulation. Hold unaffected radiance history where the affected-region computation supports doing so.

**Separate this denoiser confidence from reservoir validity.** Keep the existing global reservoir reset until the retained-path/MIS state contract is satisfied. The implementation has no complete previous world/light scene for reverse-shift evaluation. A locally calm image or an unchanged receiving face is not proof that both shift directions are valid. It is possible to keep conservative reservoir resets while independently improving denoiser radiance history; removing the reset is a separate estimator change.

### 2. Add sparse sample-replay confidence where events are insufficient

Use the RTXDI/A-SVGF approach for moving lights, changing emission, motion, and missed/nonlocal changes: re-evaluate a retained sample's light ID, UV, surface, and relevant shading attributes in the current scene and compare its current result to its saved result. Account for exposure and the correct jitter domain. Store actual old shading; do not compare two independently resampled pixels and call their Monte Carlo difference a lighting change.

The official RTXDI confidence document explicitly states that **forward replay of a previous sample** is the meaningful option without a previous scene BVH. A current sample evaluated backward needs previous light/scene data. Start with the forward option in this voxel renderer; do not pretend it supports full bidirectional scene replay. Deleted lights or unavailable history require conservative confidence handling.

Evaluate sparse strata and filter the confidence signal, rather than adding one extra shadow ray to every pixel unconditionally. Benchmark 3x3 strata against an edit-only baseline. Thin shadows can fall between sparse samples, so known edits must override this approximation. Fast attack and brief recovery suit edit responsiveness better than slowly averaging the reset signal. The current reservoir confidence cap is an estimator property, not a denoiser sample count or proof of independent samples.

### 3. Filter direct and indirect lighting independently

- DI: preserve high spatial frequencies and use immediate edit response. Initial experiments: stable history caps 8 and 16, a 1–2-frame fast history, and two spatial strides (1 and 2). These are hypotheses to compare, not tuned defaults.
- GI: allow a longer stable history (compare 16 and 32), more spatial support, and conservative reset for changed transport. Half-resolution GI plus guided upsampling is a later bandwidth tradeoff; retain full-resolution DI for block/shadow edges first.
- Share geometry/motion guides and dispatch infrastructure. Two signal histories do increase storage/bandwidth; they need not imply blindly duplicating every full-screen pass.
- Borrow RELAX's statistical fast/slow history-clamping and disocclusion-repair ideas. Use compatible fast-history neighborhood statistics and variance; do not restore the previously removed rule that clips every bright center to its neighbors' maximum. Small bright emitters and glass projections are legitimate features.
- Allocate a few additional **fresh** samples to affected/disoccluded pixels before widening the spatial filter. Immediate response, very low sampling, and perfectly sharp noise-free output are competing requirements; measure the ray budget and filter budget together.

### 4. Exploit planes and categorical geometry without introducing seams

For confirmed full opaque cubes, six geometric face directions and exact block coordinates provide cheap stable surface cues. Use geometric face orientation plus a point-to-plane distance such as `abs(dot(Pneighbor-Pcenter, Ngeometry))`, with a tolerance tied to reconstruction precision/footprint. On the same face plane this avoids rejecting a floor merely because it recedes in view depth. The local [light-tree filter reference](../reference/shaders/lighttree/light_tree_indirect_denoising.fsh), lines 79–80, already illustrates this plane test; its name/include is not evidence that it is the NRD SDK.

Use block/face identity for temporal correspondence and detecting replaced surfaces. **Do not hard-separate every block in the spatial filter**: adjacent compatible coplanar blocks should share samples or the result can acquire a block grid. Plane equality alone also cannot preserve shadows cast onto one flat plane. Add compatible radiance/chromatic/visibility guidance where it represents the signal reliably.

Keep a general path for stairs, slabs, fences, cutout foliage, translucent glass, water, entities, and normal-mapped surfaces. Material classes should preserve transmission and incompatible surfaces without treating every texture texel as a different filter domain. Colored-glass changes require RGB/chromatic validation; equal luminance does not mean equal illumination.

### 5. Use specialized shadow denoising only where the signal supports it

For a small explicit set of lights, current-frame deterministic visibility can respond immediately. For sampled area-light/sun penumbrae, SIGMA-style blocker-distance and light-angular-size guidance is a useful model. A point/directional hard-shadow sample is not the same target as a soft area-light shadow. Benchmark the intended light model and retain physical penumbra width.

For general many-light ReSTIR, keep denoising the properly weighted visible RGB estimate. In general, `E[unoccluded lighting × visibility]` is not `E[unoccluded lighting] × E[visibility]`. Do not multiply smoothed summed irradiance by a selected light's binary visibility: that light is not the whole sum. Nor should `di_output.a` be populated with a convenient but semantically unrelated reservoir scalar just to activate the existing edge weight. A shadow guide needs a defined, compatible signal and tests.

### 6. Reduce bandwidth after the response and edge tests exist

[RestirPipeline.java](../modules/core/src/main/java/at/redi2go/photonics/core/iris/rendering/restir/RestirPipeline.java), lines 149–186, has temporal accumulation, a variance prefilter, N spatial passes, and a final exposure conversion. Five spatial passes mean 45 stencil sample positions per interior pixel; two mean 18. That is a 60% reduction in spatial tap count, **not a measured 60% reduction in denoiser or frame time**. The inspected Photon fixture already uses two.

Potential next savings:

- Compute filtered output only on tiles/pixels that need it; fixed pass count currently does not adapt to age. Branching alone still incurs dispatch and writes, so measure real GPU time.
- Evaluate folding variance preparation into the first spatial pass and exposure conversion into the final output/consumer. Preserve texture encoding, ping-pong ownership, barriers, and the zero-pass raw contract.
- Avoid repeatedly storing normal/depth metadata in the color ping-pong buffer if shared existing guides and tile caching cost less. Evaluate RGBA16F radiance/variance plus compact age/confidence, with sufficient moment range and precision. Keep full-precision geometry reconstruction where needed.
- Use shared-memory tiles in GLSL compute if profiling shows bandwidth wins. Any fused neighboring computation needs valid halos and synchronization; an in-place cross-workgroup temporal/spatial pass is not safe.

## Test plan using runShaderGameTestClient

### What works now

The documented launcher is [scripts/run-shader-game-test.ps1](../scripts/run-shader-game-test.ps1). It invokes `:modules:versions:mc12111:fabric:runShaderGameTestClient`, Quick Plays the `backup` world, installs the selected native fixture, and uses packaged engine shaders from the same Java/resource build. The [integration notes](../scripts/SHADER_GAME_TEST_AGENT.md) describe its artifact, shader-failure, and world-lock contracts.

Run the normal Photon scenario with ordinary ticking:

```powershell
.\scripts\run-shader-game-test.ps1 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestRenderDistance=8'
```

For tracing/scene-upload diagnostics, use a separate run:

```powershell
.\scripts\run-shader-game-test.ps1 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestRenderDistance=8','-PshaderGameTestTraceStartup=true','-PtraceSceneChanges=true','-PtraceLighting=true'
```

Also run the native Shrimple configuration:

```powershell
.\scripts\run-shader-game-test.ps1 -ShaderPack Shrimple-ph-0.4.zip -GradleArgs '-PshaderGameTestRenderDistance=8'
```

Use `-PshaderGameTestFreezeTicks=true` only for a separately labeled static diagnostic. It cannot validate block-edit responsiveness or the active-clock world. All game runs sharing `run/saves/backup` and `run/automation` must be serialized. The runner refuses overlapping world use and owns a mutex; do not bypass them. During concurrent development, build a stable snapshot with the other editor's intended state, or use a separate checkout/run directory. Preserve shaderpack archive hashes; settings belong in normal `.zip.txt` files, and these are already being edited by another agent.

The current reporter captures raw DI, optional GI, denoised output, reservoirs, final PNGs, and scene generations. It retains 6 stationary A captures, 12 movement captures, and 6 stationary B captures. However, it runs from `END_CLIENT_TICK`, warms up for 240/100 ticks, and uses four skipped ticks between stationary captures. It **does not place/remove a block**, retain a per-render-frame event timeline, or assert local shadow latency/edge width. Mean brightness and chromaticity can pass with a local stale or blurry shadow. Its generic configuration check also currently requires GI enabled; a DI-only experiment needs a scenario-specific contract, not silently bypassing the check.

### Required denoiser regression scenarios — proposed, not implemented flags

| Scenario | What must remain fixed / what it catches |
| --- | --- |
| Place and remove one opaque occluder between an emitter and a visible flat receiver | Keep camera and receiver unchanged, including its depth/normal; catches lighting changes that reprojection alone cannot detect. Test both darkening and brightening. |
| Off-screen caster, visible shadow | Same fixed receiver; catches masks based only on the edited block's screen rectangle. |
| Place/remove emissive block; toggle a lamp; move a handheld light | Distinguish world changes, light-list changes, light identity mapping, and the separately rendered hand path. |
| Distant redstone clock outside the receiver's lighting influence | Compare unaffected ROI history/noise while local changes occur elsewhere; catches global denoiser churn. Keep conservative reservoir validation visible in metrics. |
| Thin fence/stair shadow and a glass-colored projection | Catches leakage, false cube assumptions, equal-luminance color smearing, and narrow features missed by sparse gradients. |
| Camera motion across a silhouette and section boundary | Catches disocclusion, wrong jitter, coordinate changes, and history leaking between surfaces. |
| Sun/roof change, DI-only, GI-only, and combined lighting | Identifies whether the limiting stage is the pack shadow map, upload, direct estimator, GI, or final temporal reconstruction. |

Use a dedicated disposable copy of the test world for mutating scenarios, or record/restore the exact edited states including any block-entity state on the integrated server thread. Schedule actual world edits, observe client propagation, and restore in completion/failure handling. Do not leave the shared `backup` world altered. A fixed fixture, camera, exposure, resolution, light model, and seeded sample sequence make comparisons repeatable; sample several independent seeds as well so one lucky sequence cannot pass the test.

### Add per-render-frame observations and meaningful assertions

Use a property-gated frame-completion hook for the exact texture being measured. A client-tick callback cannot resolve 1–3 rendered frames at common frame rates. Record the action, matching section build/upload, and the first raw/filtered/final responses using a shared frame ID. Confirm the particular edited voxel with a diagnostic ray; a world generation increment by itself could be a different redstone update.

Capture all frames from before the edit through at least the first 64 frames afterward. Keep four milestones distinct: edit observed, affected geometry GPU-visible, raw estimator response, denoised response; record the native final image separately. Timestamp CPU events with one monotonic CPU clock; GPU timing queries use their own clock and must not be subtracted directly from CPU timestamps.

Compare linear lighting after exposure normalization. The current diagnostic PNG export applies a display curve and quantization; retain float images or compute metrics from float attachments for numeric comparison. Use independent high-sample references before/after the edit, preferably fresh per-light samples in the simple DI fixture. Do not use the same temporally filtered output as its own ground truth, or assume correlated ReSTIR history is independent sample count. Existing 16-UV-per-light diagnostic probes are useful sanity checks, not guaranteed converged pixel-level references.

Initial acceptance goals, to calibrate against the fixture/reference noise:

- **Response:** on pixels known to change, normalized residual error reaches ≤10% of the before/after lighting step within 3 rendered frames of the first frame using the correct uploaded geometry. Count a sustained response over the next 3 frames, not one lucky sample. Also record end-to-end edit-to-final milliseconds, including upload; a short filter tail cannot hide a long upload delay. This is a proposed target, not an achieved result.
- **Sharpness:** measure shadow-boundary displacement and 10–90% transition width against the high-sample reference. Start with ≤1 extra output pixel at a hard-shadow edge; compare soft penumbrae to their reference width rather than forcing a hard edge. Evaluate surface boundaries and colored projections separately.
- **Noise and energy:** measure per-pixel temporal variance, spatial error, and RGB energy in matched stable/changed/unaffected ROIs. Proposed unchanged-region guard: no more than 10% noise increase over a baseline run without the unrelated edit, with confidence intervals across seeds. Never pass by making the image uniformly darker or spatially blurred.
- **State:** retain finite-value, reservoir correctness, native shader compilation, packaged-resource, and no-GUI checks. Record history length and invalidation reasons. A pass cannot silently fall back to vanilla rendering or discard inconvenient frames.

A compact report can add `metrics.denoiser` with `scenario`, `seed`, frame IDs, edit/upload/raw/filtered/final response times, residual-error curves, edge-width error, chromatic error, unchanged-ROI noise, invalidation fraction, and per-pass GPU times. These keys are proposed; the existing PowerShell runner already allows arbitrary metrics and need not be coupled to their schema.

### Measure GPU cost separately

Time tracing, temporal accumulation, each spatial pass, and the full denoiser with asynchronous OpenGL timestamp/query objects; collect completed results several frames later. Do not use the current `fpsIncludingReadbackOverhead` as a denoiser benchmark. The reporter reads full textures and writes images synchronously, and optional diagnostic rays materially change the workload. [Khronos ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt) specifies asynchronous GPU timing and explains why CPU timing around queued GL commands is insufficient.

Use a diagnostic mode for detailed image evidence and a separate profiling mode without full-frame readback/PNG encoding/ray probes. In the eventual test implementation, reduce small ROI statistics on the GPU or use delayed readback where useful. Report GPU model/driver, render resolution and scale, pack hash/settings, seed, temperature/load conditions, and median/p95 times over a stable sampling interval. Run experiments serially on an otherwise idle GPU and do not build while another agent is replacing the resource snapshot.

The minimum ablation sequence is: current two-pass Photon baseline; reset/confidence changes only; improved moment/history clamping; plane-aware two-pass filtering; separated DI/GI; optional sparse gradients; optional adaptive sampling. Change one factor at a time and compare at both matched ray budget and matched total GPU budget. Include zero-pass raw captures for diagnosis, not as a competing quality preset. Only then evaluate pass fusion/packing or full NRD integration.

## Evidence from this research pass

- Ran `.\scripts\test-run-shader-game-test.ps1`; exit code 0 and output `run-shader-game-test watchdog regression test passed`. This validates the launcher watchdog with isolated temporary fixtures, not rendering quality or GPU latency.
- Inspected the existing `run/automation/shader-game-test-report.json` and `camera-a-0.png`: native Photon, 2 executed denoiser passes, 854×480, render distance 8, **ticks frozen**, `success: true`. It is a pre-existing report from other work. It is not a new run, an unfrozen-world pass, or evidence of instantaneous block-edit response. This artifact location rotates on later launches.
- No game instance was launched and no shared world, shader, reporter, setting, or build file was modified. Only separate research notes were added. The first implementation task should be the per-render-frame placement/removal regression, followed by the smallest confidence/history change that improves it.

Snapshot SHA-256 values for the most important inspected files:

```text
history.glsl                 A52E41C66933373F834DDAB3627E53867D90F28AB0BD7845880EC450C1406C91
sv0_accumulation.fsh          DF6EE978D4F0563139D0BFEFEFE9417BCFE09F1B67943D125036ADB28AEE8ED3
sv2_atrous.fsh                0E3F4E2DD808850C679447B01BAB10D3FCD4448F001E10A846EB1A71F0531B9E
di3_validate_visibility.fsh  FE04507A6492DD1CCA4749A3600DF5557FA79C3778C94B067B218020A92F5EA7
ShaderGameTestReporter.java   4C9FAF71CEBDABE7F736E8764771B005D35B8A269F53D57F81C5B7578CCDB33E
run-shader-game-test.ps1      C7B4C5E773E3915CB5E1FE4BEC7CBC240A1979C2524FB13AD74B580B02D0C894
```
