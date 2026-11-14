package app.snowfold.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.snowfold.ui.theme.SnowFoldType

/** 三个主页面共用原生可收起顶栏；列表滚动与系统返回仍由 Compose 接管。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val colors = appColors()
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = colors.canvas,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = {
                    val collapsed = behavior.state.collapsedFraction > 0.5f
                    Text(title, style = if (collapsed) SnowFoldType.statValue else SnowFoldType.pageTitle,
                        modifier = Modifier.testTag("page-title").semantics {
                            stateDescription = if (collapsed) "collapsed" else "expanded"
                        })
                },
                actions = actions,
                expandedHeight = 112.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.canvas, scrolledContainerColor = colors.canvas),
                scrollBehavior = behavior,
            )
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}
