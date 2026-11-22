package au.net.maz.privacyai.ui.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.mutableStateListOf
import au.net.maz.privacyai.ui.screenState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.comfy.ComfyRunner
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.chat.copyText
import au.net.maz.privacyai.ui.chat.rememberPhotoSources
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.MediaFull
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.components.MenuItem
import au.net.maz.privacyai.ui.kept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A grid of media: the main Gallery, a roleplay's own gallery, or (with [resultKey]) a
 * picker that returns the chosen item. Newest first; in-progress items show a placeholder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridScreen(owner: String, resultKey: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val picking = resultKey.isNotBlank()
    val allItems by store.observeMedia(owner).collectAsState(initial = emptyList())
    val isMain = owner == MediaOwner.GALLERY
    // A newly opened gallery starts with spoilers hidden (not on returning from the viewer).
    // Picking uses its own switch, for that one pick, leaving the gallery's alone.
    var pickSpoilers by kept("pickSpoilers") { false }
    if (!picking) screenState("spoilersOff") { store.showSpoilers.value = false }
    val galleryShows by store.showSpoilers.collectAsState()
    val showSpoilers = if (picking) pickSpoilers else galleryShows
    val items = if (isMain && !showSpoilers) allItems.filterNot { it.info.spoiler } else allItems
    var tab by kept("tab") { 0 }
    val photos = rememberPhotoSources(owner, allowVideo = !picking) { m ->
        if (picking) { app.results.put(resultKey, m.id); nav.popBackStack() }
    }
    val title = when {
        picking -> "Choose"
        owner == MediaOwner.GALLERY -> "Gallery"
        else -> "Roleplay gallery"
    }
    AppScaffold(
        title = title,
        onBack = { nav.popBackStack() },
        menu = listOf(
            MenuItem("Add from device") { photos.pickFromDevice() },
            MenuItem("Add from files") { photos.pickFile() },
            MenuItem("Take photo") { photos.takePhoto() },
        ),
        actions = {
            if (isMain) IconButton(onClick = {
                if (picking) pickSpoilers = !pickSpoilers else store.showSpoilers.value = !showSpoilers
            }) {
                if (showSpoilers) Icon(Icons.Filled.Visibility, "Hide spoilers")
                else Icon(Icons.Filled.VisibilityOff, "Show spoilers")
            }
        },
    ) { pad ->
        val open: (Media) -> Unit = { m ->
            if (picking) { app.results.put(resultKey, m.id); nav.popBackStack() }
            else nav.navigate(Routes.viewer(m.id, owner, GalleryTab.entries[tab].kind))
        }
        if (picking) {
            // Picking a reference image: images only, no tabs.
            MediaGrid(
                items.filter { it.status == MediaStatus.DONE && it.kind == MediaKind.IMAGE },
                "No images here yet. Use the menu to add one from the device.",
                open,
                Modifier.padding(pad),
            )
            return@AppScaffold
        }
        val pager = rememberPagerState(initialPage = tab) { GalleryTab.entries.size }
        LaunchedEffect(pager.currentPage) { tab = pager.currentPage }
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = pager.currentPage) {
                GalleryTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text(t.label) },
                        icon = { Icon(t.icon, null) },
                    )
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                val kind = GalleryTab.entries[page].kind
                MediaGrid(
                    if (kind == null) items else items.filter { it.kind == kind },
                    "Nothing here yet.",
                    open,
                )
            }
        }
    }
}

/** The Gallery's tabs. */
private enum class GalleryTab(val label: String, val icon: ImageVector, val kind: MediaKind?) {
    ALL("All", Icons.Filled.PhotoLibrary, null),
    IMAGES("Images", Icons.Filled.Image, MediaKind.IMAGE),
    AUDIO("Audio", Icons.Filled.AudioFile, MediaKind.AUDIO),
    VIDEO("Video", Icons.Filled.Movie, MediaKind.VIDEO),
}

/** A grid of thumbnails, newest first, or [empty] if there are none. */
@Composable
private fun MediaGrid(shown: List<Media>, empty: String, onOpen: (Media) -> Unit, modifier: Modifier = Modifier) {
    if (shown.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(empty) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(shown, key = { it.id }) { m ->
            MediaThumb(m, Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(m) })
        }
    }
}

/**
 * One media item: view/play it, its details, and Regenerate / Export / Delete.
 * Opened from a gallery ([owner], and [kind] for its tab), swiping left/right moves through
 * that gallery. Swipes aren't navigation steps, so Back always leaves the viewer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(id: String, owner: String, kind: MediaKind?) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The items to swipe through: the gallery (as its tab showed it), or just this one.
    // Just the opened item: null while loading, empty once it's gone (deleted).
    val single by remember(id) { store.observeMediaItem(id).map { listOfNotNull(it) } }.collectAsState(initial = null)
    val galleryItems by remember(owner) {
        if (owner.isBlank()) kotlinx.coroutines.flow.flowOf(null) else store.observeMedia(owner)
    }.collectAsState(initial = null)
    val showSpoilers by store.showSpoilers.collectAsState()
    // Marked as a spoiler here: stays in view rather than vanishing from under the switch.
    val markedHere = remember { mutableStateListOf<String>() }
    // Opened on an item that isn't in that gallery (e.g. a chat attachment from elsewhere):
    // show just that item. Decided once, when the gallery first loads, and kept while away
    // (e.g. on Regenerate's form). It used to be decided again on coming back, and if the
    // item the viewer was opened on had been deleted meanwhile, it then waited for that
    // missing item forever: an empty page with no title.
    var notInGallery by kept<Boolean?>("notInGallery") { null }
    if (notInGallery == null && galleryItems != null) notInGallery = galleryItems!!.none { it.id == id }
    val gallery = galleryItems?.filter {
        (kind == null || it.kind == kind) &&
            (owner != MediaOwner.GALLERY || showSpoilers || !it.info.spoiler || it.id in markedHere)
    }
    val list: List<Media>? = when {
        owner.isBlank() -> single
        // The lone item has gone: show the gallery rather than nothing.
        notInGallery == true -> if (single?.isEmpty() == true) gallery else single
        else -> gallery
    }
    if (list == null) {
        AppScaffold(title = "", onBack = { nav.popBackStack() }) {}
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    // The item being looked at, kept while away (e.g. on Regenerate's form). Coming back used
    // to restore the old page *number*, which after a new generation went in at the front
    // pointed at a different item (or a blank page) instead of the one you were on.
    var currentId by kept("currentId") { id }
    // Regenerate's form hands back the new (in-progress) item: show that on return.
    val regenKey by kept("regenKey") { Routes.newResultKey() }
    app.results.take<String>(regenKey)?.let { newId -> if (list.any { it.id == newId }) currentId = newId }

    val pageCount by rememberUpdatedState(list.size)
    val pager = remember { ViewerPagerState(list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { pageCount } }
    val m = list[pager.currentPage.coerceIn(0, list.lastIndex)]

    // The gallery changes underneath the viewer (new generations go in at the front, finished
    // or deleted ones move or go), so page numbers shift. Stay on the item being looked at.
    LaunchedEffect(pager.currentPage) { list.getOrNull(pager.currentPage)?.let { currentId = it.id } }
    LaunchedEffect(list.map { it.id }) {
        val i = list.indexOfFirst { it.id == currentId }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val fileId = m.fileId ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> store.files.openInput(fileId).use { it.copyTo(out) } }
                }
            }.onSuccess { snackbar.showSnackbar("Exported") }.onFailure { snackbar.showSnackbar("Export failed: ${it.message}") }
        }
    }

    // Full screen: tap the picture to show just the media (no bars, text or buttons) and tap
    // again (or Back) to leave. Swiping still moves through the gallery. The layout below is
    // the same tree either way, so a playing video keeps playing.
    var fullScreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullScreen) { fullScreen = false }
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(fullScreen, activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullScreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val toggleFull = { fullScreen = !fullScreen }

    AppScaffold(
        title = m.info.workflowName.ifBlank { null } ?: m.kind.label,
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        showTopBar = !fullScreen,
    ) { pad ->
        // The pager can ask for a page from just before the list shrank: never index past the end.
        HorizontalPager(
            pager,
            Modifier
                .padding(if (fullScreen) PaddingValues(0.dp) else pad)
                .fillMaxSize()
                .background(if (fullScreen) Color.Black else Color.Transparent),
            key = { list.getOrNull(it)?.id ?: "gone:$it" },
        ) { page ->
            val item = list.getOrNull(page) ?: return@HorizontalPager
            // Audio has no picture to fill the screen with.
            val onTap: (() -> Unit)? = if (item.kind == MediaKind.AUDIO) null else toggleFull
            val actions = @Composable {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val running = item.status == MediaStatus.PENDING
                            val r = snackbar.showSnackbar(
                                if (running) "Cancel this generation? It won't be kept." else "Delete this item?",
                                actionLabel = if (running) "Cancel it" else "Delete", withDismissAction = true,
                            )
                            if (r == SnackbarResult.ActionPerformed) {
                                app.runner?.cancelForMedia(item.id)
                                store.deleteMedia(item.id)
                                // Stay on the next item; leave if that was the last one.
                                if (list.size <= 1) nav.popBackStack()
                            }
                        }
                    }) { Text(if (item.status == MediaStatus.PENDING) "Cancel" else "Delete") }
                    if (item.status == MediaStatus.DONE) OutlinedButton(onClick = {
                        exporter.launch("privacyai-${item.createdAt}.${ComfyRunner.extFor(item.mime)}")
                    }) { Text("Export") }
                    val wf = item.info.workflowId
                    if (wf != null) OutlinedButton(onClick = { nav.navigate(Routes.form(wf, item.owner, from = item.id, result = regenKey)) }) { Text("Regenerate") }
                }
            }
            val details = @Composable {
                Details(item, actions, onSpoiler = { on ->
                    if (on) markedHere += item.id
                    scope.launch { store.updateMedia(item.id) { it.copy(info = it.info.copy(spoiler = on)) } }
                })
            }
            val scroll = rememberScrollState()
            LaunchedEffect(fullScreen) { if (fullScreen) scroll.scrollTo(0) }
            CompositionLocalProvider(LocalContentColor provides if (fullScreen) Color.White else LocalContentColor.current) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenHeight = maxHeight
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize()) {
                        MediaFull(item, Modifier.weight(if (fullScreen) 1f else 1.4f).fillMaxHeight(), onTap = onTap, fill = fullScreen)
                        if (!fullScreen) {
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(12.dp)) { details() }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !fullScreen)) {
                        // Images: full width, as tall as their shape needs (capped at square).
                        // Full screen: the whole screen, for images and videos.
                        MediaFull(
                            item,
                            when {
                                fullScreen -> Modifier.fillMaxWidth().height(screenHeight)
                                item.kind == MediaKind.IMAGE -> Modifier.fillMaxWidth()
                                else -> Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp)
                            },
                            fitWidth = !fullScreen,
                            onTap = onTap,
                            fill = fullScreen,
                        )
                        if (!fullScreen) Column(Modifier.padding(12.dp)) { details() }
                    }
                }
            }
            }
        }
    }
}

/**
 * A pager state that isn't put in saved state: it starts where it's told every time the
 * viewer is shown, instead of restoring a page number that may now be a different item.
 */
@OptIn(ExperimentalFoundationApi::class)
private class ViewerPagerState(page: Int, private val count: () -> Int) : PagerState(page, 0f) {
    override val pageCount: Int get() = count()
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Details(m: Media, actions: @Composable () -> Unit, onSpoiler: (Boolean) -> Unit) {
    val context = LocalContext.current
    actions()
    m.info.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
    if (m.status == MediaStatus.PENDING) Text("Generating...", style = MaterialTheme.typography.bodyMedium)
    if (m.info.description.isNotBlank()) {
        Text("Prompt / description", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(m.info.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { copyText(context, m.info.description) })
    }
    if (m.owner == MediaOwner.GALLERY) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Spoiler / NSFW", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = m.info.spoiler, onCheckedChange = onSpoiler)
        }
    }
    if (m.info.values.isNotEmpty()) {
        Text("Settings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        m.info.values.filter { it.value != m.info.description }.forEach { v ->
            Text("${v.input}: ${v.value}", style = MaterialTheme.typography.bodySmall)
        }
    }
    m.info.generationMs?.let { ms ->
        val label = if (m.status == MediaStatus.FAILED) "Failed after" else "Generation time"
        Text("$label: ${formatDuration(ms)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    if (m.info.files.isNotEmpty()) {
        Text("Input images: ${m.info.files.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

/** e.g. "850 ms", "42.3 s", "1 m 23 s", "1 h 2 m". */
internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        ms < 1000 -> "$ms ms"
        s < 60 -> "%.1f s".format(ms / 1000.0)
        s < 3600 -> "${s / 60} m ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} m"
    }
}
