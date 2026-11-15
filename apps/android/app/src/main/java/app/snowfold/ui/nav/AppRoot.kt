package app.snowfold.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import app.snowfold.AppState
import app.snowfold.Editor
import app.snowfold.MainViewModel
import app.snowfold.PendingConfirm
import app.snowfold.R
import app.snowfold.ui.components.AppIcon
import app.snowfold.ui.components.CircleIconButton
import app.snowfold.ui.components.EmptyState
import app.snowfold.ui.components.GlassSource
import app.snowfold.ui.components.NoticeBanner
import app.snowfold.ui.components.SfConfirmDialog
import app.snowfold.ui.components.SfMenu
import app.snowfold.ui.components.SyncButton
import app.snowfold.ui.components.UndoBanner
import app.snowfold.ui.components.glassPanel
import app.snowfold.ui.components.glassSource
import app.snowfold.ui.components.floatingShadow
import app.snowfold.ui.components.uiMessageText
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import kotlinx.coroutines.delay

private const val ROUTE_OVERVIEW = "overview"
private const val ROUTE_ASSETS = "assets"
private const val ROUTE_SUBSCRIPTIONS = "subscriptions"
private const val ROUTE_SETTINGS = "settings"

private data class TabItem(val route: String, val line: ImageVector, val fill: ImageVector, val label: Int)

private val TABS = listOf(
    TabItem(ROUTE_OVERVIEW, AppIcon.overviewLine, AppIcon.overviewFill, R.string.overview_title),
    TabItem(ROUTE_ASSETS, AppIcon.assetsLine, AppIcon.assetsFill, R.string.assets_tab),
    TabItem(ROUTE_SUBSCRIPTIONS, AppIcon.subscriptionsLine, AppIcon.subscriptionsFill, R.string.subscriptions_title),
    TabItem(ROUTE_SETTINGS, AppIcon.settingsLine, AppIcon.settingsFill, R.string.settings_title),
)

/**
 * 应用骨架：手机 = 悬浮胶囊标签栏（浮层材质）+ 顶栏；平板（≥600dp）= 浮层侧边栏
 * + 工具栏（mobile-layout §1/§8）。撤销/通知横幅浮在最上层；确认框为系统警告框形态。
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val glass = app.snowfold.ui.components.rememberGlassSource()
    when (val current = state) {
        AppState.Loading -> Box(
            Modifier.fillMaxSize().background(appColors().bg),
            contentAlignment = Alignment.Center,
        ) {
            Text("SnowFold", style = SnowFoldType.headline.appType(), color = appColors().text2)
        }

        is AppState.Fatal -> FatalPage(current)
        is AppState.Ready -> ReadyShell(vm, current, glass)
    }
}

/** 启动失败（mobile-layout §9）：整页错误空状态，不显示标签栏。 */
@Composable
private fun FatalPage(state: AppState.Fatal) {
    val colors = appColors()
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
        val tablet = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (tablet) {
                TabletSidebar(app.snowfold.ui.components.rememberGlassSource(), ROUTE_OVERVIEW,
                    rememberNavController(), Modifier.width(DesignTokens.tabletSidebarWidth + 16.dp), enabled = false)
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(if (tablet) DesignTokens.tabletToolbarHeight else DesignTokens.navBarHeight),
                    contentAlignment = if (tablet) Alignment.CenterStart else Alignment.Center) {
                    Text(stringResource(R.string.overview_title), style = SnowFoldType.headline.appType(), color = colors.text,
                        modifier = Modifier.padding(horizontal = DesignTokens.space6))
                }
                app.snowfold.ui.components.EmptyPage(Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.negSoft),
                            contentAlignment = Alignment.Center) {
                            Icon(AppIcon.warningTriangle, contentDescription = null, tint = colors.neg, modifier = Modifier.size(48.dp))
                        }
                        Text(stringResource(R.string.startup_error_title), style = SnowFoldType.headline.appType(), color = colors.text,
                            modifier = Modifier.padding(top = DesignTokens.space6))
                        Text(uiMessageText(app.snowfold.UiMessage(R.string.local_data_unreadable, argRes = state.argRes)),
                            style = SnowFoldType.body.appType(), color = colors.text2, textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp).padding(top = DesignTokens.space2))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyShell(vm: MainViewModel, ready: AppState.Ready, glass: GlassSource) {
    val colors = appColors()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
        val tablet = maxWidth >= 600.dp
        var notice by remember { mutableStateOf<app.snowfold.UiMessage?>(null) }
        val haptics = app.snowfold.ui.components.rememberHaptics()
        LaunchedEffectCompat(vm) {
            vm.messages.collect {
                notice = it
                if (it.res == R.string.subscription_renewed) haptics.success()
            }
        }
        LaunchedEffectCompat(notice) { if (notice != null && !app.snowfold.isErrorMessage(notice!!)) { delay(4000); notice = null } }
        androidx.lifecycle.compose.LifecycleStartEffect(vm) {
            vm.onForeground()
            onStopOrDispose { vm.onBackground() }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (vm.state.value is AppState.Ready) vm.refresh()
        }

        // 内容层（记录给毛玻璃）
        Column(
            Modifier
                .fillMaxSize()
                .glassSource(glass)
                // 采集背景一并覆盖原文；透明采集会让未模糊的文字继续透出。
                .background(colors.bg),
        ) {
            if (tablet) {
                Row(Modifier.fillMaxSize().statusBarsPadding()) {
                    TabletSidebar(glass, route, nav, Modifier.width(DesignTokens.tabletSidebarWidth + 16.dp))
                    Column(Modifier.weight(1f).navigationBarsPadding()) {
                        TabletToolbar(vm, route, nav)
                        AppNavHost(
                            vm, nav, tablet = true,
                            modifier = Modifier.weight(1f),
                            route = route,
                            bottomPadding = DesignTokens.space8,
                        )
                    }
                }
            } else {
                PhoneNavBar(vm, route, nav)
                AppNavHost(vm, nav, tablet = false, modifier = Modifier.weight(1f), route = route, bottomPadding = 0.dp)
                // 底部留白由各页内容内边距处理
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        if (!tablet) {
            // 手机标签栏：悬浮胶囊（浮层材质）
            var barOffset by remember { mutableStateOf(IntOffset.Zero) }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = maxOf(if (maxHeight < 700.dp) 12.dp else 28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
                    .onGloballyPositioned { barOffset = IntOffset(it.positionInRoot().x.toInt(), it.positionInRoot().y.toInt()) },
            ) {
                PhoneTabBar(glass, barOffset, route, nav)
            }
        }

        // 撤销 / 通知横幅：标签栏上方（手机）或内容底部（平板）
        FeedbackOverlays(vm, notice, onDismiss = { notice = null }, tablet = tablet, glass = glass)

        // 确认框（不可逆操作）
        ConfirmDialogs(vm)

        // 编辑 sheet（手机底部 / 平板居中）
        EditorSheets(vm, tablet)
    }
}

@Composable
private fun LaunchedEffectCompat(key: Any?, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}

// ── 手机导航栏 ──────────────────────────────────────────────────────────

@Composable
private fun PhoneNavBar(vm: MainViewModel, route: String?, nav: NavHostControllerCompat) {
    val colors = appColors()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncEnabled = syncStatus?.enabled == true && syncStatus?.config != null
    val title = stringResource(
        when (route) {
            ROUTE_ASSETS -> R.string.assets_title
            ROUTE_SUBSCRIPTIONS -> R.string.subscriptions_title
            ROUTE_SETTINGS -> R.string.settings_title
            else -> R.string.overview_title
        },
    )
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(DesignTokens.navBarHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (route == ROUTE_ASSETS && editor is Editor.Asset) {
            CircleIconButton(AppIcon.chevronLeft, stringResource(R.string.cd_back), vm::closeEditor,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = DesignTokens.pageMargin),
                testTag = "asset-back")
        } else {
            Text(title, style = SnowFoldType.headline.appType(), color = colors.text)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
        when (route) {
            ROUTE_OVERVIEW -> if (syncEnabled) SyncButton(vm, Modifier.padding(end = DesignTokens.pageMargin))
            ROUTE_ASSETS -> {
                // 详情推入页左侧为返回按钮；列表页右侧「＋」
                if (editor is Editor.Asset) {
                    Spacer(Modifier.width(DesignTokens.circleButton + DesignTokens.pageMargin))
                } else {
                    CircleIconButton(
                        icon = AppIcon.plus,
                        contentDescription = stringResource(R.string.add_asset),
                        onClick = { vm.openEditor(Editor.NewAsset(0)) },
                        tint = colors.text,
                        testTag = "assets-add",
                        modifier = Modifier.padding(end = DesignTokens.pageMargin),
                    )
                }
            }

            ROUTE_SUBSCRIPTIONS -> CircleIconButton(
                icon = AppIcon.plus,
                contentDescription = stringResource(R.string.new_subscription_title),
                onClick = { vm.openEditor(Editor.NewSubscription) },
                tint = colors.text,
                testTag = "subscriptions-add",
                modifier = Modifier.padding(end = DesignTokens.pageMargin),
            )

            else -> Spacer(Modifier.width(DesignTokens.circleButton + DesignTokens.pageMargin))
        }
    }
        }
}

/** 手机标签栏：高 64、内边距 4、四项等宽；选中 56 高胶囊 brand-soft + 实心图标。 */
@Composable
private fun PhoneTabBar(glass: GlassSource, barOffset: IntOffset, route: String?, nav: NavHostControllerCompat) {
    val colors = appColors()
    val shape = RoundedCornerShape(DesignTokens.tabBarHeight / 2)
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.tabBarHeight)
            .glassPanel(glass, shape, barOffset, cornerRadius = DesignTokens.tabBarHeight / 2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TABS.forEach { item ->
            val selected = route == item.route
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (selected) colors.brandSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { nav.navigateToTab(item.route) }
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    }
                    .testTag("nav-${item.route}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (selected) item.fill else item.line,
                    contentDescription = null,
                    tint = if (selected) colors.brand else colors.text,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(item.label),
                    style = SnowFoldType.caption.appType(),
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (selected) colors.brand else colors.text,
                )
            }
        }
    }
}

// ── 平板侧边栏与工具栏 ──────────────────────────────────────────────────

@Composable
private fun TabletSidebar(
    glass: GlassSource,
    route: String?,
    nav: NavHostControllerCompat,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = appColors()
    var sidebarOffset by remember { mutableStateOf(IntOffset.Zero) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxHeight()
            .padding(8.dp)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
            .onGloballyPositioned { sidebarOffset = IntOffset(it.positionInRoot().x.toInt(), it.positionInRoot().y.toInt()) }
            // 侧栏背后是纯 bg；不把侧栏自身加入模糊采集，避免 RenderNode 循环引用。
            .floatingShadow(shape)
            .background(colors.glass, shape)
            .then(Modifier.border(0.5.dp, colors.glassEdge, shape))
            .clip(shape)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = SnowFoldType.headline.appType(),
            color = colors.text,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )
        listOf(
            TABS[0], TABS[1], TABS[2],
        ).forEach { item ->
            val itemRoute = item.route
            val icon = if (route == itemRoute) item.fill else item.line
            val label = item.label
            SidebarRow(
                label = stringResource(label),
                icon = icon,
                selected = route == itemRoute,
                enabled = enabled,
                onClick = { nav.navigateToTab(itemRoute) },
                modifier = Modifier.testTag("nav-$itemRoute"),
            )
        }
        Spacer(Modifier.weight(1f))
        SidebarRow(
            label = stringResource(R.string.settings_title),
            icon = if (route == ROUTE_SETTINGS) AppIcon.settingsFill else AppIcon.settingsLine,
            selected = route == ROUTE_SETTINGS,
            enabled = enabled,
            onClick = { nav.navigateToTab(ROUTE_SETTINGS) },
            modifier = Modifier.testTag("nav-settings"),
        )
    }
}

@Composable
private fun SidebarRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.brandSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.brand else colors.text, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = SnowFoldType.bodyMedium.appType(),
            color = if (selected) colors.brand else colors.text,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun TabletToolbar(vm: MainViewModel, route: String?, nav: NavHostControllerCompat) {
    val colors = appColors()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncEnabled = syncStatus?.enabled == true && syncStatus?.config != null
    val editor by vm.editor.collectAsStateWithLifecycle()
    val title = stringResource(
        when (route) {
            ROUTE_ASSETS -> R.string.assets_title
            ROUTE_SUBSCRIPTIONS -> R.string.subscriptions_title
            ROUTE_SETTINGS -> R.string.settings_title
            else -> R.string.overview_title
        },
    )
    Row(
        Modifier.fillMaxWidth().height(DesignTokens.tabletToolbarHeight).padding(horizontal = DesignTokens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SnowFoldType.headline.appType(), color = colors.text, modifier = Modifier.weight(1f))
        if (syncEnabled) SyncButton(vm, Modifier.padding(end = DesignTokens.space2))
        when (route) {
            ROUTE_OVERVIEW -> CircleIconButton(
                icon = AppIcon.plus,
                contentDescription = stringResource(R.string.add_asset),
                onClick = { vm.openEditor(Editor.NewAsset(0)) },
                tint = colors.text,
                testTag = "overview-toolbar-add",
            )
            ROUTE_ASSETS -> CircleIconButton(
                icon = AppIcon.plus,
                contentDescription = stringResource(R.string.add_asset),
                onClick = { vm.openEditor(Editor.NewAsset(0)) },
                tint = colors.text,
                testTag = "assets-add",
            )

            ROUTE_SUBSCRIPTIONS -> CircleIconButton(
                icon = AppIcon.plus,
                contentDescription = stringResource(R.string.new_subscription_title),
                onClick = { vm.openEditor(Editor.NewSubscription) },
                tint = colors.text,
                testTag = "subscriptions-add",
            )

            else -> {}
        }
    }
}

// ── 内容区 ─────────────────────────────────────────────────────────────

private typealias NavHostControllerCompat = androidx.navigation.NavHostController

@Composable
private fun AppNavHost(
    vm: MainViewModel,
    nav: androidx.navigation.NavHostController,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    route: String?,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    NavHost(nav, startDestination = ROUTE_OVERVIEW, modifier = modifier) {
        composable(ROUTE_OVERVIEW) {
            OverviewRoute(vm, tablet, nav)
        }
        composable(
            "$ROUTE_ASSETS?focus={focus}",
            arguments = listOf(navArgument("focus") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            AssetsRoute(vm, tablet, entry.arguments?.getInt("focus") ?: -1)
        }
        composable(
            "$ROUTE_SUBSCRIPTIONS?focus={focus}",
            arguments = listOf(navArgument("focus") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            SubscriptionsRoute(vm, tablet, entry.arguments?.getInt("focus") ?: -1)
        }
        composable(
            "$ROUTE_SETTINGS?importRecovery={importRecovery}",
            arguments = listOf(navArgument("importRecovery") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            SettingsRoute(vm, tablet, entry.arguments?.getBoolean("importRecovery") == true)
        }
    }
}

@Composable
private fun OverviewRoute(vm: MainViewModel, tablet: Boolean, nav: androidx.navigation.NavHostController) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? AppState.Ready ?: return
    val ui = ready.ui
    val bannerText = ready.banner?.let { uiMessageText(app.snowfold.UiMessage(it.titleRes, argRes = it.argRes)) }
    val overflowText = if (ui.overflow) stringResource(R.string.statistics_error, stringResource(R.string.error_overflow)) else null
    val isKeyBanner = ready.banner?.titleRes == R.string.startup_key_unavailable
    val pillMaxWidth = 720.dp
    app.snowfold.ui.overview.OverviewScreen(
        ui = ui,
        bannerText = if (isKeyBanner) null else bannerText,
        overflowText = overflowText,
        onGoAssets = { category -> nav.navigateToTab("$ROUTE_ASSETS?focus=$category") },
        onGoSubscriptions = { nav.navigateToTab(ROUTE_SUBSCRIPTIONS) },
        tablet = tablet,
        modifier = Modifier.fillMaxSize(),
        onAddAsset = { vm.openEditor(Editor.NewAsset(0)) },
        keyCard = if (isKeyBanner) {
            {
                app.snowfold.ui.overview.KeyUnavailableCard(
                    text = bannerText ?: "",
                    onImport = { nav.navigateToTab("$ROUTE_SETTINGS?importRecovery=true") },
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun AssetsRoute(vm: MainViewModel, tablet: Boolean, focusCategory: Int) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? AppState.Ready ?: return
    val ui = ready.ui
    val editor by vm.editor.collectAsStateWithLifecycle()
    AssetsBody(vm, ui, editor, tablet, focusCategory)
}

@Composable
private fun AssetsBody(
    vm: MainViewModel,
    ui: app.snowfold.ui.model.UiLedger,
    editor: Editor?,
    tablet: Boolean,
    focusCategory: Int,
) {
    // 手机：详情推入整页（含返回）；平板：两栏
    if (!tablet && editor is Editor.Asset) {
        BackHandler { vm.closeEditor() }
        val asset = ui.assets.firstOrNull { it.id == editor.id }
        if (asset != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("assets-list")
                    .padding(horizontal = DesignTokens.pageMargin)
                    .padding(top = DesignTokens.space2, bottom = DesignTokens.phoneContentBottom),
            ) {
                app.snowfold.ui.assets.AssetDetailContent(
                    ui = ui,
                    asset = asset,
                    showBack = false,
                    onBack = { vm.closeEditor() },
                    onAdjust = { vm.openEditor(Editor.Asset(asset.id, "balance")) },
                    onRename = { vm.openEditor(Editor.Asset(asset.id, "rename")) },
                    onDelete = { vm.deleteAsset(asset.id) },
                    onCorrect = { vm.openCorrection(it) },
                )
            }
            return
        }
    }
    app.snowfold.ui.assets.AssetsScreen(
        vm = vm,
        ui = ui,
        bannerText = null,
        onNewAsset = { vm.openEditor(Editor.NewAsset(it)) },
        onOpenAsset = { vm.openEditor(Editor.Asset(it)) },
        onDeleteAsset = vm::deleteAsset,
        onRenameAsset = { vm.openEditor(Editor.Asset(it, "rename")) },
        onAdjustAsset = { vm.openEditor(Editor.Asset(it, "balance")) },
        editorTarget = editor,
        tablet = tablet,
        selectedInPane = true,
        focusCategory = focusCategory,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SubscriptionsRoute(vm: MainViewModel, tablet: Boolean, focusGroup: Int) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? AppState.Ready ?: return
    val ui = ready.ui
    app.snowfold.ui.subscriptions.SubscriptionsScreen(
        vm = vm,
        ui = ui,
        tablet = tablet,
        onNewSubscription = { vm.openEditor(Editor.NewSubscription) },
        onOpenSubscription = { vm.openEditor(Editor.Subscription(it)) },
        onRenew = vm::renewSubscription,
        onPauseToggle = vm::setSubscriptionPaused,
        onDelete = vm::deleteSubscription,
        focusGroup = focusGroup,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SettingsRoute(vm: MainViewModel, tablet: Boolean, importRecovery: Boolean) {
    app.snowfold.ui.settings.SettingsScreen(vm = vm, tablet = tablet, modifier = Modifier.fillMaxSize(), importRecoveryOnOpen = importRecovery)
}

private fun androidx.navigation.NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = !route.contains("?")
    }
}

// ── 横幅与确认框 ───────────────────────────────────────────────────────

@Composable
private fun FeedbackOverlays(
    vm: MainViewModel,
    notice: app.snowfold.UiMessage?,
    onDismiss: () -> Unit,
    tablet: Boolean,
    glass: GlassSource,
) {
    val colors = appColors()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()
    val haptics = app.snowfold.ui.components.rememberHaptics()
    androidx.compose.runtime.LaunchedEffect(snackbar?.undoToken) {
        snackbar?.undoToken?.let { token ->
            if (snackbar?.res == R.string.asset_deleted || snackbar?.res == R.string.subscription_deleted) haptics.warning()
            delay(vm.undoRemainingMs(token))
            vm.onSnackbarDismissed(token)
        }
    }
    var feedbackOffset by remember { mutableStateOf(IntOffset.Zero) }
    var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val dismissThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    fun dismiss() {
        snackbar?.undoToken?.let(vm::onSnackbarDismissed)
        onDismiss()
    }
    val alignment = if (tablet) Alignment.BottomCenter else Alignment.BottomCenter
    val bottomPadding = if (tablet) DesignTokens.space8 else DesignTokens.bannerBottom
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.pageMargin)
                .padding(bottom = bottomPadding)
                .onGloballyPositioned { feedbackOffset = IntOffset(it.positionInRoot().x.toInt(), it.positionInRoot().y.toInt()) }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) },
                    onDragStopped = { velocity ->
                        if (drag > dismissThreshold || velocity > 1000f) dismiss()
                        drag = 0f
                    },
                ),
        ) {
            val ticket = snackbar
            if (ticket != null) {
                androidx.compose.runtime.key(ticket.undoToken) {
                    UndoBanner(
                        message = uiMessageText(app.snowfold.UiMessage(ticket.res, args = ticket.args)),
                        actionLabel = stringResource(R.string.undo),
                        onAction = { haptics.light(); vm.onUndoPressed() },
                        modifier = Modifier.glassPanel(glass, RoundedCornerShape(26.dp), feedbackOffset, 26.dp).testTag2("undo-banner"),
                    )
                }
            } else if (notice != null) {
                NoticeBanner(
                    message = uiMessageText(notice),
                    error = app.snowfold.isErrorMessage(notice),
                    onDismiss = {
                        onDismiss()
                    },
                    modifier = Modifier.glassPanel(glass, RoundedCornerShape(26.dp), feedbackOffset, 26.dp).testTag2("notice-banner"),
                )
            }
        }
    }
}

fun Modifier.testTag2(tag: String): Modifier = this.testTag(tag)

@Composable
private fun ConfirmDialogs(vm: MainViewModel) {
    val pending by vm.pendingConfirm.collectAsStateWithLifecycle()
    when (val target = pending) {
        is PendingConfirm.ImportJson -> SfConfirmDialog(
            title = stringResource(R.string.import_confirmation_title),
            body = stringResource(
                R.string.import_confirmation_body,
                target.info.assets.toString(),
                target.info.history.toString(),
                target.info.subscriptions.toString(),
            ),
            confirmLabel = stringResource(R.string.confirm_overwrite),
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        is PendingConfirm.ImportSealed -> {
            val info = target.info
            SfConfirmDialog(
                title = stringResource(R.string.import_confirmation_title),
                body = stringResource(
                    R.string.sealed_import_confirmation_body,
                    info.exportedAtDisplay,
                    info.deviceId,
                    info.counts.assets.toString(),
                    info.counts.history.toString(),
                    info.counts.subscriptions.toString(),
                ),
                confirmLabel = stringResource(R.string.confirm_overwrite),
                onConfirm = vm::confirmPending,
                onDismiss = vm::cancelPendingConfirm,
            )
        }

        is PendingConfirm.ReplaceKey -> SfConfirmDialog(
            title = stringResource(R.string.replace_key_confirmation_title),
            body = stringResource(
                R.string.replace_key_confirmation_body,
                target.info.currentFingerprint ?: stringResource(R.string.key_not_generated),
                target.info.newFingerprint,
            ),
            confirmLabel = stringResource(R.string.confirm_replace_key),
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        is PendingConfirm.ClearData -> SfConfirmDialog(
            title = stringResource(R.string.clear_confirmation_title),
            body = stringResource(R.string.clear_confirmation_body),
            confirmLabel = stringResource(R.string.clear_confirm),
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        null -> Unit
    }
}

// ── 编辑 sheet ────────────────────────────────────────────────────────

@Composable
private fun EditorSheets(vm: MainViewModel, tablet: Boolean) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    val target = editor ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val ui = (state as? AppState.Ready)?.ui ?: return
    val centered = tablet
    when (target) {
        is Editor.NewAsset -> app.snowfold.ui.editors.NewAssetSheet(
            vm, target, { id -> ui.assets.firstOrNull { it.id == id } }, centered,
        )

        is Editor.Asset -> if (target.action != "detail") {
            val asset = ui.assets.firstOrNull { it.id == target.id }
            if (asset != null) {
                when (target.action) {
                    "balance" -> app.snowfold.ui.editors.AdjustBalanceSheet(vm, asset, centered)
                    "rename" -> app.snowfold.ui.editors.RenameAssetSheet(vm, asset, centered)
                }
            }
        }
        // action == detail：手机整页推入；平板右栏

        is Editor.NewSubscription -> app.snowfold.ui.editors.SubscriptionEditorSheet(
            vm, target, { id -> ui.subscriptions.firstOrNull { it.id == id } }, centered,
        )

        is Editor.Subscription -> app.snowfold.ui.editors.SubscriptionEditorSheet(
            vm, target, { id -> ui.subscriptions.firstOrNull { it.id == id } }, centered,
        )
    }
    // 修正历史 sheet
    val correctionId by vm.correctionId.collectAsStateWithLifecycle()
    val correction = correctionId?.let { id -> ui.assets.firstOrNull { asset -> asset.history.any { it.id == id } } }
        ?.history?.firstOrNull { it.id == correctionId }
    if (correction != null) {
        app.snowfold.ui.editors.CorrectHistorySheet(vm, correction, centered)
    }
}
