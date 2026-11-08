package au.net.maz.privacyai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Settings
import au.net.maz.privacyai.net.ServerStatus
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.Dropdown
import au.net.maz.privacyai.ui.components.ModelPicker
import au.net.maz.privacyai.ui.components.PrivateText
import au.net.maz.privacyai.ui.components.SectionTitle
import au.net.maz.privacyai.ui.lock.PinField
import kotlinx.coroutines.launch

/** Settings, grouped. (The full reorganisation is still to be discussed.) */
@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val loaded by store.settings.collectAsState(initial = null)
    val settings = loaded ?: return
    val characters by store.characters.collectAsState(initial = emptyList())
    val presets by store.presets.collectAsState(initial = emptyList())
    val health by app.health.state.collectAsState()

    fun update(f: (Settings) -> Settings) = scope.launch { store.updateSettings(f) }

    // Text fields keep their own state and save as you type.
    var llm by remember { mutableStateOf(settings.llmAddress) }
    var image by remember { mutableStateOf(settings.imageAddress) }
    var minutes by remember { mutableStateOf(settings.inactivityMinutes.toString()) }
    var promptSystem by remember { mutableStateOf(settings.imagePromptSystem) }

    AppScaffold(title = "Settings", onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = 32.dp)) {
                SectionTitle("Servers")
                OutlinedTextField(
                    value = llm,
                    onValueChange = { v -> llm = v.trim(); update { it.copy(llmAddress = v.trim()) } },
                    label = { Text("LLM server address (with port)") },
                    placeholder = { Text("192.168.1.50:8080") },
                    supportingText = { Text(statusText(health.llm)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = image,
                    onValueChange = { v -> image = v.trim(); update { it.copy(imageAddress = v.trim()) } },
                    label = { Text("Image generator address (with port)") },
                    placeholder = { Text("192.168.1.50:8188") },
                    supportingText = { Text(statusText(health.image)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle("Security")
                if (app.session.hasPin) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { v ->
                            minutes = v.filter { it.isDigit() }.take(4)
                            minutes.toIntOrNull()?.takeIf { it > 0 }?.let { m -> update { it.copy(inactivityMinutes = m) } }
                        },
                        label = { Text("Lock after inactivity (minutes)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Locking is off because no PIN is set.", style = MaterialTheme.typography.bodyMedium)
                }
                PinSection()

                SectionTitle("Chat")
                ModelPicker("Model", settings.chatModel.ifBlank { null }, { m -> update { it.copy(chatModel = m.orEmpty()) } })
                Dropdown(
                    label = "Character",
                    options = characters,
                    selected = characters.firstOrNull { it.id == settings.chatCharacterId },
                    onSelect = { c -> update { it.copy(chatCharacterId = c.id) } },
                    display = { it.name.ifBlank { "(unnamed)" } },
                    placeholder = "None",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Dropdown(
                    label = "Preset",
                    options = presets.map { it.name },
                    selected = settings.chatPreset,
                    onSelect = { p -> update { it.copy(chatPreset = p) } },
                    display = { it },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.CHARACTERS) }) { Text("Edit characters") }
                    OutlinedButton(onClick = { nav.navigate(Routes.PRESETS) }) { Text("Edit presets") }
                }

                SectionTitle("Roleplay")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable Roleplay", modifier = Modifier.weight(1f))
                    Switch(checked = settings.roleplayEnabled, onCheckedChange = { on -> update { it.copy(roleplayEnabled = on) } })
                }
                if (settings.roleplayEnabled) {
                    OutlinedTextField(
                        value = promptSystem,
                        onValueChange = { v -> promptSystem = v; update { it.copy(imagePromptSystem = v) } },
                        label = { Text("Image prompt writer: system prompt") },
                        minLines = 4,
                        keyboardOptions = PrivateText,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    TextButton(onClick = {
                        promptSystem = Settings.DEFAULT_IMAGE_PROMPT_SYSTEM
                        update { it.copy(imagePromptSystem = Settings.DEFAULT_IMAGE_PROMPT_SYSTEM) }
                    }) { Text("Reset to default") }
                }
            }
        }
    }
}

private fun statusText(s: ServerStatus) = when (s) {
    ServerStatus.NOT_SET -> "Not set"
    ServerStatus.UNKNOWN -> "Checking..."
    ServerStatus.UP -> "Connected"
    ServerStatus.DOWN -> "Not connected"
}

/** Add or change the PIN, inline (no popup). The PIN can't be removed for now. */
@Composable
private fun PinSection() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val hasPin = app.session.hasPin

    LaunchedEffect(open) { if (!open) { current = ""; pin = ""; confirm = "" } }

    if (!open) {
        OutlinedButton(onClick = { open = true; message = null }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (hasPin) "Change PIN" else "Add PIN")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        if (hasPin) PinField(current, { current = it }, "Current PIN")
        PinField(pin, { pin = it }, "New PIN")
        PinField(confirm, { confirm = it }, "Confirm new PIN")
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open = false; message = null }) { Text("Cancel") }
            Button(onClick = {
                when {
                    pin.isEmpty() -> message = "Enter a PIN"
                    pin != confirm -> message = "The PINs don't match"
                    else -> scope.launch {
                        // Check the current PIN by trying it against the vault.
                        if (hasPin && !app.session.verifyPin(current)) {
                            message = "Current PIN is wrong"
                        } else {
                            app.session.setPin(pin)
                            open = false
                            message = if (hasPin) "PIN changed" else "PIN added. Locking is now on."
                        }
                    }
                }
            }) { Text("Save PIN") }
        }
    }
}
