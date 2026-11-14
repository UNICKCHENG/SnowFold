@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.snowfold.ui.editors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppCard
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.appIcon
import app.snowfold.ui.components.Haptics
import app.snowfold.ui.components.appColors
import app.snowfold.ui.components.categoryTint
import app.snowfold.ui.model.AssetUi
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType

private fun String.moneyInput(): String = filter { it.isDigit() || it == '.' }

/** sheet 顶栏（§6 编辑 sheet）：取消 | 标题 | 保存。 */
@Composable
fun SheetNavBar(
    title: String,
    onCancel: () -> Unit,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
) {
    val tokens = appColors()
    val largeText = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth()) {
        if (largeText) Text(title, style = SnowFoldType.statValue, modifier = Modifier.padding(horizontal = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("editor-cancel")) {
                Text(stringResource(R.string.cancel), color = tokens.accent)
            }
            if (largeText) Spacer(Modifier.weight(1f)) else Text(
                title, style = SnowFoldType.statValue, color = tokens.ink,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (onSave != null) {
                Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.testTag("editor-save"),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.ink, contentColor = tokens.canvas),
                    shape = RoundedCornerShape(9.dp)) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun EditorErrorLine(error: app.snowfold.UiMessage?) {
    if (error != null) {
        Text(
            text = app.snowfold.ui.components.uiMessageText(error),
            style = SnowFoldType.body,
            color = appColors().negative,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, label: String, amount: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(if (amount) next.moneyInput() else next) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (amount) KeyboardType.Decimal else KeyboardType.Text,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    )
}

@Composable
private fun categoryLabel(index: Int): String = stringResource(
    when (index) {
        0 -> R.string.category_cash
        1 -> R.string.category_fixed
        2 -> R.string.category_investment
        3 -> R.string.category_receivable
        else -> R.string.category_debt
    },
)

/** 新建资产表单（§6）：分类(只读) + 名称 + 金额。 */
@Composable
private fun NewAssetForm(vm: MainViewModel, target: Editor.NewAsset, error: app.snowfold.UiMessage?) {
    val tokens = appColors()
    var name by rememberEditorField(vm, target.key, "name", "")
    var amount by rememberEditorField(vm, target.key, "amount", "")
    val busy by vm.busy.collectAsStateWithLifecycle()
    val view = LocalView.current
    var category by rememberEditorField(vm, target.key, "category", target.categoryIndex)
    SheetNavBar(stringResource(R.string.new_asset_title), vm::closeEditor,
        onSave = { vm.saveNewAsset(category, name.trim(), amount.trim()) },
        saveEnabled = !busy && name.isNotBlank() && amount.isNotBlank())
    (0..4).chunked(3).forEach { group ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            group.forEach { index ->
                val tint = categoryTint(index, tokens)
                TextButton(
                    onClick = { category = index }, modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        containerColor = if (category == index) tint.second else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = tint.first,
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcon.category(index), null, Modifier.size(20.dp))
                        Text(categoryLabel(index), style = SnowFoldType.helper)
                    }
                }
            }
            if (group.size < 3) Spacer(Modifier.weight((3 - group.size).toFloat()))
        }
    }
    FormField(value = name, onValueChange = { name = it }, label = stringResource(R.string.name_label))
    FormField(value = amount, onValueChange = { amount = it }, label = stringResource(R.string.amount_label), amount = true)
    EditorErrorLine(error)

}

/** 资产详情（§6）：金额大字 + 操作 + 变动记录（含可展开修正记录）。 */
@Composable
private fun AssetDetail(vm: MainViewModel, asset: AssetUi, target: Editor.Asset, error: app.snowfold.UiMessage?) {
    val tokens = appColors()
    var name by rememberEditorField(vm, target.key, "name", asset.name)
    var renaming by rememberEditorField(vm, target.key, "renaming", target.action == "rename")
    var changingBalance by rememberEditorField(vm, target.key, "changingBalance", target.action == "balance")
    var amount by rememberEditorField(vm, target.key, "amount", asset.amountPlain)
    val editingHistoryId by vm.correctionId.collectAsStateWithLifecycle()
    var expandedHistoryId by rememberSaveable(asset.id) { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val view = LocalView.current

    Text(
        text = asset.name,
        style = SnowFoldType.statValue,
        color = tokens.ink,
    )
    AmountText(
        value = asset.amountText,
        style = SnowFoldType.hero.copy(fontSize = SnowFoldType.cardNumber.fontSize * 1.4f),
        color = tokens.ink,
        modifier = Modifier.padding(top = 8.dp),
    )
    Button(
        onClick = { changingBalance = !changingBalance }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tokens.ink, contentColor = tokens.canvas),
        shape = RoundedCornerShape(9.dp),
    ) { Text(stringResource(R.string.adjust_balance)) }
    TextButton(onClick = { renaming = !renaming }, enabled = !busy) {
        Text(stringResource(R.string.cd_rename), color = tokens.ink2)
    }
    if (renaming) {
        FormField(name, { name = it }, stringResource(R.string.name_label))
        Button(onClick = { vm.renameAsset(asset.id, name.trim()) }, enabled = !busy && name.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = tokens.ink, contentColor = tokens.canvas)) {
            Text(stringResource(R.string.save))
        }
    }
    if (changingBalance) {
    Text(
        text = stringResource(R.string.current_balance_note, asset.amountText),
        style = SnowFoldType.helper,
        color = tokens.ink2,
        modifier = Modifier.padding(top = 4.dp),
    )
    FormField(value = amount, onValueChange = { amount = it }, label = stringResource(R.string.amount_label), amount = true)
    Text(
        text = stringResource(R.string.balance_undo_note),
        style = SnowFoldType.helper,
        color = tokens.ink3,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = {
                Haptics.success(view)
                vm.changeBalance(asset.id, amount.trim())
            },
            enabled = !busy,
        ) {
            Text(stringResource(R.string.save), color = tokens.accent, fontWeight = FontWeight.SemiBold)
        }
    }

    }
    if (editingHistoryId == null) EditorErrorLine(error)
    if (asset.history.isNotEmpty()) {
        Text(
            text = stringResource(R.string.history_title),
            style = SnowFoldType.sectionHeader,
            color = tokens.ink2,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        asset.history.forEachIndexed { index, entry ->
            val isCurrent = entry.isCurrent
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp).clickable { vm.openCorrection(entry.id) }.testTag("history-${entry.id}"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = runCatching {
                                java.time.OffsetDateTime.parse(entry.recordedAt).toLocalDateTime()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            }.getOrDefault(entry.recordedAt) + if (isCurrent) "  " + stringResource(R.string.history_current) else "",
                            style = SnowFoldType.helper,
                            color = tokens.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.deltaText ?: stringResource(R.string.history_initial),
                            style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"),
                            color = tokens.ink3,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    AmountText(entry.amountText, SnowFoldType.rowValue, Modifier.weight(0.9f), tokens.ink)
                    TextButton(
                        onClick = { vm.openCorrection(entry.id) },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(R.string.correct_history),
                            style = SnowFoldType.helper,
                            color = tokens.accent,
                        )
                    }
                }
                TextButton(onClick = { expandedHistoryId = if (expandedHistoryId == entry.id) null else entry.id }) {
                    Text(stringResource(R.string.corrections_toggle), style = SnowFoldType.helper)
                }
                if (expandedHistoryId == entry.id) CorrectionHistory(vm, entry.id)
            }
        }
    }
    editingHistoryId?.let { historyId ->
        val entry = asset.history.firstOrNull { it.id == historyId }
        if (entry != null) {
            var correctionAmount by rememberEditorField(vm, "correction:$historyId", "amount", entry.amountPlain)
            val correctionSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false)
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = vm::closeCorrection,
                sheetState = correctionSheetState,
                containerColor = tokens.card,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SheetNavBar(
                        stringResource(R.string.correction_sheet_title),
                        onCancel = vm::closeCorrection,
                        onSave = {
                            vm.correctHistory(historyId, correctionAmount.trim())
                        },
                        saveEnabled = !busy && correctionAmount.isNotBlank(),
                    )
                    Text(displayTimestamp(entry.recordedAt), style = SnowFoldType.helper, color = tokens.ink3, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        stringResource(R.string.history_current) + " " + entry.amountText,
                        style = SnowFoldType.helper, color = tokens.ink2, modifier = Modifier.padding(top = 2.dp),
                    )
                    FormField(value = correctionAmount, onValueChange = { correctionAmount = it }, label = stringResource(R.string.amount_label), amount = true)
                    EditorErrorLine(error)
                    CorrectionHistory(vm, historyId)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    AppCard(modifier = Modifier.padding(top = 16.dp)) {
        TextButton(
            onClick = {
                Haptics.warning(view)
                vm.deleteAsset(asset.id)
            },
            enabled = !busy,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.cd_delete_asset), color = tokens.negative)
        }
    }
}

/** 资产编辑入口：sheet 与宽屏详情共用。 */
@Composable
fun AssetEditorContent(
    vm: MainViewModel,
    target: Editor,
    findAsset: (String) -> AssetUi?,
    modifier: Modifier = Modifier,
    titleOverride: String? = null,
) {
    val editorError by vm.editorError.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxWidth()) {
        if (titleOverride != null && target !is Editor.NewAsset) {
            SheetNavBar(title = titleOverride, onCancel = vm::closeEditor)
        }
        when (target) {
            is Editor.NewAsset -> NewAssetForm(vm, target, editorError)
            is Editor.Asset -> {
                val asset = findAsset(target.id) ?: return
                AssetDetail(vm, asset, target, editorError)
            }

            else -> Unit
        }
    }
}

private fun displayTimestamp(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)

@Composable
private fun CorrectionHistory(vm: MainViewModel, id: String) {
    var rows by remember(id) { mutableStateOf<List<MainViewModel.CorrectionUi>?>(null) }
    var failure by remember(id) { mutableStateOf<app.snowfold.UiMessage?>(null) }
    LaunchedEffect(id) {
        try { rows = vm.correctionsFor(id) }
        catch (error: app.snowfold.core.CoreException) {
            failure = app.snowfold.UiMessage(R.string.operation_failed, argRes = listOf(app.snowfold.data.coreErrorRes(error)))
        }
    }
    EditorErrorLine(failure)
    rows?.let { corrections ->
        if (corrections.isEmpty()) Text(stringResource(R.string.corrections_empty), style = SnowFoldType.helper, color = appColors().ink3)
        corrections.forEach { correction ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(displayTimestamp(correction.recordedAt), style = SnowFoldType.helper, color = appColors().ink3)
                Text("${correction.previousText} → ${correction.amountText}", style = SnowFoldType.helper.copy(fontFeatureSettings = "tnum"))
            }
        }
    }
}
