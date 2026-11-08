package au.net.maz.privacyai

import android.app.Application
import android.graphics.Color
import android.util.Log
import au.net.maz.privacyai.comfy.ComfyRunner
import au.net.maz.privacyai.comfy.Workflows
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.media.MediaFetcher
import au.net.maz.privacyai.llm.ChatEngine
import au.net.maz.privacyai.net.ComfyClient
import au.net.maz.privacyai.net.HealthMonitor
import au.net.maz.privacyai.net.LlamaClient
import au.net.maz.privacyai.security.Session
import au.net.maz.privacyai.tasks.TaskManager
import au.net.maz.privacyai.ui.Results
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Holds the app-wide pieces (no DI framework, like the old app). Anything that touches
 * stored data exists only while unlocked.
 */
class PrivacyApp : Application(), ImageLoaderFactory {

    /** Errors in background work (e.g. a write racing the lock) are logged, not fatal. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.w("PrivacyApp", "background task failed", t) },
    )

    val llama = LlamaClient()
    val comfy = ComfyClient()
    val tasks by lazy { TaskManager(this, appScope) }
    val session by lazy { Session(this) }
    val engine by lazy { ChatEngine(this) }
    val results = Results()
    val health by lazy {
        HealthMonitor(appScope, llama) {
            session.store?.let { s -> runCatching { s.settings() }.getOrNull()?.let { it.llmAddress to it.imageAddress } }
        }
    }

    /** Only set while unlocked. */
    @Volatile var runner: ComfyRunner? = null
        private set
    @Volatile var workflows: Workflows? = null
        private set

    private var settingsWatch: Job? = null

    private val imageLoader by lazy {
        ImageLoader.Builder(this)
            .components {
                add(MediaFetcher.Factory { session.store?.files })
                add(MediaFetcher.Key())
            }
            .diskCache(null) // never write decrypted images to storage
            .crossfade(true)
            .build()
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        session.start()
        health.start()
    }

    fun onUnlocked(store: Store) {
        runner = ComfyRunner(appScope, store, comfy, tasks).also { r -> appScope.launch { r.resume() } }
        workflows = Workflows(this, store)
        settingsWatch?.cancel()
        settingsWatch = appScope.launch {
            launch {
                store.settings.map { it.llmAddress to it.imageAddress }.distinctUntilChanged().collect {
                    health.refreshNow()
                }
            }
            store.settings.collect { session.inactivityMinutes = it.inactivityMinutes }
        }
    }

    /** Runs before the database is closed on lock. */
    suspend fun onLocking() {
        settingsWatch?.cancel()
        tasks.cancelAll()
        runner?.stop()
        runner = null
        workflows = null
        results.clear()
        imageLoader.memoryCache?.clear()
        health.reset()
    }

    fun randomCharacterColour(): Int =
        Color.HSVToColor(floatArrayOf(Random.nextFloat() * 360f, 0.55f + Random.nextFloat() * 0.3f, 0.75f))
}
