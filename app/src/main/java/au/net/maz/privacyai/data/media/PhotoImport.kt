package au.net.maz.privacyai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Bring a photo (camera or device picker) into the app: downscaled and re-encoded as JPEG
 * (as the old app did, which also drops the photo's metadata such as location), then
 * encrypted into the owner's media.
 */
object PhotoImport {
    private const val MAX_DIMENSION_PX = 1024
    private const val JPEG_QUALITY = 85

    /** Throws with a readable message if the photo can't be read. */
    suspend fun import(context: Context, store: Store, owner: String, uri: Uri, origin: String): Media? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            if (mime.startsWith("video") || mime.startsWith("audio")) {
                // Videos/audio are stored as they are.
                return@withContext resolver.openInputStream(uri)?.use {
                    store.importMedia(owner, mime, MediaInfo(origin = origin), it)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // A bounds-only decode always returns null, so check the sizes it filled in instead.
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Couldn't read the image")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Couldn't decode the image")
            val rotation = runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val scale = MAX_DIMENSION_PX.toFloat() / maxOf(decoded.width, decoded.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            val out = if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            store.importMedia(owner, "image/jpeg", MediaInfo(origin = origin), bytes.inputStream())
        }
}
