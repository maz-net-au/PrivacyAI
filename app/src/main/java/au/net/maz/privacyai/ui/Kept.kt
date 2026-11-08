package au.net.maz.privacyai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Per-screen state that survives going to another screen and back (e.g. a form's values
 * while you pick an image), kept in memory only. `rememberSaveable` isn't used for content
 * because Android may store saved state outside the app. Everything here is dropped on lock.
 */
class ScreenStateHolder : ViewModel() {
    val values = mutableMapOf<String, Any?>()

    override fun onCleared() {
        values.values.forEach { (it as? OnCleared)?.close() }
        values.clear()
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
fun <T> screenState(key: String, init: () -> T): T {
    val holder: ScreenStateHolder = viewModel()
    return remember(holder, key) { holder.values.getOrPut(key) { init() } as T }
}

/** A [MutableState] kept for the life of the screen (see [screenState]). */
@Composable
fun <T> kept(key: String, init: () -> T): MutableState<T> = screenState(key) { mutableStateOf(init()) }

/** Runs [block] when the screen is closed for good (not when going to a sub-screen). */
class OnCleared(private val block: () -> Unit) : java.io.Closeable {
    override fun close() = block()
}
