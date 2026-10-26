#!/usr/bin/env bash
# 不依赖 CMake 的构建脚本（本机没装 cmake 时用这个）。
# 需要 MSYS2 UCRT64 工具链：pacman -S mingw-w64-ucrt-x86_64-gcc
#
# 有 CMake 的话优先用 `cmake -S . -B build && cmake --build build`，
# 这个脚本只是等价的手工版。

set -euo pipefail
cd "$(dirname "$0")"

CXX="${CXX:-g++}"
OUT=build
mkdir -p "$OUT"

CORE="src/packet.cpp src/jitter_buffer.cpp src/clock_sync.cpp src/asrc.cpp src/mixer.cpp src/udp_socket.cpp src/hub_core.cpp src/http_api.cpp src/discovery.cpp"
# 静态链接 libstdc++/libgcc：否则 exe 依赖 MSYS2 的运行时 DLL，
# 用户双击运行会报"找不到 libstdc++-6.dll"。多几百 KB，换免安装。
STATIC="-static -static-libgcc -static-libstdc++"
FLAGS="-std=c++17 -O2 -Wall -Wextra -Iinclude $STATIC"

echo "==> 单元测试"
# 测试只编纯核心（不含 hub_core.cpp：它依赖 WASAPI 实现，
# 而测试用不到 HubCore，也不该为此把平台依赖拖进来）
$CXX $FLAGS \
  src/packet.cpp src/jitter_buffer.cpp src/clock_sync.cpp src/asrc.cpp src/mixer.cpp src/udp_socket.cpp \
  tests/test_main.cpp tests/test_packet.cpp tests/test_ring.cpp tests/test_jitter.cpp \
  tests/test_clock.cpp tests/test_asrc.cpp tests/test_mixer.cpp tests/test_pipeline.cpp \
  -lws2_32 -o "$OUT/audiohub_tests.exe"

if [ "${WIN32:-1}" = "1" ]; then
  echo "==> Windows 应用（WASAPI）"
  for app in hub source; do
    # -liphlpapi：发现模块要用 GetAdaptersAddresses 枚举各网卡的广播地址
    $CXX $FLAGS -Iplatform/win \
      apps/${app}_main.cpp platform/win/wasapi_audio.cpp platform/win/media_file.cpp \
      $CORE -lole32 -lws2_32 -liphlpapi -lmfplat -lmfreadwrite -lmfuuid \
      -o "$OUT/audiohub-${app}.exe"
  done

fi

echo "==> 运行测试"
"./$OUT/audiohub_tests.exe"
# Electron 壳需要 C++ 核心的可执行文件；asar 里不能 spawn 二进制，
# 必须放到 bin/ 并在打包时 asarUnpack
mkdir -p ui/bin
cp "$OUT/audiohub-hub.exe" ui/bin/audiohub-hub.exe
echo "==> 已同步 ui/bin/audiohub-hub.exe（Electron 壳用）"

echo "==> 产物在 $OUT/"
