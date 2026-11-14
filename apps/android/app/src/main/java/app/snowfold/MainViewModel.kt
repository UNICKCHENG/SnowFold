package app.snowfold

import androidx.annotation.StringRes
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import app.snowfold.core.BackupInfo
import app.snowfold.core.CoreException
import app.snowfold.core.RecoveryCodeInfo
import app.snowfold.core.SealedBackupInfo
import app.snowfold.core.SubscriptionInput
import app.snowfold.core.ThemePreference
import app.snowfold.core.UndoTicket
import app.snowfold.data.CoreAccess
import app.snowfold.data.LedgerGateway
import app.snowfold.data.coreErrorRes
import app.snowfold.ui.model.CATEGORY_CODES
import app.snowfold.ui.model.UiLedger
import app.snowfold.ui.model.toUiLedger
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Loading : AppState
    data class Fatal(@StringRes val titleRes: Int, val argRes: List<Int> = emptyList()) : AppState
    data class Ready(
        val ui: UiLedger,
        val theme: ThemePreference,
        val keyFingerprint: String?,
        val banner: Banner?,
    ) : AppState
}

data class Banner(@StringRes val titleRes: Int, val argRes: List<Int> = emptyList())

/** Editor targets survive configuration changes and process death via SavedStateHandle. */
sealed interface Editor {
    val key: String

    data class NewAsset(val categoryIndex: Int) : Editor {
        override val key: String get() = "newAsset:$categoryIndex"
    }

    data class Asset(val id: String, val action: String = "detail") : Editor {
        override val key: String get() = "asset:$id:$action"
    }

    data object NewSubscription : Editor {
        override val key: String get() = "newSubscription"
    }

    data class Subscription(val id: String) : Editor {
        override val key: String get() = "subscription:$id"
    }

    companion object {
        fun fromKey(raw: String?): Editor? = when {
            raw == null -> null
            raw.startsWith("newAsset:") -> raw.substringAfter("newAsset:").toIntOrNull()?.let { NewAsset(it) }
            raw.startsWith("asset:") -> raw.split(":").let { Asset(it[1], it.getOrElse(2) { "detail" }) }
            raw == "newSubscription" -> NewSubscription
            raw.startsWith("subscription:") -> Subscription(raw.substringAfter("subscription:"))
            else -> null
        }
    }
}

sealed interface PendingConfirm {
    data object ClearData : PendingConfirm
    data class ImportJson(val json: String, val info: BackupInfo) : PendingConfirm
    data class ImportSealed(val bytes: ByteArray, val info: SealedBackupInfo) : PendingConfirm
    data class ReplaceKey(val contents: String, val info: RecoveryCodeInfo) : PendingConfirm
}

/** Transient toast-style message; argRes entries are resolved before raw args. */
data class UiMessage(
    @StringRes val res: Int,
    val argRes: List<Int> = emptyList(),
    val args: List<String> = emptyList(),
)

class SnackbarMessage(
    @StringRes val res: Int,
    val args: List<String> = emptyList(),
    val undoToken: String? = null,
    val validForMs: Long = 0L,
)

private data class OpOutcome(
    val undo: UndoTicket? = null,
    val undoLabelRes: Int = 0,
    val successMessage: UiMessage? = null,
    val closeEditor: Boolean = true,
    val closeCorrection: Boolean = false,
    val invalidateUndo: Boolean = true,
)

class MainViewModel(
    private val savedState: SavedStateHandle,
    private val core: CoreAccess,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** True while a core write is in flight; double taps are ignored. */
    val busy = MutableStateFlow(false)

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state

    private val _editor = MutableStateFlow<Editor?>(Editor.fromKey(savedState.get<String>(KEY_EDITOR)))
    val editor: StateFlow<Editor?> = _editor

    val correctionId = savedState.getStateFlow<String?>("correctionId", null)

    fun openCorrection(id: String) {
        _editorError.value = null
        savedState["correctionId"] = id
    }

    fun closeCorrection() {
        savedState["correctionId"] = null
        _editorError.value = null
    }

    fun <T> draftField(scope: String, field: String, initial: T): StateFlow<T> =
        savedState.getStateFlow("draft.$scope.$field", initial)

    fun <T> updateDraft(scope: String, field: String, value: T) {
        savedState["draft.$scope.$field"] = value
    }

    private fun clearDrafts() {
        savedState.keys().filter { it.startsWith("draft.") }.forEach { savedState.remove<Any?>(it) }
    }

    private val _editorError = MutableStateFlow<UiMessage?>(null)
    val editorError: StateFlow<UiMessage?> = _editorError

    private val _pendingConfirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _pendingConfirm

    private val _messages = Channel<UiMessage>(capacity = Channel.CONFLATED)
    val messages = _messages.receiveAsFlow()

    private val _snackbar = MutableStateFlow<SnackbarMessage?>(null)
    val snackbar: StateFlow<SnackbarMessage?> = _snackbar

    private val _highlight = MutableStateFlow<String?>(null)
    val highlight: StateFlow<String?> = _highlight

    private var undoPending: UndoTicket? = null
    private var undoReceivedAt = 0L
    private var highlightJob: kotlinx.coroutines.Job? = null

    init {
        boot()
    }

    private fun boot() {
        viewModelScope.launch {
            val theme = try {
                core.call { it.theme() }
            } catch (error: Throwable) {
                _state.value = AppState.Fatal(R.string.local_data_title, listOf(coreErrorRes(error)))
                return@launch
            }
            var banner: Banner? = null
            try {
                core.call { it.startupBackup() }
            } catch (error: Throwable) {
                banner = Banner(R.string.startup_backup_failed, argRes = listOf(coreErrorRes(error)))
            }
            val fingerprint = try {
                core.call { it.keyFingerprint() }
            } catch (error: Throwable) {
                if (banner == null) {
                    banner = Banner(R.string.startup_key_unavailable, argRes = listOf(coreErrorRes(error)))
                }
                null
            }
            val ui = try {
                loadUi()
            } catch (error: Throwable) {
                _state.value = AppState.Fatal(R.string.local_data_title, listOf(coreErrorRes(error)))
                return@launch
            }
            closeEditorIfMissing(ui)
            _state.value = AppState.Ready(ui, theme, fingerprint, banner)
        }
    }

    private suspend fun loadUi(): UiLedger = core.call { g ->
        val data = g.data()
        val overview = try {
            g.overview(LocalDate.now())
        } catch (error: CoreException.Overflow) {
            null
        }
        g.toUiLedger(data, overview, LocalDate.now())
    }

    /**
     * 重新读取核心读模型并刷新状态。写操作后的刷新失败用桌面同款
     * 「已保存，但读取失败」提示；后台回前台的静默刷新失败保留旧数据。
     */
    private suspend fun reload(notifyOnFailure: Boolean = false): Boolean {
        val ui = try {
            loadUi()
        } catch (error: CoreException) {
            if (notifyOnFailure) {
                _messages.trySend(UiMessage(R.string.saved_but_reload_failed, argRes = listOf(coreErrorRes(error))))
            }
            return false
        } catch (_: Throwable) {
            return false
        }
        closeEditorIfMissing(ui)
        val current = _state.value as? AppState.Ready ?: return false
        _state.value = current.copy(ui = ui)
        return true
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** 编辑 sheet 打开期间该记录被删：刷新后发现目标不存在就关 sheet 并提示。 */
    private fun closeEditorIfMissing(ui: UiLedger) {
        when (val target = _editor.value) {
            is Editor.Asset -> if (ui.assets.none { it.id == target.id }) {
                closeEditor()
                _messages.trySend(UiMessage(R.string.error_not_found))
            }

            is Editor.Subscription -> if (ui.subscriptions.none { it.id == target.id }) {
                closeEditor()
                _messages.trySend(UiMessage(R.string.error_not_found))
            }

            else -> Unit
        }
    }

    fun openEditor(target: Editor) {
        if (_editor.value != target) {
            clearDrafts()
            closeCorrection()
        }
        _editorError.value = null
        _editor.value = target
        savedState[KEY_EDITOR] = target.key
    }

    fun closeEditor() {
        closeCorrection()
        clearDrafts()
        _editor.value = null
        _editorError.value = null
        savedState[KEY_EDITOR] = null
    }

    // MARK: writes

    private fun submit(errorIntoEditor: Boolean, failRes: Int = R.string.not_saved, block: (LedgerGateway) -> OpOutcome) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val outcome = core.call(block)
                if (outcome.undo != null) {
                    offerUndo(outcome.undo, outcome.undoLabelRes)
                } else if (outcome.invalidateUndo) {
                    undoPending = null
                    _snackbar.value = null
                }
                reload(notifyOnFailure = true)
                if (outcome.closeEditor) closeEditor()
                if (outcome.closeCorrection) closeCorrection()
                outcome.successMessage?.let { _messages.trySend(it) }
            } catch (error: CoreException) {
                val message = UiMessage(failRes, argRes = listOf(coreErrorRes(error)))
                if (errorIntoEditor) {
                    _editorError.value = message
                } else {
                    _messages.trySend(message)
                }
            } finally {
                busy.value = false
            }
        }
    }

    private fun offerUndo(ticket: UndoTicket, labelRes: Int) {
        undoPending = ticket
        undoReceivedAt = now()
        _snackbar.value = SnackbarMessage(
            res = labelRes,
            undoToken = ticket.token,
            validForMs = ticket.validForMs.toLong(),
        )
    }

    fun onSnackbarDismissed(token: String) {
        if (undoPending?.token == token) {
            undoPending = null
        }
        if (_snackbar.value?.undoToken == token) {
            _snackbar.value = null
        }
    }

    /** Snackbar 撤销按钮；与桌面一致先查本地 6 秒时限再交给核心。 */
    fun onUndoPressed() {
        val ticket = undoPending ?: run {
            _messages.trySend(UiMessage(R.string.nothing_to_undo))
            return
        }
        if (now() - undoReceivedAt > UNDO_WINDOW_MS) {
            undoPending = null
            _snackbar.value = null
            _messages.trySend(UiMessage(R.string.undo_expired))
            return
        }
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                core.call { it.undo(ticket.token) }
                undoPending = null
                _snackbar.value = null
                refresh()
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.operation_failed, argRes = listOf(coreErrorRes(error))))
            } finally {
                busy.value = false
            }
        }
    }

    private fun markHighlight(id: String) {
        highlightJob?.cancel()
        _highlight.value = id
        highlightJob = viewModelScope.launch {
            delay(3000)
            if (_highlight.value == id) _highlight.value = null
        }
    }

    fun saveNewAsset(categoryIndex: Int, name: String, amountText: String) {
        submit(errorIntoEditor = true) { g ->
            val cents = g.parseAmount(amountText)
            val id = g.createAsset(CATEGORY_CODES[categoryIndex], name, cents)
            markHighlight(id)
            OpOutcome()
        }
    }

    fun renameAsset(id: String, name: String) {
        submit(errorIntoEditor = true) { g ->
            g.renameAsset(id, name)
            OpOutcome()
        }
    }

    fun changeBalance(id: String, amountText: String) {
        submit(errorIntoEditor = true) { g ->
            val ticket = g.changeBalance(id, g.parseAmount(amountText))
            OpOutcome(undo = ticket, undoLabelRes = R.string.balance_change_recorded, invalidateUndo = ticket != null)
        }
    }

    fun correctHistory(historyId: String, amountText: String) {
        submit(errorIntoEditor = true) { g ->
            g.correctHistory(historyId, g.parseAmount(amountText))
            OpOutcome(closeEditor = false, closeCorrection = true)
        }
    }

    fun deleteAsset(id: String) {
        submit(errorIntoEditor = false, failRes = R.string.operation_failed) { g ->
            val ticket = g.deleteAsset(id)
            OpOutcome(undo = ticket, undoLabelRes = R.string.asset_deleted)
        }
    }

    fun saveNewSubscription(
        name: String,
        amountText: String,
        currency: String,
        cycle: String,
        nextChargeOn: LocalDate?,
    ) {
        if (nextChargeOn == null) {
            _editorError.value = UiMessage(R.string.choose_next_charge_date)
            return
        }
        submit(errorIntoEditor = true) { g ->
            val cents = g.parseAmount(amountText)
            val id = g.createSubscription(
                SubscriptionInput(
                    name = name,
                    amountCents = cents,
                    currency = currency,
                    cycle = cycle,
                    nextChargeOn = nextChargeOn.toString(),
                    status = STATUS_ACTIVE,
                ),
            )
            markHighlight(id)
            OpOutcome()
        }
    }

    fun saveSubscription(
        id: String,
        name: String,
        amountText: String,
        currency: String,
        cycle: String,
        nextChargeOn: LocalDate?,
        status: String? = null,
    ) {
        if (nextChargeOn == null) {
            _editorError.value = UiMessage(R.string.choose_next_charge_date)
            return
        }
        submit(errorIntoEditor = true) { g ->
            val current = g.data().subscriptions.firstOrNull { it.id == id }
                ?: throw CoreException.NotFound()
            val cents = g.parseAmount(amountText)
            g.updateSubscription(
                id,
                SubscriptionInput(
                    name = name,
                    amountCents = cents,
                    currency = currency,
                    cycle = cycle,
                    nextChargeOn = nextChargeOn.toString(),
                    status = status ?: current.status,
                ),
            )
            OpOutcome()
        }
    }

    fun renewSubscription(id: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result = core.call { it.renewSubscription(id, LocalDate.now()) }
                undoPending = null
                _snackbar.value = null
                _messages.trySend(UiMessage(R.string.subscription_renewed, args = listOf(result.periods.toString())))
                refresh()
            } catch (error: CoreException.Conflict) {
                _messages.trySend(UiMessage(R.string.renewal_not_due))
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.operation_failed, argRes = listOf(coreErrorRes(error))))
            } finally {
                busy.value = false
            }
        }
    }

    fun setSubscriptionPaused(id: String, paused: Boolean) {
        submit(errorIntoEditor = false, failRes = R.string.operation_failed) { g ->
            g.setSubscriptionPaused(id, paused)
            OpOutcome()
        }
    }

    fun deleteSubscription(id: String) {
        submit(errorIntoEditor = false, failRes = R.string.operation_failed) { g ->
            val ticket = g.deleteSubscription(id)
            OpOutcome(undo = ticket, undoLabelRes = R.string.subscription_deleted)
        }
    }

    fun setTheme(theme: ThemePreference) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                core.call { it.saveTheme(theme) }
                val current = _state.value as? AppState.Ready
                if (current != null) _state.value = current.copy(theme = theme)
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.theme_not_saved, argRes = listOf(coreErrorRes(error))))
            } finally {
                busy.value = false
            }
        }
    }

    // MARK: confirmations (only for destructive/irreversible actions)

    fun requestClearData() {
        _pendingConfirm.value = PendingConfirm.ClearData
    }

    fun prepareImportJson(json: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val info = core.call { it.inspectJson(json) }
                _pendingConfirm.value = PendingConfirm.ImportJson(json, info)
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.import_failed_unchanged, argRes = listOf(coreErrorRes(error))))
            } finally {
                busy.value = false
            }
        }
    }

    fun prepareImportSealed(bytes: ByteArray) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val info = core.call { it.inspectSealed(bytes) }
                _pendingConfirm.value = PendingConfirm.ImportSealed(bytes, info)
            } catch (error: CoreException.KeyUnavailable) {
                _messages.trySend(UiMessage(R.string.key_unreadable_import_aborted, argRes = listOf(coreErrorRes(error))))
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.sealed_import_failed_unchanged, argRes = listOf(coreErrorRes(error))))
            } finally {
                busy.value = false
            }
        }
    }

    fun prepareReplaceKey(contents: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val info = core.call { it.inspectRecoveryCode(contents) }
                _pendingConfirm.value = PendingConfirm.ReplaceKey(contents, info)
            } catch (error: CoreException) {
                _messages.trySend(UiMessage(R.string.recovery_code_unreadable))
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelPendingConfirm() {
        _pendingConfirm.value = null
    }

    fun confirmPending() {
        val pending = _pendingConfirm.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                when (pending) {
                    is PendingConfirm.ClearData -> core.call { it.clearData() }
                    is PendingConfirm.ImportJson -> core.call { it.importJson(pending.json) }
                    is PendingConfirm.ImportSealed -> core.call { it.importSealed(pending.bytes) }
                    is PendingConfirm.ReplaceKey -> {
                        val result = core.call {
                            it.importRecoveryCode(pending.contents, pending.info.currentFingerprint)
                        }
                        val fingerprint = result.fingerprint
                        _pendingConfirm.value = null
                        undoPending = null
                        _snackbar.value = null
                        closeEditor()
                        refresh()
                        val message = if (result.previousKeyBackedUp) {
                            UiMessage(R.string.key_replaced, args = listOf(fingerprint))
                        } else {
                            UiMessage(R.string.key_imported, args = listOf(fingerprint))
                        }
                        _messages.trySend(message)
                        updateReady(fingerprint = fingerprint)
                        return@launch
                    }
                }
                // Data replaced: desktop drops pending undo and open editors.
                _pendingConfirm.value = null
                undoPending = null
                _snackbar.value = null
                closeEditor()
                refresh()
                _messages.trySend(UiMessage(R.string.data_updated))
            } catch (error: CoreException.Conflict) {
                if (pending is PendingConfirm.ReplaceKey) {
                    _messages.trySend(UiMessage(R.string.key_changed_elsewhere))
                    _pendingConfirm.value = null
                } else {
                    _messages.trySend(UiMessage(R.string.operation_failed, argRes = listOf(coreErrorRes(error))))
                }
            } catch (error: CoreException) {
                when (pending) {
                    is PendingConfirm.ImportJson -> _messages.trySend(
                        UiMessage(R.string.import_failed_unchanged, argRes = listOf(coreErrorRes(error))),
                    )

                    is PendingConfirm.ImportSealed -> _messages.trySend(
                        UiMessage(R.string.sealed_import_failed_unchanged, argRes = listOf(coreErrorRes(error))),
                    )

                    else -> _messages.trySend(
                        UiMessage(R.string.operation_failed, argRes = listOf(coreErrorRes(error))),
                    )
                }
            } finally {
                busy.value = false
            }
        }
    }

    private fun updateReady(fingerprint: String?) {
        val current = _state.value as? AppState.Ready ?: return
        _state.value = current.copy(keyFingerprint = fingerprint)
    }

    // MARK: exports (payload fetch runs on the core worker; the screen writes it via SAF)

    /** 快照的修正记录（改前→改后），金额文本由核心输出。 */
    suspend fun correctionsFor(historyId: String): List<CorrectionUi> = core.call { g ->
        g.historyCorrections(historyId).map { record ->
            CorrectionUi(
                recordedAt = record.recordedAt,
                previousText = g.formatMoney(record.previousAmountCents, "CNY"),
                amountText = g.formatMoney(record.amountCents, "CNY"),
            )
        }
    }

    data class CorrectionUi(
        val recordedAt: String,
        val previousText: String,
        val amountText: String,
    )

    suspend fun exportJsonPayload(): String = core.call { it.exportJson() }

    suspend fun exportSealedPayload(): ByteArray = core.call { it.exportSealed() }

    suspend fun exportRecoveryCodePayload(): String = core.call { it.exportRecoveryCode() }

    suspend fun exportSealedWithFingerprint(): Pair<ByteArray, String?> = core.call { g ->
        g.exportSealed() to g.keyFingerprint()
    }

    suspend fun exportRecoveryCodeWithFingerprint(): Pair<String, String?> = core.call { g ->
        g.exportRecoveryCode() to g.keyFingerprint()
    }

    fun refreshFingerprintInState(fingerprint: String?) {
        val current = _state.value as? AppState.Ready ?: return
        _state.value = current.copy(keyFingerprint = fingerprint)
    }

    fun notifyExportComplete(kindRes: Int, argRes: List<Int> = emptyList(), args: List<String> = emptyList()) {
        _messages.trySend(UiMessage(kindRes, argRes = argRes, args = args))
    }

    /** Fresh fingerprint after a key-generating export; also refreshes settings display. */
    suspend fun currentFingerprintOrNull(): String? = try {
        core.call { it.keyFingerprint() }.also { fingerprint -> updateReady(fingerprint = fingerprint) }
    } catch (_: CoreException) {
        null
    }

    fun notifyImportReadFailed() {
        _messages.trySend(UiMessage(R.string.import_failed_unchanged, argRes = listOf(R.string.error_io)))
    }

    fun notifyExportFailed(detailRes: Int = R.string.error_io, templateRes: Int = R.string.export_failed) {
        _messages.trySend(UiMessage(templateRes, argRes = listOf(detailRes)))
    }

    companion object {
        val Factory: androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as android.app.Application
                MainViewModel(
                    savedState = createSavedStateHandle(),
                    core = app.snowfold.data.SerializedCoreAccess(application.filesDir.absolutePath),
                )
            }
        }

        private const val KEY_EDITOR = "app.snowfold.EDITOR"
        private const val STATUS_ACTIVE = "active"
        private const val UNDO_WINDOW_MS = 6000L
    }
}

private val ERROR_MESSAGE_RESOURCES = setOf(
    R.string.operation_failed,
    R.string.not_saved,
    R.string.nothing_to_undo,
    R.string.undo_expired,
    R.string.recovery_code_unreadable,
    R.string.key_changed_elsewhere,
    R.string.export_failed,
    R.string.import_failed_unchanged,
    R.string.sealed_export_failed,
    R.string.key_unreadable_import_aborted,
    R.string.sealed_import_failed_unchanged,
    R.string.recovery_export_failed,
    R.string.recovery_import_failed,
    R.string.recovery_import_failed_unchanged,
    R.string.theme_not_saved,
    R.string.renewal_not_due,
    R.string.saved_but_reload_failed,
    R.string.error_validation,
    R.string.error_not_found,
    R.string.error_conflict,
    R.string.error_authentication,
    R.string.error_incompatible_backup,
    R.string.error_invalid_backup,
    R.string.error_invalid_recovery_code,
    R.string.error_key_unavailable,
    R.string.error_io,
    R.string.error_database,
    R.string.error_incompatible_database,
    R.string.error_overflow,
    R.string.error_internal,
)

/** 通知条色调：错误红、成功品牌蓝（docs/design.md §6 横幅）。 */
fun isErrorMessage(message: UiMessage): Boolean = message.res in ERROR_MESSAGE_RESOURCES
