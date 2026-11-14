#!/bin/sh
set -eu
if [ "$(uname -s)" != Darwin ]; then
    echo 'macOS is required to package SnowFold.app' >&2
    exit 1
fi
project_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../../../.." && pwd)
cd "$project_dir"
universal=false
case "$#:${1:-}" in
    0:) ;;
    1:--universal) universal=true ;;
    *) echo 'Usage: package.sh [--universal]' >&2; exit 1 ;;
esac
if [ "$universal" = true ]; then
    for target in aarch64-apple-darwin x86_64-apple-darwin; do
        cargo build --locked --release -p snowfold --target "$target"
    done
else
    cargo build --locked --release -p snowfold
fi
target_dir=$(cargo metadata --locked --no-deps --format-version 1 | plutil -extract target_directory raw -o - -)
bundle="$project_dir/dist/SnowFold.app"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources"
if [ "$universal" = true ]; then
    lipo -create "$target_dir/aarch64-apple-darwin/release/snowfold" \
        "$target_dir/x86_64-apple-darwin/release/snowfold" -output "$bundle/Contents/MacOS/SnowFold"
    lipo -info "$bundle/Contents/MacOS/SnowFold"
else
    cp "$target_dir/release/snowfold" "$bundle/Contents/MacOS/SnowFold"
fi
cp apps/desktop/packaging/macos/Info.plist "$bundle/Contents/Info.plist"
cp apps/desktop/packaging/macos/AppIcon.icns apps/desktop/packaging/NOTICE.txt "$bundle/Contents/Resources/"
codesign --force --deep --sign - "$bundle"
codesign --verify --deep --strict --verbose=2 "$bundle"
plutil -lint "$bundle/Contents/Info.plist"
ditto -c -k --sequesterRsrc --keepParent "$bundle" "$project_dir/dist/SnowFold-macos.zip"
if [ "$universal" = true ]; then
    staging=$(mktemp -d "$project_dir/dist/.dmg.XXXXXX")
    trap 'rm -rf "$staging"' EXIT HUP INT TERM
    ditto "$bundle" "$staging/SnowFold.app"
    ln -s /Applications "$staging/Applications"
    hdiutil create -volname SnowFold -srcfolder "$staging" -ov -format UDZO "$project_dir/dist/SnowFold-macos.dmg"
    hdiutil verify "$project_dir/dist/SnowFold-macos.dmg"
fi
echo "$bundle"
