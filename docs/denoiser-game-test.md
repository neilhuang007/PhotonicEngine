# Denoiser responsiveness game test

The `denoiserResponsiveness` scenario uses the native Photonics ReSTIR path in
the disposable `backup` world. It keeps the room camera fixed, places and then
removes one opaque sandstone block through the integrated server, and reads a
small linear-light ROI from the raw direct and final denoised GPU attachments on
every Photonics render frame.

Run the two-pass native Photon fixture from the repository root:

```powershell
.\scripts\run-shader-game-test.ps1 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestScenario=denoiserResponsiveness','-PshaderGameTestRenderDistance=8','-PshaderGameTestPitch=20','-PshaderGameTestYaw=180'
```

Add `-PshaderGameTestFreezeTicks=true` for the controlled-edit mode. It freezes
background simulation while the reporter still schedules the real placement
and removal on the integrated-server executor. The report labels this mode and
can attribute the next generation plus matching raw response to that manual
edit. Repeat without the flag to exercise the active-clock world; global
generation changes are then explicitly reported as ambiguous. The shader pack
must enable direct ReSTIR and at least one denoiser pass.

Add `-PprofileGpu=true` to collect asynchronous per-pass GPU timing statistics.

Defaults match the tracked room fixture: camera
`-24.011911129209686,-47,53`, floor-standing edit block `-25,-47,46`, existing
sea-lantern source `-25,-45,43`, receiver anchor `-25,-48,48`, ROI
`0.35,0.32,0.30,0.30`, and horizontal edge profiles. The
edit cell is air in the copied fixture and the receiver anchor is sandstone.
Override a fixture after
visually validating a disposable world copy with these Gradle properties:

```text
-PshaderGameTestDenoiserCamera=x,y,z
-PshaderGameTestDenoiserEditBlock=x,y,z
-PshaderGameTestDenoiserSourceBlock=x,y,z
-PshaderGameTestDenoiserReceiverBlock=x,y,z
-PshaderGameTestDenoiserRoi=xFraction,yFraction,widthFraction,heightFraction
-PshaderGameTestDenoiserEdgeAxis=x|y
```

The standard report is written, from the repository root, to
`modules/versions/1_21_11/fabric/run/automation/shader-game-test-report.json`. Under
`metrics.denoiser`, it records edit request, server application, client
observation, GPU generation, raw response, and sustained 90% denoised-response
frames for both placement and removal. It also records per-frame residual
curves, raw/denoised 10–90% edge widths, changed and unchanged ROI temporal
noise, exposure drift, center receiver geometry drift, counters, effective
fixture coordinates, GPU timings when requested, and nine cropped endpoint
reference PNGs (DI, matching DI+GI raw input, and denoised) in
`modules/versions/1_21_11/fabric/run/automation/denoiser-responsiveness`.
The `endpoints` subdirectory also contains lossless float32 little-endian dumps
of before, placed, and restored endpoint means for direct DI, matching raw
DI+GI, filtered RGB, and `frag_data0` geometry. Its `manifest.json` records the
dimensions, signal meanings, interleaved channel layout, and bottom-left row
origin so paired runs can be reanalyzed later with the same geometry data.

Edit attribution uses exposure-normalized `di_output`: the affected GPU frame
requires both a world-content generation transition and a direct-light response
on the receiver. Denoised endpoint bias, step retention, and saved raw-reference
images instead use the matching filter input, exposure-normalized DI plus GI
when combined ReSTIR GI is enabled. This keeps unrelated GI variation out of
the edit-timing marker while comparing filtered output with the signal it
actually filters.

The primary response curve is a signed least-squares projection of each frame
onto that output's before-to-after endpoint vector. Its sustained 90% frame
measures how quickly the edit's lighting energy appears, including overshoot.
The report separately retains `1 - current-to-target RMS / endpoint-step RMS`
as RMS convergence evidence. RMS convergence includes stochastic shape and
noise settling and is not used as the edit-response latency gate.

Per-pixel `frag_data0` endpoint comparisons form a stable receiver-geometry
mask. Pixels whose primary geometry changes are excluded from changed-shadow,
unchanged-noise, and edge-profile measurements, so the placed block silhouette
does not count as receiver response. The report records accepted and rejected
pixel counts to make the effective sample area reviewable.

Edge evidence uses signed before-minus-placed response on each scanline. It
excludes unstable geometry, applies a five-pixel moving average, derives a
separate background level from each outer tail, and interpolates local 10% and
90% crossings. A scanline needs at least three confidently changed shadow-core
pixels, and its raw peak must be one of those pixels; crossing searches still
use every stable receiver pixel so they retain the penumbra. Raw and denoised
widths are paired on the same scanline and shadow side; the report includes
every accepted pair and uses the median paired width increase for the quality
guard. This avoids folding the perspective shadow shape and a nonzero Monte
Carlo tail into one full-ROI profile width.

Each endpoint is the average of the final 16 frames in its finite capture
window. That average is the practical reference for this bounded regression,
not an independent high-sample ground truth; rare temporal artifacts outside
the window can be missed. Use matched runs and multiple seeds before drawing a
quality or performance conclusion. Synchronous ROI readback changes runtime
cost, so elapsed CPU time and screenshot FPS are not GPU timings.
