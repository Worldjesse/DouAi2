[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidateSet('uninjected', 'disabled', 'common', 'heavy')][string]$Scenario,
    [ValidateRange(5, 45)][int]$DurationSeconds = 30,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[a-zA-Z0-9_.:-]+$') { throw 'Invalid device serial.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw 'Android platform-tools adb.exe is required.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $projectRoot 'work/host-performance' }
$name = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + $Scenario + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) $name
New-Item -ItemType Directory -Path $run | Out-Null
$remoteConfig = '/data/local/tmp/douai-perf-' + $name + '.pbtx'
$remoteTrace = '/data/local/tmp/douai-perf-' + $name + '.perfetto-trace'

function Invoke-AdbText {
    param([string[]]$AdbArguments, [string]$FileName)
    $result = & $adb -s $Serial @AdbArguments 2>&1
    $code = $LASTEXITCODE
    $result | Set-Content -LiteralPath (Join-Path $run $FileName) -Encoding UTF8
    if ($code -ne 0) { throw "adb failed; see $FileName" }
    return ($result -join "`n")
}

$manifest = @{
    schemaVersion = 1; status = 'collecting'; scenario = $Scenario; scenarioSource = 'user_declared_not_verified'
    serial = $Serial; durationSeconds = $DurationSeconds; capturedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    hostPackage = 'com.ss.android.ugc.aweme'; modulePackage = 'io.github.yudigaga.douai'
    scope = 'host_system_trace_and_before_after_snapshots_not_module_only_cpu_or_peak_memory'
    devicePerformanceMeasured = $false; batteryImprovementMeasured = $false
}
try {
    $null = Invoke-AdbText @('get-state') 'device-state.txt'
    $null = Invoke-AdbText @('shell', 'getprop', 'ro.build.fingerprint') 'device-build.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'package', 'com.ss.android.ugc.aweme') 'host-package.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'package', 'io.github.yudigaga.douai') 'module-package.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'display') 'display.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'meminfo', 'com.ss.android.ugc.aweme') 'memory-before.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'gfxinfo', 'com.ss.android.ugc.aweme', 'reset') 'frames-reset.txt'
    $config = @"
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: $($DurationSeconds * 1000)
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "power/cpu_idle"
  atrace_categories: "am"
  atrace_categories: "wm"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "dalvik"
  atrace_categories: "binder_driver"
  atrace_apps: "com.ss.android.ugc.aweme"
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
"@
    $configPath = Join-Path $run 'capture.pbtx'
    [IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
    $null = Invoke-AdbText @('push', $configPath, $remoteConfig) 'push-config.txt'
    Write-Output "Capturing $DurationSeconds seconds for declared scenario '$Scenario'. Perform the same host actions for each comparison."
    $null = Invoke-AdbText @('shell', 'perfetto', '--txt', '-c', $remoteConfig, '-o', $remoteTrace) 'perfetto.txt'
    $null = Invoke-AdbText @('pull', $remoteTrace, (Join-Path $run 'host.perfetto-trace')) 'pull-trace.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'gfxinfo', 'com.ss.android.ugc.aweme', 'framestats') 'frames.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'meminfo', 'com.ss.android.ugc.aweme') 'memory-after.txt'
    $null = Invoke-AdbText @('shell', 'dumpsys', 'cpuinfo') 'cpu-after.txt'
    $trace = Get-Item -LiteralPath (Join-Path $run 'host.perfetto-trace')
    if ($trace.Length -eq 0) { throw 'Empty system trace.' }
    $manifest.traceSha256 = (Get-FileHash -LiteralPath $trace.FullName -Algorithm SHA256).Hash
    $manifest.traceBytes = $trace.Length
    $manifest.status = 'captured_pending_comparative_analysis'
    $manifest.devicePerformanceMeasured = $true
} catch {
    $manifest.status = 'failed'; $manifest.failure = $_.Exception.Message
    throw
} finally {
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'manifest.json') -Encoding UTF8
    # Only remove the two exact temporary files created by this invocation.
    & $adb -s $Serial shell rm -f $remoteConfig $remoteTrace 2>&1 | Out-Null
    Write-Output ('Evidence: ' + $run)
}
