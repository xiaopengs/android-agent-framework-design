#!/usr/bin/env bash
# 拉取验证台需要的两个大件：Kotlin 编译器 2.0.21 与 byte-buddy（mockito 依赖）。
# 其余 jar 已随包提供。需要网络。
set -eu
cd "$(dirname "$0")/lib"

if [ ! -x kotlinc/bin/kotlinc ]; then
  echo "==> Kotlin 2.0.21 compiler"
  curl -fL -o kotlin-compiler.zip \
    https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
  unzip -q kotlin-compiler.zip
fi

if [ ! -f byte-buddy.jar ]; then
  echo "==> byte-buddy 1.14.x (mockito runtime)"
  curl -fL -o byte-buddy.jar \
    https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/1.14.19/byte-buddy-1.14.19.jar
fi

echo "==> done. now run ./run_all.sh"
