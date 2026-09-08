Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$watchdogSource = Join-Path $PSScriptRoot 'run-shader-game-test.ps1'
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("photonicengine-shader-watchdog-{0}" -f [Guid]::NewGuid().ToString('N'))
$powerShellExecutable = 'powershell.exe'

function Remove-WatchdogFixture {
    param([string]$Root)
    if (!(Test-Path -LiteralPath $Root)) { return }
    $resolved = (Resolve-Path -LiteralPath $Root).Path
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($resolved) -ne $temporaryRoot -or
            [IO.Path]::GetFileName($resolved) -notmatch '^photonicengine-shader-watchdog-(stale-)?[a-f0-9]{32}$' -or
            ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Refusing to remove an unexpected fixture directory: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function New-WatchdogFixture {
    param(
        [string]$Root,
        [switch]$WriteStaleFailure
    )

    $fixtureScriptDirectory = Join-Path $Root 'scripts'
    $fixtureRunDirectory = Join-Path $Root 'modules/versions/1_21_11/fabric/run'
    $fixtureLogDirectory = Join-Path $fixtureRunDirectory 'logs'
    $fixtureWorldDirectory = Join-Path $fixtureRunDirectory 'saves/backup'
    $fixtureShaderPackPath = Join-Path $fixtureRunDirectory 'shaderpacks/Shrimple-ph-0.4.zip'

    New-Item -ItemType Directory -Force -Path $fixtureScriptDirectory, $fixtureLogDirectory, $fixtureWorldDirectory, (Split-Path -Parent $fixtureShaderPackPath) | Out-Null
    Copy-Item -LiteralPath $watchdogSource -Destination (Join-Path $fixtureScriptDirectory 'run-shader-game-test.ps1')
    Set-Content -LiteralPath (Join-Path $fixtureWorldDirectory 'level.dat') -Value 'fixture' -Encoding Ascii
    Set-Content -LiteralPath $fixtureShaderPackPath -Value 'read-only shaderpack sentinel' -Encoding Ascii

    $logPath = Join-Path $fixtureLogDirectory 'latest.log'
    if ($WriteStaleFailure) {
        Set-Content -LiteralPath $logPath -Encoding Ascii -Value '[Render thread/ERROR] (Iris) Failed to create shader rendering pipeline, disabling shaders!'
    }

$gradleScript = @'
@echo off
setlocal
ping -n 2 127.0.0.1 >nul
if "%SHADER_WATCHDOG_WRITE_FAILURE_MODE%"=="pipeline" (
  >>"%~dp0modules\versions\1_21_11\fabric\run\logs\latest.log" echo [Render thread/WARN] ^(GlShader^) Shader compilation log for reproject previous reservoirs.csh: 0^(102^) : error C1503: undefined variable "RGBToLinear"
  >>"%~dp0modules\versions\1_21_11\fabric\run\logs\latest.log" echo [Render thread/ERROR] ^(Iris^) Failed to create shader rendering pipeline, disabling shaders!
)
if "%SHADER_WATCHDOG_WRITE_FAILURE_MODE%"=="pipeline-single" (
  >>"%~dp0modules\versions\1_21_11\fabric\run\logs\latest.log" echo [Render thread/ERROR] ^(Iris^) Failed to create shader rendering pipeline, disabling shaders!
)
if "%SHADER_WATCHDOG_WRITE_FAILURE_MODE%"=="shaderpack" (
  >>"%~dp0modules\versions\1_21_11\fabric\run\logs\latest.log" echo [Render thread/ERROR] ^(Iris^) Failed to load the shaderpack "Shrimple-ph-0.4.zip"!
  >>"%~dp0modules\versions\1_21_11\fabric\run\logs\latest.log" echo java.lang.RuntimeException: unexpected error: failed to read /ph_lights.json
)
ping -n 5 127.0.0.1 >nul
exit /b 0
'@
    Set-Content -LiteralPath (Join-Path $Root 'gradlew.bat') -Value $gradleScript -Encoding Ascii
}

function Invoke-WatchdogFixture {
    param(
        [string]$Root,
        [ValidateSet('none', 'pipeline', 'pipeline-single', 'shaderpack')]
        [string]$FailureMode = 'none'
    )

    $previousFailureMode = $env:SHADER_WATCHDOG_WRITE_FAILURE_MODE
    $previousMutexName = $env:PHOTONICS_SHADER_GAME_MUTEX_NAME
    try {
        $env:SHADER_WATCHDOG_WRITE_FAILURE_MODE = $FailureMode
        $env:PHOTONICS_SHADER_GAME_MUTEX_NAME = 'Local\PhotonicEngine-Shader-Game-Test-Fixture-' + ([System.IO.Path]::GetFileName($Root))
        $standardOutputPath = Join-Path $Root 'watchdog.stdout.log'
        $standardErrorPath = Join-Path $Root 'watchdog.stderr.log'
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $process = Start-Process -FilePath $powerShellExecutable `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root 'scripts/run-shader-game-test.ps1'), '-LaunchOnly', '-TimeoutSeconds', '30') `
            -PassThru `
            -Wait `
            -NoNewWindow `
            -RedirectStandardOutput $standardOutputPath `
            -RedirectStandardError $standardErrorPath
        $process.Refresh()
        $stopwatch.Stop()

        $standardOutput = if (Test-Path -LiteralPath $standardOutputPath) { Get-Content -LiteralPath $standardOutputPath -Raw } else { '' }
        $standardError = if (Test-Path -LiteralPath $standardErrorPath) { Get-Content -LiteralPath $standardErrorPath -Raw } else { '' }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = ($standardOutput, $standardError) -join [Environment]::NewLine
            Elapsed = $stopwatch.Elapsed
        }
    } finally {
        $env:SHADER_WATCHDOG_WRITE_FAILURE_MODE = $previousFailureMode
        $env:PHOTONICS_SHADER_GAME_MUTEX_NAME = $previousMutexName
    }
}

try {
    New-WatchdogFixture -Root $fixtureRoot
    $fixtureShaderPackPath = Join-Path $fixtureRoot 'modules/versions/1_21_11/fabric/run/shaderpacks/Shrimple-ph-0.4.zip'
    $shaderPackHashBeforeRuns = (Get-FileHash -LiteralPath $fixtureShaderPackPath -Algorithm SHA256).Hash
    $fixtureRunRoot = Join-Path $fixtureRoot 'modules/versions/1_21_11/fabric/run'
    $oldCapture = Join-Path $fixtureRunRoot 'automation/screenshots/stale-capture.png'
    New-Item -ItemType Directory -Force (Split-Path -Parent $oldCapture) | Out-Null
    Set-Content -LiteralPath $oldCapture -Value 'previous run image' -Encoding Ascii
    $newPipelineFailure = Invoke-WatchdogFixture -Root $fixtureRoot -FailureMode pipeline
    if (Test-Path -LiteralPath $oldCapture) {
        throw 'A failed game run still exposes the previous run screenshots as current artifacts.'
    }
    $archivedCaptures = @(Get-ChildItem -LiteralPath (Join-Path $fixtureRunRoot 'automation-history') -Recurse -Filter 'stale-capture.png')
    if ($archivedCaptures.Count -ne 1 -or (Get-Content -LiteralPath $archivedCaptures[0].FullName) -ne 'previous run image') {
        throw 'Previous game-test evidence must be archived intact, not discarded.'
    }
    $singlePipelineFailure = Invoke-WatchdogFixture -Root $fixtureRoot -FailureMode pipeline-single
    $newShaderpackFailure = Invoke-WatchdogFixture -Root $fixtureRoot -FailureMode shaderpack
    $cleanRun = Invoke-WatchdogFixture -Root $fixtureRoot

    if ($newPipelineFailure.ExitCode -ne 1) {
        throw "Expected a newly appended Iris shader-pipeline failure to fail -LaunchOnly, but the watchdog exited $($newPipelineFailure.ExitCode). Output: $($newPipelineFailure.Output)"
    }
    if ($newPipelineFailure.Output -notmatch 'watchdogResult=shader-pipeline-failed') {
        throw "Expected shader-pipeline-failed watchdog result. Output: $($newPipelineFailure.Output)"
    }
    if ($newPipelineFailure.Elapsed.TotalSeconds -ge 12) {
        throw "Expected prompt shader-pipeline failure, but the watchdog took $($newPipelineFailure.Elapsed.TotalSeconds) seconds."
    }
    if ($singlePipelineFailure.ExitCode -ne 1 -or
            $singlePipelineFailure.Output -notmatch 'watchdogResult=shader-pipeline-failed') {
        throw "Expected one Iris failure line to produce a shader-pipeline-failed result. Output: $($singlePipelineFailure.Output)"
    }

    if ($newShaderpackFailure.ExitCode -ne 1) {
        throw "Expected a newly appended shaderpack load failure to fail -LaunchOnly, but the watchdog exited $($newShaderpackFailure.ExitCode). Output: $($newShaderpackFailure.Output)"
    }
    if ($newShaderpackFailure.Output -notmatch 'watchdogResult=shaderpack-load-failed') {
        throw "Expected shaderpack-load-failed watchdog result. Output: $($newShaderpackFailure.Output)"
    }
    if ($newShaderpackFailure.Elapsed.TotalSeconds -ge 12) {
        throw "Expected prompt shaderpack load failure, but the watchdog took $($newShaderpackFailure.Elapsed.TotalSeconds) seconds."
    }

    if ($cleanRun.ExitCode -ne 0) {
        throw "Expected a clean shader game run to succeed, but the watchdog exited $($cleanRun.ExitCode). Output: $($cleanRun.Output)"
    }
    if ($cleanRun.Output -notmatch 'watchdogResult=launch-only-completed') {
        throw "Expected launch-only-completed for a clean run. Output: $($cleanRun.Output)"
    }

    $shaderPackHashAfterRuns = (Get-FileHash -LiteralPath $fixtureShaderPackPath -Algorithm SHA256).Hash
    if ($shaderPackHashAfterRuns -ne $shaderPackHashBeforeRuns) {
        throw 'The shader game test runner modified a shaderpack archive.'
    }

    $staleFixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("photonicengine-shader-watchdog-stale-{0}" -f [Guid]::NewGuid().ToString('N'))
    try {
        New-WatchdogFixture -Root $staleFixtureRoot -WriteStaleFailure
        $staleFailure = Invoke-WatchdogFixture -Root $staleFixtureRoot

        if ($staleFailure.ExitCode -ne 0) {
            throw "Expected a stale Iris shader-pipeline failure to be ignored, but the watchdog exited $($staleFailure.ExitCode). Output: $($staleFailure.Output)"
        }
        if ($staleFailure.Output -notmatch 'watchdogResult=launch-only-completed') {
            throw "Expected launch-only-completed for stale logs. Output: $($staleFailure.Output)"
        }
    } finally {
        Remove-WatchdogFixture -Root $staleFixtureRoot
    }

    Write-Output 'run-shader-game-test watchdog regression test passed'
} finally {
    Remove-WatchdogFixture -Root $fixtureRoot
}
