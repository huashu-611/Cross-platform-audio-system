# 编译并运行 Scene3D.java 的纯 Java 自检（不需要 Android SDK / gradle）。
#
# 用法（在任意目录）：
#   powershell -ExecutionPolicy Bypass -File android\verify\run-scene-check.ps1
#
# 退出码：0 = 全部 PASS；非 0 = 编译失败或有断言失败。
#
# 只依赖本机 JDK 8（Corretto 1.8.0_504）。Scene3D.java 是 PC 端 ui/scene3d.js
# （v1.3，物理房间 + Canvas2D 透视渲染的纯函数部分）的逐函数移植；本脚本把它
# 与 Node 直接跑 scene3d.js 得到的期望值对一遍，确保 Android 端相机/投影/反投影/
# 32 槽位/听者方位/球半径/槽位分配与 PC 端逐数值一致（abs 1e-9 量级）。

# 注意：这里必须是 'Continue' 而不是 'Stop'。Windows PowerShell 5.1 会把原生程序
# （javac/java）写到 stderr 的任何一行包装成 NativeCommandError；若开启 Stop，
# 例如设置了 JAVA_TOOL_OPTIONS 时 JDK 打印的 "Picked up JAVA_TOOL_OPTIONS: ..."
# 就会让脚本在编译前异常退出。失败与否统一由 $LASTEXITCODE 判断。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$jdkBin = 'C:\Users\25516\.jdks\corretto-1.8.0_504\bin'
$javac = Join-Path $jdkBin 'javac.exe'
$java = Join-Path $jdkBin 'java.exe'

$verifyDir = $PSScriptRoot
# android/verify -> 项目根（跨端音频系统）
$projectRoot = Split-Path -Parent (Split-Path -Parent $verifyDir)
$sceneJava = Join-Path $projectRoot 'android\app\src\main\java\com\audiohub\source\Scene3D.java'
$checkJava = Join-Path $verifyDir 'SceneCheck.java'

Write-Host '== AudioHub Android 3D 场景自检（与 PC ui/scene3d.js 对齐） =='
Write-Host "JDK        : $jdkBin"
Write-Host "Scene3D    : $sceneJava"
Write-Host "SceneCheck : $checkJava"
Write-Host ''

if (!(Test-Path $javac)) {
    Write-Host "FAIL: 找不到 javac（本机应只有 JDK 8）: $javac"
    exit 1
}
if (!(Test-Path $sceneJava)) {
    Write-Host "FAIL: 找不到 Scene3D.java: $sceneJava"
    exit 1
}
if (!(Test-Path $checkJava)) {
    Write-Host "FAIL: 找不到 SceneCheck.java: $checkJava"
    exit 1
}

$outDir = Join-Path ([System.IO.Path]::GetTempPath()) ('audiohub-scene-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$exitCode = 0
try {
    # 显式 -source/-target 1.8：项目 gradle 用 Java 17 编译，源码必须保持 Java 8 语法。
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -d $outDir $sceneJava $checkJava 2>&1 |
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
        & $java '-Dfile.encoding=UTF-8' -cp $outDir com.audiohub.source.SceneCheck
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host "FAIL: SceneCheck 有失败项（exit=$LASTEXITCODE）"
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