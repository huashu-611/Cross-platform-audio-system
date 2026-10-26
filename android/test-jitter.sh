#!/usr/bin/env bash
# 抖动缓冲的离线回归测试。
#
# 只需要 JDK（不需要 Android SDK / 真机）：JitterBuffer 是纯 Java，
# 直接编译 app/src/main 里那一个文件和 test 里的用例。
#
# 这个测试存在的理由：曾经因为槽位索引写成 (abs - readPos) % 容量，
# 导致"每一帧都欠载" —— 手机上表现为完全没有声音，只有偶尔的电流杂音，
# 而且在 PC 端测试完全正常（C++ 用的是绝对索引）。离线用例能一次抓住它。
set -e
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/c/tmp_audiohub/android/sdk/jdk-17.0.20.1+1}"
OUT="build/jitter-test"
rm -rf "$OUT" && mkdir -p "$OUT"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/audiohub/source/JitterBuffer.java \
  app/src/test/java/com/audiohub/source/JitterBufferTest.java

"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT" com.audiohub.source.JitterBufferTest
