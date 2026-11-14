package app.snowfold.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.core.SyncBackend
import app.snowfold.core.SyncConfig
import app.snowfold.ui.components.*
import app.snowfold.ui.theme.SnowFoldType

/** 凭证仅在该表单的 remember 内存中存在，不进入 SavedStateHandle 或 ViewModel 状态。 */
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

    Text(stringResource(R.string.settings_sync_header), style = SnowFoldType.sectionHeader,
        color = colors.ink2, modifier = Modifier.testTag("sync-section"))
    AppCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_help), style = SnowFoldType.helper, color = colors.ink3)
            if (current?.recoveryCodeExported != true) {
                Text(stringResource(R.string.sync_recovery_required_help), style = SnowFoldType.helper, color = colors.ink2)
                TextButton(onClick = onExportRecovery) { Text(stringResource(R.string.export_recovery_code)) }
            }
            Text(stringResource(R.string.sync_backend_label), style = SnowFoldType.body, color = colors.ink)
            SnowFoldSegmented(
                options = listOf(stringResource(R.string.sync_backend_s3), stringResource(R.string.sync_backend_webdav)),
                selected = if (backend == SyncBackend.S3) 0 else 1,
                onSelect = { backend = if (it == 0) SyncBackend.S3 else SyncBackend.WEB_DAV },
                modifier = Modifier.testTag("sync-backend"),
            )
            SyncField(R.string.sync_endpoint_label, endpoint, { endpoint = it }, "sync-endpoint")
            if (backend == SyncBackend.S3) {
                SyncField(R.string.sync_bucket_label, bucket, { bucket = it }, "sync-bucket")
                SyncField(R.string.sync_region_label, region, { region = it }, "sync-region")
            }
            SyncField(R.string.sync_prefix_label, prefix, { prefix = it }, "sync-prefix")
            if (backend == SyncBackend.S3) SyncSwitch(R.string.sync_path_style_label, pathStyle, { pathStyle = it })
            SyncField(if (backend == SyncBackend.S3) R.string.sync_access_key_label else R.string.sync_username_label,
                username, { username = it }, "sync-username")
            OutlinedTextField(secret, { secret = it }, modifier = Modifier.fillMaxWidth().testTag("sync-secret"),
                label = { Text(stringResource(if (backend == SyncBackend.S3) R.string.sync_secret_key_label else R.string.sync_password_label)) },
                textStyle = SnowFoldType.body, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (config != null) Text(stringResource(R.string.sync_secret_reenter_help), style = SnowFoldType.helper, color = colors.ink3)
            FilledTonalButton(onClick = {
                vm.configureSync(SyncConfig(backend, endpoint, if (backend == SyncBackend.S3) bucket else "",
                    if (backend == SyncBackend.S3) region else "", prefix, username, backend == SyncBackend.S3 && pathStyle), secret)
                secret = ""
            }, enabled = !busy && !syncing, modifier = Modifier.testTag("sync-save"),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.ink, contentColor = colors.canvas)) { Text(stringResource(R.string.sync_save_config)) }

            if (config != null && current != null) {
                HorizontalDivider(color = colors.hairline)
                SyncSwitch(R.string.sync_enable, current.enabled, vm::setSyncEnabled,
                    enabled = current.recoveryCodeExported && !syncing && !busy, tag = "sync-enable")
                Text(stringResource(R.string.sync_policy_header), style = SnowFoldType.sectionHeader, color = colors.ink2)
                SyncSwitch(R.string.sync_on_launch_label, current.policy.onLaunch,
                    { vm.setSyncPolicy(current.policy.copy(onLaunch = it)) }, !busy && !syncing)
                Text(stringResource(R.string.sync_interval_label), style = SnowFoldType.body, color = colors.ink)
                val intervals = listOf(0u, 15u, 30u, 60u)
                val intervalLabels = listOf(R.string.sync_interval_off, R.string.sync_interval_15m, R.string.sync_interval_30m, R.string.sync_interval_1h)
                var intervalOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { intervalOpen = true }, enabled = !busy && !syncing) {
                        Text(stringResource(intervalLabels[intervals.indexOf(current.policy.intervalMinutes).coerceAtLeast(0)]), color = colors.accent)
                    }
                    DropdownMenu(expanded = intervalOpen, onDismissRequest = { intervalOpen = false }) {
                        intervalLabels.forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(stringResource(label), style = SnowFoldType.body) }, onClick = {
                                intervalOpen = false
                                vm.setSyncPolicy(current.policy.copy(intervalMinutes = intervals[index]))
                            })
                        }
                    }
                }
                SyncSwitch(R.string.sync_after_edit_label, current.policy.afterEdit,
                    { vm.setSyncPolicy(current.policy.copy(afterEdit = it)) }, !busy && !syncing)
                Text(stringResource(R.string.sync_interval_help), style = SnowFoldType.helper, color = colors.ink3)
                val time = syncTimeText(current.lastSyncedAt)
                Text(if (syncing) stringResource(R.string.sync_in_progress)
                    else if (current.lastSyncedAt == null) stringResource(R.string.sync_never_synced)
                    else stringResource(R.string.sync_last_synced, time), style = SnowFoldType.body,
                    color = colors.ink, modifier = Modifier.testTag("sync-status"))
                error?.let { Text(stringResource(it), style = SnowFoldType.helper, color = colors.negative) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalButton(onClick = { vm.syncNow() }, enabled = current.enabled && !syncing,
                        modifier = Modifier.testTag("sync-now"),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.ink, contentColor = colors.canvas)) { Text(stringResource(R.string.sync_now)) }
                    TextButton(onClick = { clearConfirm = true }, enabled = !busy && !syncing) {
                        Text(stringResource(R.string.sync_clear_config), color = colors.negative)
                    }
                }
            }
            Text(stringResource(R.string.sync_pairing_help), style = SnowFoldType.helper, color = colors.ink3)
        }
    }
    if (clearConfirm) ConfirmDialog(stringResource(R.string.sync_clear_confirmation_title),
        stringResource(R.string.sync_clear_confirmation_body), stringResource(R.string.sync_clear_config),
        danger = true, onConfirm = { clearConfirm = false; secret = ""; vm.clearSyncConfig() }, onDismiss = { clearConfirm = false })
}

@Composable
private fun SyncField(label: Int, value: String, onChange: (String) -> Unit, tag: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(stringResource(label)) },
        textStyle = SnowFoldType.body, singleLine = true)
}

@Composable
private fun SyncSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, tag: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = SnowFoldType.body, color = appColors().ink)
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}
