package au.net.maz.privacyai.ui.generate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.comfy.FieldType
import au.net.maz.privacyai.comfy.GenJob
import au.net.maz.privacyai.comfy.PatchOp
import au.net.maz.privacyai.comfy.WorkflowField
import au.net.maz.privacyai.comfy.WorkflowPatcher
import au.net.maz.privacyai.data.model.FileValue
import au.net.maz.privacyai.data.model.FormValue
import au.net.maz.privacyai.data.model.MediaInfo
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.Dropdown
import au.net.maz.privacyai.ui.components.MediaThumb
import au.net.maz.privacyai.ui.components.PrivateText
import au.net.maz.privacyai.ui.kept
import au.net.maz.privacyai.ui.screenState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** One control on the form (from workflow_config.json). */
class FieldState(
    val spec: WorkflowField,
    val type: FieldType,
    val options: List<String>,
    val configError: String?,
) {
    var value by mutableStateOf("")
    /** For file fields: the chosen media item. */
    var mediaId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

class FormState {
    var loaded by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    var name by mutableStateOf("")
    var description by mutableStateOf<String?>(null)
    var fields by mutableStateOf<List<FieldState>>(emptyList())
    var submitting by mutableStateOf(false)
    var submitError by mutableStateOf<String?>(null)
    var graph: JsonObject? = null
    var outputNodeTitle = ""
    var outputField = ""
    var section = au.net.maz.privacyai.data.model.MediaKind.IMAGE
}

private const val MAX_SEED = 0x1F_FFFF_FFFF_FFFFL
private fun randomSeed() = Random.nextLong(0, MAX_SEED).toString()

/**
 * The generation form, built from the workflow's config (the old app's field mapping and
 * form generation). Regenerate opens it with [fromMediaId]'s values filled in (new seed),
 * including its input images where they still exist. Text fields can be written by the
 * LLM ("Write with LLM", using the roleplay as context when there is one).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkflowFormScreen(workflowId: String, owner: String, fromMediaId: String, resultKey: String, popTo: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val form = screenState("form") { FormState() }
    val keyBase by kept("keyBase") { Routes.newResultKey() }
    val roleplayId = MediaOwner.roleplayId(owner)
    var rpTitle by kept("rpTitle") { "" }

    LaunchedEffect(Unit) {
        if (form.loaded) return@LaunchedEffect
        roleplayId?.let { rpTitle = store.roleplay(it)?.title.orEmpty() }
        val loaded = app.workflows?.load(workflowId)?.getOrElse { form.loadError = it.message; return@LaunchedEffect }
            ?: run { form.loadError = "Workflow not found"; return@LaunchedEffect }
        val base = store.baseEnums()
        val from = fromMediaId.takeIf { it.isNotBlank() }?.let { store.media(it) }
        val prefill = from?.info?.values?.associate { (it.nodeTitle to it.input) to it.value }.orEmpty()
        val prefillFiles = from?.info?.files?.associate { (it.nodeTitle to it.input) to it.mediaId }.orEmpty()
        form.graph = loaded.graph
        form.outputNodeTitle = loaded.config.output.nodeTitle
        form.outputField = loaded.config.output.field
        form.name = loaded.workflow.name
        form.section = loaded.workflow.section
        form.description = loaded.config.description
        form.fields = loaded.config.fields.map { spec ->
            val type = FieldType.fromWire(spec.type) ?: FieldType.STRING
            val options = if (type == FieldType.ENUM) spec.resolveOptions(base) else emptyList()
            val err = if (type == FieldType.ENUM && options.isEmpty()) "Option list '${spec.optionsRef}' isn't installed" else null
            FieldState(spec, type, options, err).apply {
                val key = spec.nodeTitle to spec.input
                val graphDefault = if (type == FieldType.FILE) null
                else WorkflowPatcher.defaultValue(loaded.graph, spec.nodeTitle, spec.input)?.contentOrNull
                value = when (type) {
                    FieldType.SEED -> randomSeed()
                    FieldType.BOOL -> prefill[key] ?: graphDefault?.toBoolean()?.toString() ?: "false"
                    else -> prefill[key] ?: graphDefault.orEmpty()
                }
                // Input images from the previous run, if they still exist.
                if (type == FieldType.FILE) mediaId = prefillFiles[key]?.takeIf { store.media(it)?.fileId != null }
            }
        }
        form.loaded = true
    }

    // Results coming back: a picked image, or a prompt written on the focus screen.
    val results by app.results.values.collectAsState()
    LaunchedEffect(results) {
        form.fields.forEachIndexed { i, f ->
            app.results.take<String>("$keyBase:f$i")?.let { f.mediaId = it; f.error = null }
            app.results.take<String>("$keyBase:p$i")?.let { f.value = it; f.error = null }
        }
    }

    fun generate() {
        val g = form.graph ?: return
        var ok = true
        form.fields.forEach { f ->
            f.error = when {
                f.configError != null && f.spec.mandatory -> f.configError
                f.type == FieldType.FILE -> if (f.spec.mandatory && f.mediaId == null) "Required" else null
                f.value.isBlank() -> if (f.spec.mandatory) "Required" else null
                (f.type == FieldType.INT || f.type == FieldType.SEED) && f.value.trim().toLongOrNull() == null -> "Whole number required"
                f.type == FieldType.FLOAT && f.value.trim().toDoubleOrNull() == null -> "Number required"
                else -> null
            }
            if (f.error != null) ok = false
        }
        if (!ok || form.submitting) return
        form.submitting = true
        form.submitError = null
        scope.launch {
            runCatching {
                require(store.settings().imageAddress.isNotBlank()) { "No image generator address is set (Settings)" }
                val ops = mutableListOf<PatchOp>()
                val values = mutableListOf<FormValue>()
                val files = mutableListOf<FileValue>()
                val empty = mutableListOf<String>()
                form.fields.forEach { f ->
                    when (f.type) {
                        FieldType.FILE -> {
                            val m = f.mediaId
                            if (m != null) files += FileValue(f.spec.nodeTitle, f.spec.input, m)
                            else empty += f.spec.nodeTitle
                        }
                        else -> {
                            val v = f.value.trim()
                            if (v.isEmpty()) return@forEach
                            values += FormValue(f.spec.nodeTitle, f.spec.input, f.value)
                            ops += PatchOp(
                                f.spec.nodeTitle, f.spec.input,
                                when (f.type) {
                                    FieldType.INT, FieldType.SEED -> JsonPrimitive(v.toLong())
                                    FieldType.FLOAT -> JsonPrimitive(v.toDouble())
                                    FieldType.BOOL -> JsonPrimitive(v.toBoolean())
                                    else -> JsonPrimitive(f.value)
                                },
                            )
                        }
                    }
                }
                val patched = WorkflowPatcher.patch(g, ops).getOrThrow()
                val outputNodeId = WorkflowPatcher.nodeIdByTitle(patched, form.outputNodeTitle).getOrThrow()
                val media = store.newPendingMedia(
                    owner, form.section,
                    MediaInfo(
                        origin = MediaInfo.ORIGIN_GENERATED,
                        description = promptOf(form.fields),
                        workflowId = workflowId,
                        workflowName = form.name,
                        values = values,
                        files = files,
                    ),
                )
                val runner = app.runner ?: error("Locked")
                runner.submit(
                    GenJob(
                        mediaId = media.id, outputNodeId = outputNodeId, outputField = form.outputField,
                        graph = patched, fileInputs = files, emptySlots = empty.distinct(),
                    ),
                )
                media.id
            }.onSuccess { mediaId ->
                if (resultKey.isNotBlank()) app.results.put(resultKey, mediaId)
                if (popTo == "section") nav.popBackStack(Routes.SECTION, inclusive = true) else nav.popBackStack()
            }.onFailure {
                form.submitting = false
                form.submitError = it.message ?: "Couldn't start the generation"
            }
        }
    }

    AppScaffold(title = form.name.ifBlank { "Generate" }, onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                form.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!form.loaded) {
                    if (form.loadError == null) CircularProgressIndicator()
                    return@Column
                }
                if (roleplayId != null) Text("Context: roleplay \"${rpTitle.ifBlank { "Untitled" }}\"", style = MaterialTheme.typography.bodySmall)
                form.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                form.fields.forEachIndexed { i, f -> FieldControl(f, i, owner, keyBase) }
                form.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = ::generate, enabled = !form.submitting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (form.submitting) "Starting..." else "Generate")
                }
            }
        }
    }
}

/** The text sent instead of the image to models without vision: the prompt. */
private fun promptOf(fields: List<FieldState>): String {
    val strings = fields.filter { it.type == FieldType.STRING && it.value.isNotBlank() }
    fun name(f: FieldState) = (f.spec.label ?: f.spec.input).lowercase()
    return (strings.firstOrNull { "prompt" in name(it) && "negative" !in name(it) }
        ?: strings.firstOrNull { "negative" !in name(it) } ?: strings.firstOrNull())?.value.orEmpty()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FieldControl(f: FieldState, index: Int, owner: String, keyBase: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val label = f.spec.displayLabel + if (!f.spec.mandatory) " (optional)" else ""
    val support: @Composable (() -> Unit)? = (f.error ?: f.configError ?: f.spec.description)?.let { t -> { Text(t) } }
    when (f.type) {
        FieldType.STRING -> Column {
            OutlinedTextField(
                value = f.value, onValueChange = { f.value = it; f.error = null },
                label = { Text(label) }, minLines = 3, isError = f.error != null, supportingText = support,
                keyboardOptions = PrivateText, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val key = "$keyBase:p$index"
                    app.results.put("init:$key", f.value)
                    nav.navigate(Routes.focus(owner, key))
                }) { Text(if (MediaOwner.roleplayId(owner) != null) "From context" else "Write with LLM") }
            }
        }
        FieldType.INT, FieldType.FLOAT -> OutlinedTextField(
            value = f.value, onValueChange = { f.value = it; f.error = null },
            label = { Text(label) }, singleLine = true, isError = f.error != null, supportingText = support,
            keyboardOptions = KeyboardOptions(keyboardType = if (f.type == FieldType.INT) KeyboardType.Number else KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldType.SEED -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = f.value, onValueChange = { f.value = it; f.error = null },
                label = { Text(label) }, singleLine = true, isError = f.error != null, supportingText = support,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { f.value = randomSeed() }) { Text("Random") }
        }
        FieldType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = f.value.toBoolean(), onCheckedChange = { f.value = it.toString() })
        }
        FieldType.ENUM -> Column {
            Dropdown(label, f.options, f.value.ifBlank { null }, { f.value = it; f.error = null }, { it })
            support?.invoke()
        }
        FieldType.FILE -> Row(verticalAlignment = Alignment.CenterVertically) {
            val media = f.mediaId?.let { store.observeMediaItem(it).collectAsState(initial = null).value }
            val pick = {
                // Pick from this roleplay's gallery, or the main gallery outside a roleplay.
                nav.navigate(Routes.gallery(owner, "$keyBase:f$index"))
            }
            MediaThumb(media, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = pick, onLongClick = { f.mediaId = null }))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                (f.error ?: f.spec.description)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (f.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    TextButton(onClick = pick) { Text(if (f.mediaId == null) "Choose" else "Change") }
                    if (f.mediaId != null) TextButton(onClick = { f.mediaId = null }) { Text("Clear") }
                }
            }
        }
    }
}
