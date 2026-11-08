package au.net.maz.privacyai.ui.generate

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.net.maz.privacyai.data.media.PhotoImport
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaInfo
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.chat.rememberPhotoSources
import au.net.maz.privacyai.ui.chat.toast
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.kept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class InputTab(val label: String) { ROLEPLAY("Roleplay"), DEVICE("Device"), GALLERY("Gallery") }

/**
 * Picks a file for a workflow's file input. Tabs: Roleplay (only in a roleplay, and then
 * the default), Device (the phone's media; needs the media permission), Gallery.
 * Device picks and the camera are copied (encrypted) into the roleplay's gallery in a
 * roleplay, otherwise the main Gallery. The chosen media id goes back via [resultKey].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputPickerScreen(owner: String, kind: MediaKind, resultKey: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inRoleplay = MediaOwner.roleplayId(owner) != null
    // Where device picks and photos are kept.
    val saveTo = if (inRoleplay) owner else MediaOwner.GALLERY
    val tabs = if (inRoleplay) InputTab.entries else listOf(InputTab.DEVICE, InputTab.GALLERY)
    var tab by kept("tab") { 0 }
    var importing by remember { mutableStateOf(false) }

    fun choose(id: String) { app.results.put(resultKey, id); nav.popBackStack() }

    val photos = rememberPhotoSources(saveTo) { m -> choose(m.id) }

    // Device media permission.
    val permissions = devicePermissions(kind)
    var granted by remember { mutableStateOf(hasDeviceAccess(context, kind)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasDeviceAccess(context, kind)
    }
    fun requestIfNeeded() { if (!granted) askPermission.launch(permissions) }

    val pager = rememberPagerState(initialPage = tab) { tabs.size }
    LaunchedEffect(pager.currentPage) { tab = pager.currentPage }
    // Device is the default outside a roleplay: ask straight away on opening.
    LaunchedEffect(Unit) { if (tabs[pager.currentPage] == InputTab.DEVICE) requestIfNeeded() }

    AppScaffold(
        title = "Choose ${kind.label.lowercase()}",
        onBack = { nav.popBackStack() },
        actions = {
            if (kind == MediaKind.IMAGE) {
                IconButton(onClick = { photos.takePhoto() }) { Icon(Icons.Filled.PhotoCamera, "Take photo") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = pager.currentPage) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = {
                            if (t == InputTab.DEVICE) requestIfNeeded()
                            scope.launch { pager.animateScrollToPage(i) }
                        },
                        text = { Text(t.label) },
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { tabs[it] }) { page ->
                    when (tabs[page]) {
                        InputTab.ROLEPLAY -> StoredGrid(owner, kind, ::choose)
                        InputTab.GALLERY -> StoredGrid(MediaOwner.GALLERY, kind, ::choose)
                        InputTab.DEVICE -> if (granted) DeviceGrid(kind) { uri ->
                            if (importing) return@DeviceGrid
                            importing = true
                            scope.launch {
                                runCatching { PhotoImport.import(context, store, saveTo, uri, MediaInfo.ORIGIN_IMPORTED) }
                                    .onSuccess { m -> if (m != null) choose(m.id) else toast(context, "Couldn't add that file") }
                                    .onFailure { toast(context, "Couldn't add that file: ${it.message}") }
                                importing = false
                            }
                        } else Box(Modifier.fillMaxSize())
                    }
                }
                if (importing) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Finished items of [kind] from the app's own (encrypted) media. */
@Composable
private fun StoredGrid(owner: String, kind: MediaKind, onPick: (String) -> Unit) {
    val items by LocalStore.current.observeMedia(owner).collectAsState(initial = emptyList())
    val shown = items.filter { it.kind == kind && it.status == MediaStatus.DONE && it.fileId != null }
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Nothing here yet.") }
        return
    }
    Grid(shown, key = { it.id }) { m: Media ->
        MediaThumb(m, Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(m.id) })
    }
}

private data class DeviceItem(val uri: Uri, val name: String)

/** The phone's own media of [kind], newest first, read through MediaStore. */
@Composable
private fun DeviceGrid(kind: MediaKind, onPick: (Uri) -> Unit) {
    val context = LocalContext.current
    val items by produceState<List<DeviceItem>?>(null, kind) {
        value = withContext(Dispatchers.IO) { runCatching { queryDevice(context, kind) }.getOrDefault(emptyList()) }
    }
    val list = items
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Nothing on the device.") }
        else -> Grid(list, key = { it.uri.toString() }) { item: DeviceItem ->
            DeviceThumb(item, kind, Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(item.uri) })
        }
    }
}

@Composable
private fun <T> Grid(items: List<T>, key: (T) -> Any, cell: @Composable (T) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = key) { cell(it) }
    }
}

/** A thumbnail of a device file, decoded in memory only. */
@Composable
private fun DeviceThumb(item: DeviceItem, kind: MediaKind, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadThumbnail(context, item.uri, kind) }.getOrNull() }
    }
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (kind != MediaKind.IMAGE) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(if (kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.AudioFile, null, Modifier.size(32.dp))
                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (b != null && kind == MediaKind.VIDEO) {
            Icon(Icons.Filled.Movie, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

private fun loadThumbnail(context: android.content.Context, uri: Uri, kind: MediaKind): Bitmap? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
    kind == MediaKind.IMAGE -> {
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }
    kind == MediaKind.VIDEO -> {
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    }
    else -> null
}

private fun collection(kind: MediaKind): Uri = when (kind) {
    MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
}

private fun queryDevice(context: android.content.Context, kind: MediaKind): List<DeviceItem> {
    val base = collection(kind)
    val out = mutableListOf<DeviceItem>()
    context.contentResolver.query(
        base,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        null, null,
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        while (c.moveToNext()) {
            out += DeviceItem(ContentUris.withAppendedId(base, c.getLong(idCol)), c.getString(nameCol).orEmpty())
        }
    }
    return out
}

/** The permissions to ask for to read the phone's media of [kind]. */
private fun devicePermissions(kind: MediaKind): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when (kind) {
        MediaKind.IMAGE -> visual(Manifest.permission.READ_MEDIA_IMAGES)
        MediaKind.VIDEO -> visual(Manifest.permission.READ_MEDIA_VIDEO)
        MediaKind.AUDIO -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    }
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** On Android 14+, "Select photos" grants only the chosen ones; that counts too. */
private fun visual(p: String): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) arrayOf(p, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    else arrayOf(p)

private fun hasDeviceAccess(context: android.content.Context, kind: MediaKind): Boolean {
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when (kind) {
            MediaKind.IMAGE -> has(Manifest.permission.READ_MEDIA_IMAGES) || partial(::has)
            MediaKind.VIDEO -> has(Manifest.permission.READ_MEDIA_VIDEO) || partial(::has)
            MediaKind.AUDIO -> has(Manifest.permission.READ_MEDIA_AUDIO)
        }
        else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun partial(has: (String) -> Boolean): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
