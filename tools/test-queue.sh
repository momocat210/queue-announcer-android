#!/usr/bin/env bash
# 队列逻辑单元测试（纯 JVM，不需要安卓设备）
# 用桩类替代 android.content.* 与 org.json.*，直接跑真实的 QueueStore 代码
# 用法：bash tools/test-queue.sh
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/test"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -d "$OUT" \
  -sourcepath "$ROOT/test/stub:$ROOT/app/src" \
  "$ROOT/test/src/QTest.java" \
  "$ROOT/app/src/com/qcaller/announcer/QueueStore.java"

java -cp "$OUT" QTest
