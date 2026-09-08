# Iron door appearance — 2026-09-08

The apparent scene visible through the lower iron-door panel is Photon's native
screen-space reflection of the gold pressure plate in front of the door. The
controlled comparison does not support a missing or transparent door surface.
No engine geometry or material change was made for this symptom.

## Evidence

Native `Photon-0.4-support.zip` (SHA-256
`f462652e47068765f4043ec1cf3c46bfd70b5588c8549a1f5b343d57b526fb9f`), camera
`(-11.8, -47, 49.5)`, yaw 90, pitch 20, frozen world, five denoiser passes:

- Baseline: `tmp/rendering-improvements-20260908/baseline-door/automation/`.
- Comparison: `tmp/rendering-improvements-20260908/door-environment-reflections-off/`.
  Only the ordinary native option `ENVIRONMENT_REFLECTIONS=false` was requested
  for this diagnostic; the shader-pack archive was not modified. The option was
  restored afterward. Engine development proceeded between builds, so this is
  not a byte-identical engine A/B; the attachment and control-region checks below
  distinguish the native reflection from that independent work.

The baseline center G-buffer probe identifies an opaque lower iron door at
`(-14.000001, -46.177102, 49.496587)`, with flags 1 and normal approximately
`(1, 0, 0)`. The lower-panel image is absent from both Photonics
`denoise_result-5.png` and `gi_output-5.png`: every pixel in the measured region
is RGB zero. The pattern appears in the final native composition.

In `camera-a-5.png`, the lower-panel region is x `[370,485)`, y `[285,332)`
(top-left image origin). Values below are normalized PNG channel means, not HDR
radiometric measurements.

| Region | Reflections on, mean RGB | Reflections off, mean RGB |
| --- | --- | --- |
| Lower door | 0.11258, 0.10498, 0.07992 | 0.05516, 0.05930, 0.06505 |
| Upper door control | 0.06730, 0.07008, 0.07445 | 0.06694, 0.06981, 0.07419 |
| Wall control | 0.09036, 0.08722, 0.06871 | 0.09029, 0.08719, 0.06869 |

The gold pattern visibly disappears when native environment reflections are
disabled. Lower-door red minus blue changes from +0.03266 to -0.00989, while
the maximum wall mean-channel change is below 0.000063. The gold pressure plate
also loses its reflected environment, as expected for the same metal option.

The native pack explicitly maps `iron_door` to block ID 10018. Material mask 18
sets `is_metal=true`, `ssr_multiplier=1`, and an albedo-dependent roughness under
`HARDCODED_SPECULAR`. Its specular pass traces a reflected screen-space ray and
reads previous `colortex5` scene radiance. Source excerpts and ROI measurements
are archived as `door-material-source-evidence.txt` and
`door-reflection-roi-comparison.json` in the evidence root.

Both close-door runs fail the existing room illumination gate because this
view's Photonics lighting attachment is effectively black. They are targeted
visual diagnostics, not overall rendering passes. No artificial door-opacity
fix or permanent reflection-setting change is justified by this evidence.
