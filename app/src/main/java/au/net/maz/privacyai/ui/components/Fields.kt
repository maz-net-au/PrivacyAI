package au.net.maz.privacyai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.ui.LocalApp

/** Keyboard options for private text: no autocorrect suggestions are requested. */
val PrivateText = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrect = false)

/**
 * A large text field with a "Generate" option (LLM). While generating, the text streams
 * in and the button becomes "Stop". An optional guidance line lets the user steer it.
 */
@Composable
fun GenerateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    generating: Boolean = false,
    onGenerate: ((hint: String) -> Unit)? = null,
    onStop: () -> Unit = {},
    generateLabel: String = "Generate",
    error: String? = null,
) {
    var hint by remember { mutableStateOf("") }
    var showHint by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            minLines = minLines,
            enabled = !generating,
            keyboardOptions = PrivateText,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onGenerate != null) {
            if (showHint && !generating) {
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text("Guidance (optional)") },
                    keyboardOptions = PrivateText,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                }
                if (generating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onStop) { Text("Stop") }
                } else {
                    TextButton(onClick = { showHint = !showHint }) { Text(if (showHint) "Hide guidance" else "Guidance") }
                    TextButton(onClick = { onGenerate(hint) }) { Text(generateLabel) }
                }
            }
        }
    }
}

/** A dropdown for picking one of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    display: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(display) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/**
 * Pick an LLM model: the list the server reports, plus whatever is currently set.
 * [allowDefault] adds a "same as ..." entry that maps to null.
 */
@Composable
fun ModelPicker(
    label: String,
    value: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    defaultLabel: String? = null,
) {
    val health by LocalApp.current.health.state.collectAsState()
    val models = buildList<String?> {
        if (defaultLabel != null) add(null)
        addAll(health.models)
        if (value != null && value.isNotBlank() && value !in health.models) add(value)
    }
    Dropdown(
        label = label,
        options = models,
        selected = value,
        onSelect = onChange,
        display = { it ?: defaultLabel.orEmpty() },
        modifier = modifier,
        placeholder = if (value == null && defaultLabel != null) defaultLabel
        else if (health.models.isEmpty()) "No models (is the LLM server connected?)" else "Choose a model",
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
