package au.net.maz.privacyai.comfy

import android.util.Log
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.db.JobEntity
import au.net.maz.privacyai.data.model.FileValue
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.net.ComfyClient
import au.net.maz.privacyai.net.ComfyFileRef
import au.net.maz.privacyai.net.ComfyHistoryResult
import au.net.maz.privacyai.tasks.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An image-generator job. Saved in the database so it survives the app being killed. */
@Serializable
data class GenJob(
    val id: String = Ids.new("job_"),
    /** The placeholder media item the result goes into. */
    val mediaId: String,
    val outputNodeId: String,
    val outputField: String,
    /** The patched graph, until it has been submitted. */
    val graph: JsonObject? = null,
    /** File inputs still to upload: which media goes into which node input. */
    val fileInputs: List<FileValue> = emptyList(),
    /** Titles of nodes behind empty optional file slots, to remove before submitting. */
    val emptySlots: List<String> = emptyList(),
    val promptId: String? = null,
    val running: Boolean = false,
    /** When the app first saw it running (fallback timing if the server doesn't report it). */
    val startedAt: Long? = null,
)

/**
 * Drives image-generator jobs (adapted from the old app's ComfyGenerationService):
 * uploads inputs, submits, polls the queue/history, and stores the results encrypted
 * into the job's placeholder media item. Each job counts as a task on the status ring.
 */
class ComfyRunner(
    private val scope: CoroutineScope,
    private val store: Store,
    private val client: ComfyClient,
    private val tasks: TaskManager,
) {
    private val clientId = Ids.new()
    private val mutex = Mutex()
    private var loop: Job? = null
    private val misses = mutableMapOf<String, Int>()
    private val requiredCache = mutableMapOf<String, Set<String>?>()

    /** Queue a job (its placeholder media must already exist). */
    suspend fun submit(job: GenJob) {
        save(job)
        track(job.id)
        ensureLoop()
    }

    /** After unlocking: pick up jobs that were running when the app was killed or locked. */
    suspend fun resume() {
        val jobs = all()
        jobs.forEach { track(it.id) }
        if (jobs.isNotEmpty()) ensureLoop()
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Cancel the job producing [mediaId], if any. */
    suspend fun cancelForMedia(mediaId: String) {
        all().filter { it.mediaId == mediaId }.forEach { tasks.cancel(it.id) }
    }

    private fun track(id: String) = tasks.register(id) { cancel(id) }

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                val jobs = all()
                if (jobs.isEmpty()) break
                val address = store.settings().imageAddress
                if (address.isBlank()) {
                    jobs.forEach { fail(it, "No image generator address is set (Settings)") }
                    break
                }
                runCatching { tick(jobs, address) }.onFailure { Log.w(TAG, "tick failed", it) }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun tick(jobs: List<GenJob>, address: String) {
        jobs.filter { it.promptId == null }.forEach { submitNow(it, address) }
        val queue = client.queue(address).getOrNull() ?: return
        for (job in all()) {
            val pid = job.promptId ?: continue
            when (pid) {
                in queue.runningIds -> {
                    misses.remove(job.id)
                    if (!job.running) save(job.copy(running = true, startedAt = System.currentTimeMillis()))
                }
                in queue.pendingIds -> misses.remove(job.id)
                else -> checkHistory(job, pid, address)
            }
        }
    }

    private suspend fun submitNow(job: GenJob, address: String) {
        var graph = job.graph ?: return fail(job, "Submission data went missing")
        job.fileInputs.forEachIndexed { i, input ->
            val media = store.media(input.mediaId)
            val fileId = media?.fileId
            if (media == null || fileId == null) return fail(job, "An input image no longer exists")
            val ext = extFor(media.mime)
            val uploaded = client.uploadInput(address, media.mime.ifBlank { "image/png" }, "${job.id}_$i.$ext") {
                store.files.openInput(fileId)
            }.getOrElse { return fail(job, it.message ?: "Upload failed") }
            graph = WorkflowPatcher.patch(
                graph, listOf(PatchOp(input.nodeTitle, input.input, JsonPrimitive(uploaded.inputPath))),
            ).getOrElse { return fail(job, it.message ?: "Patch failed") }
        }
        if (job.emptySlots.isNotEmpty()) {
            graph = WorkflowPatcher.pruneEmptySlots(graph, job.emptySlots, job.outputNodeId) { cls ->
                requiredCache.getOrPut(cls) { client.requiredInputs(address, cls) }
            }.getOrElse { return fail(job, it.message ?: "Could not remove the empty slots") }
        }
        if (load(job.id) == null) return // cancelled meanwhile
        val resp = client.queuePrompt(address, graph, clientId)
            .getOrElse { return fail(job, it.message ?: "Submit failed") }
        save(job.copy(promptId = resp.promptId, graph = null))
    }

    private suspend fun checkHistory(job: GenJob, promptId: String, address: String) {
        when (val h = client.history(address, promptId).getOrNull() ?: return) {
            is ComfyHistoryResult.Completed -> {
                misses.remove(job.id)
                download(job, h.outputs, address, h.durationMs ?: fallbackMs(job))
            }
            is ComfyHistoryResult.Error -> fail(job, h.message, h.durationMs ?: fallbackMs(job))
            ComfyHistoryResult.Pending -> {
                val n = (misses[job.id] ?: 0) + 1
                misses[job.id] = n
                if (n >= MAX_MISSES) fail(job, "Job lost. The image generator may have restarted.")
            }
        }
    }

    /** First output fills the placeholder; any extra outputs become new items beside it. */
    /** Time since the app first saw the job running; accurate to the poll interval. */
    private fun fallbackMs(job: GenJob): Long? = job.startedAt?.let { System.currentTimeMillis() - it }

    private suspend fun download(job: GenJob, outputs: JsonObject, address: String, durationMs: Long?) {
        val refs = (outputs[job.outputNodeId]?.jsonObject?.get(job.outputField) as? JsonArray)
            ?.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["filename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ComfyFileRef(
                    filename = name,
                    subfolder = o["subfolder"]?.jsonPrimitive?.contentOrNull ?: "",
                    type = o["type"]?.jsonPrimitive?.contentOrNull ?: "output",
                )
            }
        if (refs.isNullOrEmpty()) return fail(job, "The workflow produced no output")
        val placeholder = store.media(job.mediaId)
        if (placeholder == null) { // deleted while generating
            finish(job.id)
            return
        }
        refs.forEachIndexed { i, ref ->
            val fileId = store.files.newFileId()
            var failure: Throwable? = null
            withContext(Dispatchers.IO) {
                runCatching {
                    store.files.write(fileId) { out ->
                        client.download(address, ref, out).getOrElse { failure = it; throw it }
                    }
                }.onFailure { failure = failure ?: it }
            }
            failure?.let { return fail(job, it.message ?: "Download failed") }
            val mime = mimeFor(ref.filename)
            val done = placeholder.copy(
                fileId = fileId, mime = mime, kind = MediaKind.fromMime(mime), status = MediaStatus.DONE,
                info = placeholder.info.copy(generationMs = durationMs),
            )
            if (i == 0) {
                // Still there? (It may have been deleted while downloading.)
                if (store.media(job.mediaId) == null) store.files.delete(fileId) else store.putMedia(done)
            } else {
                store.putMedia(done.copy(id = Ids.new("media_"), createdAt = System.currentTimeMillis()))
            }
        }
        finish(job.id)
    }

    /** Cancel wherever it is: dropped locally, removed from the server queue, or interrupted. */
    private suspend fun cancel(id: String) {
        val job = load(id) ?: return
        val pid = job.promptId
        if (pid != null) {
            val address = runCatching { store.settings().imageAddress }.getOrDefault("")
            if (address.isNotBlank()) {
                val q = client.queue(address).getOrNull()
                when {
                    q == null -> Unit
                    pid in q.pendingIds -> client.deleteQueued(address, pid)
                    pid in q.runningIds -> client.interrupt(address)
                }
            }
        }
        fail(job, "Cancelled")
    }

    private suspend fun fail(job: GenJob, message: String, durationMs: Long? = null) {
        runCatching {
            store.updateMedia(job.mediaId) { m: Media ->
                m.copy(status = MediaStatus.FAILED, info = m.info.copy(error = message.take(500), generationMs = durationMs))
            }
        }
        finish(job.id)
    }

    private suspend fun finish(id: String) {
        misses.remove(id)
        runCatching { mutex.withLock { store.db.jobs().delete(id) } }
        tasks.unregister(id)
    }

    private suspend fun save(job: GenJob) = mutex.withLock {
        store.db.jobs().put(JobEntity(job.id, store.json.encodeToString(job)))
    }

    private suspend fun load(id: String): GenJob? =
        store.db.jobs().get(id)?.let { runCatching { store.json.decodeFromString<GenJob>(it.json) }.getOrNull() }

    private suspend fun all(): List<GenJob> =
        runCatching {
            store.db.jobs().all().mapNotNull { runCatching { store.json.decodeFromString<GenJob>(it.json) }.getOrNull() }
        }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "ComfyRunner"
        private const val POLL_MS = 2_000L
        private const val MAX_MISSES = 3

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }

        fun extFor(mime: String): String = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/flac" -> "flac"
            "audio/ogg" -> "ogg"
            else -> mime.substringAfter('/', "bin")
        }
    }
}
