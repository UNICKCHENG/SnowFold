import SwiftUI

// MARK: - 弹层骨架

#if os(macOS)
/// 桌面居中弹层（§5.6）：宽 480、card 底、圆角 16、浮层阴影；
/// 标题上 20、左右 24；标签列 88 右对齐；底栏右对齐取消/保存。
struct MacDialogScaffold<Content: View, Footer: View>: View {
    let title: String
    var canSave = true
    let onSave: () -> Void
    let onCancel: () -> Void
    var errorLine: String? = nil
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footerLeading: () -> Footer

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
                .padding(.top, 20)
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            content()
                .padding(.horizontal, 24)
            if let errorLine {
                FormErrorLine(message: errorLine)
                    .padding(.top, 12)
                    .padding(.horizontal, 24)
                    .accessibilityIdentifier("editor-error")
            }
            HStack(spacing: 8) {
                footerLeading()
                Spacer(minLength: 8)
                Button(Copy.cancel, action: onCancel)
                    .buttonStyle(SecondaryButtonStyle())
                    .keyboardShortcut(.cancelAction)
                Button(Copy.save) { onSave() }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canSave)
                    .keyboardShortcut(.defaultAction)
                    .accessibilityIdentifier("dialog-save")
            }
            .padding(.top, 20)
            .padding(.horizontal, 24)
            .padding(.bottom, 20)
        }
        .frame(width: 480)
        .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(Color.cardBg.opacity(0.0), lineWidth: 0))
        .shadow(color: .black.opacity(0.14), radius: 32, y: 8)
    }
}
#else
/// 移动 sheet（mobile-layout.md §6）：头部取消/保存圆形按钮 + 标题，单卡片表单。
struct MobileSheetScaffold<Content: View>: View {
    let title: String
    var canSave = true
    let onSave: () -> Void
    let onCancel: () -> Void
    var errorLine: String? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                IconCircleButton(icon: "ic-close", size: 44, action: onCancel)
                    .accessibilityLabel(Copy.cancel)
                    .accessibilityIdentifier("sheet-cancel")
                Spacer()
                Text(title)
                    .font(Ds.sectionTitle())
                    .foregroundStyle(Color.ink)
                Spacer()
                IconCircleButton(icon: "ic-check", size: 44,
                                 background: canSave ? .brand : Color.sep,
                                 foreground: canSave ? .onBrand : .text2) { onSave() }
                    .disabled(!canSave)
                    .accessibilityLabel(Copy.save)
                    .accessibilityIdentifier("sheet-save")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    content()
                    if let errorLine {
                        FormErrorLine(message: errorLine)
                            .padding(.horizontal, 16)
                            .accessibilityIdentifier("editor-error")
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
        }
        .background(Color.bg)
        .presentationDragIndicator(.visible)
        .presentationDetents([.large])
    }
}
#endif

/// 弹层内标签（桌面右对齐 88 宽）。
struct DialogLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(Ds.body())
            .foregroundStyle(Color.ink)
            #if os(macOS)
            .frame(width: 88, alignment: .trailing)
            #else
            .frame(width: 96, alignment: .leading)
            #endif
    }
}

/// 金额输入行：左侧币种符（text2）+ 金额（等宽数字）；占位符「0.00」。
struct AmountFieldRow: View {
    var symbol = "¥"
    @Binding var text: String
    var autoFocus = false
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 6) {
            Text(symbol)
                .font(Ds.body())
                .foregroundStyle(Color.text2)
            TextField("0.00", text: $text)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .font(Ds.body())
                .monospacedDigit()
                .focused($focused)
                .modifier(DSInputStyle(focused: $focused))
        }
        .onAppear {
            if autoFocus { focused = true }
        }
    }
}

/// 文本输入行。
struct TextFieldRow: View {
    let placeholder: String
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        TextField(placeholder, text: $text)
            .font(Ds.body())
            .focused($focused)
            .modifier(DSInputStyle(focused: $focused))
            .onAppear { focused = true }
    }
}

/// 分段控件独占一行（标签在上，控件满宽）。
struct StackedSegmentedRow<Option: Hashable>: View {
    let title: String
    let options: [Option]
    @Binding var selection: Option
    var label: (Option) -> String
    var identifier: (Option) -> String = { _ in "" }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(Ds.body())
                .foregroundStyle(Color.ink)
            BrandSegmentedPicker(options: options, selection: $selection,
                                 label: label, identifier: identifier)
        }
    }
}

// MARK: - 添加资产 / 重命名 / 调整余额

/// 三种资产编辑形态：新建（分类选择器）、重命名、调整余额。
struct AssetEditorSheet: View {
    enum Mode {
        case new(categoryCode: String)
        case rename(AssetRecord)
        case balance(AssetRecord)

        var title: String {
            switch self {
            case .new: return Copy.addAsset
            case .rename: return Copy.rename
            case .balance: return Copy.adjustBalance
            }
        }
    }

    let mode: Mode
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(Router.self) private var router
    @State private var name = ""
    @State private var amount = ""
    @State private var errorText: String?
    @State private var categoryPickerPresented = false
    @State private var selectedCategory = "cash"

    var body: some View {
        #if os(macOS)
        MacDialogScaffold(
            title: mode.title,
            canSave: !cannotSave && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { router.modal = nil },
            errorLine: errorText
        ) {
            dialogContent
        } footerLeading: {
            EmptyView()
        }
        #else
        MobileSheetScaffold(
            title: mode.title,
            canSave: !cannotSave && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { dismiss() },
            errorLine: errorText
        ) {
            formCard
        }
        #endif
        .onAppear(perform: prefill)
        .sensoryFeedback(.selection, trigger: selectedCategory)
    }

    private var cannotSave: Bool {
        switch mode {
        case .new: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || amount.isEmpty
        case .rename: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .balance: amount.isEmpty
        }
    }

    // MARK: 桌面：两列网格

    #if os(macOS)
    @ViewBuilder
    private var dialogContent: some View {
        switch mode {
        case .new:
            VStack(spacing: 16) {
                categoryDialogRow
                FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                    TextFieldRow(placeholder: Copy.assetNamePlaceholder, text: $name)
                        .frame(maxWidth: .infinity)
                }
                FormRow(label: { DialogLabel(text: Copy.amountLabel) }) {
                    AmountFieldRow(symbol: symbol, text: $amount)
                        .frame(maxWidth: .infinity)
                }
            }
        case .rename:
            FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                TextFieldRow(placeholder: Copy.assetNamePlaceholder, text: $name)
                    .frame(maxWidth: .infinity)
            }
        case .balance(let asset):
            VStack(spacing: 16) {
                FormRow(label: { DialogLabel(text: Copy.assetLabel) }) {
                    ReadOnlyValue(text: asset.name).frame(maxWidth: .infinity, alignment: .leading)
                }
                FormRow(label: { DialogLabel(text: Copy.currentBalanceLabel) }) {
                    ReadOnlyValue(text: Fmt.money(asset.amountCents, "CNY"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                FormRow(label: { DialogLabel(text: Copy.newBalanceLabel) }) {
                    AmountFieldRow(symbol: symbol, text: $amount, autoFocus: true)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    /// 分类选择器行：图标块 + 当前分类 + chevron-right；点开列表选择。
    private var categoryDialogRow: some View {
        FormRow(label: { DialogLabel(text: Copy.categoryLabel) }) {
            Button {
                categoryPickerPresented = true
            } label: {
                HStack(spacing: 8) {
                    CategoryChip(code: selectedCategory, size: 24)
                    Text(Copy.categoryName(selectedCategory))
                        .font(Ds.body())
                        .foregroundStyle(Color.ink)
                    Spacer(minLength: 8)
                    AppIcon(name: "ic-chevron-right", size: 14)
                        .foregroundStyle(Color.text2)
                }
                .padding(.horizontal, 10)
                .frame(minHeight: Ds.fieldHeight)
                .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.sep, lineWidth: 1))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .popover(isPresented: $categoryPickerPresented, arrowEdge: .bottom) {
                VStack(spacing: 2) {
                    ForEach(Copy.categoryOrder, id: \.self) { code in
                        Button {
                            selectedCategory = code
                            categoryPickerPresented = false
                        } label: {
                            HStack(spacing: 8) {
                                CategoryChip(code: code, size: 24)
                                Text(Copy.categoryName(code))
                                    .font(Ds.body())
                                    .foregroundStyle(Color.ink)
                                Spacer(minLength: 0)
                                if code == selectedCategory {
                                    AppIcon(name: "ic-check", size: 14)
                                        .foregroundStyle(Color.brand)
                                }
                            }
                            .padding(.horizontal, 12)
                            .frame(minHeight: 32)
                            .background(code == selectedCategory
                                        ? AnyShapeStyle(Color.brandSoft) : AnyShapeStyle(.clear),
                                        in: RoundedRectangle(cornerRadius: 10))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("category-dialog-" + code)
                    }
                }
                .padding(8)
                .frame(width: 220)
                .presentationCompactAdaptation(.popover)
            }
        }
    }
    #endif

    // MARK: 移动：单卡片表单（行最小高 52）

    private var symbol: String { "¥" }

    private var formCard: some View {
        Card {
            VStack(spacing: 0) {
                switch mode {
                case .new:
                    FormRow(label: { DialogLabel(text: Copy.categoryLabel) }) {
                        categoryMobilePicker
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                        TextFieldRow(placeholder: Copy.assetNamePlaceholder, text: $name)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.amountLabel) }) {
                        AmountFieldRow(text: $amount)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                case .rename:
                    FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                        TextFieldRow(placeholder: Copy.assetNamePlaceholder, text: $name)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                case .balance(let asset):
                    FormRow(label: { DialogLabel(text: Copy.assetLabel) }) {
                        ReadOnlyValue(text: asset.name).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.currentBalanceLabel) }) {
                        ReadOnlyValue(text: Fmt.money(asset.amountCents, "CNY"))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.newBalanceLabel) }) {
                        AmountFieldRow(text: $amount)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                }
            }
            .padding(.horizontal, 16)
        }
        if case .balance = mode {
            FormHelpLine(message: "变动会记入该资产的历史，保存后 6 秒内可撤销。")
        }
    }

    /// 移动分类选择：点行弹出五个分类菜单项，选中带对勾。
    private var categoryMobilePicker: some View {
        Menu {
            ForEach(Copy.categoryOrder, id: \.self) { code in
                Button {
                    selectedCategory = code
                } label: {
                    if code == selectedCategory {
                        Label(Copy.categoryName(code), image: "ic-check")
                    } else {
                        Label(Copy.categoryName(code), image: CategoryStyle.icon(code))
                    }
                }
                .accessibilityIdentifier("category-" + code)
            }
        } label: {
            HStack(spacing: 8) {
                CategoryChip(code: selectedCategory, size: 24)
                Text(Copy.categoryName(selectedCategory))
                    .font(Ds.body())
                    .foregroundStyle(Color.ink)
                Spacer(minLength: 8)
                AppIcon(name: "ic-chevron-right", size: 14)
                    .foregroundStyle(Color.text2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1)
    }

    // MARK: 保存

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
            close()
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
        }
    }

    private func staleRecord() {
        store.showNotice(Copy.message(for: CoreError.NotFound), isError: true)
        close()
    }

    private func close() {
        #if os(macOS)
        router.modal = nil
        #else
        dismiss()
        #endif
    }
}

// MARK: - 修正历史

/// 修正某条快照的金额：核心记录改前改后，不新增快照。
struct HistoryCorrectionSheet: View {
    let history: HistoryRecord
    let assetName: String
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(Router.self) private var router
    @State private var amount = ""
    @State private var errorText: String?

    var body: some View {
        #if os(macOS)
        MacDialogScaffold(
            title: Copy.correctHistoryTitle,
            canSave: !amount.isEmpty && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { router.modal = nil },
            errorLine: errorText
        ) {
            VStack(spacing: 16) {
                FormRow(label: { DialogLabel(text: Copy.historyTimeLabel) }) {
                    ReadOnlyValue(text: Fmt.timestamp(history.recordedAt))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                FormRow(label: { DialogLabel(text: Copy.originalAmountLabel) }) {
                    ReadOnlyValue(text: Fmt.money(history.amountCents, "CNY"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                FormRow(label: { DialogLabel(text: Copy.correctedAmountLabel) }) {
                    AmountFieldRow(text: $amount, autoFocus: true)
                        .frame(maxWidth: .infinity)
                }
                FormHelpLine(message: Copy.historyNote)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } footerLeading: {
            EmptyView()
        }
        #else
        MobileSheetScaffold(
            title: Copy.correctHistoryTitle,
            canSave: !amount.isEmpty && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { dismiss() },
            errorLine: errorText
        ) {
            VStack(alignment: .leading, spacing: 12) {
                Card {
                    VStack(spacing: 0) {
                        FormRow(label: { DialogLabel(text: Copy.historyTimeLabel) }) {
                            ReadOnlyValue(text: Fmt.timestamp(history.recordedAt))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: 52)
                        rowSeparator
                        FormRow(label: { DialogLabel(text: Copy.originalAmountLabel) }) {
                            ReadOnlyValue(text: Fmt.money(history.amountCents, "CNY"))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: 52)
                        rowSeparator
                        FormRow(label: { DialogLabel(text: Copy.correctedAmountLabel) }) {
                            AmountFieldRow(text: $amount)
                                .frame(maxWidth: .infinity)
                        }
                        .frame(minHeight: 52)
                    }
                    .padding(.horizontal, 16)
                }
                FormHelpLine(message: Copy.historyNote)
            }
        }
        #endif
        .onAppear { amount = Fmt.decimal(history.amountCents) }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1)
    }

    private func save() async {
        guard store.ledger.history.contains(where: { $0.id == history.id }) else {
            store.showNotice(Copy.message(for: CoreError.NotFound), isError: true)
            close()
            return
        }
        do {
            try await store.correctHistory(historyID: history.id, amountText: amount)
            errorText = nil
            close()
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
        }
    }

    private func close() {
        #if os(macOS)
        router.modal = nil
        #else
        dismiss()
        #endif
    }
}

// MARK: - 新建 / 编辑订阅

/// 新建 / 编辑订阅：名称、金额、币种、周期、下次扣费日；编辑态底栏左侧删除与暂停/恢复。
struct SubscriptionEditorSheet: View {
    enum Mode {
        case new
        case edit(SubscriptionRecord)

        var title: String {
            switch self {
            case .new: return Copy.newSubscriptionTitle
            case .edit: return Copy.editSubscriptionTitle
            }
        }
    }

    let mode: Mode
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(Router.self) private var router
    @State private var name = ""
    @State private var amount = ""
    @State private var currency = "CNY"
    @State private var cycle = "monthly"
    @State private var status = "active"
    @State private var chargeDate = Date()
    @State private var errorText: String?
    @State private var chargeDateMissing = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        #if os(macOS)
        MacDialogScaffold(
            title: mode.title,
            canSave: !cannotSave && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { router.modal = nil },
            errorLine: overallError
        ) {
            dialogContent
        } footerLeading: {
            if case .edit(let record) = mode {
                HStack(spacing: 16) {
                    Button {
                        Task {
                            await store.deleteSubscription(id: record.id)
                            router.modal = nil
                        }
                    } label: {
                        Text(Copy.deleteSubscription)
                            .font(Ds.bodyMedium())
                            .foregroundStyle(Color.neg)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Button {
                        Task {
                            await store.setSubscriptionPaused(id: record.id, paused: record.status == "active")
                            router.modal = nil
                        }
                    } label: {
                        Text(record.status == "active" ? Copy.pause : Copy.resume)
                            .font(Ds.bodyMedium())
                            .foregroundStyle(Color.brand)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        #else
        MobileSheetScaffold(
            title: mode.title,
            canSave: !cannotSave && !store.isWorking,
            onSave: { Task { await save() } },
            onCancel: { dismiss() },
            errorLine: overallError
        ) {
            mobileContent
        }
        #endif
        .onAppear(perform: prefill)
    }

    private var cannotSave: Bool {
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || amount.isEmpty
    }

    /// 日期缺失时字段下方「请选择下次扣费日」；其余用整体错误行。
    private var overallError: String? {
        if chargeDateMissing { return nil }
        return errorText
    }

    // MARK: 桌面内容

    #if os(macOS)
    private var dialogContent: some View {
        VStack(spacing: 16) {
            FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                TextFieldRow(placeholder: Copy.subscriptionNamePlaceholder, text: $name)
                    .frame(maxWidth: .infinity)
            }
            FormRow(label: { DialogLabel(text: Copy.amountLabel) }) {
                AmountFieldRow(symbol: currency == "USD" ? "$" : "¥", text: $amount)
                    .frame(maxWidth: .infinity)
            }
            FormRow(label: { DialogLabel(text: Copy.currencyFieldLabel) }) {
                BrandSegmentedPicker(
                    options: ["CNY", "USD"],
                    selection: $currency,
                    label: { Copy.currencyLabel($0) }
                )
                .frame(width: 240)
            }
            FormRow(label: { DialogLabel(text: Copy.cycleFieldLabel) }) {
                BrandSegmentedPicker(
                    options: Copy.cycles,
                    selection: $cycle,
                    label: { Copy.cycleLabel($0) }
                )
                .frame(width: 240)
            }
            VStack(alignment: .leading, spacing: 4) {
                FormRow(label: { DialogLabel(text: Copy.nextChargeDateLabel) }) {
                    DatePicker("", selection: $chargeDate, displayedComponents: .date)
                        .labelsHidden()
                }
                if chargeDateMissing {
                    FormErrorLine(message: Copy.chooseNextChargeDate)
                        .padding(.leading, 100)
                }
            }
            FormHelpLine(message: Copy.newSubscriptionHelp)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    #endif

    // MARK: 移动内容

    private var mobileContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            Card {
                VStack(spacing: 0) {
                    FormRow(label: { DialogLabel(text: Copy.nameLabel) }) {
                        TextFieldRow(placeholder: Copy.subscriptionNamePlaceholder, text: $name)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.amountLabel) }) {
                        AmountFieldRow(symbol: currency == "USD" ? "$" : "¥", text: $amount)
                            .frame(maxWidth: .infinity)
                    }
                    .frame(minHeight: 52)
                    rowSeparator
                    StackedSegmentedRow(
                        title: Copy.currencyFieldLabel,
                        options: ["CNY", "USD"],
                        selection: $currency,
                        label: { Copy.currencyLabel($0) }
                    )
                    .padding(.vertical, 8)
                    rowSeparator
                    StackedSegmentedRow(
                        title: Copy.cycleFieldLabel,
                        options: Copy.cycles,
                        selection: $cycle,
                        label: { Copy.cycleLabel($0) }
                    )
                    .padding(.vertical, 8)
                    rowSeparator
                    FormRow(label: { DialogLabel(text: Copy.nextChargeDateLabel) }) {
                        DatePicker("", selection: $chargeDate, displayedComponents: .date)
                            .labelsHidden()
                    }
                    .frame(minHeight: 52)
                    if chargeDateMissing {
                        FormErrorLine(message: Copy.chooseNextChargeDate)
                            .padding(.bottom, 8)
                    }
                }
                .padding(.horizontal, 16)
            }
            FormHelpLine(message: Copy.newSubscriptionHelp)
            if case .edit(let record) = mode {
                Card {
                    VStack(spacing: 0) {
                        Button {
                            Task {
                                await store.setSubscriptionPaused(id: record.id, paused: record.status == "active")
                                dismiss()
                            }
                        } label: {
                            Text(record.status == "active" ? Copy.pause : Copy.resume)
                                .font(Ds.bodyMedium())
                                .foregroundStyle(Color.brand)
                                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Rectangle().fill(Color.sep).frame(height: 1)
                        Button(role: .destructive) {
                            Task {
                                await store.deleteSubscription(id: record.id)
                                dismiss()
                            }
                        } label: {
                            Text(Copy.deleteSubscription)
                                .font(Ds.bodyMedium())
                                .foregroundStyle(Color.neg)
                                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1)
    }

    // MARK: 保存

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

    private func save() async {
        let day = Fmt.dayString(from: chargeDate)
        chargeDateMissing = false
        let cents: Int64
        do {
            cents = try store.parseCents(amount)
        } catch {
            errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
            return
        }
        let payload = SubscriptionInput(
            name: name,
            amountCents: cents,
            currency: currency,
            cycle: cycle,
            nextChargeOn: day,
            status: status
        )
        do {
            switch mode {
            case .new:
                _ = try await store.createSubscription(payload)
            case .edit(let record):
                guard store.ledger.subscriptions.contains(where: { $0.id == record.id }) else {
                    store.showNotice(Copy.message(for: CoreError.NotFound), isError: true)
                    finishEditing()
                    return
                }
                try await store.updateSubscription(id: record.id, payload)
            }
            errorText = nil
            finishEditing()
        } catch {
            if isMissingDate(error) {
                chargeDateMissing = true
            } else {
                errorText = "\(Copy.notSaved)：\(Copy.message(for: error))"
            }
        }
    }

    /// 核心对缺失日期返回 Validation；预检本地日期兜底。
    private func isMissingDate(_ error: Error) -> Bool {
        error as? CoreError == .Validation && Fmt.date(fromDay: Fmt.dayString(from: chargeDate)) == nil
    }

    private func finishEditing() {
        #if os(macOS)
        router.modal = nil
        #else
        dismiss()
        #endif
    }
}
