import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// 语义色（浅/深成对），与 GPUI 桌面端 apps/desktop/src/app/components.rs 的
/// Palette 完全一致——桌面端是设计基准，苹果端逐值对齐。
extension Color {
    /// 背景（画布）
    static let canvas = Color(light: 0xF1F1EE, dark: 0x0B0B0A)
    /// 卡片
    static let cardSurface = Color(light: 0xFFFFFF, dark: 0x171716)
    /// 弱卡片/输入底
    static let subtleSurface = Color(light: 0xF7F7F4, dark: 0x1E1E1C)
    /// 悬停底
    static let hoverSurface = Color(light: 0xFBFBF9, dark: 0x1C1C1A)
    /// 主文字
    static let ink = Color(light: 0x191917, dark: 0xF3F3EE)
    /// 次级文字
    static let inkMuted = Color(light: 0x6F6F68, dark: 0x9B9B93)
    /// 三级文字（标签、说明）
    static let ink3 = Color(light: 0x9D9D95, dark: 0x6C6C65)
    /// 强调
    static let accentBrand = Color(light: 0x2B59E0, dark: 0x8098FF)
    /// 收入/资产正向
    static let positiveBrand = Color(light: 0x1C7A50, dark: 0x58C795)
    /// 支出/负债负向
    static let negativeBrand = Color(light: 0xAE4A3C, dark: 0xE58A7A)
    /// 分隔线
    static let hairline = Color(light: 0x000000, alpha: 0x17, dark: 0xFFFFFF, darkAlpha: 0x15)
    /// 强分隔线（输入框描边）
    static let hairlineStrong = Color(light: 0x000000, alpha: 0x29, dark: 0xFFFFFF, darkAlpha: 0x29)
    /// 强调弱底
    static let accentSoft = Color(light: 0x2B59E0, alpha: 0x14, dark: 0x8098FF, darkAlpha: 0x26)
    /// 正向弱底
    static let positiveSoft = Color(light: 0x1C7A50, alpha: 0x14, dark: 0x58C795, darkAlpha: 0x26)
    /// 负向弱底
    static let negativeSoft = Color(light: 0xAE4A3C, alpha: 0x14, dark: 0xE58A7A, darkAlpha: 0x24)
    /// 图表第二序列（美元）
    static let markB = Color(light: 0x4A4A3F, dark: 0xC9C9BD)
    /// 标签栏底
    static let tabsBg = Color(light: 0x191917, alpha: 0x0E, dark: 0xFFFFFF, darkAlpha: 0x0F)
    /// 激活标签底
    static let activeTab = Color(light: 0xFFFFFF, dark: 0x2B2B28)

    init(light: UInt32, alpha: UInt8 = 0xFF, dark: UInt32, darkAlpha: UInt8 = 0xFF) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { traits in
            let isDark = traits.userInterfaceStyle == .dark
            let value = isDark ? dark : light
            let a = isDark ? darkAlpha : alpha
            return UIColor(red: CGFloat((value >> 16) & 0xFF) / 255,
                           green: CGFloat((value >> 8) & 0xFF) / 255,
                           blue: CGFloat(value & 0xFF) / 255,
                           alpha: CGFloat(a) / 255)
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .vibrantDark, .aqua, .vibrantLight]) != .aqua
                && appearance.bestMatch(from: [.darkAqua, .vibrantDark, .aqua, .vibrantLight]) != .vibrantLight
            let value = isDark ? dark : light
            let a = isDark ? darkAlpha : alpha
            return NSColor(srgbRed: CGFloat((value >> 16) & 0xFF) / 255,
                           green: CGFloat((value >> 8) & 0xFF) / 255,
                           blue: CGFloat(value & 0xFF) / 255,
                           alpha: CGFloat(a) / 255)
        }))
        #else
        self.init(.white)
        #endif
    }
}

extension ShapeStyle where Self == Color {
    static var negativeBrand: Color { .negativeBrand }
    static var accentBrand: Color { .accentBrand }
    static var positiveBrand: Color { .positiveBrand }
}

/// 五分类外观（SF Symbol + 桌面端同款双色 chip）。
/// 桌面端 category_tint：cash→accent、fixed→棕、investment→正向绿、receivable→灰、debt→负向红。
enum CategoryStyle {
    static let order = ["cash", "fixed", "investment", "receivable", "debt"]

    static func name(_ code: String) -> String {
        switch code {
        case "fixed": "固定资产"
        case "investment": "投资理财"
        case "receivable": "应收款"
        case "debt": "负债"
        default: "流动资金"
        }
    }

    static func icon(_ code: String) -> String {
        switch code {
        case "fixed": "house.fill"
        case "investment": "chart.line.uptrend.xyaxis"
        case "receivable": "banknote.fill"
        case "debt": "creditcard.fill"
        default: "wallet.pass.fill"
        }
    }

    /// (chip 底色, 图标色)
    static func tint(_ code: String) -> (chip: Color, glyph: Color) {
        switch code {
        case "fixed":
            // 桌面端浅深同值：bg 0x8c6d3f1a / glyph 0x8c6d3f
            (Color(light: 0x8C6D3F, alpha: 0x1A, dark: 0x8C6D3F, darkAlpha: 0x1A),
             Color(light: 0x8C6D3F, dark: 0x8C6D3F))
        case "investment":
            (.positiveSoft, .positiveBrand)
        case "receivable":
            (Color(light: 0x6D6D68, alpha: 0x1A, dark: 0x6D6D68, darkAlpha: 0x1A), .inkMuted)
        case "debt":
            (Color(light: 0xAE4A3C, alpha: 0x17, dark: 0xE58A7A, darkAlpha: 0x24), .negativeBrand)
        default:
            (Color(light: 0x2B59E0, alpha: 0x17, dark: 0x8098FF, darkAlpha: 0x24), .accentBrand)
        }
    }

    /// 分类排序（桌面端 CATEGORIES 顺序）
    static func rank(_ code: String) -> Int {
        order.firstIndex(of: code) ?? 9
    }
}
