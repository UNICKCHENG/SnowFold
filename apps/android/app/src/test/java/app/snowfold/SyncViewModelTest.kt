package app.snowfold

import androidx.lifecycle.SavedStateHandle
import app.snowfold.core.*
import app.snowfold.data.CoreAccess
import app.snowfold.data.LedgerGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: FakeGateway
    private var calls = 0
    private var nextResult: CompletableDeferred<SyncReport>? = null
    private var failure: CoreException? = null
    private val baseTime = 1_800_000_000_000L
    private val transport = object : SyncTransport {
        override fun send(request: SyncRequest): SyncResponse = error("未开启时不得发送请求")
    }
    private val config = SyncConfig(SyncBackend.S3, "https://storage.example.com", "ledger", "us-east-1", "", "example", true)
    private val access = object : CoreAccess {
        override suspend fun <T> call(block: (LedgerGateway) -> T): T = block(gateway)
        override suspend fun sync(transport: SyncTransport): SyncReport {
            calls++
            failure?.let { throw it }
            val result = nextResult?.await() ?: report()
            gateway.syncState = gateway.syncState.copy(lastSyncedAt = result.lastSyncedAt)
            return result
        }
    }
    private fun report(pending: Boolean = false) = SyncReport(1u, 2u, 1u,
        java.time.Instant.ofEpochMilli(baseTime + dispatcher.scheduler.currentTime).toString(), 0u, pending)
    private fun vm() = MainViewModel(SavedStateHandle(), access,
        now = { baseTime + dispatcher.scheduler.currentTime }, syncTransport = transport)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway()
        gateway.syncState = SyncStatus(config, true, true, null, SyncPolicy(true, 15u, true))
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun disabledSyncNeverCallsTransportFromAnyTrigger() = runTest(dispatcher.scheduler) {
        gateway.syncState = gateway.syncState.copy(enabled = false)
        val vm = vm(); runCurrent()
        vm.onForeground(); vm.syncNow(); vm.saveNewAsset(0, "离线记录", "1.00"); runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(0, calls)
        assertEquals(1, gateway.createdCount)
        vm.onBackground()
    }

    @Test fun manualTimerAndForegroundAreSingleFlightWhileLocalEditRemainsAvailable() = runTest(dispatcher.scheduler) {
        gateway.syncState = gateway.syncState.copy(policy = SyncPolicy(false, 15u, true))
        val vm = vm(); runCurrent(); vm.onForeground(); runCurrent()
        val result = CompletableDeferred<SyncReport>(); nextResult = result
        vm.syncNow(); runCurrent(); vm.syncNow(); vm.onForeground()
        vm.saveNewAsset(0, "同步时写入", "12.00"); runCurrent()
        assertEquals(1, calls)
        assertEquals(1, gateway.createdCount)
        assertFalse(vm.busy.value)
        advanceTimeBy(15 * 60_000L); runCurrent()
        assertEquals(1, calls)
        result.complete(report(pending = true)); runCurrent()
        nextResult = null
        assertFalse(vm.syncing.value)
        assertEquals("同步时写入", (vm.state.value as AppState.Ready).ui.assets.single().name)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, calls)
        vm.onBackground()
    }

    @Test fun foregroundTriggersAndBackgroundStopsIntervalAndDebounce() = runTest(dispatcher.scheduler) {
        val vm = vm(); runCurrent(); vm.onForeground(); runCurrent()
        assertEquals(1, calls)
        vm.saveNewAsset(0, "后台前修改", "1.00"); runCurrent()
        vm.onBackground(); advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(1, calls)
        vm.onForeground(); runCurrent()
        assertEquals(2, calls)
        vm.onBackground()
    }

    @Test fun editsDebounceThirtySecondsAndIntervalUsesLastSync() = runTest(dispatcher.scheduler) {
        gateway.syncState = gateway.syncState.copy(policy = SyncPolicy(false, 15u, true))
        val vm = vm(); runCurrent(); vm.onForeground(); runCurrent()
        vm.saveNewAsset(0, "第一笔", "1.00"); runCurrent()
        advanceTimeBy(20_000)
        vm.saveNewAsset(0, "第二笔", "1.00"); runCurrent()
        advanceTimeBy(29_999); runCurrent(); assertEquals(0, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(15 * 60_000L); runCurrent(); assertEquals(2, calls)
        vm.onBackground()
    }

    @Test fun successfulSyncClearsUndoAndFailureRetainsLocalDataAndUndo() = runTest(dispatcher.scheduler) {
        val id = gateway.createAsset("cash", "本机", 100)
        val vm = vm(); runCurrent()
        vm.changeBalance(id, "2.00"); runCurrent(); assertNotNull(vm.snackbar.value)
        failure = CoreException.SyncAuthentication()
        vm.syncNow(); runCurrent()
        assertNotNull(vm.snackbar.value)
        assertEquals(R.string.error_sync_authentication, vm.syncError.value)
        assertEquals(200, gateway.assets.single().amountCents)
        failure = null
        vm.syncNow(); runCurrent()
        assertNull(vm.snackbar.value)
        assertNull(vm.syncError.value)
    }

    @Test fun coreConflictIsSilentAndReleasesSingleFlight() = runTest(dispatcher.scheduler) {
        val vm = vm(); runCurrent()
        failure = CoreException.Conflict(); vm.syncNow(); runCurrent()
        assertFalse(vm.syncing.value)
        assertNull(vm.syncError.value)
        failure = null; vm.syncNow(); runCurrent(); assertEquals(2, calls)
    }

    @Test fun configureEnablePolicyAndClearRefreshCoreStatus() = runTest(dispatcher.scheduler) {
        val vm = vm(); runCurrent()
        vm.configureSync(config.copy(prefix = "family"), "test-credential"); runCurrent()
        assertFalse(vm.syncStatus.value!!.enabled)
        assertEquals("family", vm.syncStatus.value!!.config!!.prefix)
        vm.setSyncPolicy(SyncPolicy(false, 60u, false)); runCurrent()
        assertEquals(60u, vm.syncStatus.value!!.policy.intervalMinutes)
        vm.setSyncEnabled(true); runCurrent(); assertTrue(vm.syncStatus.value!!.enabled)
        vm.clearSyncConfig(); runCurrent(); assertNull(vm.syncStatus.value!!.config)
    }
}
