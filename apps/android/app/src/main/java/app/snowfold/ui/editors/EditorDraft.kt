package app.snowfold.ui.editors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snowfold.MainViewModel

/** 同一份表单在 sheet 和平板右栏之间移动时，草稿仍属于 SavedStateHandle。 */
@Composable
fun <T> rememberEditorField(vm: MainViewModel, scope: String, fieldName: String, initial: T): MutableState<T> {
    val state = vm.draftField(scope, fieldName, initial).collectAsStateWithLifecycle()
    return remember(vm, scope, fieldName) {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) { vm.updateDraft(scope, fieldName, value) }
            override fun component1(): T = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
