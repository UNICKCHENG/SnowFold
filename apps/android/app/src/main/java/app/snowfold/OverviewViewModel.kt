package app.snowfold

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.snowfold.core.SnowFoldCore
import app.snowfold.core.CoreException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface OverviewState {
    data object Loading : OverviewState
    data class Ready(val totalAssetsCents: Long) : OverviewState
    data class Failed(@param:StringRes val detail: Int) : OverviewState
}

class OverviewViewModel(application: Application) : AndroidViewModel(application) {
    var state: OverviewState by mutableStateOf(OverviewState.Loading)
        private set

    init {
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                try {
                    // This shell only needs one snapshot; close the Rust handle after the read.
                    val total = SnowFoldCore.open(application.filesDir.absolutePath).use { core ->
                        core.overview(LocalDate.now().toString()).totalAssetsCents
                    }
                    Log.i("SnowFold", "Overview loaded from local core")
                    OverviewState.Ready(total)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CoreException) {
                    Log.w("SnowFold", "Core read failed (${error.javaClass.simpleName})")
                    OverviewState.Failed(error.descriptionResource())
                } catch (error: LinkageError) {
                    Log.e("SnowFold", "Core native library unavailable (${error.javaClass.simpleName})")
                    OverviewState.Failed(R.string.error_internal)
                } catch (error: Exception) {
                    Log.e("SnowFold", "Core initialization failed (${error.javaClass.simpleName})")
                    OverviewState.Failed(R.string.error_internal)
                }
            }
        }
    }
}

// UniFFI's flat error variants have empty messages; use their stable categories.
@StringRes
private fun CoreException.descriptionResource(): Int = when (this) {
    is CoreException.Validation -> R.string.error_validation
    is CoreException.NotFound -> R.string.error_not_found
    is CoreException.Conflict -> R.string.error_conflict
    is CoreException.Authentication -> R.string.error_authentication
    is CoreException.IncompatibleBackup -> R.string.error_incompatible_backup
    is CoreException.InvalidBackup -> R.string.error_invalid_backup
    is CoreException.InvalidRecoveryCode -> R.string.error_invalid_recovery_code
    is CoreException.KeyUnavailable -> R.string.error_key_unavailable
    is CoreException.Io -> R.string.error_io
    is CoreException.Database -> R.string.error_database
    is CoreException.IncompatibleDatabase -> R.string.error_incompatible_database
    is CoreException.Overflow -> R.string.error_overflow
    is CoreException.Internal -> R.string.error_internal
}
