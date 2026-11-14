import Foundation

/// 显示格式化：金额一律走核心；日期按 copy.md 的短/长格式；历史时刻转本地可读。
enum Fmt {
    // MARK: 金额

    /// `¥12,345.00`，负数用 `−` 前缀（与桌面端 format_money 一致）。
    static func money(_ cents: Int64, _ currency: String) -> String {
        (try? formatMoney(amountCents: cents, currency: currency)) ?? String(cents)
    }

    /// 编辑框预填用的纯数字文本 `12345.67`。
    static func decimal(_ cents: Int64) -> String {
        (try? formatAmount(amountCents: cents)) ?? String(cents)
    }

    /// hero 金额拆段：负号与币种符号小号、整数大号、小数中号。
    static func heroSegments(_ cents: Int64, _ currency: String) -> (sign: String, symbol: String, integer: String, decimal: String) {
        let text = money(cents, currency)
        var sign = ""
        var rest = Substring(text)
        if rest.hasPrefix("−") {
            sign = "−"
            rest = rest.dropFirst()
        }
        let symbol = String(rest.removeFirst())
        if let dot = rest.firstIndex(of: ".") {
            return (sign, symbol, String(rest[..<dot]), String(rest[dot...]))
        }
        return (sign, symbol, String(rest), "")
    }

    // MARK: 自然日

    /// 宿主当前本地自然日，`YYYY-MM-DD`（传给核心 `overview(on:)` 等）。
    static var today: String {
        dayString(from: Date())
    }

    static func dayString(from date: Date) -> String {
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", comps.year ?? 1, comps.month ?? 1, comps.day ?? 1)
    }

    /// `2026-11-14` → 本年 `11月17日`，跨年 `2025年11月17日`。
    static func day(_ ymd: String, relativeTo todayText: String = Fmt.today) -> String {
        let parts = ymd.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return ymd }
        let todayParts = todayText.split(separator: "-").compactMap { Int($0) }
        if parts.count == 3, todayParts.count == 3, parts[0] == todayParts[0] {
            return "\(parts[1])月\(parts[2])日"
        }
        return "\(parts[0])年\(parts[1])月\(parts[2])日"
    }

    /// `2026-11-14` → 本机日历 Date（DatePicker 编辑用）。
    static func date(fromDay ymd: String) -> Date? {
        let parts = ymd.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var comps = DateComponents()
        comps.year = parts[0]
        comps.month = parts[1]
        comps.day = parts[2]
        return Calendar.current.date(from: comps)
    }

    // MARK: 时刻

    /// RFC3339（九位小数 UTC）→ `11月14日 13:45`，跨年带年份。
    static func timestamp(_ rfc3339: String, relativeTo todayText: String = Fmt.today) -> String {
        guard let date = parseRFC3339(rfc3339) else { return rfc3339 }
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        guard let year = comps.year, let month = comps.month, let day = comps.day,
              let hour = comps.hour, let minute = comps.minute else { return rfc3339 }
        let todayParts = todayText.split(separator: "-").compactMap { Int($0) }
        let yearPrefix = todayParts.first != year ? "\(year)年" : ""
        return "\(yearPrefix)\(month)月\(day)日 \(String(format: "%02d:%02d", hour, minute))"
    }

    private static func parseRFC3339(_ text: String) -> Date? {
        let base = text.split(separator: ".")[0]
        let trimmed = base.hasSuffix("Z") ? String(base.dropLast()) : String(base)
        var comps = DateComponents()
        let datePart = trimmed.split(separator: "T")
        guard datePart.count == 2 else { return nil }
        let ymd = datePart[0].split(separator: "-").compactMap { Int($0) }
        let hms = datePart[1].split(separator: ":").compactMap { Int($0) }
        guard ymd.count == 3, hms.count == 3 else { return nil }
        comps.year = ymd[0]; comps.month = ymd[1]; comps.day = ymd[2]
        comps.hour = hms[0]; comps.minute = hms[1]; comps.second = hms[2]
        // 核心统一写 UTC；展示按本机时区。
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        let utcDate = calendar.date(from: comps)
        guard let utcDate else { return nil }
        return utcDate
    }

    // MARK: 统计

    static func weekday(of date: Date = Date()) -> String {
        let names = ["日", "一", "二", "三", "四", "五", "六"]
        let index = Calendar.current.component(.weekday, from: date) - 1
        return names[max(0, min(6, index))]
    }

    static func dateLong(from date: Date = Date()) -> String {
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return "\(comps.year ?? 1)年\(comps.month ?? 1)月\(comps.day ?? 1)日"
    }

    // MARK: 订阅徽标

    /// 总览即将扣费徽标：逾期 N 天 / 今天 / N 天后。
    static func chargeBadge(for ymd: String, today: String) -> (text: String, kind: BadgeKind) {
        guard let days = daysBetween(today, ymd) else { return ("", .neutral) }
        if days < 0 { return ("逾期 \(-days) 天", .danger) }
        if days == 0 { return ("今天", .danger) }
        if days <= 7 { return ("\(days) 天后", .accent) }
        return ("\(days) 天后", .neutral)
    }

    /// 订阅分组行内提示：逾期{days}天。
    static func overdueCompact(_ ymd: String, today: String) -> String {
        guard let days = daysBetween(today, ymd) else { return "" }
        return "逾期\(-days)天"
    }

    enum BadgeKind { case danger, accent, neutral }

    /// 两个 YYYY-MM-DD 的天数差（to - from）。
    static func daysBetween(_ from: String, _ to: String) -> Int? {
        guard let fromDate = date(fromDay: from), let toDate = date(fromDay: to) else { return nil }
        let days = Calendar.current.dateComponents([.day], from: fromDate, to: toDate).day
        return days
    }
}
