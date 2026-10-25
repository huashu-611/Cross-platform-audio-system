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

CORE="src/packet.cpp src/jitter_buffer.cpp src/clock_sync.cpp src/asrc.cpp src/mixer.cpp src/udp_socket.cpp"
FLAGS="-std=c++17 -O2 -Wall -Wextra -Iinclude"

echo "==> 单元测试"
$CXX $FLAGS \
  $CORE \
  tests/test_main.cpp tests/test_packet.cpp tests/test_ring.cpp tests/test_jitter.cpp \
  tests/test_clock.cpp tests/test_asrc.cpp tests/test_mixer.cpp \
  -lws2_32 -o "$OUT/audiohub_tests.exe"

if [ "${WIN32:-1}" = "1" ]; then
  echo "==> Windows 应用（WASAPI）"
  for app in hub source; do
    $CXX $FLAGS -Iplatform/win \
      apps/${app}_main.cpp platform/win/wasapi_audio.cpp \
      $CORE -lole32 -lws2_32 -o "$OUT/audiohub-${app}.exe"
  done
fi

echo "==> 运行测试"
"./$OUT/audiohub_tests.exe"
echo "==> 产物在 $OUT/"
