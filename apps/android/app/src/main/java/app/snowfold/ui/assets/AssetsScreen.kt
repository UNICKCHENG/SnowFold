package app.snowfold.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AmountLevel
import app.snowfold.ui.components.AmountRowText
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.ButtonStyle
import app.snowfold.ui.components.CategoryEmptyRow
import app.snowfold.ui.components.CategoryIconBlock
import app.snowfold.ui.components.EmptyState
import app.snowfold.ui.components.historyTimestamp
import app.snowfold.ui.components.PillBadge
import app.snowfold.ui.components.RowDivider
import app.snowfold.ui.components.SfButton
import app.snowfold.ui.components.SfCard
import app.snowfold.ui.components.SfMenu
import app.snowfold.ui.components.SwipeActions
import app.snowfold.ui.components.SwipeRow
import app.snowfold.ui.components.TextLink
import app.snowfold.ui.components.categoryLabel
import app.snowfold.ui.components.dateShort
import app.snowfold.ui.model.AssetUi
import app.snowfold.ui.model.CategoryUi
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate

/**
 * 资产页（mobile-layout §3）：总资产焦点 + 五个分类分区（紧凑空状态行）；
 * 行左滑露出删除（可一滑到底）、长按菜单；点行推入资产详情。
 * 平板照 iPad：左列分类列表（380），右栏概览或详情（design.md §9.3）。
 */
@Composable
fun AssetsScreen(
    vm: MainViewModel,
    ui: UiLedger,
    bannerText: String?,
    onNewAsset: (Int) -> Unit,
    onOpenAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onRenameAsset: (String) -> Unit,
    onAdjustAsset: (String) -> Unit,
    editorTarget: Editor?,
    tablet: Boolean,
    selectedInPane: Boolean = false,
    focusCategory: Int = -1,
    modifier: Modifier = Modifier,
) {
    if (tablet && ui.assets.isEmpty()) {
        app.snowfold.ui.components.EmptyPage(modifier) {
            EmptyState(
                icon = AppIcon.assetsLine,
                title = stringResource(R.string.assets_empty_title),
                help = stringResource(R.string.assets_empty_help),
                action = { SfButton(stringResource(R.string.add_asset), { onNewAsset(0) }, testTag = "assets-add") },
            )
        }
        return
    }
    if (tablet) {
        val selectedAssetId = (editorTarget as? Editor.Asset)?.id
        Row(modifier.fillMaxWidth().padding(horizontal = DesignTokens.space6)) {
            AssetsList(
                ui = ui,
                tablet = true,
                selectedAssetId = if (selectedInPane) selectedAssetId else null,
                focusCategory = focusCategory,
                onNewAsset = {},
                onOpenAsset = onOpenAsset,
                onDeleteAsset = onDeleteAsset,
                onAdjustAsset = onAdjustAsset,
                onRenameAsset = onRenameAsset,
                modifier = Modifier.width(380.dp),
            )
            Spacer(Modifier.width(DesignTokens.space6))
            AssetPaneRight(
                ui = ui,
                selectedId = selectedAssetId,
                onAdjustAsset = onAdjustAsset,
                onRenameAsset = onRenameAsset,
                onDeleteAsset = onDeleteAsset,
                onCorrect = vm::openCorrection,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        AssetsList(
            ui = ui,
            tablet = false,
            selectedAssetId = null,
            focusCategory = focusCategory,
            onNewAsset = onNewAsset,
            onOpenAsset = onOpenAsset,
            onDeleteAsset = onDeleteAsset,
            onAdjustAsset = onAdjustAsset,
            onRenameAsset = onRenameAsset,
            modifier = modifier,
        )
    }
}

@Composable
private fun AssetsList(
    ui: UiLedger,
    tablet: Boolean,
    selectedAssetId: String?,
    focusCategory: Int,
    onNewAsset: (Int) -> Unit,
    onOpenAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onAdjustAsset: (String) -> Unit,
    onRenameAsset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    val listState = rememberLazyListState()
    val focusMap = remember(ui.categories, ui.assets) {
        var cursor = if (tablet) 0 else 1 // 手机汇总
        val map = mutableMapOf<Int, Int>()
        ui.categories.forEach { category ->
            map[category.index] = cursor
            cursor += 1 // 分区头
            cursor += 1 // 卡片
        }
        map
    }
    LaunchedEffect(focusCategory) {
        if (focusCategory >= 0) listState.animateScrollToItem(focusMap[focusCategory] ?: 0)
    }
    if (ui.assets.isEmpty()) {
        app.snowfold.ui.components.EmptyPage(modifier) {
            EmptyState(
                icon = AppIcon.assetsLine,
                title = stringResource(R.string.assets_empty_title),
                help = stringResource(R.string.assets_empty_help),
                action = { SfButton(stringResource(R.string.add_asset), { onNewAsset(0) }, testTag = "assets-add") },
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("assets-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = if (tablet) 0.dp else DesignTokens.pageMargin,
            end = if (tablet) 0.dp else DesignTokens.pageMargin,
            bottom = if (tablet) DesignTokens.space8 else DesignTokens.phoneContentBottom,
        ),
    ) {
        if (!tablet) item(key = "sums") {
            Column(Modifier.padding(top = DesignTokens.space2, bottom = DesignTokens.space4)) {
                Text(stringResource(R.string.total_assets), style = SnowFoldType.bodyMedium.appType(), color = colors.text2)
                AmountText(
                    ui.hero?.assetsText ?: stringResource(R.string.out_of_range),
                    AmountLevel.Title,
                    Modifier.fillMaxWidth().testTag("assets-total"),
                )
                Spacer(Modifier.height(DesignTokens.space2))
                val largeFont = LocalDensity.current.fontScale > 1.3f
                if (largeFont) {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.space1)) {
                        SumStat(stringResource(R.string.total_debt), ui.hero?.debtText, negative = true)
                        SumStat(stringResource(R.string.net_assets), ui.hero?.netText, negative = ui.hero?.netNegative == true)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.space5)) {
                        SumStat(stringResource(R.string.total_debt), ui.hero?.debtText, negative = true)
                        SumStat(stringResource(R.string.net_assets), ui.hero?.netText, negative = ui.hero?.netNegative == true)
                    }
                }
            }
        }
        ui.categories.forEach { category ->
            val categoryAssets = ui.assets.filter { it.categoryIndex == category.index }
            item(key = "cat-head-${category.index}") {
                CategorySectionHead(category, Modifier.padding(top = DesignTokens.space6).testTag("asset-group-${category.index}"))
            }
            item(key = "cat-body-${category.index}") {
                if (categoryAssets.isEmpty()) {
                    SfCard { CategoryEmptyRow(category.index, { onNewAsset(category.index) }) }
                } else {
                    SfCard {
                        categoryAssets.forEachIndexed { index, asset ->
                            if (index > 0) RowDivider(startInset = DesignTokens.pageMargin)
                            androidx.compose.runtime.key(asset.id) { AssetRow(
                                asset = asset,
                                selected = asset.id == selectedAssetId,
                                onOpen = { onOpenAsset(asset.id) },
                                onAdjust = { onAdjustAsset(asset.id) },
                                onRename = { onRenameAsset(asset.id) },
                                onDelete = { onDeleteAsset(asset.id) },
                                tablet = tablet,
                            ) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SumStat(label: String, value: String?, negative: Boolean) {
    val colors = appColors()
    Column {
        Text(label, style = SnowFoldType.caption.appType(), color = colors.text2)
        AmountRowText(value ?: stringResource(R.string.out_of_range), color = if (negative) colors.neg else colors.text)
    }
}

/** 分区头（mobile-layout §3）：图标块 28、分类名分区标题、{n} 项、右侧合计（负债 neg）。 */
@Composable
private fun CategorySectionHead(category: CategoryUi, modifier: Modifier = Modifier) {
    val colors = appColors()
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column(modifier.fillMaxWidth().padding(bottom = DesignTokens.space2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIconBlock(category.index, DesignTokens.iconBlockSmall)
            Text(categoryLabel(category.index), style = SnowFoldType.headline.appType(), color = colors.text,
                modifier = Modifier.padding(start = DesignTokens.space2))
            Text(stringResource(R.string.item_count, category.count), style = SnowFoldType.caption.appType(),
                color = colors.text2, modifier = Modifier.padding(start = DesignTokens.space2))
            if (!stacked) {
                Box(Modifier.weight(1f))
                AmountRowText(category.amountText, color = if (category.isLiability) colors.neg else colors.text)
            }
        }
        if (stacked) AmountRowText(category.amountText, color = if (category.isLiability) colors.neg else colors.text)
    }
}

/** 资产行：名称（可多行）+ 金额 + chevron；左滑删除（手机），长按菜单。 */
@Composable
private fun AssetRow(
    asset: AssetUi,
    selected: Boolean,
    onOpen: () -> Unit,
    onAdjust: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    var menuOpen by remember(asset.id) { androidx.compose.runtime.mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val menuItems = listOf(
        stringResource(R.string.adjust_balance) to onAdjust,
        stringResource(R.string.rename) to onRename,
        stringResource(R.string.delete) to onDelete,
    )
    val row: @Composable () -> Unit = { Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.brandSoft else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            )
            .testTag("asset-${asset.id}")
            .semantics {
                customActions = menuItems.map { (label, action) ->
                    androidx.compose.ui.semantics.CustomAccessibilityAction(label) { action(); true }
                }
            }
            .heightIn(min = DesignTokens.rowHeight)
            .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
    ) {
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.weight(1f)) {
                Text(asset.name, style = SnowFoldType.body.appType(), color = colors.text)
                AmountRowText(asset.amountText, color = if (asset.categoryIndex == 4) colors.neg else colors.text)
            }
            Icon(AppIcon.chevronRight, contentDescription = null, tint = colors.text2, modifier = Modifier.size(16.dp))
        } else {
        Text(
            asset.name,
            style = SnowFoldType.body.appType(),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DesignTokens.space3))
        AmountRowText(asset.amountText, color = if (asset.categoryIndex == 4) colors.neg else colors.text)
        Spacer(Modifier.width(DesignTokens.space2))
        Icon(AppIcon.chevronRight, contentDescription = null, tint = colors.text2, modifier = Modifier.size(16.dp))
        }
    } }

    SwipeRow(
            actions = SwipeActions(
                onDelete = onDelete,
                deleteTag = "asset-delete-${asset.id}",
            ),
            fullSwipeDeletes = true,
            modifier = modifier,
        ) { row() }
        SfMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = menuItems,
            dangerIndexes = setOf(2),
        )
}

/** 平板右栏：未选中 = 总资产概览；选中 = 资产详情（design.md §9.3）。 */
@Composable
private fun AssetPaneRight(
    ui: UiLedger,
    selectedId: String?,
    onAdjustAsset: (String) -> Unit,
    onRenameAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onCorrect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    val asset = ui.assets.firstOrNull { it.id == selectedId }
    Column(modifier.verticalScroll(rememberScrollState()).testTag("asset-detail-scroll")) {
        if (asset == null) {
            Spacer(Modifier.height(DesignTokens.space2))
            Text(stringResource(R.string.total_assets), style = SnowFoldType.bodyMedium.appType(), color = colors.text2)
            AmountText(
                ui.hero?.assetsText ?: stringResource(R.string.out_of_range),
                AmountLevel.Title,
                Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DesignTokens.space3))
            if (LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.space2)) {
                    SumStat(stringResource(R.string.total_debt), ui.hero?.debtText, negative = true)
                    SumStat(stringResource(R.string.net_assets), ui.hero?.netText, negative = ui.hero?.netNegative == true)
                    SumStat(stringResource(R.string.records_label), stringResource(R.string.item_count, ui.assetCount), negative = false)
                }
            } else {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.space8)) {
                    SumStat(stringResource(R.string.total_debt), ui.hero?.debtText, negative = true)
                    SumStat(stringResource(R.string.net_assets), ui.hero?.netText, negative = ui.hero?.netNegative == true)
                    SumStat(stringResource(R.string.records_label), stringResource(R.string.item_count, ui.assetCount), negative = false)
                }
            }
            Spacer(Modifier.height(DesignTokens.space4))
            SfCard {
                ui.categories.take(4).forEachIndexed { index, category ->
                    if (index > 0) RowDivider(startInset = DesignTokens.iconBlock + DesignTokens.pageMargin + DesignTokens.space3)
                    Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryIconBlock(category.index, DesignTokens.iconBlock)
                            Text(
                                categoryLabel(category.index),
                                style = SnowFoldType.bodyMedium.appType(),
                                color = colors.text,
                                modifier = Modifier.padding(start = DesignTokens.space3).weight(1f),
                            )
                            AmountRowText(category.amountText)
                        }
                        Spacer(Modifier.height(DesignTokens.space2))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
                                    .background(colors.bg),
                            ) {
                                if (category.barFraction > 0f) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(category.barFraction.coerceIn(0.005f, 1f))
                                            .height(4.dp)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
                                            .background(colors.cat[category.index]),
                                    )
                                }
                            }
                            Spacer(Modifier.width(DesignTokens.space3))
                            Text(category.sharePercentText, style = SnowFoldType.caption.appType(), color = colors.text2)
                        }
                    }
                }
            }
        } else {
            AssetDetailContent(
                ui = ui, asset = asset, showBack = false,
                onAdjust = { onAdjustAsset(asset.id) },
                onRename = { onRenameAsset(asset.id) },
                onDelete = { onDeleteAsset(asset.id) },
                onCorrect = onCorrect,
            )
        }
    }
}

/**
 * 资产详情（mobile-layout §3.3）：分类 → 名称 → 余额（页标题级）→ meta →
 * 按钮行 → 变动记录卡片（「修正」命中区 44）。手机推入页带返回按钮。
 */
@Composable
fun AssetDetailContent(
    ui: UiLedger,
    asset: AssetUi,
    onBack: (() -> Unit)? = null,
    onAdjust: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCorrect: ((String) -> Unit)? = null,
    showBack: Boolean = true,
) {
    val colors = appColors()
    Column(Modifier.fillMaxWidth().testTag("asset-detail")) {
        if (showBack && onBack != null) {
            Row(Modifier.fillMaxWidth()) {
                app.snowfold.ui.components.CircleIconButton(
                    icon = AppIcon.chevronLeft,
                    contentDescription = stringResource(R.string.cd_back),
                    onClick = onBack,
                    tint = colors.text,
                    testTag = "asset-back",
                )
            }
            Spacer(Modifier.height(DesignTokens.space4))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIconBlock(asset.categoryIndex, 24.dp)
            Text(
                categoryLabel(asset.categoryIndex),
                style = SnowFoldType.caption.appType(),
                color = colors.text2,
                modifier = Modifier.padding(start = DesignTokens.space2),
            )
        }
        Text(
            asset.name,
            style = SnowFoldType.headline.appType(),
            color = colors.text,
            modifier = Modifier.padding(top = DesignTokens.space2),
        )
        AmountText(
            asset.amountText,
            AmountLevel.Title,
            Modifier.fillMaxWidth().padding(top = DesignTokens.space2).testTag("asset-balance"),
            negative = asset.categoryIndex == 4,
        )
        Text(
            stringResource(
                R.string.asset_meta,
                asset.updatedOn?.let { dateShort(it) } ?: "—",
                asset.history.size,
            ),
            style = SnowFoldType.caption.appType(),
            color = colors.text2,
            modifier = Modifier.padding(top = DesignTokens.space2),
        )
        Spacer(Modifier.height(DesignTokens.space4))
        if (onAdjust != null) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.space2),
            ) {
                SfButton(
                    stringResource(R.string.adjust_balance),
                    onAdjust,
                    testTag = "asset-adjust",
                )
                Spacer(Modifier.width(DesignTokens.space2))
                SfButton(
                    stringResource(R.string.rename),
                    onRename ?: {},
                    style = ButtonStyle.Secondary,
                )
                if (LocalDensity.current.fontScale <= 1.3f) Spacer(Modifier.weight(1f))
                TextLink(
                    stringResource(R.string.delete),
                    onDelete ?: {},
                    danger = true,
                    icon = AppIcon.trash,
                    testTag = "asset-delete",
                )
            }
        }
        Spacer(Modifier.height(DesignTokens.space5))
        // 变动记录分区头 + 说明
        androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.history_section), style = SnowFoldType.headline.appType(), color = colors.text)
            Spacer(Modifier.width(DesignTokens.space3))
            Text(stringResource(R.string.history_note), style = SnowFoldType.caption.appType(), color = colors.text2)
        }
        Spacer(Modifier.height(DesignTokens.space2))
        SfCard {
            asset.history.forEachIndexed { index, entry ->
                if (index > 0) RowDivider(startInset = DesignTokens.pageMargin)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3),
                ) {
                    val largeFont = LocalDensity.current.fontScale > 1.3f
                    val metadata: @Composable () -> Unit = {
                        Text(
                            historyTimestamp(entry.recordedAt),
                            style = SnowFoldType.body.appType(), color = colors.text,
                            softWrap = largeFont,
                            modifier = Modifier.testTag("history-time-${entry.id}"),
                        )
                        androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entry.isCurrent) {
                                PillBadge(stringResource(R.string.history_current), colors.brandSoft, colors.brand)
                                Spacer(Modifier.width(DesignTokens.space2))
                            }
                            Text(entry.deltaText ?: stringResource(R.string.history_initial), style = SnowFoldType.caption.appType(), color = colors.text2)
                        }
                    }
                    val correction: @Composable () -> Unit = {
                        if (onCorrect != null) TextLink(
                            stringResource(R.string.correct_history), { onCorrect(entry.id) },
                            testTag = "history-correct-${entry.id}",
                        )
                    }
                    if (largeFont) {
                        metadata()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AmountRowText(entry.amountText, color = if (asset.categoryIndex == 4) colors.neg else colors.text)
                            correction()
                        }
                    } else {
                        // 时间先取自然宽度；金额在剩余空间内按统一金额规则缩放。
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(IntrinsicSize.Max)) { metadata() }
                            Spacer(Modifier.width(DesignTokens.space3))
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                    AmountRowText(entry.amountText, color = if (asset.categoryIndex == 4) colors.neg else colors.text)
                                }
                                correction()
                            }
                        }
                    }
                }
            }
        }
    }
}
