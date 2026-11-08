package au.net.maz.privacyai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.net.maz.privacyai.net.Health
import au.net.maz.privacyai.net.ServerStatus
import au.net.maz.privacyai.ui.LocalApp
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.Routes

/** An item in a screen's hamburger menu. */
data class MenuItem(val label: String, val onClick: () -> Unit)

/**
 * Every screen's frame: title bar with the status ring (always) and, when the screen has
 * one, a hamburger menu to its right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    menu: List<MenuItem> = emptyList(),
    snackbar: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    StatusRing()
                    if (menu.isNotEmpty()) HamburgerMenu(menu)
                },
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
private fun HamburgerMenu(items: List<MenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Menu, "Menu") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { open = false; item.onClick() })
            }
        }
    }
}

/**
 * The status ring: colour = server connections, spinner with a number = requests in
 * progress. Tapping opens the global menu (server status, Settings, Lock).
 */
@Composable
fun StatusRing() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val health by app.health.state.collectAsState()
    val tasks by app.tasks.ids.collectAsState()
    var open by remember { mutableStateOf(false) }
    val colour = when (health.colour) {
        Health.Colour.GREEN -> Color(0xFF2E9E4F)
        Health.Colour.YELLOW -> Color(0xFFE0A800)
        Health.Colour.RED -> Color(0xFFD93A3A)
    }
    Box {
        IconButton(onClick = { open = true; app.health.refreshNow() }) {
            Ring(colour, tasks.size)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StatusLine("LLM", health.llm)
            StatusLine("IMG", health.image)
            Text(
                if (tasks.isEmpty()) "No tasks running" else "${tasks.size} task${if (tasks.size == 1) "" else "s"} running",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                open = false
                nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
            })
            if (app.session.hasPin) {
                DropdownMenuItem(text = { Text("Lock") }, onClick = { open = false; app.session.lock() })
            }
        }
    }
}

@Composable
private fun ColumnScope.StatusLine(name: String, s: ServerStatus) {
    val text = when (s) {
        ServerStatus.NOT_SET -> "no address set"
        ServerStatus.UNKNOWN -> "checking"
        ServerStatus.UP -> "connected"
        ServerStatus.DOWN -> "not connected"
    }
    Text("$name: $text", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun Ring(colour: Color, count: Int) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val w = 3.dp.toPx()
            if (count == 0) {
                drawCircle(colour, style = Stroke(w), radius = size.minDimension / 2 - w / 2)
            } else {
                drawCircle(colour.copy(alpha = 0.25f), style = Stroke(w), radius = size.minDimension / 2 - w / 2)
                drawArc(
                    colour, startAngle = angle, sweepAngle = 100f, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        if (count > 0) Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colour)
    }
}
