package app.snowfold.ui.model

import androidx.annotation.StringRes
import app.snowfold.R
import app.snowfold.core.CategoryTotal
import app.snowfold.core.LedgerData
import app.snowfold.core.Overview
import app.snowfold.core.SubscriptionGroup
import app.snowfold.data.LedgerGateway
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** The five fixed categories; index doubles as the palette position. */
val CATEGORY_CODES = listOf("cash", "fixed", "investment", "receivable", "debt")

@StringRes
fun categoryLabelRes(code: String): Int = when (code) {
    "cash" -> R.string.category_cash
    "fixed" -> R.string.category_fixed
    "investment" -> R.string.category_investment
    "receivable" -> R.string.category_receivable
    "debt" -> R.string.category_debt
    else -> R.string.error_internal
}

@StringRes
fun cycleLabelRes(cycle: String): Int = when (cycle) {
    "weekly" -> R.string.cycle_weekly
    "monthly" -> R.string.cycle_monthly
    "quarterly" -> R.string.cycle_quarterly
    "yearly" -> R.string.cycle_yearly
    else -> R.string.cycle_unknown
}

@StringRes
fun groupLabelRes(groupOrdinal: Int): Int = when (groupOrdinal) {
    0 -> R.string.group_overdue
    1 -> R.string.group_this_month
    2 -> R.string.group_next_month
    3 -> R.string.group_later
    else -> R.string.group_paused
}

data class HistoryUi(
    val id: String,
    val recordedAt: String,
    val amountText: String,
    /** Plain decimal text (core format_amount) for edit-field prefills. */
    val amountPlain: String,
    /** Formatted "+¥12.00"/"−¥3.00"; null on the oldest snapshot (初始). */
    val deltaText: String?,
    val isCurrent: Boolean,
)

data class AssetUi(
    val id: String,
    val categoryIndex: Int,
    val name: String,
    val amountText: String,
    val amountPlain: String,
    val history: List<HistoryUi>,
    val updatedOn: java.time.LocalDate?,
)

data class CategoryUi(
    val index: Int,
    @StringRes val labelRes: Int,
    val amountText: String,
    val amountCents: Long,
    val count: Int,
    /** Bar length: share of positive assets, or debt ratio for the liability card. */
    val barFraction: Float,
    val sharePercentText: String,
    val isLiability: Boolean,
)

data class SubscriptionUi(
    val id: String,
    val name: String,
    val amountText: String,
    val amountPlain: String,
    /** 核心按扣费周期折算的年化金额（charts §5.4 用，禁止客户端换算）。 */
    val annualCents: Long,
    val annualText: String,
    val cycle: String,
    val currency: String,
    @StringRes val cycleLabelRes: Int,
    val nextChargeOn: LocalDate,
    val nextChargeRaw: String,
    val groupOrdinal: Int,
    val overdueDays: Int?,
    val active: Boolean,
    val initial: String,
)

sealed interface ChargeBadge {
    data class Overdue(val days: Int) : ChargeBadge
    data object Today : ChargeBadge
    data class InDays(val days: Int) : ChargeBadge
}

data class UpcomingUi(
    val id: String,
    val name: String,
    val date: LocalDate,
    val badge: ChargeBadge,
    val amountText: String,
)

sealed interface SubscriptionSummary {
    data object None : SubscriptionSummary
    data class WithOverdue(val active: Int, val overdue: Int) : SubscriptionSummary
    data class WithNext(val active: Int, val date: LocalDate) : SubscriptionSummary
    data class Count(val active: Int) : SubscriptionSummary
}

data class HeroUi(
    val netText: String,
    val netNegative: Boolean,
    val assetsText: String,
    val debtText: String,
    /** "12.3%", or null when assets are zero/non-positive (desktop shows —). */
    val ratioText: String?,
)

data class SubscriptionStatUi(
    val dueThisMonthText: String,
    val monthlyText: String,
    val annualText: String,
)

data class NextChargeUi(
    val nextChargeRaw: String,
    val name: String?,
)

/**
 * Immutable read model produced entirely on the core worker: every money string
 * is formatted by the core, so the UI thread never calls across FFI.
 */
enum class DayKind { Overdue, Today, Future }

/** 30 天时间轴的日期刻度（docs/design.md §5.3，MM-dd）。 */
data class TimelineTick(
    val index: Int,
    val label: String,
)

data class TimelineDayUi(
    val date: LocalDate,
    val cents: Long,
    val kind: DayKind,
)

data class UiLedger(
    val today: LocalDate,
    /** 未来扣费时间线：30 天逐日 CNY 合计（含左端逾期日）。 */
    val timeline: List<TimelineDayUi>,
    /** X 轴日期刻度 MM-dd，3–4 个。 */
    val timelineTicks: List<String>,
    val assets: List<AssetUi>,
    val assetCount: Int,
    val categories: List<CategoryUi>,
    val subscriptions: List<SubscriptionUi>,
    val hero: HeroUi?,
    val stats: SubscriptionStatUi?,
    val summary: SubscriptionSummary,
    val nextCharge: NextChargeUi?,
    val upcoming: List<UpcomingUi>,
    /** True when overview overflowed; hero/stats show 超出范围 placeholders. */
    val overflow: Boolean,
) {
    /** §5.1 环形图数据：cash→receivable 四类金额，负债不计入环。 */
    val donutAmounts: List<Long>
        get() = categories.take(4).map { it.amountCents }
}

private fun LedgerGateway.money(cents: Long): String = formatMoney(cents, "CNY")

private fun LedgerGateway.plainAmount(cents: Long): String = formatAmount(cents)

private fun LedgerGateway.deltaText(delta: Long): String =
    (if (delta > 0) "+" else "") + formatMoney(delta, "CNY")

private fun groupOrdinalOf(group: SubscriptionGroup): Int = group.ordinal

/**
 * Builds the UI read model. `overview` is null when the totals overflowed; the
 * rest of the ledger is still renderable with placeholders, mirroring the
 * desktop behavior of showing 超出范围 instead of failing the page.
 */
fun LedgerGateway.toUiLedger(
    data: LedgerData,
    overview: Overview?,
    today: LocalDate,
): UiLedger {
    val historiesByAsset = data.history.groupBy { it.assetId }
    val assets = data.assets.map { asset ->
        val history = historiesByAsset[asset.id].orEmpty()
        // LedgerData.history is in row order (oldest first); the desktop panel
        // renders newest first with the newest row labeled 当前.
        val rows = history.asReversed().mapIndexed { position, h ->
            HistoryUi(
                id = h.id,
                recordedAt = h.recordedAt,
                amountText = formatMoney(h.amountCents, "CNY"),
                amountPlain = plainAmount(h.amountCents),
                deltaText = if (position + 1 < history.size) {
                    deltaText(h.amountCents - history[history.size - 2 - position].amountCents)
                } else {
                    null
                },
                isCurrent = position == 0,
            )
        }
        AssetUi(
            id = asset.id,
            categoryIndex = CATEGORY_CODES.indexOf(asset.categoryCode).coerceAtLeast(0),
            name = asset.name,
            amountText = formatMoney(asset.amountCents, "CNY"),
            amountPlain = plainAmount(asset.amountCents),
            history = rows,
            updatedOn = runCatching { java.time.LocalDate.parse(asset.updatedAt.substringBefore('T')) }.getOrNull(),
        )
    }

    val totalAssets = overview?.totalAssetsCents
    val categoryTotals: List<CategoryUi> = overview?.categories?.mapIndexed { index, c: CategoryTotal ->
        val share = totalAssets?.takeIf { it > 0 }?.let { c.amountCents.toFloat() / it * 100f } ?: 0f
        val bar = if (c.isLiability) {
            totalAssets?.takeIf { it > 0 }?.let { overview.totalDebtCents.toFloat() / it * 100f } ?: 0f
        } else {
            share
        }
        CategoryUi(
            index = index,
            labelRes = categoryLabelRes(c.code),
            amountText = money(c.amountCents),
            amountCents = c.amountCents,
            count = c.count.toInt(),
            barFraction = (bar / 100f).coerceIn(0f, 1f),
            sharePercentText = String.format(Locale.ROOT, "%.1f%%", share),
            isLiability = c.isLiability,
        )
    } ?: CATEGORY_CODES.map { code ->
        CategoryUi(index = CATEGORY_CODES.indexOf(code), labelRes = categoryLabelRes(code), amountText = "", amountCents = 0, count = 0, barFraction = 0f, sharePercentText = "", isLiability = code == "debt")
    }

    val subscriptions = data.subscriptions.map { s ->
        val group = groupOrdinalOf(subscriptionGroup(s.id, today))
        val overdueDays = if (s.status == "active" && s.nextChargeOn.toLocalDate() < today) {
            ChronoUnit.DAYS.between(s.nextChargeOn.toLocalDate(), today).toInt()
        } else {
            null
        }
        val annualCents = subscriptionAnnual(s.id)
        SubscriptionUi(
            id = s.id,
            name = s.name,
            amountText = formatMoney(s.amountCents, s.currency),
            amountPlain = plainAmount(s.amountCents),
            annualCents = annualCents,
            annualText = formatMoney(annualCents, s.currency),
            currency = s.currency,
            cycle = s.cycle,
            cycleLabelRes = cycleLabelRes(s.cycle),
            nextChargeOn = s.nextChargeOn.toLocalDate(),
            nextChargeRaw = s.nextChargeOn,
            groupOrdinal = group,
            overdueDays = overdueDays,
            active = s.status == "active",
            initial = s.name.firstOrNull()?.uppercase() ?: "?",
        )
    }

    val hero = overview?.let {
        val ratio = it.totalAssetsCents.takeIf { v -> v > 0 }
            ?.let { v -> String.format(Locale.ROOT, "%.1f%%", it.totalDebtCents.toDouble() / v.toDouble() * 100.0) }
        HeroUi(
            netText = money(it.netAssetsCents),
            netNegative = it.netAssetsCents < 0,
            assetsText = money(it.totalAssetsCents),
            debtText = money(it.totalDebtCents),
            ratioText = ratio,
        )
    }
    val stats = overview?.let {
        val cny = it.subscriptionTotals.firstOrNull { t -> t.currency == "CNY" }
        val usd = it.subscriptionTotals.firstOrNull { t -> t.currency == "USD" }
        SubscriptionStatUi(
            dueThisMonthText = "${money(cny?.dueThisMonthCents ?: 0)} / ${formatMoney(usd?.dueThisMonthCents ?: 0, "USD")}",
            monthlyText = "${money(cny?.monthlyCents ?: 0)} / ${formatMoney(usd?.monthlyCents ?: 0, "USD")}",
            annualText = "${money(cny?.annualCents ?: 0)} / ${formatMoney(usd?.annualCents ?: 0, "USD")}",
        )
    }

    val activeCount = overview?.activeSubscriptions?.toInt()
        ?: data.subscriptions.count { it.status == "active" }
    val overdueCount = overview?.overdueSubscriptions?.toInt()
        ?: data.subscriptions.count { it.status == "active" && it.nextChargeOn.toLocalDate() < today }
    val summary = when {
        activeCount == 0 -> SubscriptionSummary.None
        overdueCount > 0 -> SubscriptionSummary.WithOverdue(activeCount, overdueCount)
        else -> overview?.nextChargeOn
            ?.let { SubscriptionSummary.WithNext(activeCount, it.toLocalDate()) }
            ?: SubscriptionSummary.Count(activeCount)
    }

    val nextActive = data.subscriptions
        .filter { it.status == "active" && it.nextChargeOn.toLocalDate() >= today }
        .minByOrNull { it.nextChargeOn }
    val nextCharge = nextActive?.let {
        NextChargeUi(nextChargeRaw = it.nextChargeOn, name = it.name)
    }

    val upcoming = overview?.upcoming.orEmpty().map { s ->
        val date = s.nextChargeOn.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, date).toInt()
        UpcomingUi(
            id = s.id,
            name = s.name,
            date = date,
            badge = when {
                days < 0 -> ChargeBadge.Overdue(-days)
                days == 0 -> ChargeBadge.Today
                else -> ChargeBadge.InDays(days)
            },
            amountText = formatMoney(s.amountCents, s.currency),
        )
    }

    // 时间线窗口：30 列，从最早逾期日（最多回看 7 天）或今天开始。
    val activeCny = data.subscriptions.filter { it.status == "active" && it.currency == "CNY" }
    val windowStart = activeCny.map { it.nextChargeOn.toLocalDate() }
        .filter { it < today }
        .minOrNull()
        ?.coerceAtLeast(today.minusDays(7))
        ?: today
    val timeline = (0L..29L).map { offset ->
        val date = windowStart.plusDays(offset)
        val cents = activeCny
            .filter { it.nextChargeOn.toLocalDate() == date }
            .sumOf { it.amountCents }
        val kind = when {
            date < today -> DayKind.Overdue
            date == today -> DayKind.Today
            else -> DayKind.Future
        }
        TimelineDayUi(date, cents, kind)
    }

    val tickCount = 4
    val tickIndices = if (timeline.isEmpty()) emptyList() else listOf(0, 9, 19, timeline.lastIndex).distinct()
    val timelineTicks = tickIndices.map { index ->
        val date = timeline[index].date
        String.format(java.util.Locale.ROOT, "%02d-%02d", date.monthValue, date.dayOfMonth)
    }
    val tickFraction = tickCount // 保留刻度数量语义

    return UiLedger(
        today = today,
        timeline = timeline,
        timelineTicks = timelineTicks,
        assets = assets,
        assetCount = assets.size,
        categories = categoryTotals,
        subscriptions = subscriptions,
        hero = hero,
        stats = stats,
        summary = summary,
        nextCharge = nextCharge,
        upcoming = upcoming,
        overflow = overview == null,
    )
}

private fun String.toLocalDate(): LocalDate = LocalDate.parse(this)
