import SwiftUI

// MARK: - 图标（Asset Catalog 模板图，随文字着色）

/// IconMCP 线条图标（shared/icons/line → ic-*，fill → ic-*-fill）。
struct AppIcon: View {
    let name: String
    var size: CGFloat = 20
    var fill = false

    var body: some View {
        Image(fill ? name + "-fill" : name)
            .resizable()
            .renderingMode(.template)
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
    }
}

/// 分类图标块：28（桌面）/32（移动），图标 16/20，圆角 10。
struct CategoryChip: View {
    let code: String
    var size: CGFloat = Ds.chipSize

    var body: some View {
        AppIcon(name: CategoryStyle.icon(code), size: Ds.chipIcon)
            .foregroundStyle(Color.categoryColor(code))
            .frame(width: size, height: size)
            .background(Color.categorySoft(code), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// 订阅首字头像：32（桌面）/36（移动），圆形。
struct SubscriptionAvatar: View {
    let name: String
    var kind: AvatarKind = .normal

    enum AvatarKind { case normal, danger, muted }

    var body: some View {
        let (bg, fg): (Color, Color) = switch kind {
        case .normal: (Color.brandSoft, Color.brand)
        case .danger: (Color.negSoft, Color.neg)
        case .muted: (Color.bg, Color.text2)
        }
        Text(name.first.map { String($0).uppercased() } ?? "?")
            .font(Ds.bodyMedium())
            .foregroundStyle(fg)
            .frame(width: Ds.avatarSize, height: Ds.avatarSize)
            .background(bg, in: Circle())
            .accessibilityHidden(true)
    }
}

// MARK: - 按钮（§5.1）

/// 主要：brand 底 on-brand 中等。悬停 +8% 亮度，按下 −10%。
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Ds.bodyMedium())
            .padding(.horizontal, 16)
            .frame(minHeight: Ds.buttonHeight)
            .foregroundStyle(enabled ? Color.onBrand : Color.text2)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(enabled ? Color.brand : Color.sep)
                    .overlay {
                        if enabled {
                            Rectangle().fill(configuration.isPressed ? Color.black.opacity(0.10)
                                             : (hovering ? Color.white.opacity(0.08) : .clear))
                        }
                    }
            )
            .onHover { hovering = $0 }
    }
}

/// 次要：card 底 + 1px sep 内描边，text 中等。
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Ds.bodyMedium())
            .padding(.horizontal, 16)
            .frame(minHeight: Ds.buttonHeight)
            .foregroundStyle(enabled ? Color.ink : Color.text2)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.cardBg)
                    .overlay {
                        if enabled && hovering && !configuration.isPressed {
                            Rectangle().fill(Color.ink.opacity(0.04))
                        }
                    }
            )
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.sep, lineWidth: 1))
            .onHover { hovering = $0 }
    }
}

/// 危险：neg 底，浅色白字 / 深 #0E0E10。
struct DangerButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Ds.bodyMedium())
            .padding(.horizontal, 16)
            .frame(minHeight: Ds.buttonHeight)
            .foregroundStyle(Color.onBrand)
            .background {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.neg)
                    .overlay {
                        if enabled {
                            Rectangle().fill(configuration.isPressed ? Color.black.opacity(0.10)
                                             : (hovering ? Color.white.opacity(0.08) : .clear))
                        }
                    }
            }
            .onHover { hovering = $0 }
    }
}

/// 文字按钮：透明底 brand（危险用 neg）。
struct TextButtonStyle: ButtonStyle {
    var danger = false
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Ds.bodyMedium())
            .foregroundStyle(enabled ? (danger ? Color.neg : Color.brand) : Color.text2)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .contentShape(Rectangle())
    }
}

/// 浅色按钮：brand-soft 底 brand 文字（已续费、分类「添加」）。
struct SoftButtonStyle: ButtonStyle {
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Ds.auxMedium())
            .padding(.horizontal, 12)
            .frame(minHeight: Ds.inlineButtonHeight)
            .foregroundStyle(Color.brand)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.brandSoft)
                    .overlay {
                        if hovering && !configuration.isPressed {
                            Rectangle().fill(Color.ink.opacity(0.04))
                        }
                    }
            )
            .contentShape(Rectangle())
            .onHover { hovering = $0 }
    }
}

/// 圆形图标按钮（工具栏「＋」、同步、sheet 头部）：32（桌面）/44（移动）。
struct IconCircleButton: View {
    let icon: String
    var fill = false
    var size: CGFloat = Ds.iconButtonSize
    var background: Color = .cardBg
    var foreground: Color = .ink
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            AppIcon(name: icon, size: size >= 44 ? 22 : 18, fill: fill)
                .foregroundStyle(foreground)
                .frame(width: size, height: size)
                .background(background, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 金额

/// 焦点金额（大标题 40 / 页标题 28）：币种符与小数段降一级、字重中等；
/// 负号「−」用整数段字号紧贴币种符之前；整体等比缩小最低 60%，仍放不下才换行。
struct FocusAmountText: View {
    let cents: Int64
    var currency = "CNY"
    var level: Level = .page
    var color: Color = .ink

    enum Level { case hero, page }

    @ScaledMetric(relativeTo: .largeTitle) private var heroScale: CGFloat = 40
    @ScaledMetric(relativeTo: .title2) private var pageScale: CGFloat = 28

    var body: some View {
        let base = level == .hero ? heroScale : pageScale
        ViewThatFits(in: .horizontal) {
            text(base: base, scale: 1).fixedSize()
            text(base: base, scale: 0.8).fixedSize()
            text(base: base, scale: 0.7).fixedSize()
            text(base: base, scale: 0.6).fixedSize()
            text(base: base, scale: 0.6, wrap: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Fmt.money(cents, currency))
    }

    private func text(base: CGFloat, scale: CGFloat, wrap: Bool = false) -> Text {
        let segments = Fmt.heroSegments(cents, currency)
        let negative = cents < 0
        let decimalColor = negative ? color : Color.text2
        let signedSymbol = Text(segments.sign)
            .font(.system(size: base * scale, weight: .medium))
            .foregroundColor(color)
            + Text(segments.symbol)
            .font(.system(size: base * scale * 0.6, weight: .medium))
            .foregroundColor(color)
        let integer = Text(segments.integer)
            .font(.system(size: base * scale, weight: .semibold))
            .foregroundColor(color)
        let decimal = Text(segments.decimal)
            .font(.system(size: base * scale * 0.6, weight: .medium))
            .foregroundColor(decimalColor)
        return (signedSymbol + integer + decimal).monospacedDigit()
    }
}

/// 行内金额：等宽数字，可指定缩小下限，不截断。
struct MoneyText: View {
    let cents: Int64
    let currency: String
    var font: Font? = nil
    var color: Color = .ink

    var body: some View {
        Text(Fmt.money(cents, currency))
            .font(font ?? Ds.bodyMedium())
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .fixedSize(horizontal: false, vertical: true)
            .foregroundStyle(color)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Fmt.money(cents, currency))
    }
}

// MARK: - 徽标（§5.5）

enum BadgeKind { case danger, warn, neutral, brand }

struct TagBadge: View {
    let text: String
    let kind: BadgeKind

    var body: some View {
        let (fg, bg): (Color, Color?) = switch kind {
        case .danger: (Color.neg, Color.negSoft)
        case .warn: (Color.warn, Color.warnSoft)
        case .brand: (Color.brand, Color.brandSoft)
        case .neutral: (Color.text2, nil)
        }
        Text(text)
            .font(Ds.auxMedium())
            .monospacedDigit()
            .foregroundStyle(fg)
            .padding(.horizontal, 8)
            .frame(minHeight: Ds.isDesktop ? 20 : 24)
            .background {
                if let bg {
                    Capsule().fill(bg)
                }
            }
            .fixedSize()
    }
}

// MARK: - 占比条

/// 4 高占比条：bg 底、分类色填充。
struct PercentBar: View {
    /// 0–100。
    let percent: Double
    let color: Color

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.bg)
                Capsule()
                    .fill(color)
                    .frame(width: proxy.size.width * CGFloat(min(max(percent / 100, 0.005), 1)))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

// MARK: - 卡片与分区

/// 卡片：card 底、圆角 16、无阴影无描边；内容行左右内边距 16。
struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// 分区头：分区标题 + 右侧动作；分组头在卡片外。
struct SectionHeader<Trailing: View>: View {
    let title: String
    var titleColor: Color = .ink
    var count: Int? = nil
    var countColor: Color = .text2
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title).font(Ds.sectionTitle()).foregroundStyle(titleColor)
            if let count {
                Text("\(count)").font(Ds.auxMedium()).monospacedDigit().foregroundStyle(countColor)
            }
            Spacer(minLength: 12)
            trailing()
        }
    }
}

/// 「全部明细 ›」类文字跳转按钮。
struct LinkButton: View {
    let title: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title).font(Ds.auxMedium())
                AppIcon(name: "ic-chevron-right", size: 12)
            }
            .foregroundStyle(Color.brand)
            .contentShape(Rectangle())
        }
        .buttonStyle(TextButtonStyle())
    }
}

// MARK: - 空状态（§5.7）

/// 88 圆形 brand-soft 底 + 48 线条图标 brand；整体居中略偏上。
struct EmptyStateView: View {
    let icon: String
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    var actionIcon: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            AppIcon(name: icon, size: 48)
                .foregroundStyle(Color.brand)
                .frame(width: 88, height: 88)
                .background(Color.brandSoft, in: Circle())
                .padding(.bottom, 16)
            Text(title)
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
                .padding(.bottom, 8)
            if let message {
                Text(message)
                    .font(Ds.body())
                    .foregroundStyle(Color.text2)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 24)
            }
            if let actionTitle, let action {
                Button {
                    action()
                } label: {
                    HStack(spacing: 6) {
                        if let actionIcon {
                            AppIcon(name: actionIcon, size: 16)
                        }
                        Text(actionTitle)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("empty-action")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
        .offset(y: -12)
    }
}

// MARK: - 分段控件（§5.3）

/// 外框 bg 底 + 1px sep、圆角 10、内边距 2；选中段 brand 底 on-brand 中等；段宽均分。
struct BrandSegmentedPicker<Option: Hashable>: View {
    let options: [Option]
    @Binding var selection: Option
    var label: (Option) -> String
    var identifier: (Option) -> String = { _ in "" }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.self) { option in
                let selected = option == selection
                Button {
                    selection = option
                } label: {
                    Text(label(option))
                        .font(Ds.auxMedium())
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, 10)
                        .frame(maxWidth: .infinity, minHeight: Ds.segmentHeight - 4)
                        .foregroundStyle(selected ? Color.onBrand : Color.text2)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(Color.brand)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
                .accessibilityIdentifier(identifier(option))
            }
        }
        .padding(2)
        .frame(minHeight: Ds.segmentHeight)
        .background(Color.bg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
            .strokeBorder(Color.sep, lineWidth: 1))
        .animation(.easeOut(duration: 0.15), value: selection)
    }
}

// MARK: - 滑动动作行（移动端 ScrollView 内的左滑动作）

/// 左滑露出动作（各宽 88，图标 20 + 辅助中等文字）；一滑到底保持全开。
struct SwipeActionSpec {
    let icon: String
    let title: String
    var tint: Color = .neg
    let perform: () -> Void
}

struct SwipeableRow<Content: View>: View {
    let specs: [SwipeActionSpec]
    @ViewBuilder var content: () -> Content
    @State private var offset: CGFloat = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ZStack(alignment: .trailing) {
            HStack(spacing: 0) {
                ForEach(Array(specs.enumerated()), id: \.title) { _, spec in
                    Button {
                        reset()
                        spec.perform()
                    } label: {
                        VStack(spacing: 4) {
                            AppIcon(name: spec.icon, size: 20)
                            Text(spec.title)
                                .font(Ds.auxMedium())
                        }
                        .foregroundStyle(Color.onBrand)
                        .frame(width: 88, height: 64)
                        .background(spec.tint)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(spec.title)
                }
            }
            content()
                .offset(x: offset)
                .simultaneousGesture(swipe)
                .background(
                    // 点击行其他位置或滚动后收起。
                    Color.clear.contentShape(Rectangle())
                        .onTapGesture { reset() }
                )
        }
        .clipped()
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 20, coordinateSpace: .local)
            .onChanged { value in
                guard value.translation.width < 0 else { return }
                offset = min(0, max(-CGFloat(specs.count * 88 + 44), value.translation.width))
            }
            .onEnded { value in
                let width = value.translation.width
                withAnimation(.easeOut(duration: 0.2)) {
                    if width < -160 {
                        offset = -CGFloat(specs.count * 88) // 一滑到底保持全开
                    } else if width < -56 {
                        offset = -88
                    } else {
                        offset = 0
                    }
                }
            }
    }

    private func reset() {
        withAnimation(.easeOut(duration: 0.2)) { offset = 0 }
    }
}

// MARK: - 表单行与输入（§5.2）

/// 输入框：card 底、1px sep、圆角 10、左右 10；占位符 text2。
struct DSInputStyle: ViewModifier {
    @FocusState.Binding var focused: Bool
    var hasError = false

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 10)
            .frame(minHeight: Ds.fieldHeight)
            .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(hasError ? Color.neg : (focused ? Color.brand : Color.sep),
                                  lineWidth: focused || hasError ? 1.5 : 1)
            )
    }
}

/// 表单行：标签（左）+ 控件（右）；AX 大字号上下排列。
struct FormRow<Label: View, Field: View>: View {
    @ViewBuilder var label: () -> Label
    @ViewBuilder var field: () -> Field
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        layout {
            label().font(Ds.body())
            if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
            field()
        }
    }
}

/// 只读值：无框，text 色，等宽数字。
struct ReadOnlyValue: View {
    let text: String
    var monospaced = true

    var body: some View {
        Text(text)
            .font(Ds.body())
            .monospacedDigit()
            .foregroundStyle(Color.ink)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// 出错/说明行：warning-triangle 16 + 辅助 neg。
struct FormErrorLine: View {
    let message: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            AppIcon(name: "ic-warning-triangle", size: 16)
            Text(message)
                .font(Ds.aux())
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Color.neg)
    }
}

/// 说明行：辅助 text2。
struct FormHelpLine: View {
    let message: String

    var body: some View {
        Text(message)
            .font(Ds.aux())
            .foregroundStyle(Color.text2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - 横幅与通知（§5.8）

/// 撤销横幅：浮层材质胶囊，trash + 消息 + 「撤销 · 6秒内」（undo 图标）；
/// 6 秒自动消失；不画倒计时条。可下滑关闭。
struct UndoBanner: View {
    let state: UndoState
    @Environment(SnowFoldStore.self) private var store
    @GestureState private var dragOffset: CGFloat = 0

    var body: some View {
        HStack(spacing: 12) {
            AppIcon(name: "ic-trash", size: 16)
                .foregroundStyle(Color.text2)
            Text(state.message)
                .font(Ds.bodyMedium())
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button {
                Task { await store.undoNow() }
            } label: {
                HStack(spacing: 4) {
                    AppIcon(name: "ic-undo", size: 16)
                    Text(Copy.undoButton)
                }
                .font(Ds.bodyMedium())
                .foregroundStyle(Color.brand)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(store.isWorking)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(minHeight: Ds.isDesktop ? 44 : 52)
        .glassEffect(in: Capsule(style: .continuous))
        .shadow(color: .black.opacity(0.14), radius: 16, y: 8)
        .offset(y: dragOffset)
        .gesture(DragGesture(minimumDistance: 12)
            .updating($dragOffset) { value, offset, _ in offset = max(0, value.translation.height) }
            .onEnded { value in
                if value.translation.height > 28 { store.invalidateUndo() }
            })
        .accessibilityAction(named: Text(Copy.close)) { store.invalidateUndo() }
    }
}

/// 通知条：check（成功）或 warning-triangle（错误）+ 消息 + 关闭按钮。
struct NoticeToast: View {
    let message: String
    var isError = false
    @Environment(SnowFoldStore.self) private var store

    var body: some View {
        HStack(spacing: 12) {
            AppIcon(name: isError ? "ic-warning-triangle" : "ic-check", size: 16)
                .foregroundStyle(isError ? Color.neg : Color.pos)
            Text(message)
                .font(Ds.bodyMedium())
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            Spacer(minLength: 8)
            Button {
                store.dismissNotice()
            } label: {
                AppIcon(name: "ic-close", size: 16)
                    .foregroundStyle(Color.text2)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Copy.close)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(minHeight: Ds.isDesktop ? 44 : 52)
        .glassEffect(in: Capsule(style: .continuous))
        .shadow(color: .black.opacity(0.14), radius: 16, y: 8)
    }
}

/// 两类横幅统一挂在页面底部：通知在上，撤销横幅在下。
struct BannerOverlay: View {
    @Environment(SnowFoldStore.self) private var store

    var body: some View {
        VStack(spacing: 8) {
            if let notice = store.notice {
                NoticeToast(message: notice, isError: store.noticeIsError)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if let undo = store.undoState {
                UndoBanner(state: undo)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .animation(.spring(duration: 0.3), value: store.notice)
        .animation(.spring(duration: 0.3), value: store.noticeIsError)
        .animation(.spring(duration: 0.3), value: store.undoState)
    }
}

// MARK: - 统计口径

/// info 16 图标按钮：popover 呈现口径说明（读屏名带指标名）。
struct HelpInfoButton: View {
    let metricName: String
    let help: String
    @State private var showing = false

    var body: some View {
        Button {
            showing = true
        } label: {
            AppIcon(name: "ic-info", size: 16)
                .foregroundStyle(Color.text2)
                .frame(width: 24, height: 24)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(metricName)口径说明")
        .popover(isPresented: $showing, arrowEdge: .bottom) {
            Text(help)
                .font(Ds.body())
                .padding(12)
                .frame(maxWidth: 280, alignment: .leading)
                .presentationCompactAdaptation(.popover)
        }
    }
}
