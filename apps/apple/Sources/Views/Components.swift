import SwiftUI

/// 桌面同款墨色主操作，保留系统字体、禁用状态与最小命中区。
struct InkButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16)
            #if os(macOS)
            .padding(.vertical, 8)
            #else
            .padding(.vertical, 12)
            #endif
            .foregroundStyle(Color.canvas)
            .background(Color.ink, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .opacity(enabled ? (configuration.isPressed ? 0.78 : 1) : 0.4)
    }
}

// MARK: - Hero 金额

/// 净资产 hero：币种符号与小数小一号，负号独立；动态字体随辅助功能档位缩放。
struct HeroAmountText: View {
    let cents: Int64
    var color: Color = .ink
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 44

    var body: some View {
        ViewThatFits(in: .horizontal) {
            amountText(scale: 1).fixedSize()
            amountText(scale: 0.85).fixedSize()
            amountText(scale: 0.7).fixedSize()
            amountText(scale: 0.6).fixedSize()
            amountText(scale: 0.5).fixedSize()
            amountText(scale: 0.5).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentTransition(.numericText())
        .animation(.default, value: cents)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Fmt.money(cents, "CNY"))
        .accessibilityIdentifier("hero-amount")
    }

    private func amountText(scale: CGFloat) -> Text {
        let segments = Fmt.heroSegments(cents, "CNY")
        let fittedSize = size * scale
        // 单个富文本先整体缩至 60%，再换行；不允许任何分段独自省略。
        return (Text(segments.sign + segments.symbol)
            .font(.system(size: fittedSize * 0.6, weight: .medium, design: .rounded))
            .foregroundColor(color)
         + Text(segments.integer)
            .font(.system(size: fittedSize, weight: .bold, design: .rounded))
            .foregroundColor(color)
         + Text(segments.decimal)
            .font(.system(size: fittedSize * 0.6, weight: .medium, design: .rounded))
            .foregroundColor(.inkMuted)).monospacedDigit()
    }

}

/// 行内金额：等宽数字，右对齐，超长缩小不换行。
struct MoneyText: View {
    let cents: Int64
    let currency: String
    var font: Font = .body.weight(.medium)
    var color: Color = .ink

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Text(Fmt.money(cents, currency))
            .font(typeSize.isAccessibilitySize ? Font.caption.weight(.medium) : font)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .fixedSize(horizontal: false, vertical: true)
            .foregroundStyle(color)
            .contentTransition(.numericText())
            .animation(.default, value: cents)
    }
}

// MARK: - 徽标

enum TagKind { case danger, accent, neutral }

struct TagBadge: View {
    let text: String
    let kind: Fmt.BadgeKind

    private var foreground: Color {
        switch kind {
        case .danger: return .negativeBrand
        case .accent: return .accentBrand
        case .neutral: return .inkMuted
        }
    }

    private var background: Color {
        switch kind {
        case .danger: return .negativeSoft
        case .accent: return .accentSoft
        case .neutral: return .tabsBg
        }
    }

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .monospacedDigit()
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(background, in: Capsule())
            .foregroundStyle(foreground)
            .fixedSize()
    }
}

// MARK: - 分类图标

struct CategoryChip: View {
    let code: String
    var size: CGFloat = 28

    var body: some View {
        let tint = CategoryStyle.tint(code)
        Image(systemName: CategoryStyle.icon(code))
            .font(.system(size: size * 0.52, weight: .medium))
            .foregroundStyle(tint.glyph)
            .frame(width: size, height: size)
            .background(tint.chip, in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: - 占比条

struct ShareBar: View {
    /// 0-100。
    let percent: Double
    var tint: Color = .accentBrand

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.hairline)
                Capsule()
                    .fill(tint)
                    .frame(width: proxy.size.width * CGFloat(min(max(percent / 100, 0), 1)))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

// MARK: - 统计块（订阅页四格）

struct StatTile<Value: View>: View {
    let title: String
    let help: String
    @ViewBuilder var value: Value

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(Color.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)
                HelpButton(help: help)
                Spacer(minLength: 0)
            }
            value
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 「?」统计口径说明按钮：popover 呈现，不挡正文。
struct HelpButton: View {
    let help: String
    @State private var showing = false

    var body: some View {
        Button {
            showing = true
        } label: {
            Image(systemName: "questionmark.circle")
                .foregroundStyle(.tertiary)
        }
        .buttonStyle(.plain)
        .font(.caption)
        .accessibilityLabel("统计口径")
        .accessibilityHint(help)
        .popover(isPresented: $showing, arrowEdge: .bottom) {
            Text(help)
                .font(.footnote)
                .padding(12)
                .frame(maxWidth: 280, alignment: .leading)
                .presentationCompactAdaptation(.popover)
        }
    }
}

// MARK: - 空状态

struct EmptyStateView: View {
    let icon: String
    let title: String
    var message: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 48, weight: .light))
                .frame(width: 64, height: 64)
                .foregroundStyle(Color.ink3)
                .padding(.bottom, 2)
            Text(title)
                .font(.headline)
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(Color.inkMuted)
                    .multilineTextAlignment(.center)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(InkButtonStyle())
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 44)
        .padding(.horizontal, 20)
    }
}

// MARK: - 底部横幅（撤销 + 通知）

/// 撤销横幅：非阻塞浮层，6 秒倒计时进度随票据 deadline。
struct UndoBanner: View {
    let state: UndoState
    @Environment(SnowFoldStore.self) private var store
    @GestureState private var dragOffset: CGFloat = 0

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.12)) { context in
            let total = 6.0
            let remaining = max(0, state.deadline.timeIntervalSince(context.date))
            let progress = total > 0 ? remaining / total : 0
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Text(state.message)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Button {
                        Task { await store.undoNow() }
                    } label: {
                        Text(Copy.undoButton)
                            .font(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(InkButtonStyle())
                    .controlSize(.small)
                    .disabled(store.isWorking)
                }
                .padding(.horizontal, 14)
                .padding(.top, 11)
                .padding(.bottom, 9)
                GeometryReader { proxy in
                    HStack {
                        Capsule()
                            .fill(Color.accentBrand.opacity(0.8))
                            .frame(width: proxy.size.width * CGFloat(progress))
                            .animation(.linear(duration: 0.12), value: progress)
                        Spacer(minLength: 0)
                    }
                }
                .frame(height: 3)
            }
            .background(Color.cardSurface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.hairline, lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.10), radius: 10, y: 4)
        }
        .offset(y: dragOffset)
        .gesture(DragGesture(minimumDistance: 12)
            .updating($dragOffset) { value, offset, _ in offset = max(0, value.translation.height) }
            .onEnded { value in
                if value.translation.height > 28 { store.invalidateUndo() }
            })
        .accessibilityAction(named: Text("关闭撤销提示")) { store.invalidateUndo() }
    }
}

/// 通知条（备份完成、顺延成功等）：手动关闭，不自动消失。
struct NoticeToast: View {
    let message: String
    @Environment(SnowFoldStore.self) private var store

    var body: some View {
        HStack(spacing: 10) {
            Text(message)
                .font(.footnote.weight(.medium))
                .lineLimit(3)
                .textSelection(.enabled)
            Button {
                store.dismissNotice()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.inkMuted)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Copy.close)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.cardSurface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.hairline, lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.08), radius: 8, y: 3)
    }
}

/// 两类横幅统一挂在页面底部：撤销横幅在下，通知在上。
struct BannerOverlay: View {
    @Environment(SnowFoldStore.self) private var store

    var body: some View {
        VStack(spacing: 8) {
            if let notice = store.notice {
                NoticeToast(message: notice)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if let undo = store.undoState {
                UndoBanner(state: undo)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
        .animation(.spring(duration: 0.3), value: store.notice)
        .animation(.spring(duration: 0.3), value: store.undoState)
        .animation(.spring(duration: 0.3), value: store.undoState?.deadline)
    }
}

// MARK: - 分区头

struct SectionHeader<Trailing: View>: View {
    let title: String
    var count: Int?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.ink3)
            if let count {
                Text("\(count)")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(Color.ink3)
            }
            Spacer(minLength: 8)
            trailing()
        }
    }
}

// MARK: - 溢出/错误面板

struct InlineErrorPanel: View {
    let message: String

    var body: some View {
        Label {
            Text(message)
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
        }
        .foregroundStyle(.negativeBrand)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.negativeBrand.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
