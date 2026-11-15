#!/bin/bash
# 用法：run-mac-shots.sh <SnowFold.app 路径> <截图输出目录> <fixture 根目录>
# fixture 根目录需含 long/ neg/ bad/（bad = 坏库，触发启动失败页）。
# 隔离数据目录，只截本进程的应用窗口，绝不清理用户数据。
set -euo pipefail
APP_PATH="$1"
DEST="$2"
FIXTURE_ROOT="$3"
APP="$APP_PATH/Contents/MacOS/SnowFold"
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

CONTAINER_TMP="$HOME/Library/Containers/app.snowfold/Data/tmp"
mkdir -p "$CONTAINER_TMP" "$DEST"
CAPTURE_ROOT="$(mktemp -d "$CONTAINER_TMP/snowfold-apple-capture.XXXXXX")"
trap 'rm -rf "$CAPTURE_ROOT"' EXIT
mkdir -p "$CAPTURE_ROOT/long" "$CAPTURE_ROOT/empty" "$CAPTURE_ROOT/neg" "$CAPTURE_ROOT/bad"
if [ -d "$FIXTURE_ROOT/long" ]; then cp -R "$FIXTURE_ROOT/long/." "$CAPTURE_ROOT/long/"; fi
if [ -d "$FIXTURE_ROOT/neg" ]; then cp -R "$FIXTURE_ROOT/neg/." "$CAPTURE_ROOT/neg/"; fi
if [ -d "$FIXTURE_ROOT/bad" ]; then cp -R "$FIXTURE_ROOT/bad/." "$CAPTURE_ROOT/bad/"; fi

shoot() {
    local width="$1" height="$2" look="$3" name="$4" fixture="${5:-long}"; shift 5
    local result=0
    open -W -n -a "$APP_PATH" --env "SNOWFOLD_DATA_DIR=$CAPTURE_ROOT/$fixture" --args \
        -SNOWFOLD_SHOT_WIDTH "$width" -SNOWFOLD_SHOT_HEIGHT "$height" \
        -SNOWFOLD_SHOT_PATH "$CAPTURE_ROOT/$name.png" \
        -SNOWFOLD_SHOT_APPEARANCE "$look" "$@" \
        > "$APPLE_ROOT/.build/$name.log" 2>&1 || result=$?
    if [ "$result" -ne 0 ] || [ ! -f "$CAPTURE_ROOT/$name.png" ]; then
        cat "$CAPTURE_ROOT/$name.png.failure" >&2 2>/dev/null || true
        echo "FAILED $name" >&2
        return 1
    fi
    cp "$CAPTURE_ROOT/$name.png" "$DEST/"
    echo "captured $name"
}

set +e
failed=0
for look in light dark; do
    # 主窗口三页签（1080×720）
    shoot 1080 720 "$look" "mac-$look-overview" long -SNOWFOLD_SHOT_TAB overview || failed=1
    shoot 1080 720 "$look" "mac-$look-assets" long -SNOWFOLD_SHOT_TAB assets || failed=1
    shoot 1080 720 "$look" "mac-$look-subscriptions" long -SNOWFOLD_SHOT_TAB subscriptions || failed=1
    # 空库总览
    shoot 1080 720 "$look" "mac-$look-overview-empty" empty -SNOWFOLD_SHOT_TAB overview || failed=1
    # 1440×900（8 条即将扣费档）
    shoot 1440 900 "$look" "mac-$look-overview-1440" long -SNOWFOLD_SHOT_TAB overview || failed=1
    shoot 1440 900 "$look" "mac-$look-assets-1440" long -SNOWFOLD_SHOT_TAB assets || failed=1
    shoot 1440 900 "$look" "mac-$look-subscriptions-1440" long -SNOWFOLD_SHOT_TAB subscriptions || failed=1
    # 设置窗口（680 宽，高随页签）
    shoot 680 560 "$look" "mac-$look-settings-general" long -SNOWFOLD_SHOT_OPEN_SETTINGS general || failed=1
    shoot 680 560 "$look" "mac-$look-settings-data" long -SNOWFOLD_SHOT_OPEN_SETTINGS data || failed=1
    shoot 680 900 "$look" "mac-$look-settings-seal" long -SNOWFOLD_SHOT_OPEN_SETTINGS seal || failed=1
    shoot 680 560 "$look" "mac-$look-settings-about" long -SNOWFOLD_SHOT_OPEN_SETTINGS about || failed=1
    # 居中弹层
    shoot 1080 720 "$look" "mac-$look-dialog-add-asset" long -SNOWFOLD_SHOT_DIALOG asset || failed=1
    shoot 1080 720 "$look" "mac-$look-dialog-adjust" long -SNOWFOLD_SHOT_DIALOG adjust || failed=1
    shoot 1080 720 "$look" "mac-$look-dialog-correct" long -SNOWFOLD_SHOT_DIALOG correct || failed=1
    shoot 1080 720 "$look" "mac-$look-dialog-sub-new" long -SNOWFOLD_SHOT_DIALOG sub-new || failed=1
    shoot 1080 720 "$look" "mac-$look-dialog-sub-edit" long -SNOWFOLD_SHOT_DIALOG sub-edit || failed=1
    # 横幅
    shoot 1080 720 "$look" "mac-$look-toast-notice" long -SNOWFOLD_SHOT_NOTICE || failed=1
    shoot 1080 720 "$look" "mac-$look-toast-undo" long -SNOWFOLD_SHOT_UNDO || failed=1
    # 错误页
    shoot 1080 720 "$look" "mac-$look-error-startup" bad -SNOWFOLD_SHOT_TAB overview || failed=1
    shoot 1080 720 "$look" "mac-$look-error-key" long \
        -SNOWFOLD_KEYCHAIN_ACCOUNT "snowfold-shot-key-$(date +%s)" -SNOWFOLD_KEYCHAIN_FAILURE || failed=1
done
# 负净资产（§2 负数焦点金额）
shoot 1080 720 light "mac-light-overview-negative" neg -SNOWFOLD_SHOT_TAB overview || failed=1
shoot 1080 720 light "mac-light-assets-negative" neg -SNOWFOLD_SHOT_TAB assets || failed=1

if [ "$failed" -ne 0 ]; then
    echo "部分 Mac 截图失败，详见 $APPLE_ROOT/.build/*.log" >&2
    exit 1
fi
echo "Mac 截图完成：$(ls "$DEST" | wc -l | tr -d ' ') 张"
