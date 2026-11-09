package au.net.maz.privacyai.ui.roleplay

import au.net.maz.privacyai.data.model.MediaInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import au.net.maz.privacyai.ui.chat.FollowNewest
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // The message whose buttons are showing (tap a message to show/hide them).
    var selectedId by kept<String?>("selected") { null }
    // Generated images shown in the tray so far (they start hidden). Memory only.
    val revealed = screenState("revealed") { mutableStateListOf<String>() }
    val attachments = screenState("attachments") { mutableStateListOf<Attachment>() }
    var speaker by kept<String?>("speaker") { null }
    var editing by kept<String?>("editing") { null }
    var editText by kept("editText") { "" }
    val pickKey by kept("pickKey") { Routes.newResultKey() }
    val owner = "rp:$id"
    val photos = rememberPhotoSources(owner) { attachments += Attachment(it.id) }
    val listState = rememberLazyListState()

    // An image picked from the roleplay gallery comes back here. Collected in one long-lived
    // effect: an effect keyed on the result was cancelled by taking the result (the key
    // changes) while it was still looking the image up, so the pick could be lost.
    LaunchedEffect(pickKey) {
        app.results.values.collect { values ->
            if (values[pickKey] == null) return@collect
            val mediaId = app.results.take<String>(pickKey) ?: return@collect
            if (attachments.none { it.mediaId == mediaId }) {
                val description = store.media(mediaId)?.info?.description.orEmpty()
                attachments += Attachment(mediaId, description)
            }
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
    // Replies being written: every token so far, ahead of the stored copy (saved now and then).
    val live by app.engine.live.collectAsState()
    val messages = if (live.isEmpty()) rp.messages else rp.messages.map { m -> live[m.id]?.takeIf { m.pending } ?: m }
    // Show the newest text as it's written.
    FollowNewest(listState, messages.size, messages.lastOrNull()?.text?.length ?: 0)

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
                    if (editing == m.id) {
                        EditInPlace(
                            text = editText,
                            onText = { editText = it },
                            background = colours?.background ?: MaterialTheme.colorScheme.primaryContainer,
                            textColour = colours?.text ?: MaterialTheme.colorScheme.onPrimaryContainer,
                            onCancel = { editing = null },
                            onSave = {
                                val t = editText
                                scope.launch { store.updateRoleplay(id) { r -> r.copy(messages = r.messages.map { if (it.id == m.id) it.withText(t) else it }) } }
                                editing = null
                            },
                        )
                        return@items
                    }
                    MessageBubble(
                        roleplayWidths = true,
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
                        selected = selectedId == m.id,
                        onSelect = { selectedId = if (selectedId == m.id) null else m.id },
                        onCopy = { copyText(context, stripThink(m.text)) },
                        actions = buildList {
                            if (!busy) {
                                // Left to right: Delete, Edit, Continue, Regenerate (Delete kept away from the thumb).
                                add(MenuItem("Delete", Icons.Filled.Delete) {
                                    selectedId = null
                                    // Deleting the last reply also takes back the message of yours
                                    // just before it, into the input box (as the old app did).
                                    val msgs = rp.messages
                                    val prev = msgs.getOrNull(msgs.indexOfFirst { it.id == m.id } - 1)
                                        ?.takeIf { isLast && !m.isUser && it.isUser }
                                    if (prev != null) {
                                        input = prev.text
                                        attachments.clear()
                                        attachments += prev.attachments
                                    }
                                    val gone = setOfNotNull(m.id, prev?.id)
                                    scope.launch { store.updateRoleplay(id) { r -> r.copy(messages = r.messages.filter { it.id !in gone }) } }
                                })
                                add(MenuItem("Edit", Icons.Filled.Edit) { selectedId = null; editing = m.id; editText = m.text })
                                if (!m.isUser && isLast) {
                                    // Long-press: no stopping early (end-of-turn ignored, length capped).
                                    add(MenuItem("Continue", Icons.Filled.PlayArrow, onLongClick = { selectedId = null; app.engine.continueRoleplay(id, m.id, ignoreEos = true) }) { selectedId = null; app.engine.continueRoleplay(id, m.id) })
                                    add(MenuItem("Regenerate", Icons.Filled.Refresh, onLongClick = { selectedId = null; app.engine.regenerateRoleplay(id, m.id, ignoreEos = true) }) { selectedId = null; app.engine.regenerateRoleplay(id, m.id) })
                                }
                            }
                            if (m.attachments.any { it.description.isBlank() }) add(MenuItem("Describe images", Icons.Filled.ImageSearch) {
                                selectedId = null
                                describeInto(app, store, scope, id, m.id, rp.effectiveDescriptionModel) { err ->
                                    scope.launch { snackbar.showSnackbar(err) }
                                }
                            })
                        },
                    )
                }
            }
            // While a message is being edited (in place, above), the input is hidden for room.
            if (editing == null) {
                // Generations not sent yet: everything generated for this roleplay that isn't
                // in a message (or a character's reference image).
                val gallery by store.observeMedia(owner).collectAsState(initial = emptyList())
                val used = remember(rp) {
                    rp.messages.flatMap { m -> m.attachments.map { it.mediaId } }.toSet() + rp.characters.mapNotNull { it.referenceImage }
                }
                val unsent = gallery.filter {
                    it.info.origin == MediaInfo.ORIGIN_GENERATED && it.id !in used && it.kind != MediaKind.AUDIO &&
                        it.id !in rp.trayDismissed
                }
                val settings by store.settings.collectAsState(initial = null)
                GenerationTray(
                    items = unsent,
                    open = settings?.generationTrayOpen ?: true,
                    onToggleOpen = { scope.launch { store.updateSettings { it.copy(generationTrayOpen = !it.generationTrayOpen) } } },
                    revealed = revealed.toSet(),
                    onReveal = { revealed += it },
                    attached = attachments.map { it.mediaId }.toSet(),
                    onView = { m -> nav.navigate(Routes.viewer(m.id, owner)) }, // swipes through the whole roleplay gallery
                    onDismiss = { m ->
                        scope.launch {
                            store.updateRoleplay(id) { r ->
                                // Only ids still in the gallery are kept, so the list doesn't grow forever.
                                val keep = gallery.map { it.id }.toSet()
                                r.copy(trayDismissed = (r.trayDismissed.filter { it in keep } + m.id).distinct())
                            }
                        }
                    },
                    onDelete = { m ->
                        attachments.removeAll { it.mediaId == m.id }
                        scope.launch { app.runner?.cancelForMedia(m.id); store.deleteMedia(m.id) }
                    },
                )
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
                    // Send turns into Stop while a reply or Impersonate is being written.
                    busy = busy || "impersonate:$id" in tasks,
                    onSend = {
                        val text = input
                        val atts = attachments.toList()
                        input = ""
                        attachments.clear()
                        app.engine.sendRoleplay(id, text, atts, speaker)
                    },
                    onStop = { app.engine.stop(id); app.tasks.cancel("impersonate:$id") },
                    usage = rp.usage,
                    buttons = {
                        // Impersonate: the LLM writes your next line (continuing anything typed).
                        val impersonating = "impersonate:$id" in tasks
                        if (impersonating) {
                            IconButton(onClick = { app.tasks.cancel("impersonate:$id") }) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (!busy) {
                            fun impersonate(ignoreEos: Boolean) = app.engine.impersonate(
                                id, input,
                                onText = { input = it },
                                onDone = { e ->
                                    if (e != null && e !is CancellationException) scope.launch { snackbar.showSnackbar(e.message ?: "Impersonate failed") }
                                },
                                ignoreEos = ignoreEos,
                            )
                            // Tap: write my reply (or carry on from what's typed).
                            // Long-press: the same, but the model can't end the turn itself;
                            // it runs until a name prefix or the length cap.
                            val haptics = LocalHapticFeedback.current
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                                    onClick = { impersonate(false) },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        impersonate(true)
                                    },
                                    onLongClickLabel = "Impersonate without stopping early",
                                ),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.RecordVoiceOver, "Impersonate (write my reply)") }
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

/**
 * Editing a message in place: its bubble becomes a full-width editor that grows with the
 * text (like the old app), with Cancel / Save under it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EditInPlace(
    text: String,
    onText: (String) -> Unit,
    background: Color,
    textColour: Color,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus(); bring.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).background(background)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = onText,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColour),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = PrivateText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).focusRequester(focus),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bring),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onSave) { Text("Save") }
        }
    }
}
