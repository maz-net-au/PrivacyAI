package au.net.maz.privacyai.llm

import au.net.maz.privacyai.data.model.BuiltIns
import au.net.maz.privacyai.data.model.Character
import au.net.maz.privacyai.data.model.Message
import au.net.maz.privacyai.data.model.Preset
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.net.ApiMessage
import au.net.maz.privacyai.net.ChatRequest
import au.net.maz.privacyai.net.apiText

/**
 * The text that goes to the LLM. Request shapes are adapted from the old app's
 * ChatRequestBuilder; anything new is marked.
 */
object Prompts {

    fun resolve(text: String, charName: String, userName: String): String =
        text.replace("{{char}}", charName).replace("{{user}}", userName.ifBlank { "User" })

    // ---- roleplay ------------------------------------------------------------------

    /** The user's name in a roleplay ("User" if blank). */
    fun userName(rp: Roleplay) = rp.yourName.ifBlank { "User" }

    /**
     * Background for a roleplay: the user's description, the summary, and every
     * character's persona and state. (How "my description" goes in was left open; this
     * puts it in its own section.)
     */
    fun roleplayBackground(rp: Roleplay): String {
        val user = userName(rp)
        return buildString {
            if (rp.myDescription.isNotBlank()) {
                appendLine("## $user (the user)")
                appendLine(resolve(rp.myDescription, user, user))
                appendLine()
            }
            if (rp.summaryBackground.isNotBlank()) {
                appendLine("## Story so far")
                appendLine(rp.summaryBackground)
                appendLine()
            }
            if (rp.characters.isNotEmpty()) {
                appendLine("## Characters")
                rp.characters.forEach { c ->
                    appendLine("### ${c.name}")
                    if (c.description.isNotBlank()) appendLine(c.description)
                    if (c.systemPrompt.isNotBlank()) appendLine(resolve(c.systemPrompt, c.name, user))
                    if (c.state.isNotBlank()) appendLine("Current state: ${c.state}")
                    appendLine()
                }
            }
        }.trim()
    }

    /** The conversation as plain "Name: text" lines (reasoning removed). */
    fun transcript(rp: Roleplay, upTo: Int = rp.messages.size): String = buildString {
        rp.messages.take(upTo).forEach { m ->
            if (m.pending || m.text.isBlank()) return@forEach
            appendLine("${speakerName(rp, m)}: ${stripThink(m.text)}")
        }
    }.trim()

    fun speakerName(rp: Roleplay, m: Message): String =
        if (m.isUser) userName(rp) else rp.character(m.speaker)?.name ?: "Narrator"

    fun roleplaySystem(rp: Roleplay): String {
        val user = userName(rp)
        val multi = rp.characters.size > 1
        return buildString {
            appendLine(
                "This is an ongoing roleplay between $user and the characters below. " +
                    "Play the characters and write their words and actions. Never write $user's lines.",
            )
            if (multi) {
                appendLine(
                    "Any of these characters may speak. Start every turn with the speaker's name " +
                        "followed by a colon (\"Name:\").",
                )
            }
            appendLine()
            append(roleplayBackground(rp))
        }.trim()
    }

    /**
     * Messages for a roleplay reply. [history] are already-built API messages (they may
     * carry images); [forcedSpeaker] makes a specific character speak next.
     */
    fun roleplayReply(
        rp: Roleplay,
        history: List<ApiMessage>,
        forcedSpeaker: Character?,
        preset: Preset,
    ): ChatRequest {
        val out = ArrayList<ApiMessage>()
        out += apiText("system", roleplaySystem(rp))
        out += history
        if (forcedSpeaker != null && rp.characters.size > 1) out += apiText("assistant", "${forcedSpeaker.name}:")
        val user = userName(rp)
        return request(
            model = rp.model,
            messages = out,
            preset = preset,
            stop = listOf("\n$user:", "$user:"),
        )
    }

    /** A character's text as sent back to the model (name prefix when several characters). */
    fun assistantText(rp: Roleplay, m: Message): String {
        val text = stripThink(m.text)
        return if (rp.characters.size > 1) "${speakerName(rp, m)}: $text" else text
    }

    /**
     * Split a reply into (speaker id, text). With several characters the reply starts with
     * "Name:"; otherwise it's the only character (or [fallback]).
     */
    fun parseSpeaker(rp: Roleplay, reply: String, fallback: String?): Pair<String, String> {
        val trimmed = reply.trimStart()
        val visible = stripThink(trimmed)
        for (c in rp.characters.sortedByDescending { it.name.length }) {
            if (c.name.isNotBlank() && visible.startsWith("${c.name}:")) {
                val idx = trimmed.indexOf("${c.name}:")
                val rest = (trimmed.substring(0, idx) + trimmed.substring(idx + c.name.length + 1).trimStart()).trimStart()
                return c.id to rest
            }
        }
        val id = fallback ?: rp.characters.firstOrNull()?.id ?: Message.ASSISTANT
        return id to trimmed
    }

    // ---- writing image prompts (from the old app's scene image feature) ------------------

    /**
     * Ask the LLM for an image prompt. Same contents as the old app: the story background,
     * every message, then the focus. Without a roleplay, just the focus.
     */
    fun imagePrompt(rp: Roleplay?, focus: String, model: String, system: String): ChatRequest {
        val user = buildString {
            if (rp != null) {
                roleplayBackground(rp).takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
                appendLine("## Recent messages")
                appendLine(transcript(rp))
                appendLine()
            }
            appendLine("Focus for the image: ${focus.ifBlank { "the current scene" }}")
            append("Describe the single image now, leading with the focus above.")
        }
        return fixed(model, system, user)
    }

    /** New: rewrite an existing prompt with a change request. */
    fun refinePrompt(rp: Roleplay?, current: String, change: String, model: String, system: String): ChatRequest {
        val user = buildString {
            if (rp != null) {
                roleplayBackground(rp).takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
                appendLine("## Recent messages")
                appendLine(transcript(rp))
                appendLine()
            }
            appendLine("## Current image description")
            appendLine(current)
            appendLine()
            appendLine("Change requested: $change")
            append("Rewrite the whole image description with that change. Output only the description.")
        }
        return fixed(model, system, user)
    }

    // ---- text fields with a Generate option ---------------------------------------------

    /**
     * New: write one character field. Uses the other fields, plus the roleplay so far when
     * the character is in a roleplay (so characters added mid-session are filled in from the
     * conversation). The "state" field follows the old app's "Update with LLM".
     */
    fun characterField(field: String, c: Character, rp: Roleplay?, model: String, hint: String): ChatRequest {
        val user = rp?.let(::userName) ?: "the user"
        val sheet = buildString {
            if (c.name.isNotBlank()) appendLine("Name: ${c.name}")
            if (c.description.isNotBlank() && field != "description") appendLine("Short description: ${c.description}")
            if (c.systemPrompt.isNotBlank() && field != "persona") appendLine("Persona: ${c.systemPrompt}")
            if (c.greeting.isNotBlank() && field != "greeting") appendLine("Greeting: ${c.greeting}")
            if (c.state.isNotBlank()) appendLine("${if (field == "state") "Previous state" else "Current state"}: ${c.state}")
        }.trim()
        val ask = when (field) {
            "name" -> "Suggest a fitting name for this character. Output only the name."
            "description" -> "Write a one-line description of this character (under 20 words). Output only the description."
            "greeting" -> "Write this character's opening message to $user, in character, as the first line of a roleplay. Use {{user}} for the user's name. Output only the message."
            "persona" -> "Write this character's persona for a roleplay system prompt: personality, appearance, manner of speaking, background and goals, in second person (\"You are...\"). Use {{char}} and {{user}} for the names. Output only the persona."
            "state" -> "Rewrite this character's current state from the conversation so far: appearance and clothing, physical condition, location, possessions, relationships and feelings toward others. Keep what still applies, change what changed. Be concise. Output only the state notes."
            else -> "Write the $field. Output only the text."
        }
        val userTurn = buildString {
            if (rp != null) {
                roleplayBackground(rp).takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
                transcript(rp).takeIf { it.isNotBlank() }?.let {
                    appendLine("## Conversation so far"); appendLine(it); appendLine()
                }
            }
            if (sheet.isNotBlank()) { appendLine("## Character sheet"); appendLine(sheet); appendLine() }
            if (hint.isNotBlank()) { appendLine("Guidance from the user: $hint"); appendLine() }
            append(ask)
        }
        return fixed(
            model,
            "You help write characters for roleplays. Follow the request exactly and output only what is asked for, with no preamble.",
            userTurn,
            maxTokens = 1200,
        )
    }

    /** New: generate "my description" for the user in a roleplay. */
    fun myDescription(rp: Roleplay, model: String, hint: String): ChatRequest {
        val user = userName(rp)
        val turn = buildString {
            roleplayBackground(rp.copy(myDescription = "")).takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            transcript(rp).takeIf { it.isNotBlank() }?.let { appendLine("## Conversation so far"); appendLine(it); appendLine() }
            if (hint.isNotBlank()) { appendLine("Guidance: $hint"); appendLine() }
            append("Write a short description of $user, the user's character in this roleplay: appearance, personality and role. Output only the description.")
        }
        return fixed(rp.model, "You help write roleplays. Output only what is asked for.", turn, maxTokens = 600)
            .copy(model = model)
    }

    /** Summary / background, generated from the conversation (old app's summarise step). */
    fun summary(rp: Roleplay, model: String, hint: String): ChatRequest {
        val turn = buildString {
            if (rp.summaryBackground.isNotBlank()) {
                appendLine("## Summary so far"); appendLine(rp.summaryBackground); appendLine()
            }
            transcript(rp).takeIf { it.isNotBlank() }?.let { appendLine("## Conversation"); appendLine(it); appendLine() }
            if (hint.isNotBlank()) { appendLine("Guidance: $hint"); appendLine() }
            append(
                "Write a concise summary of the story so far: key events, where things stand now, " +
                    "and anything that must be remembered. Output only the summary.",
            )
        }
        return fixed(model, "You summarise roleplay stories accurately and concisely.", turn, maxTokens = 1200)
    }

    /** A plain one-shot request with the image-prompt sampling. */
    fun fixed(model: String, system: String, user: String, maxTokens: Int = BuiltIns.IMAGE_PROMPT_MAX_TOKENS): ChatRequest {
        val p = BuiltIns.imagePromptSampling
        return ChatRequest(
            model = model,
            messages = listOf(apiText("system", system), apiText("user", user)),
            stream = true,
            maxTokens = maxTokens,
            temperature = p.temperature,
            topP = p.topP,
            topK = p.topK,
            repeatPenalty = p.repeatPenalty,
        )
    }

    fun request(model: String, messages: List<ApiMessage>, preset: Preset, stop: List<String>? = null) = ChatRequest(
        model = model,
        messages = messages,
        stream = true,
        stop = stop,
        temperature = preset.temperature,
        topP = preset.topP,
        topK = preset.topK,
        minP = preset.minP,
        typicalP = preset.typicalP,
        repeatPenalty = preset.repeatPenalty,
        repeatLastN = preset.repeatLastN,
        presencePenalty = preset.presencePenalty,
        frequencyPenalty = preset.frequencyPenalty,
        mirostat = preset.mirostat,
        mirostatTau = preset.mirostatTau,
        mirostatEta = preset.mirostatEta,
    )
}
