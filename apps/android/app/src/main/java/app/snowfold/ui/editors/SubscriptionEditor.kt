package app.snowfold.ui.editors

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AmountRowText
import app.snowfold.ui.components.ButtonStyle
import app.snowfold.ui.components.FormCard
import app.snowfold.ui.components.FormFootnote
import app.snowfold.ui.components.FormRow
import app.snowfold.ui.components.FormSegmentedRow
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfButton
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfTextField
import app.snowfold.ui.components.SelectorField
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.calendarInline
import app.snowfold.ui.components.dateLong
import app.snowfold.ui.components.rememberHaptics
import app.snowfold.ui.components.uiMessageText
import app.snowfold.ui.model.SubscriptionUi
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate

/**
 * 新建 / 编辑订阅 sheet（§9.5）：名称、金额（币种符跟随币种）、币种分段、
 * 扣费周期分段、下次扣费日（日期选择）；编辑态下方一张卡片「暂停/恢复」「删除订阅」。
 */
@Composable
fun SubscriptionEditorSheet(
    vm: MainViewModel,
    target: Editor,
    findSubscription: (String) -> SubscriptionUi?,
    centered: Boolean = false,
) {
    val colors = appColors()
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
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
    var dateMissing by rememberEditorField(vm, target.key, "dateMissing", false)

    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val dateMissingNow = dateMissing && date.isBlank()
    val save: () -> Unit = {
        val parsed = if (date.isBlank()) null else runCatching { LocalDate.parse(date) }.getOrNull()
        if (parsed == null) {
            dateMissing = true
        } else if (isNew) {
            vm.saveNewSubscription(name.trim(), amount.trim(), currency, cycle, parsed)
        } else if (existing != null) {
            vm.saveSubscription(existing.id, name.trim(), amount.trim(), currency, cycle, parsed, status)
        }
    }
    val cycleOptions = listOf(
        stringResource(R.string.cycle_weekly),
        stringResource(R.string.cycle_monthly),
        stringResource(R.string.cycle_quarterly),
        stringResource(R.string.cycle_yearly),
    )
    val cycleCodes = listOf("weekly", "monthly", "quarterly", "yearly")
    val body: @Composable () -> Unit = {
        FormCard {
            FormRow(stringResource(R.string.name_label)) {
                SfTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.subscription_name_placeholder),
                    testTag = "sub-name",
                )
            }
            FormRow(stringResource(R.string.amount_label)) {
                SfTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = "0.00",
                    amount = true,
                    currencySymbol = if (currency == "USD") "$" else "¥",
                    error = editorError != null,
                    errorMessage = editorError?.let { uiMessageText(it) },
                    onDone = save,
                    testTag = "sub-amount",
                )
            }
            FormSegmentedRow(
                label = stringResource(R.string.currency_label),
                options = listOf(stringResource(R.string.currency_cny), stringResource(R.string.currency_usd)),
                selected = if (currency == "USD") 1 else 0,
                onSelect = { currency = if (it == 1) "USD" else "CNY" },
                testTag = "sub-currency",
            )
            app.snowfold.ui.components.RowDivider(startInset = 16.dp)
            FormSegmentedRow(
                label = stringResource(R.string.billing_cycle_label),
                options = cycleOptions,
                selected = cycleCodes.indexOf(cycle).coerceAtLeast(0),
                onSelect = { cycle = cycleCodes[it] },
                testTag = "sub-cycle",
            )
            app.snowfold.ui.components.RowDivider(startInset = 16.dp)
            FormRow(stringResource(R.string.next_charge_date_label)) {
                Column {
                    SelectorField(
                        value = parsedDate?.let { dateLong(it) } ?: "",
                        placeholder = stringResource(R.string.choose_date_placeholder),
                        onClick = { showCalendar = !showCalendar },
                        calendarIcon = true,
                        error = dateMissingNow,
                        testTag = "sub-date",
                    )
                    if (dateMissingNow) {
                        Text(
                            stringResource(R.string.choose_next_charge_date),
                            style = SnowFoldType.caption.appType(),
                            color = colors.neg,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (showCalendar) {
            Spacer(Modifier.height(12.dp))
            SfCard {
                Box(Modifier.padding(vertical = 8.dp)) {
                    calendarInline(
                        initial = if (date.isBlank()) LocalDate.now() else runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()),
                        onApplied = { picked ->
                            date = picked.toString()
                            dateMissing = false
                            showCalendar = false
                        },
                        onCancelled = { showCalendar = false },
                    )
                }
            }
        }
        FormFootnote(
            error = null,
            help = if (isNew) stringResource(R.string.new_subscription_help) else null,
        )
        if (!isNew && existing != null) {
            Spacer(Modifier.height(16.dp))
            SfCard {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                status = if (status == "active") "paused" else "active"
                                vm.setSubscriptionPaused(existing.id, status == "paused")
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (existing.active) R.string.pause else R.string.resume),
                            style = SnowFoldType.body.appType(),
                            color = colors.brand,
                        )
                    }
                    RowDivider(startInset = 16.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                vm.closeEditor()
                                vm.deleteSubscription(existing.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("sub-delete"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.delete_subscription),
                            style = SnowFoldType.body.appType(),
                            color = colors.neg,
                        )
                    }
                }
            }
        }
    }
    if (centered) {
        app.snowfold.ui.components.SfCenterSheet(
            title = stringResource(if (isNew) R.string.new_subscription_title else R.string.edit_subscription_title),
            onDismiss = vm::closeEditor,
            onSave = save,
            saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank() && date.isNotBlank(),
        ) { body() }
    } else {
        app.snowfold.ui.components.SfBottomSheet(
            title = stringResource(if (isNew) R.string.new_subscription_title else R.string.edit_subscription_title),
            onDismiss = vm::closeEditor,
            onSave = save,
            saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank() && date.isNotBlank(),
        ) { body() }
    }
}
