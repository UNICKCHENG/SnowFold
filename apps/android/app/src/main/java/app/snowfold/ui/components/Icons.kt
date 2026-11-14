package app.snowfold.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 语义图标 → Lucide（docs/design.md §4，Android 用 Lucide 列）。
 * 图标由构建时从 shared/icons/ui 转成 ImageVector（tools/svg2vector.py → IconsGen.kt）。
 */
object AppIcon {
    val overview: ImageVector = IconsGen.ic_chart_pie
    val assets: ImageVector = IconsGen.ic_wallet
    val subscriptions: ImageVector = IconsGen.ic_calendar_clock
    val settings: ImageVector = IconsGen.ic_sliders
    val back: ImageVector = IconsGen.ic_chevron_left
    val plus: ImageVector = IconsGen.ic_plus
    val trash: ImageVector = IconsGen.ic_trash
    val pencil: ImageVector = IconsGen.ic_pencil
    val refreshCw: ImageVector = IconsGen.ic_refresh_cw
    val circleCheck: ImageVector = IconsGen.ic_circle_check
    val circlePause: ImageVector = IconsGen.ic_circle_pause
    val circlePlay: ImageVector = IconsGen.ic_circle_play
    val undo: ImageVector = IconsGen.ic_undo_2
    val clock: ImageVector = IconsGen.ic_clock
    val circleHelp: ImageVector = IconsGen.ic_circle_help
    val hardDrive: ImageVector = IconsGen.ic_hard_drive
    val calendar: ImageVector = IconsGen.ic_calendar
    val close: ImageVector = IconsGen.ic_x
    val check: ImageVector = IconsGen.ic_check
    val arrowRight: ImageVector = IconsGen.ic_arrow_right
    val info: ImageVector = IconsGen.ic_info

    fun category(index: Int): ImageVector = when (index) {
        0 -> IconsGen.ic_wallet
        1 -> IconsGen.ic_house
        2 -> IconsGen.ic_trending_up
        3 -> IconsGen.ic_banknote
        else -> IconsGen.ic_credit_card
    }
}

/** 统一取图入口：直接返回 ImageVector，Icon(imageVector=) 重载可用。 */
fun appIcon(icon: ImageVector): ImageVector = icon
