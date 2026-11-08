package au.net.maz.privacyai.ui.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Attachment
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.UtilityChat
import au.net.maz.privacyai.llm.stripThink
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.OnCleared
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.kept
import au.net.maz.privacyai.ui.screenState
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.MenuItem
import kotlinx.coroutines.launch

/** Copy to the clipboard, flagged as sensitive so Android doesn't preview it. */
fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("PrivacyAI", text)
    if (Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}

/** List of utility chats. + starts one; long-press to delete; tap to resume. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val chats by store.chats.collectAsState(initial = null)
    AppScaffold(
        title = "Chat",
        onBack = { nav.popBackStack() },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.chat("new")) }) { Icon(Icons.Filled.Add, "New chat") }
        },
    ) { pad ->
        val list = chats ?: return@AppScaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No chats yet. Tap + to start one.")
            }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { nav.navigate(Routes.chat(c.id)) }, onLongClick = { menu = true })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(c.title.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val last = c.messages.lastOrNull()
                        if (last != null) {
                            Text(
                                (if (last.isUser) "You: " else "") + stripThink(last.text).replace('\n', ' '),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { store.deleteChat(c.id) } })
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** A utility chat. Model, character and preset come from Settings. No image generation here. */
@Composable
fun ChatScreen(routeId: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id by kept("id") { if (routeId == "new") Ids.new("chat_") else routeId }
    val chat by store.observeChat(id).collectAsState(initial = null)
    val tasks by app.tasks.ids.collectAsState()
    val busy = app.engine.replyTaskId(id) in tasks
    var input by kept("input") { "" }
    val attachments = screenState("attachments") { mutableStateListOf<String>() }
    val owner = MediaOwner.chat(id)
    val photos = rememberPhotoSources(owner) { attachments += it.id }
    val listState = rememberLazyListState()
    val messages = chat?.messages.orEmpty()

    LaunchedEffect(Unit) { app.engine.clearStalePending(id, isRoleplay = false) }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // A new chat that was never sent: drop any photos picked for it when the screen closes.
    screenState("cleanup") { OnCleared { app.appScope.launch { runCatching { if (store.chat(id) == null) store.deleteMediaOwnedBy(owner) } } } }

    fun send() {
        if (input.isBlank() && attachments.isEmpty()) return
        val text = input
        val atts = attachments.map { Attachment(it) }
        input = ""
        attachments.clear()
        scope.launch {
            if (store.chat(id) == null) store.saveChat(UtilityChat(id = id))
            app.engine.sendUtility(id, text, atts)
        }
    }

    AppScaffold(title = chat?.title?.ifBlank { null } ?: "New chat", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(messages, key = { it.id }) { m ->
                    val isLast = m.id == messages.lastOrNull()?.id
                    MessageBubble(
                        versionIndex = m.active,
                        versionCount = m.versions.size,
                        onVersion = { i ->
                            scope.launch { store.updateChat(id) { c -> c.copy(messages = c.messages.map { if (it.id == m.id) it.showVersion(i) else it }) } }
                        },
                        text = m.text,
                        isUser = m.isUser,
                        background = if (m.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        textColour = if (m.isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        attachments = m.attachments.map { it.mediaId },
                        pending = m.pending,
                        error = m.error,
                        onOpenMedia = openMedia(),
                        actions = buildList {
                            add(MenuItem("Copy") { copyText(context, stripThink(m.text)) })
                            if (!m.isUser && isLast && !busy) {
                                add(MenuItem("Continue") { app.engine.continueUtility(id, m.id) })
                                add(MenuItem("Regenerate") { app.engine.regenerateUtility(id, m.id) })
                            }
                            if (!busy) add(MenuItem("Delete") {
                                scope.launch { store.updateChat(id) { c -> c.copy(messages = c.messages.filter { it.id != m.id }) } }
                            })
                        },
                    )
                }
            }
            ChatInput(
                text = input,
                onText = { input = it },
                attachments = attachments,
                onRemoveAttachment = { mid -> attachments.remove(mid); scope.launch { store.deleteMedia(mid) } },
                plusMenu = listOf(
                    MenuItem("Choose photo") { photos.pickFromDevice() },
                    MenuItem("Take photo") { photos.takePhoto() },
                ),
                busy = busy,
                onSend = ::send,
                onStop = { app.engine.stop(id) },
                usage = chat?.usage,
            )
        }
    }
}
