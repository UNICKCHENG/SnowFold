package app.snowfold.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppCard
import app.snowfold.ui.components.SettingRow
import app.snowfold.ui.components.SnowFoldSegmented
import app.snowfold.ui.components.appColors
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** 设置页（docs/design.md §6）：外观 / 数据 / 加密备份 / 关于，行内说明 + 分段主题。 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingJson by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var pendingSealed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    var pendingSealedFingerprint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var pendingRecovery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var pendingRecoveryFingerprint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val exportJsonLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingJson
        scope.launch {
            val ok = uri != null && payload != null && app.snowfold.platform.SafIo.writeText(context, uri, payload)
            if (ok) vm.notifyExportComplete(R.string.backup_exported) else vm.notifyExportFailed()
        }
        pendingJson = null
    }
    val exportSealedLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val payload = pendingSealed
        val fingerprint = pendingSealedFingerprint
        scope.launch {
            val ok = uri != null && payload != null && app.snowfold.platform.SafIo.writeBytes(context, uri, payload)
            if (ok) {
                vm.notifyExportComplete(
                    R.string.sealed_backup_exported,
                    args = listOf(fingerprint ?: ""),
                )
                vm.refreshFingerprintInState(fingerprint)
            } else {
                vm.notifyExportFailed(templateRes = R.string.sealed_export_failed)
            }
        }
        pendingSealed = null
    }
    val exportRecoveryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = pendingRecovery
        val fingerprint = pendingRecoveryFingerprint
        scope.launch {
            val ok = uri != null && payload != null && app.snowfold.platform.SafIo.writeText(context, uri, payload)
            if (ok) {
                vm.notifyExportComplete(
                    R.string.recovery_code_exported,
                    args = listOf(fingerprint ?: ""),
                )
            } else {
                vm.notifyExportFailed(templateRes = R.string.recovery_export_failed)
            }
        }
        pendingRecovery = null
        pendingRecoveryFingerprint = null
    }
    val importJsonLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        scope.launch {
            val text = uri?.let { app.snowfold.platform.SafIo.readText(context, it) }
            if (text == null) vm.notifyImportReadFailed() else vm.prepareImportJson(text)
        }
    }
    val importSealedLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        scope.launch {
            val bytes = uri?.let { app.snowfold.platform.SafIo.readBytes(context, it) }
            if (bytes == null) vm.notifyImportReadFailed() else vm.prepareImportSealed(bytes)
        }
    }
    val importRecoveryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        scope.launch {
            val text = uri?.let { app.snowfold.platform.SafIo.readText(context, it) }
            if (text == null) vm.notifyImportReadFailed() else vm.prepareReplaceKey(text)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings-list").verticalScroll(rememberScrollState())
            .padding(
                start = DesignTokens.pageMargin,
                end = DesignTokens.pageMargin,
                top = DesignTokens.space3,
                bottom = DesignTokens.space4 + 48.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space3),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.close), style = SnowFoldType.body, color = tokens.accent)
            }
            Text(stringResource(R.string.settings_title), style = SnowFoldType.statValue, color = tokens.ink, modifier = Modifier.padding(start = 4.dp))
        }
        val state by vm.state.collectAsStateWithLifecycle()
        val theme = (state as? app.snowfold.AppState.Ready)?.theme
        val fingerprint = (state as? app.snowfold.AppState.Ready)?.keyFingerprint

        Text(stringResource(R.string.appearance_section), style = SnowFoldType.sectionHeader, color = tokens.ink2)
        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.theme_label), style = SnowFoldType.body, color = tokens.ink)
                SnowFoldSegmented(
                    options = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                    ),
                    selected = when (theme) {
                        app.snowfold.core.ThemePreference.LIGHT -> 1
                        app.snowfold.core.ThemePreference.DARK -> 2
                        else -> 0
                    },
                    onSelect = { index ->
                        val pref = when (index) {
                            1 -> app.snowfold.core.ThemePreference.LIGHT
                            2 -> app.snowfold.core.ThemePreference.DARK
                            else -> app.snowfold.core.ThemePreference.SYSTEM
                        }
                        vm.setTheme(pref)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.theme_help),
                    style = SnowFoldType.helper,
                    color = tokens.ink3,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Text(stringResource(R.string.backups_section), style = SnowFoldType.sectionHeader, color = tokens.ink2)
        AppCard {
            Column {
                SettingRow(
                    title = stringResource(R.string.export_backup_title),
                    help = stringResource(R.string.export_backup_help),
                    onClick = {
                        scope.launch {
                            runCatching { vm.exportJsonPayload() }
                                .onSuccess { pendingJson = it; exportJsonLauncher.launch("snowfold-backup.json") }
                                .onFailure { vm.notifyExportFailed(detailRes = app.snowfold.data.coreErrorRes(it)) }
                        }
                    },
                )
                SettingRow(
                    title = stringResource(R.string.import_backup_title),
                    help = stringResource(R.string.import_backup_help),
                    onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                SettingRow(
                    title = stringResource(R.string.export_sealed),
                    help = stringResource(R.string.encrypted_backup_help),
                    onClick = {
                        scope.launch {
                            runCatching { vm.exportSealedWithFingerprint() }
                                .onSuccess { (bytes, fingerprint) ->
                                    pendingSealed = bytes
                                    pendingSealedFingerprint = fingerprint
                                    exportSealedLauncher.launch("snowfold-backup.sfseal")
                                }
                                .onFailure { vm.notifyExportFailed(detailRes = app.snowfold.data.coreErrorRes(it), templateRes = R.string.sealed_export_failed) }
                        }
                    },
                )
                SettingRow(
                    title = stringResource(R.string.import_sealed),
                    help = stringResource(R.string.restore_encrypted_backup_help),
                    onClick = { importSealedLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                )
            }
        }

        Text(stringResource(R.string.key_section), style = SnowFoldType.sectionHeader, color = tokens.ink2)
        AppCard {
            Column {
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .pointerInput(fingerprint) {
                        detectTapGestures(onLongPress = {
                            fingerprint?.let { clipboard.setText(AnnotatedString(it)) }
                        })
                    },
            ) {
                Text(
                    stringResource(R.string.key_fingerprint_label),
                    style = SnowFoldType.helper,
                    color = tokens.ink2,
                )
                Text(
                    fingerprint ?: stringResource(R.string.key_not_generated),
                    style = SnowFoldType.helper.copy(fontFamily = FontFamily.Monospace),
                    color = tokens.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column {
                SettingRow(
                    title = stringResource(R.string.export_recovery_code),
                    help = stringResource(R.string.recovery_code_help),
                    onClick = {
                        scope.launch {
                            runCatching { vm.exportRecoveryCodeWithFingerprint() }
                                .onSuccess { (code, fingerprint) ->
                                    pendingRecovery = code
                                    pendingRecoveryFingerprint = fingerprint
                                    exportRecoveryLauncher.launch("snowfold-recovery-code.txt")
                                }
                                .onFailure { vm.notifyExportFailed(detailRes = app.snowfold.data.coreErrorRes(it), templateRes = R.string.recovery_export_failed) }
                        }
                    },
                )
                SettingRow(
                    title = stringResource(R.string.import_recovery_code),
                    help = null,
                    onClick = { importRecoveryLauncher.launch(arrayOf("text/plain", "*/*")) },
                )
            }
        }
        }

        Text(stringResource(R.string.data_section), style = SnowFoldType.sectionHeader, color = tokens.ink2)
        AppCard {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.data_location_title), style = SnowFoldType.body, color = tokens.ink)
                    Text(context.filesDir.resolve("snowfold.sqlite3").absolutePath, style = SnowFoldType.helper, color = tokens.ink3)
                }
                SettingRow(
                    title = stringResource(R.string.clear_all_title),
                    help = stringResource(R.string.clear_all_help),
                    onClick = vm::requestClearData,
                    danger = true,
                )
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.app_version), style = SnowFoldType.body, color = tokens.ink)
                Text(
                    stringResource(R.string.offline_description),
                    style = SnowFoldType.helper,
                    color = tokens.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
