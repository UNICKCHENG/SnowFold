#!/bin/bash
# 用法：render_all.sh <输出目录> [过滤正则]
set -uo pipefail
cd "$(dirname "$0")"; OUT="$1"; F="${2:-.}"
grep -v '^#' screens.txt | grep -E "$F" | while IFS='|' read -r d n w h q; do
  for t in light dark; do mkdir -p "$OUT/$d"; echo "$OUT/$d/$n-$t.png $w $h $q&theme=$t"; done
done | xargs -P 6 -L 1 ./shot.sh
rm -rf .chrome-*
