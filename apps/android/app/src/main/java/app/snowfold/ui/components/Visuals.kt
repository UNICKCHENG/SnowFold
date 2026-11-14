package app.snowfold.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.model.ChargeBadge
import app.snowfold.UiMessage
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import java.time.DayOfWeek
import java.time.LocalDate

/** 金额先在 60%–100% 间按实际字宽适配，仍放不下则换行；绝不裁掉小数。 */
@Composable
fun AmountText(
    value: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    animate: Boolean = false,
    maxLines: Int = 1,
    decimalHierarchy: Boolean = false,
) {
    val resolved = style.copy(fontFeatureSettings = "tnum", fontWeight = fontWeight ?: style.fontWeight)
    if (animate) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically { it / 3 } + fadeIn())
                    .togetherWith(slideOutVertically { -it / 3 } + fadeOut())
            },
            modifier = modifier,
            label = "amount",
        ) { current ->
            FittedAmount(current, resolved, color, decimalHierarchy)
        }
    } else {
        FittedAmount(value, resolved, color, decimalHierarchy, modifier)
    }
}

@Composable
private fun FittedAmount(
    value: String,
    style: TextStyle,
    color: Color,
    decimalHierarchy: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth
        fun amountText(ratio: Float, wrapDecimal: Boolean) = buildAnnotatedString {
            val decimalStart = value.lastIndexOf('.')
            if (decimalStart > 0) {
                append(value.substring(0, decimalStart))
                if (wrapDecimal) append("\n")
                withStyle(SpanStyle(
                    fontSize = style.fontSize * ratio * if (decimalHierarchy) 0.72f else 1f,
                    color = if (decimalHierarchy) resolvedColor.copy(alpha = 0.7f) else resolvedColor,
                )) { append(value.substring(decimalStart)) }
            } else append(value)
        }
        val fit = remember(value, style, widthPx, density.density, density.fontScale, decimalHierarchy) {
            fun fits(ratio: Float) = measurer.measure(
                text = amountText(ratio, false),
                style = style.copy(fontSize = style.fontSize * ratio, lineHeight = style.lineHeight * ratio),
                softWrap = false, maxLines = 1,
            ).size.width <= widthPx - 2
            // Android 14+ font scaling is nonlinear; measure each candidate rather than
            // multiplying an initial width by a ratio, which can strand the final digit.
            when {
                widthPx == Constraints.Infinity || fits(1f) -> 1f to false
                !fits(0.45f) -> 0.45f to true
                else -> {
                    var lower = 0.45f
                    var upper = 1f
                    repeat(9) {
                        val middle = (lower + upper) / 2f
                        if (fits(middle)) lower = middle else upper = middle
                    }
                    lower to false
                }
            }
        }
        val fittedStyle = style.copy(fontSize = style.fontSize * fit.first, lineHeight = style.lineHeight * fit.first)
        val text = amountText(fit.first, fit.second)
        Text(text = text, style = fittedStyle, color = resolvedColor, softWrap = true)
    }
}

@Composable
fun dateShort(date: LocalDate): String =
    stringResource(R.string.date_short, date.monthValue, date.dayOfMonth)

@Composable
fun dateLong(date: LocalDate): String =
    stringResource(R.string.date_long, date.year, date.monthValue, date.dayOfMonth)

@Composable
fun weekdayChar(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> stringResource(R.string.weekday_monday)
    DayOfWeek.TUESDAY -> stringResource(R.string.weekday_tuesday)
    DayOfWeek.WEDNESDAY -> stringResource(R.string.weekday_wednesday)
    DayOfWeek.THURSDAY -> stringResource(R.string.weekday_thursday)
    DayOfWeek.FRIDAY -> stringResource(R.string.weekday_friday)
    DayOfWeek.SATURDAY -> stringResource(R.string.weekday_saturday)
    else -> stringResource(R.string.weekday_sunday)
}

@Composable
fun badgeLabel(badge: ChargeBadge): String = when (badge) {
    is ChargeBadge.Overdue -> stringResource(R.string.overdue_days, badge.days)
    ChargeBadge.Today -> stringResource(R.string.today)
    is ChargeBadge.InDays -> stringResource(R.string.days_until_charge, badge.days)
}

/** 详情字符串先解析，再接纯文本参数（{detail} 用核心错误描述）。 */
@Composable
fun uiMessageText(message: UiMessage): String {
    val resolved = message.argRes.map { stringResource(it) } + message.args
    return stringResource(message.res, *resolved.toTypedArray())
}

/** 屏幕大标题（§2 页大标题 34 bold）+ 副标题。 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    AppIcon.back,
                    contentDescription = stringResource(app.snowfold.R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SnowFoldType.pageTitle, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SnowFoldType.helper,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** 分区头（§2 13 semibold ink-2）+ 可选右侧链接。 */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DesignTokens.sectionGap, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = SnowFoldType.sectionHeader,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** 卡片基底（§1.4）：card 实色 + hairline 1px 描边，圆角 18，不用阴影。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    container: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = appColors()
    val shape = RoundedCornerShape(DesignTokens.cardCorner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (container == Color.Unspecified) tokens.card else container)
            .border(1.dp, tokens.hairline, shape),
        content = content,
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SnowFoldType.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    val tokens = appColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.negative.copy(alpha = 0.12f), RoundedCornerShape(DesignTokens.tileCorner))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = text, style = SnowFoldType.body, color = tokens.negative)
    }
}

/** 占比条（§5.1 4pt 圆角）。 */
@Composable
fun ShareBar(fraction: Float, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(DesignTokens.shareBarHeight)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(99.dp)),
    ) {
        Box(
            modifier = Modifier
                .height(DesignTokens.shareBarHeight)
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(tint, RoundedCornerShape(99.dp)),
        )
    }
}

@Composable
fun InfoTooltip(help: String, modifier: Modifier = Modifier) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(AppIcon.info, stringResource(R.string.cd_stats_info), modifier = Modifier.size(16.dp))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        text = { Text(help) },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } },
    )
}

/** 不可逆操作确认（标题/正文冻结文案，确认按钮 destructive）。 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = true,
) {
    val tokens = appColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DesignTokens.sheetCorner),
        containerColor = tokens.card,
        title = { Text(title, style = SnowFoldType.statValue) },
        text = { Text(body, style = SnowFoldType.body, color = tokens.ink2) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    text = confirmLabel,
                    style = SnowFoldType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = if (danger) tokens.negative else tokens.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = SnowFoldType.body, color = tokens.accent)
            }
        },
    )
}

/** 统计格（§1.4 tile 底，圆角 14）。 */
@Composable
fun StatTile(
    label: String,
    modifier: Modifier = Modifier,
    help: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = appColors()
    Column(
        modifier = modifier
            .background(tokens.tile, RoundedCornerShape(DesignTokens.tileCorner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = SnowFoldType.helper,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (help != null) help()
        }
        Box(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

/**
 * 分段控件（§5/§7）：capsule 外观，选中项底色 card、字重加深，
 * 颜色过渡 200ms 近似 iOS 分段的滑动高亮。
 */
@Composable
fun SnowFoldSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.tile, RoundedCornerShape(7.dp))
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) tokens.card else Color.Transparent,
                label = "segmentBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) tokens.ink else tokens.ink2,
                label = "segmentText",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, RoundedCornerShape(7.dp))
                        .heightIn(min = 48.dp).padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = SnowFoldType.body,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 图形化空状态（§6）：着色圆底图标 + 说明 + 主操作。 */
@Composable
fun GraphicEmptyState(
    iconRes: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    support: String?,
    iconContainer: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(iconContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(appIcon(iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        }
        Text(text = title, style = SnowFoldType.statValue, modifier = Modifier.padding(top = 12.dp))
        if (support != null) {
            Text(
                text = support,
                style = SnowFoldType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (action != null) action()
    }
}

/** 设置行（§6）：标题 + 行内说明，整行可点；danger 行文案用 negative。 */
@Composable
fun SettingRow(
    title: String,
    help: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val tokens = appColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = SnowFoldType.rowTitle,
            color = if (danger) tokens.negative else tokens.ink,
        )
        if (help != null) {
            Text(
                text = help,
                style = SnowFoldType.helper,
                color = tokens.ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 语义色与分类色转发（组件包内统一入口）。 */
@Composable
fun appColors(): app.snowfold.ui.theme.SnowFoldColors = app.snowfold.ui.theme.appColors()

fun categoryTint(index: Int, colors: app.snowfold.ui.theme.SnowFoldColors): Pair<Color, Color> =
    app.snowfold.ui.theme.categoryTint(index, colors)

/** 页面基底：canvas 底色 + 系统栏内边距由外层处理，这里只提供统一滚动容器。 */
@Composable
fun AppScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = appColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.canvas),
        content = content,
    )
}
