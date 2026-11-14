#!/bin/bash
# 归集截图：从两台 snowfold-apple 模拟器的 runner tmp 目录拷到交付目录。
set -eo pipefail
UDIDS=(
    "07E31642-A3E3-44A7-892D-24358BFA7C9A"
    "3F68DE99-682B-4B2A-BEEA-AA9E3C993571"
)
DEST="/Users/cheng/Documents/github/billfold/.fullstack/layout/shots/apple-full"
mkdir -p "$DEST"
for udid in "${UDIDS[@]}"; do
    while IFS= read -r dir; do
        for f in "$dir"/se3-*.png "$dir"/e16-*.png; do
            [ -f "$f" ] || continue
            base="$(basename "$f")"
            if [ ! -f "$DEST/$base" ] || [ "$f" -nt "$DEST/$base" ]; then
                cp "$f" "$DEST/$base"
            fi
        done
    done < <(find ~/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Data/Application -type d -name "snowfold-shots" 2>/dev/null)
done
cp /Users/cheng/Documents/github/billfold/apps/apple/.build/mac-shots/mac-*.png "$DEST/" 2>/dev/null || true
ls -la "$DEST"
