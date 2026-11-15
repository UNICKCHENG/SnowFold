package app.snowfold.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 集中主题常量：全部数值照 shared/design/tokens.json 与 docs/design.md（冻结版）。
 * 移动档字号（dp=pt）；颜色浅深两值；改值只改这里。
 */
object DesignTokens {
    // 间距只用 4 的倍数（§4）
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val pageMargin = 16.dp

    // 圆角：卡片 16、控件 10、sheet/确认框 28（§4）
    val cardCorner = 16.dp
    val controlCorner = 10.dp
    val sheetCorner = 28.dp

    // 尺寸（§4 移动档）
    val rowHeight = 52.dp
    val rowDoubleHeight = 64.dp
    val buttonHeight = 50.dp
    val buttonInlineHeight = 32.dp
    val inputHeight = 44.dp
    val segmentedHeight = 32.dp
    val switchWidth = 52.dp
    val switchHeight = 32.dp
    val iconBlock = 32.dp
    val iconBlockSmall = 28.dp
    val avatar = 36.dp
    val circleButton = 44.dp
    val compBarHeight = 8.dp

    // 导航（mobile-layout.md §1/§8）
    val tabBarHeight = 64.dp
    val navBarHeight = 52.dp
    val tabletSidebarWidth = 260.dp
    val tabletToolbarHeight = 56.dp
    val phoneContentBottom = 120.dp
    val bannerBottom = 112.dp
}

/** Inter 变量字体：tnum 等宽数字在金额处按需叠加。 */
object SnowFoldFont {
    const val features = "'tnum' 1"
}

/** §2 字号层级（移动档）：字号/行高/字重。 */
object SnowFoldType {
    val display = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.28).sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val captionMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}

/** §1 语义色 + 分类色 + 浮层材质（浅深两值，tokens.json）。 */
data class SnowFoldColors(
    val brand: Color,
    val brandSoft: Color,
    val onBrand: Color,
    val bg: Color,
    val card: Color,
    val sep: Color,
    val text: Color,
    val text2: Color,
    val neg: Color,
    val negSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val pos: Color,
    val cat: List<Color>,
    val catSoft: List<Color>,
    // 浮层材质（§1）：标签栏、侧边栏、工具栏按钮、横幅。
    val glass: Color,
    val glassEdge: Color,
    val scrim: Color,
    val isDark: Boolean,
)

private fun lightColors() = SnowFoldColors(
    brand = Color(0xFF076B75),
    brandSoft = Color(0xFFE1EDEE),
    onBrand = Color(0xFFFFFFFF),
    bg = Color(0xFFF4F4F6),
    card = Color(0xFFFFFFFF),
    sep = Color(0xFFE2E2E7),
    text = Color(0xFF1C1C1E),
    text2 = Color(0xFF66666D),
    neg = Color(0xFFBF3228),
    negSoft = Color(0xFFF9EAEA),
    warn = Color(0xFF9A5B00),
    warnSoft = Color(0xFFF3EBE0),
    pos = Color(0xFF1F7A3E),
    cat = listOf(Color(0xFF946100), Color(0xFF3B7D23), Color(0xFF6A4FD0), Color(0xFF1F6FB0), Color(0xFFBF3228)),
    catSoft = listOf(Color(0xFFF2ECE0), Color(0xFFE7EFE5), Color(0xFFEDEAF9), Color(0xFFE4EEF6), Color(0xFFF9EAEA)),
    glass = Color(0xD6FAFAFC),
    glassEdge = Color(0xE6FFFFFF),
    scrim = Color(0x521C1C1E),
    isDark = false,
)

private fun darkColors() = SnowFoldColors(
    brand = Color(0xFF3FC1CC),
    brandSoft = Color(0xFF223A3E),
    onBrand = Color(0xFF0E0E10),
    bg = Color(0xFF0E0E10),
    card = Color(0xFF1C1C1F),
    sep = Color(0xFF2F2F35),
    text = Color(0xFFF2F2F5),
    text2 = Color(0xFF9C9CA4),
    neg = Color(0xFFFF6A5E),
    negSoft = Color(0xFF402829),
    warn = Color(0xFFF5B041),
    warnSoft = Color(0xFF3F3424),
    pos = Color(0xFF4CC77A),
    cat = listOf(Color(0xFFE0A93F), Color(0xFF7CC75B), Color(0xFFA796FF), Color(0xFF5FA8E8), Color(0xFFFF6A5E)),
    catSoft = listOf(Color(0xFF3F3525), Color(0xFF2D3B2A), Color(0xFF353247), Color(0xFF283543), Color(0xFF452A2A)),
    glass = Color(0xD6242428),
    glassEdge = Color(0x1FFFFFFF),
    scrim = Color(0x8F000000),
    isDark = true,
)

private val LocalSnowFoldColors = staticCompositionLocalOf { lightColors() }

@Composable
fun appColors(): SnowFoldColors = LocalSnowFoldColors.current

/** 分类语义 → (图标色, 图标底色)。 */
fun categoryTint(index: Int, colors: SnowFoldColors): Pair<Color, Color> =
    colors.cat[index.coerceIn(0, 4)] to colors.catSoft[index.coerceIn(0, 4)]

/**
 * 品牌配色（docs/design.md §1）。MaterialTheme 仅作为文本/基础组件宿主保留，
 * 全部控件外观由自绘组件提供（§7 安卓：不用 Material 控件外观）。
 */
@Composable
fun SnowFoldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) darkColors() else lightColors()
    val typography = Typography(
        displayLarge = SnowFoldType.display,
        displaySmall = SnowFoldType.display,
        headlineMedium = SnowFoldType.title,
        headlineSmall = SnowFoldType.headline,
        titleLarge = SnowFoldType.headline,
        titleMedium = SnowFoldType.bodyMedium,
        bodyLarge = SnowFoldType.body,
        bodyMedium = SnowFoldType.body,
        bodySmall = SnowFoldType.caption,
        labelLarge = SnowFoldType.bodyMedium,
        labelMedium = SnowFoldType.caption,
        labelSmall = SnowFoldType.caption,
    )
    CompositionLocalProvider(LocalSnowFoldColors provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) {
                androidx.compose.material3.darkColorScheme(
                    primary = tokens.brand, onPrimary = tokens.onBrand,
                    background = tokens.bg, onBackground = tokens.text,
                    surface = tokens.card, onSurface = tokens.text,
                    outline = tokens.sep, error = tokens.neg,
                )
            } else {
                androidx.compose.material3.lightColorScheme(
                    primary = tokens.brand, onPrimary = tokens.onBrand,
                    background = tokens.bg, onBackground = tokens.text,
                    surface = tokens.card, onSurface = tokens.text,
                    outline = tokens.sep, error = tokens.neg,
                )
            },
            typography = typography,
            content = content,
        )
    }
}
