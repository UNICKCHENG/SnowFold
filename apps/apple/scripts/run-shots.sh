#!/bin/bash
# 用法：run-shots.sh <snowfold-apple UDID> <prefix> <empty|long|interaction> <light|dark> [ax]
# 每次保留原始日志，失败也归集已拍截图；仅操作本路自建模拟器。
set -euo pipefail
UDID="$1"; PREFIX="$2"; KIND="$3"; LOOK="$4"; AX="${5:-}"
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DERIVED_DATA="${DERIVED_DATA:-$APPLE_ROOT/.build/supplement}"
FIXTURE="${FIXTURE:-$APPLE_ROOT/.build/long-data-v2}"
DEST="/Users/cheng/Documents/github/billfold/.fullstack/layout/shots/apple-full"
xcrun simctl list devices -j | python3 -I -c 'import json,sys; uid=sys.argv[1]; devices=[d for rows in json.load(sys.stdin)["devices"].values() for d in rows]; assert any(d["udid"]==uid and "snowfold-apple" in d["name"] for d in devices), "refusing unowned simulator"' "$UDID"
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance "$LOOK"
if [ "$AX" = ax ]; then
    xcrun simctl ui "$UDID" content_size accessibility-extra-extra-extra-large
else
    xcrun simctl ui "$UDID" content_size large
fi
xcrun simctl uninstall "$UDID" app.snowfold 2>/dev/null || true
xcrun simctl install "$UDID" "$DERIVED_DATA/Build/Products/Debug-iphonesimulator/SnowFold.app"
container="$(xcrun simctl get_app_container "$UDID" app.snowfold data)"
mkdir -p "$container/Library/Application Support/SnowFold"
if [ "$KIND" = long ]; then
    cp -R "$FIXTURE/." "$container/Library/Application Support/SnowFold/"
fi
case "$KIND" in
    empty) TOUR=testEmptyTour ;;
    long) TOUR=testLongTour ;;
    interaction) TOUR=testCategorySelectionAndForegroundRefresh ;;
    *) echo 'kind must be empty, long or interaction' >&2; exit 2 ;;
esac
MODE="$PREFIX-$LOOK"
if [ "$AX" = ax ]; then MODE="$MODE-ax"; fi
mkdir -p "$DEST"
set +e
RUN_FILES=("$DERIVED_DATA"/Build/Products/SnowFold-iOS-snapshots_*.xctestrun)
mkdir -p "$APPLE_ROOT/.build/shot-results"
TEST_RUNNER_SHOT_MODE="$MODE" xcodebuild test-without-building \
    -xctestrun "${RUN_FILES[0]}" -destination "id=$UDID" \
    -resultBundlePath "$APPLE_ROOT/.build/shot-results/$MODE-$(date +%H%M%S).xcresult" \
    -parallel-testing-enabled NO -only-testing:"SnowFoldSnapshots-iOS/SnapshotTours/$TOUR" \
    > "$APPLE_ROOT/.build/shots-$MODE.log" 2>&1
result=$?
set -e
find "$HOME/Library/Developer/CoreSimulator/Devices/$UDID/data/Containers/Data/Application" \
    -name "$MODE-*.png" -exec cp {} "$DEST/" \;
rg 'Test Case.*(passed|failed)|error:|TEST EXECUTE|Executed ' "$APPLE_ROOT/.build/shots-$MODE.log" | tail -10
exit "$result"
