package au.net.maz.privacyai.security

import android.os.SystemClock
import au.net.maz.privacyai.PrivacyApp
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.db.AppDatabase
import au.net.maz.privacyai.data.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LockState {
    data object Starting : LockState
    data object NoDatabase : LockState
    data object Locked : LockState
    /** [epoch] changes on every unlock so screens start fresh (nothing restored from before). */
    data class Unlocked(val store: Store, val epoch: Long) : LockState
}

/**
 * Opening, locking and unlocking the database.
 *
 * The data key only ever lives in memory. If Android kills the process it is gone, so a
 * restarted app goes back to PIN entry (or opens straight away when no PIN is set).
 */
class Session(private val app: PrivacyApp) {

    private val vault = Vault(File(app.noBackupFilesDir, "vault.json"))

    private val _state = MutableStateFlow<LockState>(LockState.Starting)
    val state: StateFlow<LockState> = _state

    private var key: ByteArray? = null
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var inactivityJob: Job? = null

    /** Settings cached for the inactivity check. */
    @Volatile var inactivityMinutes: Int = 5
    @Volatile var hasPin: Boolean = false
        private set

    val store: Store? get() = (state.value as? LockState.Unlocked)?.store

    fun start() {
        app.appScope.launch {
            when {
                !vault.exists() -> _state.value = LockState.NoDatabase
                !vault.hasPin() -> unlock(null)
                else -> {
                    hasPin = true
                    _state.value = LockState.Locked
                }
            }
        }
    }

    suspend fun create(pin: String?) = withContext(Dispatchers.IO) {
        // A stale database without a vault (e.g. after clearing part of the app data)
        // can't be opened; start clean.
        app.getDatabasePath(AppDatabase.FILE_NAME).let { db ->
            db.delete()
            File(db.path + "-wal").delete()
            File(db.path + "-shm").delete()
        }
        mediaDir().deleteRecursively()
        val dek = vault.create(pin)
        open(dek, firstRun = true)
    }

    /** False if the PIN is wrong. */
    suspend fun unlock(pin: String?): Boolean = withContext(Dispatchers.IO) {
        val dek = vault.unlock(pin) ?: return@withContext false
        open(dek, firstRun = false)
        true
    }

    private suspend fun open(dek: ByteArray, firstRun: Boolean) {
        key = dek
        hasPin = vault.hasPin()
        val db = AppDatabase.open(app, dek)
        val store = Store(db, MediaFiles(mediaDir(), dek))
        if (firstRun) store.seed(app.randomCharacterColour())
        else {
            store.deleteStaleDrafts()
            runCatching { store.cleanOrphanFiles() }
        }
        inactivityMinutes = store.settings().inactivityMinutes
        touch()
        _state.value = LockState.Unlocked(store, SystemClock.elapsedRealtimeNanos())
        app.onUnlocked(store)
        startInactivityWatch()
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) { vault.unlock(pin) != null }

    /** Add or change the PIN (re-wraps the key; no data is re-encrypted). */
    suspend fun setPin(pin: String) = withContext(Dispatchers.IO) {
        val dek = key ?: return@withContext
        vault.setPin(dek, pin)
        hasPin = true
    }

    /** Lock now: cancel running requests, close the database, forget the key. */
    fun lock() {
        if (!hasPin) return
        val unlocked = _state.value as? LockState.Unlocked ?: return
        _state.value = LockState.Locked
        inactivityJob?.cancel()
        app.appScope.launch {
            app.onLocking()
            runCatching { unlocked.store.close() }
            key?.fill(0)
            key = null
        }
    }

    fun touch() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    /** Called when the app comes back to the foreground. */
    fun checkInactivity() {
        if (!hasPin || _state.value !is LockState.Unlocked) return
        val idle = SystemClock.elapsedRealtime() - lastInteraction
        if (idle >= inactivityMinutes * 60_000L) lock()
    }

    private fun startInactivityWatch() {
        inactivityJob?.cancel()
        inactivityJob = app.appScope.launch {
            while (true) {
                delay(10_000)
                checkInactivity()
            }
        }
    }

    private fun mediaDir() = File(app.noBackupFilesDir, "media")
}
