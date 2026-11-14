import Charts
import SwiftUI

// 三张图表的规格见 docs/design.md §7：构成环形图、年化条形图、未来扣费时间线。
// 金额一律来自核心（overview / subscription_annual），图表只做呈现。

// MARK: - 资产构成环形图

/// 五分类构成（docs/mobile-layout.md §2.3）：直径 112，只画资产不含负债，
/// 中心只放「N 类」不放金额；负债在卡片底部单独一行。
struct CompositionDonutChart: View {
    let categories: [CategoryTotal]
    let totalAssetsCents: Int64

    /// 图例数据：色点、分类名、占比、金额（第二行）。
    var legend: [(code: String, percent: Double, amount: Int64)] {
        let positive = categories.filter { CategoryStyle.rank($0.code) < 4 }
        let base = totalAssetsCents
        return positive.map { category in
            let share = base > 0 ? Double(max(category.amountCents, 0)) / Double(base) * 100 : 0
            return (category.code, share, category.amountCents)
        }
    }

    private var assetOnly: [CategoryTotal] {
        categories.filter { CategoryStyle.rank($0.code) < 4 && $0.amountCents > 0 }
    }

    var body: some View {
        if assetOnly.isEmpty {
            Text(Copy.compositionEmpty)
                .font(.footnote)
                .foregroundStyle(Color.ink3)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 18)
        } else {
            Chart(assetOnly, id: \.code) { category in
                SectorMark(
                    angle: .value("金额", category.amountCents),
                    innerRadius: .ratio(0.62),
                    angularInset: 2
                )
                .cornerRadius(4)
                .foregroundStyle(CategoryStyle.tint(category.code).glyph)
            }
            .chartLegend(.hidden)
            .frame(width: 112, height: 112)
            .overlay {
                Text("\(assetOnly.count) 类")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.inkMuted)
            }
            .accessibilityLabel(
                Copy.assetComposition
                    + "，" + assetOnly.map { "\(Copy.categoryName($0.code)) \(Fmt.money($0.amountCents, "CNY"))" }
                    .joined(separator: "，")
            )
        }
    }
}

// MARK: - 年化支出条形图

struct AnnualBarItem: Identifiable {
    let id: String
    let name: String
    let annualCents: Int64
}

/// 水平条形：每条 = 一个在订订阅年化；CNY、USD 各一张（不换汇）。
struct AnnualBarsChart: View {
    let currency: String
    let items: [AnnualBarItem]
    private let barColor: Color

    init(currency: String, items: [AnnualBarItem]) {
        self.currency = currency
        self.items = items
        self.barColor = currency == "USD" ? .markB : .accentBrand
    }

    var body: some View {
        if items.isEmpty {
            Text(Copy.noCharges)
                .font(.footnote)
                .foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 12)
        } else {
            // Swift Charts 的分类 Y 轴在 List 内与固定宽条形错位（三次布局验证），
            // 此图改用同形态的自定义行条形；环形图与时间线仍为 Swift Charts。
            let maxAnnual = max(items.map(\.annualCents).max() ?? 1, 1)
            VStack(alignment: .leading, spacing: 14) {
                ForEach(items) { item in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.name)
                            .font(.caption).foregroundStyle(Color.inkMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        MoneyText(cents: item.annualCents, currency: currency, font: .caption.weight(.medium), color: .ink)
                        GeometryReader { proxy in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(barColor)
                                .frame(width: max(proxy.size.width * CGFloat(Double(item.annualCents) / Double(maxAnnual)), 2))
                        }
                        .frame(height: 6)
                        .accessibilityHidden(true)
                    }
                }
            }
            .padding(.vertical, 4)
            .accessibilityLabel(accessibilitySummary)
        }
    }

    private var accessibilitySummary: String {
        let name = currency == "USD" ? "美元" : "人民币"
        let parts = items.map { "\($0.name) \(Fmt.money($0.annualCents, currency))" }
        return name + "年化支出，" + parts.joined(separator: "，")
    }
}

// MARK: - 未来扣费时间线

struct UpcomingTimelineChart: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    /// (自然日, 当天笔数)。柱高只表示笔数，金额一律看下方明细行（核心无日汇总接口）。
    let daily: [(day: Date, count: Int, kind: Fmt.BadgeKind)]
    var height: CGFloat = 56

    /// 仅用 overview.upcoming：逾期钳到今天，柱高表示笔数，绝不相加金额。
    static func dailyEntries(from upcoming: [SubscriptionRecord], today: String) -> [(day: Date, count: Int, kind: Fmt.BadgeKind)] {
        guard let todayDate = Fmt.date(fromDay: today) else { return [] }
        var buckets: [Date: (count: Int, overdue: Bool)] = [:]
        for record in upcoming {
            guard let scheduled = Fmt.date(fromDay: record.nextChargeOn) else { continue }
            let day = max(todayDate, scheduled)
            let previous = buckets[day] ?? (0, false)
            buckets[day] = (previous.count + 1, previous.overdue || scheduled < todayDate)
        }
        return buckets.sorted { $0.key < $1.key }.map { day, entry in
            (day, entry.count, entry.overdue ? .danger : (day == todayDate ? .accent : .neutral))
        }
    }

    var body: some View {
        if daily.isEmpty {
            Text(Copy.noUpcomingCharges)
                .font(.footnote)
                .foregroundStyle(Color.ink3)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 12)
        } else {
            GeometryReader { geometry in
                // 最小 Mac 分栏不足 280pt 时用日号，完整日期仍在明细中显示。
                let compactAxis = typeSize.isAccessibilitySize || geometry.size.width < 280
                Chart(daily, id: \.day) { entry in
                    BarMark(
                        x: .value("日期", entry.day, unit: .day),
                        y: .value("笔数", entry.count),
                        width: .fixed(8)
                    )
                    .cornerRadius(2)
                    .foregroundStyle(color(for: entry.kind))
                }
                .chartXAxis {
                    AxisMarks(position: .bottom, values: .automatic(desiredCount: compactAxis ? 2 : 3)) { _ in
                        AxisValueLabel(format: compactAxis ? .dateTime.day() : .dateTime.month().day(), centered: false)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(Color.ink3)
                    }
                }
                .chartYAxis(.hidden)
            }
            .frame(height: typeSize.isAccessibilitySize ? max(height, 180) : height)
            .accessibilityLabel("未来 30 天扣费时间线，共 \(daily.count) 天有安排")
        }
    }

    private func color(for kind: Fmt.BadgeKind) -> Color {
        switch kind {
        case .danger: return .negativeBrand
        case .accent: return .accentBrand
        case .neutral: return .accentBrand
        }
    }
}
