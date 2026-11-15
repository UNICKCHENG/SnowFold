package app.snowfold.ui.editors

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
import androidx.compose.runtime.mutableStateOf
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
import app.snowfold.ui.components.CategoryIconBlock
import app.snowfold.ui.components.FormCard
import app.snowfold.ui.components.FormFootnote
import app.snowfold.ui.components.FormRow
import app.snowfold.ui.components.FormSegmentedRow
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfMenu
import app.snowfold.ui.components.SfTextField
import app.snowfold.ui.components.SelectorField
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.categoryLabel
import app.snowfold.ui.components.historyTimestamp
import app.snowfold.ui.components.rememberHaptics
import app.snowfold.ui.components.uiMessageText
import app.snowfold.ui.model.AssetUi
import app.snowfold.ui.model.HistoryUi
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType

/** 选择分类（暂存到草稿）：与弹层之间经 SavedStateHandle。 */
@Composable
fun rememberEditorCategory(vm: MainViewModel, target: Editor.NewAsset): androidx.compose.runtime.MutableState<Int> =
    rememberEditorField(vm, target.key, "category", target.categoryIndex)

/** 新建资产 sheet（§6/§9.5）：分类选择器 + 名称 + 金额。 */
@Composable
fun NewAssetSheet(
    vm: MainViewModel,
    target: Editor.NewAsset,
    findAsset: (String) -> AssetUi?,
    centered: Boolean = false,
) {
    val colors = appColors()
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberEditorField(vm, target.key, "name", "")
    var amount by rememberEditorField(vm, target.key, "amount", "")
    var category by rememberEditorCategory(vm, target)
    var categoryMenu by remember { mutableStateOf(false) }
    val save = { vm.saveNewAsset(category, name.trim(), amount.trim()) }
    val body: @Composable () -> Unit = {
        FormCard {
            FormRow(stringResource(R.string.category_label)) {
                Box {
                    SelectorField(
                        value = categoryLabel(category),
                        onClick = { categoryMenu = true },
                        leading = { CategoryIconBlock(category, 20.dp) },
                        testTag = "asset-category",
                    )
                    SfMenu(
                        expanded = categoryMenu,
                        onDismiss = { categoryMenu = false },
                        items = (0..4).map { index -> categoryLabel(index) to { category = index } },
                    )
                }
            }
            FormRow(stringResource(R.string.name_label)) {
                SfTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.asset_name_placeholder),
                    testTag = "asset-name",
                )
            }
            FormRow(stringResource(R.string.amount_label)) {
                SfTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = "0.00",
                    amount = true,
                    currencySymbol = "¥",
                    error = editorError != null,
                    errorMessage = editorError?.let { uiMessageText(it) },
                    onDone = save,
                    testTag = "asset-amount",
                )
            }
        }
        FormFootnote(
            error = null,
            help = null,
        )
    }
    if (centered) {
        app.snowfold.ui.components.SfCenterSheet(
            title = stringResource(R.string.add_asset),
            onDismiss = vm::closeEditor,
            onSave = save,
            saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank(),
        ) { body() }
    } else {
        app.snowfold.ui.components.SfBottomSheet(
            title = stringResource(R.string.add_asset),
            onDismiss = vm::closeEditor,
            onSave = save,
            saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank(),
        ) { body() }
    }
}

/** 调整余额 sheet（§9.5）：资产只读、当前余额只读、新余额输入（初始焦点）。 */
@Composable
fun AdjustBalanceSheet(vm: MainViewModel, asset: AssetUi, centered: Boolean = false) {
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var amount by rememberEditorField(vm, "balance.${asset.id}", "amount", asset.amountPlain)
    val save = { vm.changeBalance(asset.id, amount.trim()) }
    val body: @Composable () -> Unit = {
        FormCard {
            FormRow(stringResource(R.string.asset_label)) {
                Text(asset.name, style = SnowFoldType.body.appType(), color = appColors().text)
            }
            FormRow(stringResource(R.string.current_balance_label)) {
                AmountRowText(asset.amountText)
            }
            FormRow(stringResource(R.string.new_balance_label)) {
                SfTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = "0.00",
                    amount = true,
                    currencySymbol = "¥",
                    error = editorError != null,
                    errorMessage = editorError?.let { uiMessageText(it) },
                    onDone = save,
                    testTag = "adjust-amount",
                )
            }
        }
        FormFootnote(error = null, help = stringResource(R.string.balance_undo_note))
    }
    if (centered) {
        app.snowfold.ui.components.SfCenterSheet(
            title = stringResource(R.string.adjust_balance), onDismiss = vm::closeEditor,
            onSave = save, saveEnabled = !busy && amount.isNotBlank(),
        ) { body() }
    } else {
        app.snowfold.ui.components.SfBottomSheet(
            title = stringResource(R.string.adjust_balance), onDismiss = vm::closeEditor,
            onSave = save, saveEnabled = !busy && amount.isNotBlank(),
        ) { body() }
    }
}

/** 重命名 sheet。 */
@Composable
fun RenameAssetSheet(vm: MainViewModel, asset: AssetUi, centered: Boolean = false) {
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberEditorField(vm, "rename.${asset.id}", "name", asset.name)
    val save = { vm.renameAsset(asset.id, name.trim()) }
    val body: @Composable () -> Unit = {
        FormCard {
            FormRow(stringResource(R.string.name_label)) {
                SfTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.asset_name_placeholder),
                    error = editorError != null,
                    errorMessage = editorError?.let { uiMessageText(it) },
                    onDone = save,
                    testTag = "rename-name",
                )
            }
        }
        FormFootnote(error = null, help = null)
    }
    if (centered) {
        app.snowfold.ui.components.SfCenterSheet(
            title = stringResource(R.string.rename), onDismiss = vm::closeEditor,
            onSave = save, saveEnabled = !busy && name.isNotBlank(),
        ) { body() }
    } else {
        app.snowfold.ui.components.SfBottomSheet(
            title = stringResource(R.string.rename), onDismiss = vm::closeEditor,
            onSave = save, saveEnabled = !busy && name.isNotBlank(),
        ) { body() }
    }
}

/** 修正历史 sheet（§9.5）：记录时间/原金额只读 + 修正为输入 + 说明 + 修正记录。 */
@Composable
fun CorrectHistorySheet(vm: MainViewModel, entry: HistoryUi, centered: Boolean = false) {
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var amount by rememberEditorField(vm, "correction.${entry.id}", "amount", entry.amountPlain)
    val save = { vm.correctHistory(entry.id, amount.trim()) }
    val body: @Composable () -> Unit = {
        FormCard(tag = "correction-sheet") {
            FormRow(stringResource(R.string.history_time_label)) {
                Text(historyTimestamp(entry.recordedAt), style = SnowFoldType.body.appType(), color = appColors().text)
            }
            FormRow(stringResource(R.string.original_amount_label)) {
                AmountRowText(entry.amountText)
            }
            FormRow(stringResource(R.string.corrected_amount_label)) {
                SfTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = "0.00",
                    amount = true,
                    currencySymbol = "¥",
                    error = editorError != null,
                    errorMessage = editorError?.let { uiMessageText(it) },
                    onDone = save,
                    testTag = "correct-amount",
                )
            }
        }
        Text(
            stringResource(R.string.history_note),
            style = SnowFoldType.caption.appType(),
            color = appColors().text2,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        FormFootnote(error = null, help = null)
        CorrectionHistory(vm, entry.id)
    }
    if (centered) {
        app.snowfold.ui.components.SfCenterSheet(
            title = stringResource(R.string.correct_history_title), onDismiss = vm::closeCorrection,
            onSave = save, saveEnabled = !busy && amount.isNotBlank(),
        ) { body() }
    } else {
        app.snowfold.ui.components.SfBottomSheet(
            title = stringResource(R.string.correct_history_title), onDismiss = vm::closeCorrection,
            onSave = save, saveEnabled = !busy && amount.isNotBlank(),
        ) { body() }
    }
}

/** 修正记录（改前 → 改后），金额文本由核心输出。 */
@Composable
private fun CorrectionHistory(vm: MainViewModel, id: String) {
    val syncRevision by vm.syncRevision.collectAsStateWithLifecycle()
    var rows by remember(id, syncRevision) { mutableStateOf<List<MainViewModel.CorrectionUi>?>(null) }
    androidx.compose.runtime.LaunchedEffect(id, syncRevision) {
        rows = runCatching { vm.correctionsFor(id) }.getOrNull()
    }
    val colors = appColors()
    rows?.let { corrections ->
        if (corrections.isNotEmpty()) {
            SfCard {
                corrections.forEachIndexed { index, correction ->
                    if (index > 0) app.snowfold.ui.components.RowDivider(startInset = 16.dp)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            historyTimestamp(correction.recordedAt),
                            style = SnowFoldType.caption.appType(),
                            color = colors.text2,
                        )
                        Text(
                            "${correction.previousText} → ${correction.amountText}",
                            style = SnowFoldType.caption.appType(),
                            color = colors.text,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
