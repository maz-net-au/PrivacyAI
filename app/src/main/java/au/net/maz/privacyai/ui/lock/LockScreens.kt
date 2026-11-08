package au.net.maz.privacyai.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.ui.LocalApp
import kotlinx.coroutines.launch

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** First run: create the database, optionally with a PIN. */
@Composable
fun CreateDatabaseScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun create() {
        if (pin != confirm) { error = "The PINs don't match"; return }
        busy = true
        scope.launch {
            runCatching { app.session.create(pin.ifEmpty { null }) }
                .onFailure { error = it.message ?: "Could not create the database"; busy = false }
        }
    }

    Centered {
        Text("PrivacyAI", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Create the database. If you set a PIN, your data is encrypted with it and the app " +
                "asks for it when opened. Without a PIN it opens straight away (still encrypted on the device).",
            style = MaterialTheme.typography.bodyMedium,
        )
        PinField(pin, { pin = it; error = null }, "PIN (optional)")
        if (pin.isNotEmpty()) PinField(confirm, { confirm = it; error = null }, "Confirm PIN", onDone = ::create)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        else Button(onClick = ::create, modifier = Modifier.fillMaxWidth()) { Text("Create database") }
    }
}

/** PIN entry. Wrong PINs can simply be tried again. */
@Composable
fun PinScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    fun unlock() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { app.session.unlock(pin) }.getOrDefault(false)
            if (!ok) {
                error = "Wrong PIN"
                pin = ""
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Centered {
        Text("PrivacyAI", style = MaterialTheme.typography.headlineMedium)
        PinField(pin, { pin = it; error = null }, "PIN", Modifier.focusRequester(focus), onDone = ::unlock)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        else Button(onClick = ::unlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}
