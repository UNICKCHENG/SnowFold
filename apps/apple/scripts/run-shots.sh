#!/bin/bash
# 用法：run-shots.sh <snowfold-apple UDID> <prefix> <empty|standard|long|neg|bad|key|sync|interaction> <light|dark> [ax]
# 每次保留原始日志，失败也归集已拍截图；仅操作本路自建的 snowfold-apple 模拟器。
set -euo pipefail
UDID="$1"; PREFIX="$2"; KIND="$3"; LOOK="$4"; AX="${5:-}"
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DERIVED_DATA="${DERIVED_DATA:-$APPLE_ROOT/.build/supplement}"
FIXTURE="${FIXTURE:-$APPLE_ROOT/.build/long-data-v2}"
# 输出目录可由 SNOWFOLD_SHOTS_DEST 覆盖；默认仍落仓库 .build 下，便于本机试跑。
DEST="${SNOWFOLD_SHOTS_DEST:-$APPLE_ROOT/.build/shots-out}"
xcrun simctl list devices -j | python3 -I -c 'import json,sys; uid=sys.argv[1]; devices=[d for rows in json.load(sys.stdin)["devices"].values() for d in rows]; assert any(d["udid"]==uid and "snowfold-apple" in d["name"] for d in devices), "refusing unowned simulator"' "$UDID"
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryLevel 100 --batteryState charged --wifiBars 3
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
if [[ "$KIND" = long || "$KIND" = standard || "$KIND" = neg || "$KIND" = bad ]]; then
    case "$KIND" in
        standard) FIXTURE="$APPLE_ROOT/.build/standard-data" ;;
        neg) FIXTURE="$APPLE_ROOT/.build/neg-data-v1" ;;
        bad) FIXTURE="$APPLE_ROOT/.build/bad-data" ;;
    esac
    cp -R "$FIXTURE/." "$container/Library/Application Support/SnowFold/"
fi
case "$KIND" in
    empty) TOUR=testEmptyTour ;;
    long|standard) TOUR=testLongTour ;;
    neg|bad|key|sync) TOUR=testSupplementalStates ;;
    interaction) TOUR=testCategorySelectionAndForegroundRefresh ;;
    *) echo 'unknown screenshot fixture' >&2; exit 2 ;;
esac
MODE="$PREFIX-$KIND-$LOOK"
if [ "$AX" = ax ]; then MODE="$MODE-ax"; fi
mkdir -p "$DEST"
RUN_ID="$(uuidgen)"
EXTRA_TESTS=()
if [[ "$KIND" = empty || "$KIND" = long || "$KIND" = standard ]]; then
    EXTRA_TESTS=(-only-testing:"SnowFoldSnapshots-iOS/SnapshotTours/test00ScreenMatrix" -only-testing:"SnowFoldSnapshots-iOS/SnapshotTours/testSupplementalStates")
fi
set +e
RUN_FILES=("$DERIVED_DATA"/Build/Products/SnowFold-iOS-snapshots_*.xctestrun)
mkdir -p "$APPLE_ROOT/.build/shot-results"
TEST_RUNNER_SHOT_MODE="$MODE" TEST_RUNNER_SHOT_KIND="$KIND" TEST_RUNNER_SHOT_RUN_ID="$RUN_ID" bash "$APPLE_ROOT/scripts/with-xcode-lock.sh" xcodebuild test-without-building \
    -xctestrun "${RUN_FILES[0]}" -destination "id=$UDID" \
    -resultBundlePath "$APPLE_ROOT/.build/shot-results/$MODE-$(date +%H%M%S).xcresult" \
    -parallel-testing-enabled NO -only-testing:"SnowFoldSnapshots-iOS/SnapshotTours/$TOUR" \
    "${EXTRA_TESTS[@]+"${EXTRA_TESTS[@]}"}" \
    > "$APPLE_ROOT/.build/shots-$MODE.log" 2>&1
result=$?
set -e
copied=$(find "$HOME/Library/Developer/CoreSimulator/Devices/$UDID/data/Containers/Data/Application" \
    -path "*/snowfold-shots/$RUN_ID/$MODE-*.png" -exec cp {} "$DEST/" \; -print | wc -l)
if [ "$copied" -eq 0 ]; then
    echo "本轮没有采集到截图：$MODE ($RUN_ID)" >&2
    result=1
fi
# iOS 27 的窗口截图可能仍按竖向像素编码，横屏归集时只校正方向。
if [ "$PREFIX" = ipad ]; then
    for shot in "$DEST/$MODE-"*.png; do
        [ -f "$shot" ] || continue
        read -r pixel_width pixel_height <<< "$(sips -g pixelWidth -g pixelHeight "$shot" | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END {print w,h}')"
        if [ "$pixel_height" -gt "$pixel_width" ]; then sips -r -90 "$shot" >/dev/null; fi
    done
fi
# runner 上没有 ripgrep；测试原文用 grep 归纳（完整日志另行上传）。
grep -E 'Test Case.*(passed|failed)|error:|TEST EXECUTE|Executed ' "$APPLE_ROOT/.build/shots-$MODE.log" | tail -12 || true
exit "$result"
