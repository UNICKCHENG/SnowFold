import SwiftUI

// MARK: - 通用骨架

/// sheet 内联错误行（保留输入，提示后重试）。
struct SheetErrorLine: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.circle.fill")
            .font(.footnote)
            .foregroundStyle(.negativeBrand)
            .fixedSize(horizontal: false, vertical: true)
    }
}

extension View {
    /// 保持核心返回的错误在编辑器可见区域，输入和草稿不变。
    func editorErrorBanner(_ message: String?) -> some View {
        safeAreaInset(edge: .top, spacing: 0) {
            if let message {
                SheetErrorLine(message: message)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Color.negativeSoft)
                    .accessibilityIdentifier("editor-error")
            }
        }
    }
}

/// 金额输入：数字键盘，两位小数以内由核心校验。
struct AmountField: View {
    let label: String
    @Binding var text: String

    var body: some View {
        TextField("0.00", text: $text)
            #if os(iOS)
            .keyboardType(.decimalPad)
            #endif
            .monospacedDigit()
            .textFieldStyle(.roundedBorder)
    }
}

// MARK: - 资产编辑

/// 新建资产 / 重命名 / 调整余额 三种编辑形态。
struct AssetEditorSheet: View {
    enum Mode {
        case new(categoryCode: String)
        case rename(AssetRecord)
        case balance(AssetRecord)

        var title: String {
            switch self {
            case .new: return "添加资产"
            case .rename: return "重命名"
            case .balance: return "调整余额"
            }
        }
    }

    let mode: Mode
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var amount = ""
    @State private var errorText: String?
    @FocusState private var inputFocused: Bool
    @State private var selectedCategory = "cash"
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationStack {
            Form {
                switch mode {
                case .new:
                    Section("分类") {
                        LazyVGrid(columns: typeSize.isAccessibilitySize
                                  ? [GridItem(.flexible())]
                                  : [GridItem(.adaptive(minimum: 88))], spacing: 12) {
                            ForEach(Copy.categoryOrder, id: \.self) { code in
                                Button { selectedCategory = code } label: {
                                    VStack(spacing: 7) {
                                        CategoryChip(code: code, size: 34)
                                        Text(Copy.categoryName(code))
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(Color.ink)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 72)
                                    .padding(6)
                                    .background(selectedCategory == code ? Color.accentSoft : Color.subtleSurface,
                                                in: RoundedRectangle(cornerRadius: 12))
                                    .overlay(RoundedRectangle(cornerRadius: 12)
                                        .strokeBorder(selectedCategory == code ? Color.accentBrand : .clear, lineWidth: 1.5))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Copy.categoryName(code))
                                .accessibilityAddTraits(selectedCategory == code ? [.isSelected] : [])
                                .accessibilityIdentifier("category-" + code)
                            }
                        }
                    }
                    Section {
                        TextField("名称", text: $name).focused($inputFocused)
                        AmountField(label: "金额", text: $amount).focused($inputFocused)
                    }
                case .rename:
                    Section {
                        TextField("名称", text: $name).focused($inputFocused)
                    }
                case .balance(let asset):
                    Section {
                        AmountField(label: "金额", text: $amount).focused($inputFocused)
                    } header: {
                        Text("当前余额 \(Fmt.money(asset.amountCents, "CNY"))")
                    } footer: {
                        Text("变动会记入该资产的历史，保存后 6 秒内可撤销。")
                    }
                }
            }
            .editorErrorBanner(errorText)
            .navigationTitle(mode.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            #if os(iOS)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Copy.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Copy.save) { Task { await save() } }
                        .disabled(store.isWorking || cannotSave)
                }
            }
            #else
            .toolbar {
                ToolbarItem {
                    Button(Copy.save) { Task { await save() } }
                        .disabled(store.isWorking || cannotSave)
                        .keyboardShortcut(.defaultAction)
                }
            }
            #endif
            .onAppear(perform: prefill)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.selection, trigger: selectedCategory)
    }

    private var cannotSave: Bool {
        switch mode {
        case .new: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || amount.isEmpty
        case .rename: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .balance: amount.isEmpty
        }
    }

    private func prefill() {
        switch mode {
        case .new(let categoryCode):
            selectedCategory = categoryCode
            name = ""
            amount = ""
        case .rename(let asset):
            name = asset.name
        case .balance(let asset):
            amount = Fmt.decimal(asset.amountCents)
        }
    }

    private func save() async {
        inputFocused = false
        do {
            switch mode {
            case .new:
                _ = try await store.createAsset(categoryCode: selectedCategory, name: name, amountText: amount)
            case .rename(let asset):
                // 打开期间记录被删除：提示后关闭，不写库。
                guard store.ledger.assets.contains(where: { $0.id == asset.id }) else {
                    return staleRecord()
                }
                try await store.renameAsset(id: asset.id, name: name)
            case .balance(let asset):
                guard store.ledger.assets.contains(where: { $0.id == asset.id }) else {
                    return staleRecord()
                }
                try await store.changeBalance(id: asset.id, amountText: amount)
            }
            errorText = nil
            dismiss()
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
        }
    }

    private func staleRecord() {
        store.showNotice(Copy.message(for: CoreError.NotFound))
        dismiss()
    }
}

// MARK: - 快照修正

/// 修正某条快照的金额：核心记录改前改后，不新增快照。
struct HistoryCorrectionSheet: View {
    let history: HistoryRecord
    let assetName: String

    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""
    @State private var errorText: String?
    @FocusState private var inputFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    AmountField(label: "金额", text: $amount).focused($inputFocused)
                } header: {
                    Text(assetName)
                } footer: {
                    Text("原金额 \(Fmt.money(history.amountCents, "CNY")) · 记录于 \(Fmt.timestamp(history.recordedAt))。修正不会新增快照。")
                }
            }
            .editorErrorBanner(errorText)
            .navigationTitle(Copy.correctHistory)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            #if os(iOS)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Copy.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Copy.save) { Task { await save() } }
                        .disabled(store.isWorking)
                }
            }
            #else
            .toolbar {
                ToolbarItem {
                    Button(Copy.save) { Task { await save() } }
                        .disabled(store.isWorking)
                        .keyboardShortcut(.defaultAction)
                }
            }
            #endif
            .onAppear { amount = Fmt.decimal(history.amountCents) }
        }
        .presentationDetents([.medium, .large])
    }

    private func save() async {
        inputFocused = false
        guard store.ledger.history.contains(where: { $0.id == history.id }) else {
            store.showNotice(Copy.message(for: CoreError.NotFound))
            dismiss()
            return
        }
        do {
            try await store.correctHistory(historyID: history.id, amountText: amount)
            errorText = nil
            dismiss()
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
        }
    }
}

// MARK: - 订阅编辑

/// 新建 / 编辑订阅：名称、金额、币种、周期、下次扣费日（与状态）。
struct SubscriptionEditorSheet: View {
    enum Mode {
        case new
        case edit(SubscriptionRecord)

        var title: String {
            switch self {
            case .new: return Copy.newSubscriptionTitle
            case .edit: return "编辑订阅"
            }
        }
    }

    let mode: Mode
    var embedded = false
    var onCompletion: (() -> Void)? = nil
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var amount = ""
    @State private var currency = "CNY"
    @State private var cycle = "monthly"
    @State private var status = "active"
    @State private var chargeDate = Date()
    @State private var errorText: String?
    @FocusState private var inputFocused: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if embedded {
                editorContent
            } else {
                NavigationStack { editorContent }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var editorContent: some View {
        Form {
            Section {
                TextField("名称", text: $name).focused($inputFocused)
                AmountField(label: "金额", text: $amount).focused($inputFocused)
                VStack(alignment: .leading, spacing: 8) {
                    Text("币种").font(.caption).foregroundStyle(Color.inkMuted)
                    if typeSize.isAccessibilitySize { currencyPicker }
                    else { currencyPicker.pickerStyle(.segmented) }
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("扣费周期").font(.caption).foregroundStyle(Color.inkMuted)
                    if typeSize.isAccessibilitySize { cyclePicker }
                    else { cyclePicker.pickerStyle(.segmented) }
                }
                DatePicker("下次扣费日", selection: $chargeDate, displayedComponents: .date)
                if case .edit = mode {
                    Picker("状态", selection: $status) {
                        Text("在订").tag("active")
                        Text("已暂停").tag("paused")
                    }
                }
            } footer: {
                Text(Copy.newSubscriptionHelp)
                    .font(.footnote)
            }
        }
        .editorErrorBanner(errorText)
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Color.canvas)
        .navigationTitle(mode.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(Copy.cancel) { finishEditing() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(Copy.save) { Task { await save() } }
                    .disabled(store.isWorking || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || amount.isEmpty)
            }
        }
        .onAppear(perform: prefill)
    }

    private var currencyPicker: some View {
        Picker("币种", selection: $currency) {
            Text(Copy.currencyCNY).tag("CNY")
            Text(Copy.currencyUSD).tag("USD")
        }
    }

    private var cyclePicker: some View {
        Picker("扣费周期", selection: $cycle) {
            ForEach(Copy.cycles, id: \.self) { code in Text(Copy.cycleLabel(code)).tag(code) }
        }
    }

    private func finishEditing() {
        if let onCompletion { onCompletion() } else { dismiss() }
    }

    private func prefill() {
        switch mode {
        case .new:
            chargeDate = Date()
            currency = "CNY"
            cycle = "monthly"
            status = "active"
        case .edit(let record):
            name = record.name
            amount = Fmt.decimal(record.amountCents)
            currency = record.currency
            cycle = record.cycle
            status = record.status
            chargeDate = Fmt.date(fromDay: record.nextChargeOn) ?? Date()
        }
    }

    private var input: SubscriptionInput {
        SubscriptionInput(
            name: name,
            amountCents: 0, // save() 里解析后填充
            currency: currency,
            cycle: cycle,
            nextChargeOn: Fmt.dayString(from: chargeDate),
            status: status
        )
    }

    private func save() async {
        inputFocused = false
        let cents: Int64
        do {
            cents = try store.parseCents(amount)
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
            return
        }
        var payload = input
        payload.amountCents = cents
        do {
            switch mode {
            case .new:
                _ = try await store.createSubscription(payload)
            case .edit(let record):
                guard store.ledger.subscriptions.contains(where: { $0.id == record.id }) else {
                    store.showNotice(Copy.message(for: CoreError.NotFound))
                    finishEditing()
                    return
                }
                try await store.updateSubscription(id: record.id, payload)
            }
            errorText = nil
            finishEditing()
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
        }
    }
}
