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
                    val total = SnowFoldCore.open(application.filesDir.absolutePath, app.snowfold.platform.AndroidSecureKeyStore(application)).use { core ->
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
private fun CoreException.descriptionResource(): Int = app.snowfold.data.coreErrorRes(this)
