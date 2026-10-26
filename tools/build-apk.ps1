# Windows-native APK build script (equivalent to android/build-apk.sh).
# Why: no bash on PATH, and the SDK/JDK live outside the repo.
# Keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI without BOM).
#
# Toolchain:
#   ANDROID SDK : C:\tmp_audiohub\android\sdk
#     android-35  = platform (android.jar)
#     android-15  = build-tools (aapt2 / zipalign / d8.jar / apksigner.jar)
#     jdk-17...   = JDK 17 (system java is 8 and d8 needs 17)
#
# Usage: powershell -NoProfile -File tools/build-apk.ps1

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Sdk = 'C:\tmp_audiohub\android\sdk'
$JdkBin = "$Sdk\jdk-17.0.20.1+1\bin"
$AndroidJar = "$Sdk\android-35\android.jar"
$Bt = "$Sdk\android-15"
$Pkg = 'com.audiohub.source'

foreach ($p in @("$JdkBin\javac.exe", "$JdkBin\java.exe", "$JdkBin\keytool.exe",
                 $AndroidJar, "$Bt\aapt2.exe", "$Bt\zipalign.exe",
                 "$Bt\lib\d8.jar", "$Bt\lib\apksigner.jar")) {
  if (-not (Test-Path $p)) { throw "missing Android toolchain file: $p" }
}

Set-Location (Join-Path $Root 'android')
$Work = (Get-Location).Path
$Out = 'build'
$OutAbs = Join-Path $Work $Out
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force -Path "$Out\classes", "$Out\dex" | Out-Null

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Write-Host '==> 1/6 inject package attribute into temporary manifest'
$manifestPath = (Resolve-Path 'app\src\main\AndroidManifest.xml').Path
$manifest = [System.IO.File]::ReadAllText($manifestPath, $Utf8NoBom)
$needle = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
if ($manifest.IndexOf($needle) -lt 0) { throw 'unexpected AndroidManifest.xml header' }
$replacement = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"' + "`r`n" +
  '    package="' + $Pkg + '"' + "`r`n" +
  '    android:versionCode="1"' + "`r`n" +
  '    android:versionName="0.1">'
$manifest = $manifest.Replace($needle, $replacement)
# .NET file APIs resolve relative paths against the process cwd, which is NOT
# the PowerShell location set above -> always pass the absolute path here.
[System.IO.File]::WriteAllText((Join-Path $OutAbs 'AndroidManifest.xml'), $manifest, $Utf8NoBom)

Write-Host '==> 2/6 aapt2 compile + link'
& "$Bt\aapt2.exe" compile --dir app\src\main\res -o "$Out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }
& "$Bt\aapt2.exe" link -o "$Out\base.apk" -I "$AndroidJar" `
  --manifest "$Out\AndroidManifest.xml" "$Out\res.zip" `
  --min-sdk-version 29 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host '==> 3/6 javac'
$javaFiles = @(Get-ChildItem app\src\main\java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javacOut = Join-Path $OutAbs 'javac.out'
$javacErr = Join-Path $OutAbs 'javac.err'
# javac writes harmless "uses or overrides a deprecated API" notes to stderr.
# PowerShell 5.1 would wrap native stderr as NativeCommandError and, with
# $ErrorActionPreference='Stop', abort the script -> redirect via Start-Process.
$javacArgs = @('-encoding', 'UTF-8', '-nowarn', '-source', '8', '-target', '8',
  '-bootclasspath', $AndroidJar, '-d', (Join-Path $OutAbs 'classes')) + $javaFiles
$p = Start-Process -FilePath "$JdkBin\javac.exe" -ArgumentList $javacArgs -NoNewWindow -Wait -PassThru `
  -RedirectStandardOutput $javacOut -RedirectStandardError $javacErr
if ($p.ExitCode -ne 0) {
  Get-Content $javacErr -Encoding UTF8 -ErrorAction SilentlyContinue
  Get-Content $javacOut -Encoding UTF8 -ErrorAction SilentlyContinue
  throw "javac failed ($($p.ExitCode))"
}

Write-Host '==> 4/6 d8 (class -> dex)'
$classFiles = @(Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$JdkBin\java.exe" -cp "$Bt\lib\d8.jar" com.android.tools.r8.D8 `
  --lib "$AndroidJar" --min-api 29 --output "$Out\dex" @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Write-Host '==> 5/6 add classes.dex (stored) + zipalign'
Copy-Item "$Out\base.apk" "$Out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = (Resolve-Path "$Out\unsigned.apk").Path
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
  $entryStream = $entry.Open()
  try {
    $dex = [System.IO.File]::ReadAllBytes((Resolve-Path "$Out\dex\classes.dex").Path)
    $entryStream.Write($dex, 0, $dex.Length)
  } finally { $entryStream.Close() }
} finally { $zip.Dispose() }
& "$Bt\zipalign.exe" -f -p 4 "$Out\unsigned.apk" "$Out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

Write-Host '==> 6/6 sign'
# Java tools always chatter on stderr (keytool prints the key-generation banner,
# apksigner prints the certificate thumbprint). PowerShell 5.1 turns native stderr
# into NativeCommandError, and with $ErrorActionPreference='Stop' that would abort
# the script (or leave $? false -> exit code 1), so run them via Start-Process.
$Ks = "$Out\audiohub-debug.keystore"
function Invoke-Native([string]$exe, [string[]]$argv, [string]$tag) {
  $o = Join-Path $OutAbs ("$tag.out")
  $e = Join-Path $OutAbs ("$tag.err")
  $p = Start-Process -FilePath $exe -ArgumentList $argv -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $o -RedirectStandardError $e
  return $p
}
if (-not (Test-Path $Ks)) {
  $p = Invoke-Native "$JdkBin\keytool.exe" @('-genkeypair', '-keystore', $Ks, '-alias', 'audiohub',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
    '-storepass', 'android', '-keypass', 'android',
    '-dname', '"CN=AudioHub Debug, OU=dev, O=audiohub, C=CN"') 'keytool'
  if ($p.ExitCode -ne 0) {
    Get-Content (Join-Path $OutAbs 'keytool.err') -Encoding UTF8 -ErrorAction SilentlyContinue
    throw "keytool failed ($($p.ExitCode))"
  }
}
$p = Invoke-Native "$JdkBin\java.exe" @('-jar', "$Bt\lib\apksigner.jar", 'sign', '--ks', $Ks,
  '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
  '--out', "$Out\audiohub-source.apk", "$Out\aligned.apk") 'apksigner-sign'
if ($p.ExitCode -ne 0) {
  Get-Content (Join-Path $OutAbs 'apksigner-sign.err') -Encoding UTF8 -ErrorAction SilentlyContinue
  throw "apksigner sign failed ($($p.ExitCode))"
}

$p = Invoke-Native "$JdkBin\java.exe" @('-jar', "$Bt\lib\apksigner.jar", 'verify', '--print-certs',
  "$Out\audiohub-source.apk") 'apksigner-verify'
if ($p.ExitCode -ne 0) {
  Get-Content (Join-Path $OutAbs 'apksigner-verify.err') -Encoding UTF8 -ErrorAction SilentlyContinue
  throw "apksigner verify failed ($($p.ExitCode))"
}
Get-Content (Join-Path $OutAbs 'apksigner-verify.out') -Encoding UTF8 -ErrorAction SilentlyContinue |
  Where-Object { $_ -match 'Signer #1 certificate DN' } | Select-Object -First 1 | Out-Null

$apk = Get-Item "$Out\audiohub-source.apk"
Write-Host "==> done: $($apk.FullName) ($([math]::Round($apk.Length / 1024)) KB)"