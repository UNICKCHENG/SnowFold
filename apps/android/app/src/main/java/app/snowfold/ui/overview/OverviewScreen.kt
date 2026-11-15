package app.snowfold.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AmountLevel
import app.snowfold.ui.components.AmountRowText
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.CategoryIconBlock
import app.snowfold.ui.components.ChargeBadgeView
import app.snowfold.ui.components.EmptyState
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfButton
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SectionHeader
import app.snowfold.ui.components.SubscriptionAvatar
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.ValueRow
import app.snowfold.ui.components.badgeLabel
import app.snowfold.ui.components.categoryLabel
import app.snowfold.ui.components.dateShort
import app.snowfold.ui.components.uiMessageText
import app.snowfold.ui.model.SubscriptionSummary
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate

/**
 * 总览（mobile-layout §2）：净资产（唯一大标题）+ 三项合计 + 资产构成 + 即将扣费；
 * 无资产时整页空状态。平板照 iPad：两列（design.md §9.2）。
 */
@Composable
fun OverviewScreen(
    ui: UiLedger,
    bannerText: String?,
    overflowText: String?,
    onGoAssets: (Int) -> Unit,
    onGoSubscriptions: () -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    onAddAsset: () -> Unit = {},
    keyCard: (@Composable () -> Unit)? = null,
    syncAction: (@Composable () -> Unit)? = null,
) {
    val colors = appColors()
    val largeFont = LocalDensity.current.fontScale > 1.3f
    if (ui.assets.isEmpty()) {
        Column(modifier) {
            if (keyCard != null) keyCard()
            if (bannerText != null) StartupBanner(bannerText)
            app.snowfold.ui.components.EmptyPage(Modifier.weight(1f).testTag("overview-list")) {
                EmptyState(
                    icon = AppIcon.overviewLine,
                    title = stringResource(R.string.overview_empty_title),
                    help = stringResource(R.string.overview_empty_help),
                    action = { SfButton(stringResource(R.string.add_asset), onAddAsset, testTag = "overview-add") },
                )
            }
        }
        return
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .testTag("overview-list")
            .padding(horizontal = if (tablet) DesignTokens.space6 else DesignTokens.pageMargin)
            .padding(bottom = if (tablet) DesignTokens.space8 else DesignTokens.phoneContentBottom),
    ) {
        if (keyCard != null) {
            keyCard()
            Spacer(Modifier.height(DesignTokens.space4))
        }
        if (bannerText != null) {
            StartupBanner(bannerText)
            Spacer(Modifier.height(DesignTokens.space4))
        }
        Spacer(Modifier.height(DesignTokens.space2))
        // 1. 净资产
        Text(stringResource(R.string.net_assets), style = SnowFoldType.bodyMedium.appType(), color = colors.text2)
        AmountText(
            value = ui.hero?.netText ?: stringResource(R.string.out_of_range),
            level = AmountLevel.Display,
            negative = ui.hero?.netNegative == true,
            modifier = Modifier.testTag("overview-net").fillMaxWidth(),
        )
        Spacer(Modifier.height(DesignTokens.space4))
        if (largeFont) {
            HeroStatStacked(ui, overflowText)
        } else if (tablet) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.space8),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.space2),
            ) {
                HeroStat(stringResource(R.string.total_assets), ui.hero?.assetsText ?: stringResource(R.string.out_of_range), false)
                HeroStat(stringResource(R.string.total_debt), ui.hero?.debtText ?: stringResource(R.string.out_of_range), true)
                HeroStat(stringResource(R.string.debt_ratio), ui.hero?.ratioText ?: "—", false)
            }
        } else {
            // 三个独立指标保持左对齐；长金额按列宽整体缩放，不挤走负债率。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DesignTokens.space5)) {
                HeroStat(stringResource(R.string.total_assets), ui.hero?.assetsText ?: stringResource(R.string.out_of_range), false, Modifier.weight(1.4f))
                HeroStat(stringResource(R.string.total_debt), ui.hero?.debtText ?: stringResource(R.string.out_of_range), true, Modifier.weight(1.5f))
                HeroStat(stringResource(R.string.debt_ratio), ui.hero?.ratioText ?: "—", false, Modifier.weight(0.6f))
            }
        }
        Spacer(Modifier.height(if (tablet) DesignTokens.space6 else 28.dp))

        if (tablet) {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.space6)) {
                Column(Modifier.weight(1f)) {
                    SectionHeader(
                        stringResource(R.string.asset_composition),
                        trailing = { TextLink(stringResource(R.string.all_details), { onGoAssets(-1) }, chevron = true) },
                    )
                    Spacer(Modifier.height(DesignTokens.space2))
                    CompositionCard(ui, tablet = true, onGoAssets = onGoAssets)
                }
                Column(Modifier.weight(1f)) {
                    SectionHeader(
                        stringResource(R.string.upcoming_charges),
                        trailing = { TextLink(stringResource(R.string.all_subscriptions), { onGoSubscriptions() }, chevron = true) },
                    )
                    Spacer(Modifier.height(DesignTokens.space2))
                    UpcomingCard(ui, tablet = true)
                }
            }
        } else {
            SectionHeader(
                stringResource(R.string.asset_composition),
                trailing = { TextLink(stringResource(R.string.all_details), { onGoAssets(-1) }, chevron = true) },
            )
            Spacer(Modifier.height(DesignTokens.space2))
            CompositionCard(ui, tablet = false, onGoAssets = onGoAssets)
            Spacer(Modifier.height(28.dp))
            SectionHeader(
                stringResource(R.string.upcoming_charges),
                trailing = { TextLink(stringResource(R.string.all_subscriptions), { onGoSubscriptions() }, chevron = true) },
            )
            Spacer(Modifier.height(DesignTokens.space2))
            UpcomingCard(ui, tablet = false)
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, negative: Boolean, modifier: Modifier = Modifier) {
    val colors = appColors()
    Column(modifier) {
        Text(label, style = SnowFoldType.caption.appType(), color = colors.text2)
        AmountRowText(value, color = if (negative) colors.neg else colors.text)
    }
}

/** 系统最大字号：行内左右排列改上下排列（design.md §2）。 */
@Composable
private fun HeroStatStacked(ui: UiLedger, overflowText: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.space2)) {
        HeroStat(stringResource(R.string.total_assets), ui.hero?.assetsText ?: stringResource(R.string.out_of_range), false)
        HeroStat(stringResource(R.string.total_debt), ui.hero?.debtText ?: stringResource(R.string.out_of_range), true)
        HeroStat(stringResource(R.string.debt_ratio), ui.hero?.ratioText ?: "—", false)
    }
}

/** 资产构成卡片：手机 = 顶部 8 高构成条 + 五行；平板 = 逐行占比条（iPad 稿）。 */
@Composable
private fun CompositionCard(ui: UiLedger, tablet: Boolean, onGoAssets: (Int) -> Unit) {
    val colors = appColors()
    SfCard(tag = "composition-card") {
        if (!tablet) {
            Column(Modifier.padding(horizontal = DesignTokens.pageMargin).padding(top = DesignTokens.space4)) {
                StackedCompositionBar(ui)
            }
        }
        ui.categories.forEachIndexed { index, category ->
            if (index > 0) RowDivider(startInset = DesignTokens.iconBlock + DesignTokens.pageMargin + DesignTokens.space3)
            val rowModifier = Modifier.fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onGoAssets(category.index) }
                .testTag("composition-row-${category.index}")
                .heightIn(min = if (tablet) DesignTokens.rowDoubleHeight else DesignTokens.rowHeight)
                .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space2)
            if (tablet) {
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconBlock(category.index, DesignTokens.iconBlock)
                    Column(Modifier.weight(1f).padding(start = DesignTokens.space3)) {
                        ValueRow(
                            leading = {
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.space2)) {
                                    Text(categoryLabel(category.index), style = SnowFoldType.bodyMedium.appType(), color = colors.text)
                                    Text(stringResource(R.string.item_count, category.count), style = SnowFoldType.caption.appType(), color = colors.text2)
                                }
                            },
                            trailing = { AmountRowText(category.amountText, color = if (category.isLiability) colors.neg else colors.text) },
                        )
                        Spacer(Modifier.height(DesignTokens.space1))
                        if (category.isLiability) {
                            Text(stringResource(R.string.composition_excluded), style = SnowFoldType.caption.appType(), color = colors.text2)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(99.dp)).background(colors.bg)) {
                                    if (category.barFraction > 0f) Box(Modifier.fillMaxWidth(category.barFraction.coerceIn(0.005f, 1f)).height(4.dp).background(colors.cat[category.index]))
                                }
                                Text(category.sharePercentText, style = SnowFoldType.caption.appType(), color = colors.text2,
                                    modifier = Modifier.padding(start = DesignTokens.space3), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            } else {
                ValueRow(
                    modifier = rowModifier,
                    leading = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryIconBlock(category.index, DesignTokens.iconBlock)
                            Column(Modifier.padding(start = DesignTokens.space3)) {
                                Text(categoryLabel(category.index), style = SnowFoldType.bodyMedium.appType(), color = colors.text)
                                Text(stringResource(R.string.item_count, category.count), style = SnowFoldType.caption.appType(), color = colors.text2)
                            }
                        }
                    },
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            AmountRowText(category.amountText, color = if (category.isLiability) colors.neg else colors.text)
                            if (!category.isLiability) Text(category.sharePercentText, style = SnowFoldType.caption.appType(), color = colors.text2)
                        }
                    },
                )
            }
        }
    }
}

/** 手机版顶部构成条：四类（负债不计入）。 */
@Composable
private fun StackedCompositionBar(ui: UiLedger) {
    val colors = appColors()
    val total = ui.categories.take(4).sumOf { it.amountCents }.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.compBarHeight)
            .clip(RoundedCornerShape(99.dp))
            .background(colors.bg),
    ) {
        ui.categories.take(4).forEach { category ->
            if (category.amountCents > 0) {
                Box(
                    Modifier
                        .weight(category.amountCents.toFloat() / total)
                        .fillMaxWidth()
                        .height(DesignTokens.compBarHeight)
                        .background(colors.cat[category.index]),
                )
            }
        }
    }
}

/** 即将扣费卡片：最多 5 行（首字头像/名称/日期/徽标/金额）+ 订阅摘要行。 */
@Composable
private fun UpcomingCard(ui: UiLedger, tablet: Boolean) {
    val colors = appColors()
    SfCard {
        if (ui.upcoming.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = DesignTokens.space8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.no_upcoming_charges),
                    style = SnowFoldType.body.appType(),
                    color = colors.text2,
                )
            }
            return@SfCard
        }
        ui.upcoming.take(5).forEachIndexed { index, upcoming ->
            if (index > 0) RowDivider(startInset = if (tablet) DesignTokens.avatar + DesignTokens.pageMargin + DesignTokens.space3 else DesignTokens.pageMargin)
            ValueRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)
                    .testTag("upcoming-${upcoming.id}"),
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tablet) SubscriptionAvatar(upcoming.name.take(1), groupOrdinal = if (upcoming.badge is app.snowfold.ui.model.ChargeBadge.Overdue) 0 else -1)
                        Column(Modifier.padding(start = if (tablet) DesignTokens.space3 else 0.dp)) {
                            Text(upcoming.name, style = (if (tablet) SnowFoldType.bodyMedium else SnowFoldType.body).appType(), color = colors.text)
                            val subscription = ui.subscriptions.firstOrNull { it.id == upcoming.id }
                            val detail = if (tablet && subscription != null) stringResource(subscription.cycleLabelRes) + " · " + dateShort(upcoming.date) else dateShort(upcoming.date)
                            Text(detail, style = SnowFoldType.caption.appType(), color = colors.text2)
                        }
                    }
                },
                trailing = {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.space3),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.space1),
                    ) {
                        ChargeBadgeView(upcoming.badge)
                        AmountRowText(upcoming.amountText)
                    }
                },
            )
        }
        // 订阅摘要行
        RowDivider(startInset = DesignTokens.pageMargin)
        Row(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)) {
            Text(
                text = when (val summary = ui.summary) {
                    is SubscriptionSummary.WithOverdue -> stringResource(R.string.subscription_summary_overdue, summary.active, summary.overdue)
                    is SubscriptionSummary.WithNext -> stringResource(R.string.subscription_summary_next, summary.active, dateShort(summary.date))
                    is SubscriptionSummary.Count -> stringResource(R.string.subscription_summary, summary.active)
                    SubscriptionSummary.None -> stringResource(R.string.no_subscriptions)
                },
                style = SnowFoldType.captionMedium.appType(),
                color = if (ui.summary is SubscriptionSummary.WithOverdue) colors.neg else colors.text2,
            )
        }
    }
}

/** 启动横幅（§9.7）：warning-triangle + 消息的 neg 通知卡。 */
@Composable
fun StartupBanner(text: String) {
    val colors = appColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.cardCorner))
            .background(colors.negSoft)
            .padding(DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcon.warningTriangle, contentDescription = null, tint = colors.neg, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = SnowFoldType.caption.appType(),
            color = colors.neg,
            modifier = Modifier.padding(start = DesignTokens.space3),
        )
    }
}

/** 密钥不可用卡（§9.7）：warn-soft 卡 + key 20（warn）+ 导入恢复码。 */
@Composable
fun KeyUnavailableCard(text: String, onImport: () -> Unit) {
    val colors = appColors()
    val largeFont = LocalDensity.current.fontScale > 1.3f
    val heading: @Composable () -> Unit = {
        Text(
            stringResource(R.string.key_unavailable_title),
            style = SnowFoldType.bodyMedium.appType(),
            color = colors.text,
        )
    }
    val help: @Composable () -> Unit = {
        Text(text, style = SnowFoldType.caption.appType(), color = colors.text2)
    }
    val action: @Composable () -> Unit = {
        SfButton(
            stringResource(R.string.import_recovery_code),
            onImport,
            style = app.snowfold.ui.components.ButtonStyle.Secondary,
            inlineHeight = 36.dp,
            testTag = "key-import",
        )
    }
    SfCard(modifier = Modifier.testTag("key-card")) {
        if (largeFont) {
            Column(Modifier.background(colors.warnSoft).padding(DesignTokens.space4)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.space3)) {
                    Icon(AppIcon.key, contentDescription = null, tint = colors.warn, modifier = Modifier.size(20.dp))
                    heading()
                }
                Spacer(Modifier.height(DesignTokens.space2))
                help()
                Spacer(Modifier.height(DesignTokens.space3))
                action()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(colors.warnSoft).padding(DesignTokens.space4),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.space3),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(AppIcon.key, contentDescription = null, tint = colors.warn, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignTokens.space1)) {
                    heading()
                    help()
                }
                action()
            }
        }
    }
}
