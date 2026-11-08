package au.net.maz.privacyai.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import au.net.maz.privacyai.data.media.MediaRef
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.ui.LocalApp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** A square thumbnail: placeholder while pending, error mark if it failed. */
@Composable
fun MediaThumb(media: Media?, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            media == null -> Icon(Icons.Filled.ErrorOutline, "Missing", tint = MaterialTheme.colorScheme.outline)
            media.status == MediaStatus.PENDING -> CircularProgressIndicator(Modifier.size(28.dp))
            media.status == MediaStatus.FAILED || media.fileId == null ->
                Icon(Icons.Filled.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error)
            media.kind == MediaKind.IMAGE -> AsyncImage(
                model = MediaRef(media.fileId), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            media.kind == MediaKind.VIDEO -> {
                AsyncImage(
                    model = MediaRef(media.fileId, video = true), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            else -> Icon(Icons.Filled.AudioFile, "Audio", modifier = Modifier.size(36.dp))
        }
    }
}

/** Full view of a media item: zoomable image, or a player for video/audio. */
@Composable
/**
 * [fitWidth]: for images, size the box to the image instead of the box's own height:
 * full width, height from the image's shape, but no taller than it is wide (so tall
 * images get space at the sides instead).
 */
fun MediaFull(media: Media, modifier: Modifier = Modifier, fitWidth: Boolean = false) {
    val fileId = media.fileId
    when {
        media.status == MediaStatus.PENDING -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        fileId == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(media.info.error ?: "Failed", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        }
        media.kind == MediaKind.IMAGE && fitWidth -> {
            var aspect by remember(fileId) { mutableFloatStateOf(1f) }
            ZoomableImage(MediaRef(fileId), null, modifier.aspectRatio(maxOf(aspect, 1f)), onAspect = { aspect = it })
        }
        media.kind == MediaKind.IMAGE -> ZoomableImage(MediaRef(fileId), null, modifier)
        else -> EncryptedPlayer(fileId, video = media.kind == MediaKind.VIDEO, modifier = modifier)
    }
}

/**
 * Plays an encrypted video or audio file. MediaPlayer reads decrypted bytes on demand,
 * so no plaintext copy is written anywhere. Everything opens paused; videos also open
 * muted, with an unmute button.
 */
@Composable
fun EncryptedPlayer(fileId: String, video: Boolean, modifier: Modifier = Modifier) {
    val store = LocalApp.current.session.store ?: return
    val player = remember(fileId) { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var error by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(video) }

    DisposableEffect(fileId) {
        runCatching {
            player.setDataSource(store.files.dataSource(fileId))
            player.isLooping = video
            player.setOnPreparedListener {
                prepared = true
                if (it.videoWidth > 0 && it.videoHeight > 0) aspect = it.videoWidth.toFloat() / it.videoHeight
                if (muted) it.setVolume(0f, 0f)
                // Start paused. For videos, seeking shows the first frame instead of a black box.
                if (video) it.seekTo(0)
            }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, what, _ -> error = "Can't play this file ($what)"; true }
            player.prepareAsync()
        }.onFailure { error = it.message ?: "Can't play this file" }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(playing, prepared) {
        while (prepared) {
            runCatching {
                val d = player.duration
                if (d > 0) position = player.currentPosition.toFloat() / d
            }
            delay(250)
        }
    }

    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        if (video) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                runCatching {
                                    player.setSurface(Surface(st))
                                    if (prepared && !player.isPlaying) player.seekTo(player.currentPosition)
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                runCatching { player.setSurface(null) }
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AudioFile, null, modifier = Modifier.size(72.dp))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(enabled = prepared, onClick = {
                if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true }
            }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
            }
            Slider(
                value = position,
                enabled = prepared,
                onValueChange = { v ->
                    position = v
                    runCatching { player.seekTo((v * player.duration).toInt()) }
                },
                modifier = Modifier.weight(1f),
            )
            if (video) {
                IconButton(enabled = prepared, onClick = {
                    muted = !muted
                    runCatching { if (muted) player.setVolume(0f, 0f) else player.setVolume(1f, 1f) }
                }) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Unmute" else "Mute",
                    )
                }
            }
        }
    }
}
