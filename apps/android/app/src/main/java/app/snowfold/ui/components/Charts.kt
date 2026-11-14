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
import androidx.compose.ui.geometry.CornerRadius
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

/**
 * 扣费柱状图（docs/mobile-layout.md §8，对齐 iPad/iPhone）：核心 upcoming 按自然日分桶，
 * 柱高 = 当天笔数（笔数在界面统计，金额仍只用核心值）；x 轴范围取这几笔的日期跨度，
 * 柱子沿横轴铺开；逾期钳到最早一天并用负值色，其余品牌蓝；日期标签 M月d日。
 */
@Composable
fun UpcomingBarChart(
    charges: List<UpcomingUi>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 160.dp,
) {
    val colors = appColors()
    data class Bucket(val offset: Int, val count: Int, val overdue: Boolean)
    val buckets = buildList {
        val byDay = sortedMapOf<Int, IntArray>()
        charges.forEach { charge ->
            val raw = java.time.temporal.ChronoUnit.DAYS.between(today, charge.date).toInt()
            val clamped = raw.coerceAtLeast(0)
            val entry = byDay.getOrPut(clamped) { intArrayOf(0, 0) }
            entry[0] += 1
            if (raw < 0) entry[1] = 1
        }
        byDay.forEach { (day, v) -> add(Bucket(day, v[0], v[1] == 1)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (buckets.isEmpty()) {
            Text(stringResource(R.string.no_upcoming_charges), style = SnowFoldType.helper, color = colors.ink2,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
        } else {
            val minD = buckets.first().offset
            val maxD = buckets.last().offset
            val span = (maxD - minD).coerceAtLeast(1)
            val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(plotHeight)) {
                val inset = 4.dp.toPx()
                val usable = size.width - 2 * inset
                buckets.forEach { bucket ->
                    val x = inset + usable * (bucket.offset - minD) / span.toFloat()
                    val barHeight = size.height * 0.9f * bucket.count / maxCount
                    val width = 8.dp.toPx()
                    drawRoundRect(
                        color = if (bucket.overdue) colors.negative else colors.accent,
                        topLeft = Offset(x - width / 2, size.height - barHeight),
                        size = Size(width, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(minD, minD + span / 3, minD + span * 2 / 3, maxD).distinct().forEach { offset ->
                    val date = today.plusDays(offset.toLong())
                    Text(dateShort(date), style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"), color = colors.ink2)
                }
            }
        }
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
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (name, annual, amountText) ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = if (name.length > 12) name.take(11) + "…" else name,
                    style = SnowFoldType.helper,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = amountText,
                    style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(annual.toFloat() / maxAnnual)
                            .height(6.dp)
                            .background(barColor, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}
