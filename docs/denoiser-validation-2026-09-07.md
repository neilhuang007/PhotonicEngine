# Denoiser implementation and validation log

Worktree: `E:/projects/PhotonicEngine-denoiser-20260907`, branch `codex/denoiser-responsiveness-20260907`, starting commit `6ff287af01b3056b9cb21fa5f2cb70f13c6452af`. The original working tree has a concurrent editor. Implementation is isolated, and game tests use a copied `backup` world.

Design sources and proposed targets are in [the repository assessment](denoiser-responsiveness-research-2026-09-07.md) and [the primary-source review](denoiser-primary-sources-2026-09-07.md). This log records actual implementation evidence separately from those proposals.

## Current assessment

The RELAX-style diffuse adaptation is implemented: three-sample responsive history, statistical YCoCg clipping, noise-aware history reset, reconstruction for history younger than four frames, and geometric plane guidance. Existing passes and attachments are reused. This is an independent GLSL implementation of selected ideas, not the NRD library or its full feature set.

The measured tradeoff is sharper retained lighting structure with more residual noise and higher per-pass cost. **No overall speedup or universally better quality has been established.** In the controlled fixture, original SVGF already responds on its first GPU-affected frame. Frequent global scene invalidation remains the limiting issue in the active-clock world; a validated replay/change signal is the next architectural step. The conservative reservoir-validity contract remains intact.

Two passes are the current quality experiment, selected explicitly in the test command. Application and pass-count defaults were not retuned from this single fixture. Photon test TAA is explicitly disabled to isolate the denoiser. Shared ignored references are accessible in both worktrees through a directory junction to the original checkout's `reference` directory.

## Baseline: raw, normal ticking

Command run by the root agent:

```powershell
.\scripts\run-shader-game-test.ps1 -TimeoutSeconds 540 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestRenderDistance=8'
```

The imported current fixture settings were `PHOTONICS_RESTIR_DENOISER_PASSES=0`, `TAA=false`, and combined GI enabled. This is an unfiltered baseline, not a measurement of a two-pass denoiser. Java and shader resources came from the same packaged snapshot.

Observed hardware: NVIDIA GeForce RTX 4070 Laptop GPU, OpenGL 4.6, driver 596.49; Intel Core Ultra 9 185H; Windows 11. Frame size 854×480, render distance 8, normal world ticking.

Result: Gradle launch succeeded, reporter/runner **failed** with `Direct reservoir confidence did not accumulate beyond the current-frame spatial baseline.` All 24 captures reported `historyValid=false`, with confidence 5. World content generations ranged from 159 to 277 across the capture sequence. Raw images visibly contain substantial sampling noise. This confirms ongoing invalidation in the dynamic fixture; it does not measure a block-placement response curve.

Evidence preserved in `tmp/denoiser-validation-20260907/baseline-raw-unfrozen/`: complete `automation` report and PNG directory, plus the launch's `latest.log`. The initial fresh-worktree dependency setup took most of the 7m37s Gradle invocation; this is not rendering time.

## Review decisions before shader changes

- Preserve conservative reservoir validity checks and mixed-light estimator weights.
- Measure shader changes against the same native pack, settings, camera, and test scene.
- The user authorized implementing RELAX-style filtering concurrently with profiling. Shader development now runs in `E:/projects/PhotonicEngine-relax-20260907`, branch `codex/relax-denoiser-20260907`, at the same starting commit. The profiling tree retains the original SVGF shaders until its comparable baseline is recorded.
- Shader experiment: geometry-aware short-history reconstruction, geometric plane-distance spatial filtering, and statistical responsive/slow history clamping with noise-aware anti-lag. Two GPT-5.6 Sol agents own disjoint temporal and spatial shader files; the root agent reviews their code and runs builds and game tests serially.
- Preserve the mature temporal variance model. Raw M1/M2 continue to describe luminance observations; clipping filtered color alone must not silently reinterpret those moments.
- Plane guidance uses geometric normals; shading normals remain a separate detail guide. Test thin geometry before generalizing the distance tolerance.
- Defer sample-replay confidence until there is a dedicated valid comparison record. Existing reservoir textures are ping-ponged within the frame and cannot be assumed to contain the previous frame at the denoiser stage.
- GPU timings use deferred query results. Root review corrected a source-index/runtime-index pass-label mismatch; the profiler now uses actual debug pass names. Quantile-window sample count is recorded separately from total samples.

The entries below preserve the development sequence. Statements about pending measurements refer to the stage described, not the final state of the branch.

## Profiler verification

`./gradlew.bat :modules:versions:mc12111:common:test --tests '*GpuPassProfilerTest' --console=plain` passed: two tests, no failures. This compiled the profiler and verified its percentile window behavior; actual GPU callback coverage still requires the game run.

The new opt-in block-edit scenario captures the Photonics output after each render, matches the raw DI+GI signal to the denoiser input, and records placement/removal events separately from global GPU scene generations. A controlled mode freezes background simulation while still applying explicit server edits. This allows the filter response to be measured separately from the dynamic-world stress case. Small synchronous ROI readbacks alter frame pacing; pass GPU timestamps, rather than FPS, are the relevant cost measurement.

## First controlled fixture launch

The root ran the native Photon fixture with two passes, TAA disabled, render distance 8, `shaderGameTestScenario=denoiserResponsiveness`, `shaderGameTestFreezeTicks=true`, and `profileGpu=true`. Java compiled and the client launched successfully. Fixture validation failed before measurement because `(-25,-46,44)` contains lime stained glass, not air. The source-lantern and receiver checks passed. The reporter restored the saved state and the client exited cleanly.

This is a harness/fixture failure, not a denoiser result. The profiler reported GPU queries available with zero dropped samples, but no completed measurement window. The test agent is inspecting the copied world data to select a verified placement cell and receiving-surface ROI before another launch.

## Implemented shader commits awaiting comparison

The clean RELAX worktree contains temporal commit `3654d8e` and spatial commit `73164ae`. They add a three-sample responsive history, statistical YCoCg clamping and noise-aware anti-lag, short-history color/moment reconstruction, and geometric plane guides. No extra full-screen pass or texture attachment is added. Root review corrected inconsistent raw-moment luminance definitions and removed ineffective age-based spatial-pass weighting. These commits have not yet been used for a game measurement.

## Matched controlled runs, protocol 1

The corrected fixture uses camera feet `(-24.011911129209686,-47,53)`, a floor-standing caster at `(-25,-47,46)`, the existing sea lantern at `(-25,-45,43)`, and receiving floor near `(-25,-48,48)`. The placement cell, floor, and camera column were verified by separately reading the correct saved-world chunks. The ROI is 257×144 pixels within an 854×480 render. Both runs used two passes, TAA off, combined GI on, render distance 8, and frozen background ticks with explicit server edits.

| Measurement | Original SVGF | RELAX-style v1 |
| --- | ---: | ---: |
| Denoiser median GPU ms | 0.524288 | 1.022976 |
| Denoiser p95 GPU ms | 0.537600 | 1.091584 |
| Accumulation median ms | 0.292864 | 0.451584 |
| Variance prefilter median ms | 0.068608 | 0.190464 |
| First spatial pass median ms | 0.065536 | 0.173056 |
| Second spatial pass median ms | 0.060416 | 0.172032 |
| Placement RMS settling frames | 23 | 19 |
| Removal RMS settling frames | 13 | 12 |
| Placed-endpoint raw/filtered RMS discrepancy | 18.73% | 19.02% |

Each GPU row contains 159 completed samples, with no dropped query samples. Direct lighting took about 24.7 ms in both runs, substantially more than the denoiser. Both runs restored the fixture and had finite captures, zero exposure drift, and zero center-geometry drift. These are single runs with separately selected confident-change masks, not confidence intervals or a statistically established quality improvement.

**Measurement correction:** protocol 1's response function was `1 - RMS(current,target)/RMS(before,target)`. Its 23/13 and 19/12 frame figures measure noise/shape convergence, not pure shadow latency. Original SVGF already changed the mean shadow brightness in its first GPU-affected frame. Protocol 2 now records signed least-squares step response separately from RMS convergence.

Protocol 1's collapsed edge measurement also failed because it mixed perspective-dependent boundaries across many rows and assumed a zero background difference. The raw finite-frame reference retained a nonzero noise/background floor. Protocol 2 measures paired per-scanline edges relative to local baselines, requires a confidently changed shadow core, and excludes changed primary geometry.

Artifacts are under `tmp/denoiser-validation-20260907/baseline-svgf-two-pass-controlled/` and `relax-two-pass-controlled-v1/`, each with the complete automation report, endpoint PNGs, and `latest.log`.

## Active-world run, RELAX-style v1

The same scenario with `shaderGameTestFreezeTicks=false` completed but failed the proposed quality gates. Of its 128 placement/removal frames, 127 had invalid history. Only 18 pixels satisfied the conservative confident-change mask, and the placed endpoint differed from the finite-frame raw reference by 26.9% RMS. Denoiser median GPU time was 0.641024 ms; the lower time reflects skipped temporal work, not better convergence. Artifacts are in `tmp/denoiser-validation-20260907/relax-two-pass-dynamic-v1/`.

This confirms that frequent global scene invalidation prevents temporal denoising from doing most of its work in the clock-heavy world. The implementation keeps conservative reservoir and lighting-history validation. A separate, validated lighting-change/replay signal is required to preserve unaffected history safely; it has not been implemented here.

## Optimization and verification in progress

Root reviewed subsequent exact-packed-normal fast paths: matching normals reuse the decoded center normal and a single plane dot; different normals use the generic path. This avoids a full-cube assumption and changes no filter tolerance, sampling count, attachment, or pass. Shader commits `824ec34` and `ef6cb6d` were integrated into the profiling branch as `e791d13` and `8d08b9d`.

The root ran 27 targeted core tests (24 existing shader regression checks and 3 temporal checks), all passing, then repeated them with the fast paths and compiled the updated Fabric reporter successfully. Actual optimized GPU timings and protocol-2 quality results still require the next game run.

## Controlled comparisons, protocol 2

All rows below used native Photon, 854×480, render distance 8, combined GI, fixed camera, frozen background ticks, and TAA disabled. The native archive SHA-256 is `f462652e47068765f4043ec1cf3c46bfd70b5588c8549a1f5b343d57b526fb9f`. These are serial, single-run observations on the RTX 4070 Laptop GPU described above, not cross-GPU results. Each denoiser total has 159 completed query samples and zero dropped samples.

| Measurement | Original SVGF, 5 passes | RELAX-style, 1 pass | RELAX-style, 2 passes (final shaders) |
| --- | ---: | ---: | ---: |
| Denoiser median GPU ms | 0.723968 | 0.761856 | 0.907264 |
| Denoiser p95 GPU ms | 0.751616 | 0.814080 | 0.972800 |
| Placement signed-step response frames after affected GPU frame | 0 | 0 | 0 |
| Removal signed-step response frames after affected GPU frame | 0 | 0 | 0 |
| Placement RMS settling frames | 13 | 20 | 18 |
| Removal RMS settling frames | 3 | 23 | 13 |
| Placed endpoint/raw relative RMS discrepancy | 21.63% | 18.00% | 17.77% |
| Placement step retention | 86.67% | 89.96% | 88.39% |
| Removal step retention | 87.36% | 90.72% | 90.08% |
| Placed temporal variance, confidently changed pixels | 2.72e-7 | 1.27e-6 | 5.46e-7 |
| Paired edge-width guard | Pass | Pass | Pass |
| Overall proposed quality gates | Fail: noise guard | Fail: noise guard | Fail: noise guard |

The final shader snapshot for this table is `8084fbd`. Its final center-input reuse preserves the equations; total GPU cost was effectively unchanged from the prior optimized two-pass result (0.906240 ms). Earlier packed-normal fast paths reduced the initial RELAX two-pass measurement from 1.022976 ms to about 0.91 ms. This is an optimization of the new implementation, **not a speedup over original SVGF**. Original two-pass SVGF measured 0.524288 ms.

The original five-pass shader baseline was deliberately restored from `5ad2358` only for that launch, while retaining the newer reporter/profiler. The RELAX shaders were restored afterward. Tracked fixture edits used for the early pass-count comparisons were also restored; the final fixture defaults to five passes and exposes a per-run override.

The independently selected confident-change masks contain 4,734, 4,902 and 4,978 pixels respectively. Native compilation, finite captures, restoration, exposure and center geometry checks passed. Both manual edits took three rendered frames to appear in the GPU/raw signal in these runs. CPU time includes synchronous readback and is not an interactive input-latency benchmark. The earlier optimized two-pass run measured 0/1 response frames, so 0–1 is the observed range, not a universal guarantee.

The edge guard measures broad area-light penumbrae in this room. Its negative median extra widths do not establish sharper physical edges: a noisy finite-frame reference can move 10% crossings substantially. Thin blockers, fences/stairs, colored-light transitions, moving cameras under block edits, and an independent high-sample reference are still needed before selecting universal quality settings.

### Noise guard interpretation

The proposed 10% unchanged-region variance guard failed both baseline and new filters. Five-pass baseline variance changed from 2.14e-7 to 2.86e-7 (+33.3%). Final two-pass RELAX changed from 4.77e-7 to 5.71e-7 (+19.6%). Earlier two-pass RELAX runs reported +30.3% and +74.2%. These percentages are not a reproducible regression ranking.

The guard compares two short 16-frame windows, not paired baseline/variant trials with confidence intervals. Its "unchanged" mask is the complement of confident DI change on stable geometry; failure to detect a change is not proof of unchanged illumination. Correlated ReSTIR noise, small denominators, and this classification limit need calibration. The threshold was not loosened to force a pass. Reports retain their failures; the implementation is an experiment and has not met every proposed quality target.

The lossless endpoint dumps permit an additional same-mask comparison. Root verified all twelve files per run have their declared 444,096-byte size and contain finite float32 values. An offline check uses the intersection of stable geometry across all phases/runs, then compares against pooled finite-frame matching-raw means. This removes differing per-run pixel selection, but the pooled reference still shares samples with each run and is not independent ground truth. The verification script and output are `tmp/compare-denoiser-endpoints.mjs` and `tmp/denoiser-validation-20260907/pooled-endpoint-comparison.json`.

On the shared 35,483-pixel stable-geometry mask, placed-image luminance RMS discrepancy against that pooled reference is 10.10% for original five-pass SVGF, 8.63% for one-pass RELAX and 8.62% for two-pass RELAX. The corresponding per-run raw references themselves differ from the pool by 7.96%, 8.62% and 7.92%. These numbers support preserving the experiment for further evaluation, but do not establish a statistically significant quality improvement.

Preserved artifacts: `baseline-svgf-five-pass-controlled-v2`, `relax-one-pass-controlled-v2`, and `relax-two-pass-controlled-v3`, under `tmp/denoiser-validation-20260907/`. Each contains the full report, PNGs, lossless endpoint means and `latest.log`.

## Final shader checks and reproducible commands

After the center-input reuse, root reran the 27 targeted core tests and Fabric compilation successfully. These tests cover structural contracts and scalar guide behavior; the native game runs provide actual GLSL compilation and GPU execution coverage. The two profiler percentile-window tests also passed earlier, and actual launches collected nonblocking timing samples without dropped queries.

The standard Photon game test with zero denoiser passes and frozen background ticks **passed**. It reported `executedDenoiserPasses=0`, a native ReSTIR pipeline, finite/bounded output, and successful stationary/moving camera checks. Its archive is `relax-raw-zero-pass-controlled`. The earlier normal-ticking raw run failed reservoir-confidence accumulation, making the controlled/active-world distinction observable even before denoising.

The standard native Shrimple game test with the final shaders and frozen ticks also **passed**: four executed denoiser passes, finite/bounded output, and twelve movement captures. Its archive is `relax-shrimple-standard-controlled`, and archive SHA-256 is `34415770bbcaa97ce4dbbab10a467fbb2cb3aa3e95f87dc86af0d21a6e111ed2`. The native pack emitted nonfatal preexisting-style compatibility/uninitialized-ray warnings; it linked and completed. This is integration coverage, not a block-edit sharpness measurement or an equal-settings performance comparison with Photon.

Root review additionally identified a pixel-pack-buffer assumption in the new ROI reporter. Commit `690f8ee` isolates CPU readback from PBO binding and all relevant pack-layout parameters, restores them in `finally`, checks GL 4.5/ARB capability, and performs a one-time diagnostic reread with a bound tiny PBO and deliberately nondefault layout. Earlier measurements used default pack state and remain valid.

The final native Photon run at `690f8ee` verified this diagnostic: `pixelPackIsolationVerified=true`, zero readback failures, 257 rendered frames, finite lossless endpoints, and restored fixture state. It measured 0.906240 ms median / 0.973824 ms p95 denoiser time, 0/0 signed-step response frames, and passed the edge/endpoint/exposure/geometry guards. Overall quality status remained **failed**, solely for the proposed noise guard (+88.7%). This repeat reinforces the guard's instability; it does not establish equivalent noise quality. The GL 4.5 path was exercised; the ARB-only fallback was compiled but not exercised on this GPU. Artifacts are in `relax-two-pass-final-readback-check`.

Run the block-edit experiment from this worktree:

```powershell
.\scripts\run-shader-game-test.ps1 -TimeoutSeconds 420 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestScenario=denoiserResponsiveness','-PshaderGameTestDenoiserPasses=2','-PshaderGameTestRenderDistance=8','-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestFreezeTicks=true','-PprofileGpu=true'
```

Use `-PshaderGameTestFreezeTicks=false` for the active-world stress case. Use `-PshaderGameTestScenario=standard` with `-PshaderGameTestDenoiserPasses=0` for the raw-path smoke test; the block-edit quality scenario intentionally requires a denoiser. The pass override is supported for the Photon fixture only. Its generated settings were checked to contain exactly the requested `...PASSES=2` and `TAA=false`, while the tracked fixture retained five passes. Gradle task inputs include the pass override.

The original checkout remains at the concurrently edited source state. The validated implementation is on `codex/denoiser-responsiveness-20260907`, with development also preserved on `codex/relax-denoiser-20260907`. No remote deployment or publishing was performed.

## Remaining work before a production-quality selection

- Preserve unaffected denoiser history with a dedicated, validated previous-light replay/change record. Keep reservoir MIS invalidation separate. The active-clock fixture currently invalidates history on almost every frame, so optimizing a longer temporal filter alone cannot fix its noise.
- Measure and prioritize the local section upload path. The denoiser only reacts after the changed tracing scene exists on the GPU. Direct-light tracing is about 24.5–24.7 ms in this fixture; denoiser optimization alone has limited effect on total frame time.
- Separate DI from GI histories when quality/cost measurements justify another signal path. The implemented adaptation continues to filter the configured combined input.
- Validate thin and non-cube geometry before retuning the 0.025-block plane tolerance. Analytically, a parallel plane separated by 1/16 block has a plane-only weight of about 0.082; an isolated center surrounded by such taps can receive about 21.5% cross-plane contribution before other guides. Normal equality is not proof of a full cube. The implemented fast paths preserve the generic fallback and do not snap geometry to a voxel face.
- Add independent converged references and repeated matched trials; calibrate the unchanged-noise and penumbra-edge guards; test colored-light changes, thin blockers, long shadows, native TAA-on output, larger resolutions and additional GPUs. Tile/shared-memory caching is a plausible later performance experiment, rather than a measured benefit of this branch.
