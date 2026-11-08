package au.net.maz.privacyai.llm

/**
 * Reasoning block delimiters to strip. Mirrors `THINK_TAGS` in the UI's `parseThink`
 * (ui/chat/ThinkingSection.kt): `<think>…</think>` for most models, plus Gemma 4's
 * `<|channel>thought … <channel|>` thought channel.
 */
private val THINK_BLOCKS = listOf(
    "<think>" to "</think>",
    "<|channel>thought" to "<channel|>",
)

/** Contentless markers to drop wherever they appear (e.g. Gemma's `<|think|>`). */
private val THINK_NOISE = listOf("<|think|>")

/**
 * Whether [text] carries any reasoning tag at all — a cheap guard so callers can skip
 * [stripThink] (and its whitespace collapsing) for the common case of plain text.
 */
fun containsThink(text: String): Boolean =
    THINK_BLOCKS.any { text.contains(it.first) } || THINK_NOISE.any { text.contains(it) }

/**
 * Remove reasoning blocks (see [THINK_BLOCKS]) from a stored message so only the
 * visible answer remains. Mirrors the answer-extraction in the UI's `parseThink`
 * (ui/chat/ThinkingSection.kt), but as a plain string→string helper usable from the
 * data layer — for the text handed to the summarizer and for permanently cleaning
 * messages as they're locked.
 *
 * Handles multiple blocks and a dangling, still-open block (everything after it is
 * dropped). The leftover run of spaces/tabs where a block used to sit is collapsed
 * so a `"Name: <think>…</think> reply"` becomes `"Name: reply"`; the leading `Name:`
 * prefix and any internal newlines in the answer are preserved.
 */
fun stripThink(text: String): String {
    var out = text
    for ((open, close) in THINK_BLOCKS) {
        val o = Regex.escape(open)
        val c = Regex.escape(close)
        out = Regex("(?s)$o.*?$c").replace(out, "")
        val dangling = out.indexOf(open)
        if (dangling >= 0) out = out.substring(0, dangling)
    }
    for (marker in THINK_NOISE) out = out.replace(marker, "")
    // Collapse only horizontal whitespace runs (leave newlines/formatting intact),
    // then trim the ends left bare by a removed block.
    return out.replace(Regex("[ \\t]{2,}"), " ").trim()
}
