#!/usr/bin/env bash
# 构建未签名 APK（不依赖 Gradle）
# 用法：bash tools/build.sh
# 依赖：JDK 8+、Android SDK（platform android-34、build-tools 34）
# SDK 路径通过环境变量 ANDROID_HOME 指定，默认 $HOME/android-sdk
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build/out"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
PLATFORM="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"

if [ -z "$BT" ] || [ -z "$PLATFORM" ]; then
  echo "找不到 Android SDK，请先安装 platform 与 build-tools，并设置 ANDROID_HOME"
  echo "  SDK 路径：$SDK"
  exit 1
fi

echo "== 0. 环境 =="
echo "SDK    : $SDK"
echo "tools  : $BT"
echo "platform: $PLATFORM"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile =="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "== 3. javac =="
find "$APP/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
JAVAC_MAJOR="$(javac -version 2>&1 | sed 's/javac //' | cut -d. -f1)"
if [ "$JAVAC_MAJOR" -ge 9 ] 2>/dev/null; then
  # JDK 9+ 已移除 -bootclasspath，用 -classpath 指向 android.jar
  javac -source 8 -target 8 -classpath "$PLATFORM" -d "$OUT/classes" \
    -encoding UTF-8 -nowarn @"$OUT/sources.txt"
else
  javac -source 8 -target 8 -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
    -d "$OUT/classes" -encoding UTF-8 -nowarn @"$OUT/sources.txt"
fi

echo "== 4. d8 =="
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5. 打包 dex =="
mkdir -p "$OUT/dex"
(cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex)

echo "== 6. zipalign =="
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== BUILD OK: $OUT/aligned.apk =="
