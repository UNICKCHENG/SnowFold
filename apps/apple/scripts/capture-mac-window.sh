#!/bin/bash
# 用法：capture-mac-window.sh <app> <输出 png> <隔离数据目录> <宽> <高> <light|dark> [启动参数...]
# 应用导出自身窗口编号；录屏由外部驱动负责，沿用执行环境已有的系统权限。
set -euo pipefail
APP_PATH="$1"; OUTPUT="$2"; DATA_DIR="$3"; WIDTH="$4"; HEIGHT="$5"; LOOK="$6"; shift 6
mkdir -p "$(dirname "$OUTPUT")"
rm -f "$OUTPUT.ready" "$OUTPUT.failure"
# Debug 启动参数同时隔离钥匙串 account 与数据库目录，避免旧临时签名的授权弹窗。
case " $* " in
    *" -SNOWFOLD_KEYCHAIN_ACCOUNT "*) ;;
    *)
        container_tmp="$HOME/Library/Containers/app.snowfold/Data/tmp"
        case "$DATA_DIR" in
            "$container_tmp"/*) set -- "$@" -SNOWFOLD_KEYCHAIN_ACCOUNT "${DATA_DIR#"$container_tmp"/}" ;;
            *) echo 'capture data must be inside the application temporary container' >&2; exit 2 ;;
        esac
        ;;
esac
open -W -n -F -a "$APP_PATH" --env "SNOWFOLD_DATA_DIR=$DATA_DIR" --args \
    -SNOWFOLD_SHOT_WIDTH "$WIDTH" -SNOWFOLD_SHOT_HEIGHT "$HEIGHT" \
    -SNOWFOLD_SHOT_PATH "$OUTPUT" -SNOWFOLD_SHOT_APPEARANCE "$LOOK" \
    -SNOWFOLD_SHOT_EXTERNAL "$@" &
launcher=$!
# 已恢复为无窗口状态时，系统 reopen 事件让 SwiftUI 创建主窗口。
sleep 1
open -a "$APP_PATH"
for ((i=0; i<180; i++)); do
    [ -f "$OUTPUT.ready" ] && break
    [ -f "$OUTPUT.failure" ] && break
    kill -0 "$launcher" 2>/dev/null || break
    if [ "$i" -eq 10 ] || [ "$i" -eq 20 ]; then open -a "$APP_PATH"; fi
    sleep 0.2
done
if [ -f "$OUTPUT.ready" ]; then
    window_id="$(cat "$OUTPUT.ready")"
    [[ "$window_id" =~ ^[0-9]+$ ]] || { echo 'invalid application window id' >&2; exit 1; }
    # argv 交给 AppleScript 的 quoted form，路径中的空格与引号均按字面量传递。
    osascript - "$window_id" "$OUTPUT" <<'APPLESCRIPT'
on run argv
    do shell script "/usr/sbin/screencapture -x -o -l " & quoted form of (item 1 of argv) & " " & quoted form of (item 2 of argv)
end run
APPLESCRIPT
fi
wait "$launcher" || true
if [ ! -s "$OUTPUT" ]; then
    cat "$OUTPUT.failure" >&2 2>/dev/null || true
    echo "window capture failed: $OUTPUT" >&2
    exit 1
fi
rm -f "$OUTPUT.ready"
