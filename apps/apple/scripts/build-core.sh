#!/bin/bash
set -euo pipefail

APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$APPLE_ROOT/../.." && pwd)"
CORE_MANIFEST="$REPO_ROOT/core/Cargo.toml"
BINDGEN_MANIFEST="$REPO_ROOT/core/bindgen/Cargo.toml"
TARGET_DIR="${CARGO_TARGET_DIR:-$REPO_ROOT/target}"
# 调试构建沿用 arm64；发版脚本显式启用 Intel + Apple Silicon 通用切片。
MACOS_UNIVERSAL="${SNOWFOLD_MACOS_UNIVERSAL:-0}"
case "$MACOS_UNIVERSAL" in
    0|1) ;;
    *) echo 'SNOWFOLD_MACOS_UNIVERSAL must be 0 or 1' >&2; exit 1 ;;
esac

for tool in cargo rustup xcrun xcodebuild xcodegen; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
mkdir -p "$TARGET_DIR" "$APPLE_ROOT/.build"
TARGET_DIR="$(cd "$TARGET_DIR" && pwd)"
STAGING="$(mktemp -d "$APPLE_ROOT/.build/core.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT

build_slice() {
    local target="$1" sdk="$2"
    echo "== Building snowfold-core: $target =="
    CARGO_TARGET_DIR="$TARGET_DIR" \
        SDKROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
        IPHONEOS_DEPLOYMENT_TARGET=26.0 MACOSX_DEPLOYMENT_TARGET=26.0 \
        cargo build --locked --release --manifest-path "$CORE_MANIFEST" --target "$target"
    mkdir -p "$STAGING/$target"
    cp "$TARGET_DIR/$target/release/libsnowfold_core.a" "$STAGING/$target/"
    if [[ "$target" == aarch64-apple-darwin ]]; then
        cp "$TARGET_DIR/$target/release/libsnowfold_core.dylib" "$STAGING/"
    fi
}

build_slice aarch64-apple-ios iphoneos
build_slice aarch64-apple-ios-sim iphonesimulator
build_slice aarch64-apple-darwin macosx

mkdir -p "$STAGING/macos"
if [[ "$MACOS_UNIVERSAL" == 1 ]]; then
    build_slice x86_64-apple-darwin macosx
    xcrun lipo -create \
        "$STAGING/aarch64-apple-darwin/libsnowfold_core.a" \
        "$STAGING/x86_64-apple-darwin/libsnowfold_core.a" \
        -output "$STAGING/macos/libsnowfold_core.a"
    MACOS_ARCHS="$(xcrun lipo -archs "$STAGING/macos/libsnowfold_core.a")"
    for arch in arm64 x86_64; do
        [[ " $MACOS_ARCHS " == *" $arch "* ]] || { echo "Missing core architecture: $arch" >&2; exit 1; }
    done
    MACOS_DESCRIPTION='arm64 + x86_64'
else
    cp "$STAGING/aarch64-apple-darwin/libsnowfold_core.a" "$STAGING/macos/"
    MACOS_DESCRIPTION=arm64
fi

mkdir -p "$STAGING/Generated/Swift" "$STAGING/Headers"
echo '== Generating Swift bindings from the compiled core =='
CARGO_TARGET_DIR="$TARGET_DIR" cargo run --locked --manifest-path "$BINDGEN_MANIFEST" \
    --bin uniffi-bindgen -- generate --library "$STAGING/libsnowfold_core.dylib" \
    --language swift --out-dir "$STAGING/Generated/Swift" --no-format
cp "$STAGING/Generated/Swift/SnowFoldCoreFFI.h" "$STAGING/Headers/"
cp "$STAGING/Generated/Swift/SnowFoldCoreFFI.modulemap" "$STAGING/Headers/module.modulemap"

bash "$APPLE_ROOT/scripts/with-xcode-lock.sh" xcodebuild -create-xcframework \
    -library "$STAGING/aarch64-apple-ios/libsnowfold_core.a" -headers "$STAGING/Headers" \
    -library "$STAGING/aarch64-apple-ios-sim/libsnowfold_core.a" -headers "$STAGING/Headers" \
    -library "$STAGING/macos/libsnowfold_core.a" -headers "$STAGING/Headers" \
    -output "$STAGING/Generated/SnowFoldCore.xcframework"

# Publish bindings and binaries together only after all slices succeed.
rm -rf "$APPLE_ROOT/Generated"
mv "$STAGING/Generated" "$APPLE_ROOT/Generated"
xcodegen generate --spec "$APPLE_ROOT/project.yml" --project "$APPLE_ROOT"
echo "== Apple core ready: iOS arm64 / iOS Simulator arm64 / macOS $MACOS_DESCRIPTION =="
echo "XCFramework: $APPLE_ROOT/Generated/SnowFoldCore.xcframework"
echo "Swift bindings: $APPLE_ROOT/Generated/Swift/SnowFoldCore.swift"
echo "Xcode project: $APPLE_ROOT/SnowFold.xcodeproj"
