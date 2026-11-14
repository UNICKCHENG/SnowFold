package app.snowfold.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.components.*
import app.snowfold.ui.model.*
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType

/**
 * 总览（docs/mobile-layout.md §2）：大标题导航栏（滚动收起）→ 净资产主卡 → 本月待扣 →
 * 资产构成（环形图 + 图例）→ 30 天时间线。宽屏两列网格；逾期笔画在「今天」位置（负值色）。
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
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        title = stringResource(R.string.overview_title),
        modifier = modifier,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(AppIcon.settings, stringResource(R.string.cd_settings))
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { HeroCard(ui, overflowText, onGoAssets) }
                    Box(Modifier.weight(1f)) { NextCard(ui, onGoSubscriptions, showTimeline = false) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { CompositionCard(ui, onGoAssets) }
                    Box(Modifier.weight(1f)) { TimelineCard(ui, onGoSubscriptions, large = true) }
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
private fun NextCard(ui: UiLedger, onOpen: (Int) -> Unit, showTimeline: Boolean = true) {
    val colors = appColors()
    val parts = ui.stats?.dueThisMonthText?.split(" / ")
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.next_section), style = SnowFoldType.sectionHeader, color = colors.ink2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.due_this_month), style = SnowFoldType.helper, color = colors.ink2)
                    AmountText(parts?.firstOrNull() ?: stringResource(R.string.out_of_range), SnowFoldType.cardNumber, Modifier.fillMaxWidth())
                    parts?.getOrNull(1)?.let {
                        AmountText(stringResource(R.string.amount_other_currency, it), SnowFoldType.helper, Modifier.fillMaxWidth(), colors.ink)
                    }
                }
                ui.nextCharge?.let { next ->
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
            if (showTimeline) {
                UpcomingTimeline(ui.upcoming, ui.today)
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
private fun CompositionCard(ui: UiLedger, onOpenAssets: (Int) -> Unit) {
    val colors = appColors()
    val assets = ui.categories.filter { !it.isLiability }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.asset_composition), style = SnowFoldType.sectionHeader, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenAssets(-1) }) { Text(stringResource(R.string.all_details) + " ›", style = SnowFoldType.helper) }
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
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    assets.forEach { category ->
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.total_debt), style = SnowFoldType.helper, color = colors.negative, modifier = Modifier.weight(0.35f))
                AmountText(debt?.amountText ?: stringResource(R.string.out_of_range), SnowFoldType.rowValue,
                    Modifier.weight(0.65f), colors.negative)
            }
        }
    }
}

@Composable
private fun TimelineCard(ui: UiLedger, onOpen: (Int) -> Unit, large: Boolean = false) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.upcoming_charges), style = SnowFoldType.sectionHeader)
            UpcomingTimeline(ui.upcoming, ui.today, plotHeight = if (large) 112.dp else 56.dp)

        }
    }
}

@Composable
private fun summaryText(summary: SubscriptionSummary): String = when (summary) {
    SubscriptionSummary.None -> stringResource(R.string.no_charges)
    is SubscriptionSummary.WithOverdue -> stringResource(R.string.subscription_summary_overdue, summary.active, summary.overdue)
    is SubscriptionSummary.WithNext -> stringResource(R.string.subscription_summary_next, summary.active, dateShort(summary.date))
    is SubscriptionSummary.Count -> stringResource(R.string.subscription_summary, summary.active)
}
