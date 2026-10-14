<#
.SYNOPSIS
    Run one instrumented test class/method on the connected device, with a hard timeout.

.DESCRIPTION
    `gradlew :app:runDeviceTests` has no timeout, so a hanging test blocks forever.
    This script calls adb + am instrument directly, kills the run on timeout and
    reports the partial output, which makes bisecting a hang practical.

    NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less files as
    ANSI, so non-ASCII characters here break parsing (hit twice already).

.EXAMPLE
    powershell -File tools/device-tests/run-one.ps1 -Class com.haruhi.haruhibox.core.database.MigrationsTest
    powershell -File tools/device-tests/run-one.ps1 -Class "com.x.Y#method" -TimeoutSec 60
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Class,
    [int]$TimeoutSec = 120,
    [string]$Adb = "D:\Environment\Android\Sdk\platform-tools\adb.exe",
    # Always pass this explicitly: PowerShell 5.1 leaves $PSScriptRoot empty for -File,
    # so deriving it here is not reliable.
    [string]$ProjectRoot = 'D:\Project\HaruhiBox'
)

$ErrorActionPreference = 'Stop'

$appApk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $ProjectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$testPackage = 'com.haruhi.haruhibox.debug.test'
$targetPackage = 'com.haruhi.haruhibox.debug'

foreach ($f in @($appApk, $testApk)) {
    if (-not (Test-Path $f)) { throw "missing APK: $f (run :app:assembleDebug :app:assembleDebugAndroidTest first)" }
}

Write-Host '==> install'
& $Adb install -r -t $appApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'install app failed' }
& $Adb install -r -t $testApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'install test failed' }

Write-Host "==> run $Class (timeout ${TimeoutSec}s)"
$outFile = Join-Path $env:TEMP 'run-one-out.txt'
$errFile = Join-Path $env:TEMP 'run-one-err.txt'
Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue

$proc = Start-Process -FilePath $Adb -ArgumentList @(
    'shell', 'am', 'instrument', '-w', '-e', 'class', $Class,
    "$testPackage/androidx.test.runner.AndroidJUnitRunner"
) -RedirectStandardOutput $outFile -RedirectStandardError $errFile -PassThru -NoNewWindow

$finished = $proc.WaitForExit($TimeoutSec * 1000)

if (-not $finished) {
    Write-Host "==> TIMEOUT after ${TimeoutSec}s - the test is HANGING" -ForegroundColor Red
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    & $Adb shell am force-stop $targetPackage | Out-Null
    & $Adb shell am force-stop $testPackage | Out-Null
    Write-Host '--- partial output ---'
    if (Test-Path $outFile) { Get-Content $outFile -Tail 20 }
    exit 2
}

Write-Host '--- output ---'
if (Test-Path $outFile) { Get-Content $outFile }
$text = ''
if (Test-Path $outFile) { $text += (Get-Content $outFile -Raw) }
if (Test-Path $errFile) { $text += (Get-Content $errFile -Raw) }
& $Adb shell am force-stop $testPackage | Out-Null

if ($text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') {
    Write-Host '==> FAILED' -ForegroundColor Red
    exit 1
}
if ($text -match 'OK \(') {
    Write-Host '==> PASSED' -ForegroundColor Green
    exit 0
}
Write-Host '==> UNKNOWN RESULT' -ForegroundColor Yellow
exit 3
