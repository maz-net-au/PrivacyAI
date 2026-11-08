package au.net.maz.privacyai.ui.generate

import au.net.maz.privacyai.data.model.Character
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.llm.Prompts
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.GenerateField
import au.net.maz.privacyai.ui.components.ModelPicker
import au.net.maz.privacyai.ui.kept
import kotlinx.coroutines.CancellationException

/**
 * Writing an image prompt with the LLM: a screen of its own (not a small popup).
 * The focus says what the image should be about. The context is the character when
 * generating a character's image ([ctx]), otherwise the roleplay (if any).
 * The result can be edited, and refined with a change request. The model defaults to
 * the roleplay's model, since switching models takes time.
 */
@Composable
fun FocusScreen(owner: String, resultKey: String, ctx: String) {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val tasks by app.tasks.ids.collectAsState()
    val rpId = MediaOwner.roleplayId(owner)

    var loaded by kept("loaded") { false }
    var rp by kept<Roleplay?>("rp") { null }
    // A character being given an image: the only context (the roleplay just picks the model).
    val character by kept<Character?>("character") { ctx.ifBlank { null }?.let { app.results.peek<Character>(it) } }
    val contextRp = if (character != null) null else rp
    var model by kept<String?>("model") { null }
    var focus by kept("focus") { "" }
    var prompt by kept("prompt") { "" }
    var change by kept("change") { "" }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        rp = rpId?.let { store.roleplay(it) }
        model = rp?.model?.ifBlank { null } ?: store.settings().chatModel.ifBlank { null }
        prompt = app.results.take<String>("init:$resultKey").orEmpty()
        loaded = true
    }

    val writeTask = "focus:$resultKey:write"
    val refineTask = "focus:$resultKey:refine"

    fun run(task: String, build: suspend (String, String) -> au.net.maz.privacyai.net.ChatRequest) {
        error = null
        app.engine.complete(
            taskId = task,
            request = {
                val m = model ?: app.engine.defaultModel()
                build(m, store.settings().imagePromptSystem)
            },
            onText = { prompt = it },
            onDone = { e -> if (e != null && e !is CancellationException) error = e.message },
        )
    }

    AppScaffold(title = "Write image prompt", onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!loaded) return@Column
                Text(
                    character?.let { "Context: character \"${it.name.ifBlank { "Unnamed" }}\" (everything filled in on its page)" }
                        ?: rp?.let { "Context: roleplay \"${it.title.ifBlank { "Untitled" }}\" (background, characters and the conversation)" }
                        ?: "No conversation context: the prompt is written from the focus alone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ModelPicker("Model", model, { model = it })
                GenerateField(
                    label = "Focus (what the image should show)",
                    value = focus, onValueChange = { focus = it }, minLines = 3,
                    generating = writeTask in tasks,
                    onGenerate = { _ ->
                        val r = contextRp
                        val ch = character
                        val f = focus
                        run(writeTask) { m, system -> Prompts.imagePrompt(r, f, m, system, ch) }
                    },
                    onStop = { app.tasks.cancel(writeTask) },
                    generateLabel = "Write prompt",
                )
                GenerateField(
                    label = "Prompt", value = prompt, onValueChange = { prompt = it }, minLines = 6,
                    generating = writeTask in tasks || refineTask in tasks,
                )
                GenerateField(
                    label = "Change request (to refine the prompt)",
                    value = change, onValueChange = { change = it }, minLines = 2,
                    generating = refineTask in tasks,
                    onGenerate = { _ ->
                        val r = contextRp
                        val chr = character
                        val cur = prompt
                        val ch = change
                        run(refineTask) { m, system -> Prompts.refinePrompt(r, cur, ch, m, system, chr) }
                    },
                    onStop = { app.tasks.cancel(refineTask) },
                    generateLabel = "Refine",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { app.results.put(resultKey, prompt); nav.popBackStack() },
                    enabled = prompt.isNotBlank() && writeTask !in tasks && refineTask !in tasks,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this prompt") }
            }
        }
    }
}
