package app.snowfold.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 移动 sheet（mobile-layout.md §6）：从底部弹出到状态栏下，顶部圆角 28、bg 底、
 * 抓手 5×36，可下拉关闭（等于「取消」）。头部：左「取消」44 圆形浮层按钮（close），
 * 中标题（分区标题级），右「保存」44 圆形（brand 底 + check；必填未填时 sep 底）。
 */
@Composable
fun SfBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = appColors()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(1f) }
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dismissed by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(Unit) { progress.animateTo(0f, tween(250)) }
    fun close() {
        if (dismissed) return
        dismissed = true
        scope.launch {
            progress.animateTo(1f, tween(200))
            onDismiss()
        }
    }
    BackHandler { close() }
    val offsetPx = progress.value * screenHeight

    SheetWindow(onDismiss = { close() }) {
    Box(
        Modifier
            .fillMaxSize()
            .platformTag("editor-sheet"),
    ) {
        // 遮罩（§1）
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { close() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val next = progress.value + delta / screenHeight
                        if (next > 0f) {
                            scope.launch { progress.snapTo(next.coerceIn(0f, 1f)) }
                        }
                    },
                    onDragStopped = {
                        if (progress.value > 0.12f) close() else scope.launch { progress.animateTo(0f, tween(200)) }
                    },
                )
                .clip(RoundedCornerShape(topStart = DesignTokens.sheetCorner, topEnd = DesignTokens.sheetCorner))
                .background(colors.bg)
                .navigationBarsPadding()
                .then(modifier),
        ) {
            Spacer(Modifier.height(8.dp))
            // 抓手
            Box(
                Modifier
                    .width(36.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.sep)
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            // 头部：取消 | 标题 | 保存
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = AppIcon.close,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = { close() },
                    tint = colors.text,
                    testTag = "editor-cancel",
                )
                Text(
                    title,
                    style = SnowFoldType.headline.appType(),
                    color = colors.text,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                )
                if (onSave != null) {
                    val saveInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        Modifier
                            .size(DesignTokens.circleButton)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (saveEnabled) colors.brand else colors.sep)
                            .clickable(
                                interactionSource = saveInteraction,
                                indication = null,
                                enabled = saveEnabled,
                            ) { onSave() }
                            .platformTag("editor-save"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcon.check,
                            contentDescription = stringResource(R.string.save),
                            tint = if (saveEnabled) colors.onBrand else colors.text2,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(DesignTokens.circleButton))
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DesignTokens.pageMargin),
            ) {
                content()
                Spacer(Modifier.height(DesignTokens.space6))
            }
        }
    }
    }
}

/**
 * 平板表单 sheet（mobile-layout.md §6/§8）：居中、宽 540、高度随内容、
 * 中心线在 46%、圆角 28、无抓手。
 */
@Composable
fun SfCenterSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = appColors()
    val sheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(Unit) { progress.animateTo(0f, tween(250)) }
    fun close() = scope.launch { progress.animateTo(1f, tween(200)); onDismiss() }
    BackHandler { close() }

    SheetWindow(onDismiss = { close() }) {
    Box(Modifier.fillMaxSize().platformTag("editor-sheet")) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { close() },
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-0.04f * LocalConfiguration.current.screenHeightDp).dp) // 中心线在 46%
                .width(540.dp)
                .heightIn(min = minOf(320.dp, sheetMaxHeight), max = sheetMaxHeight)
                .graphicsLayer { alpha = 1f - progress.value }
                .floatingShadow(RoundedCornerShape(DesignTokens.sheetCorner))
                .clip(RoundedCornerShape(DesignTokens.sheetCorner))
                .background(colors.bg)
                .then(modifier),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = AppIcon.close,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = { close() },
                    tint = colors.text,
                    testTag = "editor-cancel",
                )
                Text(
                    title,
                    style = SnowFoldType.headline.appType(),
                    color = colors.text,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    textAlign = TextAlign.Center,
                )
                if (onSave != null) {
                    Box(
                        Modifier
                            .size(DesignTokens.circleButton)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (saveEnabled) colors.brand else colors.sep)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                enabled = saveEnabled,
                            ) { onSave() }
                            .platformTag("editor-save"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcon.check,
                            contentDescription = stringResource(R.string.save),
                            tint = if (saveEnabled) colors.onBrand else colors.text2,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(DesignTokens.circleButton))
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DesignTokens.space6),
            ) {
                content()
                Spacer(Modifier.height(DesignTokens.space6))
            }
        }
    }
    }
}

/**
 * 移动确认框（design.md §5.6）：居中、宽 min(320, 屏宽−48)、圆角 28（系统警告框形态），
 * 标题居中、正文居中（辅助 text2）；按钮纵向满宽：危险按钮在上、「取消」在下。
 */
@Composable
fun SfConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    BackHandler { onDismiss() }
    SheetWindow(onDismiss = onDismiss) {
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(DesignTokens.sheetCorner))
                .background(colors.card)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(DesignTokens.space5),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = SnowFoldType.headline.appType(),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = SnowFoldType.caption.appType(),
                color = colors.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = DesignTokens.space2),
            )
            Spacer(Modifier.height(DesignTokens.space5))
            SfButton(confirmLabel, onConfirm, style = ButtonStyle.Danger, fillMaxWidth = true)
            Spacer(Modifier.height(DesignTokens.space2))
            SfButton(stringResource(R.string.cancel), onDismiss, style = ButtonStyle.Secondary, fillMaxWidth = true)
        }
    }
    }
}

/** 长按菜单：卡片式弹层（自绘，不用 Material DropdownMenu 外观）。 */
@Composable
fun SfMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    dangerIndexes: Set<Int> = emptySet(),
    testTag: String? = null,
) {
    val colors = appColors()
    if (!expanded) return
    androidx.compose.ui.window.Popup(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            modifier
                .widthIn(min = 160.dp)
                .shadow(8.dp, RoundedCornerShape(DesignTokens.cardCorner))
                .clip(RoundedCornerShape(DesignTokens.cardCorner))
                .background(colors.card)
                .padding(vertical = 4.dp)
                .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier),
        ) {
            items.forEachIndexed { index, (label, action) ->
                val danger = index in dangerIndexes
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {
                            onDismiss()
                            action()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = SnowFoldType.body.appType(),
                        color = if (danger) colors.neg else colors.text,
                    )
                }
            }
        }
    }
}

/** 只使用 Android 原生窗口作焦点与读屏隔离，视觉由内部 Compose 内容绘制。 */
@Composable
private fun SheetWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        content()
    }
}
