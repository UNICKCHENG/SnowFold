#!/bin/bash
# ci-base 整套重拍：run-apple-shots.sh <代码目录> <iOS DerivedData> <macOS DerivedData> <全新输出目录>
# 复用本任务自建的 snowfold-apple 三机型；所有 Xcode 操作均使用统一目录锁。
set -euo pipefail
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$PATH" CARGO_NET_OFFLINE=true
CODE_ROOT="$(cd "$1" && pwd)"; IOS_DD="$2"; MAC_DD="$3"; OUT="$4"
for path in "$IOS_DD" "$MAC_DD" "$OUT"; do
    [[ "$path" = /* ]] || { echo 'DerivedData 与输出目录必须是绝对路径' >&2; exit 2; }
done
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT")" ]; then echo '输出目录必须全新或为空' >&2; exit 2; fi
mkdir -p "$OUT/logs" "$OUT/raw" "$OUT/mac" "$OUT/package"
APPLE_ROOT="$CODE_ROOT/apps/apple"; SCRIPTS="$APPLE_ROOT/scripts"
# 同名内核产物固定隔离在代码目录，避免跨工作区覆盖。
export CARGO_TARGET_DIR="$CODE_ROOT/target"
export DERIVED_DATA="$IOS_DD" SNOWFOLD_SHOTS_DEST="$OUT/raw"
cd "$CODE_ROOT"
uid() {
    xcrun simctl list devices -j | python3 -I -c 'import json,sys; name=sys.argv[1]; rows=[d for ds in json.load(sys.stdin)["devices"].values() for d in ds if d["name"]==name]; assert len(rows)==1, "需要唯一的本任务模拟器："+name; print(rows[0]["udid"])' "$1"
}
IPHONE="$(uid snowfold-apple-iphone-393)"; SMALL="$(uid snowfold-apple-iphone-se)"; IPAD="$(uid snowfold-apple-ipad)"
ORIGINAL_MODE=""; PACKAGE_CAPTURE=""
cleanup() {
    if [ -n "$ORIGINAL_MODE" ]; then swift "$SCRIPTS/capture-display.swift" "$ORIGINAL_MODE" > "$OUT/logs/display-restore.log" 2>&1 || true; fi
    for owned in "$IPHONE" "$SMALL" "$IPAD"; do xcrun simctl shutdown "$owned" 2>/dev/null || true; done
    xcrun simctl list devices | grep snowfold-apple > "$OUT/logs/simulators-shutdown.txt" || true
    cp "$APPLE_ROOT"/.build/shots-*.log "$OUT/logs/" 2>/dev/null || true
    cp "$APPLE_ROOT"/.build/mac-*.log "$OUT/logs/" 2>/dev/null || true
    [ -z "$PACKAGE_CAPTURE" ] || rm -rf "$PACKAGE_CAPTURE"
}
trap cleanup EXIT
xcrun simctl list devices -j > "$OUT/simulators.json"
failed=0
bash "$SCRIPTS/build-core.sh" > "$OUT/logs/build-core.log" 2>&1
for scheme in SnowFold-iOS SnowFold-iOS-snapshots SnowFold-macOS; do
    dd="$IOS_DD"; destination='generic/platform=iOS Simulator'
    if [ "$scheme" = SnowFold-macOS ]; then dd="$MAC_DD"; destination='platform=macOS,arch=arm64'; fi
    bash "$SCRIPTS/with-xcode-lock.sh" xcodebuild -project "$APPLE_ROOT/SnowFold.xcodeproj" -scheme "$scheme" \
        -destination "$destination" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -derivedDataPath "$dd" \
        CODE_SIGNING_ALLOWED=YES build-for-testing > "$OUT/logs/build-$scheme.log" 2>&1 || failed=1
done
[ "$failed" -eq 0 ] || exit "$failed"
bash "$SCRIPTS/seed-fixtures.sh" > "$OUT/logs/seed.log" 2>&1
for platform in ios mac; do
    scheme=SnowFold-iOS; dd="$IOS_DD"; destination="platform=iOS Simulator,id=$IPHONE"
    if [ "$platform" = mac ]; then scheme=SnowFold-macOS; dd="$MAC_DD"; destination='platform=macOS,arch=arm64'; fi
    bash "$SCRIPTS/with-xcode-lock.sh" xcodebuild -project "$APPLE_ROOT/SnowFold.xcodeproj" -scheme "$scheme" \
        -destination "$destination" -derivedDataPath "$dd" CODE_SIGNING_ALLOWED=YES test \
        > "$OUT/logs/tests-$platform.log" 2>&1 || failed=1
done
bash "$SCRIPTS/run-ios-matrix.sh" "$IPHONE" "$SMALL" "$IPAD" > "$OUT/logs/ios-matrix.log" 2>&1 || failed=1
xcrun simctl bootstatus "$IPHONE" -b >/dev/null
xcrun simctl terminate "$IPHONE" app.snowfold 2>/dev/null || true
xcrun simctl io "$IPHONE" screenshot "$OUT/raw/icon-iphone-homescreen.png"
# 显示配置使用会话作用域，才能跨应用进程保留；EXIT 时恢复原模式。
ORIGINAL_MODE="$(swift "$SCRIPTS/capture-display.swift")"
bash "$SCRIPTS/run-mac-shots.sh" "$MAC_DD/Build/Products/Debug/SnowFold.app" "$OUT/mac" \
    "$APPLE_ROOT/.build/fixture-mac" > "$OUT/logs/mac-matrix.log" 2>&1 || failed=1
if SNOWFOLD_ACCEPTANCE_CAPTURE=1 bash "$SCRIPTS/package-macos.sh" > "$OUT/logs/package.log" 2>&1; then
    APP="$APPLE_ROOT/.build/package-macos/Build/Products/Release/SnowFold.app"
    { ls -l "$APP/Contents/Resources/AppIcon.icns"; plutil -p "$APP/Contents/Info.plist" | grep -i icon; xcrun lipo -archs "$APP/Contents/MacOS/SnowFold"; } > "$OUT/package/package-evidence.txt"
    PACKAGE_CAPTURE="$(mktemp -d "$HOME/Library/Containers/app.snowfold/Data/tmp/snowfold-apple-package.XXXXXX")"
    mkdir -p "$PACKAGE_CAPTURE/data"; cp -R "$APPLE_ROOT/.build/long-data-v2/." "$PACKAGE_CAPTURE/data/"
    bash "$SCRIPTS/capture-mac-window.sh" "$APP" "$PACKAGE_CAPTURE/package-first-screen.png" "$PACKAGE_CAPTURE/data" \
        1080 720 light -SNOWFOLD_SHOT_TAB overview > "$OUT/logs/package-first-screen.log" 2>&1 || failed=1
    cp "$PACKAGE_CAPTURE/package-first-screen.png" "$OUT/package/" 2>/dev/null || true
    cp "$PACKAGE_CAPTURE/package-first-screen.png.failure" "$OUT/package/" 2>/dev/null || true
    ditto -c -k --keepParent "$APP" "$OUT/package/SnowFold.app.zip"
    cp "$CODE_ROOT/dist/SnowFold-macos.dmg" "$OUT/package/"
else failed=1; fi
find "$OUT" -name '*.png' -print > "$OUT/screenshots.txt"
exit "$failed"
