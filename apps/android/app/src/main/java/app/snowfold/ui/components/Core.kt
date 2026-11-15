package app.snowfold.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import app.snowfold.R
import app.snowfold.UiMessage
import app.snowfold.ui.model.ChargeBadge
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import app.snowfold.ui.theme.categoryTint
import java.time.DayOfWeek
import java.time.LocalDate

// ── 日期与文案 ────────────────────────────────────────────────────────────

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

// ── 金额排版（design.md §2）─────────────────────────────────────────────

/** 金额层级：display/title 走分级小数；列表金额为均匀正文中等。 */
enum class AmountLevel(val style: TextStyle, val decimalStyle: TextStyle?) {
    Display(
        SnowFoldType.display,
        SnowFoldType.title.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
    ),
    Title(
        SnowFoldType.title,
        SnowFoldType.headline.copy(fontWeight = FontWeight.Medium),
    ),
    Body(SnowFoldType.bodyMedium, null),
}

/**
 * 金额一律等宽数字；焦点金额的负号与整数段同级、同基线，
 * 币种符与小数段降一级，小数段为 text2 色（负数整串 neg）；放不下时整体等比缩到最小 60%，
 * 仍放不下才换行。任何金额都不得省略或截断。
 */
@Composable
fun AmountText(
    value: String,
    level: AmountLevel,
    modifier: Modifier = Modifier,
    negative: Boolean = false,
    color: Color = Color.Unspecified,
    minScale: Float = 0.6f,
) {
    val tokens = appColors()
    val base = if (color.isUnspecified()) { if (negative) tokens.neg else tokens.text } else color
    val decimalColor = if (negative) tokens.neg else tokens.text2
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val annotated = remember(value, level, base, decimalColor) {
            buildAmountString(value, level, base, decimalColor)
        }
        val widthPx = constraints.maxWidth
        val fitted = remember(annotated, widthPx, density.density, density.fontScale, minScale) {
            if (widthPx == Constraints.Infinity) 1f
            else fitScale(measurer, annotated, level.style, widthPx, minScale)
        }
        Text(
            text = annotated.scaled(fitted),
            color = base,
            style = level.style
                .copy(fontSize = level.style.fontSize * fitted, lineHeight = level.style.lineHeight * fitted)
                .appType(),
            softWrap = true,
        )
    }
}

private fun Color.isUnspecified(): Boolean = this == Color.Unspecified

/** 列表行等均匀金额（正文中等 + tnum），无分级。 */
@Composable
fun AmountRowText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    AmountText(value, AmountLevel.Body, modifier, color = color)

}

/**
 * 「−¥1,234,567.89」→ [−¥][1,234,567][.89]：
 * 负号用整数段字号、同基线；币种符与小数段降一级，小数段为 text2 色。
 */
private fun buildAmountString(value: String, level: AmountLevel, base: Color, decimalColor: Color) =
    buildAnnotatedString {
        val decimal = level.decimalStyle
        val decimalStart = if (decimal == null) -1 else value.lastIndexOf('.')
        if (decimal == null || decimalStart <= 0) {
            append(value)
            return@buildAnnotatedString
        }
        val signEnd = if (value.startsWith("−")) 1 else 0
        val currencyEnd = if (value.getOrNull(signEnd) in listOf('¥', '$')) signEnd + 1 else signEnd
        if (signEnd > 0) withStyle(SpanStyle(fontSize = level.style.fontSize, color = base)) {
            append(value.substring(0, signEnd))
        }
        if (currencyEnd > signEnd) withStyle(SpanStyle(fontSize = decimal.fontSize, fontWeight = FontWeight.Medium, color = base)) {
            append(value.substring(signEnd, currencyEnd))
        }
        withStyle(SpanStyle(fontSize = level.style.fontSize, color = base)) {
            append(value.substring(currencyEnd, decimalStart))
        }
        withStyle(SpanStyle(fontSize = decimal.fontSize, fontWeight = FontWeight.Medium, color = decimalColor)) {
            append(value.substring(decimalStart))
        }
    }

private fun androidx.compose.ui.text.AnnotatedString.scaled(ratio: Float) =
    androidx.compose.ui.text.AnnotatedString(
        text = text,
        spanStyles = spanStyles.map { range ->
            androidx.compose.ui.text.AnnotatedString.Range(
                range.item.copy(fontSize = range.item.fontSize * ratio), range.start, range.end,
            )
        },
    )

private fun fitScale(
    measurer: TextMeasurer,
    text: androidx.compose.ui.text.AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    minScale: Float,
): Float {
    fun fits(ratio: Float) = measurer.measure(
        text = text.scaled(ratio),
        style = style.appType().copy(fontSize = style.fontSize * ratio, lineHeight = style.lineHeight * ratio),
        softWrap = true,
        maxLines = 1,
    ).size.width <= widthPx - 2
    if (fits(1f)) return 1f
    if (!fits(minScale)) return minScale // 仍放不下：换行由 softWrap 接管
    var lower = minScale
    var upper = 1f
    repeat(8) {
        val middle = (lower + upper) / 2f
        if (fits(middle)) lower = middle else upper = middle
    }
    return lower
}

// ── 浮层材质（毛玻璃，§1/§7）────────────────────────────────────────────

/** 记录内容层，供浮层材质在其上做模糊。挂在整页内容上。 */
class GlassSource(val layer: androidx.compose.ui.graphics.layer.GraphicsLayer)

/** 内容层记忆工厂：rememberGraphicsLayer 只能在组合里创建。 */
@Composable
fun rememberGlassSource(): GlassSource = GlassSource(rememberGraphicsLayer())

/** 内容层：绘制时把内容同时记录进 GraphicsLayer。 */
fun Modifier.glassSource(source: GlassSource): Modifier = drawWithContent {
    source.layer.record { this@drawWithContent.drawContent() }
    drawContent()
}

/**
 * 浮层材质：Android 12+ 真模糊（blur 24 + 饱和 1.6 + 浮层色 84%），
 * 低版本按安卓任务书保留半透明底；外加 0.5 像素亮边（§1）。
 * offset = 玻璃块相对内容层原点的偏移（px），由调用方用 onGloballyPositioned 提供。
 */
@Composable
fun Modifier.glassPanel(
    source: GlassSource,
    shape: Shape,
    offset: androidx.compose.ui.unit.IntOffset,
    cornerRadius: androidx.compose.ui.unit.Dp,
): Modifier {
    val colors = appColors()
    val blurredLayer = rememberGraphicsLayer()
    return this
        .floatingShadow(shape)
        .graphicsLayer(shape = shape, compositingStrategy = CompositingStrategy.Offscreen, clip = true)
        .drawWithCache {
            val blurPx = 24.dp.toPx()
            val edge = 0.5.dp.toPx()
            val canBlur = Build.VERSION.SDK_INT >= 31
            val effect = if (canBlur) {
                val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                    blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                val matrix = android.graphics.ColorMatrix().apply { setSaturation(1.6f) }
                val sat = android.graphics.RenderEffect.createColorFilterEffect(
                    android.graphics.ColorMatrixColorFilter(matrix), blurEffect)
                sat.asComposeRenderEffect()
            } else {
                null
            }
            val ox = offset.x.toFloat()
            val oy = offset.y.toFloat()
            onDrawWithContent {
                if (canBlur) {
                    blurredLayer.record {
                        translate(-ox, -oy) { drawLayer(source.layer) }
                    }
                    blurredLayer.renderEffect = effect
                    drawLayer(blurredLayer)
                }
                drawContent()
            }
        }
        .background(colors.glass, shape)
        .drawWithContent {
            drawContent()
            drawRoundRect(
                color = colors.glassEdge,
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(0.5.dp.toPx()),
            )
        }
}

// ── 卡片、分区头、分隔线 ────────────────────────────────────────────────

/** 卡片：card 底、圆角 16、无阴影无描边（§4）。 */
@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = appColors()
    Column(
        modifier = (if (tag != null) modifier.platformTag(tag) else modifier)
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.cardCorner))
            .background(colors.card),
        content = content,
    )
}

/** 分区头：分区标题级；分组头在卡片外，左缩进 4（§5.4）。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = appColors()
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(modifier.fillMaxWidth()) {
            Text(title, style = SnowFoldType.headline.appType(), color = colors.text)
            if (trailing != null) trailing()
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SnowFoldType.headline.appType(), color = colors.text, modifier = Modifier.weight(1f))
            if (trailing != null) trailing()
        }
    }

}

/** 行分隔线：1px sep，从文字起始处开始（startInset = 16 + 图标块 + 12）。 */
@Composable
fun RowDivider(startInset: Dp = 60.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(appColors().sep),
    )
}

// ── 徽标（§5.5）────────────────────────────────────────────────────────

@Composable
fun ChargeBadgeView(badge: ChargeBadge, modifier: Modifier = Modifier) {
    val colors = appColors()
    if (badge is ChargeBadge.InDays && badge.days > 7) {
        Text(
            badgeLabel(badge),
            style = SnowFoldType.captionMedium.appType(),
            color = colors.text2,
            modifier = modifier,
        )
    } else {
        val (bg, fg) = when (badge) {
            is ChargeBadge.Overdue -> colors.negSoft to colors.neg
            ChargeBadge.Today -> colors.negSoft to colors.neg
            is ChargeBadge.InDays -> colors.warnSoft to colors.warn
        }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(99.dp))
                .background(bg)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(badgeLabel(badge), style = SnowFoldType.captionMedium.appType(), color = fg)
        }
    }
}

@Composable
fun PillBadge(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SnowFoldType.captionMedium.appType(), color = fg)
    }
}

// ── 分类图标块与头像 ────────────────────────────────────────────────────

@Composable
fun CategoryIconBlock(index: Int, size: Dp, modifier: Modifier = Modifier, filled: Boolean = false) {
    val colors = appColors()
    val (fg, bg) = categoryTint(index, colors)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (filled) AppIcon.categoryFill(index) else AppIcon.category(index),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(size * 0.625f),
        )
    }
}

@Composable
fun SubscriptionAvatar(initial: String, groupOrdinal: Int, modifier: Modifier = Modifier) {
    val colors = appColors()
    val (fg, bg) = when {
        groupOrdinal == 0 -> colors.neg to colors.negSoft
        groupOrdinal == 4 -> colors.text2 to colors.bg
        else -> colors.brand to colors.brandSoft
    }
    Box(
        modifier = modifier.size(DesignTokens.avatar).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = SnowFoldType.bodyMedium.appType(), color = fg)
    }
}

// ── 空状态（§5.7）──────────────────────────────────────────────────────

/** 整页空状态的视觉中心略高；最大字号下内容超高时仍能滚动。 */
@Composable
fun EmptyPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).padding(bottom = maxHeight * 0.08f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    help: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = appColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).background(colors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.brand, modifier = Modifier.size(48.dp))
        }
        Text(
            title,
            style = SnowFoldType.headline.appType(),
            color = colors.text,
            modifier = Modifier.padding(top = DesignTokens.space6),
        )
        if (help != null) {
            Text(
                help,
                style = SnowFoldType.body.appType(),
                color = colors.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp).padding(top = DesignTokens.space2),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(DesignTokens.space6))
            action()
        }
    }
}

/** 分类紧凑空状态行（§5.7）：图标块 + 「暂无{分类}」+「添加」浅色按钮。 */
@Composable
fun CategoryEmptyRow(categoryIndex: Int, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = appColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIconBlock(categoryIndex, DesignTokens.iconBlock)
        Text(
            stringResource(R.string.category_empty, categoryLabel(categoryIndex)),
            style = SnowFoldType.body.appType(),
            color = colors.text2,
            modifier = Modifier.weight(1f).padding(start = DesignTokens.space3),
        )
        SoftButton(stringResource(R.string.add), onAdd, icon = AppIcon.plus)
    }
}

@Composable
fun categoryLabel(index: Int): String = stringResource(
    when (index) {
        0 -> R.string.category_cash
        1 -> R.string.category_fixed
        2 -> R.string.category_investment
        3 -> R.string.category_receivable
        else -> R.string.category_debt
    },
)

/** 负债行说明（§9.2：负债行下行改为「不计入资产构成」）。 */
@Composable
fun compositionExcludedText(): String = stringResource(R.string.composition_excluded)

/**
 * 统计口径说明按钮（§6.1）：info 16，读屏名带指标名（「月均支出」「年化支出」），
 * 点开居中卡片显示口径说明。
 */
@Composable
fun StatHelpDialog(help: String, a11yLabel: String, modifier: Modifier = Modifier) {
    val colors = appColors()
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val closeInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Icon(
        AppIcon.info,
        contentDescription = a11yLabel,
        tint = colors.text2,
        modifier = modifier
            .size(16.dp)
            .clickable(
                interactionSource = closeInteraction,
                indication = null,
            ) { open = true },
    )
    if (open) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DesignTokens.cardCorner))
                    .background(colors.card).padding(DesignTokens.space4),
            ) {
                Text(help, style = SnowFoldType.caption.appType(), color = colors.text)
                Spacer(Modifier.height(DesignTokens.space3))
                SfButton(stringResource(R.string.done), { open = false }, style = ButtonStyle.Secondary)
            }
        }
    }

}

/** 变动记录时间：2026年11月14日 09:41。 */
@Composable
fun historyTimestamp(recordedAt: String): String {
    val parsed = runCatching {
        java.time.OffsetDateTime.parse(recordedAt).toLocalDateTime()
    }.getOrNull() ?: return recordedAt
    val d = parsed.toLocalDate()
    val time = parsed.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return stringResource(R.string.date_long, d.year, d.monthValue, d.dayOfMonth) + " " + time
}

// ── 触感（§6：续费 success、删除 warning、撤销 light）────────────────────

class Haptics(private val view: android.view.View) {
    fun success() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun warning() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun light() {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** 同步按钮旋转（1 秒/圈；减少动态效果时不转）。 */
@Composable
fun Modifier.syncSpin(enabled: Boolean, reduceMotion: Boolean): Modifier {
    if (!enabled || reduceMotion) return this
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "angle",
    )
    return this.graphicsLayer(rotationZ = angle)
}

/** 大字号与长金额时把值放到下一行，名称与日期始终保留完整宽度。 */
@Composable
fun ValueRow(
    modifier: Modifier = Modifier,
    stacked: Boolean = LocalDensity.current.fontScale > 1.3f,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    if (stacked) {
        Column(modifier) {
            leading()
            Spacer(Modifier.height(DesignTokens.space2))
            trailing()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { leading() }
            Spacer(Modifier.width(DesignTokens.space3))
            trailing()
        }
    }
}

/** 浮层专用阴影；卡片不调用，深浅透明度沿用 design.md §4。 */
@Composable
fun Modifier.floatingShadow(shape: Shape): Modifier {
    val dark = appColors().isDark
    return shadow(16.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.3f else 0.06f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.14f))
}
