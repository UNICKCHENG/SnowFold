package app.snowfold.ui.editors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.Haptics
import app.snowfold.ui.components.SnowFoldSegmented
import app.snowfold.ui.components.appColors
import app.snowfold.ui.components.appIcon
import app.snowfold.ui.components.CalendarPicker
import app.snowfold.ui.components.dateShort
import app.snowfold.ui.model.SubscriptionUi
import app.snowfold.ui.theme.SnowFoldType
import java.time.LocalDate

private fun String.moneyInput(): String = filter { it.isDigit() || it == '.' }

/** 订阅编辑（§6 编辑 sheet）：名称 / 金额 / 币种 / 周期 / 下次扣费日 / 状态。 */
@Composable
fun SubscriptionEditorContent(
    vm: MainViewModel,
    target: Editor,
    findSubscription: (String) -> SubscriptionUi?,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val view = LocalView.current

    val isNew = target is Editor.NewSubscription
    val targetId = (target as? Editor.Subscription)?.id
    val existing = targetId?.let(findSubscription)
    if (targetId != null && existing == null) return

    var name by rememberEditorField(vm, target.key, "name", existing?.name ?: "")
    var amount by rememberEditorField(vm, target.key, "amount", existing?.amountPlain ?: "")
    var currency by rememberEditorField(vm, target.key, "currency", existing?.currency ?: "CNY")
    var cycle by rememberEditorField(vm, target.key, "cycle", existing?.cycle ?: "monthly")
    var status by rememberEditorField(vm, target.key, "status", if (existing?.active != false) "active" else "paused")
    var date by rememberEditorField(vm, target.key, "date", existing?.nextChargeRaw ?: "")
    var showCalendar by rememberEditorField(vm, target.key, "showCalendar", false)

    Column(modifier = modifier.fillMaxWidth()) {
        SheetNavBar(
            title = stringResource(if (isNew) R.string.new_subscription_title else R.string.subscriptions_title),
            onCancel = vm::closeEditor,
            onSave = {
                Haptics.success(view)
                val parsed = if (date.isBlank()) null else runCatching { LocalDate.parse(date) }.getOrNull()
                if (isNew) {
                    vm.saveNewSubscription(name.trim(), amount.trim(), currency, cycle, parsed)
                } else if (existing != null) {
                    vm.saveSubscription(existing.id, name.trim(), amount.trim(), currency, cycle, parsed, status)
                }
            },
            saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank() && date.isNotBlank(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.name_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text(stringResource(R.string.amount_label)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.currency_label),
            style = SnowFoldType.sectionHeader,
            color = tokens.ink2,
            modifier = Modifier.padding(top = 16.dp),
        )
        SnowFoldSegmented(
            options = listOf(stringResource(R.string.currency_cny), stringResource(R.string.currency_usd)),
            selected = if (currency == "CNY") 0 else 1,
            onSelect = { index -> currency = if (index == 0) "CNY" else "USD" },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.billing_cycle_label),
            style = SnowFoldType.sectionHeader,
            color = tokens.ink2,
            modifier = Modifier.padding(top = 16.dp),
        )
        SnowFoldSegmented(
            options = listOf(
                stringResource(R.string.cycle_weekly),
                stringResource(R.string.cycle_monthly),
                stringResource(R.string.cycle_quarterly),
                stringResource(R.string.cycle_yearly),
            ),
            selected = when (cycle) {
                "weekly" -> 0
                "monthly" -> 1
                "quarterly" -> 2
                else -> 3
            },
            onSelect = { index ->
                cycle = when (index) {
                    0 -> "weekly"
                    1 -> "monthly"
                    2 -> "quarterly"
                    else -> "yearly"
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.next_charge_date_label),
            style = SnowFoldType.sectionHeader,
            color = tokens.ink2,
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(
            onClick = { showCalendar = !showCalendar },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                appIcon(AppIcon.calendar),
                contentDescription = stringResource(R.string.cd_pick_date),
                modifier = Modifier.size(18.dp),
                tint = tokens.accent,
            )
            Text(
                text = if (date.isBlank()) stringResource(R.string.choose_date) else dateShort(LocalDate.parse(date)),
                style = SnowFoldType.body.copy(fontFeatureSettings = "tnum"),
                color = tokens.accent,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (showCalendar) {
            CalendarPicker(
                initial = if (date.isBlank()) LocalDate.now() else LocalDate.parse(date),
                onApplied = { picked: java.time.LocalDate ->
                    date = picked.toString()
                    showCalendar = false
                },
                onCancelled = { showCalendar = false },
            )
        }
        if (!isNew) {
            Text(
                text = stringResource(R.string.status_label),
                style = SnowFoldType.sectionHeader,
                color = tokens.ink2,
                modifier = Modifier.padding(top = 16.dp),
            )
            SnowFoldSegmented(
                options = listOf(stringResource(R.string.status_active), stringResource(R.string.status_paused)),
                selected = if (status == "active") 0 else 1,
                onSelect = { index -> status = if (index == 0) "active" else "paused" },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.new_subscription_help),
            style = SnowFoldType.helper,
            color = tokens.ink3,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (editorError != null) {
            Text(
                text = app.snowfold.ui.components.uiMessageText(editorError!!),
                style = SnowFoldType.body,
                color = tokens.negative,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!isNew && existing != null) {
                TextButton(
                    onClick = {
                        Haptics.warning(view)
                        vm.deleteSubscription(existing.id)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.cd_delete_subscription), color = tokens.negative)
                }
            }

        }
    }
}
