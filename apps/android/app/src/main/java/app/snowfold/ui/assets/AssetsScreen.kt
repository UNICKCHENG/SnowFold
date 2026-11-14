package app.snowfold.ui.assets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.R
import app.snowfold.ui.components.AppCard
import app.snowfold.ui.components.PageScaffold
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.AppScreen
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.components.ErrorBanner
import app.snowfold.ui.components.Haptics
import app.snowfold.ui.components.appColors
import app.snowfold.ui.components.appIcon
import app.snowfold.ui.components.categoryTint
import app.snowfold.ui.editors.AssetEditorContent
import app.snowfold.ui.model.AssetUi
import app.snowfold.ui.model.CategoryUi
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType

/** 资产与负债页（docs/design.md §6）：合计卡 + 五个分区；行 = 分类 chip + 名称 + 历史 + 金额。 */
@Composable
fun AssetsScreen(
    vm: MainViewModel,
    ui: UiLedger,
    bannerText: String?,
    onOpenSettings: () -> Unit,
    onNewAsset: (Int) -> Unit,
    onOpenAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onRenameAsset: (String) -> Unit,
    editorTarget: Editor?,
    expanded: Boolean,
    focusCategory: Int = -1,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val selectedAssetId = (editorTarget as? Editor.Asset)?.id
    if (!expanded && editorTarget is Editor.Asset) {
        androidx.activity.compose.BackHandler { vm.closeEditor() }
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            app.snowfold.ui.editors.SheetNavBar(stringResource(R.string.assets_title), vm::closeEditor)
            AssetEditorContent(vm, editorTarget, { id -> ui.assets.firstOrNull { it.id == id } })
        }
    } else {
        PageScaffold(
            title = stringResource(R.string.assets_title),
            modifier = modifier,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcon.settings, stringResource(R.string.cd_settings), tint = tokens.ink2)
                }
                IconButton(onClick = { onNewAsset(0) }) {
                    Icon(AppIcon.plus, stringResource(R.string.add), tint = tokens.accent)
                }
            },
        ) { pad ->
            if (expanded) {
                Row(pad.fillMaxWidth()) {
            Box(Modifier.weight(0.45f)) {
                AssetsList(
                    ui = ui,
                    bannerText = bannerText,
                    onNewAsset = onNewAsset,
                    onOpenAsset = onOpenAsset,
                    onDeleteAsset = onDeleteAsset,
                    onRenameAsset = onRenameAsset,
                    onAdjustAsset = { vm.openEditor(Editor.Asset(it, "balance")) },
                    selectedAssetId = selectedAssetId,
                    focusCategory = focusCategory,
                )
            }
            Box(Modifier.weight(0.55f)) {
                val paneTarget = if (editorTarget is Editor.NewAsset || editorTarget is Editor.Asset) editorTarget else null
                if (paneTarget != null) {
                    AppScreen {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            AssetEditorContent(
                                vm = vm,
                                target = paneTarget,
                                findAsset = { id -> ui.assets.firstOrNull { it.id == id } },
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.assets_pane_hint),
                            style = SnowFoldType.body,
                            color = tokens.ink3,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
            } else {
                AssetsList(
                    ui = ui,
                    bannerText = bannerText,
                    onNewAsset = onNewAsset,
                    onOpenAsset = onOpenAsset,
                    onDeleteAsset = onDeleteAsset,
                    onRenameAsset = onRenameAsset,
                    onAdjustAsset = { vm.openEditor(Editor.Asset(it, "balance")) },
                    selectedAssetId = null,
                    focusCategory = focusCategory,
                    modifier = pad,
                )
            }
        }
    }
}

@Composable
private fun AssetsList(
    ui: UiLedger,
    bannerText: String?,
    focusCategory: Int = -1,
    onNewAsset: (Int) -> Unit,
    onOpenAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onRenameAsset: (String) -> Unit,
    selectedAssetId: String?,
    onAdjustAsset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val listState = rememberLazyListState()
    val focusMap = remember(ui.categories, ui.assets, bannerText) {
        var cursor = if (bannerText != null) 1 else 0
        cursor += 1 // sums card
        val map = mutableMapOf<Int, Int>()
        ui.categories.forEach { category ->
            map[category.index] = cursor
            val count = ui.assets.count { it.categoryIndex == category.index }
            cursor += 1 + (if (count == 0) 1 else 0) + count
        }
        map
    }
    androidx.compose.runtime.LaunchedEffect(focusCategory) {
        if (focusCategory >= 0) listState.animateScrollToItem(focusMap[focusCategory] ?: 0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("assets-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = DesignTokens.pageMargin,
            end = DesignTokens.pageMargin,
            top = DesignTokens.space3,
            bottom = DesignTokens.space4 + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space2),
    ) {
        if (bannerText != null) item(key = "banner") { ErrorBanner(text = bannerText) }
        item(key = "sums") {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.total_assets), style = SnowFoldType.helper, color = tokens.ink2)
                AmountText(ui.hero?.assetsText ?: stringResource(R.string.out_of_range), SnowFoldType.cardNumber,
                    Modifier.fillMaxWidth(), decimalHierarchy = true)
            }
        }
        ui.categories.forEach { category ->
            val categoryAssets = ui.assets.filter { it.categoryIndex == category.index }
            item(key = "cat-${category.index}") {
                CategoryHeader(
                    category = category,
                    onAdd = { onNewAsset(category.index) },
                    modifier = Modifier.padding(top = DesignTokens.space3).testTag("asset-group-${category.index}"),
                )
            }
            if (categoryAssets.isEmpty()) {
                item(key = "cat-empty-${category.index}") {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .drawBehind {
                                drawRoundRect(tokens.hairline, cornerRadius = CornerRadius(12.dp.toPx()),
                                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                            }
                            .clickable { onNewAsset(category.index) }.padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.add_category, stringResource(category.labelRes)),
                            style = SnowFoldType.body, color = tokens.ink2)
                    }
                }
            }
            itemsIndexed(categoryAssets, key = { _, asset -> asset.id }) { _, asset ->
                AssetRow(
                    asset = asset,
                    selected = asset.id == selectedAssetId,
                    onOpen = { onOpenAsset(asset.id) },
                    onAdjust = { onAdjustAsset(asset.id) },
                    onDelete = { onDeleteAsset(asset.id) },
                    onRename = { onRenameAsset(asset.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** 分区头（§6）：chip + 名称 + N 项 | 右侧合计 + 「+ 添加」。 */
@Composable
private fun CategoryHeader(
    category: CategoryUi,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val tint = categoryTint(category.index, tokens)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(tint.second, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(AppIcon.category(category.index), null, tint = tint.first, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(stringResource(category.labelRes), style = SnowFoldType.sectionHeader, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.item_count, category.count), style = SnowFoldType.helper, color = tokens.ink3)
        }
        AmountText(category.amountText, SnowFoldType.rowValue, Modifier.weight(1f),
            color = if (category.isLiability) tokens.negative else tokens.ink)
    }
}

/** 资产行：右滑删除（撤销条），左滑重命名，点行 / 长按进详情。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.AssetRow(
    asset: AssetUi,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = appColors()
    val view = LocalView.current
    // 手势回调在越过阈值与落定时会各触发一次，只处理第一次。
    var swipeHandled by remember { mutableStateOf(false) }
    LaunchedEffect(swipeHandled) { if (swipeHandled) { delay(500); swipeHandled = false } }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (swipeHandled) return@rememberSwipeToDismissBoxState false
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    swipeHandled = true
                    Haptics.light(view)
                    onAdjust()
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    swipeHandled = true
                    Haptics.warning(view)
                    onDelete()
                }

                else -> return@rememberSwipeToDismissBoxState false
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        modifier = modifier,
        backgroundContent = {
            val endToStart = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            val alignment = if (endToStart) Alignment.CenterEnd else Alignment.CenterStart
            val tint = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) androidx.compose.ui.graphics.Color.Transparent else if (endToStart) tokens.negative else tokens.accent
            val label = stringResource(if (endToStart) R.string.cd_delete_asset else R.string.adjust_balance)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp).clearAndSetSemantics {},
                contentAlignment = alignment,
            ) {
                Text(label, style = SnowFoldType.body, color = tint, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        BoxWithConstraints {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f || asset.name.length > 16
        var menuOpen by remember(asset.id) { mutableStateOf(false) }
        val tint = categoryTint(asset.categoryIndex, tokens)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    if (selected) tokens.accent.copy(alpha = 0.10f) else tokens.card,
                    RoundedCornerShape(DesignTokens.tileCorner),
                )
                .testTag("asset-${asset.id}")
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.adjust_balance)) }, onClick = { menuOpen = false; onAdjust() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete)) }, onClick = { menuOpen = false; onDelete() })
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(tint.second, RoundedCornerShape(DesignTokens.chipCorner)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    appIcon(AppIcon.category(asset.categoryIndex)),
                    contentDescription = null,
                    tint = tint.first,
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = asset.name,
                    style = SnowFoldType.rowTitle,
                    color = tokens.ink,
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.history_count_inline, asset.history.size),
                    style = SnowFoldType.helper,
                    color = tokens.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (stacked) AmountText(asset.amountText, SnowFoldType.rowValue, Modifier.fillMaxWidth())
            }
            if (!stacked) AmountText(
                value = asset.amountText,
                style = SnowFoldType.rowValue,
                color = if (asset.categoryIndex == 4) tokens.negative else tokens.ink,
                modifier = Modifier.weight(0.9f).padding(start = 12.dp),
            )
        }
    }
    }
}

@Composable
private fun SumLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = appColors().ink) {
    val tokens = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = SnowFoldType.body, color = tokens.ink2)
        AmountText(value = value, style = SnowFoldType.rowValue, color = valueColor)
    }
}
