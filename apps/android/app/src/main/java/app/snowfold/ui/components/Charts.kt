package app.snowfold.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.snowfold.R
import app.snowfold.ui.model.UpcomingUi
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.ui.model.TimelineDayUi
import app.snowfold.ui.theme.SnowFoldType

/**
 * 资产构成环形图（docs/design.md §5.1）：内半径 0.62×外半径，扇区间隙 2°，
 * 圆头近似 4pt 圆角；环 = 总资产四类（负债不计入）；中心「总资产」+ 等宽金额；
 * 合计 0 → 居中空态文案。无图例（分类卡即图例）。
 */
@Composable
fun CompositionDonut(
    amounts: List<Long>,
    colors: List<Color>,
    totalText: String,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val total = amounts.sumOf { it.coerceAtLeast(0).toDouble() }
    val ink3 = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val gapAngle = 2f
            val strokeWidth = 14.dp.toPx()
            if (total <= 0L) {
                drawCircle(
                    color = ink3,
                    radius = size.minDimension / 2 - strokeWidth / 2,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                return@Canvas
            }
            var start = -90f
            val inset = strokeWidth / 2
            amounts.forEachIndexed { index, amount ->
                val sweep = (360.0 * amount / total).toFloat()
                val visible = sweep - gapAngle
                if (visible > 0.1f) {
                    drawArc(
                        color = colors[index],
                        startAngle = start + gapAngle / 2,
                        sweepAngle = visible,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    )
                }
                start += sweep
            }
        }
        if (total <= 0L) {
            Text(
                text = emptyText,
                style = SnowFoldType.helper,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = totalText,
                    style = SnowFoldType.rowValue.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 日期轴上的每个标记代表核心 upcoming 返回的一笔扣费，不汇总日金额。 */
@Composable
fun UpcomingTimeline(
    charges: List<UpcomingUi>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 56.dp,
) {
    val colors = appColors()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = charges.firstOrNull { it.id == selectedId } ?: charges.firstOrNull()
    val description = stringResource(R.string.timeline_description)
    val entries = charges.joinToString("；") { "${it.date} ${it.name} ${it.amountText}" }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(description, style = SnowFoldType.helper, color = colors.ink2)
        Canvas(
            Modifier.fillMaxWidth().height(plotHeight)
                .semantics { contentDescription = "$description；$entries" }
                .pointerInput(charges) {
                    detectTapGestures { position ->
                        selectedId = charges.minByOrNull {
                            val day = ChronoUnit.DAYS.between(today, it.date).coerceIn(0, 29)
                            kotlin.math.abs(position.x / size.width - day / 29f)
                        }?.id
                    }
                },
        ) {
            val inset = 5.dp.toPx()
            val usable = size.width - 2 * inset
            drawLine(colors.hairline, Offset(inset, size.height - 2.dp.toPx()), Offset(size.width - inset, size.height - 2.dp.toPx()), 1.dp.toPx())
            charges.forEachIndexed { index, charge ->
                val day = ChronoUnit.DAYS.between(today, charge.date).coerceIn(0, 29)
                val x = inset + usable * day / 29f
                val tint = when {
                    charge.date < today -> colors.negative
                    charge.date == today -> colors.accent
                    else -> colors.accent
                }
                // 同一天的多笔使用不同高度，仍保持各自标记，不相加金额。
                val height = size.height * (0.45f + (index % 3) * 0.2f)
                drawLine(tint.copy(alpha = if (charge.id == selected?.id) 1f else 0.55f),
                    Offset(x, size.height - 2.dp.toPx()), Offset(x, size.height - height), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(tint, 4.dp.toPx(), Offset(x, size.height - height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0L, 9L, 19L, 29L).forEach { offset ->
                if (offset == 0L) {
                    Text(stringResource(R.string.today), style = SnowFoldType.helper, color = colors.ink2)
                } else {
                    val date = today.plusDays(offset)
                    Text("${date.monthValue}/${date.dayOfMonth}", style = SnowFoldType.helper, color = colors.ink2)
                }
            }
        }
        charges.distinctBy { it.id }.forEach { charge ->
            val tint = if (charge.date < today) colors.negative else colors.ink
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${dateShort(charge.date)} · ${charge.name}", style = SnowFoldType.helper, color = tint)
                AmountText(charge.amountText, SnowFoldType.rowValue, Modifier.fillMaxWidth(), tint)
            }
        }
        if (charges.isEmpty()) Text(stringResource(R.string.no_upcoming_charges), style = SnowFoldType.helper, color = colors.ink2)
    }
}

/**
 * 年化支出条形图（docs/design.md §5.4）：每条 = 一项在订订阅的年化金额
 * （核心 subscription_annual），按年化降序最多 8 条，名称截断 12 字，
 * 金额等宽带符号，条圆角 4；空 → 「暂无扣费」。
 */
@Composable
fun AnnualBars(
    items: List<Triple<String, Long, String>>,
    barColor: Color,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = SnowFoldType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        )
        return
    }
    val maxAnnual = items.maxOf { it.second }.coerceAtLeast(1L)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (name, annual, amountText) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = name.take(12),
                    style = SnowFoldType.helper,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(96.dp),
                )
                Box(modifier = Modifier.weight(1f).height(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(annual.toFloat() / maxAnnual)
                            .height(10.dp)
                            .background(barColor, RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    text = amountText,
                    style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
