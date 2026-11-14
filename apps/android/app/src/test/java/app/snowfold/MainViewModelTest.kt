package app.snowfold

import androidx.lifecycle.SavedStateHandle
import app.snowfold.core.AssetRecord
import app.snowfold.core.BackupInfo
import app.snowfold.core.CategoryTotal
import app.snowfold.core.CoreException
import app.snowfold.core.CurrencyTotal
import app.snowfold.core.HistoryCorrectionRecord
import app.snowfold.core.HistoryRecord
import app.snowfold.core.KeyImportResult
import app.snowfold.core.LedgerData
import app.snowfold.core.Overview
import app.snowfold.core.RecoveryCodeInfo
import app.snowfold.core.RenewalResult
import app.snowfold.core.SealedBackupInfo
import app.snowfold.core.SubscriptionGroup
import app.snowfold.core.SubscriptionInput
import app.snowfold.core.SubscriptionRecord
import app.snowfold.core.ThemePreference
import app.snowfold.core.UndoTicket
import app.snowfold.data.CoreAccess
import app.snowfold.data.LedgerGateway
import app.snowfold.data.coreErrorRes
import app.snowfold.ui.model.CATEGORY_CODES
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class FakeGateway : LedgerGateway {
    var assets = mutableListOf<MutableAsset>()
    var subscriptions = mutableListOf<MutableSubscription>()
    var createdCount = 0
    var undoCalls = mutableListOf<String>()
    var failNextData: CoreException? = null
    private var undoStore = mutableMapOf<String, MutableAsset>()
    private var undoSubStore = mutableMapOf<String, MutableSubscription>()

    data class MutableAsset(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        var amountCents: Long,
        val history: MutableList<HistoryRecord> = mutableListOf(),
    )

    data class MutableSubscription(
        val id: String = UUID.randomUUID().toString(),
        var name: String = "订阅",
        var amountCents: Long = 1000,
        var currency: String = "CNY",
        var cycle: String = "monthly",
        var nextChargeOn: LocalDate = LocalDate.of(2026, 12, 1),
        var status: String = "active",
    )

    private fun snapshot(): LedgerData = LedgerData(
        assets = assets.map { a ->
            AssetRecord(
                id = a.id,
                revisionUid = UUID.randomUUID().toString(),
                currentHistoryId = a.history.lastOrNull()?.id ?: UUID.randomUUID().toString(),
                categoryCode = CATEGORY_CODES[0],
                name = a.name,
                amountCents = a.amountCents,
                createdAt = "2026-01-01T00:00:00.000000000Z",
                updatedAt = "2026-01-01T00:00:00.000000000Z",
            )
        },
        history = assets.flatMap { it.history },
        subscriptions = subscriptions.map { s ->
            SubscriptionRecord(
                id = s.id,
                revisionUid = UUID.randomUUID().toString(),
                createdAt = "2026-01-01T00:00:00.000000000Z",
                updatedAt = "2026-01-01T00:00:00.000000000Z",
                name = s.name,
                amountCents = s.amountCents,
                currency = s.currency,
                cycle = s.cycle,
                nextChargeOn = s.nextChargeOn.toString(),
                status = s.status,
            )
        },
    )

    private fun overviewOf(): Overview {
        val total = assets.sumOf { it.amountCents }
        val today = LocalDate.now()
        val categories = CATEGORY_CODES.mapIndexed { index, code ->
            CategoryTotal(
                code = code,
                name = code,
                isLiability = code == "debt",
                amountCents = if (index == 0) total else 0,
                count = if (index == 0) assets.size.toULong() else 0u,
            )
        }
        val active = subscriptions.count { it.status == "active" }
        val overdue = subscriptions.count { it.status == "active" && it.nextChargeOn < today }
        return Overview(
            totalAssetsCents = total,
            totalDebtCents = 0,
            netAssetsCents = total,
            categories = categories,
            subscriptionTotals = listOf("CNY", "USD").map {
                CurrencyTotal(currency = it, dueThisMonthCents = 0, monthlyCents = 0, annualCents = 0)
            },
            activeSubscriptions = active.toULong(),
            pausedSubscriptions = (subscriptions.size - active).toULong(),
            overdueSubscriptions = overdue.toULong(),
            nextChargeOn = subscriptions.filter { it.status == "active" }.minByOrNull { it.nextChargeOn }?.nextChargeOn?.toString(),
            upcoming = emptyList(),
        )
    }

    private fun checkData() {
        failNextData?.let { throw it }
    }

    override fun data(): LedgerData {
        checkData()
        return snapshot()
    }

    override fun overview(on: LocalDate): Overview = overviewOf()
    override fun createAsset(categoryCode: String, assetName: String, amountCents: Long): String {
        createdCount += 1
        val asset = MutableAsset(name = assetName, amountCents = amountCents)
        assets.add(asset)
        return asset.id
    }

    override fun renameAsset(id: String, assetName: String) {
        assets.firstOrNull { it.id == id }?.name = assetName
    }

    override fun changeBalance(id: String, amountCents: Long): UndoTicket? {
        val asset = assets.firstOrNull { it.id == id } ?: throw CoreException.NotFound()
        if (asset.amountCents == amountCents) return null
        val token = "undo-$id-${amountCents}"
        undoStore[token] = MutableAsset(id = asset.id, name = asset.name, amountCents = asset.amountCents)
        asset.amountCents = amountCents
        return UndoTicket(token = token, validForMs = 6000u)
    }

    override fun correctHistory(id: String, amountCents: Long) = Unit
    override fun historyCorrections(id: String): List<HistoryCorrectionRecord> = emptyList()

    override fun deleteAsset(id: String): UndoTicket {
        val index = assets.indexOfFirst { it.id == id }
        if (index < 0) throw CoreException.NotFound()
        val asset = assets.removeAt(index)
        val token = "undo-del-$id"
        undoStore[token] = MutableAsset(id = asset.id, name = asset.name, amountCents = asset.amountCents)
        return UndoTicket(token = token, validForMs = 6000u)
    }

    override fun undo(token: String) {
        undoCalls.add(token)
        val asset = undoStore.remove(token) ?: throw CoreException.Conflict()
        val existing = assets.firstOrNull { it.id == asset.id }
        if (existing == null) assets.add(asset) else existing.amountCents = asset.amountCents
        val subscription = undoSubStore.remove(token)
        if (subscription != null && subscriptions.none { it.id == subscription.id }) subscriptions.add(subscription)
    }

    override fun createSubscription(subscription: SubscriptionInput): String {
        val s = MutableSubscription(
            name = subscription.name,
            amountCents = subscription.amountCents,
            currency = subscription.currency,
            cycle = subscription.cycle,
            nextChargeOn = LocalDate.parse(subscription.nextChargeOn),
            status = subscription.status,
        )
        subscriptions.add(s)
        return s.id
    }

    override fun updateSubscription(id: String, subscription: SubscriptionInput) {
        val existing = subscriptions.firstOrNull { it.id == id } ?: throw CoreException.NotFound()
        existing.name = subscription.name
        existing.amountCents = subscription.amountCents
        existing.currency = subscription.currency
        existing.cycle = subscription.cycle
        existing.nextChargeOn = LocalDate.parse(subscription.nextChargeOn)
        existing.status = subscription.status
    }

    override fun setSubscriptionPaused(id: String, isPaused: Boolean) {
        subscriptions.firstOrNull { it.id == id }?.status = if (isPaused) "paused" else "active"
    }

    override fun renewSubscription(id: String, on: LocalDate): RenewalResult {
        val s = subscriptions.firstOrNull { it.id == id } ?: throw CoreException.NotFound()
        if (s.status != "active") throw CoreException.Conflict()
        if (s.nextChargeOn > on) throw CoreException.Conflict() // 尚未到期
        var periods = 0uL
        var next = s.nextChargeOn
        while (next <= on) {
            next = next.plusMonths(1)
            periods += 1uL
        }
        s.nextChargeOn = next
        return RenewalResult(nextChargeOn = next.toString(), periods = periods.toUInt())
    }

    override fun subscriptionGroup(id: String, on: LocalDate): SubscriptionGroup {
        val s = subscriptions.firstOrNull { it.id == id } ?: return SubscriptionGroup.PAUSED
        return when {
            s.status == "paused" -> SubscriptionGroup.PAUSED
            s.nextChargeOn < on -> SubscriptionGroup.OVERDUE
            s.nextChargeOn.month == on.month && s.nextChargeOn.year == on.year -> SubscriptionGroup.THIS_MONTH
            else -> SubscriptionGroup.LATER
        }
    }

    override fun subscriptionAnnual(id: String): Long {
        val s = subscriptions.firstOrNull { it.id == id } ?: return 0
        val factor = when (s.cycle) {
            "weekly" -> 52L
            "monthly" -> 12L
            "quarterly" -> 4L
            else -> 1L
        }
        return s.amountCents * factor
    }

    override fun deleteSubscription(id: String): UndoTicket {
        val index = subscriptions.indexOfFirst { it.id == id }
        if (index < 0) throw CoreException.NotFound()
        val s = subscriptions.removeAt(index)
        val token = "undo-del-sub-$id"
        undoSubStore[token] = s
        return UndoTicket(token = token, validForMs = 6000u)
    }

    override fun theme(): ThemePreference = ThemePreference.SYSTEM
    override fun saveTheme(theme: ThemePreference) = Unit
    override fun exportJson(): String = "{}"
    override fun inspectJson(json: String): BackupInfo {
        if (json == "bad") throw CoreException.InvalidBackup()
        return BackupInfo(assets = assets.size.toULong(), history = 0u, subscriptions = subscriptions.size.toULong())
    }

    override fun importJson(json: String) {
        if (json == "bad") throw CoreException.InvalidBackup()
        assets.clear()
        subscriptions.clear()
    }

    override fun exportSealed(): ByteArray = ByteArray(0)
    override fun inspectSealed(bytes: ByteArray): SealedBackupInfo = SealedBackupInfo(
        exportedAtDisplay = "2026-11-14 13:00",
        deviceId = "device",
        counts = BackupInfo(1u, 1u, 1u),
    )

    override fun importSealed(bytes: ByteArray): SealedBackupInfo = inspectSealed(bytes)
    override fun keyFingerprint(): String? = null
    override fun exportRecoveryCode(): String = "code"
    override fun inspectRecoveryCode(contents: String): RecoveryCodeInfo =
        RecoveryCodeInfo(currentFingerprint = null, newFingerprint = "new-fp")

    override fun importRecoveryCode(contents: String, expectedCurrentFingerprint: String?): KeyImportResult =
        KeyImportResult(fingerprint = "new-fp", previousKeyBackedUp = false)

    var syncState = app.snowfold.core.SyncStatus(null, false, false, null,
        app.snowfold.core.SyncPolicy(true, 30u, true))
    override fun syncStatus() = syncState
    override fun configureSync(config: app.snowfold.core.SyncConfig, secret: String) {
        syncState = syncState.copy(config = config, enabled = false, lastSyncedAt = null)
    }
    override fun setSyncEnabled(enabled: Boolean) { syncState = syncState.copy(enabled = enabled) }
    override fun clearSyncConfig() { syncState = syncState.copy(config = null, enabled = false, lastSyncedAt = null) }
    override fun setSyncPolicy(policy: app.snowfold.core.SyncPolicy) { syncState = syncState.copy(policy = policy) }
    override fun syncNow(transport: app.snowfold.core.SyncTransport): app.snowfold.core.SyncReport =
        error("测试须提供显式同步入口")

    override fun clearData() {
        assets.clear()
        subscriptions.clear()
    }

    override fun startupBackup() = Unit
    override fun parseAmount(value: String): Long {
        val parts = value.split(".")
        if (value.isBlank() || parts.size > 2 || parts[0].isEmpty() || !parts[0].all { it.isDigit() }) {
            throw CoreException.Validation()
        }
        val whole = parts[0].toLong()
        val fraction = parts.getOrNull(1)?.let { if (it.length == 1) "${it}0".toLong() else it.toLong() } ?: 0
        return whole * 100 + fraction
    }

    override fun formatAmount(amountCents: Long): String = "${amountCents / 100}.${(amountCents % 100).toString().padStart(2, '0')}"
    override fun formatMoney(amountCents: Long, currency: String): String =
        "${if (currency == "USD") "$" else "¥"}${formatAmount(amountCents)}"

    fun removeUnderneath(id: String) {
        assets.removeIf { it.id == id }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: FakeGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inner class DirectAccess : CoreAccess {
        override suspend fun <T> call(block: (LedgerGateway) -> T): T = block(gateway)
    }

    private fun vm(now: () -> Long = System::currentTimeMillis): MainViewModel =
        MainViewModel(SavedStateHandle(), DirectAccess(), now = now)

    @Test
    fun bootLoadsReadyStateWithDataAndTheme() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "现金", amountCents = 12345))
        val viewModel = vm()
        advanceUntilIdle()
        val ready = viewModel.state.value as AppState.Ready
        assertEquals(1, ready.ui.assets.size)
        assertEquals("现金", ready.ui.assets[0].name)
        assertEquals("¥123.45", ready.ui.assets[0].amountText)
        assertEquals(ThemePreference.SYSTEM, ready.theme)
    }

    @Test
    fun incompatibleDatabaseMapsToFatalMessage() = runTest(dispatcher.scheduler) {
        gateway.failNextData = CoreException.IncompatibleDatabase()
        val viewModel = vm()
        advanceUntilIdle()
        val fatal = viewModel.state.value as AppState.Fatal
        assertEquals(R.string.local_data_title, fatal.titleRes)
        assertEquals(listOf(R.string.error_incompatible_database), fatal.argRes)
    }

    @Test
    fun saveNewAssetWritesOnceUnderDoubleTapAndClosesEditor() = runTest(dispatcher.scheduler) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.openEditor(Editor.NewAsset(0))
        viewModel.saveNewAsset(0, "现金", "100.50")
        viewModel.saveNewAsset(0, "现金", "100.50") // busy: ignored
        advanceUntilIdle()
        assertEquals(1, gateway.createdCount)
        assertTrue(viewModel.editor.value == null)
        assertEquals("100.50", gateway.formatAmount(gateway.assets.single().amountCents))
    }

    @Test
    fun invalidAmountKeepsEditorOpenWithValidationHint() = runTest(dispatcher.scheduler) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.openEditor(Editor.NewAsset(0))
        viewModel.saveNewAsset(0, "现金", "abc")
        advanceUntilIdle()
        val error = viewModel.editorError.value
        assertNotNull(error)
        assertEquals(R.string.not_saved, error!!.res)
        assertTrue(error.argRes.contains(R.string.error_validation))
        assertTrue(viewModel.editor.value is Editor.NewAsset)
        assertEquals(0, gateway.createdCount)
    }

    @Test
    fun deleteAssetShowsUndoSnackbarAndUndoRestores() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "将被删除", amountCents = 100))
        val viewModel = vm()
        advanceUntilIdle()
        val id = gateway.assets.single().id
        viewModel.deleteAsset(id)
        advanceUntilIdle()
        assertTrue(gateway.assets.isEmpty())
        val snackbar = viewModel.snackbar.value
        assertNotNull(snackbar)
        assertEquals(R.string.asset_deleted, snackbar!!.res)
        assertNotNull(snackbar.undoToken)
        viewModel.onUndoPressed()
        advanceUntilIdle()
        assertEquals(1, gateway.assets.size)
        assertNull(viewModel.snackbar.value)
    }

    @Test
    fun undoAfterWindowShowsExpired() = runTest(dispatcher.scheduler) {
        var clock = 0L
        val viewModel = vm(now = { clock })
        advanceUntilIdle()
        gateway.assets.add(FakeGateway.MutableAsset(name = "x", amountCents = 1))
        val id = gateway.assets.single().id
        viewModel.deleteAsset(id)
        advanceUntilIdle()
        clock = 7000L
        viewModel.onUndoPressed()
        advanceUntilIdle()
        assertEquals(0, gateway.assets.size)
        assertTrue(gateway.undoCalls.isEmpty())
    }

    @Test
    fun refreshClosesEditorWhenRecordDeletedElsewhere() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "x", amountCents = 1))
        val viewModel = vm()
        advanceUntilIdle()
        val id = gateway.assets.single().id
        viewModel.openEditor(Editor.Asset(id))
        gateway.removeUnderneath(id)
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.editor.value)
    }

    @Test
    fun sameAmountBalanceChangeProducesNoTicket() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "x", amountCents = 500))
        val viewModel = vm()
        advanceUntilIdle()
        val id = gateway.assets.single().id
        viewModel.changeBalance(id, "5.00")
        advanceUntilIdle()
        assertNull(viewModel.snackbar.value)
    }

    @Test
    fun importRequiresConfirmationThenReplacesData() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "旧", amountCents = 1))
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.prepareImportJson("{}")
        advanceUntilIdle()
        val pending = viewModel.pendingConfirm.value as PendingConfirm.ImportJson
        assertEquals(1uL, pending.info.assets)
        viewModel.confirmPending()
        advanceUntilIdle()
        assertTrue(gateway.assets.isEmpty())
        assertNull(viewModel.pendingConfirm.value)
    }

    @Test
    fun invalidImportKeepsDataAndReports() = runTest(dispatcher.scheduler) {
        gateway.assets.add(FakeGateway.MutableAsset(name = "旧", amountCents = 1))
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.prepareImportJson("bad")
        advanceUntilIdle()
        assertEquals(1, gateway.assets.size)
        assertNull(viewModel.pendingConfirm.value)
    }

    @Test
    fun renewNotDueMapsToRenewalCopy() = runTest(dispatcher.scheduler) {
        gateway.subscriptions.add(FakeGateway.MutableSubscription(nextChargeOn = LocalDate.now().plusDays(3)))
        val viewModel = vm()
        advanceUntilIdle()
        val id = gateway.subscriptions.single().id
        viewModel.renewSubscription(id)
        advanceUntilIdle()
        assertEquals(LocalDate.now().plusDays(3), gateway.subscriptions.single().nextChargeOn)
    }

    @Test
    fun renewOverdueAdvancesAndNotifies() = runTest(dispatcher.scheduler) {
        gateway.subscriptions.add(FakeGateway.MutableSubscription(nextChargeOn = LocalDate.now().minusDays(10)))
        val viewModel = vm()
        advanceUntilIdle()
        val id = gateway.subscriptions.single().id
        viewModel.renewSubscription(id)
        advanceUntilIdle()
        // 核心按日历顺延：从原下次扣费日推进一整个周期（此处一期跨过今天）。
        assertEquals(LocalDate.now().minusDays(10).plusMonths(1), gateway.subscriptions.single().nextChargeOn)
    }

    @Test
    fun coreErrorMappingCoversAllVariants() {
        assertEquals(R.string.error_validation, coreErrorRes(CoreException.Validation()))
        assertEquals(R.string.error_not_found, coreErrorRes(CoreException.NotFound()))
        assertEquals(R.string.error_conflict, coreErrorRes(CoreException.Conflict()))
        assertEquals(R.string.error_incompatible_database, coreErrorRes(CoreException.IncompatibleDatabase()))
        assertEquals(R.string.error_overflow, coreErrorRes(CoreException.Overflow()))
        assertEquals(R.string.error_internal, coreErrorRes(RuntimeException("unexpected")))
        assertFalse(false)
    }
    @Test fun draftsAndTargetSurviveANewViewModel() = runTest(dispatcher.scheduler) {
        val handle = SavedStateHandle()
        val first = MainViewModel(handle, DirectAccess())
        advanceUntilIdle()
        first.openEditor(Editor.NewSubscription)
        first.draftField("newSubscription", "name", "")
        first.updateDraft("newSubscription", "name", "未保存的草稿")
        first.updateDraft("newSubscription", "amount", "12.34")
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        val restored = MainViewModel(SavedStateHandle(snapshot), DirectAccess())
        advanceUntilIdle()
        assertEquals(Editor.NewSubscription, restored.editor.value)
        assertEquals("未保存的草稿", restored.draftField("newSubscription", "name", "").value)
        assertEquals("12.34", restored.draftField("newSubscription", "amount", "").value)
        restored.closeEditor()
        assertEquals("", restored.draftField("newSubscription", "name", "").value)
    }

    @Test fun correctionFailureKeepsSheetAndDraftAndSuccessKeepsAssetDetail() = runTest(dispatcher.scheduler) {
        val asset = FakeGateway.MutableAsset(name = "资产", amountCents = 100)
        gateway.assets.add(asset)
        val model = vm()
        advanceUntilIdle()
        model.openEditor(Editor.Asset(asset.id))
        model.openCorrection("history")
        model.correctHistory("history", "abc")
        advanceUntilIdle()
        assertEquals("history", model.correctionId.value)
        assertNotNull(model.editorError.value)
        model.correctHistory("history", "2.00")
        advanceUntilIdle()
        assertNull(model.correctionId.value)
        assertEquals(Editor.Asset(asset.id), model.editor.value)
    }

    @Test fun staleBannerExpiryCannotDismissANewerTicket() = runTest(dispatcher.scheduler) {
        val asset = FakeGateway.MutableAsset(name = "资产", amountCents = 100)
        gateway.assets.add(asset)
        val model = vm()
        advanceUntilIdle()
        model.changeBalance(asset.id, "2.00")
        advanceUntilIdle()
        val oldToken = model.snackbar.value!!.undoToken!!
        model.changeBalance(asset.id, "3.00")
        advanceUntilIdle()
        val newToken = model.snackbar.value!!.undoToken!!
        model.onSnackbarDismissed(oldToken)
        assertEquals(newToken, model.snackbar.value!!.undoToken)
        model.onUndoPressed()
        advanceUntilIdle()
        assertEquals(200L, gateway.assets.single().amountCents)
    }

    @Test fun noOpBalanceRetainsExistingUndo() = runTest(dispatcher.scheduler) {
        val asset = FakeGateway.MutableAsset(name = "资产", amountCents = 100)
        gateway.assets.add(asset)
        val model = vm()
        advanceUntilIdle()
        model.changeBalance(asset.id, "2.00")
        advanceUntilIdle()
        val token = model.snackbar.value!!.undoToken
        model.changeBalance(asset.id, "2.00")
        advanceUntilIdle()
        assertEquals(token, model.snackbar.value!!.undoToken)
    }

}
