import Foundation

// 截图样例数据生成：`sample-seed <数据目录>`。
// 只经核心 API 写入，与真实数据完全同构；不含界面外的任何格式。
let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    print("usage: sample-seed <数据目录>")
    exit(2)
}
let dataDirectory = arguments[1]

func dayString(_ date: Date) -> String {
    let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
    return String(format: "%04d-%02d-%02d", comps.year!, comps.month!, comps.day!)
}

func shift(days: Int = 0, months: Int = 0) -> String {
    var date = Date()
    if months != 0 {
        date = Calendar.current.date(byAdding: .month, value: months, to: date)!
    }
    if days != 0 {
        date = Calendar.current.date(byAdding: .day, value: days, to: date)!
    }
    return dayString(date)
}

let core = try SnowFoldCore.open(dataDir: dataDirectory, keyStore: KeychainKeyStore())

// 主金额回归用：只有这一项资产，净资产恰为 ¥12,345,678.90。
if arguments.contains("--hero") {
    _ = try core.createAsset(categoryCode: "cash", assetName: "招商银行朝朝宝（活期理财）家庭备用金账户", amountCents: 1_234_567_890)
    print("hero regression seeded")
    exit(0)
}

// 资产（5 分类共 15 条；金额走核心解析口径，直接整数分）。
let assetSpecs: [(String, String, Int64)] = [
    ("cash", "招商银行朝朝宝（活期理财）家庭备用金账户", 1_234_567_890),
    ("cash", "微信钱包零钱与零钱通", 320_845),
    ("cash", "支付宝余额宝", 105_820),
    ("cash", "工商银行工资卡活期", 1_864_275),
    ("cash", "现金备用（家里抽屉）", 50_000),
    ("fixed", "家里一辆代步小车（2019 款自动挡）", 7_800_000),
    ("fixed", "MacBook Pro 14 英寸工作机", 1_899_900),
    ("investment", "华泰证券 A 股账户", 15_620_455),
    ("investment", "天弘余额宝货币基金", 4_587_611),
    ("investment", "黄金积存存折（按克计价）", 2_300_000),
    ("receivable", "借给同事小王的装修周转款", 2_000_000),
    ("receivable", "朋友圈借款（老李，婚宴用）", 500_000),
    ("debt", "房贷（招商银行 30 年等额本息）", 128_654_022),
    ("debt", "花呗本期待还", 128_640),
    ("debt", "车贷剩余本金", 4_567_890),
]
for (category, name, cents) in assetSpecs {
    _ = try core.createAsset(categoryCode: category, assetName: name, amountCents: cents)
}

// 额外 20 项，合计 35 资产 + 13 订阅，真实长列表超过 30 条。
for index in 1...20 {
    let category = ["cash", "fixed", "investment", "receivable", "debt"][(index - 1) % 5]
    _ = try core.createAsset(categoryCode: category, assetName: "家庭长期规划账户 · 第 \(index) 项", amountCents: 10_000 + Int64(index) * 125)
}

// 余额变动：追加历史快照。
let data = try core.data()
func assetID(named name: String) -> String {
    data.assets.first { $0.name == name }!.id
}
_ = try core.changeBalance(id: assetID(named: "招商银行朝朝宝（活期理财）家庭备用金账户"), amountCents: 1_234_557_890)
_ = try core.changeBalance(id: assetID(named: "招商银行朝朝宝（活期理财）家庭备用金账户"), amountCents: 1_234_567_890)
_ = try core.changeBalance(id: assetID(named: "家里一辆代步小车（2019 款自动挡）"), amountCents: 7_250_000)
_ = try core.changeBalance(id: assetID(named: "华泰证券 A 股账户"), amountCents: 16_104_880)
_ = try core.changeBalance(id: assetID(named: "华泰证券 A 股账户"), amountCents: 15_402_315)
_ = try core.changeBalance(id: assetID(named: "房贷（招商银行 30 年等额本息）"), amountCents: 125_430_000)

// 历史修正：改前改后各留记录。
let updated = try core.data()
let correctedHistory = updated.history.first { $0.assetId == assetID(named: "华泰证券 A 股账户") }!
try core.correctHistory(id: correctedHistory.id, amountCents: 15_588_000)
let carHistory = updated.history.first { $0.assetId == assetID(named: "家里一辆代步小车（2019 款自动挡）") }!
try core.correctHistory(id: carHistory.id, amountCents: 7_320_000)

// 订阅（含逾期、今天、本月、下月、之后、暂停与美元）。
let subscriptionSpecs: [(String, Int64, String, String, String)] = [
    ("iCloud 2TB（家庭共享）", 6_800, "CNY", "monthly", shift(days: -5)),
    ("网易云音乐黑胶 VIP", 1_900, "CNY", "monthly", shift()),
    ("Apple Music 家庭版", 2_500, "CNY", "monthly", shift(days: 2)),
    ("Netflix Premium", 1_599, "USD", "monthly", shift(days: 6)),
    ("iCloud 50GB", 600, "CNY", "monthly", shift(days: 11)),
    ("腾讯视频 VIP", 2_500, "CNY", "quarterly", shift(days: 18)),
    ("房贷自动扣款（招商银行）", 754_218, "CNY", "monthly", shift(days: 33)),
    ("iCloud+（美国区）", 299, "USD", "monthly", shift(days: 37)),
    ("Notion Plus（年付折月）", 1_000, "USD", "monthly", shift(days: 45)),
    ("域名 snowfold.app 续费", 1_400, "USD", "yearly", shift(days: 90)),
    ("健身房年卡（威尔仕）", 398_000, "CNY", "yearly", shift(days: 120)),
    ("极客时间年度会员", 19_900, "CNY", "yearly", shift(days: 60)),
    ("AppleCare+ for iPhone", 39_900, "CNY", "yearly", shift(days: 3)),
]
var pausedNames: Set<String> = ["极客时间年度会员"]
for (name, cents, currency, cycle, next) in subscriptionSpecs {
    let id = try core.createSubscription(subscription: SubscriptionInput(
        name: name,
        amountCents: cents,
        currency: currency,
        cycle: cycle,
        nextChargeOn: next,
        status: "active"
    ))
    if pausedNames.contains(name) {
        try core.setSubscriptionPaused(id: id, isPaused: true)
    }
}

// 本机主题与密钥：让截图与真实使用一致。
try core.saveTheme(theme: .system)
// 审计分支改动：CI 临时签名可能没有钥匙串写权限；造数不依赖同步密钥，导出失败不阻断。
_ = try? core.exportSealed()
let overview = try core.overview(on: dayString(Date()))
print("seeded: \(overview.totalAssetsCents) cents assets, \(overview.activeSubscriptions) active subs, \(overview.pausedSubscriptions) paused")
