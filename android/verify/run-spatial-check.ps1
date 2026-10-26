# 编译并运行 Spatial.java 的纯 Java 自检（不需要 Android SDK / gradle）。
#
# 用法（在任意目录）：
#   powershell -ExecutionPolicy Bypass -File android\verify\run-spatial-check.ps1
#
# 退出码：0 = 全部 PASS；非 0 = 编译失败或有断言失败。
#
# 只依赖本机 JDK 8（Corretto 1.8.0_504）。Spatial.java 是 C++ 权威实现
# （include/audiohub/spatial.hpp + src/spatial.cpp）的逐公式移植，本脚本把它与
# tests/test_spatial.cpp 的期望值对一遍，确保 Android 端和 PC 端算出同样的结果。

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$jdkBin = 'C:\Users\25516\.jdks\corretto-1.8.0_504\bin'
$javac = Join-Path $jdkBin 'javac.exe'
$java = Join-Path $jdkBin 'java.exe'

$verifyDir = $PSScriptRoot
# android/verify -> 项目根（跨端音频系统）
$projectRoot = Split-Path -Parent (Split-Path -Parent $verifyDir)
$spatialJava = Join-Path $projectRoot 'android\app\src\main\java\com\audiohub\source\Spatial.java'
$checkJava = Join-Path $verifyDir 'SpatialCheck.java'

Write-Host '== AudioHub Android 空间化自检（与 C++ tests/test_spatial.cpp 对齐） =='
Write-Host "JDK      : $jdkBin"
Write-Host "Spatial  : $spatialJava"
Write-Host "SpatialCheck: $checkJava"
Write-Host ''

if (!(Test-Path $javac)) {
    Write-Host "FAIL: 找不到 javac（本机应只有 JDK 8）: $javac"
    exit 1
}
if (!(Test-Path $spatialJava)) {
    Write-Host "FAIL: 找不到 Spatial.java: $spatialJava"
    exit 1
}
if (!(Test-Path $checkJava)) {
    Write-Host "FAIL: 找不到 SpatialCheck.java: $checkJava"
    exit 1
}

$outDir = Join-Path ([System.IO.Path]::GetTempPath()) ('audiohub-spatial-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$exitCode = 0
try {
    # 显式 -source/-target 1.8：项目 gradle 用 Java 17 编译，源码必须保持 Java 8 语法。
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -d $outDir $spatialJava $checkJava 2>&1 |
        ForEach-Object { Write-Host $_ }

    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host 'FAIL: javac 编译失败（Java 8 语法/类型检查未通过）'
        $exitCode = 1
    } else {
        Write-Host 'javac: 编译通过（纯 Java 8，无 android.* 依赖）'
        Write-Host ''

        # -Dfile.encoding=UTF-8：JDK 8 在中文 Windows 上默认用 GBK 写 stdout，
        # 而本脚本把控制台输出编码设成了 UTF-8，不加这个参数中文会变乱码。
        # 注意：Windows PowerShell 5.1 会把不带引号的 -Dxxx=yyy 拆开，必须加引号。
        & $java '-Dfile.encoding=UTF-8' -cp $outDir SpatialCheck
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host "FAIL: SpatialCheck 有失败项（exit=$LASTEXITCODE）"
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