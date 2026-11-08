package au.net.maz.privacyai.ui.roleplay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Attachment
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.llm.Prompts
import au.net.maz.privacyai.llm.stripThink
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.chat.ChatInput
import au.net.maz.privacyai.ui.chat.MessageBubble
import au.net.maz.privacyai.ui.chat.copyText
import au.net.maz.privacyai.ui.chat.openMedia
import au.net.maz.privacyai.ui.chat.rememberPhotoSources
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.MenuItem
import au.net.maz.privacyai.ui.components.PrivateText
import au.net.maz.privacyai.ui.kept
import au.net.maz.privacyai.ui.screenState
import au.net.maz.privacyai.ui.theme.bubbleColours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A roleplay's conversation. Everything about the roleplay is reached from here: the
 * hamburger menu (details, its gallery, export, delete) or the conversation itself.
 */
@Composable
fun RoleplayChatScreen(id: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rpState by store.observeRoleplay(id).collectAsState(initial = null)
    val tasks by app.tasks.ids.collectAsState()
    val busy = app.engine.replyTaskId(id) in tasks
    val snackbar = remember { SnackbarHostState() }

    var input by kept("input") { "" }
    val attachments = screenState("attachments") { mutableStateListOf<Attachment>() }
    var speaker by kept<String?>("speaker") { null }
    var editing by kept<String?>("editing") { null }
    var editText by kept("editText") { "" }
    val pickKey by kept("pickKey") { Routes.newResultKey() }
    val owner = "rp:$id"
    val photos = rememberPhotoSources(owner) { attachments += Attachment(it.id) }
    val listState = rememberLazyListState()

    // An image picked from the roleplay gallery comes back here.
    val results by app.results.values.collectAsState()
    LaunchedEffect(results[pickKey]) {
        app.results.take<String>(pickKey)?.let { mediaId ->
            val m = store.media(mediaId)
            if (attachments.none { it.mediaId == mediaId }) attachments += Attachment(mediaId, m?.info?.description.orEmpty())
        }
    }
    LaunchedEffect(Unit) { app.engine.clearStalePending(id, isRoleplay = true) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val rp = rpState ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write("${rp.title}\n\n${Prompts.transcript(rp)}\n")
                }
            }.onSuccess { snackbar.showSnackbar("Exported") }.onFailure { snackbar.showSnackbar("Export failed") }
        }
    }

    val rp = rpState ?: run {
        AppScaffold(title = "Roleplay", onBack = { nav.popBackStack() }) {}
        return
    }
    val messages = rp.messages
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    AppScaffold(
        title = rp.title.ifBlank { "Roleplay" },
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        menu = listOf(
            MenuItem("Edit chat details") { nav.navigate(Routes.rpSetup(id)) },
            MenuItem("Roleplay gallery") { nav.navigate(Routes.gallery(owner)) },
            MenuItem("Export conversation") { exporter.launch("${rp.title.ifBlank { "roleplay" }}.txt") },
            MenuItem("Delete roleplay") {
                scope.launch {
                    val r = snackbar.showSnackbar("Delete this roleplay and all its images?", actionLabel = "Delete", withDismissAction = true)
                    if (r == SnackbarResult.ActionPerformed) {
                        app.engine.stop(id)
                        store.deleteRoleplay(id)
                        nav.popBackStack()
                    }
                }
            },
        ),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(messages, key = { it.id }) { m ->
                    val ch = rp.character(m.speaker)
                    val isLast = m.id == messages.lastOrNull()?.id
                    val colours = ch?.let { bubbleColours(it.colour) }
                    MessageBubble(
                        versionIndex = m.active,
                        versionCount = m.versions.size,
                        onVersion = { i ->
                            scope.launch { store.updateRoleplay(id) { r -> r.copy(messages = r.messages.map { if (it.id == m.id) it.showVersion(i) else it }) } }
                        },
                        text = m.text,
                        isUser = m.isUser,
                        background = colours?.background ?: MaterialTheme.colorScheme.primaryContainer,
                        textColour = colours?.text ?: MaterialTheme.colorScheme.onPrimaryContainer,
                        name = Prompts.speakerName(rp, m),
                        nameColour = colours?.name ?: MaterialTheme.colorScheme.primary,
                        attachments = m.attachments.map { it.mediaId },
                        pending = m.pending,
                        error = m.error,
                        onOpenMedia = openMedia(),
                        actions = buildList {
                            add(MenuItem("Copy") { copyText(context, stripThink(m.text)) })
                            if (!busy) {
                                add(MenuItem("Edit") { editing = m.id; editText = m.text })
                                if (!m.isUser && isLast) {
                                    add(MenuItem("Continue") { app.engine.continueRoleplay(id, m.id) })
                                    add(MenuItem("Regenerate") { app.engine.regenerateRoleplay(id, m.id) })
                                }
                                add(MenuItem("Delete") {
                                    scope.launch { store.updateRoleplay(id) { r -> r.copy(messages = r.messages.filter { it.id != m.id }) } }
                                })
                            }
                            if (m.attachments.any { it.description.isBlank() }) add(MenuItem("Describe images") {
                                describeInto(app, store, scope, id, m.id, rp.effectiveDescriptionModel) { err ->
                                    scope.launch { snackbar.showSnackbar(err) }
                                }
                            })
                        },
                    )
                }
            }
            val editingId = editing
            if (editingId != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        OutlinedTextField(editText, { editText = it }, label = { Text("Edit message") }, maxLines = 8,
                            keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { editing = null }) { Text("Cancel") }
                            Button(onClick = {
                                val t = editText
                                scope.launch { store.updateRoleplay(id) { r -> r.copy(messages = r.messages.map { if (it.id == editingId) it.withText(t) else it }) } }
                                editing = null
                            }) { Text("Save") }
                        }
                    }
                }
            } else {
                ChatInput(
                    text = input,
                    onText = { input = it },
                    attachments = attachments.map { it.mediaId },
                    onRemoveAttachment = { mid -> attachments.removeAll { it.mediaId == mid } },
                    plusMenu = listOf(
                        MenuItem("Choose from roleplay gallery") { nav.navigate(Routes.gallery(owner, pickKey)) },
                        MenuItem("Take photo") { photos.takePhoto() },
                        MenuItem("Choose from device") { photos.pickFromDevice() },
                        MenuItem("Generate image...") { nav.navigate(Routes.section(MediaKind.IMAGE, owner)) },
                        MenuItem("Generate video...") { nav.navigate(Routes.section(MediaKind.VIDEO, owner)) },
                    ),
                    busy = busy,
                    onSend = {
                        val text = input
                        val atts = attachments.toList()
                        input = ""
                        attachments.clear()
                        app.engine.sendRoleplay(id, text, atts, speaker)
                    },
                    onStop = { app.engine.stop(id) },
                    usage = rp.usage,
                    buttons = {
                        // Impersonate: the LLM writes your next line (continuing anything typed).
                        val impersonating = "impersonate:$id" in tasks
                        if (impersonating) {
                            IconButton(onClick = { app.tasks.cancel("impersonate:$id") }) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (!busy) {
                            IconButton(onClick = {
                                app.engine.impersonate(
                                    id, input,
                                    onText = { input = it },
                                    onDone = { e ->
                                        if (e != null && e !is CancellationException) scope.launch { snackbar.showSnackbar(e.message ?: "Impersonate failed") }
                                    },
                                )
                            }) { Icon(Icons.Filled.RecordVoiceOver, "Impersonate (write my reply)") }
                        }
                    },
                    extra = {
                        if (rp.characters.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                                item {
                                    FilterChip(selected = speaker == null, onClick = { speaker = null }, label = { Text("Auto") })
                                }
                                items(rp.characters, key = { it.id }) { c ->
                                    FilterChip(selected = speaker == c.id, onClick = { speaker = c.id }, label = { Text(c.name) })
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Generate descriptions for a message's images that don't have one, and save them on the
 * message and on the media item (so they're reused wherever the image is attached).
 */
private fun describeInto(
    app: au.net.maz.privacyai.PrivacyApp,
    store: au.net.maz.privacyai.data.Store,
    scope: kotlinx.coroutines.CoroutineScope,
    rpId: String,
    messageId: String,
    model: String,
    onError: (String) -> Unit,
) {
    scope.launch {
        val msg = store.roleplay(rpId)?.messages?.firstOrNull { it.id == messageId } ?: return@launch
        msg.attachments.filter { it.description.isBlank() }.forEach { a ->
            var text = ""
            app.engine.complete(
                taskId = "describe:${a.mediaId}",
                request = { app.engine.describeRequest(a.mediaId, model) },
                onText = { text = it },
                onDone = { e ->
                    if (e != null) {
                        if (e !is CancellationException) onError(e.message ?: "Couldn't describe the image")
                    } else app.appScope.launch {
                        store.updateMedia(a.mediaId) { it.copy(info = it.info.copy(description = text)) }
                        store.updateRoleplay(rpId) { r ->
                            r.copy(messages = r.messages.map { m ->
                                if (m.id != messageId) m
                                else m.copy(attachments = m.attachments.map { if (it.mediaId == a.mediaId) it.copy(description = text) else it })
                            })
                        }
                    }
                },
            )
        }
    }
}
