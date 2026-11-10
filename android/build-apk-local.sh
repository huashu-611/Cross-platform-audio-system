#!/usr/bin/env bash
# 用本机已有的 Android SDK（C:\tmp_audiohub\android\sdk）+ JDK 17 打 APK。
#
# PATH 必须用 **unix 风格**的路径（/c/...），用 C:/... 形式 MSYS 不会解析成可执行目录。
# JAVA_HOME 仍用 Windows 风格，因为 build-apk.sh 里 java 命令走 PATH。
set -euo pipefail

SDK_WIN="C:/tmp_audiohub/android/sdk"
SDK_UNIX="/c/tmp_audiohub/android/sdk"
JDK_UNIX="$SDK_UNIX/jdk-17.0.20.1+1"

export JAVA_HOME="$JDK_UNIX"
export PATH="$JDK_UNIX/bin:$PATH"
export ANDROID_SDK_ROOT="$SDK_WIN"

echo "java : $(java -version 2>&1 | head -1)"
echo "javac: $(javac -version 2>&1)"

cd "$(dirname "$0")"

export VERSION_CODE="${VERSION_CODE:-37}"
export VERSION_NAME="${VERSION_NAME:-0.37}"

bash ./build-apk.sh
