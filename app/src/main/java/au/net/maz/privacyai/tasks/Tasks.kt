package au.net.maz.privacyai.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.net.maz.privacyai.MainActivity
import au.net.maz.privacyai.PrivacyApp
import au.net.maz.privacyai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active or pending server requests ("external tasks"). The count drives the status ring
 * and the generic foreground notification. Each task can be cancelled; the inactivity
 * lock cancels all of them.
 */
class TaskManager(private val context: Context, private val scope: CoroutineScope) {

    private class Entry(val job: Job?, val onCancel: (suspend () -> Unit)?, val startedAt: Long = System.currentTimeMillis())

    private val entries = mutableMapOf<String, Entry>()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    val count: Int get() = _ids.value.size

    /** When the longest-running current task started (wall clock ms), or null if none. */
    fun oldestStart(): Long? = synchronized(entries) { entries.values.minOfOrNull { it.startedAt } }

    /** Current task ids, longest-running first. */
    fun idsOldestFirst(): List<String> = synchronized(entries) { entries.entries.sortedBy { it.value.startedAt }.map { it.key } }

    /** Run [block] as a counted task. A task with the same id is cancelled first. */
    fun launch(id: String, block: suspend CoroutineScope.() -> Unit): Job {
        cancel(id)
        // Registered before it starts, so even an instant task is counted and removed.
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { block() }
        add(id, Entry(job, null))
        job.invokeOnCompletion { remove(id, job) }
        job.start()
        return job
    }

    /** Count something that runs elsewhere (an image generator job). */
    fun register(id: String, onCancel: suspend () -> Unit) = add(id, Entry(null, onCancel))

    fun unregister(id: String) = remove(id, null)

    fun isActive(id: String): Boolean = id in _ids.value

    fun cancel(id: String) {
        val e = synchronized(entries) { entries[id] } ?: return
        e.job?.cancel()
        e.onCancel?.let { scope.launch { it() } }
    }

    /** Cancel everything and wait (briefly) for it to wind down. */
    suspend fun cancelAll() {
        val all = synchronized(entries) { entries.toMap() }
        all.values.forEach { it.job?.cancel() }
        withTimeoutOrNull(5_000) {
            all.values.forEach { e -> e.onCancel?.let { runCatching { it() } } }
            all.values.forEach { it.job?.join() }
        }
        synchronized(entries) { entries.clear() }
        _ids.value = emptySet()
    }

    private fun add(id: String, e: Entry) {
        synchronized(entries) { entries[id] = e }
        _ids.update { it + id }
        TaskService.ensureRunning(context)
    }

    private fun remove(id: String, job: Job?) {
        synchronized(entries) {
            val cur = entries[id] ?: return
            if (job != null && cur.job !== job) return
            entries.remove(id)
        }
        _ids.update { it - id }
    }
}

/**
 * Keeps the process alive while tasks run in the background. The notification is
 * deliberately generic ("PrivacyAI: 2 tasks running") and reveals no content.
 */
class TaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val tasks = (application as PrivacyApp).tasks
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(tasks.count, tasks.oldestStart()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        scope.launch {
            tasks.ids.collect { ids ->
                if (ids.isEmpty()) {
                    running = false
                    ServiceCompat.stopForeground(this@TaskService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ids.size, tasks.oldestStart()))
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * [since]: start of the longest-running task. The system counts the elapsed time up
     * on its own (a chronometer), so the notification isn't re-posted every second.
     */
    private fun build(n: Int, since: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (n == 1) "PrivacyAI: 1 task running" else "PrivacyAI: $n tasks running"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tasks)
            .setContentTitle(text)
            .apply {
                if (since != null) {
                    setWhen(since)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
            }
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
            )
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tasks"
        private const val NOTIF_ID = 1
        @Volatile private var running = false

        fun ensureRunning(context: Context) {
            if (running) return
            running = true
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java))
            }.onFailure { running = false }
        }
    }
}
