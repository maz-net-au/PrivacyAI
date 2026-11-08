package au.net.maz.privacyai.llm

import android.util.Base64
import au.net.maz.privacyai.PrivacyApp
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.model.Attachment
import au.net.maz.privacyai.data.model.Character
import au.net.maz.privacyai.data.model.ContextUsage
import au.net.maz.privacyai.data.model.Message
import au.net.maz.privacyai.data.model.ModelCapabilities
import au.net.maz.privacyai.net.ApiMessage
import au.net.maz.privacyai.net.ChatRequest
import au.net.maz.privacyai.net.ImageUrl
import au.net.maz.privacyai.net.ImageUrlPart
import au.net.maz.privacyai.net.TextPart
import au.net.maz.privacyai.net.apiParts
import au.net.maz.privacyai.net.apiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Runs LLM requests as background tasks (they keep going if you leave the screen, count
 * on the status ring, and are cancelled by the inactivity lock). Replies stream straight
 * into the stored conversation.
 */
class ChatEngine(private val app: PrivacyApp) {

    private val store: Store get() = app.session.store ?: throw CancellationException("Locked")

    fun replyTaskId(conversationId: String) = "reply:$conversationId"

    fun isReplying(conversationId: String) = app.tasks.isActive(replyTaskId(conversationId))

    fun stop(conversationId: String) = app.tasks.cancel(replyTaskId(conversationId))

    /** Model to use when none is chosen: the utility chat model, else the server's first. */
    suspend fun defaultModel(): String =
        store.settings().chatModel.ifBlank { app.health.state.value.models.firstOrNull().orEmpty() }

    /** What a reply does: add a new message, add a new version of one, or extend one. */
    private enum class Mode { NEW, REGENERATE, CONTINUE }

    // ---- utility chat -------------------------------------------------------------

    fun sendUtility(chatId: String, text: String, attachments: List<Attachment>) {
        app.tasks.launch(replyTaskId(chatId)) {
            store.updateChat(chatId) { c ->
                val title = c.title.ifBlank { text.trim().take(60).ifBlank { "Photo" } }
                c.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                    messages = c.messages + Message(speaker = Message.USER, text = text, attachments = attachments),
                )
            }
            replyUtility(chatId, Mode.NEW, null)
        }
    }

    /** A new version of a reply (the old ones are kept and can be switched between). */
    fun regenerateUtility(chatId: String, messageId: String, ignoreEos: Boolean = false) {
        app.tasks.launch(replyTaskId(chatId)) { replyUtility(chatId, Mode.REGENERATE, messageId, ignoreEos) }
    }

    /** Carry on writing a reply from where it stopped. */
    fun continueUtility(chatId: String, messageId: String, ignoreEos: Boolean = false) {
        app.tasks.launch(replyTaskId(chatId)) { replyUtility(chatId, Mode.CONTINUE, messageId, ignoreEos) }
    }

    /**
     * [ignoreEos] (long-press): the model's end-of-turn token is ignored, so only a stop
     * string or the [CONTINUE_TOKENS] cap ends it (the old app's long-press).
     */
    private suspend fun replyUtility(chatId: String, mode: Mode, targetId: String?, ignoreEos: Boolean = false) {
        val s = store.settings()
        val chat = store.chat(chatId) ?: return
        val character = s.chatCharacterId?.let { store.character(it) }
        val preset = store.preset(s.chatPreset)
        val model = s.chatModel.ifBlank { defaultModel() }
        val target = targetId?.let { id -> chat.messages.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        if (mode != Mode.NEW && target == null) return
        val context = when (mode) {
            Mode.NEW -> chat.messages
            Mode.REGENERATE -> chat.messages.take(target!!)
            Mode.CONTINUE -> chat.messages.take(target!! + 1)
        }
        val history = buildHistory(context, model, 3, { if (it.isUser) "user" else "assistant" }) { stripThink(it.text) }
        val system = character?.systemPrompt?.let { Prompts.resolve(it, character.name, "User") }.orEmpty()
        val messages = buildList {
            if (system.isNotBlank()) add(apiText("system", system))
            addAll(history)
        }
        val request = Prompts.request(model, messages, preset)
            .let { if (mode == Mode.CONTINUE || ignoreEos) it.copy(maxTokens = CONTINUE_TOKENS, ignoreEos = ignoreEos) else it }

        val (replyId, base) = when (mode) {
            Mode.NEW -> Message(speaker = Message.ASSISTANT, pending = true).let { m ->
                store.updateChat(chatId) { it.copy(messages = it.messages + m) }
                m.id to ""
            }
            Mode.REGENERATE -> chat.messages[target!!].let { m ->
                store.updateChat(chatId) { c -> c.copy(messages = c.messages.map { if (it.id == m.id) it.newVersion().copy(pending = true, error = null) else it }) }
                m.id to ""
            }
            Mode.CONTINUE -> chat.messages[target!!].let { m ->
                store.updateChat(chatId) { c -> c.copy(messages = c.messages.map { if (it.id == m.id) it.copy(pending = true, error = null) else it }) }
                m.id to m.text
            }
        }
        val used = streamIntoMessage(request, s.llmAddress) { text, pending, error ->
            store.updateChat(chatId) { c ->
                c.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = c.messages.map {
                        if (it.id == replyId) it.withText(base + withoutEcho(stripThink(base), text)).copy(pending = pending, error = error) else it
                    },
                )
            }
        }
        if (used > 0) {
            val usage = usageFor(s.llmAddress, model, used, store.chat(chatId)?.usage)
            store.updateChat(chatId) { it.copy(usage = usage) }
        }
    }

    // ---- roleplay -----------------------------------------------------------------

    /** Send the user's message (if any) and have a character reply. */
    fun sendRoleplay(rpId: String, text: String, attachments: List<Attachment>, speakerId: String?) {
        app.tasks.launch(replyTaskId(rpId)) {
            // Always add your turn, even when empty (as the old app did): an empty send
            // asks for another reply, and the server won't take two character turns in a row.
            store.updateRoleplay(rpId) { r ->
                r.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = r.messages + Message(speaker = Message.USER, text = text.trim(), attachments = attachments),
                )
            }
            replyRoleplay(rpId, Mode.NEW, null, speakerId)
        }
    }

    /** A new version of a character's message, from the same character. */
    fun regenerateRoleplay(rpId: String, messageId: String, ignoreEos: Boolean = false) {
        app.tasks.launch(replyTaskId(rpId)) { replyRoleplay(rpId, Mode.REGENERATE, messageId, null, ignoreEos) }
    }

    fun continueRoleplay(rpId: String, messageId: String, ignoreEos: Boolean = false) {
        app.tasks.launch(replyTaskId(rpId)) { replyRoleplay(rpId, Mode.CONTINUE, messageId, null, ignoreEos) }
    }

    /** [ignoreEos]: as for [replyUtility]. */
    private suspend fun replyRoleplay(rpId: String, mode: Mode, targetId: String?, speakerId: String?, ignoreEos: Boolean = false) {
        val s = store.settings()
        val rp = store.roleplay(rpId) ?: return
        val model = rp.model.ifBlank { defaultModel() }
        val target = targetId?.let { id -> rp.messages.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        if (mode != Mode.NEW && target == null) return
        val targetMsg = target?.let { rp.messages[it] }
        val context = when (mode) {
            Mode.NEW -> rp.messages
            Mode.REGENERATE -> rp.messages.take(target!!)
            Mode.CONTINUE -> rp.messages.take(target!! + 1)
        }
        // Regenerate keeps the same speaker; Continue carries on the same message.
        val forced: Character? = when (mode) {
            Mode.NEW -> speakerId?.let { rp.character(it) }
            Mode.REGENERATE -> targetMsg?.let { rp.character(it.speaker) }
            Mode.CONTINUE -> null
        }
        val preset = store.preset(rp.preset).withOverrides(rp.overrides)
        val history = buildHistory(context, model, rp.attachedImagesSent, { if (it.isUser) "user" else "assistant" }) { m ->
            if (m.isUser) stripThink(m.text) else Prompts.assistantText(rp, m)
        }
        val request = Prompts.roleplayReply(rp.copy(model = model), history, forced, preset)
            .let { if (mode == Mode.CONTINUE || ignoreEos) it.copy(maxTokens = CONTINUE_TOKENS, ignoreEos = ignoreEos) else it }

        val replyId: String
        var base = ""
        when (mode) {
            Mode.NEW -> {
                val m = Message(speaker = forced?.id ?: rp.characters.firstOrNull()?.id ?: Message.ASSISTANT, pending = true)
                store.updateRoleplay(rpId) { it.copy(messages = it.messages + m) }
                replyId = m.id
            }
            Mode.REGENERATE -> {
                replyId = targetMsg!!.id
                store.updateRoleplay(rpId) { r -> r.copy(messages = r.messages.map { if (it.id == replyId) it.newVersion().copy(pending = true, error = null) else it }) }
            }
            Mode.CONTINUE -> {
                replyId = targetMsg!!.id
                base = targetMsg.text
                store.updateRoleplay(rpId) { r -> r.copy(messages = r.messages.map { if (it.id == replyId) it.copy(pending = true, error = null) else it }) }
            }
        }
        val prefix = if (forced != null && rp.characters.size > 1) "${forced.name}:" else ""
        // What was prefilled as the start of the reply, which newer servers send back first.
        val prefill = if (mode == Mode.CONTINUE) Prompts.assistantText(rp, targetMsg!!) else prefix
        val used = streamIntoMessage(request, s.llmAddress) { raw, pending, error ->
            store.updateRoleplay(rpId) { r ->
                r.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = r.messages.map {
                        if (it.id != replyId) it
                        else if (mode == Mode.CONTINUE) it.withText(base + withoutEcho(prefill, raw)).copy(pending = pending, error = error)
                        else {
                            val (speaker, text) = Prompts.parseSpeaker(r, prefix + withoutEcho(prefill, raw), forced?.id)
                            it.withText(text).copy(speaker = speaker, pending = pending, error = error)
                        }
                    },
                )
            }
        }
        if (used > 0) {
            val usage = usageFor(s.llmAddress, model, used, store.roleplay(rpId)?.usage)
            store.updateRoleplay(rpId) { it.copy(usage = usage) }
        }
    }

    /**
     * Impersonate (from the old app): the LLM writes the user's next line. [prefill] is
     * whatever is already typed; the model continues that. The result goes into the input
     * box for the user to edit and send.
     */
    fun impersonate(
        rpId: String,
        prefill: String,
        onText: (String) -> Unit,
        onDone: (Throwable?) -> Unit,
        ignoreEos: Boolean = false,
    ): Job {
        val head = prefill.trimEnd()
        var user: String? = null // set when the request is built, before any text arrives
        return complete(
            taskId = "impersonate:$rpId",
            request = {
                user = store.roleplay(rpId)?.let(Prompts::userName)
                impersonateRequest(rpId, head, ignoreEos)
            },
            onText = { t ->
                // Newer llama-server sends the whole prefilled message back first (the last
                // character message + "\n<you>:"), older ones only the continuation. Keep
                // what comes after the last "<you>:" either way (as the old app did).
                val body = user?.let { t.substringAfterLast("$it:") } ?: t
                onText(foldOntoPrefill(head, body))
            },
            onDone = onDone,
        )
    }

    /**
     * Join the typed text ([head]) and the model's [body]. Servers that echo the prefill
     * already include [head] in [body]; others return only the rest. (From the old app.)
     */
    private fun foldOntoPrefill(head: String, body: String): String {
        val shown = body.trimStart()
        return when {
            head.isEmpty() -> shown
            shown.startsWith(head) -> shown // the echo arrived
            head.startsWith(shown) -> head // still echoing our own text back
            else -> head + body
        }.trim()
    }

    /**
     * [ignoreEos] (long-press): the model's end-of-turn token is ignored, so it keeps writing
     * until a name prefix or the [CONTINUE_TOKENS] cap stops it.
     */
    private suspend fun impersonateRequest(rpId: String, prefill: String, ignoreEos: Boolean): ChatRequest {
        val rp = store.roleplay(rpId) ?: error("Roleplay not found")
        val model = rp.model.ifBlank { defaultModel() }
        val user = Prompts.userName(rp)
        val preset = store.preset(rp.preset).withOverrides(rp.overrides)
        val last = rp.messages.lastOrNull { !it.pending }
        val typed = if (prefill.isBlank()) "" else " " + prefill.trimEnd()
        // llama-server only continues a trailing assistant message, so the user's "Name:"
        // goes onto the end of the last character message and the model carries on from it.
        // No trailing space after the colon: that makes some models end the turn at once.
        val foldInto = last?.takeIf { !it.isUser && it.attachments.isEmpty() }
        val earlier = if (foldInto != null) rp.messages.filter { it.id != foldInto.id && !it.pending } else rp.messages
        val history = buildHistory(earlier, model, rp.attachedImagesSent, { if (it.isUser) "user" else "assistant" }) { m ->
            if (m.isUser) stripThink(m.text) else Prompts.assistantText(rp, m)
        }.toMutableList()
        history += if (foldInto != null) apiText("assistant", Prompts.assistantText(rp, foldInto).trimEnd() + "\n$user:$typed")
        else apiText("assistant", "$user:$typed")
        val messages = listOf(apiText("system", Prompts.roleplaySystem(rp))) + history
        return Prompts.request(model, messages, preset, stop = rp.characters.map { "${it.name}:" } + "\n$user:")
            .copy(maxTokens = CONTINUE_TOKENS, ignoreEos = ignoreEos)
    }

    /**
     * The part of [streamed] after an assistant [prefill] (a forced "Name:" or the text a
     * Continue extends). Newer llama-server sends the prefill back at the start of the
     * stream and older ones don't; either way the prefill ends up in the message exactly
     * once (the old app's "stitch").
     */
    private fun withoutEcho(prefill: String, streamed: String): String {
        val p = prefill.trimEnd()
        if (p.isEmpty()) return streamed
        val s = streamed.trimStart()
        return when {
            s.startsWith(p) -> s.substring(p.length)
            p.startsWith(s) -> "" // still repeating the prefill
            else -> streamed
        }
    }

    /** Context usage after a reply; the context size is looked up once per model. */
    private suspend fun usageFor(address: String, model: String, tokens: Int, old: ContextUsage?): ContextUsage {
        val limit = if (old != null && old.model == model && old.limit > 0) old.limit
        else app.llama.contextSize(address, model) ?: 0
        return ContextUsage(tokens = tokens, limit = limit, model = model)
    }

    // ---- one-shot requests (Generate buttons, image prompts) ------------------------------

    /**
     * Stream a one-shot request as a counted task. [onText] gets the full text so far
     * (reasoning removed). [onDone] gets null on success, or the error.
     */
    fun complete(
        taskId: String,
        request: suspend () -> ChatRequest,
        onText: (String) -> Unit,
        onDone: (Throwable?) -> Unit,
    ): Job = app.tasks.launch(taskId) {
        try {
            val req = request()
            val address = store.settings().llmAddress
            require(address.isNotBlank()) { "No LLM server address is set (Settings)" }
            val sb = StringBuilder()
            // Only real text is passed on, so whatever was there stays until the answer
            // starts (not while the model is still reasoning), and survives an empty result.
            app.llama.streamChat(address, req.copy(model = req.model.ifBlank { defaultModel() })).collect {
                sb.append(it)
                stripThink(sb.toString()).takeIf { t -> t.isNotBlank() }?.let(onText)
            }
            val answer = stripThink(sb.toString()).trim()
            when {
                answer.isNotEmpty() -> { onText(answer); onDone(null) }
                sb.isNotBlank() -> onDone(IllegalStateException(
                    "The model only produced reasoning, no answer (it may have run out of tokens). Nothing was changed.",
                ))
                else -> onDone(IllegalStateException("The model returned no text. Nothing was changed."))
            }
        } catch (c: CancellationException) {
            onDone(c)
            throw c
        } catch (t: Throwable) {
            onDone(t)
        }
    }

    // ---- shared ---------------------------------------------------------------------

    /** Streams into a message. Returns the tokens in context afterwards (0 if unknown). */
    private suspend fun streamIntoMessage(
        request: ChatRequest,
        address: String,
        update: suspend (text: String, pending: Boolean, error: String?) -> Unit,
    ): Int {
        val sb = StringBuilder()
        var used = 0
        try {
            require(address.isNotBlank()) { "No LLM server address is set (Settings)" }
            require(request.model.isNotBlank()) { "No model selected" }
            var lastWrite = 0L
            app.llama.streamChat(address, request) { used = it }.collect { delta ->
                sb.append(delta)
                val now = System.currentTimeMillis()
                if (now - lastWrite > 300) {
                    lastWrite = now
                    update(sb.toString(), true, null)
                }
            }
            update(sb.toString().trimEnd(), false, null)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { runCatching { update(sb.toString(), false, if (sb.isEmpty()) "Stopped" else null) } }
            throw c
        } catch (t: Throwable) {
            update(sb.toString(), false, t.message ?: "Request failed")
        }
        return used
    }

    /**
     * Conversation -> API messages. With a vision model, the newest [limit] messages that
     * have images send the images (0 = all); every other image is sent as its description.
     */
    private suspend fun buildHistory(
        messages: List<Message>,
        model: String,
        limit: Int,
        role: (Message) -> String,
        text: (Message) -> String,
    ): List<ApiMessage> {
        val vision = ModelCapabilities.supportsImages(model)
        val withImages = messages.filter { it.attachments.isNotEmpty() && !it.pending }
        val sendImages = (if (limit <= 0) withImages else withImages.takeLast(limit)).map { it.id }.toSet()
        // Your empty turns are kept (they're how you ask for another reply); empty
        // character messages are dropped.
        return messages.filter { !it.pending && (it.isUser || it.text.isNotBlank() || it.attachments.isNotEmpty()) }.map { m ->
            val body = text(m)
            if (m.attachments.isEmpty()) return@map apiText(role(m), body)
            if (vision && m.id in sendImages) {
                val parts = m.attachments.mapNotNull { imagePart(it) }
                val textPart = body.takeIf { it.isNotBlank() }?.let { json.encodeToJsonElement(TextPart(text = it)) }
                if (parts.isNotEmpty()) return@map apiParts(role(m), parts + listOfNotNull(textPart))
            }
            val notes = m.attachments.joinToString("\n") { a ->
                "[Image: ${a.description.ifBlank { "no description" }}]"
            }
            apiText(role(m), listOf(body, notes).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }

    private suspend fun imagePart(a: Attachment): JsonElement? = withContext(Dispatchers.IO) {
        val media = store.media(a.mediaId) ?: return@withContext null
        val fileId = media.fileId ?: return@withContext null
        if (!media.mime.startsWith("image")) return@withContext null
        val b64 = Base64.encodeToString(store.files.readBytes(fileId), Base64.NO_WRAP)
        json.encodeToJsonElement(ImageUrlPart(imageUrl = ImageUrl("data:${media.mime};base64,$b64")))
    }

    /** Request to describe an image, so models without vision can be sent the description. */
    suspend fun describeRequest(mediaId: String, model: String): ChatRequest {
        val use = model.ifBlank { defaultModel() }
        require(ModelCapabilities.supportsImages(use)) {
            "The description model ($use) can't see images. Choose a -VL- model in Edit chat details."
        }
        val part = imagePart(Attachment(mediaId)) ?: error("That image isn't available")
        val text = json.encodeToJsonElement(
            TextPart(text = "Describe this image in detail: the people (apparent age, build, hair, clothing, expression), what they are doing, the setting, lighting and style. Output only the description."),
        )
        val p = au.net.maz.privacyai.data.model.BuiltIns.imagePromptSampling
        return ChatRequest(
            model = use,
            messages = listOf(apiParts("user", listOf(part, text))),
            stream = true,
            maxTokens = 600,
            temperature = p.temperature, topP = p.topP, topK = p.topK, repeatPenalty = p.repeatPenalty,
        )
    }

    /** After the app was killed mid-reply, a message can be left marked pending. */
    suspend fun clearStalePending(conversationId: String, isRoleplay: Boolean) {
        if (isReplying(conversationId)) return
        fun fix(list: List<Message>) = list.map {
            if (it.pending) it.copy(pending = false, error = it.error ?: "Interrupted") else it
        }
        if (isRoleplay) {
            val rp = store.roleplay(conversationId) ?: return
            if (rp.messages.any { it.pending }) store.updateRoleplay(conversationId) { it.copy(messages = fix(it.messages)) }
        } else {
            val c = store.chat(conversationId) ?: return
            if (c.messages.any { it.pending }) store.updateChat(conversationId) { it.copy(messages = fix(it.messages)) }
        }
    }

    private val json = Json

    private companion object {
        /** Cap for Continue / Impersonate, as in the old app. */
        const val CONTINUE_TOKENS = 1000
    }
}
