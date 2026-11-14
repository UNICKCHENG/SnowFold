package app.snowfold.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 集中主题常量：全部数值照 docs/design.md（冻结版）§1–§3 填写。
 * 改值只改这里，各页面不出现散落的颜色/字号/圆角/间距字面量。
 */
object DesignTokens {
    val screenPadding = 16.dp      // §3 页面左右边距
    val cardPadding = 16.dp        // §3 卡片内边距 14–16
    val cardGap = 12.dp            // §3 卡片间距 12–14
    val sectionGap = 22.dp         // §3 页面分区间距
    val cardCorner = 18.dp         // §1.4 页面卡片圆角
    val tileCorner = 14.dp         // §1.4 统计小块圆角
    val sheetCorner = 22.dp        // §1.4 sheet/确认弹层圆角
    val chipCorner = 9.dp          // §1.4 行内 chip 圆角
    val categoryChip = 28.dp       // §4 分类 chip 28pt
    val avatar = 34.dp             // §4 订阅首字头像 34pt
    val rowCorner = 10.dp
    val shareBarHeight = 4.dp      // §5 占比条 4pt 圆角
    // 页面栅格别名（列表页用）
    val pageMargin = screenPadding
    val space2 = cardGap
    val space3 = 16.dp
    val space4 = 24.dp
}

/** §2 字体层级；金额等宽数字由 AmountText 统一加 tnum。 */
object SnowFoldType {
    val hero = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
    val pageTitle = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    val cardNumber = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val statValue = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val rowValue = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val sectionHeader = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val helper = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val badge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

/** §1 语义色 + 层级色 + 分类色（glyph 与 chip 底）。 */
data class SnowFoldColors(
    val accent: Color,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val usd: Color,
    val canvas: Color,
    val card: Color,
    val tile: Color,
    val hairline: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val categoryGlyphs: List<Color>,
    val categoryChips: List<Color>,
    val isDark: Boolean,
)

/** 桌面端 Palette::new(light) 逐项对齐（apps/desktop/src/app/components.rs）。 */
private fun lightColors() = SnowFoldColors(
    accent = Color(0xFF2B59E0),
    positive = Color(0xFF1C7A50),
    negative = Color(0xFFAE4A3C),
    warning = Color(0xFFB0781E),
    usd = Color(0xFF191917), // 桌面端 USD 不另设色，随 muted
    canvas = Color(0xFFF1F1EE),
    card = Color(0xFFFFFFFF),
    tile = Color(0xFFF7F7F4),
    hairline = Color(0x17191917),
    ink = Color(0xFF191917),
    ink2 = Color(0xFF6F6F68),
    ink3 = Color(0xFF9D9D95),
    categoryGlyphs = listOf(
        Color(0xFF2B59E0), // cash  = accent
        Color(0xFF8C6D3F), // fixed
        Color(0xFF1C7A50), // investment = pos
        Color(0xFF6F6F68), // receivable = muted
        Color(0xFFAE4A3C), // debt = neg
    ),
    categoryChips = listOf(
        Color(0x172B59E0), // accent_soft
        Color(0x1A8C6D3F),
        Color(0x141C7A50), // pos_soft
        Color(0x1A6F6F68),
        Color(0x17AE4A3C),
    ),
    isDark = false,
)

private fun darkColors() = SnowFoldColors(
    accent = Color(0xFF8098FF),
    positive = Color(0xFF58C795),
    negative = Color(0xFFE58A7A),
    warning = Color(0xFFE0A83E),
    usd = Color(0xFFF3F3EE), // 桌面端 USD 不另设色
    canvas = Color(0xFF0B0B0A),
    card = Color(0xFF171716),
    tile = Color(0xFF1E1E1C),
    hairline = Color(0x15FFFFFF),
    ink = Color(0xFFF3F3EE),
    ink2 = Color(0xFF9B9B93),
    ink3 = Color(0xFF6C6C65),
    categoryGlyphs = listOf(
        Color(0xFF8098FF), // cash = accent
        Color(0xFFC9A06A)/* fixed 用同 8C6D3F（桌面端明暗同值） */,
        Color(0xFF58C795), // investment = pos
        Color(0xFF9B9B93), // receivable = muted
        Color(0xFFE58A7A), // debt = neg
    ),
    categoryChips = listOf(
        Color(0x248098FF),
        Color(0x1A8C6D3F),
        Color(0x2658C795),
        Color(0x1A9B9B93),
        Color(0x24E58A7A),
    ),
    isDark = true,
)

private val LocalSnowFoldColors = staticCompositionLocalOf { lightColors() }

@Composable
fun appColors(): SnowFoldColors = LocalSnowFoldColors.current

fun categoryTint(index: Int, colors: SnowFoldColors): Pair<Color, Color> =
    colors.categoryGlyphs[index.coerceIn(0, 4)] to colors.categoryChips[index.coerceIn(0, 4)]

/**
 * 品牌配色全量替换动态取色（设计规范口径），M3 组件色板由同一组令牌映射，
 * 保证 Material 组件与自绘组件观感一致。
 */
@Composable
fun SnowFoldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 品牌色板恒定（docs/design.md §1）；参数仅为调用方兼容保留。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) darkColors() else lightColors()
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = Color(0xFF101C46),
            primaryContainer = tokens.tile,
            onPrimaryContainer = tokens.ink,
            secondary = tokens.ink2,
            onSecondary = tokens.canvas,
            error = tokens.negative,
            onError = tokens.card,
            errorContainer = tokens.tile,
            onErrorContainer = tokens.negative,
            background = tokens.canvas,
            onBackground = tokens.ink,
            surface = tokens.card,
            onSurface = tokens.ink,
            surfaceVariant = tokens.tile,
            onSurfaceVariant = tokens.ink2,
            outline = tokens.hairline,
            outlineVariant = tokens.hairline,
            surfaceContainer = tokens.tile,
            surfaceContainerLow = tokens.card,
            surfaceContainerHigh = tokens.tile,
            surfaceContainerHighest = tokens.tile,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = tokens.tile,
            onPrimaryContainer = tokens.ink,
            secondary = tokens.ink2,
            onSecondary = tokens.canvas,
            error = tokens.negative,
            onError = tokens.card,
            errorContainer = tokens.tile,
            onErrorContainer = tokens.negative,
            background = tokens.canvas,
            onBackground = tokens.ink,
            surface = tokens.card,
            onSurface = tokens.ink,
            surfaceVariant = tokens.tile,
            onSurfaceVariant = tokens.ink2,
            outline = tokens.hairline,
            outlineVariant = tokens.hairline,
            surfaceContainer = tokens.tile,
            surfaceContainerLow = tokens.card,
            surfaceContainerHigh = tokens.tile,
            surfaceContainerHighest = tokens.tile,
        )
    }
    CompositionLocalProvider(LocalSnowFoldColors provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(
                displaySmall = SnowFoldType.hero,
                titleLarge = SnowFoldType.cardNumber,
                titleMedium = SnowFoldType.statValue,
                bodyLarge = SnowFoldType.rowTitle,
                bodyMedium = SnowFoldType.body,
                bodySmall = SnowFoldType.helper,
                labelLarge = SnowFoldType.sectionHeader,
                labelMedium = SnowFoldType.helper,
                labelSmall = SnowFoldType.helper,
                headlineSmall = SnowFoldType.cardNumber,
                headlineMedium = SnowFoldType.pageTitle,
            ),
            content = content,
        )
    }
}
