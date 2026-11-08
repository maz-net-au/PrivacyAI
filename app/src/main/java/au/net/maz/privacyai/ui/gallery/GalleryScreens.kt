package au.net.maz.privacyai.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
@Composable
fun MediaGridScreen(owner: String, resultKey: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val picking = resultKey.isNotBlank()
    val items by store.observeMedia(owner).collectAsState(initial = emptyList())
    var filter by kept<MediaKind?>("filter") { null }
    val photos = rememberPhotoSources(owner, allowVideo = !picking) { m ->
        if (picking) { app.results.put(resultKey, m.id); nav.popBackStack() }
    }
    val title = when {
        picking -> "Choose"
        owner == MediaOwner.GALLERY -> "Gallery"
        else -> "Roleplay gallery"
    }
    val shown = items.filter { m ->
        (filter == null || m.kind == filter) && (!picking || (m.status == MediaStatus.DONE && m.kind == MediaKind.IMAGE))
    }
    AppScaffold(
        title = title,
        onBack = { nav.popBackStack() },
        menu = listOf(
            MenuItem("Add from device") { photos.pickFromDevice() },
            MenuItem("Take photo") { photos.takePhoto() },
        ),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!picking) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    item { FilterChip(filter == null, { filter = null }, { Text("All") }) }
                    items(MediaKind.entries) { k -> FilterChip(filter == k, { filter = k }, { Text(k.label) }) }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (picking) "No images here yet. Use the menu to add one from the device." else "Nothing here yet.")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { m ->
                    MediaThumb(m, Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        if (picking) { app.results.put(resultKey, m.id); nav.popBackStack() }
                        else nav.navigate(Routes.viewer(m.id))
                    })
                }
            }
        }
    }
}

/** One media item: view/play it, its details, and Regenerate / Export / Delete. */
@Composable
fun ViewerScreen(id: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val media by store.observeMediaItem(id).collectAsState(initial = null)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val m = media ?: return@rememberLauncherForActivityResult
        val fileId = m.fileId ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> store.files.openInput(fileId).use { it.copyTo(out) } }
                }
            }.onSuccess { snackbar.showSnackbar("Exported") }.onFailure { snackbar.showSnackbar("Export failed: ${it.message}") }
        }
    }

    val m = media
    AppScaffold(title = m?.info?.workflowName?.ifBlank { null } ?: m?.kind?.label ?: "", onBack = { nav.popBackStack() }, snackbar = snackbar) { pad ->
        if (m == null) return@AppScaffold
        val actions = @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                val wf = m.info.workflowId
                if (wf != null) OutlinedButton(onClick = { nav.navigate(Routes.form(wf, m.owner, from = m.id)) }) { Text("Regenerate") }
                if (m.status == MediaStatus.DONE) OutlinedButton(onClick = {
                    exporter.launch("privacyai-${m.createdAt}.${ComfyRunner.extFor(m.mime)}")
                }) { Text("Export") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = snackbar.showSnackbar(
                            if (m.status == MediaStatus.PENDING) "Cancel and delete this item?" else "Delete this item?",
                            actionLabel = "Delete", withDismissAction = true,
                        )
                        if (r == SnackbarResult.ActionPerformed) {
                            app.runner?.cancelForMedia(m.id)
                            store.deleteMedia(m.id)
                            nav.popBackStack()
                        }
                    }
                }) { Text("Delete") }
            }
        }
        val details = @Composable { Details(m, actions) }
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    MediaFull(m, Modifier.weight(1.4f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { details() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MediaFull(m, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp))
                    Column(Modifier.padding(12.dp)) { details() }
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
