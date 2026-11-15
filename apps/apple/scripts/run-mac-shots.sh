#!/bin/bash
# 用法：run-mac-shots.sh <SnowFold.app 路径> <截图输出目录> <fixture 根目录>
# 每屏独立复制夹具；截图只来自本次启动的应用窗口。
set -euo pipefail
APP_PATH="$1"; DEST="$2"; FIXTURE_ROOT="$3"
APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER_TMP="$HOME/Library/Containers/app.snowfold/Data/tmp"
mkdir -p "$CONTAINER_TMP" "$DEST"
CAPTURE_ROOT="$(mktemp -d "$CONTAINER_TMP/snowfold-apple-capture.XXXXXX")"
trap 'rm -rf "$CAPTURE_ROOT"' EXIT
failed=0
shoot() {
    local width="$1" height="$2" look="$3" name="$4" fixture="$5"; shift 5
    local data="$CAPTURE_ROOT/$name-$look-data" output="$CAPTURE_ROOT/$name-$look.png"
    mkdir -p "$data"
    if [ "$fixture" != empty ]; then cp -R "$FIXTURE_ROOT/$fixture/." "$data/"; fi
    if bash "$APPLE_ROOT/scripts/capture-mac-window.sh" "$APP_PATH" "$output" "$data" \
        "$width" "$height" "$look" "$@" > "$APPLE_ROOT/.build/mac-$name-$look.log" 2>&1; then
        cp "$output" "$DEST/"
        echo "captured $name-$look"
    else
        cp "$output.failure" "$DEST/$name-$look.failure" 2>/dev/null || true
        echo "FAILED $name-$look" >&2
        failed=1
    fi
}
for look in light dark; do
    for tab in overview assets subscriptions; do
        case "$tab" in overview) n=01;; assets) n=02;; subscriptions) n=04;; esac
        shoot 1080 720 "$look" "$n-$tab" standard -SNOWFOLD_SHOT_TAB "$tab"
        shoot 1080 720 "$look" "$n-$tab-empty" empty -SNOWFOLD_SHOT_TAB "$tab"
        if [ "$tab" != subscriptions ]; then
            shoot 1080 720 "$look" "$n-$tab-long" long -SNOWFOLD_SHOT_TAB "$tab"
        fi
        shoot 1440 900 "$look" "$n-$tab-long-1440" long -SNOWFOLD_SHOT_TAB "$tab"
    done
    shoot 1440 900 "$look" 01-overview-1440 standard -SNOWFOLD_SHOT_TAB overview
    for tab in overview assets; do
        case "$tab" in overview) n=01;; assets) n=02;; esac
        shoot 1080 720 "$look" "$n-$tab-negative" neg -SNOWFOLD_SHOT_TAB "$tab"
    done
    for tab in general data seal about; do
        height=560; [ "$tab" = seal ] && height=900
        name="06-settings-$tab"; [ "$tab" = seal ] && name=06-settings-seal-sync-off
        shoot 680 "$height" "$look" "$name" standard -SNOWFOLD_SHOT_OPEN_SETTINGS "$tab"
    done
    extra=(); [ "$look" = dark ] && extra=(-SNOWFOLD_SYNC_DARK)
    shoot 680 900 "$look" 06-settings-seal-sync-on empty \
        -SNOWFOLD_KEYCHAIN_ACCOUNT "snowfold-apple-mac-sync-$look-$(date +%s)" \
        -SNOWFOLD_SYNC_ACCEPTANCE s3 "${extra[@]+"${extra[@]}"}" -SNOWFOLD_SHOT_OPEN_SETTINGS seal
    for dialog in asset adjust correct sub-new sub-edit; do
        tab=assets
        case "$dialog" in
            asset) name=03-add-asset;; adjust) name=03-adjust;; correct) name=03-correct;;
            sub-new) name=05-sub-new; tab=subscriptions;; sub-edit) name=05-sub-edit; tab=subscriptions;;
        esac
        shoot 1080 720 "$look" "$name" standard -SNOWFOLD_SHOT_TAB "$tab" \
            -SNOWFOLD_SHOT_DIALOG "$dialog" -SNOWFOLD_SHOT_PREFILL -SNOWFOLD_SHOT_HIDE_KEYBOARD
        if [ "$dialog" != sub-edit ]; then
            shoot 1080 720 "$look" "$name-error" standard -SNOWFOLD_SHOT_TAB "$tab" \
                -SNOWFOLD_SHOT_DIALOG "$dialog" -SNOWFOLD_SHOT_PREFILL -SNOWFOLD_SHOT_HIDE_KEYBOARD -SNOWFOLD_SHOT_FORM_ERROR
        fi
    done
    shoot 1080 720 "$look" 08-toast-notice standard -SNOWFOLD_SHOT_TAB subscriptions -SNOWFOLD_SHOT_NOTICE
    shoot 1080 720 "$look" 08-toast-undo standard -SNOWFOLD_SHOT_TAB assets -SNOWFOLD_SHOT_UNDO
    shoot 1080 720 "$look" 09-error-startup bad -SNOWFOLD_SHOT_TAB overview
    shoot 1080 720 "$look" 09-error-key empty -SNOWFOLD_KEYCHAIN_ACCOUNT "snowfold-apple-mac-key-$look-$(date +%s)" -SNOWFOLD_KEYCHAIN_FAILURE
 done
exit "$failed"
