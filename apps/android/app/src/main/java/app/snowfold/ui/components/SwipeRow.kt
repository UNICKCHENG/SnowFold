package app.snowfold.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 左滑动作定义：revealAction 常为「已续费」，deleteAction 为「删除」。 */
data class SwipeActions(
    val revealLabel: String? = null,
    val revealIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onReveal: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val deleteTag: String? = null,
)

private val actionWidth = 88.dp

/**
 * 左滑行（mobile-layout.md §3/§4）：露出「已续费」（brand 底，仅到期行）与
 * 「删除」（neg 底），各宽 88，图标 20 + 辅助中等文字；资产删除可一滑到底
 * （释放时超过总宽 60% 直接触发）。
 */
@Composable
fun SwipeRow(
    actions: SwipeActions?,
    modifier: Modifier = Modifier,
    fullSwipeDeletes: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = appColors()
    val scope = rememberCoroutineScope()
    val totalWidth = if (actions?.onReveal != null) actionWidth * 2 else actionWidth
    var offsetX by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val totalPx = with(density) { totalWidth.toPx() }
    LaunchedEffect(triggered) {
        if (triggered) {
            offsetX = 0f
            triggered = false
        }
    }
    val animated = animateDpAsState(
        targetValue = with(density) { offsetX.toDp() },
        animationSpec = if (offsetX == 0f) tween(200) else tween(0),
        label = "swipe",
    )
    Box(modifier) {
        // 动作层（右对齐）：滑出后才参与布局与读屏，避免遮住内容命中。
        if (actions != null && offsetX < -1f) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(totalWidth),
            ) {
                if (actions.onReveal != null) {
                    SwipeActionButton(
                        label = actions.revealLabel ?: "",
                        icon = actions.revealIcon ?: AppIcon.check,
                        background = colors.brand,
                        contentColor = colors.onBrand,
                        onClick = {
                            scope.launch { offsetX = 0f }
                            actions.onReveal.invoke()
                        },
                    )
                }
                if (actions.onDelete != null) {
                    SwipeActionButton(
                        label = stringResource(app.snowfold.R.string.delete),
                        icon = AppIcon.trash,
                        background = colors.neg,
                        contentColor = if (colors.isDark) colors.bg else androidx.compose.ui.graphics.Color.White,
                        onClick = {
                            scope.launch { offsetX = 0f }
                            actions.onDelete.invoke()
                        },
                        testTag = actions.deleteTag,
                    )
                }
            }
        }
        // 内容层
        Box(
            Modifier
                .offset(x = animated.value)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = actions != null,
                    state = rememberDraggableState { delta ->
                        val next = offsetX + delta
                        offsetX = next.coerceIn(-totalPx * 1.3f, 0f)
                    },
                    onDragStopped = { velocity ->
                        val settled = offsetX
                        // 一滑到底：释放时超过动作宽，或快速甩动越过半程（mobile-layout §3）。
                        val flung = velocity < -2500f && settled < -totalPx * 0.25f
                        when {
                            fullSwipeDeletes && actions?.onDelete != null &&
                                (settled < -totalPx * 0.55f || flung) -> {
                                    offsetX = 0f
                                actions.onDelete.invoke()
                            }

                            settled < -totalPx / 2 -> scope.launch { offsetX = -totalPx }
                            else -> scope.launch { offsetX = 0f }
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    val colors = appColors()
    Column(
        Modifier
            .width(actionWidth)
            .fillMaxHeight()
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 8.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = SnowFoldType.captionMedium.appType(), color = contentColor)
    }
}
