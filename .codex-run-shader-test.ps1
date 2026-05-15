param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$GradleArgs
)

# Force console output to UTF-8 so Write-Output never hits
# "Windows stdio does not support writing non-UTF-8 byte sequences".
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding            = [System.Text.Encoding]::UTF8

$stdout    = Join-Path (Get-Location) 'run/runClient.stdout.log'
$stderr    = Join-Path (Get-Location) 'run/runClient.stderr.log'
$latestLog = Join-Path (Get-Location) 'modules/versions/1_21_1/fabric/run/logs/latest.log'

$fatalPatterns = @(
  'Failed to create shader rendering pipeline',
  'The shaderpack failed to load!',
  'MixinApplyError',
  'InvalidMixinException',
  'ClassNotFoundException',
  'Could not execute entrypoint stage ''preLaunch''',
  'A mod crashed on startup!',
  '---- Minecraft Crash Report ----',
  'Description: Unexpected error',
  'java.lang.UnsupportedOperationException',
  'java.lang.ClassCastException',
  'java.lang.NullPointerException',
  'java.lang.AssertionError',
  'java.lang.IllegalStateException',
  'An exception was throw during chunk compilation'
)

$successMarkers = @(
  'Loading Minecraft 1.21.1',
  'photonics ',
  '(Iris) Creating pipeline for dimension'
)

function Convert-ToCleanUtf8Text {
  param([string]$Text)

  if ([string]::IsNullOrEmpty($Text)) {
    return ''
  }

  # Keep only characters that are guaranteed safe for a UTF-8 console:
  #   - Tab (0x09), LF (0x0A), CR (0x0D)
  #   - Printable ASCII (0x20 .. 0x7E)
  # Everything else (high bytes from locale encodings, surrogates,
  # control chars) is replaced with '?' so the message stays readable
  # without ever producing non-UTF-8 byte sequences on output.
  $cleanBuilder = New-Object System.Text.StringBuilder($Text.Length)
  foreach ($char in $Text.ToCharArray()) {
    $code = [int][char]$char
    if ($char -eq "`r" -or $char -eq "`n" -or $char -eq "`t" -or ($code -ge 0x20 -and $code -le 0x7E)) {
      [void]$cleanBuilder.Append($char)
    } else {
      [void]$cleanBuilder.Append('?')
    }
  }

  return $cleanBuilder.ToString()
}

function Stop-ProcessTree {
  param([int[]]$ProcessIds)

  foreach ($processId in ($ProcessIds | Where-Object { $_ -and $_ -gt 0 } | Sort-Object -Unique)) {
    $taskkill = Start-Process -FilePath 'taskkill.exe' -ArgumentList @('/PID', $processId, '/T', '/F') -Wait -PassThru -WindowStyle Hidden
    if ($taskkill.ExitCode -ne 0 -and $taskkill.ExitCode -ne 128) {
      Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
  }
}

function Read-NewContent {
  param(
    [string]$Path,
    [ref]$Position
  )

  if (!(Test-Path $Path)) {
    return ''
  }

  $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try {
    if ($Position.Value -gt $stream.Length) {
      $Position.Value = 0L
    }

    $null = $stream.Seek($Position.Value, [System.IO.SeekOrigin]::Begin)
    # Use Latin-1 (ISO-8859-1) so every byte round-trips without
    # throwing on invalid UTF-8 sequences produced by Java / native code.
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::GetEncoding('iso-8859-1'))
    try {
      $content = $reader.ReadToEnd()
      $Position.Value = $stream.Position
      return (Convert-ToCleanUtf8Text -Text $content)
    } finally {
      $reader.Dispose()
    }
  } finally {
    $stream.Dispose()
  }
}

function Get-FatalReason {
  param(
    [string]$Content,
    [string]$ChunkName
  )

  foreach ($pattern in $fatalPatterns) {
    if ($Content.Contains($pattern)) {
      return "fatal:${pattern}:$ChunkName"
    }
  }

  if ($Content.Contains('Shader compilation log for') -and $Content -match '(?is)Shader compilation log for.*?\berror\b') {
    return "fatal:Shader compilation log for:$ChunkName"
  }

  return $null
}

function Write-FailureDiagnostics {
  param(
    [string]$Reason,
    [string]$FatalContent
  )

  Write-Output "watchdogResult=$Reason"

  if (-not [string]::IsNullOrEmpty($FatalContent)) {
    Write-Output '--- fatal output ---'
    Write-Output $FatalContent
    return
  }

  if (Test-Path $stdout)    { Write-Output '--- stdout tail ---';     Get-Content $stdout    -Tail 60  -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object { Convert-ToCleanUtf8Text -Text $_ } }
  if (Test-Path $stderr)    { Write-Output '--- stderr tail ---';     Get-Content $stderr    -Tail 60  -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object { Convert-ToCleanUtf8Text -Text $_ } }
  if (Test-Path $latestLog) { Write-Output '--- latest.log tail ---'; Get-Content $latestLog -Tail 140 -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object { Convert-ToCleanUtf8Text -Text $_ } }
}

$runDir = Split-Path $stdout -Parent
if (-not (Test-Path $runDir)) {
  New-Item -ItemType Directory -Path $runDir -Force | Out-Null
}

# Remove previous watchdog-owned stdout/stderr logs so we start clean.
foreach ($path in @($stdout, $stderr)) {
  if (Test-Path $path) {
    try {
      Remove-Item $path -Force -ErrorAction Stop
    } catch {
      Write-Output ("watchdog: log busy, reusing {0}" -f $path)
    }
  }
}

# Delete previous latest.log so success detection only sees fresh lines.
if (Test-Path $latestLog) {
  try {
    Remove-Item $latestLog -Force -ErrorAction Stop
  } catch {
    Write-Output ("watchdog: latest.log busy, could not delete: {0}" -f $latestLog)
  }
}

$logPosition    = 0L
$stdoutPosition = 0L
$stderrPosition = 0L
$deadline       = (Get-Date).AddSeconds(480)
$reason         = 'completed'
$fatalChunkContent = ''

# Accumulated latest.log content for success-marker tracking across reads.
$latestLogAccumulated = ''
$latestLogNotPresentLogged = $false
$latestLogFirstSeenTime    = $null
$markersFoundAt            = $null
$postSuccessGraceSeconds   = 60

$gradleArgumentList = @(':modules:versions:mc1211:fabric:runClient', '--no-daemon')
if ($GradleArgs) {
  $gradleArgumentList += $GradleArgs
}

$startTime = Get-Date
$proc = Start-Process -FilePath '.\gradlew.bat' -ArgumentList $gradleArgumentList -WorkingDirectory (Get-Location) -PassThru -NoNewWindow -RedirectStandardOutput $stdout -RedirectStandardError $stderr
if ($null -eq $proc) {
  Write-Output 'watchdog: failed to start gradle process'
  exit 1
}
$processIds = @($proc.Id)

while ($true) {
  Start-Sleep -Seconds 2
  $procExited = $false
  try {
    $procExited = $proc.HasExited
  } catch {
    $procExited = $true
  }
  if ($procExited) { break }

  if ($processIds.Count -lt 2) {
    $childJavaProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
      Where-Object { $_.ParentProcessId -eq $proc.Id } |
      Select-Object -ExpandProperty ProcessId)
    if ($childJavaProcesses.Count -gt 0) {
      $processIds = @($processIds + $childJavaProcesses | Sort-Object -Unique)
    }
  }

  if (-not (Test-Path $latestLog)) {
    if (-not $latestLogNotPresentLogged -and ((Get-Date) - $startTime).TotalSeconds -ge 60) {
      Write-Output 'latest.log not yet present, still waiting...'
      $latestLogNotPresentLogged = $true
    }
  } elseif ($null -eq $latestLogFirstSeenTime) {
    $latestLogFirstSeenTime = Get-Date
  }

  $chunks = @(
    @{ Name = 'stdout';     Content = (Read-NewContent -Path $stdout     -Position ([ref]$stdoutPosition)) },
    @{ Name = 'stderr';     Content = (Read-NewContent -Path $stderr     -Position ([ref]$stderrPosition)) },
    @{ Name = 'latest.log'; Content = (Read-NewContent -Path $latestLog  -Position ([ref]$logPosition))   }
  )

  foreach ($chunk in $chunks) {
    if ([string]::IsNullOrEmpty($chunk.Content)) {
      continue
    }

    $fatalReason = Get-FatalReason -Content $chunk.Content -ChunkName $chunk.Name
    if ($null -ne $fatalReason) {
      $reason = $fatalReason
      $fatalChunkContent = $chunk.Content
      Stop-ProcessTree -ProcessIds $processIds
      break
    }
  }

  if ($reason -ne 'completed') {
    break
  }

  # Accumulate new latest.log content and test for all success markers.
  $newLatestLogContent = ($chunks | Where-Object { $_.Name -eq 'latest.log' } | Select-Object -ExpandProperty Content)
  if (-not [string]::IsNullOrEmpty($newLatestLogContent)) {
    $latestLogAccumulated += $newLatestLogContent
  }

  $allMarkersFound = $true
  foreach ($marker in $successMarkers) {
    if (-not $latestLogAccumulated.Contains($marker)) {
      $allMarkersFound = $false
      break
    }
  }

  if ($allMarkersFound -and $null -eq $markersFoundAt) {
    $markersFoundAt = Get-Date
    Write-Output '--- success markers detected (entering render grace period) ---'
    foreach ($marker in $successMarkers) {
      Write-Output "  marker: $marker"
    }
    Write-Output ("  grace: monitoring fatal patterns for {0}s before declaring success" -f $postSuccessGraceSeconds)
  }

  if ($null -ne $markersFoundAt -and ((Get-Date) - $markersFoundAt).TotalSeconds -ge $postSuccessGraceSeconds) {
    Write-Output '--- render grace period elapsed without fatal patterns ---'
    Stop-ProcessTree -ProcessIds $processIds
    try { Wait-Process -Id $proc.Id -Timeout 10 -ErrorAction SilentlyContinue } catch {}
    exit 0
  }

  if ((Get-Date) -ge $deadline) {
    $reason = 'timeout'
    Stop-ProcessTree -ProcessIds $processIds
    break
  }
}

try { Wait-Process -Id $proc.Id -Timeout 10 -ErrorAction SilentlyContinue } catch {}

Write-FailureDiagnostics -Reason $reason -FatalContent $fatalChunkContent
exit 1
