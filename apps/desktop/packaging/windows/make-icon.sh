#!/bin/sh
# Regenerates apps/desktop/packaging/windows/AppIcon.ico from the shared vector sources in shared/icons/app.
# Requires librsvg (brew install librsvg) for rsvg-convert and python3 for ICO packing.
# The .ico is committed; package.ps1 embeds it into SnowFold.exe through build.rs.
set -eu
cd "$(dirname "$0")/../../../.."

big="shared/icons/app/icon-1024.svg"
small="shared/icons/app/icon-small.svg"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

emit() { # size source
    rsvg-convert -w "$1" -h "$1" "$2" -o "$work/icon-$1.png"
}

# 小尺寸用 icon-small，保证任务栏/资源管理器里清晰。
emit 16 "$small"
emit 24 "$small"
emit 32 "$small"
emit 48 "$small"
emit 64 "$small"
emit 128 "$big"
emit 256 "$big"

python3 - "$work" "apps/desktop/packaging/windows/AppIcon.ico" <<'PY'
import pathlib
import struct
import sys

work = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
sizes = [16, 24, 32, 48, 64, 128, 256]
blobs = [(size, (work / f"icon-{size}.png").read_bytes()) for size in sizes]

# ICONDIR + ICONDIRENTRY[] + PNG 数据；Vista 以后支持 PNG 压缩条目，
# Windows 10/11 的 Explorer、任务栏和 Alt+Tab 都能正确挑选尺寸。
header = struct.pack("<HHH", 0, 1, len(blobs))
offset = 6 + 16 * len(blobs)
entries = bytearray()
for size, data in blobs:
    dimension = 0 if size == 256 else size
    entries += struct.pack(
        "<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(data), offset
    )
    offset += len(data)
out.write_bytes(header + bytes(entries) + b"".join(data for _, data in blobs))
print(f"wrote {out} ({len(blobs)} sizes, {out.stat().st_size} bytes)")
PY
