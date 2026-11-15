package app.snowfold.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 语义图标 → IconMCP 线条/实心两版（docs/design.md §3）。
 * 构建时从 shared/icons/line 与 shared/icons/fill 转成 ImageVector
 * （tools/svg2vector.py → IconsGen.kt）。导航选中态与强调态用实心版。
 */
object AppIcon {
    // 导航（选中用实心）
    val overviewLine: ImageVector = IconsGen.ic_line_pie_chart
    val overviewFill: ImageVector = IconsGen.ic_fill_pie_chart
    val assetsLine: ImageVector = IconsGen.ic_line_wallet
    val assetsFill: ImageVector = IconsGen.ic_fill_wallet
    val subscriptionsLine: ImageVector = IconsGen.ic_line_calendar_repeat
    val subscriptionsFill: ImageVector = IconsGen.ic_fill_calendar_repeat
    val settingsLine: ImageVector = IconsGen.ic_line_settings
    val settingsFill: ImageVector = IconsGen.ic_fill_settings

    // 动作（两版相同时只靠颜色区分选中）
    val plus: ImageVector = IconsGen.ic_line_plus
    val refresh: ImageVector = IconsGen.ic_line_refresh
    val check: ImageVector = IconsGen.ic_line_check
    val close: ImageVector = IconsGen.ic_line_close
    val trash: ImageVector = IconsGen.ic_line_trash
    val trashFill: ImageVector = IconsGen.ic_fill_trash
    val pause: ImageVector = IconsGen.ic_line_pause
    val play: ImageVector = IconsGen.ic_line_play
    val undo: ImageVector = IconsGen.ic_line_undo
    val info: ImageVector = IconsGen.ic_line_info
    val calendar: ImageVector = IconsGen.ic_line_calendar
    val database: ImageVector = IconsGen.ic_line_database
    val key: ImageVector = IconsGen.ic_line_key
    val shieldLock: ImageVector = IconsGen.ic_line_shield_lock
    val warningTriangle: ImageVector = IconsGen.ic_line_warning_triangle

    // 方向：生成链保留源 SVG 变换。
    val chevronRight: ImageVector = IconsGen.ic_line_chevron_right
    val chevronLeft: ImageVector = IconsGen.ic_line_chevron_left

    fun category(index: Int): ImageVector = when (index) {
        0 -> IconsGen.ic_line_banknote
        1 -> IconsGen.ic_line_home
        2 -> IconsGen.ic_line_trending_up
        3 -> IconsGen.ic_line_hand_coins
        else -> IconsGen.ic_line_credit_card
    }

    fun categoryFill(index: Int): ImageVector = when (index) {
        0 -> IconsGen.ic_fill_banknote
        1 -> IconsGen.ic_fill_home
        2 -> IconsGen.ic_fill_trending_up
        3 -> IconsGen.ic_fill_hand_coins
        else -> IconsGen.ic_fill_credit_card
    }
}
