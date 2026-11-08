package au.net.maz.privacyai.data.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/** Random, meaningless ids (also used for media file names, so they reveal nothing). */
object Ids {
    private val random = SecureRandom()
    fun new(prefix: String = ""): String {
        val b = ByteArray(12).also { random.nextBytes(it) }
        return prefix + b.joinToString("") { "%02x".format(it) }
    }
}

/** Global settings (the "Settings list" in notes.md). Stored in the encrypted database. */
@Serializable
data class Settings(
    val inactivityMinutes: Int = 5,
    /** host:port, e.g. 192.168.1.50:8080. Blank = not set. */
    val llmAddress: String = "",
    val imageAddress: String = "",
    val chatModel: String = "",
    /** A reference into the main character list. */
    val chatCharacterId: String? = null,
    val chatPreset: String = "Default",
    val roleplayEnabled: Boolean = false,
    /** Roleplay section: the "visual director" system prompt used to write image prompts. */
    val imagePromptSystem: String = DEFAULT_IMAGE_PROMPT_SYSTEM,
) {
    companion object {
        /** Taken from the old app (SceneImageConfig.SYSTEM_PROMPT). */
        const val DEFAULT_IMAGE_PROMPT_SYSTEM =
            "You are a visual director that turns a moment from a story into a prompt for a " +
                "text-to-image model. Describe ONE single still image capturing the current moment. " +
                "Describe only what would be visible in that frame — never sounds, smells, thoughts, " +
                "backstory, or events outside the shot. Spend the most detail on the requested focus; " +
                "give progressively less detail to related visible elements, and only briefly note " +
                "background. Any character visible in the frame must be described at least peripherally " +
                "by apparent age, gender, race, and one or two defining features (hair, build, clothing), " +
                "so characters stay recognizable across images. Use concrete, present-tense visual " +
                "language and comma-separated descriptive phrases. Output ONLY the image description — " +
                "no preamble, labels, explanations, or commentary."
    }
}

/** A character, in the main list or inside one roleplay (same structure for both). */
@Serializable
data class Character(
    val id: String = Ids.new("ch_"),
    val name: String = "",
    /** Short subtitle. */
    val description: String = "",
    val greeting: String = "",
    /** Persona / system prompt text. */
    val systemPrompt: String = "",
    /** Current state (relationships, injuries, possessions...). */
    val state: String = "",
    /** Base colour (ARGB). Light/dark versions are derived from it. */
    val colour: Int = 0,
    /** Media id of the reference image, owned by the same place as the character. */
    val referenceImage: String? = null,
    /** For characters cloned into a roleplay: the main-list id it came from (info only). */
    val clonedFrom: String? = null,
)

/** One chat message, used by both utility chats and roleplays. */
@Serializable
data class Message(
    val id: String = Ids.new("msg_"),
    /** "user", "assistant" (utility chat), or a character id (roleplay). */
    val speaker: String,
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** True while a reply is still streaming into this message. */
    val pending: Boolean = false,
    val error: String? = null,
    /**
     * Every version of this message (from Regenerate), including the one shown, which is
     * [active]. Empty means there's only ever been one version: [text]. [text] is always
     * the version being shown.
     */
    val variants: List<String> = emptyList(),
    val active: Int = 0,
) {
    val isUser: Boolean get() = speaker == USER

    val versions: List<String> get() = variants.ifEmpty { listOf(text) }

    /** Change the text of the version being shown. */
    fun withText(t: String): Message =
        if (variants.isEmpty()) copy(text = t)
        else copy(text = t, variants = variants.toMutableList().also { it[active.coerceIn(0, it.lastIndex)] = t })

    /** Start a new, empty version and show it. */
    fun newVersion(): Message = versions.let { v -> copy(variants = v + "", active = v.size, text = "") }

    fun showVersion(i: Int): Message = versions.let { v ->
        val idx = i.coerceIn(0, v.lastIndex)
        copy(active = idx, text = v[idx])
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** A media item attached to a message. The description is sent instead of the image to
 *  models without vision (a generated image's description is its prompt). */
@Serializable
data class Attachment(
    val mediaId: String,
    val description: String = "",
)

@Serializable
data class UtilityChat(
    val id: String = Ids.new("chat_"),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val usage: ContextUsage = ContextUsage(),
)

/**
 * How full the model's context was after the last reply, as reported by the server, so it
 * includes images. [limit] is the model's context size (0 = unknown).
 */
@Serializable
data class ContextUsage(val tokens: Int = 0, val limit: Int = 0, val model: String = "")

/** A roleplay: self-contained, see roleplay-format.md. Its own images live in the media
 *  table with owner = "rp:<id>". */
@Serializable
data class Roleplay(
    val id: String = Ids.new("rp_"),
    val formatVersion: Int = 1,
    val title: String = "",
    val yourName: String = "",
    val myDescription: String = "",
    val summaryBackground: String = "",
    val model: String = "",
    /** Model used to describe images. Null = same as [model]. */
    val descriptionModel: String? = null,
    val preset: String = "Default",
    val overrides: SamplingOverrides = SamplingOverrides(),
    /** How many of the newest attached images are sent. 0 = unlimited. */
    val attachedImagesSent: Int = 3,
    val characters: List<Character> = emptyList(),
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** True until the setup screen's "Start roleplay" is pressed. */
    val draft: Boolean = true,
    val usage: ContextUsage = ContextUsage(),
) {
    val owner: String get() = MediaOwner.roleplay(id)
    val effectiveDescriptionModel: String get() = descriptionModel ?: model

    fun character(id: String): Character? = characters.firstOrNull { it.id == id }
}

/** Where a media item belongs. Deleting the owner deletes its media. */
object MediaOwner {
    const val GALLERY = "gallery"
    /** Reference images of main-list characters. */
    const val MAIN_CHARACTERS = "characters"
    fun roleplay(id: String) = "rp:$id"
    fun chat(id: String) = "chat:$id"
    fun roleplayId(owner: String): String? = owner.removePrefix("rp:").takeIf { owner.startsWith("rp:") }
}

enum class MediaKind(val key: String, val label: String) {
    IMAGE("image", "Image"), AUDIO("audio", "Audio"), VIDEO("video", "Video");

    companion object {
        fun fromKey(k: String): MediaKind = entries.firstOrNull { it.key == k } ?: IMAGE
        fun fromMime(mime: String): MediaKind = when {
            mime.startsWith("video") -> VIDEO
            mime.startsWith("audio") -> AUDIO
            else -> IMAGE
        }
    }
}

enum class MediaStatus(val key: String) {
    PENDING("pending"), DONE("done"), FAILED("failed");

    companion object {
        fun fromKey(k: String): MediaStatus = entries.firstOrNull { it.key == k } ?: DONE
    }
}

/** One value from the generation form, so Regenerate can re-open it pre-filled. */
@Serializable
data class FormValue(val nodeTitle: String, val input: String, val value: String)

/** A file input (e.g. reference image) used by a generation: which media went where. */
@Serializable
data class FileValue(val nodeTitle: String, val input: String, val mediaId: String)

/** Everything about a media item except its bytes. */
@Serializable
data class MediaInfo(
    /** "generated", "photo" or "imported". */
    val origin: String = ORIGIN_GENERATED,
    /** The prompt used (generated) or a generated description; sent to non-vision models. */
    val description: String = "",
    val workflowId: String? = null,
    val workflowName: String = "",
    val values: List<FormValue> = emptyList(),
    val files: List<FileValue> = emptyList(),
    val error: String? = null,
) {
    companion object {
        const val ORIGIN_GENERATED = "generated"
        const val ORIGIN_PHOTO = "photo"
        const val ORIGIN_IMPORTED = "imported"
    }
}

/** A media item as the UI sees it. */
data class Media(
    val id: String,
    val owner: String,
    val kind: MediaKind,
    val status: MediaStatus,
    val createdAt: Long,
    /** Encrypted file id; null while pending. */
    val fileId: String?,
    val mime: String,
    val info: MediaInfo,
)

/**
 * Model input abilities, inferred from the name as in the old app:
 * -VL- = vision, -AL- = audio, -VAL- = both.
 */
object ModelCapabilities {
    fun supportsImages(model: String): Boolean =
        model.contains("-VL-", ignoreCase = true) || model.contains("-VAL-", ignoreCase = true)

    fun supportsAudio(model: String): Boolean =
        model.contains("-AL-", ignoreCase = true) || model.contains("-VAL-", ignoreCase = true)
}
