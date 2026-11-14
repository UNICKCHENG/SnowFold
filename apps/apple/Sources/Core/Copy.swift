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
        case .RecoveryCodeRequired: return "请先导出恢复码再开启同步"
        case .SyncNotConfigured: return "尚未配置同步存储"
        case .SyncDisabled: return "同步未开启"
        case .SyncNetwork: return "无法连接同步服务，请检查网络与服务地址后重试"
        case .SyncAuthentication: return "同步服务拒绝访问，请检查账号、密钥与权限"
        case .SyncConflict: return "其他设备正在同步，请稍后重试"
        case .SyncRemoteKeyMismatch: return "远端数据无法用本机同步密钥解密，本机与远端都未改动。请确认两台设备导入的是同一个恢复码"
        case .SyncInvalidRemote: return "远端同步数据不完整或服务不支持条件写入，本机数据未改变"
        case .SyncIncompatible: return "远端数据来自更新版本的 SnowFold，请先升级"
        }
    }

    /// 用户提示单独处理安全存储失败；枚举诊断映射保留兼容。
    static let secureKeyUnavailable = "本机密钥已失效，请用恢复码恢复"
    static let keyLocation = "密钥存放在系统钥匙串"

    static func userMessage(for error: Error) -> String {
        if error as? CoreError == .KeyUnavailable { return secureKeyUnavailable }
        return message(for: error)
    }

    // MARK: 启动

    static func localDataUnreadable(_ detail: String) -> String {
        "无法读取本机数据：\(detail)。请检查数据目录权限后重新打开。"
    }

    static func startupBackupFailed(_ detail: String) -> String {
        "启动备份失败：\(detail)。原数据未改变，请检查数据目录权限。"
    }

    static func startupKeyUnavailable(_ detail: String) -> String {
        secureKeyUnavailable
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
    static let aboutOffline = "默认离线运行 · 仅在开启同步后联网"

    // MARK: 同步（docs/copy.md「同步」）

    static let settingsSyncHeader = "同步"
    static let syncHelp = "数据先用本机同步密钥加密再上传，服务器只能看到密文。只在你打开应用或点「立即同步」时联网。"
    static let syncBackendLabel = "存储类型"
    static let syncBackendS3 = "S3 兼容存储"
    static let syncBackendWebDAV = "WebDAV"
    static let syncEndpointLabel = "服务地址"
    static let syncBucketLabel = "存储桶"
    static let syncRegionLabel = "区域"
    static let syncPrefixLabel = "路径前缀（可选）"
    static let syncPathStyleLabel = "使用路径风格地址"
    static let syncAccessKeyLabel = "Access Key ID"
    static let syncSecretKeyLabel = "Secret Access Key"
    static let syncUsernameLabel = "用户名"
    static let syncPasswordLabel = "密码"
    static let syncSecretReenterHelp = "密钥与密码加密保存在本机，不会显示；修改配置时需要重新输入。"
    static let syncSaveConfig = "保存配置"
    static let syncEnable = "开启同步"
    static let syncNow = "立即同步"
    static let syncClearConfig = "清除配置"
    static let syncNeverSynced = "尚未同步"
    static let syncInProgress = "正在同步…"
    static let syncRecoveryRequiredHelp = "开启同步前请先导出恢复码。换机或另一台设备要用同一个恢复码才能解密同步数据。"
    static let syncPairingHelp = "在另一台设备上导入本机的恢复码，并填写相同的存储配置，即可同步同一份数据。"
    static let syncPolicyHeader = "同步策略"
    static let syncOnLaunchLabel = "打开应用时同步"
    static let syncIntervalLabel = "自动同步"
    static let syncIntervalOff = "关闭"
    static let syncInterval15m = "每 15 分钟"
    static let syncInterval30m = "每 30 分钟"
    static let syncInterval1h = "每小时"
    static let syncAfterEditLabel = "修改后自动同步"
    static let syncIntervalHelp = "自动同步只在应用打开时进行，关闭应用后不会在后台联网。"
    static let syncButtonA11y = "立即同步"
    static let syncButtonFailedA11y = "上次同步失败，点击重试"
    static let syncRelativeJustNow = "刚刚"
    static let syncClearConfirmationTitle = "清除同步配置？"
    static let syncClearConfirmationBody = "将删除本机保存的存储地址与凭证，并停止同步。远端数据、本机数据和同步密钥都不受影响。"
    static let syncConfigSaved = "同步配置已保存"
    static let syncEnabled = "同步已开启"
    static let syncDisabled = "同步已关闭"
    static let syncConfigCleared = "同步配置已清除"
    static let syncPendingChanges = "同步期间有新改动，下次同步时上传"
    static let syncBusy = "正在同步或修改配置，请稍后再试"
    static let syncConfigInvalid = "请检查服务地址（须为 https://）、存储桶、区域与凭证"

    static func syncLastSynced(_ time: String) -> String { "上次同步 \(time)" }
    static func syncButtonTooltip(_ time: String) -> String { "立即同步 · 上次同步 \(time)" }
    static func syncRelativeMinutes(_ minutes: Int) -> String { "\(minutes) 分钟前" }
    static func syncCompleted(_ report: SyncReport) -> String {
        "同步完成 · 拉取 \(report.pulledRecords) 条 · 上传 \(report.pushedRecords) 条"
    }
    static func syncMessage(for error: Error, configuration: Bool = false) -> String {
        if error as? CoreError == .Conflict { return syncBusy }
        if configuration, error as? CoreError == .Validation { return syncConfigInvalid }
        return userMessage(for: error)
    }
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
        return "当前指纹 \(currentText)，新指纹 \(new)。\n替换后旧密钥不会保留。请先导出当前恢复码，否则旧密钥导出的加密备份将无法在本机打开。只有确认这个恢复码来自你自己的设备时才继续。"
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
