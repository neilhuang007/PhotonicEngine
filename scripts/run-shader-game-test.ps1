param(
    [ValidateRange(30, 3600)]
    [int]$TimeoutSeconds = 320,

    # Launches the configured Gradle task without requiring a JSON completion
    # report. Use this only while developing the runtime reporter interactively.
    [switch]$LaunchOnly,

    # This is deliberately a dedicated task rather than runClient: the task is
    # responsible for Quick Playing the backup test world and passing the report
    # location to the client.
    [string]$GradleTask = ':modules:versions:mc12111:fabric:runShaderGameTestClient',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fabricRunDirectory = Join-Path $repositoryRoot 'modules/versions/1_21_11/fabric/run'
$artifactDirectory = Join-Path $fabricRunDirectory 'automation'
$reportPath = Join-Path $artifactDirectory 'shader-game-test-report.json'
$latestLogPath = Join-Path $fabricRunDirectory 'logs/latest.log'
$testWorldPath = Join-Path $fabricRunDirectory 'saves/backup/level.dat'
$sessionLockPath = Join-Path (Split-Path -Parent $testWorldPath) 'session.lock'

function Stop-ProcessTree {
    param([int[]]$ProcessIds)

    foreach ($processId in ($ProcessIds | Where-Object { $_ -gt 0 } | Sort-Object -Unique)) {
        $taskKill = Start-Process -FilePath 'taskkill.exe' -ArgumentList @('/PID', $processId, '/T', '/F') -Wait -PassThru -WindowStyle Hidden
        if ($taskKill.ExitCode -ne 0 -and $taskKill.ExitCode -ne 128) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-ReportResult {
    param([string]$Path)

    if (!(Test-Path -LiteralPath $Path)) {
        return $null
    }

    try {
        $report = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{
            IsValid = $false
            Success = $false
            FailureReason = "Invalid JSON completion report: $($_.Exception.Message)"
        }
    }

    $successProperty = $report.PSObject.Properties['success']
    if ($null -eq $successProperty -or $successProperty.Value -isnot [bool]) {
        return [pscustomobject]@{
            IsValid = $false
            Success = $false
            FailureReason = 'Invalid JSON completion report: expected boolean field "success".'
        }
    }

    $failureProperty = $report.PSObject.Properties['failureReason']
    return [pscustomobject]@{
        IsValid = $true
        Success = [bool]$successProperty.Value
        FailureReason = if ($null -eq $failureProperty) { '' } else { [string]$failureProperty.Value }
    }
}

function Read-NewLogContent {
    param(
        [string]$Path,
        [ref]$Offset
    )

    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        $Offset.Value = 0
        return ''
    }

    $logFile = Get-Item -LiteralPath $Path
    if ($logFile.Length -lt $Offset.Value) {
        $Offset.Value = 0
    }
    if ($logFile.Length -eq $Offset.Value) {
        return ''
    }

    $stream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite
    )
    try {
        [void]$stream.Seek($Offset.Value, [System.IO.SeekOrigin]::Begin)
        $byteCount = [int]($logFile.Length - $Offset.Value)
        $buffer = New-Object byte[] $byteCount
        $bytesRead = $stream.Read($buffer, 0, $byteCount)
        $Offset.Value = $stream.Position

        if ($bytesRead -le 0) {
            return ''
        }

        return [System.Text.Encoding]::UTF8.GetString($buffer, 0, $bytesRead)
    } finally {
        $stream.Dispose()
    }
}

function Get-NewShaderFailure {
    param(
        [string]$Path,
        [ref]$Offset
    )

    $content = Read-NewLogContent -Path $Path -Offset $Offset
    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }

    if ($content -match 'Failed to load the shaderpack|Falling back to normal rendering without shaders|unexpected error: failed to read') {
        $details = $content -split "`r?`n" |
                Where-Object {
                    $_ -match 'Failed to load the shaderpack' -or
                    $_ -match 'Falling back to normal rendering without shaders' -or
                    $_ -match 'unexpected error: failed to read' -or
                    $_ -match 'ZipException'
                } |
                Select-Object -Last 8

        return [pscustomobject]@{
            Result = 'shaderpack-load-failed'
            FailureReason = if ($null -eq $details -or $details.Count -eq 0) {
                'Iris failed to load the selected shaderpack.'
            } else {
                $details -join ' '
            }
        }
    }

    if ($content -notmatch 'Failed to create shader rendering pipeline') {
        return $null
    }

    $details = $content -split "`r?`n" |
            Where-Object {
                $_ -match 'Shader compilation log' -or
                $_ -match 'ShaderCompileException' -or
                $_ -match 'GLSL compile failed' -or
                $_ -match 'Failed to create shader rendering pipeline'
            } |
            Select-Object -Last 6

    if ($null -eq $details -or $details.Count -eq 0) {
        return [pscustomobject]@{
            Result = 'shader-pipeline-failed'
            FailureReason = 'Iris failed to create the shader rendering pipeline.'
        }
    }

    return [pscustomobject]@{
        Result = 'shader-pipeline-failed'
        FailureReason = $details -join ' '
    }
}

function Exit-WithFailure {
    param(
        [string]$Result,
        [string]$FailureReason
    )

    Write-Output "watchdogResult=$Result"
    Write-Output "failureReason=$FailureReason"
    exit 1
}

function Test-WorldIsAvailable {
    param([string]$SessionLockPath)

    if (!(Test-Path -LiteralPath $SessionLockPath -PathType Leaf)) {
        return $true
    }

    try {
        $lock = [System.IO.File]::Open(
            $SessionLockPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        $lock.Dispose()
        return $true
    } catch [System.IO.IOException] {
        return $false
    }
}

if (!(Test-Path -LiteralPath $repositoryRoot -PathType Container)) {
    throw "Repository root was not found: $repositoryRoot"
}

if (!(Test-Path -LiteralPath $testWorldPath -PathType Leaf)) {
    Exit-WithFailure -Result 'missing-test-world' -FailureReason "Missing required Quick Play test world: $testWorldPath"
}

if (!(Test-WorldIsAvailable -SessionLockPath $sessionLockPath)) {
    Exit-WithFailure -Result 'test-world-in-use' -FailureReason "The Quick Play test world is already open: $sessionLockPath"
}

$shaderGameTestMutexName = if ([string]::IsNullOrWhiteSpace($env:PHOTONICS_SHADER_GAME_MUTEX_NAME)) {
    'Local\PhotonicEngine-Shader-Game-Test'
} else {
    $env:PHOTONICS_SHADER_GAME_MUTEX_NAME
}
$testMutex = [System.Threading.Mutex]::new($false, $shaderGameTestMutexName)
$mutexAcquired = $false
try {
    try {
        $mutexAcquired = $testMutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] {
        $mutexAcquired = $true
    }

    if (!$mutexAcquired) {
        Exit-WithFailure -Result 'concurrent-run-refused' -FailureReason "Another shader game test owns $artifactDirectory."
    }

    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    if (Test-Path -LiteralPath $reportPath) {
        Remove-Item -LiteralPath $reportPath -Force
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $reason = 'completed'
    $failureReason = ''
    $logReadOffset = 0
    if (Test-Path -LiteralPath $latestLogPath -PathType Leaf) {
        $logReadOffset = (Get-Item -LiteralPath $latestLogPath).Length
    }

    $gradleArgumentList = @()
    if ($GradleArgs) {
        $gradleArgumentList += $GradleArgs
        Write-Output ("shaderGameTestGradleArgs={0}" -f ($GradleArgs -join ' '))
    }
    $gradleArgumentList += @($GradleTask, '--no-daemon')

    try {
        $process = Start-Process -FilePath (Join-Path $repositoryRoot 'gradlew.bat') -ArgumentList $gradleArgumentList -WorkingDirectory $repositoryRoot -PassThru -NoNewWindow
    } catch {
        Exit-WithFailure -Result 'launch-failed' -FailureReason $_.Exception.Message
    }

    $processIds = @($process.Id)
    while (!$process.HasExited) {
        Start-Sleep -Seconds 2
        $process.Refresh()

        if ($processIds.Count -lt 2) {
            $childJavaProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
                Where-Object { $_.ParentProcessId -eq $process.Id } |
                Select-Object -ExpandProperty ProcessId)
            if ($childJavaProcesses.Count -gt 0) {
                $processIds = @($processIds + $childJavaProcesses | Sort-Object -Unique)
            }
        }

        $shaderFailure = Get-NewShaderFailure `
            -Path $latestLogPath `
            -Offset ([ref]$logReadOffset)
        if ($null -ne $shaderFailure) {
            $reason = $shaderFailure.Result
            $failureReason = $shaderFailure.FailureReason
            Stop-ProcessTree -ProcessIds $processIds
            break
        }

        if ((Get-Date) -ge $deadline) {
            $reason = 'timeout'
            Stop-ProcessTree -ProcessIds $processIds
            break
        }
    }

    try {
        [void]$process.WaitForExit(10000)
        $process.Refresh()
    } catch {
        # The process tree was already terminated after the timeout.
    }
    $processExitCode = if ($null -eq $process.ExitCode) { 0 } else { $process.ExitCode }

    if ($reason -eq 'shader-pipeline-failed' -or $reason -eq 'shaderpack-load-failed') {
        Exit-WithFailure -Result $reason -FailureReason $failureReason
    }

    if ($reason -ne 'completed') {
        Exit-WithFailure -Result $reason -FailureReason 'The shader game test did not complete.'
    }

    if ($LaunchOnly) {
        if ($processExitCode -ne 0) {
            Exit-WithFailure -Result 'gradle-failed' -FailureReason "Gradle task failed with exit code $processExitCode."
        }
        Write-Output 'watchdogResult=launch-only-completed'
        exit 0
    }

    if ($processExitCode -ne 0) {
        Exit-WithFailure -Result 'gradle-failed' -FailureReason "Gradle task failed with exit code $processExitCode."
    }

    $reportResult = Get-ReportResult -Path $reportPath
    if ($null -eq $reportResult) {
        Exit-WithFailure -Result 'missing-report' -FailureReason "Runtime automation integration is required: no JSON completion report was written to $reportPath. The reporter must write boolean success and optional failureReason fields before the client exits."
    }

    if (!$reportResult.IsValid) {
        Exit-WithFailure -Result 'invalid-report' -FailureReason $reportResult.FailureReason
    }

    if (!$reportResult.Success) {
        $failureReason = if ([string]::IsNullOrWhiteSpace($reportResult.FailureReason)) { 'The runtime reporter marked the shader game test as unsuccessful.' } else { $reportResult.FailureReason }
        Exit-WithFailure -Result 'report-failure' -FailureReason $failureReason
    }

    Write-Output 'watchdogResult=passed'
    exit 0
} finally {
    if ($mutexAcquired) {
        $testMutex.ReleaseMutex()
    }
    $testMutex.Dispose()
}
