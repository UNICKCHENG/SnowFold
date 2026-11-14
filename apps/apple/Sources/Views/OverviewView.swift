import SwiftUI

/// 总览：净资产 hero、本月待扣、资产构成、即将扣费。
/// 数据全部来自核心 `overview(on:)`，界面不自算金额。
struct OverviewView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var firstAssetPresented = false

    var body: some View {
        GeometryReader { geometry in
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Copy.overviewSubtitle(Fmt.dateLong(), weekday: Fmt.weekday()))
                    .font(.footnote)
                    .foregroundStyle(Color.ink3)
                if store.overviewError != nil {
                    InlineErrorPanel(message: Copy.assetTotalOverflow)
                }
                if store.ledger.assets.isEmpty && store.ledger.subscriptions.isEmpty {
                    EmptyStateView(icon: "square.stack.3d.up", title: "从第一笔开始", message: Copy.noSubscriptionsHelp,
                                   actionTitle: "记下第一笔资产") { firstAssetPresented = true }
                } else if let overview = store.overview {
                    heroSection(overview)
                    if sizeClass == .regular && !typeSize.isAccessibilitySize {
                        HStack(alignment: .top, spacing: 12) {
                            compositionSection(overview)
                                .frame(maxWidth: .infinity)
                            timelineSection(overview)
                                .frame(maxWidth: .infinity)
                        }
                    } else {
                        compositionSection(overview)
                        if sizeClass == .regular { timelineSection(overview) }
                    }
                    upcomingSection(overview)
                }
            }
            .frame(width: max(0, geometry.size.width - 32), alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        }
        .accessibilityIdentifier("overview-scroll")
        .background(Color.canvas)
        .navigationTitle(Copy.overviewTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.large)
        #endif
        .settingsToolbar()
        .sheet(isPresented: $firstAssetPresented) { AssetEditorSheet(mode: .new(categoryCode: "cash")) }
        .sensoryFeedback(.success, trigger: store.overview?.netAssetsCents)
    }

    // MARK: hero

    @ViewBuilder
    private func heroSection(_ overview: Overview) -> some View {
        let heroCard = heroCard(overview)
        let monthlyCard = monthlyCard(overview)
        if sizeClass == .regular && !typeSize.isAccessibilitySize {
            HStack(alignment: .top, spacing: 12) {
                heroCard
                monthlyCard
                    .frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: 12) {
                heroCard
                monthlyCard
            }
        }
    }

    private func heroCard(_ overview: Overview) -> some View {
        let negative = overview.netAssetsCents < 0
        let ratio: String
        if overview.totalAssetsCents > 0 {
            let value = Double(overview.totalDebtCents) / Double(overview.totalAssetsCents) * 100
            ratio = String(format: "%.1f%%", value)
        } else {
            ratio = Copy.emptySummary
        }
        // docs/mobile-layout.md §2.1：主卡可点进资产标签；三格指标并排，AX 宽度放不下时改纵向。
        return Button {
            router.tab = .assets
        } label: {
            Card {
                VStack(alignment: .leading, spacing: 10) {
                    Text(Copy.netAssets)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.inkMuted)
                    HeroAmountText(cents: overview.netAssetsCents, color: negative ? .negativeBrand : .primary)
                    let layout = typeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                        : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                    layout {
                        heroStat(Copy.totalAssets, Fmt.money(overview.totalAssetsCents, "CNY"), color: .ink, divider: false)
                        heroStat(Copy.totalDebt, Fmt.money(overview.totalDebtCents, "CNY"), color: .negativeBrand, divider: false)
                        heroStat(Copy.debtRatio, ratio, color: .ink, divider: false)
                    }
                    ShareBar(percent: overview.totalAssetsCents > 0
                        ? Double(overview.totalDebtCents) / Double(overview.totalAssetsCents) * 100
                        : 0, tint: .negativeBrand)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    /// sum_item 同款：11.5 标签 + 语义色数值，格间 1px 竖分隔线。
    private func heroStat(_ label: String, _ value: String, color: Color, divider: Bool) -> some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(Color.ink3)
                Text(value)
                    .font(.footnote.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if divider {
                Rectangle().fill(Color.hairline).frame(width: 1, height: 30)
            }
        }
        .padding(.trailing, divider ? 12 : 0)
    }

    private func monthlyCard(_ overview: Overview) -> some View {
        let cny = overview.subscriptionTotals.first { $0.currency == "CNY" }?.dueThisMonthCents ?? 0
        let usd = overview.subscriptionTotals.first { $0.currency == "USD" }?.dueThisMonthCents ?? 0
        let pending = Int(overview.overdueSubscriptions) > 0
        let summary = Copy.overviewSummary(
            active: Int(overview.activeSubscriptions),
            overdue: Int(overview.overdueSubscriptions),
            nextDate: overview.nextChargeOn.map { Fmt.day($0) }
        )
        // docs/mobile-layout.md §2.2：「接下来」卡 = 本月待扣 + 下一笔胶囊 + 30 天笔数时间线 + 摘要行。
        return Card {
            VStack(alignment: .leading, spacing: 10) {
                let headingLayout = typeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                    : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                headingLayout {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Copy.dueThisMonth)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Color.inkMuted)
                        MoneyText(cents: cny, currency: "CNY", font: .title2.weight(.semibold))
                        if usd > 0 {
                            Text("\(Copy.amountAdditional) \(Fmt.money(usd, "USD"))")
                                .font(.footnote)
                                .monospacedDigit()
                                .foregroundStyle(Color.inkMuted)
                        }
                    }
                    if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
                    if let next = store.nextScheduledSubscription {
                        Text("\(Fmt.day(next.nextChargeOn)) · \(next.name)")
                            .font(.caption2.weight(.medium))
                            .monospacedDigit()
                            .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(Color.accentSoft, in: RoundedRectangle(cornerRadius: typeSize.isAccessibilitySize ? 14 : 99))
                            .foregroundStyle(Color.accentBrand)
                    }
                }
                let timeline = UpcomingTimelineChart.dailyEntries(from: overview.upcoming, today: Fmt.today)
                if !timeline.isEmpty && sizeClass != .regular {
                    UpcomingTimelineChart(daily: timeline)
                }
                Button {
                    router.tab = .subscriptions
                } label: {
                    Text(summary + " ›")
                        .font(.footnote)
                        .monospacedDigit()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(
                            pending ? Color.negativeSoft : Color.subtleSurface,
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                        )
                        .foregroundStyle(pending ? Color.negativeBrand : Color.inkMuted)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: 资产构成

    private func compositionSection(_ overview: Overview) -> some View {
        // docs/mobile-layout.md §2.3：环形图（不含负债、中心不放金额）+ 右侧图例；负债单独一行。
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: Copy.assetComposition) {
                Button(Copy.allDetails) {
                    router.tab = .assets
                }
                .font(.footnote)
            }
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .center, spacing: 16) {
                            CompositionDonutChart(categories: overview.categories, totalAssetsCents: overview.totalAssetsCents)
                            compositionLegend(overview).frame(minWidth: 160)
                        }
                        VStack(alignment: .leading, spacing: 14) {
                            CompositionDonutChart(categories: overview.categories, totalAssetsCents: overview.totalAssetsCents)
                                .frame(maxWidth: .infinity)
                            compositionLegend(overview)
                        }
                    }
                    if let debt = overview.categories.first(where: { $0.code == "debt" }) {
                        Divider()
                        HStack(spacing: 8) {
                            CategoryChip(code: "debt", size: 24)
                            Text(Copy.categoryName("debt"))
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(Color.inkMuted)
                            Spacer(minLength: 8)
                            Text("−" + Fmt.money(debt.amountCents, "CNY"))
                                .font(.footnote.weight(.semibold)).monospacedDigit()
                                .foregroundStyle(Color.negativeBrand)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
    }

    private func timelineSection(_ overview: Overview) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "30 天扣费安排") {}
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    UpcomingTimelineChart(
                        daily: UpcomingTimelineChart.dailyEntries(from: overview.upcoming, today: Fmt.today), height: 180
                    )
                    Text("显示未来 30 天内及逾期待确认的前 5 笔；柱高表示笔数，金额见下方明细。")
                        .font(.caption).foregroundStyle(Color.inkMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func compositionLegend(_ overview: Overview) -> some View {
        let legend = donutLegend(overview)
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(legend.indices, id: \.self) { index in
                legendRow(legend[index])
                if index < legend.count - 1 { Divider().padding(.vertical, 6) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func donutLegend(_ overview: Overview) -> [(code: String, percent: Double, amount: Int64)] {
        let chart = CompositionDonutChart(
            categories: overview.categories,
            totalAssetsCents: overview.totalAssetsCents
        )
        return chart.legend
    }

    private func legendRow(_ item: (code: String, percent: Double, amount: Int64)) -> some View {
        Button {
            router.focusAssetCategory = item.code
            router.tab = .assets
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(CategoryStyle.tint(item.code).glyph)
                    .frame(width: 8, height: 8)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(Copy.categoryName(item.code))
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(Color.ink)
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        Text(String(format: "%.0f%%", item.percent))
                            .font(.caption)
                            .monospacedDigit()
                            .foregroundStyle(Color.inkMuted)
                    }
                    Text(Fmt.money(item.amount, "CNY"))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(Color.ink3)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(Copy.categoryName(item.code))，\(String(format: "%.0f%%", item.percent))，\(Fmt.money(item.amount, "CNY"))")
    }

    // MARK: 即将扣费

    @ViewBuilder
    private func upcomingSection(_ overview: Overview) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: Copy.upcomingCharges) {
                Button(Copy.allSubscriptions) {
                    router.tab = .subscriptions
                }
                .font(.footnote)
            }
            Card {
                if overview.upcoming.isEmpty {
                    Text(Copy.noUpcomingCharges)
                        .font(.subheadline)
                        .foregroundStyle(Color.ink3)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 18)
                } else {
                    VStack(spacing: 0) {
                        ForEach(overview.upcoming.indices, id: \.self) { index in
                            let subscription = overview.upcoming[index]
                            if index > 0 { Divider().padding(.leading, 16) }
                            upcomingRow(subscription)
                        }
                    }
                }
            }
        }
    }

    private func upcomingRow(_ subscription: SubscriptionRecord) -> some View {
        let badge = Fmt.chargeBadge(for: subscription.nextChargeOn, today: Fmt.today)
        return Button {
            router.tab = .subscriptions
        } label: {
            HStack(spacing: 12) {
                Text(Fmt.day(subscription.nextChargeOn))
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(Color.inkMuted)
                    .frame(width: 74, alignment: .leading)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(subscription.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 8)
                TagBadge(text: badge.text, kind: badge.kind)
                MoneyText(cents: subscription.amountCents, currency: subscription.currency, font: .subheadline.weight(.medium))
                    .frame(minWidth: 84, alignment: .trailing)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(subscription.name)，\(Fmt.day(subscription.nextChargeOn))，\(badge.text)，\(Fmt.money(subscription.amountCents, subscription.currency))")
    }
}

// MARK: - 分类卡

struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        // docs/mobile-layout.md §1：桌面端卡片色与圆角，不加描边。
        .background(Color.cardSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
