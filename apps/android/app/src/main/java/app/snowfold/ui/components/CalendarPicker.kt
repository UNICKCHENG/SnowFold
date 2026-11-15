package app.snowfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月份日历（日期字段的内联选择器）：星期表头与月份标题来自冻结文案，
 * 选中圆底一律 brand；底部「应用日期」确认。放在卡片里使用。
 */
@Composable
fun calendarInline(
    initial: LocalDate,
    onApplied: (LocalDate) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    var month by remember(initial) { mutableStateOf(YearMonth.from(initial)) }
    var selected by remember(initial) { mutableStateOf(initial) }
    val weekdayHeads = listOf(
        stringResource(R.string.weekday_sunday),
        stringResource(R.string.weekday_monday),
        stringResource(R.string.weekday_tuesday),
        stringResource(R.string.weekday_wednesday),
        stringResource(R.string.weekday_thursday),
        stringResource(R.string.weekday_friday),
        stringResource(R.string.weekday_saturday),
    )
    val leadingBlanks = month.atDay(1).dayOfWeek.value % 7 // 周日开头
    val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.month_title, month.year, month.monthValue),
                style = SnowFoldType.headline.appType(),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Icon(
                AppIcon.chevronLeft,
                contentDescription = stringResource(R.string.cd_previous_month),
                tint = colors.text2,
                modifier = Modifier
                    .size(44.dp)
                    .padding(12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { month = month.minusMonths(1) },
            )
            Icon(
                AppIcon.chevronRight,
                contentDescription = stringResource(R.string.cd_next_month),
                tint = colors.text2,
                modifier = Modifier
                    .size(44.dp)
                    .padding(12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { month = month.plusMonths(1) },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            weekdayHeads.forEach { head ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(head, style = SnowFoldType.caption.appType(), color = colors.text2, textAlign = TextAlign.Center)
                }
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSelected = day == selected
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) colors.brand else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { selected = day },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    style = SnowFoldType.caption.appType(),
                                    color = if (isSelected) colors.onBrand else colors.text,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            app.snowfold.ui.components.SfButton(
                stringResource(R.string.apply_date),
                { onApplied(selected) },
                style = app.snowfold.ui.components.ButtonStyle.Primary,
                inlineHeight = 32.dp,
                testTag = "calendar-apply",
            )
        }
    }
}
