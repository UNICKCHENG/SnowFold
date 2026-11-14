import Foundation

/// 全部用户可见文案与 CoreError → 中文提示的映射。
/// 来源：docs/copy.md（冻结）与事实板「文案修正」；宿主按错误枚举映射，不使用 Rust Display。
enum Copy {
    // MARK: 错误映射

    static func message(for error: Error) -> String {
        guard let coreError = error as? CoreError else { return message(for: .Internal) }
        return message(for: coreError)
    }

    static func message(for error: CoreError) -> String {
        switch error {
        case .Validation: return "请检查名称、金额、日期与选项"
        case .NotFound: return "记录不存在，请刷新后重试"
        case .Conflict: return "数据已变化或正在使用，请刷新后重试"
        case .Authentication: return "无法解密：同步密钥不匹配或文件已损坏"
        case .IncompatibleBackup: return "不支持的备份版本或加密格式"
        case .IncompatibleDatabase: return "数据库版本不兼容，原文件未改动"
        case .InvalidBackup: return "备份内容不完整或不合法，原数据未改变"
        case .InvalidRecoveryCode: return "恢复码不合法，本机密钥未改变"
        case .KeyUnavailable: return "同步密钥不可用，请检查密钥文件或导入恢复码"
        case .Io: return "无法读写文件，请检查目录与权限"
        case .Database: return "无法读写本机数据库"
        case .Overflow: return "金额合计超出整数范围，请检查数据"
        case .Internal: return "核心状态不可用，请重新打开"
        }
    }

    // MARK: 启动

    static func localDataUnreadable(_ detail: String) -> String {
        "无法读取本机数据：\(detail)。请检查数据目录权限后重新打开。"
    }

    static func startupBackupFailed(_ detail: String) -> String {
        "启动备份失败：\(detail)。原数据未改变，请检查数据目录权限。"
    }

    static func startupKeyUnavailable(_ detail: String) -> String {
        "同步密钥不可用：\(detail)。加密备份暂时无法导出或导入；可以在设置里导入恢复码，或处理密钥文件后重启。"
    }

    // MARK: 页面与统计

    static let overviewTitle = "总览"
    static let assetsTitle = "资产与负债"
    static let subscriptionsTitle = "订阅"
    static let settingsTitle = "设置"
    static let newSubscriptionTitle = "新建订阅"
    static let settingsThemeHeader = "外观"
    static let settingsDataHeader = "数据"
    static let settingsEncryptedHeader = "加密备份"
    static let settingsAboutHeader = "关于"
    static let netAssets = "净资产"
    static let totalAssets = "总资产"
    static let totalDebt = "总负债"
    static let debtRatio = "负债率"
    static let assetComposition = "资产构成"
    static let allDetails = "全部明细"
    static let upcomingCharges = "即将扣费"
    static let allSubscriptions = "全部订阅"
    static let dueThisMonth = "本月待扣"
    static let monthlySpending = "月均支出"
    static let annualSpending = "年化支出"
    // 事实板「文案修正」：年化＝按扣费周期折算为全年支出；月均＝年化合计 ÷ 12 后舍入。
    static let dueThisMonthHelp = "在用订阅中，下次扣费日落在当前自然月的金额合计（含本月已过期、待确认的）"
    static let monthlySpendingHelp = "按扣费周期折算为全年支出后除以 12 舍入到分"
    static let annualSpendingHelp = "按扣费周期折算为全年支出"
    static let nextCharge = "下一个扣费"
    /// 「接下来」卡里的第二币种前缀：另有 $25.80（docs/mobile-layout.md §2.2）
    static let amountAdditional = "另有"
    static let historyTitle = "变动记录 · 修正不会新增快照"
    static let historyCurrent = "当前"
    static let historyInitial = "初始"

    static func overviewSubtitle(_ dateText: String, weekday: String) -> String {
        "\(dateText) 星期\(weekday) · 数据已保存在本机"
    }

    static func assetsSubtitle(_ count: Int) -> String {
        "\(count) 项记录 · 5 个固定分类"
    }

    static func subscriptionsSubtitle(active: Int, paused: Int) -> String {
        paused > 0 ? "\(active) 个在订 · \(paused) 个已暂停" : "\(active) 个在订"
    }

    /// 总览订阅摘要：逾期优先，其次下一笔，最后仅数量。
    static func overviewSummary(active: Int, overdue: Int, nextDate: String?) -> String {
        if active == 0 { return "暂无扣费" }
        if overdue > 0 { return "\(active) 个订阅 · \(overdue) 笔待确认" }
        if let nextDate { return "\(active) 个订阅 · 下一笔 \(nextDate)" }
        return "\(active) 个订阅"
    }

    // MARK: 分类、选项与周期

    static let categoryNames: [String: String] = [
        "cash": "流动资金",
        "fixed": "固定资产",
        "investment": "投资理财",
        "receivable": "应收款",
        "debt": "负债",
    ]
    static let categoryOrder = ["cash", "fixed", "investment", "receivable", "debt"]

    static func categoryName(_ code: String) -> String {
        categoryNames[code] ?? code
    }

    static let currencyCNY = "¥ 人民币"
    static let currencyUSD = "$ 美元"
    static func currencyLabel(_ code: String) -> String {
        code == "USD" ? currencyUSD : currencyCNY
    }

    static func cycleLabel(_ cycle: String) -> String {
        switch cycle {
        case "weekly": return "每周"
        case "monthly": return "每月"
        case "quarterly": return "每季"
        case "yearly": return "每年"
        default: return "未知周期"
        }
    }
    static let cycles = ["weekly", "monthly", "quarterly", "yearly"]

    static let groupNames = ["待确认", "本月", "下月", "之后", "已暂停"]
    static let themeSystem = "自动"
    static let themeLight = "浅色"
    static let themeDark = "深色"

    static func themeLabel(_ theme: ThemePreference) -> String {
        switch theme {
        case .system: return themeSystem
        case .light: return themeLight
        case .dark: return themeDark
        }
    }

    // MARK: 按钮

    static let done = "完成"
    static let cancel = "取消"
    static let save = "保存"
    static let close = "关闭"
    static let add = "添加"
    static let newSubscription = "＋ 新建订阅"
    static let correctHistory = "修正"
    static let markRenewed = "✓ 已续费"
    static let pause = "暂停"
    static let resume = "恢复"
    static let undoButton = "撤销 · 6秒内"
    static let exportButton = "导出"
    static let importButton = "导入"
    static let clearButton = "清空"
    static let exportSealedButton = "导出密文"
    static let importSealedButton = "导入密文"
    static let exportRecoveryButton = "导出恢复码"
    static let importRecoveryButton = "导入恢复码"
    static let confirmOverwrite = "确认覆盖"
    static let confirmReplaceKey = "确认替换"
    static let rename = "重命名"
    static let adjustBalance = "调整余额"
    static let editAction = "编辑"

    // MARK: 空状态与设置说明

    static let noAssetRecords = "还没有记录，点击右侧「添加」记下第一笔"
    static let compositionEmpty = "暂无资产"
    static let noSubscriptions = "还没有订阅"
    static let noSubscriptionsHelp = "记下常用服务，随时知道接下来要扣多少钱"
    static let noCharges = "暂无扣费"
    static let noUpcomingCharges = "未来 30 天没有待扣费用"
    static let themeHelp = "跟随系统会自动匹配电脑的深浅色。"
    static let exportBackupHelp = "保存为 JSON 文件，可随时导入恢复。"
    static let importBackupHelp = "会覆盖当前所有记录，请先导出备份。"
    static let clearAllHelp = "删除本机上的全部资产与订阅记录。"
    static let encryptedBackupHelp = "导出 .sfseal 密文，可安全上传网盘或发给家人。"
    static let restoreEncryptedBackupHelp = "导入密文并覆盖当前数据；需要本机同步密钥。"
    static let keyFingerprintLabel = "同步密钥指纹"
    static let keyNotGenerated = "尚未生成"
    static let recoveryCodeHelp = "换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。"
    static let aboutVersion = "SnowFold · MVP 0.1"
    static let aboutOffline = "离线运行 · 无网络请求"
    static let emptySummary = "—"

    // MARK: 确认弹层

    static let clearConfirmationTitle = "清空全部数据？"
    static let clearConfirmationBody = "将删除全部资产、历史与订阅，不能撤销。请先导出备份。"
    static let importConfirmationTitle = "覆盖本机数据？"
    static func importConfirmationBody(assets: UInt64, history: UInt64, subscriptions: UInt64) -> String {
        "将导入 \(assets) 项资产、\(history) 条历史、\(subscriptions) 项订阅，并覆盖现有数据。此操作不能撤销。"
    }
    static func sealedImportConfirmationBody(info: SealedBackupInfo) -> String {
        "加密备份导出于 \(info.exportedAtDisplay)（设备 \(info.deviceId)），校验通过：\(info.counts.assets) 项资产、\(info.counts.history) 条历史、\(info.counts.subscriptions) 项订阅。\n确认后会覆盖现有数据，此操作不能撤销；如果导出时间比当前数据旧，请先取消并核对。"
    }
    static let replaceKeyConfirmationTitle = "替换本机同步密钥？"
    static func replaceKeyConfirmationBody(current: String?, new: String) -> String {
        let currentText = current ?? keyNotGenerated
        return "当前指纹 \(currentText)，新指纹 \(new)。\n替换后，旧密钥导出的加密备份将无法在本机打开；旧密钥会备份为 snowfold-sync.key.bak。只有确认这个恢复码来自你自己的设备时才继续。"
    }

    // MARK: 反馈

    static let balanceRecorded = "已记录本次变动"
    static let assetDeleted = "已删除资产"
    static let subscriptionDeleted = "已删除订阅"
    static let dataUpdated = "数据已更新"
    static let backupExported = "备份已导出"
    static let databaseUnavailable = "数据库不可用"
    static let chooseDate = "请选择日期"
    static let savedButReloadFailed = "已保存，但读取失败"
    static let notSaved = "未保存"
    static func renewed(_ periods: UInt32) -> String { "已顺延 \(periods) 期" }
    static func keyImported(_ fingerprint: String) -> String { "同步密钥已导入 · 指纹 \(fingerprint)" }
    static func keyReplaced(_ fingerprint: String) -> String { "同步密钥已替换 · 指纹 \(fingerprint)（旧密钥已备份为 .bak）" }
    static func sealedExported(_ fingerprint: String) -> String { "加密备份已导出 · 密钥指纹 \(fingerprint)" }
    static func recoveryExported(_ fingerprint: String) -> String { "恢复码已导出 · 密钥指纹 \(fingerprint)" }
    static func exportFailed(_ detail: String) -> String { "导出失败：\(detail)" }
    static func importFailedUnchanged(_ detail: String) -> String { "导入失败，原数据未改变：\(detail)" }
    static func sealedExportFailed(_ detail: String) -> String { "加密备份导出失败：\(detail)" }
    static func sealedImportFailedUnchanged(_ detail: String) -> String { "加密备份导入失败，原数据未改变：\(detail)" }
    static func recoveryExportFailed(_ detail: String) -> String { "恢复码导出失败：\(detail)" }
    static func recoveryImportFailedUnchanged(_ detail: String) -> String { "恢复码导入失败，本机密钥未改变：\(detail)" }
    static let keyChangedElsewhere = "本机同步密钥已被其他窗口修改，请重新导入恢复码"
    static let assetTotalOverflow = "金额合计超出整数范围，请检查资产数据"
    static let outOfRange = "超出范围"
    static let totalOutOfRange = "合计超出范围"
    static let renewalNotDue = "下次扣费日尚未到达，无需顺延"
    static let operationFailed = "操作失败"
    static func operationFailedDetail(_ detail: String) -> String { "操作失败：\(detail)。请检查输入或数据目录后重试。" }
    static let exportTargetProtected = "不能覆盖 SnowFold 自己的数据文件，请换一个保存位置"

    // MARK: 订阅编辑说明（事实板「文案修正」）

    static let newSubscriptionHelp =
        "名称与金额必填。保存后按周期计算下次扣费；到期确认续费后按周期顺延。"

    // MARK: 辅助功能

    static func a11yAsset(_ name: String, category: String, amount: String, historyCount: Int) -> String {
        "\(category)，\(name)，金额 \(amount)，\(historyCount) 条变动记录"
    }
    static func a11ySubscription(_ name: String, amount: String, cycle: String, date: String, status: String) -> String {
        "\(name)，\(amount)，\(cycle)，下次扣费 \(date)，\(status)"
    }
}
