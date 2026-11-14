package app.snowfold.data

import androidx.annotation.StringRes
import app.snowfold.R
import app.snowfold.core.BackupInfo
import app.snowfold.core.CoreException
import app.snowfold.core.HistoryCorrectionRecord
import app.snowfold.core.KeyImportResult
import app.snowfold.core.LedgerData
import app.snowfold.core.Overview
import app.snowfold.core.RecoveryCodeInfo
import app.snowfold.core.RenewalResult
import app.snowfold.core.SealedBackupInfo
import app.snowfold.core.SnowFoldCore
import app.snowfold.core.SecureKeyStore
import app.snowfold.core.SubscriptionGroup
import app.snowfold.core.SubscriptionInput
import app.snowfold.core.ThemePreference
import app.snowfold.core.UndoTicket
import app.snowfold.core.SyncConfig
import app.snowfold.core.SyncPolicy
import app.snowfold.core.SyncStatus
import app.snowfold.core.SyncReport
import app.snowfold.core.SyncTransport
import app.snowfold.core.formatMoney
import app.snowfold.core.parseAmount
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single seam between the UI layer and the shared Rust core. Hosts call the
 * core through this interface so JVM unit tests can replace the native library
 * with an in-memory fake.
 */
interface LedgerGateway {
    fun data(): LedgerData
    fun overview(on: LocalDate): Overview
    fun createAsset(categoryCode: String, assetName: String, amountCents: Long): String
    fun renameAsset(id: String, assetName: String)
    fun changeBalance(id: String, amountCents: Long): UndoTicket?
    fun correctHistory(id: String, amountCents: Long)
    fun historyCorrections(id: String): List<HistoryCorrectionRecord>
    fun deleteAsset(id: String): UndoTicket
    fun undo(token: String)
    fun createSubscription(subscription: SubscriptionInput): String
    fun updateSubscription(id: String, subscription: SubscriptionInput)
    fun setSubscriptionPaused(id: String, isPaused: Boolean)
    fun renewSubscription(id: String, on: LocalDate): RenewalResult
    fun subscriptionGroup(id: String, on: LocalDate): SubscriptionGroup
    fun subscriptionAnnual(id: String): Long
    fun deleteSubscription(id: String): UndoTicket
    fun theme(): ThemePreference
    fun saveTheme(theme: ThemePreference)
    fun exportJson(): String
    fun inspectJson(json: String): BackupInfo
    fun importJson(json: String)
    fun exportSealed(): ByteArray
    fun inspectSealed(bytes: ByteArray): SealedBackupInfo
    fun importSealed(bytes: ByteArray): SealedBackupInfo
    fun keyFingerprint(): String?
    fun exportRecoveryCode(): String
    fun inspectRecoveryCode(contents: String): RecoveryCodeInfo
    fun importRecoveryCode(contents: String, expectedCurrentFingerprint: String?): KeyImportResult
    fun syncStatus(): SyncStatus
    fun configureSync(config: SyncConfig, secret: String)
    fun setSyncEnabled(enabled: Boolean)
    fun clearSyncConfig()
    fun setSyncPolicy(policy: SyncPolicy)
    fun syncNow(transport: SyncTransport): SyncReport
    fun clearData()
    fun startupBackup()
    fun parseAmount(value: String): Long
    fun formatAmount(amountCents: Long): String
    fun formatMoney(amountCents: Long, currency: String): String
}

class RustLedgerGateway(private val core: SnowFoldCore) : LedgerGateway {
    override fun data(): LedgerData = core.data()
    override fun overview(on: LocalDate): Overview = core.overview(on.toString())
    override fun createAsset(categoryCode: String, assetName: String, amountCents: Long): String =
        core.createAsset(categoryCode, assetName, amountCents)

    override fun renameAsset(id: String, assetName: String) = core.renameAsset(id, assetName)
    override fun changeBalance(id: String, amountCents: Long): UndoTicket? = core.changeBalance(id, amountCents)
    override fun correctHistory(id: String, amountCents: Long) = core.correctHistory(id, amountCents)
    override fun historyCorrections(id: String): List<HistoryCorrectionRecord> = core.historyCorrections(id)
    override fun deleteAsset(id: String): UndoTicket = core.deleteAsset(id)
    override fun undo(token: String) = core.undo(token)
    override fun createSubscription(subscription: SubscriptionInput): String = core.createSubscription(subscription)
    override fun updateSubscription(id: String, subscription: SubscriptionInput) = core.updateSubscription(id, subscription)
    override fun setSubscriptionPaused(id: String, isPaused: Boolean) = core.setSubscriptionPaused(id, isPaused)
    override fun renewSubscription(id: String, on: LocalDate): RenewalResult = core.renewSubscription(id, on.toString())
    override fun subscriptionGroup(id: String, on: LocalDate): SubscriptionGroup = core.subscriptionGroup(id, on.toString())
    override fun subscriptionAnnual(id: String): Long = core.subscriptionAnnual(id)
    override fun deleteSubscription(id: String): UndoTicket = core.deleteSubscription(id)
    override fun theme(): ThemePreference = core.theme()
    override fun saveTheme(theme: ThemePreference) = core.saveTheme(theme)
    override fun exportJson(): String = core.exportJson()
    override fun inspectJson(json: String): BackupInfo = core.inspectJson(json)
    override fun importJson(json: String) = core.importJson(json)
    override fun exportSealed(): ByteArray = core.exportSealed()
    override fun inspectSealed(bytes: ByteArray): SealedBackupInfo = core.inspectSealed(bytes)
    override fun importSealed(bytes: ByteArray): SealedBackupInfo = core.importSealed(bytes)
    override fun keyFingerprint(): String? = core.keyFingerprint()
    override fun exportRecoveryCode(): String = core.exportRecoveryCode()
    override fun inspectRecoveryCode(contents: String): RecoveryCodeInfo = core.inspectRecoveryCode(contents)
    override fun importRecoveryCode(contents: String, expectedCurrentFingerprint: String?): KeyImportResult =
        core.importRecoveryCode(contents, expectedCurrentFingerprint)

    override fun syncStatus(): SyncStatus = core.syncStatus()
    override fun configureSync(config: SyncConfig, secret: String) = core.configureSync(config, secret)
    override fun setSyncEnabled(enabled: Boolean) = core.setSyncEnabled(enabled)
    override fun clearSyncConfig() = core.clearSyncConfig()
    override fun setSyncPolicy(policy: SyncPolicy) = core.setSyncPolicy(policy)
    override fun syncNow(transport: SyncTransport): SyncReport = core.syncNow(transport)
    override fun clearData() = core.clearData()
    override fun startupBackup() = core.startupBackup()
    override fun parseAmount(value: String): Long = app.snowfold.core.parseAmount(value)
    override fun formatAmount(amountCents: Long): String = app.snowfold.core.formatAmount(amountCents)
    override fun formatMoney(amountCents: Long, currency: String): String = app.snowfold.core.formatMoney(amountCents, currency)
}

/** 普通调用保持串行；同步网络等待走独立 IO，允许期间本机业务写入。 */
interface CoreAccess {
    suspend fun <T> call(block: (LedgerGateway) -> T): T
    suspend fun sync(transport: SyncTransport): SyncReport = call { it.syncNow(transport) }
}

class SerializedCoreAccess(private val dataDir: String, private val keyStore: SecureKeyStore) : CoreAccess {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val openMutex = Mutex()

    @Volatile
    private var gateway: LedgerGateway? = null

    init {
        // Warm the core on the serial worker; a broken database surfaces on the
        // first real call as a Fatal state instead of crashing the process.
        CoroutineScope(dispatcher + SupervisorJob()).launch {
            try {
                access()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun access(): LedgerGateway =
        gateway ?: openMutex.withLock {
            gateway ?: RustLedgerGateway(SnowFoldCore.open(dataDir, keyStore)).also { gateway = it }
        }

    override suspend fun sync(transport: SyncTransport): SyncReport = withContext(Dispatchers.IO) {
        access().syncNow(transport)
    }

    override suspend fun <T> call(block: (LedgerGateway) -> T): T = withContext(dispatcher) {
        block(access())
    }
}

/** Stable localization for core failures; never surface Rust Display strings. */
@StringRes
fun coreErrorRes(error: Throwable): Int = when (error) {
    is CoreException.RecoveryCodeRequired -> R.string.error_recovery_code_required
    is CoreException.SyncNotConfigured -> R.string.error_sync_not_configured
    is CoreException.SyncDisabled -> R.string.error_sync_disabled
    is CoreException.SyncNetwork -> R.string.error_sync_network
    is CoreException.SyncAuthentication -> R.string.error_sync_authentication
    is CoreException.SyncConflict -> R.string.error_sync_conflict
    is CoreException.SyncRemoteKeyMismatch -> R.string.error_sync_remote_key_mismatch
    is CoreException.SyncInvalidRemote -> R.string.error_sync_invalid_remote
    is CoreException.SyncIncompatible -> R.string.error_sync_incompatible
    is CoreException.Validation -> R.string.error_validation
    is CoreException.NotFound -> R.string.error_not_found
    is CoreException.Conflict -> R.string.error_conflict
    is CoreException.Authentication -> R.string.error_authentication
    is CoreException.IncompatibleBackup -> R.string.error_incompatible_backup
    is CoreException.InvalidBackup -> R.string.error_invalid_backup
    is CoreException.InvalidRecoveryCode -> R.string.error_invalid_recovery_code
    is CoreException.KeyUnavailable -> R.string.error_key_unavailable
    is CoreException.IncompatibleDatabase -> R.string.error_incompatible_database
    is CoreException.Io -> R.string.error_io
    is CoreException.Database -> R.string.error_database
    is CoreException.Overflow -> R.string.error_overflow
    else -> R.string.error_internal
}
