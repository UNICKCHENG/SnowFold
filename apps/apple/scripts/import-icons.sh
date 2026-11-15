#!/bin/bash
# 把 shared/icons/{line,fill} 的 IconMCP SVG 复制进 Asset Catalog（矢量 + 模板渲染）。
# 入库的是复制产物；shared 更新后重跑本脚本即可同步。SF Symbols 一律不再使用。
set -euo pipefail

APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARED_ICONS="$APPLE_ROOT/../../shared/icons"
CATALOG="$APPLE_ROOT/Sources/Assets.xcassets"

for style in line fill; do
    suffix=""
    if [ "$style" = fill ]; then suffix="-fill"; fi
    for svg in "$SHARED_ICONS/$style"/*.svg; do
        base="$(basename "$svg" .svg)"
        set_name="ic-${base}${suffix}"
        dir="$CATALOG/${set_name}.imageset"
        mkdir -p "$dir"
        cp "$svg" "$dir/${set_name}.svg"
        cat > "$dir/Contents.json" <<JSON
{
  "images" : [
    {
      "filename" : "${set_name}.svg",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "preserves-vector-representation" : true,
    "template-rendering-intent" : "template"
  }
}
JSON
    done
done
echo "icons imported: $(find "$CATALOG" -name '*.imageset' -maxdepth 1 | wc -l | tr -d ' ') imagesets"
