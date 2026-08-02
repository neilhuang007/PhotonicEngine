# Shader game-test integration

Use the runner from the repository root:

```powershell
.\scripts\run-shader-game-test.ps1
```

It starts `runShaderGameTestClient`, which Quick Plays the `backup` world.
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
