package au.net.maz.privacyai.ui.roleplay

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.Message
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.data.model.SamplingParam
import au.net.maz.privacyai.data.model.formatSampling
import au.net.maz.privacyai.data.model.overridesFrom
import au.net.maz.privacyai.data.model.textFrom
import au.net.maz.privacyai.llm.Prompts
import au.net.maz.privacyai.llm.stripThink
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.kept
import au.net.maz.privacyai.ui.screenState
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.Dropdown
import au.net.maz.privacyai.ui.components.GenerateField
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.components.MenuItem
import au.net.maz.privacyai.ui.components.ModelPicker
import au.net.maz.privacyai.ui.components.PrivateText
import au.net.maz.privacyai.ui.components.SectionTitle
import au.net.maz.privacyai.ui.theme.bubbleColours
import kotlinx.coroutines.launch

/** List of roleplays. + starts one (through setup); long-press to delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoleplayListScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list by store.roleplays.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }
    AppScaffold(
        title = "Roleplay",
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        menu = listOf(
            MenuItem("Import conversation") { scope.launch { snackbar.showSnackbar("Import isn't built yet (format still to be decided)") } },
            MenuItem("Manage characters") { nav.navigate(Routes.CHARACTERS) },
        ),
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.rpSetup("new")) }) { Icon(Icons.Filled.Add, "New roleplay") }
        },
    ) { pad ->
        val items = list ?: return@AppScaffold
        if (items.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No roleplays yet. Tap + to start one.") }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(items, key = { it.id }) { rp ->
                var menu by remember { mutableStateOf(false) }
                var confirm by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { nav.navigate(Routes.rpChat(rp.id)) }, onLongClick = { menu = true })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val first = rp.characters.firstOrNull()
                        Box(Modifier.size(14.dp).clip(CircleShape).background(first?.let { bubbleColours(it.colour).name } ?: Color.Gray))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rp.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            rp.messages.lastOrNull()?.let { m ->
                                Text(
                                    "${Prompts.speakerName(rp, m)}: ${stripThink(m.text).replace('\n', ' ')}",
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false; confirm = false }) {
                        if (!confirm) DropdownMenuItem(text = { Text("Delete") }, onClick = { confirm = true })
                        else DropdownMenuItem(text = { Text("Delete \"${rp.title.ifBlank { "Untitled" }}\" and its images") }, onClick = {
                            menu = false; confirm = false
                            scope.launch { store.deleteRoleplay(rp.id) }
                        })
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Setup for a new roleplay, and "Edit chat details" for an existing one. Same screen;
 * only the button text changes. Characters are saved into the roleplay as soon as they
 * are created/edited; the other fields when the button is pressed (or on Back when editing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoleplaySetupScreen(routeId: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val isNew = routeId == "new"
    val id by kept("id") { if (isNew) Ids.new("rp_") else routeId }
    var created by kept("created") { !isNew }

    LaunchedEffect(Unit) {
        if (!created) {
            val s = store.settings()
            store.saveRoleplay(Roleplay(id = id, model = s.chatModel, preset = s.chatPreset, draft = true))
            created = true
        }
    }
    val rp by store.observeRoleplay(id).collectAsState(initial = null)
    val presets by store.presets.collectAsState(initial = emptyList())
    val tasks by app.tasks.ids.collectAsState()

    // Local copies of the fields, saved with the button.
    var loaded by kept("loaded") { false }
    var title by kept("title") { "" }
    var yourName by kept("yourName") { "" }
    var myDescription by kept("myDescription") { "" }
    var summary by kept("summary") { "" }
    var model by kept("model") { "" }
    var descriptionModel by kept<String?>("descriptionModel") { null }
    var preset by kept("preset") { "" }
    var imagesSent by kept("imagesSent") { "3" }
    val overrides = screenState("overrides") { mutableStateMapOf<SamplingParam, String>() }
    var showOverrides by remember { mutableStateOf(false) }
    var genError by remember { mutableStateOf<String?>(null) }
    val current = rp ?: return
    if (!loaded) {
        title = current.title; yourName = current.yourName; myDescription = current.myDescription
        summary = current.summaryBackground; model = current.model; descriptionModel = current.descriptionModel
        preset = current.preset; imagesSent = current.attachedImagesSent.toString()
        overrides.putAll(textFrom(current.overrides))
        loaded = true
    }

    fun withFields(r: Roleplay) = r.copy(
        title = title.trim(), yourName = yourName.trim(), myDescription = myDescription,
        summaryBackground = summary, model = model, descriptionModel = descriptionModel,
        preset = preset, attachedImagesSent = imagesSent.toIntOrNull()?.coerceAtLeast(0) ?: 3,
        overrides = overridesFrom(overrides), updatedAt = System.currentTimeMillis(),
    )

    fun save(after: () -> Unit) = scope.launch {
        store.updateRoleplay(id) { r ->
            val next = withFields(r).copy(draft = false)
            // A new roleplay opens with the first character's greeting.
            if (r.draft && next.messages.isEmpty()) {
                val first = next.characters.firstOrNull()
                if (first != null && first.greeting.isNotBlank()) {
                    next.copy(messages = listOf(Message(speaker = first.id, text = Prompts.resolve(first.greeting, first.name, Prompts.userName(next)))))
                } else next
            } else next
        }
        after()
    }

    fun back() {
        if (isNew) {
            app.appScope.launch { store.deleteRoleplay(id) }
            nav.popBackStack()
        } else save { nav.popBackStack() }
    }
    BackHandler { back() }

    fun gen(field: String, hint: String) {
        genError = null
        val snapshot = withFields(current)
        val m = snapshot.model.ifBlank { null }
        app.engine.complete(
            taskId = "gen:$id:$field",
            request = {
                val use = m ?: app.engine.defaultModel()
                if (field == "summary") Prompts.summary(snapshot, use, hint) else Prompts.myDescription(snapshot, use, hint)
            },
            onText = { t -> if (field == "summary") summary = t else myDescription = t },
            onDone = { e -> if (e != null && e !is kotlinx.coroutines.CancellationException) genError = e.message },
        )
    }

    AppScaffold(title = if (isNew) "New roleplay" else "Edit chat details", onBack = ::back) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(yourName, { yourName = it }, label = { Text("Your name") }, singleLine = true, keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth())
                GenerateField(
                    label = "My description", value = myDescription, onValueChange = { myDescription = it },
                    generating = "gen:$id:mydescription" in tasks,
                    onGenerate = { gen("mydescription", it) }, onStop = { app.tasks.cancel("gen:$id:mydescription") },
                    guidanceInDialog = true,
                )
                GenerateField(
                    label = "Summary / background", value = summary, onValueChange = { summary = it },
                    generating = "gen:$id:summary" in tasks, generateLabel = "Generate from conversation",
                    onGenerate = { gen("summary", it) }, onStop = { app.tasks.cancel("gen:$id:summary") },
                    error = genError,
                    guidanceInDialog = true,
                )

                SectionTitle("Characters")
                current.characters.forEach { c ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(bubbleColours(c.colour).background)
                                .combinedClickable(onClick = { nav.navigate(Routes.character(id, c.id)) }, onLongClick = { menu = true })
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ref = c.referenceImage
                            if (ref != null) {
                                val media by store.observeMediaItem(ref).collectAsState(initial = null)
                                MediaThumb(media, Modifier.size(44.dp).clip(CircleShape))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "(unnamed)" }, color = bubbleColours(c.colour).name, style = MaterialTheme.typography.titleSmall)
                                if (c.description.isNotBlank()) Text(c.description, color = bubbleColours(c.colour).text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.navigate(Routes.character(id, c.id)) })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                                menu = false
                                scope.launch {
                                    // Its reference image stays in the roleplay's gallery.
                                    store.updateRoleplay(id) { r -> r.copy(characters = r.characters.filter { it.id != c.id }) }
                                }
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.character(id, "new")) }) { Text("Create character") }
                    OutlinedButton(onClick = { nav.navigate(Routes.characterImport(id)) }) { Text("Import character") }
                }
                Text("Long-press a character to edit or remove it.", style = MaterialTheme.typography.bodySmall)

                SectionTitle("Model")
                ModelPicker("Model", model.ifBlank { null }, { model = it.orEmpty() })
                ModelPicker(
                    "Image description model", descriptionModel, { descriptionModel = it },
                    defaultLabel = "Same as roleplay model",
                )
                Dropdown("Preset", presets.map { it.name }, preset, { preset = it }, { it })
                TextButton(onClick = { showOverrides = !showOverrides }) {
                    Text(if (showOverrides) "Hide overrides for this chat" else "Overrides for this chat (${overrides.count { it.value.isNotBlank() }})")
                }
                if (showOverrides) {
                    val base = presets.firstOrNull { it.name == preset }
                    SamplingParam.entries.forEach { p ->
                        OutlinedTextField(
                            value = overrides[p].orEmpty(),
                            onValueChange = { overrides[p] = it },
                            label = { Text(p.label) },
                            placeholder = { Text(base?.let(p.fromPreset)?.let { "preset: ${formatSampling(it)}" } ?: "server default") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = imagesSent,
                    onValueChange = { imagesSent = it.filter(Char::isDigit).take(3) },
                    label = { Text("Attached images sent (0 = all)") },
                    supportingText = { Text("Models without -VL- in the name get the images' descriptions instead.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = created,
                    onClick = {
                        save {
                            if (isNew) nav.navigate(Routes.rpChat(id)) { popUpTo(Routes.RP_SETUP) { inclusive = true } }
                            else nav.popBackStack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (isNew) "Start roleplay" else "Save changes") }
            }
        }
    }
}
