import CoreGraphics

// 验收画布使用会话作用域，跨截图进程保持；传入模式编号时恢复该模式。
let display = CGMainDisplayID()
let modes = CGDisplayCopyAllDisplayModes(
    display, [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
) as! [CGDisplayMode]
let original = CGDisplayCopyDisplayMode(display)!
let target: CGDisplayMode?
if let argument = CommandLine.arguments.dropFirst().first {
    guard let modeID = UInt32(argument) else { fatalError("显示模式编号必须是整数") }
    target = modes.first { $0.ioDisplayModeID == modeID }
} else {
    target = modes.filter { $0.width >= 1440 && $0.height >= 1000 }
        .min { $0.width * $0.height < $1.width * $1.height }
}
guard let target else { fatalError("没有满足验收要求的显示模式") }
var configuration: CGDisplayConfigRef?
precondition(CGBeginDisplayConfiguration(&configuration) == .success)
precondition(CGConfigureDisplayWithDisplayMode(configuration!, display, target, nil) == .success)
precondition(CGCompleteDisplayConfiguration(configuration!, .forSession) == .success)
print(original.ioDisplayModeID)
