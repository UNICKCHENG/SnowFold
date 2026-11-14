#if os(macOS)
import AppKit
import CoreGraphics

/// 验收截图钩子（与桌面端 SNOWFOLD_ACCEPTANCE_EVIDENCE_DIR 同一性质）：
/// 以 `SnowFold -SNOWFOLD_SHOT_WIDTH <w> -SNOWFOLD_SHOT_HEIGHT <h> -SNOWFOLD_SHOT_PATH <file>` 启动时，
/// 加载完成后把窗口调整为指定尺寸、截取自身窗口内容并写入 PNG，然后退出。
/// 截取自身窗口不需要「屏幕录制」权限；不带参数启动时完全不生效。
enum AcceptanceCapture {
    static func runIfRequested() {
        let arguments = CommandLine.arguments
        guard let widthIndex = arguments.firstIndex(of: "-SNOWFOLD_SHOT_WIDTH"),
              let heightIndex = arguments.firstIndex(of: "-SNOWFOLD_SHOT_HEIGHT"),
              let pathIndex = arguments.firstIndex(of: "-SNOWFOLD_SHOT_PATH"),
              widthIndex + 1 < arguments.count,
              heightIndex + 1 < arguments.count,
              pathIndex + 1 < arguments.count,
              let width = Double(arguments[widthIndex + 1]),
              let height = Double(arguments[heightIndex + 1])
        else { return }
        let appearanceName = arguments.firstIndex(of: "-SNOWFOLD_SHOT_APPEARANCE").flatMap {
            $0 + 1 < arguments.count ? arguments[$0 + 1] : nil
        }
        capture(width: width, height: height, output: arguments[pathIndex + 1], appearance: appearanceName)
    }

    private static func capture(width: Double, height: Double, output: String, appearance: String?) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            if let appearance {
                NSApp.appearance = NSAppearance(named: appearance == "dark" ? .darkAqua : .aqua)
            }
            guard let window = NSApp.windows.first(where: { $0.windowNumber > 0 && $0.canBecomeKey }) else {
                writeFailureMarker(output, reason: "no application window: \(NSApp.windows.map { "\($0.title) visible=\($0.isVisible)" }.joined(separator: "; "))")
                exit(3)
            }
            window.makeKeyAndOrderFront(nil)
            window.setContentSize(NSSize(width: width, height: height))
            window.center()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                let windowID = CGWindowID(window.windowNumber)
                let image = CGWindowListCreateImage(
                    .null,
                    .optionIncludingWindow,
                    windowID,
                    [.bestResolution, .boundsIgnoreFraming]
                )
                guard let image else {
                    writeFailureMarker(output, reason: "CGWindowListCreateImage failed")
                    exit(4)
                }
                let rep = NSBitmapImageRep(cgImage: image)
                guard let data = rep.representation(using: .png, properties: [:]) else {
                    writeFailureMarker(output, reason: "png encode failed")
                    exit(5)
                }
                do {
                    try data.write(to: URL(fileURLWithPath: output))
                } catch {
                    fputs("SNOWFOLD_SHOT write failed: \(error)\n", stderr)
                    exit(6)
                }
                NSApp.terminate(nil)
            }
        }
    }

    private static func writeFailureMarker(_ path: String, reason: String) {
        try? reason.data(using: .utf8)?.write(to: URL(fileURLWithPath: path + ".failure"))
    }
}
#endif
