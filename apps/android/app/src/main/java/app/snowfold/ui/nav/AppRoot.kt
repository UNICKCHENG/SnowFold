package app.snowfold.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import app.snowfold.*
import app.snowfold.R
import app.snowfold.ui.components.*
import app.snowfold.ui.editors.*
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROUTE_OVERVIEW = "overview"
private const val ROUTE_ASSETS = "assets"
private const val ROUTE_SUBSCRIPTIONS = "subscriptions"
private const val ROUTE_SETTINGS = "settings"
private data class NavItem(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int)
private val NAV_ITEMS = listOf(
    NavItem(ROUTE_OVERVIEW, AppIcon.overview, R.string.overview_title),
    NavItem(ROUTE_ASSETS, AppIcon.assets, R.string.assets_tab),
    NavItem(ROUTE_SUBSCRIPTIONS, AppIcon.subscriptions, R.string.subscriptions_title),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppRoot(vm: MainViewModel, expanded: Boolean, wideTablet: Boolean = false) {
    // §8 新规则：内容区宽度 = 窗口宽度 − 侧边栏宽度（216dp），不在滚动列里测量。
    val windowWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val contentWidth = windowWidth - if (expanded) 216 else 0
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val colors = appColors()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { notice = it } }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
    androidx.lifecycle.compose.LifecycleStartEffect(vm) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (vm.state.value is AppState.Ready) vm.refresh()
    }
    when (val current = state) {
        AppState.Loading -> Box(Modifier.fillMaxSize().background(colors.canvas), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        is AppState.Fatal -> Box(Modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            GraphicEmptyState(AppIcon.hardDrive, stringResource(current.titleRes),
                uiMessageText(UiMessage(R.string.local_data_unreadable, argRes = current.argRes)),
                colors.negative.copy(alpha = 0.12f), colors.negative)
        }
        is AppState.Ready -> {
            BackHandler(settingsOpen) { settingsOpen = false }
            Row(Modifier.fillMaxSize()) {
                if (expanded) SnowSidebar(
                    route = route,
                    settingsOpen = settingsOpen,
                    onNavigate = { settingsOpen = false; nav.navigateToTab(it) },
                    onSettings = { settingsOpen = true },
                )
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = colors.canvas,
                    bottomBar = {
                        Column(if (expanded) Modifier.navigationBarsPadding() else Modifier) {
                            FeedbackBars(vm, notice, onDismiss = { notice = null })
                            if (!expanded) NavigationBar(containerColor = colors.card, tonalElevation = 0.dp) {
                                NAV_ITEMS.forEach { item ->
                                    NavigationBarItem(
                                        selected = route == item.route,
                                        onClick = { nav.navigateToTab(item.route) },
                                        icon = { Icon(item.icon, null, Modifier.size(22.dp)) },
                                        label = { Text(stringResource(item.label), style = SnowFoldType.badge) },
                                        modifier = Modifier.testTag("nav-${item.route}"),
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = colors.accent, selectedTextColor = colors.accent,
                                            unselectedIconColor = colors.ink2, unselectedTextColor = colors.ink2,
                                            indicatorColor = colors.accent.copy(alpha = 0.14f)),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            // §8 新规则：内容区 ≥680dp 用列表+详情双栏（列表 ≥320、详情 ≥360）；<680 推入整页。
                            val paneLayout = expanded && contentWidth >= 680
                            if (expanded && settingsOpen) {
                                Box(Modifier.fillMaxSize().testTag("settings-pane")) {
                                    app.snowfold.ui.settings.SettingsScreen(vm, onBack = { settingsOpen = false }, showClose = false,
                                        modifier = Modifier.fillMaxSize())
                                }
                            } else {
                                AppNavGraph(vm, expanded, paneLayout, contentWidth, nav,
                                    onOpenSettings = { settingsOpen = true }, hideEditor = settingsOpen)
                            }
                            if (settingsOpen && !expanded) {
                                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                ModalBottomSheet(
                                    onDismissRequest = { settingsOpen = false }, sheetState = sheet, containerColor = colors.card,
                                    modifier = Modifier.testTag("settings-sheet"),
                                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                                ) {
                                    app.snowfold.ui.settings.SettingsScreen(vm, onBack = { settingsOpen = false },
                                        modifier = Modifier.fillMaxHeight())
                                }
                            } else if (!settingsOpen) EditorSheets(vm, paneLayout)
                        }
                    }
                    ConfirmDialogs(vm)
                }
            }
        }
    }
}

@Composable
private fun FeedbackBars(vm: MainViewModel, notice: UiMessage?, onDismiss: () -> Unit) {
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()
    val ticket = snackbar
    if (ticket != null) key(ticket.undoToken) {
        UndoBanner(
            uiMessageText(UiMessage(ticket.res, args = ticket.args)), stringResource(R.string.undo), ticket.validForMs,
            onAction = vm::onUndoPressed,
            onExpired = { ticket.undoToken?.let(vm::onSnackbarDismissed) },
            modifier = Modifier.padding(horizontal = 12.dp).testTag("undo-banner"),
        )
    }
    if (notice != null && ticket == null) NoticeBar(
        uiMessageText(notice), if (isErrorMessage(notice)) appColors().negative else appColors().accent,
        onDismiss, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("notice-banner"),
    )
}

@Composable
private fun AppNavGraph(
    vm: MainViewModel,
    expanded: Boolean,
    paneLayout: Boolean,
    contentWidth: Int,
    nav: androidx.navigation.NavHostController,
    onOpenSettings: () -> Unit,
    hideEditor: Boolean,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? AppState.Ready ?: return
    val ui = ready.ui
    val banner = ready.banner?.let { uiMessageText(UiMessage(it.titleRes, argRes = it.argRes)) }
    val overflow = if (ui.overflow) stringResource(R.string.statistics_error, stringResource(R.string.error_overflow)) else null
    val editor by vm.editor.collectAsStateWithLifecycle()
    NavHost(nav, startDestination = ROUTE_OVERVIEW) {
        composable(ROUTE_OVERVIEW) {
            app.snowfold.ui.overview.OverviewScreen(ui, banner, overflow, onOpenSettings, wideTablet = expanded, contentWidth = contentWidth,
                onGoAssets = { vm.closeEditor(); nav.navigateToTab("$ROUTE_ASSETS?focus=$it") },
                onGoSubscriptions = { vm.closeEditor(); nav.navigateToTab("$ROUTE_SUBSCRIPTIONS?focus=$it") }, expanded = expanded,
                syncAction = { SyncButton(vm) })
        }
        composable("$ROUTE_ASSETS?focus={focus}", arguments = listOf(navArgument("focus") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            app.snowfold.ui.assets.AssetsScreen(vm, ui, banner, onOpenSettings, paneLayout = paneLayout,
                onNewAsset = { vm.openEditor(Editor.NewAsset(it)) },
                onOpenAsset = { vm.openEditor(Editor.Asset(it)) },
                onDeleteAsset = vm::deleteAsset,
                onRenameAsset = { vm.openEditor(Editor.Asset(it, "rename")) },
                editorTarget = if (hideEditor) null else editor, expanded = expanded,
                focusCategory = entry.arguments?.getInt("focus") ?: -1)
        }
        composable("$ROUTE_SUBSCRIPTIONS?focus={focus}", arguments = listOf(navArgument("focus") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            Row(Modifier.fillMaxSize()) {
                app.snowfold.ui.subscriptions.SubscriptionsScreen(ui, banner, overflow, onOpenSettings,
                    onNewSubscription = { vm.openEditor(Editor.NewSubscription) },
                    onOpenSubscription = { vm.openEditor(Editor.Subscription(it)) },
                    onRenew = vm::renewSubscription, onPauseToggle = vm::setSubscriptionPaused, onDelete = vm::deleteSubscription,
                    selectedSubscriptionId = (editor as? Editor.Subscription)?.id,
                    expanded = expanded, paneLayout = paneLayout, focusGroup = entry.arguments?.getInt("focus") ?: -1,
                    modifier = Modifier.weight(1f), syncAction = { if (expanded) SyncButton(vm) })
                if (paneLayout) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp).testTag("subscription-pane")) {
                    val target = editor
                    if (target is Editor.Subscription || target is Editor.NewSubscription) {
                        key(target) { SubscriptionEditorContent(vm, target, { id -> ui.subscriptions.firstOrNull { it.id == id } }) }
                    } else GraphicEmptyState(AppIcon.subscriptions, stringResource(R.string.subscription_detail_title),
                        stringResource(R.string.subs_pane_hint), appColors().tile, appColors().accent)
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        // Explicit category/group links must honor the new argument, not restore an old tab argument.
        restoreState = !route.contains("?focus=")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheets(vm: MainViewModel, paneLayout: Boolean) {
    if (paneLayout) return
    val editor by vm.editor.collectAsStateWithLifecycle()
    val target = editor ?: return
    if (target is Editor.Asset) return
    val ready by vm.state.collectAsStateWithLifecycle()
    val ui = (ready as? AppState.Ready)?.ui ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    var full by rememberEditorField(vm, target.key, "fullSheet", false)
    LaunchedEffect(target.key) { if (full) sheet.expand() }
    ModalBottomSheet(
        onDismissRequest = vm::closeEditor, sheetState = sheet, containerColor = appColors().card,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = Modifier.testTag("editor-sheet"),
    ) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { scope.launch {
                    if (sheet.currentValue == SheetValue.PartiallyExpanded) { full = true; sheet.expand() }
                    else { full = false; sheet.partialExpand() }
                } }, modifier = Modifier.testTag("sheet-toggle")) {
                    Text(stringResource(if (sheet.currentValue == SheetValue.PartiallyExpanded) R.string.sheet_expand else R.string.sheet_collapse))
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                key(target) {
                    when (target) {
                        is Editor.NewAsset -> AssetEditorContent(vm, target, { id -> ui.assets.firstOrNull { it.id == id } })
                        is Editor.NewSubscription, is Editor.Subscription -> SubscriptionEditorContent(vm, target, { id -> ui.subscriptions.firstOrNull { it.id == id } })
                        else -> Unit
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConfirmDialogs(vm: MainViewModel) {
    val pending by vm.pendingConfirm.collectAsStateWithLifecycle()
    when (val target = pending) {
        is PendingConfirm.ImportJson -> ConfirmDialog(
            title = stringResource(R.string.import_confirmation_title),
            body = stringResource(
                R.string.import_confirmation_body,
                target.info.assets.toString(),
                target.info.history.toString(),
                target.info.subscriptions.toString(),
            ),
            confirmLabel = stringResource(R.string.confirm_overwrite),
            danger = true,
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        is PendingConfirm.ImportSealed -> {
            val info = target.info
            ConfirmDialog(
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
                danger = true,
                onConfirm = vm::confirmPending,
                onDismiss = vm::cancelPendingConfirm,
            )
        }

        is PendingConfirm.ReplaceKey -> ConfirmDialog(
            title = stringResource(R.string.replace_key_confirmation_title),
            body = stringResource(
                R.string.replace_key_confirmation_body,
                target.info.currentFingerprint ?: stringResource(R.string.key_not_generated),
                target.info.newFingerprint,
            ),
            confirmLabel = stringResource(R.string.confirm_replace_key),
            danger = true,
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        is PendingConfirm.ClearData -> ConfirmDialog(
            title = stringResource(R.string.clear_confirmation_title),
            body = stringResource(R.string.clear_confirmation_body),
            confirmLabel = stringResource(R.string.clear),
            danger = true,
            onConfirm = vm::confirmPending,
            onDismiss = vm::cancelPendingConfirm,
        )

        PendingConfirm.ClearData -> Unit
        null -> Unit
    }
}


/** 侧边栏（docs/mobile-layout.md §8，对齐 iPad）：顶部应用名 + 三项主导航；「设置」固定左下角，上方只留白、无分隔线。 */
@Composable
private fun SnowSidebar(
    route: String?,
    settingsOpen: Boolean,
    onNavigate: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val colors = appColors()
    Column(
        Modifier
            .width(216.dp)
            .fillMaxHeight()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = SnowFoldType.statValue,
            color = colors.ink,
            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 12.dp),
        )
        val sidebarLabels = mapOf(
            ROUTE_OVERVIEW to R.string.overview_title,
            ROUTE_ASSETS to R.string.assets_title,
            ROUTE_SUBSCRIPTIONS to R.string.subscriptions_title,
        )
        NAV_ITEMS.forEach { item ->
            SidebarRow(
                label = stringResource(sidebarLabels.getValue(item.route)),
                icon = item.icon,
                selected = !settingsOpen && route == item.route,
                onClick = { onNavigate(item.route) },
                modifier = Modifier.testTag("nav-${item.route}"),
            )
        }
        Spacer(Modifier.weight(1f))
        SidebarRow(
            label = stringResource(R.string.settings_title),
            icon = AppIcon.settings,
            selected = settingsOpen,
            onClick = onSettings,
            modifier = Modifier.testTag("nav-settings").padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun SidebarRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, null, tint = if (selected) colors.canvas else colors.accent, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = SnowFoldType.body,
            color = if (selected) colors.canvas else colors.ink,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
