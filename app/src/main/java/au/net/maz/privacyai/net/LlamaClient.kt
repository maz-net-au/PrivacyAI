package au.net.maz.privacyai.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/** "host:port" (or a full http:// URL) -> base URL without a trailing slash. */
fun baseUrl(address: String): String {
    val a = address.trim().trimEnd('/')
    return if (a.startsWith("http://") || a.startsWith("https://")) a else "http://$a"
}

/**
 * llama-server over its OpenAI-compatible API (adapted from the old app's LlamaClient).
 * [streamChat] streams tokens via SSE; cancelling collection cancels the request.
 */
class LlamaClient {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun listModels(address: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${baseUrl(address)}/v1/models").get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Server returned HTTP ${resp.code}")
                json.decodeFromString<ModelsResponse>(resp.body?.string().orEmpty()).data.map { it.id }
            }
        }
    }

    /**
     * The model's context size from /props. `?model=` lets a multi-model router answer for
     * the right model (from the old app's fetchContextSize). Null if unknown.
     */
    suspend fun contextSize(address: String, model: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl(address)}/props" + if (model.isBlank()) "" else "?model=${java.net.URLEncoder.encode(model, "UTF-8")}"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<PropsResponse>(resp.body?.string().orEmpty()).contextSize?.takeIf { it > 0 }
            }
        }.onFailure { Log.w("LlamaClient", "contextSize failed: ${it.message}") }.getOrNull()
    }

    /**
     * Stream a chat completion as text deltas. Reasoning is folded into `<think>` tags.
     * [onUsage] gets the total tokens in context after the reply, if the server reports it.
     */
    fun streamChat(address: String, body: ChatRequest, onUsage: (Int) -> Unit = {}): Flow<String> = callbackFlow {
        val request = Request.Builder()
            .url("${baseUrl(address)}/v1/chat/completions")
            .post(json.encodeToString(body).toRequestBody(jsonMedia))
            .header("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            private var thinkOpen = false

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    finishThink(); close(); return
                }
                val chunk = runCatching { json.decodeFromString<ChatChunk>(data) }.getOrNull() ?: return
                (chunk.usage?.total?.takeIf { it > 0 } ?: chunk.timings?.total?.takeIf { it > 0 })?.let(onUsage)
                val delta = chunk.choices.firstOrNull()?.delta ?: return
                val out = StringBuilder()
                if (!delta.reasoningContent.isNullOrEmpty()) {
                    if (!thinkOpen) { out.append("<think>"); thinkOpen = true }
                    out.append(delta.reasoningContent)
                }
                if (!delta.content.isNullOrEmpty()) {
                    if (thinkOpen) { out.append("</think>"); thinkOpen = false }
                    out.append(delta.content)
                }
                if (out.isNotEmpty()) trySend(out.toString())
            }

            private fun finishThink() {
                if (thinkOpen) { trySend("</think>"); thinkOpen = false }
            }

            override fun onClosed(eventSource: EventSource) {
                finishThink(); close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val httpError = response?.let { r ->
                    val body = runCatching { r.body?.string() }.getOrNull().orEmpty().take(500)
                    "HTTP ${r.code}" + if (body.isBlank()) "" else ": $body"
                }
                val message = httpError ?: t?.let { it.message ?: it.javaClass.simpleName } ?: "Connection failed"
                Log.w("LlamaClient", "streamChat failed: $message")
                close(IOException(message))
            }
        }
        val es = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { es.cancel() }
    }
}
