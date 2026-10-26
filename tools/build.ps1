# Windows-native build script (equivalent to build.sh).
# Why: this machine has no bash/g++ on PATH, but MSYS2 UCRT64 g++ lives at
# C:\msys64\ucrt64\bin. build.sh needs bash; this one runs from PowerShell.
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI
# (GBK here) unless there is a UTF-8 BOM, so non-ASCII text breaks parsing.
#
# Usage:
#   powershell -NoProfile -File tools/build.ps1            # tests + apps + run tests
#   powershell -NoProfile -File tools/build.ps1 -Target tests
#   powershell -NoProfile -File tools/build.ps1 -Target apps

param([ValidateSet('all', 'tests', 'apps')][string]$Target = 'all')

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$GXX = 'C:\msys64\ucrt64\bin\g++.exe'
if (-not (Test-Path $GXX)) { throw "g++ not found: $GXX (MSYS2 UCRT64 toolchain required)" }
# cc1plus.exe lives in lib/gcc/... and needs the runtime DLLs from bin/; without
# this the driver exits 1 with no message at all (Windows loader failure).
$env:PATH = 'C:\msys64\ucrt64\bin;' + $env:PATH

$Out = Join-Path $Root 'build'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$CXXFLAGS = @(
  '-std=c++17', '-O2', '-Wall', '-Wextra', '-Iinclude',
  '-static', '-static-libgcc', '-static-libstdc++'
)

# Tests only need the platform-independent core (hub_core.cpp pulls in WASAPI).
# IMPORTANT: use paths relative to the repo root (build dir). MinGW ld cannot
# handle the non-ASCII ("Chinese") characters in the absolute repo path; passing
# absolute paths makes it fail with "cannot open output file ... No such file".
$TestCore = @(Get-ChildItem src\*.cpp |
  Where-Object { $_.Name -ne 'hub_core.cpp' } |
  ForEach-Object { "src\$($_.Name)" })
$TestSrc = @(Get-ChildItem tests\*.cpp | ForEach-Object { "tests\$($_.Name)" })

function Build-Tests {
  Write-Host '==> build unit tests'
  & $GXX @CXXFLAGS @TestCore @TestSrc -lws2_32 -liphlpapi -o 'build\audiohub_tests.exe'
  if ($LASTEXITCODE -ne 0) { throw "unit test build failed (exit $LASTEXITCODE)" }
}

function Build-Apps {
  Write-Host '==> build windows apps (WASAPI)'
  $core = @(Get-ChildItem src\*.cpp | ForEach-Object { "src\$($_.Name)" })
  $plat = @(Get-ChildItem platform\win\*.cpp | ForEach-Object { "platform\win\$($_.Name)" })
  foreach ($app in @('hub', 'source')) {
    & $GXX @CXXFLAGS -Iplatform/win @plat $core "apps\${app}_main.cpp" `
      -lole32 -lws2_32 -liphlpapi -lmfplat -lmfreadwrite -lmfuuid `
      -o "build\audiohub-$app.exe"
    if ($LASTEXITCODE -ne 0) { throw "audiohub-$app build failed (exit $LASTEXITCODE)" }
  }
  $Bin = Join-Path $Root 'ui\bin'
  New-Item -ItemType Directory -Force -Path $Bin | Out-Null
  Copy-Item 'build\audiohub-hub.exe' (Join-Path $Bin 'audiohub-hub.exe') -Force
  Copy-Item 'build\audiohub-source.exe' (Join-Path $Bin 'audiohub-source.exe') -Force
  Write-Host '==> ui/bin/audiohub-hub.exe + audiohub-source.exe updated'
}

if ($Target -in @('all', 'tests')) {
  Build-Tests
  Write-Host '==> run unit tests'
  & '.\build\audiohub_tests.exe'
  if ($LASTEXITCODE -ne 0) { throw "unit tests failed (exit $LASTEXITCODE)" }
}

if ($Target -in @('all', 'apps')) { Build-Apps }

Write-Host "==> done, output in $Out"