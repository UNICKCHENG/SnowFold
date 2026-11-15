package app.snowfold.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AmountLevel
import app.snowfold.ui.components.AmountRowText
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.ButtonStyle
import app.snowfold.ui.components.ChargeBadgeView
import app.snowfold.ui.model.ChargeBadge
import app.snowfold.ui.model.SubscriptionStatUi
import app.snowfold.ui.components.EmptyState
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfButton
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfMenu
import app.snowfold.ui.components.StatHelpDialog
import app.snowfold.ui.components.SubscriptionAvatar
import app.snowfold.ui.components.SwipeActions
import app.snowfold.ui.components.SwipeRow
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.ValueRow
import app.snowfold.ui.components.dateShort
import app.snowfold.ui.components.rememberHaptics
import app.snowfold.ui.model.SubscriptionUi
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate

/**
 * 订阅页（mobile-layout §4）：本月待扣（页标题级）+ 月均/年化 + 分组列表
 * （待确认/本月/下月/之后/已暂停）；左滑「已续费」+「删除」；长按菜单；点行开编辑 sheet。
 */
@Composable
fun SubscriptionsScreen(
    vm: MainViewModel,
    ui: UiLedger,
    tablet: Boolean,
    onNewSubscription: () -> Unit,
    onOpenSubscription: (String) -> Unit,
    onRenew: (String) -> Unit,
    onPauseToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    focusGroup: Int = -1,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    val stats = ui.stats
    if (ui.subscriptions.isEmpty()) {
        app.snowfold.ui.components.EmptyPage(modifier.testTag("subscriptions-list")) {
            EmptyState(
                icon = AppIcon.subscriptionsLine,
                title = stringResource(R.string.no_subscriptions),
                help = stringResource(R.string.no_subscriptions_help),
                action = {
                    SfButton(stringResource(R.string.new_subscription).removePrefix("＋ "), onNewSubscription,
                        icon = AppIcon.plus, testTag = "subscriptions-add")
                },
            )
        }
        return
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .testTag("subscriptions-list")
            .padding(horizontal = if (tablet) DesignTokens.space6 else DesignTokens.pageMargin)
            .padding(bottom = if (tablet) DesignTokens.space8 else DesignTokens.phoneContentBottom),
    ) {
        // 平板三项底对齐；手机和最大字号保持上下排列（mobile-layout §4/§8）。
        Spacer(Modifier.height(DesignTokens.space2))
        val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        if (tablet && !largeFont) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f, fill = false)) { DueThisMonth(stats) }
                StatBlock(stringResource(R.string.monthly_spending), R.string.monthly_spending_help, stats?.monthlyText, stats?.monthlyUsdText, "stat-monthly")
                StatBlock(stringResource(R.string.annual_spending), R.string.annual_spending_help, stats?.annualText, stats?.annualUsdText, "stat-annual")
            }
        } else {
            DueThisMonth(stats)
            Spacer(Modifier.height(DesignTokens.space4))
            if (largeFont) {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.space2)) {
                    StatBlock(stringResource(R.string.monthly_spending), R.string.monthly_spending_help, stats?.monthlyText, stats?.monthlyUsdText, "stat-monthly")
                    StatBlock(stringResource(R.string.annual_spending), R.string.annual_spending_help, stats?.annualText, stats?.annualUsdText, "stat-annual")
                }
            } else {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.space8)) {
                    StatBlock(stringResource(R.string.monthly_spending), R.string.monthly_spending_help, stats?.monthlyText, stats?.monthlyUsdText, "stat-monthly")
                    StatBlock(stringResource(R.string.annual_spending), R.string.annual_spending_help, stats?.annualText, stats?.annualUsdText, "stat-annual")
                }
            }
        }
        Spacer(Modifier.height(DesignTokens.space3))
        val activeCount = ui.subscriptions.count { it.active }
        val pausedCount = ui.subscriptions.count { !it.active }
        Text(
            text = if (pausedCount > 0) {
                stringResource(R.string.subscriptions_subtitle_with_paused, activeCount, pausedCount)
            } else {
                stringResource(R.string.subscriptions_subtitle, activeCount)
            },
            style = SnowFoldType.caption.appType(),
            color = colors.text2,
        )
        Spacer(Modifier.height(DesignTokens.space4))

        // 2. 分组：待确认（组头 neg）/ 本月 / 下月 / 之后 / 已暂停
        (0..4).forEach { groupOrdinal ->
            val groupSubs = ui.subscriptions.filter { it.groupOrdinal == groupOrdinal }
            if (groupSubs.isEmpty()) return@forEach
            val isOverdue = groupOrdinal == 0
            Row(
                Modifier.padding(top = DesignTokens.space4, bottom = DesignTokens.space2).testTag("subscription-group-$groupOrdinal"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(groupLabelRes(groupOrdinal)),
                    style = SnowFoldType.captionMedium.appType(),
                    color = if (isOverdue) colors.neg else colors.text,
                )
                Spacer(Modifier.width(DesignTokens.space2))
                Text(
                    groupSubs.size.toString(),
                    style = SnowFoldType.caption.appType(),
                    color = colors.text2,
                )
            }
            SfCard {
                groupSubs.forEachIndexed { index, subscription ->
                    if (index > 0) RowDivider(startInset = DesignTokens.avatar + DesignTokens.pageMargin + DesignTokens.space3)
                    androidx.compose.runtime.key(subscription.id) { SubscriptionRow(
                        vm = vm,
                        subscription = subscription,
                        today = ui.today,
                        onOpen = { onOpenSubscription(subscription.id) },
                        onRenew = {
                            onRenew(subscription.id)
                        },
                        onPauseToggle = onPauseToggle,
                        onDelete = {
                            onDelete(subscription.id)
                        },
                        tablet = tablet,
                    ) }
                }
            }
        }
    }
}

@Composable
private fun groupLabelRes(groupOrdinal: Int): Int = when (groupOrdinal) {
    0 -> R.string.group_overdue
    1 -> R.string.group_this_month
    2 -> R.string.group_next_month
    3 -> R.string.group_later
    else -> R.string.group_paused
}

@Composable
private fun DueThisMonth(stats: SubscriptionStatUi?) {
    val colors = appColors()
    Text(stringResource(R.string.due_this_month), style = SnowFoldType.bodyMedium.appType(), color = colors.text2)
    AmountText(stats?.dueThisMonthText ?: stringResource(R.string.out_of_range), AmountLevel.Title,
        Modifier.testTag("subs-due"))
    stats?.dueThisMonthUsdText?.let {
        Text(stringResource(R.string.more_currency, it), style = SnowFoldType.body.appType(), color = colors.text2,
            modifier = Modifier.padding(top = DesignTokens.space1))
    }
}

@Composable
private fun StatBlock(label: String, helpRes: Int, value: String?, usd: String?, tag: String) {
    val colors = appColors()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = SnowFoldType.caption.appType(), color = colors.text2)
            Spacer(Modifier.width(DesignTokens.space1))
            StatHelpDialog(help = stringResource(helpRes), a11yLabel = stringResource(if (tag == "stat-monthly") R.string.cd_monthly_info else R.string.cd_annual_info))
        }
        Text(value ?: stringResource(R.string.out_of_range), style = SnowFoldType.headline.appType(), color = colors.text, modifier = Modifier.testTag(tag))
        if (usd != null) {
            Text(
                stringResource(R.string.more_currency, usd),
                style = SnowFoldType.caption.appType(),
                color = colors.text2,
            )
        }
    }
}

/** 订阅行（mobile-layout §4.2）：头像 36 + 名称/副标题 + 金额 + 「已续费」（待确认行）。 */
@Composable
private fun SubscriptionRow(
    vm: MainViewModel,
    subscription: SubscriptionUi,
    today: LocalDate,
    onOpen: () -> Unit,
    onRenew: () -> Unit,
    onPauseToggle: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    tablet: Boolean,
) {
    val colors = appColors()
    var menuOpen by remember(subscription.id) { mutableStateOf(false) }
    val overdue = subscription.overdueDays != null
    val paused = !subscription.active
    val titleColor = if (paused) colors.text2 else colors.text
    val amountColor = if (paused) colors.text2 else colors.text

    val menuItems = listOf(
        stringResource(R.string.menu_edit) to onOpen,
        stringResource(if (paused) R.string.resume else R.string.pause) to { onPauseToggle(subscription.id, !paused) },
        stringResource(R.string.delete) to onDelete,
    )
    val accessibleItems = if (!paused && subscription.nextChargeOn <= today)
        listOf(stringResource(R.string.mark_renewed) to onRenew) + menuItems else menuItems
    val rowModifier = Modifier.fillMaxWidth()
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onOpen, onLongClick = { menuOpen = true },
        )
        .testTag("subscription-${subscription.id}")
        .semantics {
            customActions = accessibleItems.map { (label, action) ->
                androidx.compose.ui.semantics.CustomAccessibilityAction(label) { action(); true }
            }
        }
        .heightIn(min = DesignTokens.rowDoubleHeight)
        .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space2)
    val content = @Composable {
        if (tablet && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f) {
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                SubscriptionAvatar(subscription.initial, subscription.groupOrdinal)
                Column(Modifier.weight(1f).padding(start = DesignTokens.space3, end = DesignTokens.space3)) {
                    Text(subscription.name, style = SnowFoldType.bodyMedium.appType(), color = titleColor)
                    Text(stringResource(subscription.cycleLabelRes) + " · " + dateShort(subscription.nextChargeOn),
                        style = SnowFoldType.caption.appType(), color = colors.text2)
                }
                if (!paused) {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, subscription.nextChargeOn).toInt()
                    ChargeBadgeView(when {
                        days < 0 -> ChargeBadge.Overdue(-days)
                        days == 0 -> ChargeBadge.Today
                        else -> ChargeBadge.InDays(days)
                    })
                }
                Box(Modifier.widthIn(min = 120.dp).padding(start = DesignTokens.space3), contentAlignment = Alignment.CenterEnd) {
                    AmountRowText(subscription.amountText, color = amountColor)
                }
                Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                    if (overdue) SfButton(stringResource(R.string.mark_renewed), onRenew,
                        style = ButtonStyle.Soft, inlineHeight = DesignTokens.buttonInlineHeight,
                        icon = AppIcon.check, testTag = "renew-${subscription.id}")
                }
            }
        } else {
            ValueRow(
                modifier = rowModifier,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubscriptionAvatar(subscription.initial, subscription.groupOrdinal)
                        Column(Modifier.padding(start = DesignTokens.space3)) {
                            Text(subscription.name, style = SnowFoldType.body.appType(), color = titleColor)
                            androidx.compose.foundation.layout.FlowRow {
                                if (overdue) Text(stringResource(R.string.overdue_days, subscription.overdueDays!!) + " · ",
                                    style = SnowFoldType.caption.appType(), color = colors.neg)
                                Text(stringResource(subscription.cycleLabelRes) + " · ", style = SnowFoldType.caption.appType(), color = if (overdue) colors.neg else colors.text2)
                                Text(dateShort(subscription.nextChargeOn), style = SnowFoldType.caption.appType(), color = if (overdue) colors.neg else colors.text2)
                            }
                        }
                    }
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        AmountRowText(subscription.amountText, color = amountColor)
                        if (overdue) SfButton(stringResource(R.string.mark_renewed), onRenew,
                            style = ButtonStyle.Soft, inlineHeight = DesignTokens.buttonInlineHeight,
                            icon = AppIcon.check, modifier = Modifier.padding(top = DesignTokens.space1),
                            testTag = "renew-${subscription.id}")
                    }
                },
            )
        }
    }

    SwipeRow(
            actions = SwipeActions(
                revealLabel = if (!paused && subscription.nextChargeOn <= today) stringResource(R.string.mark_renewed) else null,
                revealIcon = AppIcon.check,
                onReveal = if (!paused && subscription.nextChargeOn <= today) onRenew else null,
                onDelete = onDelete,
            ),
            modifier = Modifier,
        ) { content() }
        SfMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = menuItems,
            dangerIndexes = setOf(2),
        )
}
