# PhotonicEngine

## Shader game-test harness

The external shader-game-test harness launches the Fabric client in its own
run configuration and Quick Plays the `backup` world from
`modules/versions/1_21_11/fabric/run/saves/backup`.

```powershell
.\scripts\run-shader-game-test.ps1
```

The property-gated client reporter waits for the active ReSTIR pipeline to
settle, samples two nearby camera positions, and writes this JSON file before
the client exits:

`modules/versions/1_21_11/fabric/run/automation/shader-game-test-report.json`

```json
{
  "success": true,
  "failureReason": "",
  "metrics": {}
}
```

`success` is required and must be a boolean; `failureReason` is optional and
is surfaced if the test fails. The reporter validates the rendered framebuffer
and the ReSTIR lighting attachment for finite, non-black, chromatically stable,
converging output. It also reads the direct-reservoir target/confidence channel
to require finite reservoirs that accumulate temporal confidence without
exceeding the Falcor confidence cap. The report records both camera samples,
shader-pack settings, convergence statistics, and reservoir confidence
statistics. The runner fails on a missing or invalid report and refuses to
launch while the test world is already open, avoiding concurrent writes. For
interactive reporter development, use:

```powershell
.\scripts\run-shader-game-test.ps1 -LaunchOnly
```
