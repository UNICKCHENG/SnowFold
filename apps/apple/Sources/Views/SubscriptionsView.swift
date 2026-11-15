import SwiftUI

/// 订阅：统计头（本月待扣焦点）、五个分组、行操作（§9.4 / mobile §4）。
struct SubscriptionsView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.usesWideLayout) private var wideLayout
    @State private var renewFeedback = 0
    @State private var hoveredSubscriptionID: String?
    @FocusState private var focusedAction: String?

    var body: some View {
        Group {
            #if os(macOS)
            if store.ledger.subscriptions.isEmpty {
                MacEmptyStateContainer { emptyState }
            } else {
                pageScrollView
            }
            #else
            pageScrollView
            #endif
        }
        .background(Color.bg)
        .navigationTitle(Copy.subscriptionsTitle)
        .phoneInlineTitle()
        .phoneToolbar { PageToolbarActions() }
        .sensoryFeedback(.success, trigger: store.highlightID)
        .sensoryFeedback(.success, trigger: renewFeedback)
        .accessibilityIdentifier("subscriptions-list")
    }

    private var emptyState: some View {
        EmptyStateView(
            icon: "ic-calendar-repeat", title: Copy.noSubscriptions,
            message: Copy.noSubscriptionsHelp, actionTitle: Copy.newSubscription,
            actionIcon: "ic-plus", action: { router.modal = .newSubscription }
        )
    }

    private var pageScrollView: some View {
        ScrollView {
            if store.ledger.subscriptions.isEmpty {
                emptyState
                .frame(minHeight: 480)
                .padding(.top, 24)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    statsHeader.padding(.bottom, 24)
                    let groups = groupOrder
                    if let pending = groups.first(where: { $0.key == .overdue }), !pending.items.isEmpty {
                        groupSection(pending)
                        Spacer(minLength: 24)
                    }
                    ForEach(groups.filter { $0.key != .overdue && $0.key != .paused && !$0.items.isEmpty },
                            id: \.key) { group in
                        groupSection(group)
                        Spacer(minLength: 24)
                    }
                    if let paused = groups.first(where: { $0.key == .paused }), !paused.items.isEmpty {
                        groupSection(paused)
                    }
                }
                .padding(.horizontal, Ds.isDesktop ? 32 : 16)
                .padding(.top, Ds.isDesktop ? 16 : 8)
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: 统计头（§4.1 / §9.4）

    private var statsHeader: some View {
        let totals = store.overview?.subscriptionTotals ?? []
        let cnyDue = totals.first { $0.currency == "CNY" }?.dueThisMonthCents ?? 0
        let usdDue = totals.first { $0.currency == "USD" }?.dueThisMonthCents ?? 0
        let active = store.ledger.subscriptions.filter { $0.status == "active" }.count
        let paused = store.ledger.subscriptions.count - active
        let due = VStack(alignment: .leading, spacing: 0) {
            Text(Copy.dueThisMonth)
                .font(Ds.bodyMedium())
                .foregroundStyle(Color.text2)
            FocusAmountText(cents: cnyDue, level: .page)
            if usdDue > 0 {
                Text(Copy.moreCurrency(Fmt.money(usdDue, "USD")))
                    .font(Ds.body())
                    .monospacedDigit()
                    .foregroundStyle(Color.text2)
                    .padding(.top, 4)
            }
        }
        return VStack(alignment: .leading, spacing: 0) {
            // 宽屏（桌面/iPad）：三项同行底对齐（§9.4）；窄屏纵向堆叠。
            if (Ds.isDesktop || wideLayout) && !typeSize.isAccessibilitySize {
                HStack(alignment: .bottom, spacing: 40) {
                    due
                    spendingStat(Copy.monthlySpending, cny(totals, \.monthlyCents), usd(totals, \.monthlyCents))
                    spendingStat(Copy.annualSpending, cny(totals, \.annualCents), usd(totals, \.annualCents))
                    Spacer(minLength: 0)
                }
            } else {
                due
                if !typeSize.isAccessibilitySize {
                    HStack(alignment: .top, spacing: 24) {
                        spendingStat(Copy.monthlySpending, cny(totals, \.monthlyCents), usd(totals, \.monthlyCents))
                        spendingStat(Copy.annualSpending, cny(totals, \.annualCents), usd(totals, \.annualCents))
                    }
                    .padding(.top, 16)
                } else {
                    spendingStat(Copy.monthlySpending, cny(totals, \.monthlyCents), usd(totals, \.monthlyCents))
                        .padding(.top, 16)
                    spendingStat(Copy.annualSpending, cny(totals, \.annualCents), usd(totals, \.annualCents))
                        .padding(.top, 12)
                }
            }
            Text(paused > 0
                 ? "\(active) 个在订 · \(paused) 个已暂停"
                 : "\(active) 个在订")
                .font(Ds.aux())
                .monospacedDigit()
                .foregroundStyle(Color.text2)
                .padding(.top, 16)
        }
        .accessibilityIdentifier("subscriptions-stats")
    }

    /// 标签辅助 + info 16（口径说明）；值分区标题级；美元下一行辅助。
    private func spendingStat(_ title: String, _ cny: Int64, _ usd: Int64) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text(title)
                    .font(Ds.aux())
                    .foregroundStyle(Color.text2)
                HelpInfoButton(metricName: title, help: title == Copy.monthlySpending
                               ? Copy.monthlySpendingHelp : Copy.annualSpendingHelp)
            }
            Text(Fmt.money(cny, "CNY"))
                .font(Ds.sectionTitle())
                .monospacedDigit()
                .foregroundStyle(Color.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if usd > 0 {
                Text(Copy.moreCurrency(Fmt.money(usd, "USD")))
                    .font(Ds.aux())
                    .monospacedDigit()
                    .foregroundStyle(Color.text2)
            }
        }
    }

    private func cny(_ totals: [CurrencyTotal], _ pick: (CurrencyTotal) -> Int64) -> Int64 {
        totals.first { $0.currency == "CNY" }.map(pick) ?? 0
    }

    private func usd(_ totals: [CurrencyTotal], _ pick: (CurrencyTotal) -> Int64) -> Int64 {
        totals.first { $0.currency == "USD" }.map(pick) ?? 0
    }

    // MARK: 分组

    private struct GroupBucket {
        let key: SubscriptionGroup
        let name: String
        let items: [SubscriptionRecord]
    }

    private var groupOrder: [GroupBucket] {
        let names: [(SubscriptionGroup, String)] = [
            (.overdue, Copy.groupNames[0]),
            (.thisMonth, Copy.groupNames[1]),
            (.nextMonth, Copy.groupNames[2]),
            (.later, Copy.groupNames[3]),
            (.paused, Copy.groupNames[4]),
        ]
        return names.map { key, name in
            let items = store.ledger.subscriptions
                .filter { store.groups[$0.id] == key }
                .sorted { ($0.nextChargeOn, $0.id) < ($1.nextChargeOn, $1.id) }
            return GroupBucket(key: key, name: name, items: items)
        }
    }

    private func groupSection(_ group: GroupBucket) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(
                title: group.name,
                titleColor: group.key == .overdue ? .neg : .ink,
                count: group.items.count,
                countColor: group.key == .overdue ? .neg : .text2
            ) {
                EmptyView()
            }
            Card {
                VStack(spacing: 0) {
                    ForEach(Array(group.items.enumerated()), id: \.element.id) { index, record in
                        if index > 0 { rowSeparator }
                        subscriptionRow(record, group: group.key)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1).padding(.leading, 16)
    }

    // MARK: 行

    private func subscriptionRow(_ record: SubscriptionRecord, group: SubscriptionGroup) -> some View {
        let pending = group == .overdue
        let active = record.status == "active"
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        let main = layout {
            Button { router.modal = .editSubscription(record) } label: {
                HStack(alignment: .center, spacing: 12) {
                    SubscriptionAvatar(name: record.name, kind: pending ? .danger : (active ? .normal : .muted))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(record.name)
                            .font(Ds.bodyMedium())
                            .foregroundStyle(active ? Color.ink : Color.text2)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(pending && !(Ds.isDesktop || wideLayout) ? overdueSubtitle(record)
                             : "\(Copy.cycleLabel(record.cycle)) · \(Fmt.day(record.nextChargeOn))")
                            .font(Ds.aux())
                            .monospacedDigit()
                            .foregroundStyle(pending && !(Ds.isDesktop || wideLayout) ? Color.neg : Color.text2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("subscription-editor-" + record.name)
            .accessibilityLabel(Copy.a11ySubscription(
                record.name, amount: Fmt.money(record.amountCents, record.currency),
                cycle: Copy.cycleLabel(record.cycle), date: Fmt.day(record.nextChargeOn),
                status: active ? "在订" : "已暂停"
            ))
            if Ds.isDesktop || (wideLayout && !typeSize.isAccessibilitySize) {
                // 宽屏行（§9.4）：徽标独立成列（逾期/今天 neg、{n} 天后 warn），金额右列；
                // 桌面另带悬停动作列，iPad 待确认行右缘是「已续费」。
                chargeBadge(record)
                    .accessibilityHidden(chargeBadge(record).text.isEmpty)
                MoneyText(cents: record.amountCents, currency: record.currency,
                          font: Ds.bodyMedium(), color: active ? .ink : .text2)
                    .frame(minWidth: 120, alignment: .trailing)
                if Ds.isDesktop {
                    HStack {
                        if pending {
                            Button { renew(record) } label: {
                                Text(Copy.markRenewed)
                            }
                            .buttonStyle(SoftButtonStyle())
                            .accessibilityLabel(Copy.markRenewed)
                            .accessibilityIdentifier("renew-" + record.id)
                        } else {
                            desktopActions(record, active: active)
                        }
                    }
                    .frame(width: 112, alignment: .trailing)
                } else if pending {
                    Button { renew(record) } label: {
                        HStack(spacing: 6) {
                            AppIcon(name: "ic-check", size: 14)
                            Text("已续费")
                        }
                    }
                    .buttonStyle(SoftButtonStyle())
                    .frame(minHeight: 44)
                    .accessibilityLabel(Copy.markRenewed)
                    .accessibilityIdentifier("renew-" + record.id)
                }
            } else {
                VStack(alignment: typeSize.isAccessibilitySize ? .leading : .trailing, spacing: 6) {
                    MoneyText(cents: record.amountCents, currency: record.currency,
                              font: Ds.bodyMedium(), color: active ? .ink : .text2)
                    if pending {
                        Button { renew(record) } label: {
                            HStack(spacing: 6) {
                                AppIcon(name: "ic-check", size: 14)
                                Text("已续费")
                            }
                        }
                        .buttonStyle(SoftButtonStyle())
                        .frame(minHeight: Ds.isDesktop ? 28 : 44)
                        .accessibilityLabel(Copy.markRenewed)
                        .accessibilityIdentifier("renew-" + record.id)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(minHeight: Ds.doubleRowHeight)
        .background(Color.cardBg)
        .contextMenu {
            Button { router.modal = .editSubscription(record) } label: { Label(Copy.editAction, image: "ic-info") }
            Button {
                Task { await store.setSubscriptionPaused(id: record.id, paused: active) }
            } label: { Label(active ? Copy.pause : Copy.resume, image: active ? "ic-pause" : "ic-play") }
            Button(role: .destructive) {
                Task { await store.deleteSubscription(id: record.id) }
            } label: { Label(Copy.delete, image: "ic-trash") }
        }
        .disabled(store.isWorking)
        return Group {
            if Ds.isDesktop {
                main.onHover { hovering in
                    if hovering { hoveredSubscriptionID = record.id }
                    else if hoveredSubscriptionID == record.id { hoveredSubscriptionID = nil }
                }
            } else {
                SwipeableRow(specs: mobileSwipeSpecs(record, pending: pending, active: active)) { main }
            }
        }
    }

    /// 待确认行副标题：逾期 {n} 天 · {周期} · {日期}，整行 neg（设计稿如此）。
    private func overdueSubtitle(_ record: SubscriptionRecord) -> String {
        var parts: [String] = []
        if let days = Fmt.daysBetween(Fmt.today, record.nextChargeOn), days < 0 {
            parts.append("逾期 \(-days) 天")
        }
        parts.append(Copy.cycleLabel(record.cycle))
        parts.append(Fmt.day(record.nextChargeOn))
        return parts.joined(separator: " · ")
    }

    /// 行内徽标（§9.4）：今天/逾期 neg、{n} 天后 warn、其余 neutral（无底）。
    private func chargeBadge(_ record: SubscriptionRecord) -> TagBadge {
        let badge = Fmt.chargeBadge(for: record.nextChargeOn, today: Fmt.today)
        let kind: BadgeKind = switch badge.kind {
        case .danger: .danger
        case .accent: .warn
        case .neutral: .neutral
        }
        return TagBadge(text: badge.text, kind: kind)
    }

    /// 移动左滑：待确认行「已续费」（brand）+「删除」（neg）；其余「暂停/恢复」+「删除」。
    private func mobileSwipeSpecs(_ record: SubscriptionRecord, pending: Bool, active: Bool) -> [SwipeActionSpec] {
        var specs: [SwipeActionSpec] = []
        if pending {
            specs.append(SwipeActionSpec(icon: "ic-check", title: Copy.markRenewed, tint: .brand) {
                renew(record)
            })
        } else {
            specs.append(SwipeActionSpec(icon: active ? "ic-pause" : "ic-play",
                                         title: active ? Copy.pause : Copy.resume, tint: .brand) {
                Task { await store.setSubscriptionPaused(id: record.id, paused: active) }
            })
        }
        specs.append(SwipeActionSpec(icon: "ic-trash", title: Copy.delete) {
            Task { await store.deleteSubscription(id: record.id) }
        })
        return specs
    }

    /// 悬停或键盘聚焦时显示动作，隐藏时仍保留列宽与 Tab 入口。
    private func desktopActions(_ record: SubscriptionRecord, active: Bool) -> some View {
        HStack(spacing: 8) {
            Button {
                Task { await store.setSubscriptionPaused(id: record.id, paused: active) }
            } label: {
                AppIcon(name: active ? "ic-pause" : "ic-play", size: 18)
                    .foregroundStyle(Color.text2)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(active ? Copy.pause : Copy.resume)
            .focused($focusedAction, equals: record.id + "-pause")
            .help(active ? Copy.pause : Copy.resume)
            Button {
                Task { await store.deleteSubscription(id: record.id) }
            } label: {
                AppIcon(name: "ic-trash", size: 18)
                    .foregroundStyle(Color.text2)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Copy.delete)
            .focused($focusedAction, equals: record.id + "-delete")
        }
        .opacity(actionsVisible(record.id) ? 1 : 0)
        .allowsHitTesting(actionsVisible(record.id))
    }

    private func actionsVisible(_ id: String) -> Bool {
        hoveredSubscriptionID == id || focusedAction == id + "-pause" || focusedAction == id + "-delete"
    }

    private func renew(_ record: SubscriptionRecord) {
        Task {
            await store.renewSubscription(id: record.id)
            if store.ledger.subscriptions.first(where: { $0.id == record.id })?.nextChargeOn != record.nextChargeOn {
                renewFeedback += 1
            }
        }
    }
}
