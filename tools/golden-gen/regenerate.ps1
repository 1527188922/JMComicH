<#
.SYNOPSIS
    重新生成 Android 工程的黄金用例数据。

.DESCRIPTION
    黄金数据由**参考实现（haruhibot-server）的编译产物**产出，不修改其源码：
    脚本先用 maven-dependency-plugin 解析参考工程的完整 classpath，再编译并运行
    tools/golden-gen/GoldenGen.java（通过反射/公开方法调用 JmcomicService、
    FileUtil、BilibiliIdConverter）。

    产物写入 app/src/test/resources/golden/：
      - golden.txt                 文本用例（签名/AES/块数/文件名清洗/排序/BV-AV）
      - descramble/*.png           解扰输入与参考输出（逐像素比对用）

    运行前提：参考工程已构建过（存在 target/classes），本机有 JDK 21 与 Maven。

.EXAMPLE
    pwsh -File tools/golden-gen/regenerate.ps1
#>
[CmdletBinding()]
param(
    [string]$ReferenceRoot = "D:\JavaProject\haruhibot-server",
    [string]$JdkHome = "D:\Environment\jdk-21.0.4",
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = "Stop"

$javac = Join-Path $JdkHome "bin\javac.exe"
$java = Join-Path $JdkHome "bin\java.exe"
foreach ($tool in @($javac, $java)) {
    if (-not (Test-Path $tool)) { throw "找不到 $tool，请用 -JdkHome 指定 JDK 21" }
}
if (-not (Test-Path (Join-Path $ReferenceRoot "target\classes"))) {
    throw "参考工程未构建：$ReferenceRoot\target\classes 不存在，请先 mvn -DskipTests package"
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) "jmcomic-golden"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$cpFile = Join-Path $work "reference-classpath.txt"

Write-Host "[1/4] 解析参考工程 classpath ..."
Push-Location $ReferenceRoot
try {
    & mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw "mvn dependency:build-classpath 失败" }
} finally {
    Pop-Location
}

$classpath = "$work\classes;$ReferenceRoot\target\classes;" + (Get-Content $cpFile -Raw).Trim()

Write-Host "[2/4] 编译 GoldenGen ..."
$classesDir = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
& $javac -encoding UTF-8 -nowarn -cp $classpath -d $classesDir (Join-Path $PSScriptRoot "GoldenGen.java")
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "[3/4] 运行 GoldenGen ..."
$outDir = Join-Path $work "out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
& $java -cp "$classesDir;$classpath" GoldenGen $outDir
if ($LASTEXITCODE -ne 0) { throw "GoldenGen 运行失败" }

Write-Host "[4/4] 复制到 app/src/test/resources/golden ..."
$goldenDir = Join-Path $ProjectRoot "app\src\test\resources\golden"
$descrambleDir = Join-Path $goldenDir "descramble"
New-Item -ItemType Directory -Force -Path $descrambleDir | Out-Null
Copy-Item (Join-Path $outDir "golden.txt") $goldenDir -Force
Copy-Item (Join-Path $outDir "descramble_*.png") $descrambleDir -Force

Write-Host "完成：$goldenDir"
