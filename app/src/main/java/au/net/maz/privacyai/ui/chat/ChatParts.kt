package au.net.maz.privacyai.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import au.net.maz.privacyai.data.media.PhotoImport
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaInfo
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.MarkdownText
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.components.MenuItem
import au.net.maz.privacyai.ui.components.PrivateText
import kotlinx.coroutines.launch
import java.io.File

/** Taking a photo or picking one from the device, imported (encrypted) into [owner]. */
class PhotoSources(val pickFromDevice: () -> Unit, val takePhoto: () -> Unit)

@Composable
fun rememberPhotoSources(owner: String, allowVideo: Boolean = false, onImported: (Media) -> Unit): PhotoSources {
    val context = LocalContext.current
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun import(uri: Uri, origin: String, after: () -> Unit = {}) = scope.launch {
        runCatching { PhotoImport.import(context, store, owner, uri, origin) }
            .onSuccess { m -> if (m != null) onImported(m) else toast(context, "Couldn't add that file") }
            .onFailure { toast(context, "Couldn't add the photo: ${it.message}") }
        after()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { import(it, MediaInfo.ORIGIN_IMPORTED) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (f == null) return@rememberLauncherForActivityResult
        // The camera writes a plain file; encrypt it in and delete it straight away.
        if (ok) import(Uri.fromFile(f), MediaInfo.ORIGIN_PHOTO) { f.delete() } else f.delete()
    }
    return remember(owner) {
        PhotoSources(
            pickFromDevice = {
                picker.launch(
                    PickVisualMediaRequest(
                        if (allowVideo) ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        else ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            takePhoto = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, Ids.new() + ".jpg")
                cameraFile = f
                camera.launch(FileProvider.getUriForFile(context, "au.net.maz.privacyai.fileprovider", f))
            },
        )
    }
}

/** Short message for failures (no content in it). */
fun toast(context: android.content.Context, text: String) =
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()

/** Splits `<think>` reasoning from the answer. */
fun splitThink(text: String): Pair<String?, String> {
    val open = text.indexOf("<think>")
    if (open < 0) return null to text
    val close = text.indexOf("</think>", open)
    return if (close < 0) text.substring(open + 7).trim() to text.substring(0, open).trim()
    else text.substring(open + 7, close).trim() to (text.substring(0, open) + text.substring(close + 8)).trim()
}

/**
 * One message. Characters' messages use their colour as the background with their name
 * small at the top. Long-press for message actions.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    background: Color,
    textColour: Color,
    name: String? = null,
    nameColour: Color = textColour,
    attachments: List<String> = emptyList(),
    pending: Boolean = false,
    error: String? = null,
    /** Buttons shown under the message while it's [selected] (tap to select). */
    actions: List<MenuItem> = emptyList(),
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    /** Long-press opens a small Copy menu where you pressed. */
    onCopy: (() -> Unit)? = null,
    onOpenMedia: (String) -> Unit = {},
    versionIndex: Int = 0,
    versionCount: Int = 1,
    onVersion: (Int) -> Unit = {},
    /**
     * Roleplay layout: others' messages are always 90% of the screen width (left), and
     * the user's are at most 90% (right). Otherwise bubbles fit their text, up to 560 dp.
     */
    roleplayWidths: Boolean = false,
) {
    val store = LocalStore.current
    var copyAt by remember { mutableStateOf<Offset?>(null) }
    var bubbleHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var showThink by remember { mutableStateOf(false) }
    val (think, answer) = remember(text) { splitThink(text) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val ninety = maxWidth * 0.9f
    val bubbleWidth = when {
        !roleplayWidths -> Modifier.widthIn(max = 560.dp)
        isUser -> Modifier.widthIn(max = ninety)
        else -> Modifier.width(ninety)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Box {
            Column(
                bubbleWidth
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .onSizeChanged { bubbleHeight = it.height }
                    .pointerInput(onCopy) {
                        detectTapGestures(
                            onTap = { onSelect() },
                            onLongPress = { pos -> if (onCopy != null) copyAt = pos },
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                name?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = nameColour) }
                if (attachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        items(attachments) { id ->
                            val media by store.observeMediaItem(id).collectAsState(initial = null)
                            MediaThumb(media, Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenMedia(id) })
                        }
                    }
                }
                if (think != null) {
                    Text(
                        if (showThink) "Hide reasoning" else "Show reasoning",
                        fontSize = 12.sp, fontStyle = FontStyle.Italic, color = textColour.copy(alpha = 0.7f),
                        modifier = Modifier.clickable { showThink = !showThink }.padding(vertical = 2.dp),
                    )
                    if (showThink) Text(think, fontSize = 13.sp, color = textColour.copy(alpha = 0.7f))
                }
                // *Italics* (e.g. roleplay actions) in the message's accent colour: the
                // character's own colour, readable on its bubble, and unlike the body text.
                if (answer.isNotBlank()) MarkdownText(answer, color = textColour, italicColor = nameColour)
                if (pending && answer.isBlank()) CircularProgressIndicator(Modifier.size(18.dp).padding(2.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (versionCount > 1) {
                    // Switch between versions from Regenerate.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "‹", fontSize = 20.sp, color = textColour.copy(alpha = if (versionIndex > 0) 0.9f else 0.3f),
                            modifier = Modifier.clickable(enabled = versionIndex > 0 && !pending) { onVersion(versionIndex - 1) }
                                .padding(horizontal = 10.dp),
                        )
                        Text("${versionIndex + 1} / $versionCount", fontSize = 12.sp, color = textColour.copy(alpha = 0.8f))
                        Text(
                            "›", fontSize = 20.sp, color = textColour.copy(alpha = if (versionIndex < versionCount - 1) 0.9f else 0.3f),
                            modifier = Modifier.clickable(enabled = versionIndex < versionCount - 1 && !pending) { onVersion(versionIndex + 1) }
                                .padding(horizontal = 10.dp),
                        )
                    }
                }
                if (selected && actions.isNotEmpty()) {
                    // Inside the bubble, scrolled into view when they appear.
                    val bring = remember { BringIntoViewRequester() }
                    LaunchedEffect(Unit) { bring.bringIntoView() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp).bringIntoViewRequester(bring),
                    ) {
                        actions.forEach { a -> ActionChip(a) }
                    }
                }
            }
            // Copy, next to where the finger was (the offset is from the bubble's bottom-left).
            val at = copyAt
            DropdownMenu(
                expanded = at != null,
                onDismissRequest = { copyAt = null },
                offset = with(density) { DpOffset((at?.x ?: 0f).toDp(), ((at?.y ?: 0f) - bubbleHeight).toDp()) },
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { copyAt = null; onCopy?.invoke() },
                )
            }
        }
        }
    }
    }
}

/** A small button under a selected message (like the old app's action chips). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionChip(item: MenuItem) {
    val haptics = LocalHapticFeedback.current
    val tint = if (item.label == "Delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .combinedClickable(
                onClick = item.onClick,
                onLongClick = item.onLongClick?.let { l -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); l() } },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
        Text(item.label, fontSize = 13.sp, color = tint)
    }
}

/** Message input with a + menu, attachment previews, and Send / Stop. */
@Composable
fun ChatInput(
    text: String,
    onText: (String) -> Unit,
    attachments: List<String>,
    onRemoveAttachment: (String) -> Unit,
    plusMenu: List<MenuItem>,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    extra: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit = {},
    usage: au.net.maz.privacyai.data.model.ContextUsage? = null,
) {
    val store = LocalStore.current
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            usage?.takeIf { it.tokens > 0 }?.let { ContextReadout(it) }
            extra()
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    items(attachments) { id ->
                        val media by store.observeMediaItem(id).collectAsState(initial = null)
                        Box {
                            MediaThumb(media, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                            IconButton(onClick = { onRemoveAttachment(id) }, modifier = Modifier.size(24.dp).align(Alignment.TopEnd)) {
                                Icon(Icons.Filled.Close, "Remove", tint = Color.White,
                                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (plusMenu.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "Add") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            plusMenu.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { menu = false; m.onClick() }) }
                        }
                    }
                }
                // Text set from outside (Impersonate streaming in) moves the cursor to the
                // end, so the box scrolls to show the newest text.
                var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
                if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it; if (it.text != text) onText(it.text) },
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    keyboardOptions = PrivateText,
                    modifier = Modifier.weight(1f),
                )
                buttons()
                if (busy) {
                    IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Stop") }
                } else {
                    IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}

/**
 * How full the model's context is, as reported by the server after the last reply (so it
 * includes images). Shown as a percentage when the context size is known.
 */
@Composable
fun ContextReadout(u: au.net.maz.privacyai.data.model.ContextUsage) {
    val fmt = java.text.NumberFormat.getIntegerInstance()
    val text = if (u.limit > 0) {
        val pct = (u.tokens * 100L / u.limit).toInt()
        "Context: ${fmt.format(u.tokens)} / ${fmt.format(u.limit)} ($pct%)"
    } else "Context: ${fmt.format(u.tokens)} tokens"
    val warn = u.limit > 0 && u.tokens * 100L / u.limit >= 85
    Text(
        text,
        fontSize = 11.sp,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
    )
}

/** Opens a media item full screen. */
@Composable
fun openMedia(): (String) -> Unit {
    val nav = LocalNav.current
    return { id -> nav.navigate(Routes.viewer(id)) }
}

/**
 * Keeps a chat scrolled to the bottom of the newest message while it grows (streaming),
 * so the newest text is always showing. Dragging the list stops following; scrolling back
 * to the bottom, or a new message, starts it again.
 */
@Composable
fun FollowNewest(listState: LazyListState, count: Int, lastLength: Int) {
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { if (!it) follow = true }
    }
    suspend fun toEnd() {
        if (count == 0) return
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == count - 1 }
        if (!visible) listState.scrollToItem(count - 1)
        listState.scrollBy(100_000f) // stops at the end
    }
    LaunchedEffect(count) { follow = true; toEnd() }
    LaunchedEffect(lastLength) { if (follow) toEnd() }
}
