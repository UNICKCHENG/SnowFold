package app.snowfold.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.core.SyncBackend
import app.snowfold.core.SyncConfig
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.FormFootnote
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfSwitch
import app.snowfold.ui.components.SfTextField
import app.snowfold.ui.components.SelectorField
import app.snowfold.ui.components.SfButton
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.syncTimeText
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.ZonedDateTime

/** 同步分区（mobile-layout §5.4）：未配置表单 / 已配置开关、存储行、策略组。 */
@Composable
fun SyncSettings(vm: MainViewModel, onExportRecovery: () -> Unit) {
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.syncError.collectAsStateWithLifecycle()
    val current = status
    val config = current?.config
    val colors = appColors()
    var backend by remember(config) { mutableStateOf(config?.backend ?: SyncBackend.S3) }
    var endpoint by remember(config) { mutableStateOf(config?.endpoint ?: "") }
    var bucket by remember(config) { mutableStateOf(config?.bucket ?: "") }
    var region by remember(config) { mutableStateOf(config?.region ?: "") }
    var prefix by remember(config) { mutableStateOf(config?.prefix ?: "") }
    var username by remember(config) { mutableStateOf(config?.username ?: "") }
    var pathStyle by remember(config) { mutableStateOf(config?.pathStyle ?: true) }
    var secret by remember(config, backend) { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    var intervalOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var backendOpen by remember { mutableStateOf(false) }
    val showForm = config == null || editing

    Text(
        stringResource(R.string.settings_sync_header),
        style = SnowFoldType.captionMedium.appType(),
        color = colors.text2,
        modifier = Modifier.padding(start = 4.dp, bottom = DesignTokens.space2).testTag("sync-section"),
    )

    if (showForm) {
        // 未配置或点「修改」：表单卡片（标签宽 112 + 输入），字段已按当前配置预填
        SfCard {
            Column {
                SyncFormRow(R.string.sync_backend_label) {
                    Box {
                        SelectorField(
                            value = backend.backendLabel(),
                            onClick = { backendOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "sync-backend",
                        )
                        app.snowfold.ui.components.SfMenu(
                            expanded = backendOpen,
                            onDismiss = { backendOpen = false },
                            items = listOf(
                                stringResource(R.string.sync_backend_s3) to { backend = SyncBackend.S3; backendOpen = false },
                                stringResource(R.string.sync_backend_webdav) to { backend = SyncBackend.WEB_DAV; backendOpen = false },
                            ),
                        )
                    }
                }
                RowDivider(startInset = DesignTokens.pageMargin)
                SyncFieldRow(R.string.sync_endpoint_label, endpoint, { endpoint = it }, "sync-endpoint")
                RowDivider(startInset = DesignTokens.pageMargin)
                if (backend == SyncBackend.S3) {
                    SyncFieldRow(R.string.sync_bucket_label, bucket, { bucket = it }, "sync-bucket")
                    RowDivider(startInset = DesignTokens.pageMargin)
                    SyncFieldRow(R.string.sync_region_label, region, { region = it }, "sync-region")
                    RowDivider(startInset = DesignTokens.pageMargin)
                }
                SyncFieldRow(R.string.sync_prefix_label, prefix, { prefix = it }, "sync-prefix")
                RowDivider(startInset = DesignTokens.pageMargin)
                SyncFieldRow(
                    if (backend == SyncBackend.S3) R.string.sync_access_key_label else R.string.sync_username_label,
                    username, { username = it }, "sync-username",
                )
                RowDivider(startInset = DesignTokens.pageMargin)
                SyncFieldRow(
                    if (backend == SyncBackend.S3) R.string.sync_secret_key_label else R.string.sync_password_label,
                    secret, { secret = it }, "sync-secret",
                    obscured = true,
                )
                if (backend == SyncBackend.S3) {
                    RowDivider(startInset = DesignTokens.pageMargin)
                    SwitchRow(R.string.sync_path_style_label, pathStyle, { pathStyle = it })
                }
            }
        }
        FormFootnote(
            error = error?.let { stringResource(it) },
            help = stringResource(R.string.sync_help),
            modifier = Modifier.padding(top = DesignTokens.space2),
        )
        if (current?.recoveryCodeExported != true) {
            Text(
                stringResource(R.string.sync_recovery_required_help),
                style = SnowFoldType.caption.appType(),
                color = colors.text2,
                modifier = Modifier.padding(top = DesignTokens.space2),
            )
        }
        SfButton(
            stringResource(R.string.sync_save_config),
            {
                vm.configureSync(
                    SyncConfig(
                        backend, endpoint, if (backend == SyncBackend.S3) bucket else "",
                        if (backend == SyncBackend.S3) region else "", prefix, username,
                        backend == SyncBackend.S3 && pathStyle,
                    ),
                    secret,
                )
                secret = ""
                editing = false
            },
            fillMaxWidth = true,
            enabled = !busy && !syncing,
            modifier = Modifier.padding(top = DesignTokens.space3),
            testTag = "sync-save",
        )
        if (current != null && current.recoveryCodeExported != true) {
            TextLink(stringResource(R.string.export_recovery_code), onExportRecovery, modifier = Modifier.padding(top = DesignTokens.space2))
        }
    } else {
        // 已配置
        SfCard {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sync_enable), style = SnowFoldType.body.appType(), color = colors.text)
                        val time = syncTimeDisplay(current.lastSyncedAt)
                        Text(
                            text = when {
                                syncing -> stringResource(R.string.sync_in_progress)
                                current.lastSyncedAt == null -> stringResource(R.string.sync_never_synced)
                                else -> stringResource(R.string.sync_last_synced, time)
                            },
                            style = SnowFoldType.caption.appType(),
                            color = colors.text2,
                            modifier = Modifier.padding(top = 2.dp).testTag("sync-status"),
                        )
                    }
                    SfSwitch(
                        checked = current.enabled,
                        onChange = vm::setSyncEnabled,
                        enabled = current.recoveryCodeExported && !syncing && !busy,
                        testTag = "sync-enable",
                    )
                }
                RowDivider(startInset = DesignTokens.pageMargin)
                // 存储行：说明为地址，右侧「修改」
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sync_storage_label), style = SnowFoldType.body.appType(), color = colors.text)
                        Text(
                            "${config.backend.backendLabel()} · ${config.endpoint}",
                            style = SnowFoldType.caption.appType(),
                            color = colors.text2,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    TextLink(stringResource(R.string.sync_edit_config), { editing = true }, testTag = "sync-edit")
                }
                RowDivider(startInset = DesignTokens.pageMargin)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextLink(
                        stringResource(R.string.sync_now),
                        { vm.syncNow() },
                        icon = AppIcon.refresh,
                        modifier = Modifier.testTag("sync-now"),
                    )
                }
            }
        }
        FormFootnote(error = error?.let { stringResource(it) }, help = stringResource(R.string.sync_help))

        // 同步策略组
        Spacer(Modifier.height(DesignTokens.space4))
        Text(
            stringResource(R.string.sync_policy_header),
            style = SnowFoldType.captionMedium.appType(),
            color = colors.text2,
            modifier = Modifier.padding(start = 4.dp, bottom = DesignTokens.space2),
        )
        SfCard {
            Column {
                SwitchRow(
                    R.string.sync_on_launch_label, current.policy.onLaunch,
                    { vm.setSyncPolicy(current.policy.copy(onLaunch = it)) },
                    enabled = !busy && !syncing,
                )
                RowDivider(startInset = DesignTokens.pageMargin)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.sync_interval_label), style = SnowFoldType.body.appType(), color = colors.text, modifier = Modifier.weight(1f))
                    Box {
                        val intervals = listOf(0u, 15u, 30u, 60u)
                        val intervalLabels = listOf(R.string.sync_interval_off, R.string.sync_interval_15m, R.string.sync_interval_30m, R.string.sync_interval_1h)
                        SelectorField(
                            value = stringResource(intervalLabels[intervals.indexOf(current.policy.intervalMinutes).coerceAtLeast(0)]),
                            onClick = { intervalOpen = true },
                            modifier = Modifier.width(160.dp).testTag("sync-interval"),
                        )
                        if (intervalOpen) {
                            app.snowfold.ui.components.SfMenu(
                                expanded = true,
                                onDismiss = { intervalOpen = false },
                                items = intervalLabels.map { res -> stringResource(res) to {
                                    vm.setSyncPolicy(current.policy.copy(intervalMinutes = intervals[intervalLabels.indexOf(res)]))
                                } },
                            )
                        }
                    }
                }
                RowDivider(startInset = DesignTokens.pageMargin)
                SwitchRow(
                    R.string.sync_after_edit_label, current.policy.afterEdit,
                    { vm.setSyncPolicy(current.policy.copy(afterEdit = it)) },
                    enabled = !busy && !syncing,
                )
            }
        }
        Text(
            stringResource(R.string.sync_interval_help),
            style = SnowFoldType.caption.appType(),
            color = colors.text2,
            modifier = Modifier.padding(top = DesignTokens.space2),
        )

        TextLink(
            stringResource(R.string.sync_clear_config),
            { clearConfirm = true },
            danger = true,
            modifier = Modifier.padding(top = DesignTokens.space4),
        )
    }

    if (clearConfirm) {
        app.snowfold.ui.components.SfConfirmDialog(
            title = stringResource(R.string.sync_clear_confirmation_title),
            body = stringResource(R.string.sync_clear_confirmation_body),
            confirmLabel = stringResource(R.string.sync_clear_config),
            onConfirm = { clearConfirm = false; secret = ""; vm.clearSyncConfig() },
            onDismiss = { clearConfirm = false },
        )
    }
}

@Composable
private fun SyncBackend.backendLabel(): String = stringResource(
    if (this == SyncBackend.S3) R.string.sync_backend_s3 else R.string.sync_backend_webdav,
)

/** {time}：1 分钟内「刚刚」，1 小时内「N 分钟前」，否则本地短日期时间。 */
@Composable
private fun syncTimeDisplay(value: String?): String {
    if (value == null) return stringResource(R.string.sync_never_synced)
    val relative = syncTimeText(value, ZonedDateTime.now())
    return when {
        relative.isEmpty() -> stringResource(R.string.sync_relative_just_now)
        relative.all { it.isDigit() } -> stringResource(R.string.sync_relative_minutes, relative)
        else -> relative
    }
}

/** 表单行（标签宽 112 + 输入）；存储类型与普通字段共用布局。 */
@Composable
private fun SyncFormRow(label: Int, content: @Composable () -> Unit) {
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth().padding(DesignTokens.pageMargin)) {
            Text(stringResource(label), style = SnowFoldType.body.appType(), color = appColors().text)
            Spacer(Modifier.height(DesignTokens.space2))
            content()
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space2),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = SnowFoldType.body.appType(), color = appColors().text, modifier = Modifier.width(112.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun SyncFieldRow(label: Int, value: String, onChange: (String) -> Unit, tag: String, obscured: Boolean = false) {
    SyncFormRow(label) {
        SfTextField(value, onChange, Modifier.fillMaxWidth(), obscured = obscured, testTag = tag)
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, tag: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = SnowFoldType.body.appType(), color = appColors().text, modifier = Modifier.weight(1f))
        SfSwitch(checked = checked, onChange = onChange, enabled = enabled, testTag = tag)
    }
}
