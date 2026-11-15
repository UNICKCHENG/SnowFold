import Foundation

// 生成结构体带 id 字段但未声明 Identifiable；在此集中补齐（供 sheet(item:) 与 ForEach 使用）。
// 绑定与宿主同模块编译，无需 @retroactive。
extension AssetRecord: Identifiable {}
extension SubscriptionRecord: Identifiable {}
extension HistoryRecord: Identifiable {}
extension HistoryCorrectionRecord: Identifiable {}

import SwiftUI

/// 主题偏好 → SwiftUI 色彩方案（system 跟随系统）。
extension ThemePreference {
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
