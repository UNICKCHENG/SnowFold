package app.snowfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snowfold.R
import java.time.LocalDate
import java.time.YearMonth

/**
 * Month calendar used by every date field. Weekday captions and the month
 * title come from the frozen copy resources, so the picker reads the same as
 * the rest of the app regardless of the system locale. Plain rows keep the
 * calendar usable inside scrollable sheet content.
 */
@Composable
fun CalendarPicker(
    initial: LocalDate,
    onApplied: (LocalDate) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var selected by remember { mutableStateOf(initial) }
    val weekdayHeads = listOf(
        stringResource(R.string.weekday_sunday),
        stringResource(R.string.weekday_monday),
        stringResource(R.string.weekday_tuesday),
        stringResource(R.string.weekday_wednesday),
        stringResource(R.string.weekday_thursday),
        stringResource(R.string.weekday_friday),
        stringResource(R.string.weekday_saturday),
    )
    val leadingBlanks = month.atDay(1).dayOfWeek.value % 7 // Sunday-first grid
    val cells: List<LocalDate?> =
        List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.month_title, month.year, month.monthValue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.cd_previous_month),
                )
            }
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(R.string.cd_next_month),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weekdayHeads.forEach { head ->
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = head,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day != null) {
                            val isSelected = day == selected
                            val isToday = day == LocalDate.now()
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape,
                                    )
                                    .clickable { selected = day },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancelled) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { onApplied(selected) }) { Text(stringResource(R.string.apply_date)) }
        }
    }
}
