package app.snowfold.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
fun AppRoot(vm: MainViewModel, expanded: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val colors = appColors()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { notice = it } }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
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
                if (expanded) NavigationRail(containerColor = colors.card, modifier = Modifier.fillMaxHeight()) {
                    (NAV_ITEMS + NavItem(ROUTE_SETTINGS, AppIcon.settings, R.string.settings_title)).forEach { item ->
                        val isSettings = item.route == ROUTE_SETTINGS
                        NavigationRailItem(
                            selected = if (isSettings) settingsOpen else !settingsOpen && route == item.route,
                            onClick = {
                                if (isSettings) settingsOpen = true else {
                                    settingsOpen = false
                                    nav.navigateToTab(item.route)
                                }
                            },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.label), style = SnowFoldType.badge) },
                            modifier = Modifier.testTag("nav-${item.route}"),
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = colors.accent, selectedTextColor = colors.accent,
                                unselectedIconColor = colors.ink2, unselectedTextColor = colors.ink2,
                                indicatorColor = colors.accent.copy(alpha = 0.14f)),
                        )
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = colors.canvas,
                    bottomBar = {
                        Column {
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
                            AppNavGraph(vm, expanded && !settingsOpen, nav,
                                onOpenSettings = { settingsOpen = true }, hideEditor = settingsOpen)
                        }
                        if (expanded && settingsOpen) Surface(
                            color = colors.card, modifier = Modifier.weight(1f).fillMaxHeight().testTag("settings-pane"),
                        ) {
                            app.snowfold.ui.settings.SettingsScreen(vm, onBack = { settingsOpen = false })
                        }
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
                    } else if (!settingsOpen) EditorSheets(vm, expanded)
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
            app.snowfold.ui.overview.OverviewScreen(ui, banner, overflow, onOpenSettings,
                onGoAssets = { vm.closeEditor(); nav.navigateToTab("$ROUTE_ASSETS?focus=$it") },
                onGoSubscriptions = { vm.closeEditor(); nav.navigateToTab("$ROUTE_SUBSCRIPTIONS?focus=$it") }, expanded = expanded)
        }
        composable("$ROUTE_ASSETS?focus={focus}", arguments = listOf(navArgument("focus") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            app.snowfold.ui.assets.AssetsScreen(vm, ui, banner, onOpenSettings,
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
                    expanded = expanded, focusGroup = entry.arguments?.getInt("focus") ?: -1,
                    modifier = Modifier.weight(1f))
                if (expanded) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp).testTag("subscription-pane")) {
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
private fun EditorSheets(vm: MainViewModel, expanded: Boolean) {
    if (expanded) return
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
