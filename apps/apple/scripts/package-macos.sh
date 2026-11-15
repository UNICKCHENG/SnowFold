#!/bin/bash
# 正式 Mac 安装包：通用 SwiftUI 应用 + /Applications 拖放入口。
set -euo pipefail

APPLE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$APPLE_ROOT/../.." && pwd)"
DERIVED_DATA="$APPLE_ROOT/.build/package-macos"

for tool in xcodebuild xcrun hdiutil ditto; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done

# build-core 自身负责绑定与 XcodeGen；通用模式只在正式打包时启用。
SNOWFOLD_MACOS_UNIVERSAL=1 bash "$APPLE_ROOT/scripts/build-core.sh"

# CI 证据模式：SNOWFOLD_ACCEPTANCE_CAPTURE=1 时为 Release 包注入 ACCEPTANCE 编译条件，
# 仅启用截图钩子（AcceptanceCapture 与启动参数钩子）；默认打包不带任何钩子。
CAPTURE_FLAGS=()
if [ "${SNOWFOLD_ACCEPTANCE_CAPTURE:-0}" = "1" ]; then
    CAPTURE_FLAGS=('OTHER_SWIFT_FLAGS=$(inherited) -DACCEPTANCE')
fi
xcodebuild build \
    -project "$APPLE_ROOT/SnowFold.xcodeproj" \
    -scheme SnowFold-macOS -configuration Release \
    -destination 'generic/platform=macOS' \
    -derivedDataPath "$DERIVED_DATA" \
    "${CAPTURE_FLAGS[@]+"${CAPTURE_FLAGS[@]}"}" \
    ARCHS='arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO

APP="$DERIVED_DATA/Build/Products/Release/SnowFold.app"
APP_ARCHS="$(xcrun lipo -archs "$APP/Contents/MacOS/SnowFold")"
for arch in arm64 x86_64; do
    [[ " $APP_ARCHS " == *" $arch "* ]] || { echo "Missing app architecture: $arch" >&2; exit 1; }
done
xcrun lipo -info "$APP/Contents/MacOS/SnowFold"

STAGING="$(mktemp -d "$APPLE_ROOT/.build/dmg.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
mkdir -p "$STAGING/image" "$REPO_ROOT/dist"
ditto "$APP" "$STAGING/image/SnowFold.app"
ln -s /Applications "$STAGING/image/Applications"

# 仅在完整映像创建成功后替换正式产物；第三方声明由发版流程单独附上。
hdiutil create -volname SnowFold -srcfolder "$STAGING/image" \
    -format UDZO -ov "$STAGING/SnowFold-macos.dmg"
mv -f "$STAGING/SnowFold-macos.dmg" "$REPO_ROOT/dist/SnowFold-macos.dmg"
echo "== Package ready: $REPO_ROOT/dist/SnowFold-macos.dmg =="
