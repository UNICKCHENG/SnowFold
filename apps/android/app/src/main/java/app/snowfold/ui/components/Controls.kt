package app.snowfold.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType

/** 按钮类型（§5.1）。 */
enum class ButtonStyle { Primary, Secondary, Danger, Text, Soft, TextDanger }

/**
 * 按钮：高 50（行内 32 由调用方用 inlineHeight 调）、圆角 10；
 * 悬停/按下/禁用/聚焦按 §5.1；带图标时图标 16 在文字左侧、间距 6。
 */
@Composable
fun SfButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    inlineHeight: androidx.compose.ui.unit.Dp? = null,
    fillMaxWidth: Boolean = false,
    testTag: String? = null,
) {
    val colors = appColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val height = inlineHeight ?: DesignTokens.buttonHeight
    val (bg, fg) = when (style) {
        ButtonStyle.Primary -> (if (pressed) colors.brand.darken(0.9f) else colors.brand) to colors.onBrand
        ButtonStyle.Secondary -> colors.card to colors.text
        ButtonStyle.Danger -> colors.neg to if (colors.isDark) appColors().bg else Color_white
        ButtonStyle.Text -> Color.Transparent to colors.brand
        ButtonStyle.TextDanger -> Color.Transparent to colors.neg
        ButtonStyle.Soft -> colors.brandSoft to colors.brand
    }
    val disabledBg = if (style == ButtonStyle.Text || style == ButtonStyle.TextDanger) Color.Transparent else colors.sep
    val disabledFg = colors.text2
    val base = modifier
        .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
        .heightIn(min = height)
        .clip(RoundedCornerShape(DesignTokens.controlCorner))
        .then(
            when (style) {
                ButtonStyle.Secondary -> Modifier.border(1.dp, colors.sep, RoundedCornerShape(DesignTokens.controlCorner))
                else -> Modifier
            },
        )
        .background(if (enabled) bg else disabledBg, RoundedCornerShape(DesignTokens.controlCorner))
        .clickable(
            enabled = enabled, role = Role.Button,
            interactionSource = interaction, indication = null, onClick = onClick,
        )
        .padding(horizontal = DesignTokens.space4, vertical = if (height >= 50.dp) 13.dp else 5.dp)
        .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier)
    Row(base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) fg else disabledFg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = SnowFoldType.bodyMedium.appType(),
            color = if (enabled) fg else disabledFg,
            textAlign = TextAlign.Center,
        )
    }
}

private val Color_white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)

private fun androidx.compose.ui.graphics.Color.darken(factor: Float) =
    androidx.compose.ui.graphics.Color(
        red = red * factor, green = green * factor, blue = blue * factor, alpha = alpha,
    )

/** 浅色按钮（§5.1 Soft）：brand-soft 底 + brand 字。 */
@Composable
fun SoftButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    SfButton(label, onClick, modifier, style = ButtonStyle.Soft, icon = icon, inlineHeight = 32.dp)
}

/** 44 圆形浮层按钮（§5.9/移动导航栏）：glass 底、图标 20；brand 实底用于 sheet 保存。 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: androidx.compose.ui.graphics.Color? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = appColors()
    val size = DesignTokens.circleButton
    val resolvedTint = tint ?: colors.text
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .floatingShadow(CircleShape)
            .clip(CircleShape)
            .background(if (filled) colors.brand else colors.glass, CircleShape)
            .then(
                if (!filled) {
                    Modifier.border(0.5.dp, colors.glassEdge, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled, role = Role.Button,
                interactionSource = interaction, indication = null, onClick = onClick,
            )
            .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (filled) colors.onBrand else resolvedTint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 平台 testTag 快捷。 */
fun Modifier.platformTag(tag: String): Modifier = this.testTag(tag)

/** 分段控件（§5.3）：外框 bg 底 + 1px sep、圆角 10、内边距 2；选中段 brand 底。 */
@Composable
fun SfSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val colors = appColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.controlCorner))
            .background(colors.bg)
            .border(1.dp, colors.sep, RoundedCornerShape(DesignTokens.controlCorner))
            .padding(2.dp)
            .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier)
            .selectableGroup(),
    ) {
        Row {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = DesignTokens.segmentedHeight - 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.brand else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        style = SnowFoldType.caption.appType(),
                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isSelected) colors.onBrand else colors.text2,
                    )
                }
            }
        }
    }
}

/** 开关（§5.3）：52×32，关 sep 底、开 brand 底，白色圆钮带阴影。 */
@Composable
fun SfSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = appColors()
    val knobPosition by animateDpAsState(if (checked) 20.dp else 2.dp, label = "knob")
    Box(
        modifier = modifier
            .width(DesignTokens.switchWidth)
            .height(DesignTokens.switchHeight)
            .clip(RoundedCornerShape(99.dp))
            .background(
                when {
                    !enabled -> colors.sep
                    checked -> colors.brand
                    else -> colors.sep
                },
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = knobPosition)
                .size(DesignTokens.switchHeight - 4.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White)
                .androidShadow(),
        )
    }
}

private fun Modifier.androidShadow(): Modifier =
    shadow(2.dp, CircleShape, ambientColor = Color(0x40000000), spotColor = Color(0x40000000))

/**
 * 输入框（§5.2）：card 底、1px sep 内描边、圆角 10、左右 10、高 44；
 * 聚焦 1.5 brand 描边；出错 1.5 neg。
 */
@Composable
fun SfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    amount: Boolean = false,
    currencySymbol: String? = null,
    error: Boolean = false,
    obscured: Boolean = false,
    testTag: String? = null,
    enabled: Boolean = true,
    errorMessage: String? = null,
    onDone: (() -> Unit)? = null,
) {
    val colors = appColors()
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(errorMessage) {
        if (errorMessage != null) requester.requestFocus()
    }
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val focusedNow = focused
    val borderColor = when {
        error -> colors.neg
        focusedNow -> colors.brand
        else -> colors.sep
    }
    Column(modifier) {
    Row(
        modifier = Modifier
            .heightIn(min = DesignTokens.inputHeight)
            .clip(RoundedCornerShape(DesignTokens.controlCorner))
            .background(colors.card)
            .border(if (error || focusedNow) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(DesignTokens.controlCorner))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currencySymbol != null) {
            Text(currencySymbol, style = SnowFoldType.body.appType(), color = colors.text2, modifier = Modifier.padding(end = 6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = { next -> onValueChange(if (amount) next.filter { it.isDigit() || it == '.' } else next) },
            singleLine = true,
            enabled = enabled,
            textStyle = SnowFoldType.body.appType().copy(color = colors.text),
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (amount) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Next,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
                onDone = { onDone?.invoke() },
            ),
            visualTransformation = if (obscured) PasswordVisualTransformation() else VisualTransformation.None,
            interactionSource = focus,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = SnowFoldType.body.appType(), color = colors.text2, maxLines = 1)
                    }
                    inner()
                }
            },
            modifier = (if (testTag != null) Modifier.platformTag(testTag) else Modifier)
                .then(Modifier.focusRequester(requester)).fillMaxWidth(),
        )
    }
    if (errorMessage != null) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcon.warningTriangle, contentDescription = null, tint = colors.neg, modifier = Modifier.size(16.dp))
            Text(errorMessage, style = SnowFoldType.caption.appType(), color = colors.neg, modifier = Modifier.padding(start = 6.dp))
        }
    }
    }

}

/** 选择器外观（§5.2）：同输入框，右侧 chevron-right / calendar 16（text2）。 */
@Composable
fun SelectorField(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: (@Composable () -> Unit)? = null,
    calendarIcon: Boolean = false,
    error: Boolean = false,
    testTag: String? = null,
) {
    val colors = appColors()
    Row(
        modifier = modifier
            .heightIn(min = DesignTokens.inputHeight)
            .clip(RoundedCornerShape(DesignTokens.controlCorner))
            .background(colors.card)
            .border(if (error) 1.5.dp else 1.dp, if (error) colors.neg else colors.sep, RoundedCornerShape(DesignTokens.controlCorner))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Text(
            value.ifEmpty { placeholder },
            style = SnowFoldType.body.appType(),
            color = if (value.isEmpty()) colors.text2 else colors.text,
            modifier = Modifier.weight(1f).padding(start = if (leading != null) 8.dp else 0.dp),
            maxLines = 1,
        )
        Icon(
            if (calendarIcon) AppIcon.calendar else AppIcon.chevronRight,
            contentDescription = null,
            tint = colors.text2,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 文字链接（brand / neg）。 */
@Composable
fun TextLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    testTag: String? = null,
) {
    val colors = appColors()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .then(if (testTag != null) Modifier.platformTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (danger) colors.neg else colors.brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = SnowFoldType.captionMedium.appType(), color = if (danger) colors.neg else colors.brand)
        if (chevron) {
            Icon(
                AppIcon.chevronRight,
                contentDescription = null,
                tint = if (danger) colors.neg else colors.brand,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
