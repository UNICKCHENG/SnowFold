#!/bin/bash
# 用法：shot.sh <输出.png> <w> <h> <查询串>；@2x 输出
set -euo pipefail
OUT="$1"; W="$2"; H="$3"; QS="$4"
DIR="$(cd "$(dirname "$0")" && pwd)"
google-chrome --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size="$W,$H" \
  --virtual-time-budget=4000 --allow-file-access-from-files --user-data-dir="$(mktemp -d "$DIR/.chrome-XXXXXX")" \
  --screenshot="$OUT" "file://$DIR/mock/mock.html?w=$W&h=$H&$QS" >/dev/null 2>&1
