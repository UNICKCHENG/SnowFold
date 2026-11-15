#!/bin/bash
# 经核心生成独立验收夹具；复用已存在的夹具，避免重复插入。
set -euo pipefail
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$APPLE_ROOT/../.." && pwd)"
TARGET_ROOT="${CARGO_TARGET_DIR:-$REPO_ROOT/target}"
mkdir -p "$APPLE_ROOT/.build/seed-link" "$APPLE_ROOT/.build/fixture-mac"
cp "$TARGET_ROOT/aarch64-apple-darwin/release/libsnowfold_core.a" "$APPLE_ROOT/.build/seed-link/"
FFI_HEADERS="$(dirname "$(find "$APPLE_ROOT/Generated/SnowFoldCore.xcframework" -path '*macos*' -name module.modulemap | head -1)")"
swiftc -O -I "$FFI_HEADERS" -L "$APPLE_ROOT/.build/seed-link" -lsnowfold_core -lc++ -liconv \
    -o "$APPLE_ROOT/.build/sample-seed" "$APPLE_ROOT/scripts/seed/main.swift" \
    "$APPLE_ROOT/Generated/Swift/SnowFoldCore.swift" "$APPLE_ROOT/Sources/Core/KeychainKeyStore.swift"
for pair in standard:standard-data long:long-data-v2 neg:neg-data-v1; do
    kind="${pair%:*}"; dir="$APPLE_ROOT/.build/${pair#*:}"
    args=(); [ "$kind" = standard ] && args=(--standard); [ "$kind" = neg ] && args=(--neg)
    if [ ! -f "$dir/snowfold.sqlite3" ]; then "$APPLE_ROOT/.build/sample-seed" "$dir" "${args[@]+"${args[@]}"}"; fi
    ln -sfn "$dir" "$APPLE_ROOT/.build/fixture-mac/$kind"
done
mkdir -p "$APPLE_ROOT/.build/bad-data"
printf '%s\n' 'not a database' > "$APPLE_ROOT/.build/bad-data/snowfold.sqlite3"
ln -sfn "$APPLE_ROOT/.build/bad-data" "$APPLE_ROOT/.build/fixture-mac/bad"
