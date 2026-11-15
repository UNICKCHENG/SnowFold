import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// 设计令牌。苹果端配色用系统语义色（2026-11-15 决策）：中性色、语义色、
/// 品牌色与分类色全部映射到系统色，跟随深浅外观、「增强对比度」与玻璃材质透光；
/// 映射只写在本文件，各视图继续用令牌名。玻璃表面上的文字与图标用层级样式（.primary/.secondary）。
extension Color {
    // MARK: 品牌色 —— 系统强调色（iOS/iPadOS = systemBlue，macOS 跟随用户在系统设置选的强调色）

    /// 主按钮、选中态、分段控件、开关、链接、焦点环
    static let brand = Color.accentColor
    /// 选中行与导航选中项的底、空状态圆底、浅色按钮底
    static let brandSoft = brand.opacity(0.14)
    /// 品牌色实底上的文字与图标
    static let onBrand = Color.white

    // MARK: 中性与语义色

    #if canImport(UIKit)
    /// 窗口背景
    static let bg = Color(uiColor: .systemGroupedBackground)
    /// 卡片、弹层（sheet 内系统自动抬升）
    static let cardBg = Color(uiColor: .secondarySystemGroupedBackground)
    /// 分隔线、输入框描边、禁用底
    static let sep = Color(uiColor: .separator)
    /// 主文字
    static let ink = Color(uiColor: .label)
    /// 次要文字、未选中图标
    static let text2 = Color(uiColor: .secondaryLabel)
    /// 负债、负值、逾期、错误、危险按钮
    static let neg = Color(uiColor: .systemRed)
    /// 提醒：7 天内扣费、密钥不可用
    static let warn = Color(uiColor: .systemOrange)
    /// 成功：续费、同步完成
    static let pos = Color(uiColor: .systemGreen)
    /// 分类识别色的系统映射
    private static let catCash = Color(uiColor: .systemOrange)
    private static let catFixed = Color(uiColor: .systemGreen)
    private static let catInvestment = Color(uiColor: .systemIndigo)
    private static let catReceivable = Color(uiColor: .systemTeal)
    #elseif canImport(AppKit)
    /// 窗口背景
    static let bg = Color(nsColor: .windowBackgroundColor)
    /// 卡片、弹层（系统设置分组表单的控制面底色：浅色白卡，深色随系统控制面抬升）
    static let cardBg = Color(nsColor: .controlBackgroundColor)
    /// 分隔线、输入框描边、禁用底
    static let sep = Color(nsColor: .separatorColor)
    /// 主文字
    static let ink = Color(nsColor: .labelColor)
    /// 次要文字、未选中图标
    static let text2 = Color(nsColor: .secondaryLabelColor)
    /// 负债、负值、逾期、错误、危险按钮
    static let neg = Color(nsColor: .systemRed)
    /// 提醒：7 天内扣费、密钥不可用
    static let warn = Color(nsColor: .systemOrange)
    /// 成功：续费、同步完成
    static let pos = Color(nsColor: .systemGreen)
    /// 分类识别色的系统映射
    private static let catCash = Color(nsColor: .systemOrange)
    private static let catFixed = Color(nsColor: .systemGreen)
    private static let catInvestment = Color(nsColor: .systemIndigo)
    private static let catReceivable = Color(nsColor: .systemTeal)
    #endif

    /// 软色：对应语义色的 14% 透明度填充，随系统色深浅变化
    static let negSoft = neg.opacity(0.14)
    static let warnSoft = warn.opacity(0.14)

    /// 分类识别色（图标块与构成条专用，不用于文字）
    static func categoryColor(_ code: String) -> Color {
        switch code {
        case "fixed": catFixed
        case "investment": catInvestment
        case "receivable": catReceivable
        case "debt": neg
        default: catCash // cash
        }
    }

    /// 分类图标块底色（cat-*-soft）
    static func categorySoft(_ code: String) -> Color {
        switch code {
        case "fixed": catFixed.opacity(0.14)
        case "investment": catInvestment.opacity(0.14)
        case "receivable": catReceivable.opacity(0.14)
        case "debt": negSoft
        default: catCash.opacity(0.14) // cash
        }
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
