package au.net.maz.privacyai.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.MenuItem
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * A generate section (image / audio / video): its list of installed workflows. Workflows
 * that take an input image are named "... Edit". Import from the menu; long-press to
 * rename or remove; "Reorder" from the menu shows up/down arrows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionScreen(section: MediaKind, owner: String, resultKey: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val workflows = app.workflows
    val list by remember(workflows) { workflows?.observe(section) ?: emptyFlow() }.collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var reorder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && workflows != null) scope.launch {
            workflows.import(uri, section)
                .onSuccess { snackbar.showSnackbar("Imported \"$it\"") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Import failed") }
        }
    }
    val enumPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && workflows != null) scope.launch {
            workflows.importEnums(uri)
                .onSuccess { snackbar.showSnackbar("Imported $it option list(s)") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Import failed") }
        }
    }

    // Generating for a roleplay or a character returns there once it starts.
    val returnToCaller = owner != MediaOwner.GALLERY || resultKey.isNotBlank()

    AppScaffold(
        title = "Generate ${section.label.lowercase()}",
        onBack = { nav.popBackStack() },
        snackbar = snackbar,
        menu = listOf(
            MenuItem("Import workflow") { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            MenuItem("Import option lists") { enumPicker.launch(arrayOf("application/json", "*/*")) },
            MenuItem(if (reorder) "Done reordering" else "Reorder") { reorder = !reorder },
        ),
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No ${section.label.lowercase()} workflows yet. Use the menu to import a workflow zip.")
            }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            itemsIndexed(list, key = { _, w -> w.id }) { index, w ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    if (renaming == w.id) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(renameText, { renameText = it }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = { renaming = null }) { Text("Cancel") }
                            TextButton(onClick = {
                                val name = renameText
                                renaming = null
                                if (name.isNotBlank()) scope.launch { workflows?.rename(w.id, name) }
                            }) { Text("Save") }
                        }
                    } else Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                enabled = !reorder,
                                onClick = {
                                    nav.navigate(Routes.form(w.id, owner, result = resultKey, popTo = if (returnToCaller) "section" else ""))
                                },
                                onLongClick = { menu = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (reorder) {
                            IconButton(enabled = index > 0, onClick = {
                                val ids = list.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }
                                scope.launch { workflows?.reorder(ids) }
                            }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(enabled = index < list.lastIndex, onClick = {
                                val ids = list.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }
                                scope.launch { workflows?.reorder(ids) }
                            }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = w.id; renameText = w.name })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; scope.launch { workflows?.delete(w.id) } })
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
