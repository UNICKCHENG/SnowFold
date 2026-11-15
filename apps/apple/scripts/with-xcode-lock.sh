#!/bin/bash
# 所有苹果构建与测试共享同一把目录锁，便于同 VM 的独立工作区串行使用 Xcode。
set -euo pipefail
LOCK="${SNOWFOLD_XCODE_LOCK:-$HOME/work/pr4-xcode.lock}"
mkdir -p "$(dirname "$LOCK")"
until mkdir "$LOCK" 2>/dev/null; do sleep 2; done
trap 'rmdir "$LOCK"' EXIT
"$@"
