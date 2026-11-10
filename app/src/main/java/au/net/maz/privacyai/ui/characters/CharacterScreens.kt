package au.net.maz.privacyai.ui.characters

import au.net.maz.privacyai.ui.components.DiscardDialog
import au.net.maz.privacyai.ui.components.PrivateText
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import au.net.maz.privacyai.ui.components.MenuItem
import au.net.maz.privacyai.data.CharacterYaml
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.SnackbarHostState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Character
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.llm.Prompts
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.chat.rememberPhotoSources
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.ColourPickerDialog
import au.net.maz.privacyai.ui.components.GenerateField
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.kept
import au.net.maz.privacyai.ui.theme.bubbleColours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun CharacterRow(c: Character, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val colours = bubbleColours(c.colour)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ref = c.referenceImage
        if (ref != null) {
            val media by store.observeMediaItem(ref).collectAsState(initial = null)
            MediaThumb(media, Modifier.size(44.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colours.background), contentAlignment = Alignment.Center) {
                Text(c.name.take(1).uppercase(), color = colours.name)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium, color = colours.name)
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The main character list (available to all roleplays and the utility chat). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen() {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by store.characters.collectAsState(initial = emptyList())
    val settings by store.settings.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }

    // Import a .yaml character: create it with a random colour, then open it for editing.
    val yamlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_YAML_BYTES + 1)
                        if (bytes.size > MAX_YAML_BYTES) error("The file is too large")
                        bytes.decodeToString()
                    } ?: error("Couldn't open the file")
                }
                val parsed = CharacterYaml.parse(text)
                val c = Character(
                    name = parsed.name,
                    greeting = parsed.greeting,
                    systemPrompt = parsed.context,
                    colour = app.randomCharacterColour(),
                )
                store.saveCharacter(c)
                c
            }.onSuccess { nav.navigate(Routes.character("main", it.id)) }
                .onFailure { snackbar.showSnackbar(it.message ?: "Import failed") }
        }
    }

    AppScaffold(
        title = "Characters",
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        menu = listOf(MenuItem("Import character") { yamlPicker.launch(arrayOf("*/*")) }),
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.character("main", "new")) }) { Icon(Icons.Filled.Add, "New character") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    CharacterRow(c, Modifier.combinedClickable(onClick = { nav.navigate(Routes.character("main", c.id)) }, onLongClick = { menu = true }))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.navigate(Routes.character("main", c.id)) })
                        if (settings?.chatCharacterId != c.id) {
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { store.deleteCharacter(c.id) } })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Pick a main-list character to clone into a roleplay (a snapshot; no syncing after). */
@Composable
fun CharacterImportScreen(rpId: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list by store.characters.collectAsState(initial = emptyList())
    AppScaffold(title = "Import character", onBack = { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                CharacterRow(c, Modifier.clickable {
                    scope.launch {
                        val owner = MediaOwner.roleplay(rpId)
                        val ref = c.referenceImage?.let { store.copyMedia(it, owner)?.id }
                        val clone = c.copy(id = au.net.maz.privacyai.data.model.Ids.new("ch_"), clonedFrom = c.id, referenceImage = ref)
                        store.updateRoleplay(rpId) { r -> r.copy(characters = r.characters + clone) }
                        nav.popBackStack()
                    }
                })
                HorizontalDivider()
            }
        }
    }
}

/**
 * Create/edit a character. The same screen for the main list ([owner] = "main") and for
 * a roleplay ([owner] = the roleplay id); only where it's saved differs. Text fields can
 * be generated with the LLM; inside a roleplay that uses the conversation so far.
 */
@Composable
fun CharacterEditScreen(owner: String, routeId: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val inRoleplay = owner != "main"
    val mediaOwner = if (inRoleplay) MediaOwner.roleplay(owner) else MediaOwner.MAIN_CHARACTERS

    var loaded by kept("loaded") { false }
    var c by kept("character") { Character(colour = app.randomCharacterColour()) }
    var original by kept<Character?>("original") { null }
    var rp by kept<Roleplay?>("rp") { null }
    val pickKey by kept("pickKey") { Routes.newResultKey() }
    var picker by remember { mutableStateOf(false) }
    // (field, message) of the last failed Generate, shown under that field.
    var genError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val tasks by app.tasks.ids.collectAsState()

    LaunchedEffect(Unit) {
        if (inRoleplay) rp = store.roleplay(owner)
        if (!loaded) {
            val existing = if (routeId == "new") null
            else if (inRoleplay) rp?.character(routeId) else store.character(routeId)
            existing?.let { c = it; original = it }
            loaded = true
        }
    }
    // A reference image picked or generated elsewhere comes back here.
    val results by app.results.values.collectAsState()
    LaunchedEffect(results[pickKey]) {
        app.results.take<String>(pickKey)?.let { picked ->
            // The main list's images live with the main list, so copy one picked from the gallery.
            val m = store.media(picked)
            val id = if (m != null && m.owner != mediaOwner) store.copyMedia(picked, mediaOwner)?.id ?: picked else picked
            c = c.copy(referenceImage = id)
        }
    }
    val photos = rememberPhotoSources(mediaOwner) { c = c.copy(referenceImage = it.id) }

    suspend fun save() {
        val ch = c
        if (inRoleplay) {
            store.updateRoleplay(owner) { r ->
                r.copy(characters = if (r.characters.any { it.id == ch.id }) r.characters.map { if (it.id == ch.id) ch else it } else r.characters + ch)
            }
        } else {
            store.saveCharacter(ch)
            // A replaced reference image of a main-list character is no longer needed.
            original?.referenceImage?.takeIf { it != ch.referenceImage }?.let { store.deleteMedia(it) }
        }
    }

    val genFields = listOf("greeting", "persona", "state")
    fun leave() {
        genFields.forEach { app.tasks.cancel("gen:${c.id}:$it") }
        nav.popBackStack()
    }

    // Back never saves (only Save does); it asks first if anything changed.
    fun back() {
        val ch = c
        val changed = original?.let { it != ch }
            ?: (ch.name.isNotBlank() || ch.description.isNotBlank() || ch.greeting.isNotBlank() ||
                ch.systemPrompt.isNotBlank() || ch.state.isNotBlank() || ch.referenceImage != null)
        if (changed) confirmDiscard = true else leave()
    }
    BackHandler { back() }
    if (confirmDiscard) DiscardDialog(onDiscard = { confirmDiscard = false; leave() }, onKeep = { confirmDiscard = false })

    fun gen(field: String, hint: String) {
        genError = null
        val snapshot = c
        val r = rp
        app.engine.complete(
            taskId = "gen:${c.id}:$field",
            request = {
                val model = r?.model?.ifBlank { null } ?: app.engine.defaultModel()
                Prompts.characterField(field, snapshot, r, model, hint, store.settings().characterFieldMaxTokens)
            },
            onText = { t ->
                c = when (field) {
                    "name" -> c.copy(name = t.lines().firstOrNull().orEmpty().trim().trim('"'))
                    "description" -> c.copy(description = t)
                    "greeting" -> c.copy(greeting = t)
                    "persona" -> c.copy(systemPrompt = t)
                    else -> c.copy(state = t)
                }
            },
            onDone = { e -> if (e != null && e !is CancellationException) genError = field to (e.message ?: "Generate failed") },
        )
    }

    @Composable
    fun field(label: String, key: String, value: String, set: (String) -> Unit, lines: Int = 3) = GenerateField(
        label = label, value = value, onValueChange = set, minLines = lines,
        generating = "gen:${c.id}:$key" in tasks,
        onGenerate = { gen(key, it) },
        onStop = { app.tasks.cancel("gen:${c.id}:$key") },
        generateLabel = if (key == "state" && value.isNotBlank()) "Update" else "Generate",
        guidanceInDialog = true,
        error = genError?.takeIf { it.first == key }?.second,
    )

    AppScaffold(title = if (routeId == "new") "New character" else c.name.ifBlank { "Character" }, onBack = ::back) { pad ->
        if (!loaded) return@AppScaffold
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Reference image
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ref = c.referenceImage
                    val media = ref?.let { store.observeMediaItem(it).collectAsState(initial = null).value }
                    MediaThumb(media, Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).clickable { ref?.let { nav.navigate(Routes.viewer(it)) } })
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Reference image", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                OutlinedButton(onClick = { picker = true }) { Text("Choose") }
                                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                                    DropdownMenuItem(text = { Text(if (inRoleplay) "From roleplay gallery" else "From gallery") }, onClick = {
                                        picker = false
                                        nav.navigate(Routes.gallery(if (inRoleplay) mediaOwner else MediaOwner.GALLERY, pickKey))
                                    })
                                    DropdownMenuItem(text = { Text("From device") }, onClick = { picker = false; photos.pickFromDevice() })
                                    DropdownMenuItem(text = { Text("From files") }, onClick = { picker = false; photos.pickFile() })
                                    DropdownMenuItem(text = { Text("Take photo") }, onClick = { picker = false; photos.takePhoto() })
                                    if (c.referenceImage != null) DropdownMenuItem(text = { Text("Remove") }, onClick = { picker = false; c = c.copy(referenceImage = null) })
                                }
                            }
                            OutlinedButton(onClick = {
                                // The prompt writer uses the character as it is now (unsaved edits
                                // included) as its context, in memory only. Nothing is saved.
                                app.results.put(Routes.characterContextKey(pickKey), c)
                                nav.navigate(Routes.section(MediaKind.IMAGE, mediaOwner, pickKey))
                            }) { Text("Generate") }
                        }
                    }
                }
                // Name and description are short: plain fields, no Generate.
                OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth())
                // Colour
                var colourOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bc = bubbleColours(c.colour)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(bc.background).clickable { colourOpen = true })
                    Spacer(Modifier.width(12.dp))
                    Text("Colour", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { colourOpen = true }) { Text("Change") }
                }
                if (colourOpen) {
                    ColourPickerDialog(c.colour, c.name, onDismiss = { colourOpen = false }) {
                        c = c.copy(colour = it); colourOpen = false
                    }
                }
                OutlinedTextField(c.description, { c = c.copy(description = it) }, label = { Text("Description") }, singleLine = true,
                    keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth())
                field("Greeting", "greeting", c.greeting, { c = c.copy(greeting = it) })
                field("Persona / system prompt", "persona", c.systemPrompt, { c = c.copy(systemPrompt = it) }, lines = 5)
                field("State", "state", c.state, { c = c.copy(state = it) })
                Button(onClick = { scope.launch { save(); nav.popBackStack() } }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
        }
    }
}

private const val MAX_YAML_BYTES = 1024 * 1024

/** Read at most [limit] bytes (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
