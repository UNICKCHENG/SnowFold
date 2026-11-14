package app.snowfold.ui.subscriptions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AnnualBars
import app.snowfold.ui.components.AppCard
import app.snowfold.ui.components.PageScaffold
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.GraphicEmptyState
import app.snowfold.ui.components.Haptics
import app.snowfold.ui.components.InfoTooltip
import app.snowfold.ui.components.StatTile
import app.snowfold.ui.components.appColors
import app.snowfold.ui.components.appIcon
import app.snowfold.ui.components.badgeLabel
import app.snowfold.ui.components.dateShort
import app.snowfold.ui.components.uiMessageText
import app.snowfold.ui.model.SubscriptionUi
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import java.time.LocalDate

/** 统计格内容：CNY 主行 + USD 次行（核心给的合并串按「 / 」拆分）。 */
@Composable
private fun StatCurrencyLines(combined: String?) {
    val tokens = appColors()
    val parts = combined?.split(" / ") ?: listOf(stringResource(R.string.out_of_range))
    Column {
        AmountText(parts.firstOrNull() ?: "", style = SnowFoldType.statValue, color = tokens.ink)
        if (parts.size > 1) {
            AmountText(
                parts.drop(1).joinToString(" / "),
                style = SnowFoldType.body.copy(fontFeatureSettings = "tnum"),
                color = tokens.ink,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 年化条形图区（§8，对齐 iPad）：CNY、USD 各一张，在订订阅按年化降序最多 8 条。 */
@Composable
private fun AnnualSection(ui: UiLedger) {
    val colors = appColors()
    val active = ui.subscriptions.filter { it.active }
    val charts = listOf("CNY" to colors.accent, "USD" to colors.usd).mapNotNull { (currency, barColor) ->
        val items = active.filter { it.currency == currency }
            .sortedByDescending { it.annualCents }.take(8)
            .map { Triple(it.name, it.annualCents, it.annualText) }
        if (items.isEmpty()) null else Triple(currency, barColor, items)
    }
    if (charts.isEmpty()) {
        AppCard {
            Text(
                stringResource(R.string.no_charges), style = SnowFoldType.body, color = colors.ink3,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    } else {
        charts.forEach { (currency, barColor, items) ->
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.annual_chart_title, currency), style = SnowFoldType.helper, color = colors.ink2)
                    AnnualBars(items, barColor, stringResource(R.string.no_charges))
                }
            }
        }
    }
}

/** 统计格内容（E）：标签 + 说明按钮 + 数值。 */
@Composable
private fun StatCell(label: String, help: String, content: @Composable () -> Unit) {
    // The help button shares the value column's height, keeping the first renewal visible.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = SnowFoldType.helper, color = appColors().ink2)
            content()
        }
        InfoTooltip(help)
    }
}

private fun groupLabelRes(group: Int): Int = when (group) {
    0 -> R.string.group_overdue
    1 -> R.string.group_this_month
    2 -> R.string.group_next_month
    3 -> R.string.group_later
    else -> R.string.group_paused
}

private fun cycleText(sub: SubscriptionUi): String = when (sub.cycle) {
    "weekly" -> SnowFoldCycleText.Weekly
    "quarterly" -> SnowFoldCycleText.Quarterly
    "yearly" -> SnowFoldCycleText.Yearly
    else -> SnowFoldCycleText.Monthly
}

private object SnowFoldCycleText {
    const val Weekly = "每周"
    const val Monthly = "每月"
    const val Quarterly = "每季"
    const val Yearly = "每年"
}

@Composable
fun SubscriptionsScreen(
    ui: UiLedger,
    bannerText: String?,
    overflowText: String?,
    onOpenSettings: () -> Unit,
    onNewSubscription: () -> Unit,
    onOpenSubscription: (String) -> Unit,
    onRenew: (String) -> Unit,
    onPauseToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    selectedSubscriptionId: String?,
    expanded: Boolean,
    paneLayout: Boolean = false,
    focusGroup: Int = -1,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    PageScaffold(
        title = stringResource(R.string.subscriptions_title),
        modifier = modifier,
        actions = {
            if (!expanded) {
                IconButton(onClick = onOpenSettings) {
                    Icon(appIcon(AppIcon.settings), contentDescription = stringResource(R.string.cd_settings), tint = tokens.ink2)
                }
            }
            IconButton(onClick = onNewSubscription) {
                Icon(appIcon(AppIcon.plus), contentDescription = stringResource(R.string.new_subscription), tint = tokens.accent)
            }
        },
    ) { pad ->
        val listState = rememberLazyListState()
        val focusMap = remember(ui, bannerText, overflowText) {
            var cursor = (if (bannerText != null) 1 else 0) + (if (overflowText != null) 1 else 0) + 1
            val map = mutableMapOf<Int, Int>()
            if (ui.subscriptions.isNotEmpty()) {
                listOf(0, 1, 2, 3, 4).forEach { group ->
                    val subs = when (group) {
                        0 -> ui.subscriptions.filter { it.active && it.groupOrdinal == 0 }
                        4 -> ui.subscriptions.filter { !it.active }
                        else -> ui.subscriptions.filter { it.active && it.groupOrdinal == group }
                    }
                    if (subs.isNotEmpty()) {
                        map[group] = cursor
                        cursor += 1 + subs.size
                    }
                }
            }
            map
        }
        androidx.compose.runtime.LaunchedEffect(focusGroup) {
            if (focusGroup >= 0) listState.animateScrollToItem(focusMap[focusGroup] ?: 0)
        }
        LazyColumn(
            state = listState,
            modifier = pad.fillMaxWidth().testTag("subscriptions-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = DesignTokens.pageMargin,
            end = DesignTokens.pageMargin,
            top = DesignTokens.space3,
            bottom = DesignTokens.space4 + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space2),
    ) {
        if (bannerText != null) item { app.snowfold.ui.components.ErrorBanner(text = bannerText) }
        if (overflowText != null) item { app.snowfold.ui.components.ErrorBanner(text = overflowText) }

        item {
            // E：四项统计合并进一张卡，2×2，带说明按钮。
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            StatCell(stringResource(R.string.due_this_month), stringResource(R.string.due_this_month_help)) {
                                StatCurrencyLines(ui.stats?.dueThisMonthText)
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            StatCell(stringResource(R.string.monthly_spending), stringResource(R.string.monthly_spending_help)) {
                                StatCurrencyLines(ui.stats?.monthlyText)
                            }
                        }
                    }
                    HorizontalDivider(color = tokens.hairline)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            StatCell(stringResource(R.string.annual_spending), stringResource(R.string.annual_spending_help)) {
                                StatCurrencyLines(ui.stats?.annualText)
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            StatCell(stringResource(R.string.next_charge), stringResource(R.string.next_charge) + " · " + stringResource(R.string.due_this_month_help)) {
                                val next = ui.nextCharge
                                if (next != null) {
                                    Column {
                                        Text(
                                            dateShort(LocalDate.parse(next.nextChargeRaw)),
                                            style = SnowFoldType.statValue.copy(fontFeatureSettings = "tnum"),
                                            color = tokens.ink,
                                        )
                                        Text(
                                            next.name ?: stringResource(R.string.no_charges),
                                            style = SnowFoldType.helper,
                                            color = tokens.ink2,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Text(stringResource(R.string.no_charges), style = SnowFoldType.statValue, color = tokens.ink2)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (ui.subscriptions.isEmpty()) {
            item {
                AppCard {
                    GraphicEmptyState(
                        iconRes = AppIcon.subscriptions,
                        title = stringResource(R.string.no_subscriptions),
                        support = stringResource(R.string.no_subscriptions_help),
                        iconContainer = tokens.accent.copy(alpha = 0.12f),
                        iconTint = tokens.accent,
                        action = {
                            FilledTonalButton(onClick = onNewSubscription) {
                                Text(stringResource(R.string.new_subscription))
                            }
                        },
                    )
                }
            }
        } else {
            val grouped = (0..4).map { group -> group to ui.subscriptions.filter { it.groupOrdinal == group }.sortedBy { it.nextChargeOn } }
            grouped.forEach { (group, subs) ->
                if (subs.isEmpty() && group != 0) return@forEach
                item(key = "group-$group") {
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("subscription-group-$group")
                        .padding(top = DesignTokens.space2)) {
                        Text(
                            text = when (group) {
                                4 -> stringResource(R.string.group_paused)
                                else -> stringResource(groupLabelRes(group))
                            },
                            style = SnowFoldType.sectionHeader,
                            color = if (group == 0) tokens.negative else tokens.ink2,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            text = subs.size.toString(),
                            style = SnowFoldType.badge,
                            color = tokens.ink3,
                        )
                    }
                }
                items(subs, key = { it.id }) { sub ->
                    SubscriptionRow(
                        sub = sub,
                        isOverdueGroup = group == 0,
                        selected = sub.id == selectedSubscriptionId,
                        expanded = expanded && paneLayout,
                        onOpen = { onOpenSubscription(sub.id) },
                        onRenew = { onRenew(sub.id) },
                        onPauseToggle = { onPauseToggle(sub.id, !sub.active) },
                        onDelete = { onDelete(sub.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (group == 0 && expanded) item(key = "annual-chart") { AnnualSection(ui) }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.SubscriptionRow(
    sub: SubscriptionUi,
    isOverdueGroup: Boolean,
    selected: Boolean,
    expanded: Boolean = false,
    onOpen: () -> Unit,
    onRenew: () -> Unit,
    onPauseToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    // 手势回调在越过阈值与落定时会各触发一次，只处理第一次。
    var swipeHandled by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(swipeHandled) { if (swipeHandled) { kotlinx.coroutines.delay(500); swipeHandled = false } }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (swipeHandled) return@rememberSwipeToDismissBoxState false
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    swipeHandled = true
                    Haptics.light(view)
                    onPauseToggle()
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    swipeHandled = true
                    Haptics.warning(view)
                    onDelete()
                }

                else -> return@rememberSwipeToDismissBoxState false
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    var menuOpen by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        modifier = modifier,
        backgroundContent = {
            val alignment = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                Alignment.CenterEnd
            } else {
                Alignment.CenterStart
            }
            val tint = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) Color.Transparent else if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) tokens.negative else tokens.accent
            val label = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                stringResource(R.string.cd_delete_subscription)
            } else if (sub.active) {
                stringResource(R.string.pause)
            } else {
                stringResource(R.string.resume)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp).clearAndSetSemantics {},
                contentAlignment = alignment,
            ) {
                Text(label, style = SnowFoldType.body, color = tint, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        BoxWithConstraints {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
            val contentAlpha by animateFloatAsState(if (sub.active) 1f else 0.62f, label = "rowAlpha")
            // 待确认行的尾部是「已续费」按钮，金额必须另起一行显示，不能被按钮顶掉。
            val twoLine = expanded || (isOverdueGroup && sub.active)
            val trailing: @Composable () -> Unit = {
                if (isOverdueGroup && sub.active) FilledTonalButton(
                    onClick = { Haptics.success(view); onRenew() },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = tokens.accent.copy(alpha = 0.12f), contentColor = tokens.accent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                ) { Text(stringResource(R.string.mark_renewed), style = SnowFoldType.badge) }
                else AmountText(sub.amountText, SnowFoldType.rowValue, Modifier.fillMaxWidth(), tokens.ink)
            }
            Column(
                Modifier.fillMaxWidth().alpha(contentAlpha)
                    .background(if (selected) tokens.accent.copy(alpha = 0.10f) else tokens.card, RoundedCornerShape(DesignTokens.tileCorner))
                    .testTag("subscription-${sub.id}")
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit)) }, onClick = { menuOpen = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(if (sub.active) R.string.pause else R.string.resume)) }, onClick = { menuOpen = false; onPauseToggle() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete)) }, onClick = { menuOpen = false; onDelete() })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(
                        if (isOverdueGroup && sub.active) tokens.negative.copy(alpha = 0.16f) else tokens.ink2.copy(alpha = 0.12f),
                        RoundedCornerShape(DesignTokens.chipCorner)), contentAlignment = Alignment.Center) {
                        Text(sub.initial, style = SnowFoldType.rowTitle, color = if (isOverdueGroup) tokens.negative else tokens.ink2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, style = SnowFoldType.rowTitle, color = tokens.ink, maxLines = if (stacked) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        Text("${dateShort(sub.nextChargeOn)} · ${stringResource(sub.cycleLabelRes)}", style = SnowFoldType.helper,
                            color = if (isOverdueGroup) tokens.negative else tokens.ink2)
                        if (sub.overdueDays != null && sub.active) Text(stringResource(R.string.overdue_days_compact, sub.overdueDays),
                            style = SnowFoldType.helper, color = tokens.negative)
                        if (!sub.active) Text(stringResource(R.string.paused_badge), style = SnowFoldType.badge, color = tokens.ink2)
                    }
                    if (!stacked && !twoLine) Box(Modifier.weight(0.9f)) { trailing() }
                }
                if (twoLine) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AmountText(sub.amountText, SnowFoldType.rowValue, Modifier, tokens.ink)
                        Spacer(Modifier.weight(1f))
                        if (isOverdueGroup && sub.active) trailing()
                    }
                }
                if (stacked && !twoLine) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
    }
}
