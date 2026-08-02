# PhotonicEngine

## Shader game-test harness

The external shader-game-test harness launches the Fabric client in its own
run configuration and Quick Plays the `backup` world from
`modules/versions/1_21_11/fabric/run/saves/backup`.

```powershell
.\scripts\run-shader-game-test.ps1
```

The runner has no renderer instrumentation or engine-specific metrics. It
passes only when the engine-side test automation writes this JSON file before
the client exits:

`modules/versions/1_21_11/fabric/run/automation/shader-game-test-report.json`

```json
{
  "success": true,
  "failureReason": ""
}
```

`success` is required and must be a boolean; `failureReason` is optional and
is surfaced if the test fails. A future engine-side agent can add any metrics
it needs without changing the harness. Until that reporter is attached, the
runner deliberately fails with `missing-report` instead of reporting a false
pass. It also refuses to launch while the test world is already open, avoiding
concurrent writes. For interactive reporter development, use:

```powershell
.\scripts\run-shader-game-test.ps1 -LaunchOnly
```
