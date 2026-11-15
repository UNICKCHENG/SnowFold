import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// 设计令牌（docs/design.md §1，浅/深成对）。
/// 桌面形态（Mac）与移动形态（iPhone/iPad）只有字号与控件尺寸分档（§4）。
extension Color {
    /// 品牌色「冰川青」：主按钮、选中态、分段控件、开关、链接、焦点环
    static let brand = Color(light: 0x076B75, dark: 0x3FC1CC)
    /// 选中行与导航选中项的底、空状态圆底、浅色按钮底
    static let brandSoft = Color(light: 0xE1EDEE, dark: 0x223A3E)
    /// 品牌色实底上的文字与图标（深色模式用深字）
    static let onBrand = Color(light: 0xFFFFFF, dark: 0x0E0E10)
    /// 窗口背景
    static let bg = Color(light: 0xF4F4F6, dark: 0x0E0E10)
    /// 卡片、弹层
    static let cardBg = Color(light: 0xFFFFFF, dark: 0x1C1C1F)
    /// 分隔线、输入框描边、禁用底
    static let sep = Color(light: 0xE2E2E7, dark: 0x2F2F35)
    /// 次要文字、未选中图标
    static let text2 = Color(light: 0x66666D, dark: 0x9C9CA4)
    /// 主文字
    static let ink = Color(light: 0x1C1C1E, dark: 0xF2F2F5)
    /// 负债、负值、逾期、错误、危险按钮
    static let neg = Color(light: 0xBF3228, dark: 0xFF6A5E)
    static let negSoft = Color(light: 0xF9EAEA, dark: 0x402829)
    /// 提醒：7 天内扣费、密钥不可用
    static let warn = Color(light: 0x9A5B00, dark: 0xF5B041)
    static let warnSoft = Color(light: 0xF3EBE0, dark: 0x3F3424)
    /// 成功：续费、同步完成
    static let pos = Color(light: 0x1F7A3E, dark: 0x4CC77A)

    /// 分类识别色（图标块与构成条专用，不用于文字）
    static func categoryColor(_ code: String) -> Color {
        switch code {
        case "fixed": Color(light: 0x3B7D23, dark: 0x7CC75B)
        case "investment": Color(light: 0x6A4FD0, dark: 0xA796FF)
        case "receivable": Color(light: 0x1F6FB0, dark: 0x5FA8E8)
        case "debt": Color(light: 0xBF3228, dark: 0xFF6A5E)
        default: Color(light: 0x946100, dark: 0xE0A93F) // cash
        }
    }

    /// 分类图标块底色（cat-*-soft）
    static func categorySoft(_ code: String) -> Color {
        switch code {
        case "fixed": Color(light: 0xE7EFE5, dark: 0x2D3B2A)
        case "investment": Color(light: 0xEDEAF9, dark: 0x353247)
        case "receivable": Color(light: 0xE4EEF6, dark: 0x283543)
        case "debt": Color(light: 0xF9EAEA, dark: 0x452A2A)
        default: Color(light: 0xF2ECE0, dark: 0x3F3525) // cash
        }
    }

    init(light: UInt32, dark: UInt32) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 0xFF) / 255,
                           green: CGFloat((value >> 8) & 0xFF) / 255,
                           blue: CGFloat(value & 0xFF) / 255, alpha: 1)
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .vibrantDark, .aqua, .vibrantLight]) != .aqua
                && appearance.bestMatch(from: [.darkAqua, .vibrantDark, .aqua, .vibrantLight]) != .vibrantLight
            let value = isDark ? dark : light
            return NSColor(srgbRed: CGFloat((value >> 16) & 0xFF) / 255,
                           green: CGFloat((value >> 8) & 0xFF) / 255,
                           blue: CGFloat(value & 0xFF) / 255, alpha: 1)
        }))
        #else
        self.init(.white)
        #endif
    }
}

extension ShapeStyle where Self == Color {
    static var brand: Color { .brand }
    static var neg: Color { .neg }
    static var pos: Color { .pos }
    static var warn: Color { .warn }
    static var ink: Color { .ink }
    static var text2: Color { .text2 }
    static var cardBg: Color { .cardBg }
}

/// 五分类外观：名称与图标（IconMCP 线条图标入 Asset Catalog，实心版用于选中态）。
enum CategoryStyle {
    static let order = ["cash", "fixed", "investment", "receivable", "debt"]

    static func name(_ code: String) -> String {
        Copy.categoryName(code)
    }

    /// 线条图标（Asset Catalog 里的模板图）。
    static func icon(_ code: String) -> String {
        switch code {
        case "fixed": "ic-home"
        case "investment": "ic-trending-up"
        case "receivable": "ic-hand-coins"
        case "debt": "ic-credit-card"
        default: "ic-banknote" // cash
        }
    }

    static func rank(_ code: String) -> Int {
        order.firstIndex(of: code) ?? 9
    }
}

/// 形态分档：Mac 为桌面档（正文字号 13、不随系统缩放），iPhone/iPad 为移动档。
enum Ds {
    static let isDesktop: Bool = {
        #if os(macOS)
        return true
        #else
        return false
        #endif
    }()

    /// 设计字号（§2）：桌面 15/13/11，移动 17/17/13；语义样式保证动态字体缩放。
    static func sectionTitle() -> Font {
        #if os(macOS)
        .system(size: 15, weight: .semibold)
        #else
        .body.weight(.semibold)
        #endif
    }

    static func body() -> Font {
        #if os(macOS)
        .system(size: 13)
        #else
        .body
        #endif
    }

    static func bodyMedium() -> Font {
        #if os(macOS)
        .system(size: 13, weight: .medium)
        #else
        .body.weight(.medium)
        #endif
    }

    /// 辅助级：标签、说明、副标题、徽标、分组头。
    static func aux() -> Font {
        #if os(macOS)
        .system(size: 11)
        #else
        .footnote
        #endif
    }

    static func auxMedium() -> Font {
        #if os(macOS)
        .system(size: 11, weight: .medium)
        #else
        .footnote.weight(.medium)
        #endif
    }

    // MARK: 尺寸（§4 表）

    static var rowHeight: CGFloat { isDesktop ? 44 : 52 }
    static var doubleRowHeight: CGFloat { isDesktop ? 56 : 64 }
    static var buttonHeight: CGFloat { isDesktop ? 32 : 50 }
    static var inlineButtonHeight: CGFloat { isDesktop ? 28 : 32 }
    static var fieldHeight: CGFloat { isDesktop ? 32 : 44 }
    static var segmentHeight: CGFloat { isDesktop ? 28 : 32 }
    static var chipSize: CGFloat { isDesktop ? 28 : 32 }
    static var chipIcon: CGFloat { isDesktop ? 16 : 20 }
    static var avatarSize: CGFloat { isDesktop ? 32 : 36 }
    static var iconButtonSize: CGFloat { isDesktop ? 32 : 44 }
    static var navIcon: CGFloat { isDesktop ? 20 : 24 }
    static var toolbarIcon: CGFloat { 20 }
}
