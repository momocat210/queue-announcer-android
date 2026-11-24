#!/usr/bin/env bash
# 一键发布：构建 → 签名 → 输出 output/叫号系统-v{版本}.apk
# 用法：KEYSTORE=/path/my.keystore KS_PASS=xxx KEY_PASS=xxx bash tools/release.sh
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build/out"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"

KS="${KEYSTORE:-$ROOT/keystore/my.keystore}"
KS_PASS="$KS_PASS"
KEY_PASS="$KEY_PASS"

if [ ! -f "$KS" ]; then
  echo "未找到签名密钥：$KS"
  echo "请用 KEYSTORE=/path/my.keystore KS_PASS=xxx KEY_PASS=xxx bash tools/release.sh 指定"
  exit 1
fi
if [ -z "$KS_PASS" ] || [ -z "$KEY_PASS" ]; then
  echo "请设置 KS_PASS 与 KEY_PASS 环境变量（自己的密钥口令）"
  exit 1
fi

VCODE=$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | grep -o '[0-9]*')
VNAME=$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | sed 's/.*android:versionName="//;s/"//')
mkdir -p "$ROOT/output"
TARGET="$ROOT/output/叫号系统-v${VNAME}.apk"

echo "版本：v${VNAME} (versionCode ${VCODE})"
bash "$ROOT/tools/build.sh" > /tmp/qcaller-build.log 2>&1 || { echo "构建失败："; tail -20 /tmp/qcaller-build.log; exit 1; }

"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$TARGET" "$OUT/aligned.apk"
rm -f "$TARGET.idsig"
"$BT/apksigner" verify "$TARGET" > /dev/null
echo "签名校验通过"

# 只保留最近两个版本
ls -t "$ROOT"/output/叫号系统-v*.apk 2>/dev/null | tail -n +3 | while read -r f; do
    rm -f "$f"; echo "清理旧版本：$(basename "$f")"
done

ls -lh "$TARGET"
echo "输出：$TARGET"
