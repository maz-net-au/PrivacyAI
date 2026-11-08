package au.net.maz.privacyai.data.media

import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import okio.buffer
import okio.source

/** Something Coil can load from an encrypted media file. [video] = show a frame. */
data class MediaRef(val fileId: String, val video: Boolean = false)

/**
 * Coil loads media by decrypting in memory. The app's ImageLoader has no disk cache, so
 * decrypted images and thumbnails never touch storage.
 */
class MediaFetcher(
    private val ref: MediaRef,
    private val files: () -> MediaFiles?,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val f = files() ?: error("Locked")
        if (ref.video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.dataSource(ref.fileId))
                val frame = r.getFrameAtTime(0) ?: error("No video frame")
                return DrawableResult(
                    BitmapDrawable(options.context.resources, frame),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            } finally {
                r.release()
            }
        }
        val bytes = f.readBytes(ref.fileId)
        return SourceResult(
            source = ImageSource(bytes.inputStream().source().buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val files: () -> MediaFiles?) : Fetcher.Factory<MediaRef> {
        override fun create(data: MediaRef, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaFetcher(data, files, options)
    }

    class Key : Keyer<MediaRef> {
        override fun key(data: MediaRef, options: Options) = "${data.fileId}:${data.video}"
    }
}
