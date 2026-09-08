# Shader game-test integration

Use the runner from the repository root:

```powershell
.\scripts\run-shader-game-test.ps1
```

It starts `runShaderGameTestClient`, which Quick Plays the `backup` world.
The default is the unmodified native `Shrimple-ph-0.4.zip`; select native Photon
with `-ShaderPack Photon-0.4-support.zip`. The runner must never rewrite a pack
archive or turn shader compilation failure into a vanilla-rendering pass.
Normal Iris settings live in the adjacent `.zip.txt` files and are recorded,
along with the archive SHA-256, in each report.
The dedicated launch uses `photonics.usePackagedShaders=true`: engine GLSL comes
from the same build's resources as an installed mod, not the live development
source directory. Java and shader changes take effect together on the next run.

For the stained-glass room diagnostic:

```powershell
.\scripts\run-shader-game-test.ps1 -ShaderPack Photon-0.4-support.zip -GradleArgs '-PshaderGameTestPitch=20','-PshaderGameTestYaw=180','-PshaderGameTestRenderDistance=8','-PtraceLighting=true'
```

Render distance otherwise preserves the client's setting. Two chunks do not
provide correct native shadow-map coverage in this world. For an explicitly
static diagnostic add `-PshaderGameTestFreezeTicks=true`; always repeat with
normal ticking before claiming dynamic-world success. `-PshaderGameTestTraceStartup=true`
records early roof-upload/lighting behavior rather than hiding startup frames.
`-PshaderGameTestHotbarSlot=4` selects the fixture's sea lantern; slot 5 is
glowstone dust and does not emit Photonics handheld light.

Every launch moves prior `automation` evidence intact into the sibling
`automation-history/<run-id>` directory. Only current-run images and reports
belong in `automation`. Keep both failed and successful evidence; aggregate
brightness metrics alone cannot establish clean rendering. Synchronous readback
and optional ray diagnostics make the reported FPS unsuitable for GPU benchmarks.

For interactive reporter work that does not require a result, use:

```powershell
.\scripts\run-shader-game-test.ps1 -LaunchOnly
```

## Reporter contract

Engine-side automation must write this file before it closes the client:

`modules/versions/1_21_11/fabric/run/automation/shader-game-test-report.json`

Minimum valid JSON:

```json
{ "success": true }
```

`success` is required and must be a JSON boolean. On failure, include an
optional human-readable `failureReason` string.

The Fabric test launch supplies the report's relative path through the JVM
system property `photonicengine.shaderGameTest.reportFile`. Read it with
`System.getProperty(...)`; resolve it against the game run directory, create
its parent directory, write the completed JSON, then close the client. Do not
close first: the runner reads the report only after the Gradle launch exits.

## Custom metrics

Place optional values under `metrics`. They are deliberately not required,
validated, or logged by the PowerShell harness, so add and change them without
changing the runner:

```json
{
  "success": true,
  "metrics": {
    "frameTimeMs": 12.4,
    "rayCount": 4096,
    "scenario": "indoor"
  }
}
```

Keep metric values JSON-safe and omit sensitive data. A separate agent may
consume this object or add engine-side telemetry; do not make the harness
depend on individual metrics.
