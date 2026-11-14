package app.snowfold.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.components.*
import app.snowfold.ui.model.*
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import java.time.LocalDate

/**
 * 总览（docs/mobile-layout.md §8，宽屏对齐 iPad）：日期副标题 → 净资产卡 + 本月待扣卡
 * （下一笔右上角胶囊）→ 卡外分区标题「资产构成 · 全部明细」「30 天扣费安排」→
 * 构成卡（环形图 + 分隔线图例 + 负债行）与柱状图卡 → 全宽「即将扣费」列表。
 * 窄屏（<600dp）保持原单列结构不动。
 */
@Composable
fun OverviewScreen(
    ui: UiLedger,
    bannerText: String?,
    overflowText: String?,
    onOpenSettings: () -> Unit,
    onGoAssets: (Int) -> Unit,
    onGoSubscriptions: (Int) -> Unit,
    expanded: Boolean,
    wideTablet: Boolean = false,
    contentWidth: Int = 0,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        title = stringResource(R.string.overview_title),
        modifier = modifier,
        actions = {
            if (!expanded) {
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcon.settings, stringResource(R.string.cd_settings))
                }
            }
        },
    ) { pad ->
        Column(
            pad.fillMaxWidth().testTag("overview-list")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.pageMargin),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.cardGap),
        ) {
            if (bannerText != null) ErrorBanner(bannerText)
            if (expanded) {
                Text(
                    stringResource(R.string.overview_subtitle, dateLong(ui.today), weekdayChar(ui.today)),
                    style = SnowFoldType.helper,
                    color = appColors().ink2,
                )
                // §8 双栏仅在窗口宽 ≥840dp（Expanded）并排；600dp 竖屏退为单列，语义与 iPad 一致。
                run {
                    // §8 新规则：内容区 ≥720dp 才双列；否则 iPad 同款卡片单列。
                    val twoCol = wideTablet && contentWidth >= 720
                    if (twoCol) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1.12f)) { HeroCard(ui, overflowText, onGoAssets) }
                            Box(Modifier.weight(0.88f)) { NextCard(ui, onGoSubscriptions, showTimeline = false, wide = true) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionHeaderRow(stringResource(R.string.asset_composition)) {
                                    TextButton(onClick = { onGoAssets(-1) }) {
                                        Text(stringResource(R.string.all_details) + " ›", style = SnowFoldType.helper)
                                    }
                                }
                                CompositionCard(ui, onGoAssets, headerInside = false)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionHeaderRow(stringResource(R.string.schedule_section))
                                ScheduleCard(ui)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeaderRow(stringResource(R.string.upcoming_charges)) {
                                TextButton(onClick = { onGoSubscriptions(-1) }) {
                                    Text(stringResource(R.string.all_subscriptions) + " ›", style = SnowFoldType.helper)
                                }
                            }
                            UpcomingListCard(ui, onGoSubscriptions)
                        }
                    } else {
                        // 单列顺序（对齐 iPad 紧凑宽）：净资产 → 本月待扣（柱状图在卡内）→ 构成 → 即将扣费列表。
                        HeroCard(ui, overflowText, onGoAssets)
                        NextCard(ui, onGoSubscriptions, showTimeline = true, wide = true)
                        CompositionCard(ui, onGoAssets)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeaderRow(stringResource(R.string.upcoming_charges)) {
                                TextButton(onClick = { onGoSubscriptions(-1) }) {
                                    Text(stringResource(R.string.all_subscriptions) + " ›", style = SnowFoldType.helper)
                                }
                            }
                            UpcomingListCard(ui, onGoSubscriptions)
                        }
                    }
                }
            } else {
                HeroCard(ui, overflowText, onGoAssets)
                NextCard(ui, onGoSubscriptions)
                CompositionCard(ui, onGoAssets)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 卡外分区标题（§8）：标题 + 可选右上链接。 */
@Composable
private fun SectionHeaderRow(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SnowFoldType.sectionHeader, color = appColors().ink2, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun HeroCard(ui: UiLedger, overflowText: String?, onOpenAssets: (Int) -> Unit) {
    val colors = appColors()
    AppCard(Modifier.clickable { onOpenAssets(-1) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.net_assets), style = SnowFoldType.helper, color = colors.ink2)
            AmountText(
                ui.hero?.netText ?: stringResource(R.string.out_of_range), SnowFoldType.hero,
                modifier = Modifier.fillMaxWidth(),
                color = if (ui.hero?.netNegative == true) colors.negative else colors.ink,
                animate = true, decimalHierarchy = true,
            )
            if (overflowText != null) ErrorBanner(overflowText)
            if (LocalDensity.current.fontScale > 1.3f) {
                listOf(
                    Triple(stringResource(R.string.total_assets), ui.hero?.assetsText, colors.ink),
                    Triple(stringResource(R.string.total_debt), ui.hero?.debtText, colors.negative),
                    Triple(stringResource(R.string.debt_ratio), ui.hero?.ratioText, colors.ink),
                ).forEach { (label, value, tint) ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = SnowFoldType.helper, color = colors.ink2)
                        AmountText(value ?: stringResource(R.string.out_of_range), SnowFoldType.rowValue, Modifier.fillMaxWidth(), tint)
                    }
                }
            } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroMetric(stringResource(R.string.total_assets), ui.hero?.assetsText, Modifier.weight(1f))
                HeroMetric(stringResource(R.string.total_debt), ui.hero?.debtText, Modifier.weight(1f), colors.negative)
                HeroMetric(stringResource(R.string.debt_ratio), ui.hero?.ratioText ?: "—", Modifier.weight(1f))
            }
            }
            ShareBar(ui.categories.lastOrNull()?.barFraction ?: 0f, colors.negative)
        }
    }
}

@Composable
private fun HeroMetric(label: String, amount: String?, modifier: Modifier, color: Color = appColors().ink) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = SnowFoldType.helper, color = appColors().ink2)
        AmountText(amount ?: stringResource(R.string.out_of_range), SnowFoldType.rowValue, Modifier.fillMaxWidth(), color)
    }
}

@Composable
private fun NextCard(ui: UiLedger, onOpen: (Int) -> Unit, showTimeline: Boolean = true, wide: Boolean = false) {
    val colors = appColors()
    val parts = ui.stats?.dueThisMonthText?.split(" / ")
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!wide) Text(stringResource(R.string.next_section), style = SnowFoldType.sectionHeader, color = colors.ink2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.due_this_month), style = SnowFoldType.helper, color = colors.ink2)
                    AmountText(parts?.firstOrNull() ?: stringResource(R.string.out_of_range), SnowFoldType.cardNumber, Modifier.fillMaxWidth())
                    parts?.getOrNull(1)?.let {
                        AmountText(stringResource(R.string.amount_other_currency, it), SnowFoldType.helper, Modifier.fillMaxWidth(), colors.ink)
                    }
                }
                ui.nextCharge?.let { next ->
                    if (wide) {
                        // §8：下一笔扣费放在卡片右上角胶囊里。
                        Text(
                            "${dateShort(java.time.LocalDate.parse(next.nextChargeRaw))} · ${next.name.orEmpty()}",
                            style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
                            color = colors.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    } else {
                        Column(
                            Modifier.weight(1f).background(colors.tile, RoundedCornerShape(14.dp))
                                .clickable { onOpen(-1) }.padding(10.dp),
                        ) {
                            Text(stringResource(R.string.next_payment), style = SnowFoldType.helper, color = colors.ink2)
                            Text(
                                "${dateShort(java.time.LocalDate.parse(next.nextChargeRaw))} · ${next.name.orEmpty()}",
                                style = SnowFoldType.helper, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (showTimeline) {
                UpcomingBarChart(ui.upcoming, ui.today, plotHeight = 96.dp)
            }
                val pending = ui.summary is SubscriptionSummary.WithOverdue
                Text(
                    summaryText(ui.summary) + " ›", style = SnowFoldType.helper,
                    color = if (pending) colors.negative else colors.ink2,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (pending) colors.negative.copy(alpha = 0.09f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .testTag("overdue-link").clickable { onOpen(if (pending) 0 else -1) }.padding(vertical = 12.dp, horizontal = 8.dp),
                )
        }
    }
}

@Composable
private fun CompositionCard(
    ui: UiLedger,
    onOpenAssets: (Int) -> Unit,
    headerInside: Boolean = true,
) {
    val colors = appColors()
    val assets = ui.categories.filter { !it.isLiability }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (headerInside) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.asset_composition), style = SnowFoldType.sectionHeader, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpenAssets(-1) }) { Text(stringResource(R.string.all_details) + " ›", style = SnowFoldType.helper) }
                }
            }
            val donut = @Composable {
                CompositionDonut(
                    amounts = assets.map { it.amountCents },
                    colors = assets.map { categoryTint(it.index, colors).first },
                    totalText = stringResource(R.string.category_count, assets.size),
                    emptyText = stringResource(R.string.composition_empty),
                    modifier = Modifier.size(112.dp),
                )
            }
            val legend = @Composable { modifier: Modifier ->
                Column(modifier) {
                    assets.forEachIndexed { index, category ->
                        LegendRow(category, onOpenAssets)
                        if (index < assets.lastIndex) {
                            HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
            }
            // A side-by-side legend narrower than ~150dp wraps category names every two characters.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 290.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        donut()
                        legend(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        donut()
                        legend(Modifier.fillMaxWidth())
                    }
                }
            }
            val debt = ui.categories.firstOrNull { it.isLiability }
            HorizontalDivider(color = colors.hairline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(categoryTint(4, colors).second, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(AppIcon.category(4), null, tint = categoryTint(4, colors).first, modifier = Modifier.size(14.dp))
                }
                Text(stringResource(R.string.total_debt), style = SnowFoldType.helper, color = colors.ink2, modifier = Modifier.weight(1f).padding(start = 8.dp))
                AmountText("−" + (debt?.amountText ?: stringResource(R.string.out_of_range)), SnowFoldType.rowValue,
                    Modifier, colors.negative)
            }
        }
    }
}

@Composable
private fun LegendRow(category: CategoryUi, onOpenAssets: (Int) -> Unit) {
    val colors = appColors()
    Column(Modifier.fillMaxWidth().testTag("legend-${category.index}").clickable { onOpenAssets(category.index) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(categoryTint(category.index, colors).first, RoundedCornerShape(99.dp)))
            Text(stringResource(category.labelRes), style = SnowFoldType.helper, modifier = Modifier.weight(1f))
            Text(category.sharePercentText, style = SnowFoldType.helper, color = colors.ink2)
        }
        AmountText(category.amountText.ifEmpty { stringResource(R.string.out_of_range) }, SnowFoldType.helper,
            Modifier.fillMaxWidth().padding(start = 13.dp), colors.ink2)
    }
}

/** 扣费柱状图卡（§8）：柱高 = 当天笔数，逾期红色；下方一行说明。 */


/** 扣费柱状图卡（§8，双列右侧）：柱高 = 当天笔数，逾期红色；下方一行说明。 */
@Composable
private fun ScheduleCard(ui: UiLedger) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UpcomingBarChart(ui.upcoming, ui.today, plotHeight = 160.dp)
            Text(stringResource(R.string.timeline_caption), style = SnowFoldType.helper, color = appColors().ink2)
        }
    }
}
/** 全宽「即将扣费」列表（§8）：日期、名称、状态标签、金额。 */
@Composable
private fun UpcomingListCard(ui: UiLedger, onGoSubscriptions: (Int) -> Unit) {
    val colors = appColors()
    AppCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (ui.upcoming.isEmpty()) {
                Text(
                    stringResource(R.string.no_upcoming_charges), style = SnowFoldType.body, color = colors.ink3,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center,
                )
            } else {
                ui.upcoming.forEachIndexed { index, charge ->
                    if (index > 0) HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 16.dp))
                    Row(
                        Modifier.fillMaxWidth().testTag("upcoming-${charge.id}").clickable { onGoSubscriptions(-1) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            dateShort(charge.date), style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
                            color = colors.ink2, modifier = Modifier.width(64.dp),
                        )
                        Text(
                            charge.name.orEmpty(), style = SnowFoldType.rowTitle, color = colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        ChargeBadgeChip(charge.badge)
                        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                            AmountText(charge.amountText, SnowFoldType.rowValue, Modifier, colors.ink)
                        }
                    }
                }
            }
        }
    }
}

/** 状态标签胶囊（TagBadge 同款）：逾期负值色、今天与 N 天后品牌蓝、其余灰。 */
@Composable
private fun ChargeBadgeChip(badge: ChargeBadge) {
    val colors = appColors()
    val pair = when (badge) {
        is ChargeBadge.Overdue -> stringResource(R.string.overdue_days_compact, badge.days) to Pair(colors.negative.copy(alpha = 0.12f), colors.negative)
        ChargeBadge.Today -> stringResource(R.string.today) to Pair(colors.accent.copy(alpha = 0.12f), colors.accent)
        is ChargeBadge.InDays -> stringResource(R.string.days_until_charge, badge.days) to Pair(colors.ink2.copy(alpha = 0.10f), colors.ink2)
    }
    Text(
        pair.first, style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
        color = pair.second.second,
        modifier = Modifier.background(pair.second.first, RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun summaryText(summary: SubscriptionSummary): String = when (summary) {
    SubscriptionSummary.None -> stringResource(R.string.no_charges)
    is SubscriptionSummary.WithOverdue -> stringResource(R.string.subscription_summary_overdue, summary.active, summary.overdue)
    is SubscriptionSummary.WithNext -> stringResource(R.string.subscription_summary_next, summary.active, dateShort(summary.date))
    is SubscriptionSummary.Count -> stringResource(R.string.subscription_summary, summary.active)
}
