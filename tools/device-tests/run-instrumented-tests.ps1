<#
  在已连接设备上安装并运行 instrumented test（真机验证）。

  为什么不用 `connectedAndroidTest`：
  AGP 安装测试 APK 时**硬编码**带 `-g`（一次性授予全部运行时权限）：

      adb install --bypass-low-target-sdk-block -t -g --user 0 app-debug-androidTest.apk

  MIUI / HyperOS 拒绝这个 flag（普通 adb 没有 INSTALL_GRANT_RUNTIME_PERMISSIONS 特权）：

      SecurityException: You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
        permission to use the PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag

  这是 ROM 侧限制，`installOptions` 改不到 AGP 内部那个默认值。
  本脚本自己装、自己跑，在任何 ROM 上都可用。

  用法（通常由 Gradle 任务 :app:runDeviceTests 调用）：
      .\gradlew.bat :app:runDeviceTests
      .\gradlew.bat :app:runDeviceTests -PclassFilter=fully.qualified.TestClass
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$AppApk,
    [Parameter(Mandatory = $true)][string]$TestApk,
    [Parameter(Mandatory = $true)][string]$TestPackage,
    [string]$Runner = 'androidx.test.runner.AndroidJUnitRunner',
    [string]$ClassFilter = ''
)

$ErrorActionPreference = 'Stop'

Write-Host '==> install app apk'
& $Adb install -r -t $AppApk
if ($LASTEXITCODE -ne 0) { throw 'install app apk failed' }

Write-Host '==> install test apk'
& $Adb install -r -t $TestApk
if ($LASTEXITCODE -ne 0) { throw 'install test apk failed' }

Write-Host '==> run instrumented tests'
$arguments = @('shell', 'am', 'instrument', '-w')
if ($ClassFilter) { $arguments += @('-e', 'class', $ClassFilter) }
$arguments += "$TestPackage/$Runner"

$output = & $Adb @arguments 2>&1
$output | ForEach-Object { Write-Host $_ }
$text = $output -join "`n"

# `am instrument` 即使有用例失败也返回 0，只能看输出判断
if ($text -match 'FAILURES!!!' -or $text -match 'INSTRUMENTATION_FAILED' -or $text -match 'shortMsg=') {
    throw 'device tests reported failures (see output above)'
}
if ($text -notmatch 'OK [(]') {
    throw "no test result recognized (expected something like 'OK (4 tests)'), check the output above"
}

Write-Host '==> device tests passed'
