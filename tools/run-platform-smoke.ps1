[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Adb = '',
    [string]$OutputRoot = '',
    [switch]$Build,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $Adb) {
    $sdkAdb = Join-Path $SdkRoot 'platform-tools/adb.exe'
    if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) { $Adb = $sdkAdb }
    else { $Adb = (Get-Command adb -ErrorAction Stop).Source }
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $projectRoot 'artifacts/platform-smoke' }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$runToken = 'smoke-' + [guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $OutputRoot $runToken
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$package = 'io.github.yudigaga.douai'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$summary = [ordered]@{
    schemaVersion = 1
    run = $runToken
    startedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    serial = $Serial
    sdkRoot = $SdkRoot
    scope = 'module_android_platform_fixture'
    status = 'running'
    device = [ordered]@{}
    metrics = @()
    uncovered = @('LSPosed callbacks and host UI behavior', 'Host startup time and scroll frame rate',
        'Network providers, accounts and real automation submissions', 'Native/GPU memory peaks and sustained performance')
}
$previousDirectory = Get-Location
$previousAndroidHome = $env:ANDROID_HOME
$timer = [Diagnostics.Stopwatch]::StartNew()

function Invoke-SmokeTool {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath = '', [switch]$AllowFailure)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($LogPath) { $lines | Set-Content -LiteralPath $LogPath -Encoding UTF8 }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "Command failed with exit code $exitCode ($Executable). See $LogPath"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Lines = $lines }
}

function Read-DeviceProperty {
    param([string]$Name)
    $result = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'shell', 'getprop', $Name)
    return ($result.Lines -join "`n").Trim()
}

function Read-InstalledApks {
    param([string]$ApplicationId)
    $paths = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $ApplicationId)
    $facts = @()
    foreach ($line in $paths.Lines) {
        if ($line -notmatch '^package:(/[A-Za-z0-9_./=+~-]+\.apk)$') { throw "Unexpected installed APK path for $ApplicationId" }
        $devicePath = $Matches[1]
        $digest = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'shell', 'sha256sum', $devicePath)
        if (($digest.Lines -join "`n") -notmatch '^([0-9a-fA-F]{64})\s+') { throw "Cannot hash installed APK for $ApplicationId" }
        $facts += [ordered]@{ package = $ApplicationId; devicePath = $devicePath; sha256 = $Matches[1].ToLowerInvariant() }
    }
    if ($facts.Count -eq 0) { throw "Package is not installed: $ApplicationId" }
    return $facts
}

try {
    Set-Location -LiteralPath $projectRoot
    $state = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'get-state')
    if (($state.Lines -join '').Trim() -ne 'device') { throw 'The specified serial is not an online authorized Android device.' }
    $summary.device = [ordered]@{
        sdk = [int](Read-DeviceProperty 'ro.build.version.sdk')
        release = Read-DeviceProperty 'ro.build.version.release'
        manufacturer = Read-DeviceProperty 'ro.product.manufacturer'
        model = Read-DeviceProperty 'ro.product.model'
        fingerprint = Read-DeviceProperty 'ro.build.fingerprint'
        abi = Read-DeviceProperty 'ro.product.cpu.abi'
        emulator = (Read-DeviceProperty 'ro.kernel.qemu') -eq '1'
    }
    if ($Build) {
        $env:ANDROID_HOME = $SdkRoot
        Invoke-SmokeTool -Executable (Join-Path $projectRoot 'gradlew.bat') -Arguments @(':app:assembleDebug',
            ':app:assembleDebugAndroidTest', '--max-workers=1', '--console=plain') -LogPath (Join-Path $runDirectory 'build.log') | Out-Null
    }
    $apks = @(
        (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'),
        (Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    )
    if ($Install) {
        foreach ($apk in $apks) {
            if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing APK: $apk. Build it first or pass -Build." }
            Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'install', '-r', $apk) `
                -LogPath (Join-Path $runDirectory ((Split-Path -Leaf $apk) + '.install.log')) | Out-Null
        }
    }
    $summary.installedThisRun = [bool]$Install
    $summary.localApks = @($apks | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $summary.installedApks = @((Read-InstalledApks $package)) + @((Read-InstalledApks "$package.test"))
    $instrumentation = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'com.douai.module.ui.PlatformSmokeTest', '-e', 'douaiSmokeRun', $runToken, $runner) `
        -LogPath (Join-Path $runDirectory 'instrumentation.log') -AllowFailure
    $summary.instrumentationExitCode = $instrumentation.ExitCode
    $metrics = Invoke-SmokeTool -Executable $Adb -Arguments @('-s', $Serial, 'shell', 'run-as', $package,
        'cat', "files/platform-smoke/$runToken.jsonl") -LogPath (Join-Path $runDirectory 'metrics.jsonl') -AllowFailure
    if ($metrics.ExitCode -ne 0) { throw 'Metrics could not be read from the installed debug application; inspect instrumentation.log.' }
    $summary.metrics = @($metrics.Lines | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $expected = @('redactedBackupRoundTripsUsingAndroidRegexAndIsolatedPreferences',
        'ownedMediaStorePngDecodesAndIsRemoved', 'ownedMediaStorePdfParsesAndIsRemoved')
    foreach ($testName in $expected) {
        if (@($summary.metrics | Where-Object { $_.test -eq $testName }).Count -ne 1) { throw "Missing or duplicate platform result: $testName" }
    }
    $output = $instrumentation.Lines -join "`n"
    if ($instrumentation.ExitCode -ne 0 -or $output -notmatch 'OK \(\d+ tests?\)' -or
        $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED' -or @($summary.metrics | Where-Object { $_.status -eq 'failed' }).Count -gt 0) {
        throw 'Android instrumentation did not complete successfully; inspect instrumentation.log and metrics.jsonl.'
    }
    $skipped = @($summary.metrics | Where-Object { $_.status -eq 'skipped' })
    if ($skipped.Count -gt 0) {
        $summary.status = 'partial'
        $summary.uncovered += 'API 28 MediaStore alternative requires interactive SAF selection; PNG/PDF provider writes remain unverified'
    } else { $summary.status = 'passed' }
} catch {
    $summary.status = 'failed'
    $summary.error = $_.Exception.Message
    throw
} finally {
    $timer.Stop()
    $summary.elapsedMillis = $timer.ElapsedMilliseconds
    $summary.completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runDirectory 'summary.json') -Encoding UTF8
    $env:ANDROID_HOME = $previousAndroidHome
    Set-Location -LiteralPath $previousDirectory.Path
    Write-Host "Platform smoke $($summary.status): $runDirectory"
}
