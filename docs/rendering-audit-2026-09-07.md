# Rendering integration audit — 2026-09-07

Work in progress. Numerical game-test success is not a claim of clean rendering.

## Merge contract

The local branch started at `01a0f7a`; upstream `origin/multi-version` was fetched
at `54e049b` (47 commits after merge base `3a9d6a6`). The pre-merge branch and user
work are retained as `backup/rendering-before-sync-20260907` and the labelled
pre-merge stash. The upstream Iris property/renderer API is being integrated;
the local direct-light estimator is not being replaced by upstream's older one.

Preserved direct-light pipeline:

1. Power-RIS preparation and RTXDI-style ReGIR onion/grid presampling.
2. Current-frame initial candidates, including replayable light UVs.
3. Reservoir Splatting `ScatterOnly`: forward projection, append, prefix offsets,
   sort, and per-destination temporal resampling.
4. Pairwise-MIS spatial reuse, retaining the selected reconnection record.
5. Selected RGB integrand times UCW resolve, followed by SVGF.

The local `reference/research/reservoir-splatting-temporal-contract.md` maps the
paper's equations 10–17 to the vendored Falcor implementation. In particular,
confidence is distinct from weight; both forward and reverse shifts participate
in MIS; selected primary hit/subpixel and suffix Jacobian remain persisted.
The RTXDI RIS normalizations and cell lookup are covered by the existing numerical
ReGIR regression tests. No arbitrary Jacobian clamp or depth/normal rejection was
added to direct `ScatterOnly` to mask artifacts. Hand paths remain a separate
domain, and GI is still the separate, approximate upstream-style estimator—not
full multi-bounce Reservoir Splatting.

## Confirmed integration defects

- **Premature material lookup:** the LevelRenderer HEAD hook started world
  compilation before Iris's first `beginLevelRendering` initialized block IDs.
  A native Shrimple run recorded sea lanterns with GPU block ID `-1`. Frame setup
  now follows Iris's material-map initialization; section collection waits for
  that first initialized frame. The test compares uploaded IDs to current IDs;
  native Shrimple now uploads the expected sea-lantern ID `306`.
- **Missing transparency define:** the new properties API retained the CPU
  transparency setting but omitted `PH_USE_TRANSPARENCY` from GLSL. Native
  glass was therefore an opaque ray hit. A properties reload regression failed
  before the fix and passes for BLOCK, VOXEL and NONE afterward. GPU probes
  changed from zero transmission to tinted RGB through the same glass pane.
- **Relative compute dispatch units:** the old adapter expected group-scaled
  extents (`1/16`), whereas Iris's relative compute API expects invocation
  extents and divides by `local_size` internally. The first merged adapter
  divided twice, dispatching the splatting stages over only the lower-left
  1/256 of the image. At steady confidence 20, all nine room probes had zero
  forward contributors and raw floor light was only 4–6% of a 16-UV-per-light
  ray reference. Dispatch now uses full invocation extents times render scale.
  A viewport-coverage regression failed before the fix and passes afterward;
  full-screen GPU verification now populates all nine room bins and restores
  raw energy to the independent reference scale. No MIS or ReGIR PDF was tuned.
- **Copied depth corruption:** Photon room surfaces were all
  classified as hands. During the fragment-data pass, the original depth was
  `0.98987174` (`DEPTH_COMPONENT32F`) while Iris's copied texture returned
  `0.4898718` (`DEPTH_COMPONENT32`). This falls below Photon's hand threshold.
  Earlier Photon brightness-only passes are not evidence of correct tracing.
  The game test now rejects upper-room probes classified entirely as hands.
  Iris now allocates its two depth copies using the actual source GL internal
  format, without changing Minecraft's logical texture-format contract. The
  original and copied depths now match (`0.9898726`), and reconstructed floor
  height is correctly `y=-47`. The first numerical confirmation had a blurred
  pause menu and is explicitly archived as visually invalid; later unpaused
  captures confirm the room/hand distinction.
- **Metadata collision:** packing unknown block ID `-1` also set the reserved
  light-transmission bit. Packing now masks ID and metadata independently and
  decoding preserves the unknown-ID sentinel. A numerical regression covers
  opaque and transmissive unknown materials.
- **Negative coordinates:** truncating light centers selected the adjacent voxel
  at negative coordinates. Light identity and camera section/origin calculations
  now use floor. The light-identity test spans zero and section boundaries.
- **Cross-section source culling:** neighbor lookup used the source's section
  coordinates and could retain a previous neighbor's section. Each neighbor now
  resolves its own section; missing sections cannot prove enclosure.
- **Denoiser scheduling:** hidden minimum-seven filtering, reversed/skipped
  fine-scale iterations, and a missing final flip were unrelated to ReGIR.
  The requested pass count now runs from radius 1 upward and resolves the final
  result. Zero disables both temporal and spatial irradiance denoising.
- **Denoiser state:** bounds, hand/world classification, finite-history checks,
  and complete output initialization were missing. Exposure conversion must
  rescale fast history and first/second moments, not only the main RGB history.
  A neighbor-maximum clamp that could darken valid bright centers was removed.
- **Surface emission:** adding source emission to incident irradiance violates
  the native material contract. Photon composites surface emission; Shrimple's
  native implementation only does so through its PBR material path, which is a
  remaining compatibility gap. The extra primary-emission trace/attachment was removed; it also
  avoids tracing a deterministic source again and blurring its texture.
- **Primary receiver classification:** the classifier started at the outgoing
  visibility-ray bias, inside glass directly in front of an opaque lantern or
  wall. It now queries just inside the unbiased receiver and requires the hit
  to belong to that block. Two native GPU primary probes changed from falsely
  transmissive to opaque. Spatial and temporal camera traversal now skip the
  translucent block layer, matching the opaque G-buffer; the independent game
  regression failed at two probes before the fix and passes afterward.
- **Jitter domains:** raster SVGF/GI history requires the previous raster jitter,
  not the current frame's jitter. It is now recorded in a separate history
  texture. Reservoir Splatting retains its unjittered film-pixel domain; its
  confidence backprojection must not mix in raster jitter.
- **GI feedback and coverage:** initial, temporal and resolve stages could sample
  attachments they wrote. GI candidates now occupy distinct lanes; later stages
  alternate read/write reservoirs. Failed temporal reprojection retains current
  candidates, and out-of-world pixels write initialized empty results.
- **GI sky miss:** sky evaluation used the initialized miss hit position instead
  of the actual escaped ray position. Iterator and first-hit outputs are now
  initialized, and the `NO_SHADOW_MAPPING` spelling matches the interface.
- **GI sky reconnection through glass:** the visibility iterator received an
  unnormalized, roughly 10,000-block sky direction. Its `direction * 0.03`
  transparency offset could jump hundreds of blocks past an opaque emitter.
  A native GPU regression found 159 falsely visible sky paths through glass
  and sea lanterns. Normalize before traversal, reject budget exhaustion as a
  sky miss, and zero blocked radiance; the same probes now all reject those
  occluded paths. This is a path-visibility fix, not an intensity adjustment.

## Test integrity and evidence

Native archives, without source patching:

- Photon `0.4-support`, commit `f7d19a75274bbd9d73164c2c3d584a62fc895f1f`.
- Shrimple `ph-0.4`, commit `c4aab0c3b2a21560f7cf09be2a13edf40c1b1373`.

The previous modified Shrimple fixture was removed (recoverable from Git/stash).
Ordinary `.zip.txt` shader options remain explicit. With Shrimple's alternate
`LIGHTING_COLORED=true` voxel renderer, the native branch fails compilation on
`texVoxels` in translucent terrain. The watchdog correctly fails that run.
Testing `LIGHTING_COLORED=false` uses the native default and still enables
Photonics block-light tinting; it does not modify the archive to hide an error.

The reader previously inherited Minecraft's non-default OpenGL pixel-pack
layout. Apparent gigantic/non-finite texture values from those early reports
are not reliable evidence of shader corruption. The reader now owns and restores
alignment, row length, skips, and byte swapping. An in-game readback regression
compares every float bit under deliberately different packing and verifies
restoration. The corrected Photon raw captures are finite.

All 6 stationary A, 12 moving, and 6 stationary B captures after a fixed warmup
are retained, regardless of their quality. PNGs, HDR/handheld/reservoir metrics,
archive SHA-256, actual denoiser count, effective ReGIR configuration, held item,
and per-capture scene generations are recorded. Global image statistics can miss
localized defects: the dark Shrimple window screenshot passed them, so visual
inspection remains mandatory.

Evidence directories under `tmp/rendering-audit-20260907/` include:

- `photon-raw-readback-fixed`: finite raw output; dynamic history failure.
- `photon-dynamic-clocks`: bounded logs identify changing repeaters, wires and
  redstone lamps elsewhere in the world.
- `photon-denoised-static`: native Photon, 5 passes, tick-frozen diagnostic;
  numerical checks pass and confidence reaches 20. Not a dynamic-world pass.
- `shrimple-stale-block-ids`: native Shrimple, 4 passes, tick-frozen diagnostic;
  numerically passes but has visibly dark windows and stale `-1` light IDs.
- `photon-depth-fixed-menu-invalid`: corrected depth but paused final imagery;
  only its GPU reconstruction measurements are valid.
- `photon-sky-through-glass-red` / `photon-sky-through-glass-green`: unpaused
  native Photon, identical glass-to-opaque-source sky-ray regression.

The harness formerly forced two chunks of render distance. It now preserves the
client setting unless `-PshaderGameTestRenderDistance=...` explicitly overrides
it. The roof/load investigation found two distinct defects in the old setup:

- At startup tick 20, the client already has the sandstone roof at `y=-43`,
  while the voxel scene misses it. By tick 40 the GPU ray hits that same roof.
  Early GI sky illumination during this upload gap is not a denoiser artifact.
  `photon-roof-startup` retains the transition; startup latency remains open.
- At two chunks, CPU and voxel sun rays both hit a loaded sandstone wall, but
  native shadow-map lookups report three floor probes fully sunlit. With eight
  chunks, all three native lookups report shadow and the erroneous orange floor
  band disappears. Compare `photon-sun-shadow-mismatch-rd2` and
  `photon-sun-shadow-correct-rd8`. The integration harness now rejects that
  specific loaded-occluder/sun-shadow contradiction when ray diagnostics run.

Subsequent room validation explicitly uses eight chunks. This is an ordinary
Minecraft setting, not a source patch to either native archive. A numerically
stable image with incomplete shadow coverage must not count as visual success.

The glowstone dust held in those captures produced **zero Photonics handheld
irradiance**. Holding a real sea lantern reproduced severe overexposure: with
the corrupted copied depth, the entire world took the unattenuated hand path.
Do not tune light intensities to compensate for this reconstruction defect.

The watchdog also had a PowerShell StrictMode scalar/array bug when exactly one
Iris pipeline-failure line arrived. A new single-line regression reproduced it;
array wrapping fixes it. Unexpected watchdog exceptions now terminate their own
launched process tree instead of leaving a shader-disabled game running.
Another regression seeded a previous screenshot, triggered compilation failure,
and proved that old images remained exposed as current evidence. Each launch now
archives the prior artifact directory intact before creating a fresh one. The
watchdog regression verifies both isolation and preservation, and passes.

Integration checkpoint: 75 core tests and 8 Minecraft-common tests pass (no
skips). The Fabric test task has no unit tests; its real native game runs are
the integration evidence above. Rendering work remains open below.

## Dynamic history and instantaneous shadows

Full-screen dispatch verification (`photon-fullscreen-splats`) found and fixed
an upstream Iris integration mismatch, not a reference-algorithm bias. Iris
`ComputeProgram.getWorkGroups` divides relative invocation extents by the GLSL
local size. Our old wrapper factors divided by 16 beforehand too: only about
1/256 of the image ran the splatting passes. Nine room bins were empty while
confidence reached 20; canonical MIS consequently retained roughly 1/21 energy.
Factors are now 1.0 times the configured render scale. The numerical dispatch
regression failed before the fix; native Photon then populated all nine bins.
Across six captures, raw green energy at the three bright floor probes rose
from 4–6% to 94–98% of an independent 16-UV-per-light direct reference. The three
dim probes are 113–132%, requiring more samples before a bias conclusion.

The same diagnostics exposed a second path-domain mismatch. Spatial camera
rays stopped on stained glass while the opaque G-buffer represented the wall
or lantern behind it. `photon-camera-glass-red` fails at probes 6 and 7 with
retained transmissive primaries up to almost one block in front of the raster
surface. Camera primary/segment traversal now skips the Minecraft translucent
block layer. Light and GI traversal still retain tint and attenuation. This
change passes the identical native game regression in
`photon-camera-glass-green`: all nine retained flags are opaque and the two
previously displaced hits return to their actual opaque receivers.

The test world contains active redstone clocks. Current direct temporal history
is invalidated globally when either GPU-visible world or light-list generation
changes. This is conservative but costly: distant lamps repeatedly reset the
whole screen to current-frame spatial confidence. Disabling that check alone is
not a correct fix. Reverse-shift MIS currently has no previous world/light-state
snapshot with which to evaluate the old domain.

For low-latency direct shadows, the useful architecture is current-frame
visibility separated from slowly converging irradiance, plus reactive history
invalidation for actual changed paths. This must not be implemented by reusing
stale visibility and smoothing it for 32 frames. Voxel upload latency and native
pack TAA are additional sources of delay outside the denoiser.

Minecraft-specific optimization opportunities, pending measurement:

- Retain previous light records and copy-on-write/dirty-page voxel snapshots;
  avoid copying the entire 512 MiB world allocation every frame.
- Track changed blocks/light bounds for local radiance-history invalidation.
  Any reuse proof must cover both shift directions, not just the selected
  forward ray, before replacing global invalidation.
- Rebuild affected ReGIR cells and alias/RIS data only when their inputs change;
  preserve the reference proposal PDF and required jitter/distribution.
- Trace axis-aligned full blocks with voxel traversal; retain mesh/subvoxel
  fallback for fences, stairs, water, glass and other non-cubes.
- Evaluate deterministic handheld lights and the sun separately from stochastic
  many-light irradiance. Trace each hand independently where shadows differ.
- Measure GPU pass time and upload delay separately from startup and synchronous
  diagnostic readback. Reducing wide denoiser passes saves bandwidth but cannot
  restore a missing lighting signal or eliminate sampling noise by itself.

## Still required

Inspect moving colored projections and the startup flash with complete roof geometry,
test actual emissive handheld items and sea-lantern source faces, exercise GI and
skylight, and repeat in the unfrozen clock world. Finish the merge and final
regression checks only after recording the remaining limitations accurately.
