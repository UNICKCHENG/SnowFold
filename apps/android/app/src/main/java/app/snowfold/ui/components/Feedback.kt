package app.snowfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 撤销横幅（§5.8/mobile-layout §7）：浮层材质胶囊、最小高 52、可换行；
 * trash 16（text2）+ 消息 + 文字按钮「撤销 · 6秒内」（undo 16）。
 */
@Composable
fun UndoBanner(
    message: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
) {
    val colors = appColors()
    Row(
        modifier
            .widthIn(max = (LocalConfiguration.current.screenWidthDp - 32).dp)
            .clip(RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcon.trash, contentDescription = null, tint = colors.text2, modifier = Modifier.size(16.dp))
        Text(message, style = SnowFoldType.caption.appType(), color = colors.text, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember2(), indication = null, onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcon.undo, contentDescription = null, tint = colors.brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(actionLabel, style = SnowFoldType.captionMedium.appType(), color = colors.brand)
        }
    }
}

/** 通知横幅（§5.8）：check 16（pos）/ warning-triangle 16（neg）+ 消息 + 关闭。 */
@Composable
fun NoticeBanner(
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    Row(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (error) AppIcon.warningTriangle else AppIcon.check,
            contentDescription = null,
            tint = if (error) colors.neg else colors.pos,
            modifier = Modifier.size(16.dp),
        )
        Text(message, style = SnowFoldType.caption.appType(), color = colors.text, modifier = Modifier.weight(1f))
        Icon(
            AppIcon.close,
            contentDescription = stringResource(R.string.close),
            tint = colors.text2,
            modifier = Modifier
                .size(44.dp)
                .padding(12.dp)
                .clickable(interactionSource = remember2(), indication = null, onClick = onDismiss),
        )
    }
}

@Composable
private fun remember2(): MutableInteractionSource = androidx.compose.runtime.remember { MutableInteractionSource() }

/**
 * 同步按钮（§5.9）：44 圆形浮层按钮，refresh 20；空闲 text 色、同步中 brand 色
 * 匀速旋转且不可再按、失败右上 8 号 neg 圆点（2 像素卡片色描边）。
 */
@Composable
fun SyncButton(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val colors = appColors()
    val syncing by vm.syncing.collectAsState()
    val failed = vm.syncError.collectAsState().value != null
    // 系统减少动态效果：关闭旋转（design.md §6），只保留 brand 色与读屏播报。
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val reduceMotion = android.provider.Settings.Global.getFloat(
        resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    val label = stringResource(
        when {
            syncing -> R.string.sync_in_progress
            failed -> R.string.sync_button_failed_a11y
            else -> R.string.sync_button_a11y
        },
    )
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    Box(
        modifier
            .size(DesignTokens.circleButton)
            .floatingShadow(CircleShape)
            .background(colors.glass, CircleShape)
            .border(0.5.dp, colors.glassEdge, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !syncing,
            ) { vm.syncNow() }
            .platformTag("sync-button")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcon.refresh,
            contentDescription = null,
            tint = if (syncing) colors.brand else colors.text,
            modifier = Modifier
                .size(20.dp)
                .syncSpin(enabled = syncing, reduceMotion = reduceMotion),
        )
        if (failed && !syncing) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .border(2.dp, colors.card, CircleShape)
                    .background(colors.neg, CircleShape)
                    .platformTag("sync-failed-dot"),
            )
        }
    }
}

/** {time}：1 分钟内「刚刚」，1 小时内「N 分钟前」，否则本地短日期。 */
fun syncTimeText(value: String?, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
    val instant = value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val minutes = java.time.Duration.between(zoned, now).toMinutes()
    return when {
        minutes < 1 -> ""
        minutes < 60 -> minutes.toString()
        else -> zoned.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}
