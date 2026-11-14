import SwiftUI

/// iPhone 推入详情；iPad/Mac 由分栏容器接收选择，复用同一列表与详情。
struct AssetsView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.dynamicTypeSize) private var typeSize
    var onSelect: ((String) -> Void)? = nil
    var selectedAssetID: String? = nil
    @State private var newAssetCategory: String?
    @State private var renameTarget: AssetRecord?
    @State private var balanceTarget: AssetRecord?

    private struct CategoryPick: Identifiable {
        let code: String
        var id: String { code }
    }

    var body: some View {
        ScrollViewReader { proxy in
            List {
                summarySection
                ForEach(Copy.categoryOrder, id: \.self) { code in categorySection(code) }
            }
            .accessibilityIdentifier("assets-list")
            .onChange(of: router.focusAssetCategory) { _, code in
                guard let code else { return }
                withAnimation { proxy.scrollTo(code, anchor: .top) }
                router.focusAssetCategory = nil
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.canvas)
        .navigationTitle(Copy.assetsTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.large)
        #endif
        .settingsToolbar {
            Button { newAssetCategory = "cash" } label: { Label("新建资产", systemImage: "plus").accessibilityIdentifier("new-asset") }
        }
        .animation(.default, value: store.ledger)
        .sensoryFeedback(.success, trigger: store.highlightID)
        .sensoryFeedback(.warning, trigger: store.undoState)
        .sheet(item: Binding(
            get: { newAssetCategory.map(CategoryPick.init) },
            set: { newAssetCategory = $0?.code }
        )) { pick in AssetEditorSheet(mode: .new(categoryCode: pick.code)) }
        .sheet(item: $renameTarget) { AssetEditorSheet(mode: .rename($0)) }
        .sheet(item: $balanceTarget) { AssetEditorSheet(mode: .balance($0)) }
    }

    private var summarySection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(Copy.totalAssets).font(.caption).foregroundStyle(Color.inkMuted)
                if let total = store.overview?.totalAssetsCents {
                    HeroAmountText(cents: total)
                } else { InlineErrorPanel(message: Copy.assetTotalOverflow) }
                Text(Copy.assetsSubtitle(store.ledger.assets.count))
                    .font(.footnote).foregroundStyle(Color.inkMuted)
                if let overview = store.overview {
                    Text("总负债 \(Fmt.money(overview.totalDebtCents, "CNY")) · 净资产 \(Fmt.money(overview.netAssetsCents, "CNY"))")
                        .font(.caption).monospacedDigit().foregroundStyle(Color.inkMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
        }
    }

    private func categorySection(_ code: String) -> some View {
        let assets = store.ledger.assets.filter { $0.categoryCode == code }
        let total = store.overview?.categories.first { $0.code == code }?.amountCents
        return Section {
            if assets.isEmpty {
                Button { newAssetCategory = code } label: {
                    Label("添加" + Copy.categoryName(code), systemImage: "plus")
                        .font(.subheadline.weight(.medium))
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .padding(12)
                        .overlay(RoundedRectangle(cornerRadius: 14)
                            .strokeBorder(Color.hairlineStrong, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentBrand)
                .accessibilityIdentifier("add-empty-" + code)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            ForEach(assets, id: \.id) { asset in assetRow(asset) }
        } header: {
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(alignment: .center, spacing: 8))
            layout {
                HStack(spacing: 7) {
                    CategoryChip(code: code, size: 24)
                    Text(Copy.categoryName(code)).font(.caption.weight(.semibold))
                    Text("\(assets.count)").font(.caption.monospacedDigit())
                }
                if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
                if let total {
                    MoneyText(cents: total, currency: "CNY", font: .footnote.weight(.semibold),
                              color: code == "debt" ? .negativeBrand : .ink)
                } else { Text(Copy.totalOutOfRange).font(.caption) }
                if !assets.isEmpty {
                    Button { newAssetCategory = code } label: { Image(systemName: "plus") }
                        .buttonStyle(.borderless)
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityLabel("添加" + Copy.categoryName(code))
                }
            }
            .foregroundStyle(Color.inkMuted)
            .id(code)
            .textCase(nil)
        }
    }

    private func assetRow(_ asset: AssetRecord) -> some View {
        Group {
            if let onSelect {
                Button { onSelect(asset.id) } label: { assetLabel(asset) }
                    .buttonStyle(.plain)
            } else {
                NavigationLink { AssetDetailView(assetID: asset.id) } label: { assetLabel(asset) }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) { Task { await store.deleteAsset(id: asset.id) } }
                label: { Label("删除", systemImage: "trash") }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button { balanceTarget = asset } label: { Label(Copy.adjustBalance, systemImage: "arrow.triangle.2.circlepath") }
                .tint(.accentBrand)
        }
        .contextMenu {
            Button { balanceTarget = asset } label: { Label(Copy.adjustBalance, systemImage: "arrow.triangle.2.circlepath") }
            Button { renameTarget = asset } label: { Label(Copy.rename, systemImage: "pencil") }
            Button(role: .destructive) { Task { await store.deleteAsset(id: asset.id) } }
                label: { Label("删除", systemImage: "trash") }
        }
        .listRowBackground(store.highlightID == asset.id || selectedAssetID == asset.id ? Color.accentSoft : Color.cardSurface)
        .listRowSeparatorTint(Color.hairline)
        .disabled(store.isWorking)
    }

    private func assetLabel(_ asset: AssetRecord) -> some View {
        let count = store.ledger.history.filter { $0.assetId == asset.id }.count
        return HStack(alignment: .top, spacing: 10) {
            CategoryChip(code: asset.categoryCode, size: 32)
            VStack(alignment: .leading, spacing: 6) {
                Text(asset.name).font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.ink).fixedSize(horizontal: false, vertical: true)
                Text("历史 \(count) 条 · 更新于 \(Fmt.day(String(asset.updatedAt.prefix(10))))")
                    .font(.caption).foregroundStyle(Color.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)
                MoneyText(cents: asset.amountCents, currency: "CNY", font: .subheadline.weight(.semibold))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 7)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Copy.a11yAsset(asset.name, category: Copy.categoryName(asset.categoryCode),
                                          amount: Fmt.money(asset.amountCents, "CNY"), historyCount: count)
                            + "，更新于" + Fmt.day(String(asset.updatedAt.prefix(10))))
    }
}

// MARK: - 资产详情（历史与修正）

struct AssetDetailView: View {
    let assetID: String
    var onDelete: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(SnowFoldStore.self) private var store
    @State private var renameTarget: AssetRecord?
    @State private var balanceTarget: AssetRecord?
    @State private var correctionTarget: HistoryRecord?

    private var asset: AssetRecord? {
        store.ledger.assets.first { $0.id == assetID }
    }

    private var history: [HistoryRecord] {
        store.ledger.history.filter { $0.assetId == assetID }
    }

    var body: some View {
        Group {
            if let asset {
                content(asset)
            } else {
                EmptyStateView(
                    icon: "questionmark.circle",
                    title: Copy.message(for: .NotFound),
                    message: "该资产可能已被删除。"
                )
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.canvas)
        .navigationTitle(asset?.name ?? Copy.assetsTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(item: $renameTarget) { target in
            AssetEditorSheet(mode: .rename(target))
        }
        .sheet(item: $balanceTarget) { target in
            AssetEditorSheet(mode: .balance(target))
        }
        .sheet(item: $correctionTarget) { record in
            HistoryCorrectionSheet(history: record, assetName: asset?.name ?? "")
        }
    }

    @ViewBuilder
    private func content(_ asset: AssetRecord) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(asset.name)
                        .font(.title3.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 8) {
                        CategoryChip(code: asset.categoryCode, size: 24)
                        Text(Copy.categoryName(asset.categoryCode))
                            .font(.footnote)
                            .foregroundStyle(Color.inkMuted)
                    }
                    MoneyText(cents: asset.amountCents, currency: "CNY", font: .title.weight(.semibold))
                    VStack(spacing: 10) {
                        Button {
                            balanceTarget = asset
                        } label: {
                            Label(Copy.adjustBalance, systemImage: "arrow.triangle.2.circlepath")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(InkButtonStyle())
                        .disabled(store.isWorking)
                        Button {
                            renameTarget = asset
                        } label: {
                            Label(Copy.rename, systemImage: "pencil")
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.vertical, 8)
            }
            historySection(asset)
            Section {
                Button("删除这项资产", role: .destructive) {
                    Task {
                        await store.deleteAsset(id: asset.id)
                        if !store.ledger.assets.contains(where: { $0.id == asset.id }) {
                            if let onDelete { onDelete() } else { dismiss() }
                        }
                    }
                }
                .disabled(store.isWorking)
            }
        }
    }

    @ViewBuilder
    private func historySection(_ asset: AssetRecord) -> some View {
        Section {
            if history.isEmpty {
                Text(Copy.historyInitial)
                    .font(.footnote)
                    .foregroundStyle(Color.ink3)
            }
            ForEach(Array(history.enumerated().reversed()), id: \.element.id) { position, record in
                HistoryRowView(
                    record: record,
                    previous: position > 0 ? history[position - 1] : nil,
                    isCurrent: position + 1 == history.count,
                    onCorrect: { correctionTarget = record }
                )
            }
        } header: {
            Text(Copy.historyTitle)
                .font(.footnote.weight(.semibold))
        }
    }
}

/// 单条快照：时间与增量、当前余额、修正入口与改前改后记录。
struct HistoryRowView: View {
    let record: HistoryRecord
    let previous: HistoryRecord?
    let isCurrent: Bool
    let onCorrect: () -> Void
    @Environment(SnowFoldStore.self) private var store
    @State private var corrections: [HistoryCorrectionRecord] = []
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 8) {
                Text(Fmt.timestamp(record.recordedAt))
                    .font(.footnote).monospacedDigit().foregroundStyle(Color.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)
                if isCurrent {
                    Text(Copy.historyCurrent).font(.caption2.weight(.medium))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentSoft, in: Capsule()).foregroundStyle(Color.accentBrand)
                }
            }
            MoneyText(cents: record.amountCents, currency: "CNY", font: .subheadline.weight(.semibold))
            Text(deltaText).font(.caption).monospacedDigit()
                .foregroundStyle(previous.map { record.amountCents >= $0.amountCents ? Color.positiveBrand : Color.negativeBrand } ?? Color.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
            Button(Copy.correctHistory, action: onCorrect)
                .font(.footnote.weight(.medium)).buttonStyle(.borderless)
                .frame(minHeight: 44)
            if !corrections.isEmpty {
                DisclosureGroup("修正记录（\(corrections.count)）", isExpanded: $expanded) {
                    ForEach(corrections, id: \.id) { correction in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(Fmt.timestamp(correction.recordedAt)).font(.caption2).foregroundStyle(Color.inkMuted)
                            Text("\(Fmt.money(correction.previousAmountCents, "CNY")) → \(Fmt.money(correction.amountCents, "CNY"))")
                                .font(.caption).monospacedDigit().fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 4)
                    }
                }
                .font(.caption)
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .contain)
        .task(id: record) {
            do { corrections = try await store.historyCorrections(historyID: record.id) }
            catch { store.showNotice(Copy.message(for: error)) }
        }
    }

    private var deltaText: String {
        guard let previous else { return Copy.historyInitial }
        let delta = record.amountCents - previous.amountCents
        let sign = delta > 0 ? "+" : ""
        return "\(sign)\(Fmt.money(delta, "CNY"))"
    }
}
