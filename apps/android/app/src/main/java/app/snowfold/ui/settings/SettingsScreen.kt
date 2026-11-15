package app.snowfold.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfSegmented
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import kotlinx.coroutines.launch

/** 设置行：标题正文 + 说明辅助 text2；可点行右侧 chevron。 */
@Composable
private fun SettingsRow(
    title: String,
    help: String?,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    val colors = appColors()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DesignTokens.rowHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = SnowFoldType.body.appType(),
                    color = if (danger) colors.neg else colors.text,
                )
                if (help != null) {
                    Text(
                        help,
                        style = SnowFoldType.caption.appType(),
                        color = colors.text2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) trailing()
            if (onClick != null && trailing == null && !danger) {
                Icon(AppIcon0.chevronRight, contentDescription = null, tint = colors.text2, modifier = Modifier.padding(start = 8.dp).height(16.dp).width(16.dp))
            }
        }
    }
}

private object AppIcon0 {
    val chevronRight get() = app.snowfold.ui.components.AppIcon.chevronRight
}

/** 设置页（mobile-layout §5）：外观 / 数据 / 加密备份 / 同步 / 关于，单列分组卡片。 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    tablet: Boolean = false,
    modifier: Modifier = Modifier,
    importRecoveryOnOpen: Boolean = false,
) {
    val colors = appColors()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
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
                vm.notifyExportComplete(R.string.sealed_backup_exported, args = listOf(fingerprint ?: ""))
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
                vm.notifyExportComplete(R.string.recovery_code_exported, args = listOf(fingerprint ?: ""))
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
    var recoveryPickerOpened by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(importRecoveryOnOpen) {
        if (importRecoveryOnOpen && !recoveryPickerOpened) {
            recoveryPickerOpened = true
            importRecoveryLauncher.launch(arrayOf("text/plain", "*/*"))
        }
    }
    val exportRecovery: () -> Unit = {
        scope.launch {
            runCatching { vm.exportRecoveryCodeWithFingerprint() }
                .onSuccess { (code, fingerprint) ->
                    pendingRecovery = code
                    pendingRecoveryFingerprint = fingerprint
                    exportRecoveryLauncher.launch("snowfold-recovery-code.txt")
                }
                .onFailure {
                    vm.notifyExportFailed(
                        detailRes = app.snowfold.data.coreErrorRes(it),
                        templateRes = R.string.recovery_export_failed,
                    )
                }
        }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val theme = (state as? app.snowfold.AppState.Ready)?.theme
    val fingerprint = (state as? app.snowfold.AppState.Ready)?.keyFingerprint
    val copyLabel = stringResource(R.string.cd_copy)

    val contentWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = if (tablet) 720.dp else contentWidth

    Box(modifier, contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = maxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("settings-list")
            .padding(horizontal = if (tablet) 0.dp else DesignTokens.pageMargin)
            .padding(bottom = if (tablet) DesignTokens.space8 else DesignTokens.phoneContentBottom),
    ) {
        // 外观
        sectionHeader(stringResource(R.string.appearance_section))
        SfCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)) {
                Text(stringResource(R.string.theme_label), style = SnowFoldType.body.appType(), color = colors.text)
                Spacer(Modifier.height(DesignTokens.space2))
                SfSegmented(
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
                    testTag = "theme-segmented",
                )
                Text(
                    stringResource(R.string.theme_help),
                    style = SnowFoldType.caption.appType(),
                    color = colors.text2,
                    modifier = Modifier.padding(top = DesignTokens.space2),
                )
            }
        }
        Spacer(Modifier.height(DesignTokens.space4))

        // 数据
        sectionHeader(stringResource(R.string.data_section))
        SfCard {
            SettingsRow(
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
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.import_backup_title),
                help = stringResource(R.string.import_backup_help),
                onClick = { importJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.clear_all_title),
                help = stringResource(R.string.clear_all_help),
                onClick = vm::requestClearData,
                danger = true,
            )
        }
        Spacer(Modifier.height(DesignTokens.space4))

        // 加密备份
        sectionHeader(stringResource(R.string.encrypted_backup_section))
        SfCard {
            SettingsRow(
                title = stringResource(R.string.encrypted_backup_section),
                help = stringResource(R.string.encrypted_backup_help),
                onClick = {
                    scope.launch {
                        runCatching { vm.exportSealedWithFingerprint() }
                            .onSuccess { (bytes, fingerprint) ->
                                pendingSealed = bytes
                                pendingSealedFingerprint = fingerprint
                                exportSealedLauncher.launch("snowfold-backup.sfseal")
                            }
                            .onFailure {
                                vm.notifyExportFailed(
                                    detailRes = app.snowfold.data.coreErrorRes(it),
                                    templateRes = R.string.sealed_export_failed,
                                )
                            }
                    }
                },
            )
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.restore_encrypted_backup_title),
                help = stringResource(R.string.restore_encrypted_backup_help),
                onClick = { importSealedLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            )
            RowDivider(startInset = DesignTokens.pageMargin)
            // 同步密钥指纹：右侧等宽 text2；长按复制 + 读屏自定义动作（§6.1）
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DesignTokens.rowHeight)
                    .testTag("key-fingerprint")
                    .pointerInput(fingerprint) {
                        detectTapGestures(onLongPress = {
                            fingerprint?.let { value ->
                                clipboard.setText(AnnotatedString(value))
                            }
                        })
                    }
                    .semantics {
                        customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction(copyLabel) {
                                fingerprint?.let { clipboard.setText(AnnotatedString(it)) }
                                fingerprint != null
                            },
                        )
                    }
                    .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.snowfold.ui.components.ValueRow(
                    leading = { Text(stringResource(R.string.key_fingerprint_label), style = SnowFoldType.body.appType(), color = colors.text) },
                    trailing = { Text(fingerprint ?: stringResource(R.string.key_not_generated), style = SnowFoldType.caption.appType(), color = colors.text2) },
                )
            }
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.recovery_code_title),
                help = stringResource(R.string.recovery_code_help),
                onClick = null,
            )
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.export_recovery_code),
                help = null,
                onClick = exportRecovery,
            )
            RowDivider(startInset = DesignTokens.pageMargin)
            SettingsRow(
                title = stringResource(R.string.import_recovery_code),
                help = null,
                onClick = {
                    importRecoveryLauncher.launch(arrayOf("text/plain", "*/*"))
                },
            )
        }
        Spacer(Modifier.height(DesignTokens.space4))

        // 同步
        SyncSettings(vm, onExportRecovery = exportRecovery)
        Spacer(Modifier.height(DesignTokens.space4))

        // 关于
        sectionHeader(stringResource(R.string.about_section))
        SfCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)) {
                Text(stringResource(R.string.app_version), style = SnowFoldType.body.appType(), color = colors.text)
                Text(
                    stringResource(R.string.offline_description),
                    style = SnowFoldType.caption.appType(),
                    color = colors.text2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun sectionHeader(title: String) {
    Text(
        title,
        style = SnowFoldType.captionMedium.appType(),
        color = appColors().text2,
        modifier = Modifier.padding(start = 4.dp, top = 0.dp, bottom = DesignTokens.space2),
    )
}
