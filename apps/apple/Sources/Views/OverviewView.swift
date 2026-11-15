import SwiftUI

/// 总览：净资产（唯一大标题）、三项指标、资产构成、即将扣费。
/// 数据全部来自核心 `overview(on:)`，界面不自算金额。
struct OverviewView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.usesWideLayout) private var wide
    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        Group {
            #if os(macOS)
            if store.ledger.assets.isEmpty {
                MacEmptyStateContainer {
                    VStack(spacing: 0) {
                        if store.keyUnavailable {
                            keyUnavailableCard.padding(.horizontal, 32).padding(.top, 16)
                        }
                        EmptyStateView(
                            icon: "ic-pie-chart", title: Copy.overviewEmptyTitle,
                            message: Copy.overviewEmptyHelp, actionTitle: Copy.addAsset,
                            action: { router.modal = .newAsset(categoryCode: "cash") }
                        )
                    }
                }
            } else {
                pageScrollView
            }
            #else
            pageScrollView
            #endif
        }
        .background(Color.bg)
        .navigationTitle(Copy.overviewTitle)
        .phoneInlineTitle()
        .phoneToolbar { PageToolbarActions() }
    }

    private var pageScrollView: some View {
        ScrollView {
            content
                .padding(.horizontal, Ds.isDesktop ? 32 : 16)
                .padding(.top, Ds.isDesktop ? 16 : 8)
                .padding(.bottom, 24)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
    }

    @ViewBuilder
    private var content: some View {
        if store.ledger.assets.isEmpty {
            VStack(spacing: 0) {
                if store.keyUnavailable { keyUnavailableCard.padding(.bottom, 24) }
                EmptyStateView(
                    icon: "ic-pie-chart",
                    title: Copy.overviewEmptyTitle,
                    message: Copy.overviewEmptyHelp,
                    actionTitle: Copy.addAsset,
                    action: { router.modal = .newAsset(categoryCode: "cash") }
                )
                .frame(maxWidth: .infinity, minHeight: 480)
            }
            .accessibilityIdentifier("overview-empty")
        } else if let overview = store.overview {
            VStack(alignment: .leading, spacing: 0) {
                if store.keyUnavailable { keyUnavailableCard.padding(.bottom, 24) }
                heroSection(overview)
                if wide && !typeSize.isAccessibilitySize {
                    HStack(alignment: .top, spacing: 24) {
                        compositionSection(overview)
                        upcomingSection(overview)
                    }
                    .padding(.top, 32)
                } else {
                    compositionSection(overview).padding(.top, 28)
                    upcomingSection(overview).padding(.top, 28)
                }
            }
        } else if let error = store.overviewError {
            InlineErrorPanel(message: error)
        }
    }

    // MARK: 密钥不可用（§9.7）

    private var keyUnavailableCard: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIcon(name: "ic-key", size: 20)
                .foregroundStyle(Color.warn)
                .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(Copy.keyUnavailableTitle)
                    .font(Ds.bodyMedium())
                    .foregroundStyle(Color.ink)
                Text(Copy.keyUnavailableHelp)
                    .font(Ds.aux())
                    .foregroundStyle(Color.text2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Button(Copy.importRecoveryButton) {
                #if os(macOS)
                router.settingsTab = .seal
                openSettings()
                #else
                router.tab = .settings
                #endif
            }
            .buttonStyle(SecondaryButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.warnSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    // MARK: hero（§9.2.1）

    private func heroSection(_ overview: Overview) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Copy.netAssets)
                .font(Ds.bodyMedium())
                .foregroundStyle(Color.text2)
                .padding(.bottom, 4)
            FocusAmountText(
                cents: overview.netAssetsCents,
                level: .hero,
                color: overview.netAssetsCents < 0 ? .neg : .ink
            )
            .padding(.bottom, 16)
            statRow(overview)
        }
        .accessibilityIdentifier("overview-hero")
    }

    private func statRow(_ overview: Overview) -> some View {
        let ratio = Fmt.debtRatio(totalAssets: overview.totalAssetsCents, totalDebt: overview.totalDebtCents)
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Ds.isDesktop ? 32 : 20))
        return layout {
            heroStat(Copy.totalAssets, Fmt.money(overview.totalAssetsCents, "CNY"), color: .ink)
            heroStat(Copy.totalDebt, Fmt.money(overview.totalDebtCents, "CNY"), color: .neg)
            heroStat(Copy.debtRatio, ratio ?? Copy.emptySummary, color: .ink)
        }
    }

    private func heroStat(_ label: String, _ value: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(Ds.aux())
                .foregroundStyle(Color.text2)
            Text(value)
                .font(Ds.bodyMedium())
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: 资产构成（§9.2.2）

    private func compositionSection(_ overview: Overview) -> some View {
        let assets = overview.categories.filter { !$0.isLiability }
        let total = overview.totalAssetsCents
        return VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: Copy.assetComposition) {
                LinkButton(title: Copy.allDetails) {
                    router.tab = .assets
                }
            }
            Card {
                if wide {
                    // 桌面：五行双行宽松行（最小高 64，左缩进 56）
                    VStack(spacing: 0) {
                        ForEach(Array(assets.enumerated()), id: \.element.code) { index, category in
                            if index > 0 { rowSeparator(56) }
                            desktopCompositionRow(category, total: total)
                                .padding(.vertical, Ds.isDesktop ? 0 : 8)
                        }
                        if let debt = overview.categories.first(where: \.isLiability) {
                            rowSeparator(56)
                            desktopDebtRow(debt).padding(.vertical, Ds.isDesktop ? 0 : 8)
                        }
                    }
                    .padding(.vertical, Ds.isDesktop ? 0 : 8)
                } else {
                    // 移动：8 高构成条 + 单行五条目
                    CompositionStackedBar(categories: assets, total: total)
                        .padding(16)
                        .padding(.bottom, 4)
                    VStack(spacing: 0) {
                        ForEach(Array(assets.enumerated()), id: \.element.code) { index, category in
                            if index > 0 { rowSeparator(60) }
                            mobileCompositionRow(category, total: total)
                                .padding(.vertical, 10)
                        }
                        if let debt = overview.categories.first(where: \.isLiability) {
                            rowSeparator(60)
                            mobileDebtRow(debt).padding(.vertical, 10)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
                }
            }
        }
        .accessibilityIdentifier("overview-composition")
    }

    private func rowSeparator(_ indent: CGFloat) -> some View {
        Rectangle().fill(Color.sep).frame(height: 1).padding(.leading, indent)
    }

    private func desktopCompositionRow(_ category: CategoryTotal, total: Int64) -> some View {
        let percent = total > 0 ? Double(category.amountCents) / Double(total) * 100 : 0
        return Button {
            router.focusAssetCategory = category.code
            router.tab = .assets
        } label: {
            HStack(alignment: .center, spacing: 12) {
                CategoryChip(code: category.code, size: 28)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(Copy.categoryName(category.code))
                            .font(Ds.bodyMedium())
                            .foregroundStyle(Color.ink)
                        Text(Copy.itemCount(Int(category.count)))
                            .font(Ds.aux())
                            .monospacedDigit()
                            .foregroundStyle(Color.text2)
                        Spacer(minLength: 12)
                        MoneyText(cents: category.amountCents, currency: "CNY", font: Ds.bodyMedium())
                    }
                    HStack(alignment: .center, spacing: 8) {
                        PercentBar(percent: percent, color: Color.categoryColor(category.code))
                            .frame(maxWidth: .infinity)
                        Text(Fmt.percent(percent))
                            .font(Ds.aux())
                            .monospacedDigit()
                            .foregroundStyle(Color.text2)
                            .fixedSize()
                            .frame(minWidth: 44, alignment: .trailing)
                    }
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 16)
            .padding(.vertical, Ds.isDesktop ? 8 : 0)
            .frame(minHeight: Ds.isDesktop ? 64 : Ds.doubleRowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func desktopDebtRow(_ debt: CategoryTotal) -> some View {
        HStack(alignment: .center, spacing: 12) {
            CategoryChip(code: debt.code, size: 28)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(Copy.categoryName(debt.code))
                        .font(Ds.bodyMedium())
                        .foregroundStyle(Color.ink)
                    Text(Copy.itemCount(Int(debt.count)))
                        .font(Ds.aux())
                        .monospacedDigit()
                        .foregroundStyle(Color.text2)
                    Spacer(minLength: 12)
                    MoneyText(cents: debt.amountCents, currency: "CNY", font: Ds.bodyMedium(), color: .neg)
                }
                Text("不计入资产构成")
                    .font(Ds.aux())
                    .foregroundStyle(Color.text2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, Ds.isDesktop ? 8 : 0)
        .frame(minHeight: Ds.isDesktop ? 64 : Ds.doubleRowHeight)
    }

    private func mobileCompositionRow(_ category: CategoryTotal, total: Int64) -> some View {
        let percent = total > 0 ? Double(category.amountCents) / Double(total) * 100 : 0
        return Button {
            router.focusAssetCategory = category.code
            router.tab = .assets
        } label: {
            HStack(alignment: .center, spacing: 12) {
                CategoryChip(code: category.code)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Copy.categoryName(category.code))
                        .font(Ds.bodyMedium())
                        .foregroundStyle(Color.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(Copy.itemCount(Int(category.count)))
                        .font(Ds.aux())
                        .monospacedDigit()
                        .foregroundStyle(Color.text2)
                }
                Spacer(minLength: 12)
                VStack(alignment: .trailing, spacing: 2) {
                    MoneyText(cents: category.amountCents, currency: "CNY", font: Ds.bodyMedium())
                    Text(Fmt.percent(percent))
                        .font(Ds.aux())
                        .monospacedDigit()
                        .foregroundStyle(Color.text2)
                }
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func mobileDebtRow(_ debt: CategoryTotal) -> some View {
        HStack(alignment: .center, spacing: 12) {
            CategoryChip(code: debt.code)
            VStack(alignment: .leading, spacing: 2) {
                Text(Copy.categoryName(debt.code))
                    .font(Ds.bodyMedium())
                    .foregroundStyle(Color.ink)
                Text(Copy.itemCount(Int(debt.count)))
                    .font(Ds.aux())
                    .monospacedDigit()
                    .foregroundStyle(Color.text2)
            }
            Spacer(minLength: 12)
            MoneyText(cents: debt.amountCents, currency: "CNY", font: Ds.bodyMedium(), color: .neg)
        }
    }

    // MARK: 即将扣费（§9.2.2 右列 / §2.3）

    private func upcomingSection(_ overview: Overview) -> some View {
        let maxRows = Ds.isDesktop ? (contentHeight >= 840 ? 8 : 5) : 5
        let rows = Array(overview.upcoming.prefix(maxRows))
        let summary = Copy.overviewSummary(
            active: Int(overview.activeSubscriptions),
            overdue: Int(overview.overdueSubscriptions),
            nextDate: overview.nextChargeOn.map { Fmt.day($0) }
        )
        let hasPending = overview.overdueSubscriptions > 0
        return VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: Copy.upcomingCharges) {
                LinkButton(title: Copy.allSubscriptions) {
                    router.tab = .subscriptions
                }
            }
            Card {
                if rows.isEmpty {
                    VStack(spacing: 0) {
                        AppIcon(name: "ic-calendar-repeat", size: 48)
                            .foregroundStyle(Color.brand)
                            .frame(width: 88, height: 88)
                            .background(Color.brandSoft, in: Circle())
                        Text(Copy.noUpcomingCharges)
                            .font(Ds.sectionTitle())
                            .foregroundStyle(Color.ink)
                            .padding(.top, 12)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, subscription in
                            if index > 0 { rowSeparator(Ds.isDesktop ? 60 : 16) }
                            upcomingRow(subscription)
                        }
                        .padding(.top, 8)
                        Rectangle().fill(Color.sep).frame(height: 1).padding(.horizontal, 16)
                        Text(summary)
                            .font(Ds.aux())
                            .monospacedDigit()
                            .foregroundStyle(hasPending ? Color.neg : Color.text2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                    }
                }
            }
        }
        .accessibilityIdentifier("overview-upcoming")
    }

    /// 双行宽松行：桌面首字头像 32 → 名称 + 周期·日期 → 徽标 → 金额；移动无头像。
    private func upcomingRow(_ subscription: SubscriptionRecord) -> some View {
        let badge = Fmt.chargeBadge(for: subscription.nextChargeOn, today: Fmt.today)
        let kind: BadgeKind = switch badge.kind {
        case .danger: .danger
        case .accent: .warn
        case .neutral: .neutral
        }
        let row = HStack(alignment: .center, spacing: 12) {
            if wide {
                SubscriptionAvatar(name: subscription.name)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(subscription.name)
                    .font(Ds.bodyMedium())
                    .foregroundStyle(Color.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(Copy.cycleLabel(subscription.cycle)) · \(Fmt.day(subscription.nextChargeOn))")
                    .font(Ds.aux())
                    .monospacedDigit()
                    .foregroundStyle(Color.text2)
            }
            Spacer(minLength: 12)
            TagBadge(text: badge.text, kind: kind)
                .accessibilityHidden(badge.text.isEmpty)
            MoneyText(cents: subscription.amountCents, currency: subscription.currency, font: Ds.bodyMedium())
                .frame(minWidth: Ds.isDesktop ? 96 : 84, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(minHeight: Ds.doubleRowHeight)
        return row
    }
}

/// 移动端资产构成顶部堆叠条（8 高，最小显示 0.5%）。
struct CompositionStackedBar: View {
    let categories: [CategoryTotal]
    let total: Int64

    var body: some View {
        GeometryReader { proxy in
            HStack(spacing: 2) {
                ForEach(categories, id: \.code) { category in
                    let ratio = total > 0 ? Double(category.amountCents) / Double(total) : 0
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(Color.categoryColor(category.code))
                        .frame(width: max(proxy.size.width * ratio * 0.995, proxy.size.width * 0.005))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 8)
        .accessibilityHidden(true)
    }
}

/// 总览空态使用的溢出/错误面板。
struct InlineErrorPanel: View {
    let message: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            AppIcon(name: "ic-warning-triangle", size: 16)
            Text(message)
                .font(Ds.body())
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Color.neg)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.negSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
