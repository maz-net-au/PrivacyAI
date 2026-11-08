package au.net.maz.privacyai.data.model

import kotlinx.serialization.Serializable

/**
 * Sampling parameters (from the old app). Every field is optional: only the ones that are
 * set are sent, so unset values fall back to llama-server's own defaults.
 */
@Serializable
data class Preset(
    val name: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val typicalP: Double? = null,
    val repeatPenalty: Double? = null,
    val repeatLastN: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val mirostat: Int? = null,
    val mirostatTau: Double? = null,
    val mirostatEta: Double? = null,
) {
    fun withOverrides(o: SamplingOverrides): Preset = copy(
        temperature = o.temperature ?: temperature,
        topP = o.topP ?: topP,
        topK = o.topK ?: topK,
        minP = o.minP ?: minP,
        typicalP = o.typicalP ?: typicalP,
        repeatPenalty = o.repeatPenalty ?: repeatPenalty,
        repeatLastN = o.repeatLastN ?: repeatLastN,
        presencePenalty = o.presencePenalty ?: presencePenalty,
        frequencyPenalty = o.frequencyPenalty ?: frequencyPenalty,
        mirostat = o.mirostat ?: mirostat,
        mirostatTau = o.mirostatTau ?: mirostatTau,
        mirostatEta = o.mirostatEta ?: mirostatEta,
    )

    fun summary(): String = SamplingParam.entries
        .mapNotNull { p -> p.fromPreset(this)?.let { "${p.short} ${formatSampling(it)}" } }
        .joinToString(" · ")
}

/** Per-chat tweaks layered on top of a preset. Null = inherit. */
@Serializable
data class SamplingOverrides(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val typicalP: Double? = null,
    val repeatPenalty: Double? = null,
    val repeatLastN: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val mirostat: Int? = null,
    val mirostatTau: Double? = null,
    val mirostatEta: Double? = null,
)

/** The tunable fields, so editors don't need 12-way boilerplate. */
enum class SamplingParam(
    val label: String,
    val short: String,
    val isInt: Boolean,
    val fromPreset: (Preset) -> Number?,
    val fromOverrides: (SamplingOverrides) -> Number?,
) {
    TEMPERATURE("Temperature", "temp", false, { it.temperature }, { it.temperature }),
    TOP_P("Top P", "top_p", false, { it.topP }, { it.topP }),
    TOP_K("Top K", "top_k", true, { it.topK }, { it.topK }),
    MIN_P("Min P", "min_p", false, { it.minP }, { it.minP }),
    TYPICAL_P("Typical P", "typ_p", false, { it.typicalP }, { it.typicalP }),
    REPEAT_PENALTY("Repeat penalty", "rep_pen", false, { it.repeatPenalty }, { it.repeatPenalty }),
    REPEAT_LAST_N("Repeat last N", "rep_n", true, { it.repeatLastN }, { it.repeatLastN }),
    PRESENCE_PENALTY("Presence penalty", "presence", false, { it.presencePenalty }, { it.presencePenalty }),
    FREQUENCY_PENALTY("Frequency penalty", "frequency", false, { it.frequencyPenalty }, { it.frequencyPenalty }),
    MIROSTAT("Mirostat (0/1/2)", "mirostat", true, { it.mirostat }, { it.mirostat }),
    MIROSTAT_TAU("Mirostat tau", "miro_tau", false, { it.mirostatTau }, { it.mirostatTau }),
    MIROSTAT_ETA("Mirostat eta", "miro_eta", false, { it.mirostatEta }, { it.mirostatEta });
}

fun formatSampling(n: Number): String =
    if (n is Double && n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

/** Text-editor values -> overrides (blank/unparseable entries dropped). */
fun overridesFrom(text: Map<SamplingParam, String>): SamplingOverrides {
    fun d(p: SamplingParam) = text[p]?.trim()?.toDoubleOrNull()
    fun i(p: SamplingParam) = text[p]?.trim()?.toIntOrNull()
    return SamplingOverrides(
        temperature = d(SamplingParam.TEMPERATURE),
        topP = d(SamplingParam.TOP_P),
        topK = i(SamplingParam.TOP_K),
        minP = d(SamplingParam.MIN_P),
        typicalP = d(SamplingParam.TYPICAL_P),
        repeatPenalty = d(SamplingParam.REPEAT_PENALTY),
        repeatLastN = i(SamplingParam.REPEAT_LAST_N),
        presencePenalty = d(SamplingParam.PRESENCE_PENALTY),
        frequencyPenalty = d(SamplingParam.FREQUENCY_PENALTY),
        mirostat = i(SamplingParam.MIROSTAT),
        mirostatTau = d(SamplingParam.MIROSTAT_TAU),
        mirostatEta = d(SamplingParam.MIROSTAT_ETA),
    )
}

/** Same, building a preset. */
fun presetFrom(name: String, text: Map<SamplingParam, String>): Preset {
    val o = overridesFrom(text)
    return Preset(name).withOverrides(o)
}

fun textFrom(o: SamplingOverrides): Map<SamplingParam, String> =
    SamplingParam.entries.mapNotNull { p -> p.fromOverrides(o)?.let { p to formatSampling(it) } }.toMap()

fun textFrom(p: Preset): Map<SamplingParam, String> =
    SamplingParam.entries.mapNotNull { s -> s.fromPreset(p)?.let { s to formatSampling(it) } }.toMap()

object BuiltIns {
    /** The old app's presets, used to seed a new database. */
    val presets: List<Preset> = listOf(
        Preset("Default", temperature = 0.7, topP = 0.9, topK = 20, repeatPenalty = 1.15),
        Preset("min_p_v5", temperature = 1.26, minP = 0.08, repeatPenalty = 1.05),
        Preset("Precise", temperature = 0.1, topP = 0.1, topK = 40, repeatPenalty = 1.18),
        Preset("Creative", temperature = 1.1, topP = 0.95, topK = 100, repeatPenalty = 1.10),
        Preset("Llama-Precise", temperature = 0.7, topP = 0.1, topK = 40, repeatPenalty = 1.18),
        Preset("Midnight Enigma", temperature = 0.98, topP = 0.37, topK = 100, repeatPenalty = 1.18),
    )

    /** Fixed sampling for writing image prompts, as in the old app (SceneImageConfig). */
    val imagePromptSampling = Preset("ImagePrompt", temperature = 0.7, topP = 0.9, topK = 40, repeatPenalty = 1.05)
    const val IMAGE_PROMPT_MAX_TOKENS = 500

    /** The utility chat's starting character. */
    fun assistant(colour: Int) = Character(
        name = "Assistant",
        description = "Helpful, concise general assistant.",
        systemPrompt = "You are a helpful, concise assistant.",
        colour = colour,
    )
}
