#!/usr/bin/env bash
# 不依赖 Gradle 的 APK 打包脚本。
#
# 为什么不用 Gradle：Gradle 要先下 AGP + 一堆依赖，而本项目的 app 模块
# 零第三方依赖（纯平台 API）。aapt2 + javac + d8 + apksigner 四步就够，
# 加起来不到 5 秒，也不需要联网。
#
# 需要环境变量（或改下面的默认值）：
#   ANDROID_SDK_ROOT   SDK 根目录，需包含 platforms/android-35/android.jar
#                     和 build-tools（含 aapt2/d8/zipalign/apksigner）
#   JAVA_HOME          JDK 17
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "请设置 ANDROID_SDK_ROOT 指向 Android SDK 根目录" >&2
  exit 1
fi

# SDK 包解压后目录名不一定规整，这里自动找一下
find_dir() { find "$SDK" -maxdepth 2 -type d -name "$1" | head -1; }
ANDROID_JAR="$(find "$SDK" -maxdepth 3 -name android.jar | head -1)"
BUILD_TOOLS="$(dirname "$(find "$SDK" -maxdepth 3 -name aapt2.exe | head -1)")"

[ -n "$ANDROID_JAR" ] || { echo "找不到 android.jar" >&2; exit 1; }
[ -n "$BUILD_TOOLS" ] || { echo "找不到 build-tools（aapt2）" >&2; exit 1; }

PKG="com.audiohub.source"
# 版本号可由环境变量覆盖，便于在手机上区分“装的是哪一版”（默认保持 0.1/1）
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-0.1}"
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> 1/6 生成临时 manifest（aapt2 命令行要求 package 属性，AGP 8 又不允许，只能构建时注入）"
python - "$PKG" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import io, sys
pkg = sys.argv[1]
code = sys.argv[2]
name = sys.argv[3]
s = io.open('app/src/main/AndroidManifest.xml', encoding='utf-8').read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
              '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    package="%s"\n    android:versionCode="%s"\n    android:versionName="%s">' % (pkg, code, name))
io.open('build/AndroidManifest.xml', 'w', encoding='utf-8').write(s)
PY

echo "==> 2/6 aapt2 compile + link（现在有 res/mipmap 了，必须先 compile）"
"$BUILD_TOOLS/aapt2.exe" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2.exe" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" "$OUT/res.zip" \
  --min-sdk-version 29 --target-sdk-version 34

echo "==> 3/6 javac"
# 源码是 UTF-8，而 Windows 默认 ANSI 是 GBK；不指定编码会把中文注释/字符串读成乱码
javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src/main/java -name '*.java')

echo "==> 4/6 d8（把 .class 转成 dex）"
# 直接调 jar 而不是 d8.bat：本机沙箱不允许 Bash 执行 .bat
java -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 29 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "==> 5/6 把 classes.dex 塞进 APK 并对齐"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# dex 用 ZIP_STORED（不压缩）：这是标准做法，zipalign 才能保证它对齐
python - "$OUT" <<'PYEOF'
import sys, os, zipfile
out = sys.argv[1]
target = os.path.join(out, 'unsigned.apk')
with zipfile.ZipFile(target, 'a') as z:
    z.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex', zipfile.ZIP_STORED)
PYEOF
"$BUILD_TOOLS/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
KS="$OUT/audiohub-debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias audiohub -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=AudioHub Debug, OU=dev, O=audiohub, C=CN" >/dev/null 2>&1
fi
java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/audiohub-source.apk" "$OUT/aligned.apk"

echo
echo "产物: android/$OUT/audiohub-source.apk"
ls -la "$OUT/audiohub-source.apk"
