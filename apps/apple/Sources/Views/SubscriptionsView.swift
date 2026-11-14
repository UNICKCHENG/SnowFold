import SwiftUI

/// 订阅：统计卡（本月待扣/月均/年化/下一笔）、五个分组、行操作。
struct SubscriptionsView: View {
    @Environment(SnowFoldStore.self) private var store
    var onEdit: ((SubscriptionRecord) -> Void)? = nil
    var onNew: (() -> Void)? = nil
    @State private var editorTarget: EditorTarget?
    @State private var renewFeedback = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    enum EditorTarget: Identifiable {
        case new
        case edit(SubscriptionRecord)
        var id: String {
            switch self {
            case .new: return "new"
            case .edit(let record): return record.id
            }
        }
    }

    var body: some View {
        List {
            if store.ledger.subscriptions.isEmpty {
                Section {
                    EmptyStateView(
                        icon: "calendar.badge.clock",
                        title: Copy.noSubscriptions,
                        message: Copy.noSubscriptionsHelp,
                        actionTitle: Copy.newSubscription
                    ) {
                        openNew()
                    }
                    .listRowBackground(Color.clear)
                }
            } else {
                statsSection
                if let pending = groupOrder.first(where: { $0.key == .overdue }), !pending.items.isEmpty {
                    groupSection(pending)
                }
                annualChartSection
                ForEach(groupOrder.filter { $0.key != .overdue && $0.key != .paused }, id: \.key) { group in
                    if !group.items.isEmpty {
                        groupSection(group)
                    }
                }
                if let paused = groupOrder.first(where: { $0.key == .paused }), !paused.items.isEmpty {
                    groupSection(paused)
                }
            }
        }
        .accessibilityIdentifier("subscriptions-list")
        .animation(.default, value: store.ledger)
        .sensoryFeedback(.success, trigger: store.highlightID)
        .sensoryFeedback(.success, trigger: renewFeedback)
        .sensoryFeedback(.warning, trigger: store.undoState)
        .scrollContentBackground(.hidden)
        .background(Color.canvas)
        .navigationTitle(Copy.subscriptionsTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.large)
        #endif
        .settingsToolbar {
            Button { openNew() } label: { Label(Copy.newSubscription, systemImage: "plus").accessibilityIdentifier("new-subscription") }
        }
        .sheet(item: $editorTarget) { target in
            switch target {
            case .new:
                SubscriptionEditorSheet(mode: .new)
            case .edit(let record):
                SubscriptionEditorSheet(mode: .edit(record))
            }
        }
    }

    private func openNew() {
        if let onNew { onNew() } else { editorTarget = .new }
    }

    private func openEditor(_ record: SubscriptionRecord) {
        if let onEdit { onEdit(record) } else { editorTarget = .edit(record) }
    }

    // MARK: 统计

    private var statsSection: some View {
        Section {
            let totals = store.overview?.subscriptionTotals ?? []
            let next = nextCharge
            LazyVGrid(
                columns: typeSize.isAccessibilitySize
                    ? [GridItem(.flexible())]
                    : [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 10
            ) {
                moneyStat(
                    title: Copy.dueThisMonth,
                    help: Copy.dueThisMonthHelp,
                    cny: cny(totals, \.dueThisMonthCents),
                    usd: usd(totals, \.dueThisMonthCents)
                )
                moneyStat(
                    title: Copy.monthlySpending,
                    help: Copy.monthlySpendingHelp,
                    cny: cny(totals, \.monthlyCents),
                    usd: usd(totals, \.monthlyCents)
                )
                moneyStat(
                    title: Copy.annualSpending,
                    help: Copy.annualSpendingHelp,
                    cny: cny(totals, \.annualCents),
                    usd: usd(totals, \.annualCents)
                )
                StatTile(title: Copy.nextCharge, help: Copy.nextCharge + " · " + Copy.dueThisMonthHelp) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(next.map { Fmt.day($0.nextChargeOn) } ?? Copy.emptySummary)
                            .font(.headline)
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        Text(next?.name ?? Copy.noCharges)
                            .font(.caption)
                            .foregroundStyle(Color.inkMuted)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func cny(_ totals: [CurrencyTotal], _ pick: (CurrencyTotal) -> Int64) -> Int64 {
        totals.first { $0.currency == "CNY" }.map(pick) ?? 0
    }

    private func usd(_ totals: [CurrencyTotal], _ pick: (CurrencyTotal) -> Int64) -> Int64 {
        totals.first { $0.currency == "USD" }.map(pick) ?? 0
    }

    private func moneyStat(title: String, help: String, cny: Int64, usd: Int64) -> some View {
        StatTile(title: title, help: help) {
            VStack(alignment: .leading, spacing: 2) {
                MoneyText(cents: cny, currency: "CNY", font: .headline)
                MoneyText(cents: usd, currency: "USD", font: .caption.weight(.medium), color: .ink)
            }
        }
    }

    /// 下一个扣费：在订、未逾期、最早的自然日（与桌面一致）。
    private var nextCharge: SubscriptionRecord? { store.nextScheduledSubscription }

    // MARK: 年化条形图

    /// 年化金额来自核心 subscription_annual（store.annuals），界面不自算。
    private var annualChartSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 14) {
                let active = store.ledger.subscriptions.filter { $0.status == "active" }
                let cnyItems = barItems(active, currency: "CNY")
                let usdItems = barItems(active, currency: "USD")
                if !cnyItems.isEmpty {
                    chartCard(currency: "CNY", items: cnyItems)
                }
                if !usdItems.isEmpty {
                    chartCard(currency: "USD", items: usdItems)
                }
                if cnyItems.isEmpty && usdItems.isEmpty {
                    Text(Copy.noCharges)
                        .font(.footnote)
                        .foregroundStyle(Color.ink3)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 10)
                }
            }
            .padding(.vertical, 6)
        }
    }

    private func barItems(_ records: [SubscriptionRecord], currency: String) -> [AnnualBarItem] {
        records
            .filter { $0.currency == currency }
            .map { AnnualBarItem(id: $0.id, name: $0.name, annualCents: store.annuals[$0.id] ?? 0) }
            .sorted { $0.annualCents > $1.annualCents }
            .prefix(8)
            .map { $0 }
    }

    @ViewBuilder
    private func chartCard(currency: String, items: [AnnualBarItem]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(currency == "USD" ? Copy.annualSpending + " · USD" : Copy.annualSpending + " · CNY")
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.inkMuted)
            AnnualBarsChart(currency: currency, items: items)
        }
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
        Section {
            ForEach(group.items, id: \.id) { record in
                subscriptionRow(record, group: group.key)
                    .listRowBackground(Color.cardSurface)
                    .listRowSeparatorTint(Color.hairline)
            }
        } header: {
            SectionHeader(title: group.name, count: group.items.count) {}
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 4, trailing: 16))
        }
    }

    private func subscriptionRow(_ record: SubscriptionRecord, group: SubscriptionGroup) -> some View {
        let pending = group == .overdue
        let active = record.status == "active"
        return VStack(alignment: .leading, spacing: 10) {
            Button {
                openEditor(record)
            } label: {
                HStack(alignment: .top, spacing: 10) {
                    avatar(for: record, overdue: pending)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(record.name)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(Color.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("\(Fmt.day(record.nextChargeOn)) · \(Copy.cycleLabel(record.cycle))")
                            .font(.caption)
                            .monospacedDigit()
                            .foregroundStyle(Color.inkMuted)
                        if pending {
                            Text(Fmt.overdueCompact(record.nextChargeOn, today: Fmt.today))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(Color.negativeBrand)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("subscription-editor-" + record.name)
            .accessibilityLabel(Copy.a11ySubscription(
                record.name, amount: Fmt.money(record.amountCents, record.currency),
                cycle: Copy.cycleLabel(record.cycle), date: Fmt.day(record.nextChargeOn),
                status: active ? "在订" : "已暂停"
            ))
            adaptiveAmounts(record, pending: pending)
        }
        .padding(.vertical, 7)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                Task { await store.deleteSubscription(id: record.id) }
            } label: { Label("删除", systemImage: "trash") }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button {
                Task { await store.setSubscriptionPaused(id: record.id, paused: active) }
            } label: {
                Label(active ? Copy.pause : Copy.resume, systemImage: active ? "pause.circle" : "play.circle")
            }
            .tint(.accentBrand)
        }
        .contextMenu {
            Button { openEditor(record) } label: { Label(Copy.editAction, systemImage: "pencil") }
            if active && record.nextChargeOn <= Fmt.today {
                Button { renew(record) } label: { Label(Copy.markRenewed, systemImage: "checkmark.circle.fill") }
            }
            Button {
                Task { await store.setSubscriptionPaused(id: record.id, paused: active) }
            } label: {
                Label(active ? Copy.pause : Copy.resume, systemImage: active ? "pause.circle" : "play.circle")
            }
            Button(role: .destructive) {
                Task { await store.deleteSubscription(id: record.id) }
            } label: { Label("删除", systemImage: "trash") }
        }
        .listRowBackground(store.highlightID == record.id ? Color.accentSoft : Color.cardSurface)
        .disabled(store.isWorking)
    }

    private func adaptiveAmounts(_ record: SubscriptionRecord, pending: Bool) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        return layout {
            MoneyText(cents: record.amountCents, currency: record.currency, font: .subheadline.weight(.semibold))
            if pending {
                if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
                Button { renew(record) } label: {
                    Text(Copy.markRenewed)
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 12)
                        .frame(minHeight: 44)
                        .background(Color.accentSoft, in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentBrand)
                .accessibilityIdentifier("renew-" + record.id)
            }
        }
    }

    private func renew(_ record: SubscriptionRecord) {
        Task {
            await store.renewSubscription(id: record.id)
            if store.ledger.subscriptions.first(where: { $0.id == record.id })?.nextChargeOn != record.nextChargeOn {
                renewFeedback += 1
            }
        }
    }

    private func avatar(for record: SubscriptionRecord, overdue: Bool) -> some View {
        let initial = record.name.first.map { String($0).uppercased() } ?? "?"
        return Text(initial)
            .font(.subheadline.weight(.semibold))
            .frame(width: 34, height: 34)
            .background(
                overdue ? Color.negativeSoft : Color.tabsBg,
                in: RoundedRectangle(cornerRadius: 9, style: .continuous)
            )
            .foregroundStyle(overdue ? .negativeBrand : .secondary)
            .accessibilityHidden(true)
    }
}
