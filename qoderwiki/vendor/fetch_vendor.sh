#!/usr/bin/env bash
# 拉取构建 HTML 需要的 marked + mermaid（不入库，与仓库根 .gitignore 的口径一致）。
# 已交付的 HTML 已把两者内联，阅读不需要这一步；只有重新构建才需要。
set -eu
cd "$(dirname "$0")"
[ -f marked.min.js ]  || curl -fL -o marked.min.js  https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js
[ -f mermaid.min.js ] || curl -fL -o mermaid.min.js https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js
echo "==> vendor ready. now: python3 ../tools/build_site.py"
