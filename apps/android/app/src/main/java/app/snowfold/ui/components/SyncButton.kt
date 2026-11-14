package app.snowfold.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.MainViewModel
import app.snowfold.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

@Composable
fun SyncButton(vm: MainViewModel) {
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val error by vm.syncError.collectAsStateWithLifecycle()
    if (status?.enabled != true) return
    val rotation = if (syncing) {
        val transition = rememberInfiniteTransition(label = "sync")
        val angle by transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "sync-angle")
        angle
    } else 0f
    IconButton(onClick = { vm.syncNow() }, enabled = !syncing, modifier = Modifier.testTag("sync-button")) {
        Box {
            Icon(Icons.Default.Sync,
                stringResource(if (error != null) R.string.sync_button_failed_a11y else R.string.sync_button_a11y),
                Modifier.size(24.dp).rotate(rotation), tint = appColors().ink2)
            if (error != null) Box(Modifier.align(Alignment.TopEnd).size(6.dp)
                .background(appColors().negative, CircleShape).testTag("sync-failed-dot"))
        }
    }
}

@Composable
fun syncTimeText(value: String?): String {
    if (value == null) return stringResource(R.string.sync_never_synced)
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); this.value = System.currentTimeMillis() }
    }
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return stringResource(R.string.sync_never_synced)
    val minutes = ((now - instant.toEpochMilli()).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> stringResource(R.string.sync_relative_just_now)
        minutes < 60 -> stringResource(R.string.sync_relative_minutes, minutes.toString())
        else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)
    }
}
