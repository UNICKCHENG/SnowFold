#!/usr/bin/env python3
"""将 shared/icons/line 与 fill 的 IconMCP SVG 生成 Compose ImageVector。
仅依赖 Python 标准库；保留填充、描边与源 SVG 的旋转组（chevron-left）。
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG_NS = "{http://www.w3.org/2000/svg}"


def num(value, default=0.0):
    try:
        return float(str(value).strip().rstrip("px"))
    except (TypeError, ValueError):
        return default


def fmt(value):
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return text if text else "0"


def circle_to_path(attrib):
    cx, cy, r = num(attrib.get("cx")), num(attrib.get("cy")), num(attrib.get("r"))
    return (
        f"M {fmt(cx - r)} {fmt(cy)} "
        f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(2 * r)} 0 "
        f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(-2 * r)} 0"
    )


def line_to_path(attrib):
    return (
        f"M {fmt(num(attrib.get('x1')))} {fmt(num(attrib.get('y1')))} "
        f"L {fmt(num(attrib.get('x2')))} {fmt(num(attrib.get('y2')))}"
    )


def rect_to_path(attrib):
    x, y = num(attrib.get("x")), num(attrib.get("y"))
    w, h = num(attrib.get("width")), num(attrib.get("height"))
    rx = num(attrib.get("rx"), 0.0)
    ry = num(attrib.get("ry"), rx)
    if rx <= 0 and ry <= 0:
        return f"M {fmt(x)} {fmt(y)} H {fmt(x + w)} V {fmt(y + h)} H {fmt(x)} Z"
    rx = min(rx or ry, w / 2)
    ry = min(ry or rx, h / 2)
    return (
        f"M {fmt(x + rx)} {fmt(y)} "
        f"H {fmt(x + w - rx)} A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + w)} {fmt(y + ry)} "
        f"V {fmt(y + h - ry)} A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + w - rx)} {fmt(y + h)} "
        f"H {fmt(x + rx)} A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x)} {fmt(y + h - ry)} "
        f"V {fmt(y + ry)} A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + rx)} {fmt(y)} Z"
    )


def poly_to_path(attrib, close):
    points = re.findall(r"[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?", attrib.get("points", ""))
    coords = [f"{fmt(float(points[i]))} {fmt(float(points[i + 1]))}" for i in range(0, len(points) - 1, 2)]
    if len(coords) < 2:
        return ""
    body = "M " + coords[0] + " " + " ".join(f"L {c}" for c in coords[1:])
    return body + (" Z" if close else "")


def shape_path(tag, attrib):
    if tag == "path":
        return attrib.get("d", "").strip()
    if tag == "circle":
        return circle_to_path(attrib)
    if tag == "line":
        return line_to_path(attrib)
    if tag == "rect":
        return rect_to_path(attrib)
    if tag == "polyline":
        return poly_to_path(attrib, False)
    if tag == "polygon":
        return poly_to_path(attrib, True)
    return ""


def icon_paths(root):
    """提取填充、描边与旋转；遇到资源集中未约定的变换就报错。"""
    root_fill = root.get("fill", "none")
    root_stroke = root.get("stroke", "none")
    fills, strokes = [], []
    rotation = 0
    for element in root.iter():
        tag = element.tag.replace(SVG_NS, "")
        attrib = {key.replace(SVG_NS, ""): value for key, value in element.attrib.items()}
        if tag in ("svg", "g"):
            if attrib.get("transform"):
                match = re.fullmatch(r"rotate\(\s*(-?\d+)\s+8\s+8\s*\)", attrib["transform"])
                if not match:
                    raise ValueError(f"不支持的 SVG 变换：{attrib['transform']}")
                rotation += int(match.group(1))
            continue
        data = shape_path(tag, attrib)
        if not data:
            continue
        fill = attrib.get("fill", root_fill)
        stroke = attrib.get("stroke", root_stroke)
        if fill not in (None, "", "none"):
            fills.append(data)
        if stroke not in (None, "", "none"):
            strokes.append(data)
    return fills, strokes, rotation


def kotlin_escape(text):
    return text.replace("\\", "\\\\").replace("\"", "\\\"")


def convert(line_dir: Path, fill_dir: Path, output_dir: Path) -> int:
    output_dir.mkdir(parents=True, exist_ok=True)
    sets = [("line", line_dir), ("fill", fill_dir)]
    generated = {}
    for prefix, source in sets:
        for svg in sorted(source.glob("*.svg")):
            root = ET.parse(svg).getroot()
            view_box = (root.get("viewBox") or "0 0 16 16").split()
            if len(view_box) != 4 or (view_box[2], view_box[3]) != ("16", "16"):
                print(f"skip {prefix}/{svg.name}: viewBox {root.get('viewBox')}")
                continue
            fills, strokes, rotation = icon_paths(root)
            if not fills and not strokes:
                print(f"skip {prefix}/{svg.name}: no shapes")
                continue
            generated[f"{prefix}_{svg.stem.replace('-', '_')}"] = (fills, strokes, rotation)

    body = []
    for name, (fills, strokes, rotation) in generated.items():
        body.append(f"    val ic_{name}: ImageVector by lazy {{\n")
        body.append("        icon(\n")
        body.append(f'            "ic_{name}",\n')
        body.append(f'            fills = listOf({", ".join(chr(34) + kotlin_escape(d) + chr(34) for d in fills)}),\n')
        body.append(f'            strokes = listOf({", ".join(chr(34) + kotlin_escape(d) + chr(34) for d in strokes)}),\n')
        body.append(f"            rotation = {rotation}f,\n")
        body.append("        )\n    }\n\n")

    code = (
        "// 由 tools/svg2vector.py 从共用 IconMCP 图标生成，不要手改。\n"
        "package app.snowfold.ui.components\n\n"
        "import androidx.compose.ui.graphics.Color\n"
        "import androidx.compose.ui.graphics.SolidColor\n"
        "import androidx.compose.ui.graphics.StrokeCap\n"
        "import androidx.compose.ui.graphics.StrokeJoin\n"
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "import androidx.compose.ui.graphics.vector.PathParser\n"
        "import androidx.compose.ui.unit.Dp\n\n"
        "private fun icon(name: String, fills: List<String>, strokes: List<String>, rotation: Float): ImageVector =\n"
        "    ImageVector.Builder(\n"
        "        name = name,\n"
        "        defaultWidth = Dp(16f),\n"
        "        defaultHeight = Dp(16f),\n"
        "        viewportWidth = 16f,\n"
        "        viewportHeight = 16f,\n"
        "    ).apply {\n"
        "        addGroup(rotate = rotation, pivotX = 8f, pivotY = 8f)\n"
        "        fills.forEach { data ->\n"
        "            addPath(\n"
        "                pathData = PathParser().parsePathString(data).toNodes(),\n"
        "                fill = SolidColor(Color.Black),\n"
        "                fillAlpha = 1f,\n"
        "            )\n"
        "        }\n"
        "        strokes.forEach { data ->\n"
        "            addPath(\n"
        "                pathData = PathParser().parsePathString(data).toNodes(),\n"
        "                fill = null,\n"
        "                stroke = SolidColor(Color.Black),\n"
        "                strokeLineWidth = 0.833f,\n"
        "                strokeLineCap = StrokeCap.Round,\n"
        "                strokeLineJoin = StrokeJoin.Round,\n"
        "            )\n"
        "        }\n"
        "        clearGroup()\n"
        "    }.build()\n\n"
        "/** IconMCP 线条/实心图标（docs/design.md §3）。 */\n"
        "object IconsGen {\n" + "\n".join(body) + "}\n"
    )
    (output_dir / "IconsGen.kt").write_text(code, encoding="utf-8")
    for stale in output_dir.glob("*.xml"):
        stale.unlink()
    print(f"converted {len(generated)} icons -> {output_dir}/IconsGen.kt")
    return len(generated)


if __name__ == "__main__":
    convert(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
