#!/bin/bash
# 终版 Mac 截图：隔离数据目录，只截本进程的应用窗口，绝不清理用户数据。
set -euo pipefail
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DERIVED_DATA="${DERIVED_DATA:-$APPLE_ROOT/.build/supplement-mac}"
APP="$DERIVED_DATA/Build/Products/Debug/SnowFold.app/Contents/MacOS/SnowFold"
FIXTURE="${FIXTURE:-$APPLE_ROOT/.build/long-data-v2}"
DEST="/Users/cheng/Documents/github/billfold/.fullstack/layout/shots/apple-full"
CONTAINER_TMP="$HOME/Library/Containers/app.snowfold/Data/tmp"
mkdir -p "$CONTAINER_TMP" "$DEST"
CAPTURE_ROOT="$(mktemp -d "$CONTAINER_TMP/snowfold-apple-capture.XXXXXX")"
trap 'rm -rf "$CAPTURE_ROOT"' EXIT
mkdir -p "$CAPTURE_ROOT/long" "$CAPTURE_ROOT/empty"
cp -R "$FIXTURE/." "$CAPTURE_ROOT/long/"
shoot() {
    local width="$1" height="$2" size="$3" look="$4" tab="$5" kind="${6:-long}"
    local name="complete-mac-$size-$look-$tab"
    if [ "$kind" = empty ]; then name="$name-empty"; fi
    local result=0
    open -W -n -a "${APP%/Contents/MacOS/SnowFold}" --env "SNOWFOLD_DATA_DIR=$CAPTURE_ROOT/$kind" --args \
        -SNOWFOLD_SHOT_WIDTH "$width" -SNOWFOLD_SHOT_HEIGHT "$height" \
        -SNOWFOLD_SHOT_PATH "$CAPTURE_ROOT/$name.png" \
        -SNOWFOLD_SHOT_APPEARANCE "$look" -SNOWFOLD_SHOT_TAB "$tab" \
        > "$APPLE_ROOT/.build/$name.log" 2>&1 || result=$?
    if [ "$result" -ne 0 ]; then
        cat "$CAPTURE_ROOT/$name.png.failure" >&2
        return "$result"
    fi
    if [ ! -f "$CAPTURE_ROOT/$name.png" ]; then
        cat "$CAPTURE_ROOT/$name.png.failure" >&2
        return 1
    fi
    cp "$CAPTURE_ROOT/$name.png" "$DEST/"
    echo "captured $name"
}
TABS=("$@")
if [ "${#TABS[@]}" -eq 0 ]; then TABS=(overview assets subscriptions settings); fi
for look in light dark; do
    for tab in "${TABS[@]}"; do
        shoot 1080 720 regular "$look" "$tab"
        shoot 760 500 min "$look" "$tab"
    done
    shoot 760 500 min "$look" overview empty
done
