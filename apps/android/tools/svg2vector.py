#!/usr/bin/env python3
"""Convert shared/icons/ui Lucide SVGs (24x24 stroke icons) into Android
vector drawables. Stdlib only; runs from a Gradle Exec task. Shapes are
converted to pathData; strokes keep round caps/joins at width 2. Compose
Icon() recolors the drawable, so strokes use opaque black.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG_NS = "{http://www.w3.org/2000/svg}"


def num(value: str, default: float = 0.0) -> float:
    try:
        return float(str(value).strip().rstrip("px"))
    except (TypeError, ValueError):
        return default


def fmt(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return text if text else "0"


def circle_to_path(attrib: dict) -> str:
    cx, cy, r = num(attrib.get("cx")), num(attrib.get("cy")), num(attrib.get("r"))
    return (
        f"M {fmt(cx - r)} {fmt(cy)} "
        f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(2 * r)} 0 "
        f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(-2 * r)} 0"
    )


def line_to_path(attrib: dict) -> str:
    return (
        f"M {fmt(num(attrib.get('x1')))} {fmt(num(attrib.get('y1')))} "
        f"L {fmt(num(attrib.get('x2')))} {fmt(num(attrib.get('y2')))}"
    )


def rect_to_path(attrib: dict) -> str:
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


def poly_to_path(attrib: dict, close: bool) -> str:
    points = re.findall(r"[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?", attrib.get("points", ""))
    coords = [f"{fmt(float(points[i]))} {fmt(float(points[i + 1]))}" for i in range(0, len(points) - 1, 2)]
    if len(coords) < 2:
        return ""
    body = "M " + coords[0] + " " + " ".join(f"L {c}" for c in coords[1:])
    return body + (" Z" if close else "")


def shape_paths(root: ET.Element) -> list[str]:
    paths: list[str] = []
    for element in root.iter():
        tag = element.tag.replace(SVG_NS, "")
        attrib = {key.replace(SVG_NS, ""): value for key, value in element.attrib.items()}
        if tag == "path":
            data = attrib.get("d", "").strip()
            if data:
                paths.append(data)
        elif tag == "circle":
            paths.append(circle_to_path(attrib))
        elif tag == "line":
            paths.append(line_to_path(attrib))
        elif tag == "rect":
            paths.append(rect_to_path(attrib))
        elif tag == "polyline":
            paths.append(poly_to_path(attrib, close=False))
        elif tag == "polygon":
            paths.append(poly_to_path(attrib, close=True))
    return [p for p in paths if p]


def kotlin_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("\"", "\\\"")


def convert(source_dir: Path, output_dir: Path) -> int:
    output_dir.mkdir(parents=True, exist_ok=True)
    icons: list[tuple[str, str]] = []
    for svg in sorted(source_dir.glob("*.svg")):
        try:
            root = ET.parse(svg).getroot()
        except ET.ParseError as error:
            print(f"skip {svg.name}: {error}", file=sys.stderr)
            continue
        view_box = (root.get("viewBox") or "0 0 24 24").split()
        if len(view_box) != 4 or (view_box[2], view_box[3]) != ("24", "24"):
            # 品牌图形（mark.svg 等）不是 24x24 行内图标，跳过。
            print(f"skip {svg.name}: viewBox {root.get('viewBox')}")
            continue
        paths = shape_paths(root)
        if not paths:
            print(f"skip {svg.name}: no shapes", file=sys.stderr)
            continue
        icons.append((svg.stem.replace("-", "_"), " ".join(paths)))

    body = []
    for name, data in icons:
        body.append(
            f'    val ic_{name}: ImageVector by lazy {{\n'
            f'        icon("ic_{name}", listOf("{kotlin_escape(data)}"))\n'
            f"    }}\n"
        )
    code = (
        "// GENERATED from shared/icons/ui (Lucide) by tools/svg2vector.py - do not edit.\n"
        "package app.snowfold.ui.components\n\n"
        "import androidx.compose.ui.graphics.Color\n"
        "import androidx.compose.ui.graphics.SolidColor\n"
        "import androidx.compose.ui.graphics.StrokeCap\n"
        "import androidx.compose.ui.graphics.StrokeJoin\n"
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "import androidx.compose.ui.graphics.vector.PathParser\n"
        "import androidx.compose.ui.unit.Dp\n\n"
        "private fun icon(name: String, paths: List<String>): ImageVector =\n"
        "    ImageVector.Builder(\n"
        '        name = name,\n'
        "        defaultWidth = Dp(24f),\n"
        "        defaultHeight = Dp(24f),\n"
        "        viewportWidth = 24f,\n"
        "        viewportHeight = 24f,\n"
        "    ).apply {\n"
        "        paths.forEach { data ->\n"
        "            addPath(\n"
        "                pathData = PathParser().parsePathString(data).toNodes(),\n"
        "                fill = null,\n"
        "                stroke = SolidColor(Color.Black),\n"
        "                strokeLineWidth = 2f,\n"
        "                strokeLineCap = StrokeCap.Round,\n"
        "                strokeLineJoin = StrokeJoin.Round,\n"
        "            )\n"
        "        }\n"
        "    }.build()\n\n"
        "/** Lucide 语义图标（docs/design.md §4 Android 列）。 */\n"
        "object IconsGen {\n" + "\n".join(body) + "}\n"
    )
    (output_dir / "IconsGen.kt").write_text(code, encoding="utf-8")
    for stale in output_dir.glob("*.xml"):
        stale.unlink()
    print(f"converted {len(icons)} icons -> {output_dir}/IconsGen.kt")
    return len(icons)


if __name__ == "__main__":
    convert(Path(sys.argv[1]), Path(sys.argv[2]))
