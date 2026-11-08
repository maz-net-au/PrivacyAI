package au.net.maz.privacyai.data.media

import android.media.MediaDataSource
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import au.net.maz.privacyai.data.model.Ids
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Media bytes (images, audio, video) as individually encrypted files with random names.
 * Streaming AES-GCM (Tink, 1 MB segments) so large videos can be read in pieces and
 * seeked without decrypting the whole file. The file id is bound in as associated data,
 * so files can't be swapped around.
 *
 * Nothing here ever writes plaintext to disk.
 */
class MediaFiles(private val dir: File, key: ByteArray) {

    private val aead = AesGcmHkdfStreaming(key.copyOf(), "HmacSha256", 32, SEGMENT, 0)

    init {
        dir.mkdirs()
        // Leftovers from a write that was interrupted (e.g. the app was killed).
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun file(fileId: String) = File(dir, fileId)

    fun exists(fileId: String) = file(fileId).exists()

    /** Encrypt everything from [input] into a new file. Returns its id. */
    fun write(input: InputStream): String {
        val id = Ids.new()
        write(id) { out -> input.copyTo(out) }
        return id
    }

    /** Encrypt whatever [block] writes into a new file with [fileId]. */
    fun write(fileId: String, block: (OutputStream) -> Unit) {
        val tmp = File(dir, "$fileId.tmp")
        try {
            aead.newEncryptingStream(FileOutputStream(tmp), fileId.toByteArray()).use(block)
            if (!tmp.renameTo(file(fileId))) error("Could not store media file")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun newFileId(): String = Ids.new()

    fun openInput(fileId: String): InputStream =
        aead.newDecryptingStream(FileInputStream(file(fileId)), fileId.toByteArray())

    fun readBytes(fileId: String): ByteArray = openInput(fileId).use { it.readBytes() }

    fun openSeekable(fileId: String): SeekableByteChannel =
        aead.newSeekableDecryptingChannel(FileInputStream(file(fileId)).channel, fileId.toByteArray())

    /** For MediaPlayer / MediaMetadataRetriever: random access to the decrypted bytes. */
    fun dataSource(fileId: String): MediaDataSource = ChannelDataSource(openSeekable(fileId))

    fun delete(fileId: String) {
        file(fileId).delete()
    }

    /** Remove files that no database row points at. */
    fun deleteOrphans(known: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in known && !it.name.endsWith(".tmp")) it.delete() }
    }

    private class ChannelDataSource(private val channel: SeekableByteChannel) : MediaDataSource() {
        private val size = channel.size()

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= this.size) return -1
            channel.position(position)
            val buf = ByteBuffer.wrap(buffer, offset, size)
            var read = 0
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n <= 0) break
                read += n
            }
            return if (read == 0) -1 else read
        }

        override fun getSize(): Long = size

        override fun close() = channel.close()
    }

    private companion object {
        const val SEGMENT = 1 shl 20
    }
}
