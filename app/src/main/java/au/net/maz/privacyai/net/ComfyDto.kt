package au.net.maz.privacyai.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire DTOs for the ComfyUI HTTP API. ComfyUI's responses are loosely shaped
 * (queue entries are heterogeneous arrays, history is keyed by prompt id), so
 * several fields stay as raw Json elements and are unpacked by helpers here.
 */

/** `POST /prompt` — accepted prompt. Validation failures come back non-2xx. */
@Serializable
data class ComfyPromptResponse(
    @SerialName("prompt_id") val promptId: String,
    val number: Int = 0,
)

/** `POST /upload/image` — where ComfyUI stored an uploaded input file. */
@Serializable
data class ComfyUploadResponse(
    val name: String,
    val subfolder: String = "",
    val type: String = "input",
) {
    /** Value a LoadImage-style node input expects: `subfolder/name` or `name`. */
    val inputPath: String get() = if (subfolder.isEmpty()) name else "$subfolder/$name"
}

/**
 * `GET /queue` — each entry is a positional array `[number, prompt_id, prompt,
 * extra_data, outputs_to_execute]`; only the prompt id (index 1) matters here.
 */
@Serializable
data class ComfyQueueResponse(
    @SerialName("queue_running") val running: List<JsonArray> = emptyList(),
    @SerialName("queue_pending") val pending: List<JsonArray> = emptyList(),
) {
    val runningIds: Set<String> get() = running.ids()
    val pendingIds: Set<String> get() = pending.ids()

    private fun List<JsonArray>.ids(): Set<String> =
        mapNotNull { it.getOrNull(1)?.jsonPrimitive?.contentOrNull }.toSet()
}

/** One generated file reference inside a history `outputs` entry. */
@Serializable
data class ComfyFileRef(
    val filename: String,
    val subfolder: String = "",
    val type: String = "output",
)

/** Outcome of polling `GET /history/{promptId}`. */
sealed class ComfyHistoryResult {
    /** Prompt unknown to /history — still queued/running, or lost to a restart. */
    data object Pending : ComfyHistoryResult()

    /** Finished; [outputs] is the raw per-node outputs object. */
    data class Completed(val outputs: JsonObject) : ComfyHistoryResult()

    data class Error(val message: String) : ComfyHistoryResult()
}
