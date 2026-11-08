package au.net.maz.privacyai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Preset
import au.net.maz.privacyai.data.model.SamplingParam
import au.net.maz.privacyai.data.model.presetFrom
import au.net.maz.privacyai.data.model.textFrom
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.components.AppScaffold
import kotlinx.coroutines.launch

/**
 * Permanently edit and save presets. Roleplays and the utility chat refer to presets by
 * name, so an edit applies everywhere that preset is used.
 */
@Composable
fun PresetsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val presets by store.presets.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<String?>(null) }

    fun save(list: List<Preset>) = scope.launch { store.savePresets(list) }

    AppScaffold(
        title = "Presets",
        onBack = { nav.popBackStack() },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                var n = 1
                while (presets.any { it.name == "New preset $n" }) n++
                val p = Preset("New preset $n", temperature = 0.8)
                save(presets + p)
                editing = p.name
            }) { Icon(Icons.Filled.Add, "New preset") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).imePadding().fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(presets, key = { it.name }) { p ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (editing == p.name) {
                        PresetEditor(
                            p,
                            onSave = { updated ->
                                save(presets.map { if (it.name == p.name) updated else it })
                                editing = null
                            },
                            onDelete = if (presets.size > 1) ({ save(presets.filter { it.name != p.name }); editing = null }) else null,
                            onCancel = { editing = null },
                        )
                    } else {
                        Column(Modifier.fillMaxWidth().clickable { editing = p.name }.padding(16.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(p.summary().ifBlank { "Server defaults" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetEditor(p: Preset, onSave: (Preset) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    val values = remember { mutableStateMapOf<SamplingParam, String>().apply { putAll(textFrom(p)) } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Leave a value blank to use the server's default.", style = MaterialTheme.typography.bodySmall)
        SamplingParam.entries.forEach { param ->
            OutlinedTextField(
                value = values[param].orEmpty(),
                onValueChange = { values[param] = it },
                label = { Text(param.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(presetFrom(name.trim().ifBlank { p.name }, values)) }) { Text("Save") }
        }
    }
}
