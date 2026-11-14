#!/bin/sh
# Regenerates apps/desktop/packaging/macos/AppIcon.icns from the shared vector sources in shared/icons/app.
# Requires librsvg (brew install librsvg) for rsvg-convert and macOS iconutil.
set -eu
cd "$(dirname "$0")/../../../.."

big="shared/icons/app/icon-1024.svg"
small="shared/icons/app/icon-small.svg"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
set_dir="$work/AppIcon.iconset"
mkdir -p "$set_dir"

emit() { # size source
    rsvg-convert -w "$1" -h "$1" "$2" -o "$set_dir/$3"
}

emit 16 "$small" icon_16x16.png
emit 32 "$small" icon_16x16@2x.png
emit 32 "$small" icon_32x32.png
emit 64 "$small" icon_32x32@2x.png
emit 128 "$big" icon_128x128.png
emit 256 "$big" icon_128x128@2x.png
emit 256 "$big" icon_256x256.png
emit 512 "$big" icon_256x256@2x.png
emit 512 "$big" icon_512x512.png
emit 1024 "$big" icon_512x512@2x.png

iconutil -c icns -o apps/desktop/packaging/macos/AppIcon.icns "$set_dir"
echo "wrote apps/desktop/packaging/macos/AppIcon.icns"
