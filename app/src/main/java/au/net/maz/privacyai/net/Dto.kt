package au.net.maz.privacyai.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Wire DTOs for llama-server's OpenAI-compatible API. The sampler fields beyond
 * `temperature` / `top_p` are llama.cpp extensions to the OpenAI chat body, which
 * llama-server accepts. Every sampler is nullable: with the client's
 * `explicitNulls = false`, unset values are omitted entirely so the server
 * applies its own defaults (this is how sparse presets work).
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    /** Sequences that halt generation, e.g. the next speaker's "Name:" prefix. */
    val stop: List<String>? = null,
    /** Token cap; bounds a "Continue" / "Impersonate" so it can't run away. */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** When true the server keeps generating past an EOS token (llama.cpp extension). */
    @EncodeDefault
    @SerialName("ignore_eos") val ignoreEos: Boolean = false,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("min_p") val minP: Double? = null,
    @SerialName("typical_p") val typicalP: Double? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
    @SerialName("repeat_last_n") val repeatLastN: Int? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    val mirostat: Int? = null,
    @SerialName("mirostat_tau") val mirostatTau: Double? = null,
    @SerialName("mirostat_eta") val mirostatEta: Double? = null,
    /** Ask for token usage in the last streamed chunk (it counts image tokens too). */
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

/**
 * OpenAI-compatible message. [content] is either a plain JSON string (text-only —
 * the wire format is byte-identical to the old `content: String`) or an array of
 * typed parts for multimodal turns. The DTO is encode-only, so a [JsonElement]
 * beats polymorphic-serializer machinery. Build via [apiText] / [apiParts].
 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: JsonElement,
)

/** Text-only message: `content` encodes as a plain JSON string. */
fun apiText(role: String, text: String): ApiMessage = ApiMessage(role, JsonPrimitive(text))

/** Multimodal message: `content` encodes as an array of typed parts. */
fun apiParts(role: String, parts: List<JsonElement>): ApiMessage =
    ApiMessage(role, buildJsonArray { parts.forEach { add(it) } })

// The `type` discriminators below carry @EncodeDefault: the parts are pre-encoded
// to JsonElement (AttachmentStore / ChatRequestBuilder) with a default Json whose
// encodeDefaults = false, which would otherwise silently drop them — and
// llama-server 400s a content part with no "type".
@Serializable
data class TextPart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault val type: String = "text",
    val text: String,
)

@Serializable
data class ImageUrl(val url: String)

/** Image content part; [imageUrl].url carries a `data:<mime>;base64,…` URI. */
@Serializable
data class ImageUrlPart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault val type: String = "image_url",
    @SerialName("image_url") val imageUrl: ImageUrl,
)

@Serializable
data class InputAudio(
    /** Base64 of the file bytes (no data-URI prefix). */
    val data: String,
    /** "wav" or "mp3" — the only formats llama-server accepts. */
    val format: String,
)

@Serializable
data class InputAudioPart(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault val type: String = "input_audio",
    @SerialName("input_audio") val inputAudio: InputAudio,
)

/** A streamed chunk: choices[].delta.content carries the incremental text. */
@Serializable
data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
    /** llama.cpp's own counters, in case [usage] isn't sent. */
    val timings: Timings? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
) {
    val total: Int get() = if (totalTokens > 0) totalTokens else promptTokens + completionTokens
}

@Serializable
data class Timings(
    /** Prompt tokens reused from the cache (not counted in [promptN]). */
    @SerialName("cache_n") val cacheN: Int = 0,
    @SerialName("prompt_n") val promptN: Int = 0,
    @SerialName("predicted_n") val predictedN: Int = 0,
) {
    val total: Int get() = cacheN + promptN + predictedN
}

@Serializable
data class ChunkChoice(
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val content: String? = null,
    /** Chain-of-thought, streamed separately when the server does its own reasoning
     *  parsing (llama.cpp with a `reasoning_format` set strips the `<think>` tags from
     *  [content] and emits the thoughts here). The client folds it back into inline
     *  `<think>…</think>` so the rest of the app — which stores/renders reasoning that
     *  way — sees a single consistent shape regardless of server config. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ModelsResponse(
    val data: List<ModelEntry> = emptyList(),
)

/**
 * llama-server's `/props`. The context window is reported under
 * `default_generation_settings.n_ctx`; older builds expose it at the top level, so
 * both spots are parsed and the first present one wins.
 */
@Serializable
data class PropsResponse(
    @SerialName("default_generation_settings") val defaultGenerationSettings: GenerationSettings? = null,
    @SerialName("n_ctx") val nCtx: Int? = null,
) {
    val contextSize: Int? get() = defaultGenerationSettings?.nCtx ?: nCtx
}

@Serializable
data class GenerationSettings(
    @SerialName("n_ctx") val nCtx: Int? = null,
)

/** Request/response for llama-server's `/tokenize`, used to count how many tokens
 *  the current transcript occupies after a reply finishes. [model] is required by
 *  multi-model routers (e.g. llama-swap) to pick the backend; plain llama-server
 *  ignores it. */
@Serializable
data class TokenizeRequest(
    val content: String,
    val model: String? = null,
)

@Serializable
data class TokenizeResponse(
    val tokens: List<Int> = emptyList(),
)

@Serializable
data class ModelEntry(
    val id: String,
)
