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

$testMutex = [System.Threading.Mutex]::new($false, 'Local\PhotonicEngine-Shader-Game-Test')
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

        if ((Get-Date) -ge $deadline) {
            $reason = 'timeout'
            Stop-ProcessTree -ProcessIds $processIds
            break
        }
    }

    try {
        Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue
    } catch {
        # The process tree was already terminated after the timeout.
    }

    if ($reason -ne 'completed') {
        Exit-WithFailure -Result $reason -FailureReason 'The shader game test did not complete.'
    }

    if ($LaunchOnly) {
        if ($process.ExitCode -ne 0) {
            Exit-WithFailure -Result 'gradle-failed' -FailureReason "Gradle task failed with exit code $($process.ExitCode)."
        }
        Write-Output 'watchdogResult=launch-only-completed'
        exit 0
    }

    if ($process.ExitCode -ne 0) {
        Exit-WithFailure -Result 'gradle-failed' -FailureReason "Gradle task failed with exit code $($process.ExitCode)."
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
