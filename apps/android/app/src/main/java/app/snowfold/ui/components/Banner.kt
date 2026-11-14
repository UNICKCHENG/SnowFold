package app.snowfold.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType

/** 触感反馈（docs/design.md §7）：按场景分级，无振动平台静默。 */
object Haptics {
    fun success(view: android.view.View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun warning(view: android.view.View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun light(view: android.view.View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/**
 * 可撤销横幅（docs/design.md §6 横幅 / §7）：card 92% 不透明 + hairline 描边 +
 * 阴影 y=4 blur=12 黑 18%；左消息右「撤销 · 6秒内」；底部 3pt 倒计时进度条
 * （accent@80%），0.12s 步进线性，到期自动撤下。
 */
@Composable
fun UndoBanner(
    message: String,
    actionLabel: String,
    validForMs: Long,
    onAction: () -> Unit,
    onExpired: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val tokens = appColors()
        val progress = remember { mutableFloatStateOf(1f) }
        LaunchedEffect(message) {
            val steps = (validForMs / 120L).coerceAtLeast(1L)
            var left = steps
            while (left > 0) {
                progress.floatValue = left.toFloat() / steps
                kotlinx.coroutines.delay(120)
                left -= 1
            }
            progress.floatValue = 0f
            onExpired()
        }
        BannerSurface(modifier = modifier) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                    Text(
                        text = message,
                        style = SnowFoldType.body,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAction) {
                        Text(text = actionLabel, style = SnowFoldType.badge, color = tokens.accent)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(tokens.accent.copy(alpha = 0.8f * progress.floatValue)),
                )
            }
        }
    }
}

/** 通知条：同材质小卡 + 右侧 ×。 */
@Composable
fun NoticeBar(
    message: String,
    tone: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    BannerSurface(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 3.dp, height = 16.dp)
                    .background(tone, RoundedCornerShape(2.dp)),
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
            Text(
                text = message,
                style = SnowFoldType.body,
                color = tokens.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    appIcon(AppIcon.close),
                    contentDescription = stringResource(app.snowfold.R.string.close),
                    tint = tokens.ink2,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 横幅基底：card 92% + hairline 描边 + 阴影（无材质平台的规格）。 */
@Composable
fun BannerSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = appColors()
    val shape = RoundedCornerShape(DesignTokens.sheetCorner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .background(tokens.card.copy(alpha = 0.92f))
            .border(1.dp, tokens.hairline, shape),
        content = content,
    )
}
