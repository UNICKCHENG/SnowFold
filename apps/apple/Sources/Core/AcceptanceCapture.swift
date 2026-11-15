#if os(macOS)
import AppKit
import ScreenCaptureKit

/// 验收截图钩子：指定尺寸、主题与输出路径时截取自身窗口；普通启动不生效。
/// 使用系统 ScreenCaptureKit，避免 macOS 26 已不可用的 CGWindowListCreateImage。
@MainActor
enum AcceptanceCapture {
    static func runIfRequested() {
        let arguments = CommandLine.arguments
        guard let width = value("-SNOWFOLD_SHOT_WIDTH", in: arguments).flatMap(Double.init),
              let height = value("-SNOWFOLD_SHOT_HEIGHT", in: arguments).flatMap(Double.init),
              let output = value("-SNOWFOLD_SHOT_PATH", in: arguments) else { return }
        let appearance = value("-SNOWFOLD_SHOT_APPEARANCE", in: arguments)
        Task { await capture(width: width, height: height, output: output, appearance: appearance) }
    }

    private static func value(_ flag: String, in arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    private static func capture(width: Double, height: Double, output: String, appearance: String?) async {
        try? await Task.sleep(for: .seconds(3.5))
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        if let appearance {
            NSApp.appearance = NSAppearance(named: appearance == "dark" ? .darkAqua : .aqua)
        }
        guard let window = NSApp.keyWindow ?? NSApp.windows.first(where: { $0.isVisible && $0.canBecomeKey }) else {
            fail(output, reason: "no application window")
            return
        }
        window.setFrame(NSRect(x: window.frame.minX, y: window.frame.minY, width: width, height: height), display: true)
        window.center()
        window.makeKeyAndOrderFront(nil)
        try? await Task.sleep(for: .seconds(1.2))
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let capturedWindow = content.windows.first(where: { $0.windowID == CGWindowID(window.windowNumber) }) else {
                fail(output, reason: "own window is missing from ScreenCaptureKit content")
                return
            }
            let filter = SCContentFilter(desktopIndependentWindow: capturedWindow)
            let config = SCStreamConfiguration()
            let scale = window.backingScaleFactor
            config.width = Int(width * scale)
            config.height = Int(height * scale)
            config.showsCursor = false
            let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
            let rep = NSBitmapImageRep(cgImage: image)
            guard let png = rep.representation(using: .png, properties: [:]) else {
                fail(output, reason: "png encode failed")
                return
            }
            try png.write(to: URL(fileURLWithPath: output))
            NSApp.terminate(nil)
        } catch {
            fail(output, reason: "ScreenCaptureKit: \(error)")
        }
    }

    private static func fail(_ output: String, reason: String) {
        try? Data(reason.utf8).write(to: URL(fileURLWithPath: output + ".failure"))
        exit(3)
    }
}
#endif
