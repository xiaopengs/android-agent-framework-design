#!/usr/bin/env bash
# 编译 nm-headless runtime 为自包含 jar（内嵌 kotlin-stdlib，运行只需 json.jar）。
# 优先用 kotlinc；没有时退回 Gradle 自带的 kotlin-compiler-embeddable。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"
mkdir -p out

if command -v kotlinc >/dev/null 2>&1; then
  echo "[build] using kotlinc ($(kotlinc -version 2>&1 | head -1))"
  kotlinc src/main/kotlin -cp libs/json.jar -include-runtime -d out/nm-headless.jar
  echo "[build] done: out/nm-headless.jar"
  exit 0
fi

GLIB=""
for d in /root/.sdkman/candidates/gradle/*/lib "$HOME/.sdkman/candidates/gradle/*/lib" /opt/gradle*/lib; do
  if compgen -G "$d/kotlin-compiler-embeddable-*.jar" >/dev/null 2>&1; then GLIB="$d"; break; fi
done
if [ -z "$GLIB" ]; then
  echo "[build] 未找到 Kotlin 编译器：请安装 kotlinc，或设置 KOTLIN_LIB 指向含 kotlin-compiler-embeddable 的目录" >&2
  exit 2
fi
STDLIB="$(ls "$GLIB"/kotlin-stdlib-*.jar | head -1)"
echo "[build] using Kotlin embeddable from $GLIB"
java -cp "$GLIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 11 \
  -cp "$STDLIB:$HERE/libs/json.jar" \
  -d out/nm-headless.jar src/main/kotlin

# 合并 kotlin-stdlib 到产物，保持 `java -cp out/nm-headless.jar:libs/json.jar` 可直接运行
python3 - "$HERE/out/nm-headless.jar" "$STDLIB" <<'PY'
import sys, zipfile, shutil, os
jar, stdlib = sys.argv[1], sys.argv[2]
tmp = jar + ".merged"
shutil.copy(jar, tmp)
with zipfile.ZipFile(jar) as z:
    existing = set(z.namelist())
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as zout, zipfile.ZipFile(stdlib) as zin:
    for item in zin.infolist():
        n = item.filename
        if n in existing or n == "META-INF/MANIFEST.MF" or n.endswith((".SF", ".RSA", ".DSA")):
            continue
        zout.writestr(n, zin.read(n))
os.replace(tmp, jar)
print(f"[build] merged {stdlib} into {jar}")
PY

echo "[build] done: out/nm-headless.jar"
