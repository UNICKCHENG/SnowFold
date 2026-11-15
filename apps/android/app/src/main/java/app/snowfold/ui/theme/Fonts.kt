package app.snowfold.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.snowfold.R

/**
 * 西文与数字一律 Inter v4.1（内置变量字体，docs/design.md §2）；
 * 汉字由系统逐字回退到 Noto Sans CJK。字重 400/500/600 由变量轴提供。
 */
private val InterFamily = FontFamily(
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

/** 全部界面文字的默认字体族：Inter + 系统中文回退。 */
val AppFontFamily: FontFamily = InterFamily

/** 等宽金额样式：在类型样式上叠加 tnum 与 Inter。 */
fun TextStyle.appType(): TextStyle = this.copy(
    fontFamily = AppFontFamily,
    fontFeatureSettings = SnowFoldFont.features,
)

/** 应用内统一的文字入口：字体族 + tnum + 可关断行。 */
@Composable
fun AppText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    Text(
        text = text,
        style = style.appType(),
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        modifier = modifier,
    )
}
