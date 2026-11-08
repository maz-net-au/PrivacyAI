package au.net.maz.privacyai.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
    val items by store.observeMedia(owner).collectAsState(initial = emptyList())
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
            MenuItem("Take photo") { photos.takePhoto() },
        ),
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
    val single by store.observeMediaItem(id).collectAsState(initial = null)
    val galleryItems by remember(owner) {
        if (owner.isBlank()) kotlinx.coroutines.flow.flowOf(null) else store.observeMedia(owner)
    }.collectAsState(initial = null)
    val list: List<Media>? = if (owner.isBlank()) single?.let { listOf(it) }
    else galleryItems?.filter { kind == null || it.kind == kind }
    if (list == null) {
        AppScaffold(title = "", onBack = { nav.popBackStack() }) {}
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    val start = remember { list.indexOfFirst { it.id == id }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { list.size }
    val m = list[pager.currentPage.coerceIn(0, list.lastIndex)]

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

    AppScaffold(title = m.info.workflowName.ifBlank { null } ?: m.kind.label, onBack = { nav.popBackStack() }, snackbar = snackbar) { pad ->
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize(), key = { list[it].id }) { page ->
            val item = list[page]
            val actions = @Composable {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    val wf = item.info.workflowId
                    if (wf != null) OutlinedButton(onClick = { nav.navigate(Routes.form(wf, item.owner, from = item.id)) }) { Text("Regenerate") }
                    if (item.status == MediaStatus.DONE) OutlinedButton(onClick = {
                        exporter.launch("privacyai-${item.createdAt}.${ComfyRunner.extFor(item.mime)}")
                    }) { Text("Export") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                if (item.status == MediaStatus.PENDING) "Cancel and delete this item?" else "Delete this item?",
                                actionLabel = "Delete", withDismissAction = true,
                            )
                            if (r == SnackbarResult.ActionPerformed) {
                                app.runner?.cancelForMedia(item.id)
                                store.deleteMedia(item.id)
                                // Stay on the next item; leave if that was the last one.
                                if (list.size <= 1) nav.popBackStack()
                            }
                        }
                    }) { Text("Delete") }
                }
            }
            val details = @Composable { Details(item, actions) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize()) {
                        MediaFull(item, Modifier.weight(1.4f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { details() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MediaFull(item, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp))
                        Column(Modifier.padding(12.dp)) { details() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(m: Media, actions: @Composable () -> Unit) {
    val context = LocalContext.current
    actions()
    m.info.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
    if (m.status == MediaStatus.PENDING) Text("Generating...", style = MaterialTheme.typography.bodyMedium)
    m.info.generationMs?.let { ms ->
        val label = if (m.status == MediaStatus.FAILED) "Failed after" else "Generation time"
        Text("$label: ${formatDuration(ms)}", style = MaterialTheme.typography.bodyMedium)
    }
    if (m.info.description.isNotBlank()) {
        Text("Prompt / description", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(m.info.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { copyText(context, m.info.description) })
    }
    if (m.info.values.isNotEmpty()) {
        Text("Settings", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        m.info.values.filter { it.value != m.info.description }.forEach { v ->
            Text("${v.input}: ${v.value}", style = MaterialTheme.typography.bodySmall)
        }
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
