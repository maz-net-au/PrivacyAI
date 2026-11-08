package au.net.maz.privacyai.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Passes results back between screens (a picked image, a written prompt) in memory only.
 * Navigation's own result passing uses saved instance state, which Android may keep
 * outside the app, so it's never used for content. Cleared on lock.
 */
class Results {
    private val _values = MutableStateFlow<Map<String, Any>>(emptyMap())
    val values: StateFlow<Map<String, Any>> = _values

    fun put(key: String, value: Any) = _values.update { it + (key to value) }

    /** Take (and remove) a result. */
    @Suppress("UNCHECKED_CAST")
    fun <T> take(key: String): T? {
        var out: T? = null
        _values.update { m -> out = m[key] as? T; m - key }
        return out
    }

    /** Read a value without removing it (for context several screens use). */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = _values.value[key] as? T

    fun remove(key: String) = _values.update { it - key }

    fun clear() {
        _values.value = emptyMap()
    }
}
