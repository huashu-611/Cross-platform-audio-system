# 编译并运行 GraphFit.java（俯视图「视野自适应」）的纯 Java 数值自检（不需要 Android SDK）。
#
# 用法（在任意目录）：
#   powershell -ExecutionPolicy Bypass -File android\verify\run-graphfit-check.ps1
#
# 退出码：0 = 全部 PASS；非 0 = 编译失败或有断言失败。
#
# GraphFit 是 HubGraphView 的纯数学部分：给定画布尺寸/顶部预留/节点世界坐标/标签盒，
# 求 1 米 = 多少像素（ppu）与视野半径。本脚本覆盖：
#   · 没有节点 = 2.6 m 近景观感；近处节点不放大；
#   · 10 m / 100 m / ±320 m / 对角远处节点的球与标签都在画布内（12% 边距）；
#   · 屏幕上方位角 == 世界方位角；1 m 与 2.4 m 环比例恒为 2.4（共用同一个 ppu）；
#   · 随机 500 组 × 6 种画布全部装得下。

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$jdkBin = 'C:\Users\25516\.jdks\corretto-1.8.0_504\bin'
$javac = Join-Path $jdkBin 'javac.exe'
$java = Join-Path $jdkBin 'java.exe'

$verifyDir = $PSScriptRoot
# android/verify -> 项目根（跨端音频系统）
$projectRoot = Split-Path -Parent (Split-Path -Parent $verifyDir)
$graphFitJava = Join-Path $projectRoot 'android\app\src\main\java\com\audiohub\source\GraphFit.java'
$checkJava = Join-Path $verifyDir 'GraphFitCheck.java'

Write-Host '== AudioHub Android 俯视图视野自适应自检（GraphFit） =='
Write-Host "JDK      : $jdkBin"
Write-Host "GraphFit : $graphFitJava"
Write-Host "GraphFitCheck: $checkJava"
Write-Host ''

if (!(Test-Path $javac)) {
    Write-Host "FAIL: 找不到 javac（本机应只有 JDK 8）: $javac"
    exit 1
}
if (!(Test-Path $graphFitJava)) {
    Write-Host "FAIL: 找不到 GraphFit.java: $graphFitJava"
    exit 1
}
if (!(Test-Path $checkJava)) {
    Write-Host "FAIL: 找不到 GraphFitCheck.java: $checkJava"
    exit 1
}

$outDir = Join-Path ([System.IO.Path]::GetTempPath()) ('audiohub-graphfit-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$exitCode = 0
try {
    # 显式 -source/-target 1.8：项目 gradle 用 Java 17 编译，源码必须保持 Java 8 语法。
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -d $outDir $graphFitJava $checkJava 2>&1 |
        ForEach-Object { Write-Host $_ }

    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host 'FAIL: javac 编译失败（Java 8 语法/类型检查未通过）'
        $exitCode = 1
    } else {
        Write-Host 'javac: 编译通过（纯 Java 8，无 android.* 依赖）'
        Write-Host ''

        # -Dfile.encoding=UTF-8：JDK 8 在中文 Windows 上默认用 GBK 写 stdout。
        # 注意：Windows PowerShell 5.1 会把不带引号的 -Dxxx=yyy 拆开，必须加引号。
        & $java '-Dfile.encoding=UTF-8' -cp $outDir GraphFitCheck
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host "FAIL: GraphFitCheck 有失败项（exit=$LASTEXITCODE）"
            $exitCode = 1
        } else {
            Write-Host ''
            Write-Host 'PASS: 全部检查通过'
        }
    }
} finally {
    Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
}

exit $exitCode