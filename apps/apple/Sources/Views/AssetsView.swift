import SwiftUI

// MARK: - 资产页（iPhone 推入形态；iPad/Mac 用 AssetsSplitView）

struct AssetsView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        Group {
            if store.ledger.assets.isEmpty {
                EmptyStateView(icon: "ic-wallet", title: Copy.assetsEmptyTitle, message: Copy.assetsEmptyHelp,
                               actionTitle: Copy.addAsset, actionIdentifier: "add-empty-cash") {
                    router.modal = .newAsset(categoryCode: "cash")
                }
                .background(Color.bg)
            } else {
                AssetsListView(selectedAssetID: .constant(nil as String?), scrollTo: .constant(nil as String?))
            }
        }
        .navigationTitle(Copy.assetsTitle)
        .phoneInlineTitle()
        .phoneToolbar { PageToolbarActions() }
    }
}

// MARK: - 资产列表（移动整页 / 桌面左栏，§9.3 左栏）

struct AssetsListView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Binding var selectedAssetID: String?
    @Binding var scrollTo: String?
    @Environment(\.dynamicTypeSize) private var typeSize
    /// 分栏形态：点行选中右栏；移动整页：推入详情。
    var selectionMode = false
    var showsSummary = true
    var compactEmpty = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if showsSummary { summary.padding(.bottom, 20) }
                    let categories = Copy.categoryOrder
                    ForEach(Array(categories.enumerated()), id: \.element) { index, code in
                        if index > 0 { Spacer(minLength: 24) }
                        categorySection(code)
                    }
                }
                .padding(.horizontal, Ds.isDesktop || !showsSummary ? 0 : 16)
                .padding(.top, showsSummary ? 0 : 8)
                .padding(.bottom, 24)
            }
            .onChange(of: router.focusAssetCategory) { _, code in
                guard let code else { return }
                withAnimation { proxy.scrollTo(code, anchor: .top) }
                router.focusAssetCategory = nil
            }
            .onChange(of: scrollTo) { _, code in
                guard let code else { return }
                withAnimation { proxy.scrollTo(code, anchor: .top) }
                scrollTo = nil
            }
        }
        .background(Color.bg)
        .accessibilityIdentifier("assets-list")
    }

    // MARK: 汇总（移动页焦点，§3.1）

    private var summary: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Copy.totalAssets)
                .font(Ds.bodyMedium())
                .foregroundStyle(Color.text2)
            if let total = store.overview?.totalAssetsCents {
                FocusAmountText(cents: total, level: .page)
            } else {
                InlineErrorPanel(message: Copy.assetTotalOverflow)
            }
            HStack(alignment: .top, spacing: 20) {
                summaryStat(Copy.totalDebt, Fmt.money(store.overview?.totalDebtCents ?? 0, "CNY"), color: .neg)
                summaryStat(Copy.netAssets, Fmt.money(store.overview?.netAssetsCents ?? 0, "CNY"), color: .ink)
            }
            .padding(.top, 8)
        }
        .accessibilityIdentifier("assets-summary")
    }

    private func summaryStat(_ label: String, _ value: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(Ds.aux()).foregroundStyle(Color.text2)
            Text(value)
                .font(Ds.bodyMedium())
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
    }

    // MARK: 分类分区

    private func categorySection(_ code: String) -> some View {
        let assets = store.ledger.assets.filter { $0.categoryCode == code }
        let total = store.overview?.categories.first { $0.code == code }?.amountCents
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                CategoryChip(code: code, size: 28)
                Text(Copy.categoryName(code))
                    .font(Ds.sectionTitle())
                    .foregroundStyle(Color.ink)
                Text(Copy.itemCount(assets.count))
                    .font(Ds.aux())
                    .monospacedDigit()
                    .foregroundStyle(Color.text2)
                Spacer(minLength: 12)
                if let total {
                    MoneyText(cents: total, currency: "CNY", font: Ds.bodyMedium(),
                              color: code == "debt" ? .neg : .ink)
                } else {
                    Text(Copy.totalOutOfRange)
                        .font(Ds.aux())
                        .foregroundStyle(Color.neg)
                }
            }
            .id(code)
            Card {
                if assets.isEmpty {
                    compactEmptyRow(code)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(assets.enumerated()), id: \.element.id) { index, asset in
                            if index > 0 { rowSeparator }
                            assetRow(asset)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1).padding(.leading, 16)
    }

    /// 分类空状态的紧凑行：图标块 + 暂无{分类} + 「添加」浅色按钮。
    private func compactEmptyRow(_ code: String) -> some View {
        HStack(spacing: 12) {
            CategoryChip(code: code)
            Text(Copy.categoryEmpty(code))
                .font(Ds.body())
                .foregroundStyle(Color.text2)
            Spacer(minLength: 8)
            Button {
                router.modal = .newAsset(categoryCode: code)
            } label: {
                HStack(spacing: 6) {
                    AppIcon(name: "ic-plus", size: 14)
                    Text(Copy.add)
                }
            }
            .buttonStyle(SoftButtonStyle())
            .accessibilityIdentifier("add-empty-" + code)
            .accessibilityLabel(Copy.add + Copy.categoryName(code))
        }
        .padding(.horizontal, 16)
        .frame(minHeight: Ds.rowHeight)
    }

    private func assetRow(_ asset: AssetRecord) -> some View {
        let selected = selectedAssetID == asset.id
        let rowLayout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        let label = rowLayout {
            VStack(alignment: .leading, spacing: 2) {
                Text(asset.name)
                    .font(Ds.body())
                    .foregroundStyle(Color.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 12)
            MoneyText(cents: asset.amountCents, currency: "CNY", font: Ds.bodyMedium())
            if !selectionMode {
                AppIcon(name: "ic-chevron-right", size: 14)
                    .foregroundStyle(Color.text2)
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: Ds.isDesktop ? 40 : 52)
        .contentShape(Rectangle())

        let row = Group {
            if selectionMode {
                // 分栏形态：点行选中
                Button { selectedAssetID = asset.id } label: { label }
                    .buttonStyle(.plain)
            } else {
                // 移动整页：推入详情（docs/mobile-layout.md §3.4 左滑删除）
                NavigationLink { AssetDetailView(assetID: asset.id) } label: { label }
            }
        }
        .background(
            selected || store.highlightID == asset.id
                ? AnyShapeStyle(Color.brandSoft) : AnyShapeStyle(.clear)
        )
        .contextMenu {
            Button { router.modal = .adjustBalance(asset) } label: { Label(Copy.adjustBalance, image: "ic-refresh") }
            Button { router.modal = .renameAsset(asset) } label: { Label(Copy.rename, image: "ic-info") }
            Button(role: .destructive) {
                Task { await store.deleteAsset(id: asset.id) }
            } label: { Label(Copy.delete, image: "ic-trash") }
        }
        .disabled(store.isWorking)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("asset-row-" + asset.name)

        return Group {
            if selectionMode {
                row
            } else {
                SwipeableRow(specs: [
                    SwipeActionSpec(icon: "ic-trash", title: Copy.delete) {
                        Task { await store.deleteAsset(id: asset.id) }
                    },
                ]) {
                    row
                }
            }
        }
    }
}

// MARK: - 右栏（桌面/iPad：概览或详情）

struct AssetsDetailPane: View {
    let selectedAssetID: String?

    var body: some View {
        if let id = selectedAssetID {
            AssetDetailView(assetID: id, inSplit: true)
        } else {
            AssetsOverviewPane()
        }
    }
}

/// 未选中时的总资产概览（§9.3 右栏未选中态）。
struct AssetsOverviewPane: View {
    @Environment(SnowFoldStore.self) private var store

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(Copy.totalAssets)
                    .font(Ds.bodyMedium())
                    .foregroundStyle(Color.text2)
                FocusAmountText(cents: store.overview?.totalAssetsCents ?? 0, level: .page)
                    .padding(.bottom, 12)
                HStack(alignment: .top, spacing: 32) {
                    stat(Copy.totalDebt, Fmt.money(store.overview?.totalDebtCents ?? 0, "CNY"), color: .neg)
                    stat(Copy.netAssets, Fmt.money(store.overview?.netAssetsCents ?? 0, "CNY"), color: .ink)
                    stat(Copy.recordsLabel, "\(store.ledger.assets.count) 项", color: .ink)
                }
                .padding(.bottom, 24)
                if let overview = store.overview {
                    let assets = overview.categories.filter { !$0.isLiability }
                    let total = overview.totalAssetsCents
                    Card {
                        VStack(spacing: 0) {
                            ForEach(Array(assets.enumerated()), id: \.element.code) { index, category in
                                if index > 0 {
                                    Rectangle().fill(Color.sep).frame(height: 1).padding(.leading, 16)
                                }
                                HStack(alignment: .center, spacing: 12) {
                                    CategoryChip(code: category.code, size: 28)
                                    Text(Copy.categoryName(category.code))
                                        .font(Ds.bodyMedium())
                                        .foregroundStyle(Color.ink)
                                    Spacer(minLength: 12)
                                    MoneyText(cents: category.amountCents, currency: "CNY", font: Ds.bodyMedium())
                                }
                                .padding(.horizontal, 16)
                                .padding(.top, 10)
                                let percent = total > 0 ? Double(category.amountCents) / Double(total) * 100 : 0
                                HStack(alignment: .center, spacing: 8) {
                                    PercentBar(percent: percent, color: Color.categoryColor(category.code))
                                        .frame(maxWidth: .infinity)
                                    Text(Fmt.percent(percent))
                                        .font(Ds.aux())
                                        .monospacedDigit()
                                        .foregroundStyle(Color.text2)
                                        .frame(width: 44, alignment: .trailing)
                                }
                                .padding(.leading, 56)
                                .padding(.trailing, 16)
                                .padding(.bottom, 10)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                }
            }
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func stat(_ label: String, _ value: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(Ds.aux()).foregroundStyle(Color.text2)
            Text(value)
                .font(Ds.bodyMedium())
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
    }
}

// MARK: - 资产详情（§9.3 右栏选中态 / 移动推入页）

struct AssetDetailView: View {
    let assetID: String
    var inSplit = false
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.dismiss) private var dismiss
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @State private var expandedCorrections: Set<String> = []

    private var asset: AssetRecord? {
        store.ledger.assets.first { $0.id == assetID }
    }

    private var history: [HistoryRecord] {
        store.ledger.history.filter { $0.assetId == assetID }
    }

    var body: some View {
        ScrollView {
            if let asset {
                detail(asset)
                    .padding(.horizontal, inSplit || Ds.isDesktop ? 0 : 16)
                    .padding(.top, inSplit ? 0 : 8)
                    .padding(.bottom, 24)
            } else {
                EmptyStateView(icon: "ic-wallet", title: Copy.message(for: .NotFound),
                               message: "该资产可能已被删除。")
            }
        }
        .background(Color.bg)
        .navigationTitle(Copy.assetsTitle)
        .phoneInlineTitle()
        .onChange(of: asset?.id) { _, id in
            if id == nil && !inSplit { dismiss() }
        }
    }

    private func detail(_ asset: AssetRecord) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                CategoryChip(code: asset.categoryCode, size: 24)
                Text(Copy.categoryName(asset.categoryCode))
                    .font(Ds.aux())
                    .foregroundStyle(Color.text2)
            }
            .padding(.bottom, 8)
            Text(asset.name)
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 4)
            FocusAmountText(cents: asset.amountCents, level: .page)
                .padding(.bottom, 8)
            Text(Copy.assetMeta(
                date: Fmt.day(String(asset.updatedAt.prefix(10))),
                historyCount: history.count
            ))
            .font(Ds.aux())
            .monospacedDigit()
            .foregroundStyle(Color.text2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, 16)
            actionRow(asset)
                .padding(.bottom, 28)
            historySection(asset)
        }
        .accessibilityIdentifier("asset-detail")
    }

    private func actionRow(_ asset: AssetRecord) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            Button(Copy.adjustBalance) {
                router.modal = .adjustBalance(asset)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(store.isWorking)
            .accessibilityIdentifier("adjust-balance")
            Button(Copy.rename) {
                router.modal = .renameAsset(asset)
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(store.isWorking)
            Spacer(minLength: 12)
            Button {
                Task {
                    await store.deleteAsset(id: asset.id)
                }
            } label: {
                HStack(spacing: 6) {
                    AppIcon(name: "ic-trash", size: 16)
                    Text(Copy.delete)
                }
            }
            .buttonStyle(TextButtonStyle(danger: true))
            .disabled(store.isWorking)
        }
    }

    private func historySection(_ asset: AssetRecord) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: Copy.historySection, count: history.count) {
                Text(Copy.historyNote)
                    .font(Ds.aux())
                    .foregroundStyle(Color.text2)
            }
            Card {
                if history.isEmpty {
                    Text(Copy.historyInitial)
                        .font(Ds.body())
                        .foregroundStyle(Color.text2)
                        .padding(16)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(history.reversed().enumerated()), id: \.element.id) { index, record in
                            if index > 0 {
                                Rectangle().fill(Color.sep).frame(height: 1).padding(.leading, 16)
                            }
                            historyRow(record, isCurrent: record.id == asset.currentHistoryId)
                        }
                    }
                    .padding(.vertical, 8)
                }
            }
        }
    }

    private func historyRow(_ record: HistoryRecord, isCurrent: Bool) -> some View {
        let previous = history.first { $0.id == record.previousId }
        return HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(Fmt.timestamp(record.recordedAt))
                    .font(Ds.body())
                    .monospacedDigit()
                    .foregroundStyle(Color.ink)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    if isCurrent {
                        TagBadge(text: Copy.historyCurrent, kind: .brand)
                    }
                    Text(deltaText(record, previous: previous))
                        .font(Ds.aux())
                        .monospacedDigit()
                        .foregroundStyle(Color.text2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !corrections(for: record).isEmpty {
                    DisclosureGroup(isExpanded: binding(for: record)) {
                        ForEach(corrections(for: record)) { correction in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Fmt.timestamp(correction.recordedAt))
                                    .font(Ds.aux())
                                    .monospacedDigit()
                                    .foregroundStyle(Color.text2)
                                Text("\(Fmt.money(correction.previousAmountCents, "CNY")) → \(Fmt.money(correction.amountCents, "CNY"))")
                                    .font(Ds.aux())
                                    .monospacedDigit()
                                    .foregroundStyle(Color.ink)
                            }
                            .padding(.vertical, 2)
                        }
                    } label: {
                        Text("修正记录（\(corrections(for: record).count)）")
                            .font(Ds.aux())
                            .foregroundStyle(Color.text2)
                    }
                    .tint(.brand)
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 4) {
                MoneyText(cents: record.amountCents, currency: "CNY", font: Ds.bodyMedium())
                Button(Copy.correctHistory) {
                    router.modal = .correctHistory(record, assetName: asset?.name ?? "")
                }
                .buttonStyle(TextButtonStyle())
                .frame(minHeight: 44)
                .disabled(store.isWorking)
                .accessibilityIdentifier("correct-" + record.id)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .task(id: "\(record.id)-\(record.amountCents)-\(store.syncGeneration)") {
            await loadCorrections(record)
        }
    }

    // 修正记录：按需从核心读取（界面不落库）。
    @State private var correctionsCache: [String: [HistoryCorrectionRecord]] = [:]

    private func corrections(for record: HistoryRecord) -> [HistoryCorrectionRecord] {
        correctionsCache[record.id] ?? []
    }

    private func binding(for record: HistoryRecord) -> Binding<Bool> {
        Binding(
            get: { expandedCorrections.contains(record.id) },
            set: { expanded in
                if expanded { expandedCorrections.insert(record.id) } else { expandedCorrections.remove(record.id) }
            }
        )
    }

    private func loadCorrections(_ record: HistoryRecord) async {
        do {
            correctionsCache[record.id] = try await store.historyCorrections(historyID: record.id)
        } catch {
            store.showErrorNotice(Copy.message(for: error))
        }
    }

    private func deltaText(_ record: HistoryRecord, previous: HistoryRecord?) -> String {
        guard let previous else { return Copy.historyInitial }
        let delta = record.amountCents - previous.amountCents
        return (delta > 0 ? "+" : "") + Fmt.money(delta, "CNY")
    }
}
