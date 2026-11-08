package au.net.maz.privacyai.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/*
 * One local database, encrypted with SQLCipher. Rows are mostly a few indexed columns
 * plus a JSON body, so structures can grow without schema migrations while the app is
 * still taking shape.
 */

/** Key/value store: settings, presets, base enum lists. */
@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

/** Main character list. */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val json: String,
)

@Entity(tableName = "roleplays")
data class RoleplayEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val draft: Boolean,
    val json: String,
)

@Entity(tableName = "media", indices = [Index("owner")])
data class MediaEntity(
    @PrimaryKey val id: String,
    val owner: String,
    val kind: String,
    val status: String,
    val createdAt: Long,
    val fileId: String?,
    val mime: String,
    val infoJson: String,
)

/** Installed ComfyUI workflow package (the old zip format). */
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** image | audio | video: which section lists it. */
    val section: String,
    val sortOrder: Int,
    val configJson: String,
    val graphJson: String,
    val installedAt: Long,
)

/** An image-generator job that hasn't finished. */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val json: String,
)

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(e: KvEntity)
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Upsert
    suspend fun put(e: CharacterEntity)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: String): Flow<ChatEntity?>

    @Upsert
    suspend fun put(e: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RoleplayDao {
    @Query("SELECT * FROM roleplays WHERE draft = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RoleplayEntity>>

    @Query("SELECT * FROM roleplays WHERE id = :id")
    suspend fun get(id: String): RoleplayEntity?

    @Query("SELECT * FROM roleplays WHERE id = :id")
    fun observe(id: String): Flow<RoleplayEntity?>

    @Query("SELECT id FROM roleplays WHERE draft = 1")
    suspend fun draftIds(): List<String>

    @Upsert
    suspend fun put(e: RoleplayEntity)

    @Query("DELETE FROM roleplays WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE owner = :owner ORDER BY createdAt DESC")
    fun observeByOwner(owner: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE owner = :owner")
    suspend fun byOwner(owner: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observe(id: String): Flow<MediaEntity?>

    @Query("SELECT fileId FROM media WHERE fileId IS NOT NULL")
    suspend fun allFileIds(): List<String>

    @Upsert
    suspend fun put(e: MediaEntity)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows WHERE section = :section ORDER BY sortOrder, installedAt")
    fun observeSection(section: String): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE section = :section ORDER BY sortOrder, installedAt")
    suspend fun section(section: String): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun get(id: String): WorkflowEntity?

    @Upsert
    suspend fun put(e: WorkflowEntity)

    @Query("UPDATE workflows SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE workflows SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: String, order: Int)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs")
    suspend fun all(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Upsert
    suspend fun put(e: JobEntity)

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        KvEntity::class, CharacterEntity::class, ChatEntity::class, RoleplayEntity::class,
        MediaEntity::class, WorkflowEntity::class, JobEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kv(): KvDao
    abstract fun characters(): CharacterDao
    abstract fun chats(): ChatDao
    abstract fun roleplays(): RoleplayDao
    abstract fun media(): MediaDao
    abstract fun workflows(): WorkflowDao
    abstract fun jobs(): JobDao

    companion object {
        const val FILE_NAME = "privacyai.db"

        fun open(context: Context, key: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            // The factory wipes the passphrase array after use, so hand it a copy.
            val factory = SupportOpenHelperFactory(key.copyOf())
            return Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .build()
        }
    }
}
