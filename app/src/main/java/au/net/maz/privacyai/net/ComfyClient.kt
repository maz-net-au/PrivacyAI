package au.net.maz.privacyai.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The ComfyUI HTTP API (adapted from the old app's ComfyClient). Uploads stream from a
 * decrypting stream and downloads stream into an encrypting one, so no plaintext copy of
 * a media file is ever written to disk.
 */
class ComfyClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Upload an input file into ComfyUI's input directory. */
    suspend fun uploadInput(
        address: String,
        mime: String,
        uploadName: String,
        open: () -> InputStream,
    ): Result<ComfyUploadResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val streamBody = object : RequestBody() {
                override fun contentType(): MediaType = mime.toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    open().use { sink.writeAll(it.source()) }
                }
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", uploadName, streamBody)
                .addFormDataPart("overwrite", "true")
                .build()
            val request = Request.Builder().url("${baseUrl(address)}/upload/image").post(body).build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Upload failed: HTTP ${resp.code}: ${respBody.take(500)}")
                json.decodeFromString<ComfyUploadResponse>(respBody)
            }
        }
    }

    suspend fun queuePrompt(address: String, graph: JsonObject, clientId: String): Result<ComfyPromptResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("prompt", graph)
                    put("client_id", clientId)
                }
                val request = Request.Builder()
                    .url("${baseUrl(address)}/prompt")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${respBody.take(1000)}")
                    json.decodeFromString<ComfyPromptResponse>(respBody)
                }
            }
        }

    suspend fun queue(address: String): Result<ComfyQueueResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${baseUrl(address)}/queue").get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<ComfyQueueResponse>(resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun history(address: String, promptId: String): Result<ComfyHistoryResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl(address)}/history/${URLEncoder.encode(promptId, "UTF-8")}")
                    .get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    parseHistory(json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject, promptId)
                }
            }
        }

    /**
     * Execution time from the history's status messages: a list of `[event, {timestamp}]`
     * where timestamps are server milliseconds. From `execution_start` to whichever of
     * success / error / interrupted came last. Null if they're missing.
     */
    private fun executionMs(status: JsonObject?): Long? = runCatching {
        val messages = status?.get("messages") as? JsonArray ?: return null
        var start: Long? = null
        var end: Long? = null
        for (m in messages) {
            val pair = m as? JsonArray ?: continue
            val event = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
            val ts = (pair.getOrNull(1) as? JsonObject)?.get("timestamp")?.jsonPrimitive?.longOrNull ?: continue
            when (event) {
                "execution_start" -> start = ts
                "execution_success", "execution_error", "execution_interrupted" -> end = ts
            }
        }
        val s = start ?: return null
        val e = end ?: return null
        (e - s).takeIf { it >= 0 }
    }.getOrNull()

    private fun parseHistory(root: JsonObject, promptId: String): ComfyHistoryResult {
        val entry = root[promptId] as? JsonObject ?: return ComfyHistoryResult.Pending
        val status = entry["status"] as? JsonObject
        val statusStr = status?.get("status_str")?.jsonPrimitive?.contentOrNull
        val completed = runCatching { status?.get("completed")?.jsonPrimitive?.boolean }.getOrNull()
        val outputs = entry["outputs"] as? JsonObject
        val duration = executionMs(status)
        return when {
            statusStr == "error" -> {
                val detail = status?.get("messages")?.toString()?.take(500)
                ComfyHistoryResult.Error("ComfyUI execution failed${detail?.let { ": $it" } ?: ""}", duration)
            }
            completed == true || (status == null && outputs != null) ->
                ComfyHistoryResult.Completed(outputs ?: JsonObject(emptyMap()), duration)
            else -> ComfyHistoryResult.Pending
        }
    }

    /** Stream a generated file from /view into [out]. Blocking: call on an IO thread. */
    fun download(address: String, ref: ComfyFileRef, out: OutputStream): Result<String> =
            runCatching {
                fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
                val url = "${baseUrl(address)}/view" +
                    "?filename=${enc(ref.filename)}&subfolder=${enc(ref.subfolder)}&type=${enc(ref.type)}"
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                    val body = resp.body ?: error("Download failed: empty body")
                    body.byteStream().copyTo(out)
                    body.contentType()?.toString().orEmpty()
                }
            }

    /**
     * `GET /object_info/{class}`: which inputs a node type requires. Used to decide what to
     * remove when an optional reference-image slot is left empty. Null if unknown.
     */
    suspend fun requiredInputs(address: String, classType: String): Set<String>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl(address)}/object_info/${URLEncoder.encode(classType, "UTF-8").replace("+", "%20")}"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val info = root[classType]?.jsonObject ?: error("unknown class")
                info["input"]?.jsonObject?.get("required")?.jsonObject?.keys ?: emptySet()
            }
        }.onFailure { Log.w(TAG, "object_info $classType failed: ${it.message}") }.getOrNull()
    }

    suspend fun interrupt(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${baseUrl(address)}/interrupt")
                .post(ByteArray(0).toRequestBody(null)).build()
            client.newCall(request).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
        }
    }

    suspend fun deleteQueued(address: String, promptId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("delete", buildJsonArray { add(promptId) }) }
            val request = Request.Builder().url("${baseUrl(address)}/queue")
                .post(payload.toString().toRequestBody(jsonMedia)).build()
            client.newCall(request).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
        }
    }

    private companion object {
        const val TAG = "ComfyClient"
    }
}
