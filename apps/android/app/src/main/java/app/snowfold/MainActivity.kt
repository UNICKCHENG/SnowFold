package app.snowfold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.activity.SystemBarStyle
import app.snowfold.core.ThemePreference
import app.snowfold.ui.nav.AppRoot
import app.snowfold.ui.theme.SnowFoldTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
            val state by vm.state.collectAsStateWithLifecycle()
            val themePref = (state as? AppState.Ready)?.theme ?: ThemePreference.SYSTEM
            val darkTheme = when (themePref) {
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            // Keep system bar icon contrast in step with the app-controlled theme.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                )
            }
            val windowSizeClass = calculateWindowSizeClass(this)
            SnowFoldTheme(darkTheme = darkTheme) {
                val expanded = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
                val wideTablet = expanded
                AppRoot(vm = vm, expanded = expanded, wideTablet = wideTablet)
            }
        }
    }
}
