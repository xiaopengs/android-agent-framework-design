#!/usr/bin/env bash
# 一键重跑全部 JVM 验证台。无需 Android SDK。
set -u
cd "$(dirname "$0")"
KC=lib/kotlinc/bin/kotlinc
LIBS="lib/junit.jar:lib/hamcrest.jar:lib/json.jar:lib/kotlinx-coroutines-core-jvm.jar:lib/kotlinx-coroutines-test-jvm.jar:lib/kotlinx-serialization-core-jvm.jar:lib/mockito-core.jar:lib/byte-buddy.jar:lib/byte-buddy-agent.jar:lib/objenesis.jar"
STDLIB=lib/kotlinc/lib/kotlin-stdlib.jar

run_suite() {
  local src=$1 out=$2; shift 2
  echo "=============================================================="
  echo "SUITE $src"
  echo "=============================================================="
  "$KC" "$src"/*.kt -cp "$LIBS" -d "$out" 2>&1 | grep -v '^warning:' || true
  local CP="$out:$LIBS:$STDLIB:lib/kotlinc/lib/kotlin-reflect.jar"
  for t in "$@"; do
    echo "--- $t"
    java -cp "$CP" org.junit.runner.JUnitCore "$t" 2>&1 | tail -6
  done
}

tests_in() {
  for f in "$1"/*Test.kt; do
    pkg=$(grep -m1 '^package ' "$f" | sed 's/package //')
    cls=$(basename "$f" .kt)
    echo "$pkg.$cls"
  done
}

run_suite src out $(tests_in src)

echo "--- ProbeKt (行为探针)"
java -cp "out:$LIBS:$STDLIB" ProbeKt

run_suite src_loop out_loop $(tests_in src_loop)

run_suite src_ctx out_ctx $(tests_in src_ctx)
