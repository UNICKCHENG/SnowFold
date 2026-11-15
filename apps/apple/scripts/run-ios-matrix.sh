#!/bin/bash
# 用法：run-ios-matrix.sh <snowfold-apple iPhone UDID> <小屏 UDID> <iPad UDID>
# 单屏/单巡游失败不阻断其他矩阵，全部执行后汇总退出码。
set -uo pipefail
IPHONE="$1"; SMALL="$2"; IPAD="$3"
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${SNOWFOLD_SHOTS_DEST:-$APPLE_ROOT/.build/shots-out}"
mkdir -p "$DEST"
STATUS="$DEST/ios-status.tsv"
: > "$STATUS"
failed=0
run() {
    local uid="$1" prefix="$2" kind="$3" look="$4" ax="${5:-}" rc
    bash "$APPLE_ROOT/scripts/run-shots.sh" "$uid" "$prefix" "$kind" "$look" "$ax"
    rc=$?
    printf '%s\t%s\t%s\t%s\t%s\n' "$prefix" "$kind" "$look" "$ax" "$rc" >> "$STATUS"
    [ "$rc" -eq 0 ] || failed=1
    xcrun simctl shutdown "$uid" 2>/dev/null || true
}
for look in light dark; do
    for kind in empty standard long neg bad key sync; do run "$IPHONE" iphone "$kind" "$look"; done
    run "$SMALL" se long "$look"
    for kind in empty standard long neg bad; do run "$IPAD" ipad "$kind" "$look"; done
done
run "$IPHONE" iphone empty light ax
run "$IPHONE" iphone interaction light
exit "$failed"
