package au.net.maz.privacyai.data

import au.net.maz.privacyai.data.db.AppDatabase
import au.net.maz.privacyai.data.db.CharacterEntity
import au.net.maz.privacyai.data.db.ChatEntity
import au.net.maz.privacyai.data.db.KvEntity
import au.net.maz.privacyai.data.db.MediaEntity
import au.net.maz.privacyai.data.db.RoleplayEntity
import au.net.maz.privacyai.data.media.MediaFiles
import au.net.maz.privacyai.data.model.BuiltIns
import au.net.maz.privacyai.data.model.Character
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaInfo
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.data.model.Preset
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.data.model.Settings
import au.net.maz.privacyai.data.model.UtilityChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Everything stored while the database is unlocked. Created on unlock, thrown away on
 * lock. All reads/writes of app data go through here.
 */
class Store(val db: AppDatabase, val files: MediaFiles) {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Serialises read-modify-write of one roleplay/chat blob (streaming replies, edits). */
    private val writeLock = Mutex()

    /**
     * Whether the main gallery shows items marked as spoilers. Not saved: off whenever a
     * gallery is opened, and gone on lock along with the rest of the Store.
     */
    val showSpoilers = MutableStateFlow(false)

    // ---- settings / presets -------------------------------------------------------

    val settings: Flow<Settings> = db.kv().observe(KEY_SETTINGS).map { decodeSettings(it) }

    suspend fun settings(): Settings = decodeSettings(db.kv().get(KEY_SETTINGS))

    suspend fun updateSettings(f: (Settings) -> Settings) = writeLock.withLock {
        val next = f(settings())
        db.kv().put(KvEntity(KEY_SETTINGS, json.encodeToString(next)))
    }

    private fun decodeSettings(s: String?): Settings =
        s?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()

    val presets: Flow<List<Preset>> = db.kv().observe(KEY_PRESETS).map { decodePresets(it) }

    suspend fun presets(): List<Preset> = decodePresets(db.kv().get(KEY_PRESETS))

    suspend fun savePresets(list: List<Preset>) =
        db.kv().put(KvEntity(KEY_PRESETS, json.encodeToString(list)))

    private fun decodePresets(s: String?): List<Preset> =
        s?.let { runCatching { json.decodeFromString<List<Preset>>(it) }.getOrNull() } ?: BuiltIns.presets

    suspend fun preset(name: String): Preset =
        presets().let { list -> list.firstOrNull { it.name == name } ?: list.firstOrNull() ?: Preset("Default") }

    /** Shared option lists for workflow enum fields ("base_enums.json"). */
    suspend fun baseEnums(): Map<String, List<String>> =
        db.kv().get(KEY_BASE_ENUMS)?.let {
            runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull()
        } ?: emptyMap()

    suspend fun mergeBaseEnums(add: Map<String, List<String>>) =
        db.kv().put(KvEntity(KEY_BASE_ENUMS, json.encodeToString(baseEnums() + add)))

    /** First-run contents: built-in presets and an "Assistant" for utility chats. */
    suspend fun seed(colour: Int) {
        savePresets(BuiltIns.presets)
        val assistant = BuiltIns.assistant(colour)
        saveCharacter(assistant)
        updateSettings { it.copy(chatCharacterId = assistant.id) }
    }

    // ---- main characters -------------------------------------------------------

    val characters: Flow<List<Character>> =
        db.characters().observeAll().map { list -> list.mapNotNull { decodeCharacter(it.json) } }

    suspend fun characters(): List<Character> = db.characters().all().mapNotNull { decodeCharacter(it.json) }

    suspend fun character(id: String): Character? = db.characters().get(id)?.let { decodeCharacter(it.json) }

    suspend fun saveCharacter(c: Character) =
        db.characters().put(CharacterEntity(c.id, c.name, json.encodeToString(c)))

    suspend fun deleteCharacter(id: String) {
        val c = character(id) ?: return
        c.referenceImage?.let { deleteMedia(it) }
        db.characters().delete(id)
    }

    private fun decodeCharacter(s: String): Character? =
        runCatching { json.decodeFromString<Character>(s) }.getOrNull()

    // ---- utility chats ---------------------------------------------------------

    val chats: Flow<List<UtilityChat>> =
        db.chats().observeAll().map { list -> list.mapNotNull { decodeChat(it.json) } }

    fun observeChat(id: String): Flow<UtilityChat?> = db.chats().observe(id).map { it?.let { e -> decodeChat(e.json) } }

    suspend fun chat(id: String): UtilityChat? = db.chats().get(id)?.let { decodeChat(it.json) }

    suspend fun saveChat(c: UtilityChat) =
        db.chats().put(ChatEntity(c.id, c.updatedAt, json.encodeToString(c)))

    /** Atomic read-modify-write of one chat. */
    suspend fun updateChat(id: String, f: (UtilityChat) -> UtilityChat): UtilityChat? = writeLock.withLock {
        val c = chat(id) ?: return@withLock null
        f(c).also { saveChat(it) }
    }

    suspend fun deleteChat(id: String) {
        deleteMediaOwnedBy(MediaOwner.chat(id))
        db.chats().delete(id)
    }

    private fun decodeChat(s: String): UtilityChat? =
        runCatching { json.decodeFromString<UtilityChat>(s) }.getOrNull()

    // ---- roleplays -------------------------------------------------------------

    val roleplays: Flow<List<Roleplay>> =
        db.roleplays().observeAll().map { list -> list.mapNotNull { decodeRoleplay(it.json) } }

    fun observeRoleplay(id: String): Flow<Roleplay?> =
        db.roleplays().observe(id).map { it?.let { e -> decodeRoleplay(e.json) } }

    suspend fun roleplay(id: String): Roleplay? = db.roleplays().get(id)?.let { decodeRoleplay(it.json) }

    suspend fun saveRoleplay(r: Roleplay) = writeLock.withLock { putRoleplay(r) }

    private suspend fun putRoleplay(r: Roleplay) =
        db.roleplays().put(RoleplayEntity(r.id, r.updatedAt, r.draft, json.encodeToString(r)))

    /** Atomic read-modify-write of one roleplay. */
    suspend fun updateRoleplay(id: String, f: (Roleplay) -> Roleplay): Roleplay? = writeLock.withLock {
        val r = roleplay(id) ?: return@withLock null
        f(r).also { putRoleplay(it) }
    }

    /** Deletes the roleplay and every media item embedded in it. */
    suspend fun deleteRoleplay(id: String) {
        deleteMediaOwnedBy(MediaOwner.roleplay(id))
        db.roleplays().delete(id)
    }

    /** Drafts left behind if the app died during a new roleplay's setup. */
    suspend fun deleteStaleDrafts(keep: String? = null) {
        db.roleplays().draftIds().filter { it != keep }.forEach { deleteRoleplay(it) }
    }

    private fun decodeRoleplay(s: String): Roleplay? =
        runCatching { json.decodeFromString<Roleplay>(s) }.getOrNull()

    // ---- media -----------------------------------------------------------------

    fun observeMedia(owner: String): Flow<List<Media>> =
        db.media().observeByOwner(owner).map { list -> list.map { it.toMedia() } }

    fun observeMediaItem(id: String): Flow<Media?> = db.media().observe(id).map { it?.toMedia() }

    suspend fun media(id: String): Media? = db.media().get(id)?.toMedia()

    suspend fun mediaOwnedBy(owner: String): List<Media> = db.media().byOwner(owner).map { it.toMedia() }

    suspend fun putMedia(m: Media) = db.media().put(
        MediaEntity(
            id = m.id, owner = m.owner, kind = m.kind.key, status = m.status.key,
            createdAt = m.createdAt, fileId = m.fileId, mime = m.mime,
            infoJson = json.encodeToString(m.info),
        ),
    )

    suspend fun updateMedia(id: String, f: (Media) -> Media): Media? {
        val m = media(id) ?: return null
        return f(m).also { putMedia(it) }
    }

    /** A placeholder shown straight away while a generation runs. */
    suspend fun newPendingMedia(owner: String, kind: MediaKind, info: MediaInfo): Media {
        val m = Media(
            id = Ids.new("media_"), owner = owner, kind = kind, status = MediaStatus.PENDING,
            createdAt = System.currentTimeMillis(), fileId = null, mime = "", info = info,
        )
        putMedia(m)
        return m
    }

    /** Encrypt bytes from [input] into a new, finished media item. */
    suspend fun importMedia(owner: String, mime: String, info: MediaInfo, input: InputStream): Media =
        withContext(Dispatchers.IO) {
            val fileId = files.write(input)
            val m = Media(
                id = Ids.new("media_"), owner = owner, kind = MediaKind.fromMime(mime),
                status = MediaStatus.DONE, createdAt = System.currentTimeMillis(),
                fileId = fileId, mime = mime, info = info,
            )
            putMedia(m)
            m
        }

    /** Copy a media item (bytes too) to another owner, e.g. cloning a character into a roleplay. */
    suspend fun copyMedia(id: String, newOwner: String): Media? = withContext(Dispatchers.IO) {
        val src = media(id) ?: return@withContext null
        val newFile = src.fileId?.let { fid ->
            if (!files.exists(fid)) null else files.openInput(fid).use { files.write(it) }
        }
        val copy = src.copy(id = Ids.new("media_"), owner = newOwner, fileId = newFile)
        putMedia(copy)
        copy
    }

    suspend fun deleteMedia(id: String) {
        val m = db.media().get(id) ?: return
        m.fileId?.let { files.delete(it) }
        db.media().delete(id)
    }

    suspend fun deleteMediaOwnedBy(owner: String) {
        db.media().byOwner(owner).forEach { m ->
            m.fileId?.let { files.delete(it) }
            db.media().delete(m.id)
        }
    }

    suspend fun cleanOrphanFiles() = withContext(Dispatchers.IO) {
        files.deleteOrphans(db.media().allFileIds().toSet())
    }

    private fun MediaEntity.toMedia() = Media(
        id = id,
        owner = owner,
        kind = MediaKind.fromKey(kind),
        status = MediaStatus.fromKey(status),
        createdAt = createdAt,
        fileId = fileId,
        mime = mime,
        info = runCatching { json.decodeFromString<MediaInfo>(infoJson) }.getOrNull() ?: MediaInfo(),
    )

    fun close() = db.close()

    private companion object {
        const val KEY_SETTINGS = "settings"
        const val KEY_PRESETS = "presets"
        const val KEY_BASE_ENUMS = "base_enums"
    }
}
