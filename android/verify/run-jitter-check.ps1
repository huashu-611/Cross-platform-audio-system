# 编译并运行 JitterBuffer.java 的纯 Java 自检（不需要 Android SDK / gradle）。
#
# 用法（在任意目录）：
#   powershell -ExecutionPolicy Bypass -File android\verify\run-jitter-check.ps1
#
# 退出码：0 = 全部 PASS；非 0 = 编译失败或有断言失败。
#
# 覆盖 v0.15 修的 bug：切换汇聚端后对端换了音频时间轴（音源进程重启时
# sample_pos 从头开始），而抖动缓冲还停在上一条时间轴的 readPos 上，
# 于是每个包都被判成"过期/超窗"丢掉 → 永远凑不满预填 → 彻底没声。

# 与 run-scene-check.ps1 同理：必须是 'Continue'，否则原生程序写到 stderr
# 的任何一行都会被 Windows PowerShell 5.1 包成 NativeCommandError 而中止。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$jdkBin = 'C:\Users\25516\.jdks\corretto-1.8.0_504\bin'
$javac = Join-Path $jdkBin 'javac.exe'
$java = Join-Path $jdkBin 'java.exe'

$verifyDir = $PSScriptRoot
# android/verify -> 项目根（跨端音频系统）
$projectRoot = Split-Path -Parent (Split-Path -Parent $verifyDir)
$jitterJava = Join-Path $projectRoot 'android\app\src\main\java\com\audiohub\source\JitterBuffer.java'
$checkJava = Join-Path $verifyDir 'JitterCheck.java'

Write-Host '== AudioHub Android 抖动缓冲自检（时间轴锚定 / 断档重锚 / 乱序丢弃） =='
Write-Host "JDK          : $jdkBin"
Write-Host "JitterBuffer : $jitterJava"
Write-Host "JitterCheck  : $checkJava"
Write-Host ''

if (!(Test-Path $javac)) {
    Write-Host "FAIL: 找不到 javac（本机应只有 JDK 8）: $javac"
    exit 1
}
if (!(Test-Path $jitterJava)) {
    Write-Host "FAIL: 找不到 JitterBuffer.java: $jitterJava"
    exit 1
}
if (!(Test-Path $checkJava)) {
    Write-Host "FAIL: 找不到 JitterCheck.java: $checkJava"
    exit 1
}

$outDir = Join-Path ([System.IO.Path]::GetTempPath()) ('audiohub-jitter-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$exitCode = 0
try {
    # 显式 -source/-target 1.8：项目 gradle 用 Java 17 编译，源码必须保持 Java 8 语法。
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -d $outDir $jitterJava $checkJava 2>&1 |
        ForEach-Object { Write-Host $_ }

    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host 'FAIL: javac 编译失败（Java 8 语法/类型检查未通过）'
        $exitCode = 1
    } else {
        Write-Host 'javac: 编译通过（纯 Java 8，无 android.* 依赖）'
        Write-Host ''

        # -Dfile.encoding=UTF-8：JDK 8 在中文 Windows 上默认用 GBK 写 stdout。
        # Windows PowerShell 5.1 会把不带引号的 -Dxxx=yyy 拆开，必须加引号。
        & $java '-Dfile.encoding=UTF-8' -cp $outDir com.audiohub.source.JitterCheck
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host "FAIL: JitterCheck 有失败项（exit=$LASTEXITCODE）"
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
