package au.net.maz.privacyai.net

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ServerStatus { NOT_SET, UNKNOWN, UP, DOWN }

data class Health(
    val llm: ServerStatus = ServerStatus.NOT_SET,
    val image: ServerStatus = ServerStatus.NOT_SET,
    val models: List<String> = emptyList(),
) {
    enum class Colour { GREEN, YELLOW, RED }

    /**
     * Green = every server with an address is connected, yellow = some, red = none.
     * Servers with no address are left out. Red if no addresses are set at all.
     */
    val colour: Colour
        get() {
            val set = listOf(llm, image).filter { it != ServerStatus.NOT_SET }
            val up = set.count { it == ServerStatus.UP }
            return when {
                set.isEmpty() || up == 0 -> Colour.RED
                up == set.size -> Colour.GREEN
                else -> Colour.YELLOW
            }
        }
}

/**
 * Polls both servers while the app is in the foreground (from the old app's
 * ServerHealthMonitor): llama-server GET /health, ComfyUI GET /system_stats.
 * Also fetches the LLM's model list.
 */
class HealthMonitor(
    private val scope: CoroutineScope,
    private val llama: LlamaClient,
    /** Current addresses (llm, image); null while locked. */
    private val addresses: suspend () -> Pair<String, String>?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(Health())
    val state: StateFlow<Health> = _state

    private val lock = Mutex()

    fun start() {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    probe()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun refreshNow() {
        scope.launch { probe() }
    }

    private suspend fun probe() {
        if (!lock.tryLock()) return
        try {
            val (llmAddr, imageAddr) = addresses() ?: return
            coroutineScope {
                val llmUp = async { if (llmAddr.isBlank()) null else isUp("${baseUrl(llmAddr)}/health") }
                val imgUp = async { if (imageAddr.isBlank()) null else isUp("${baseUrl(imageAddr)}/system_stats") }
                val llm = status(llmUp.await())
                val image = status(imgUp.await())
                val models = if (llm == ServerStatus.UP) {
                    llama.listModels(llmAddr).getOrNull() ?: _state.value.models
                } else _state.value.models
                _state.value = Health(llm, image, models)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun status(up: Boolean?) = when (up) {
        null -> ServerStatus.NOT_SET
        true -> ServerStatus.UP
        false -> ServerStatus.DOWN
    }

    private suspend fun isUp(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun reset() {
        _state.value = Health()
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
