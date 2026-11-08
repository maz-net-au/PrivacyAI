package au.net.maz.privacyai.ui.roleplay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.Media
import au.net.maz.privacyai.data.model.MediaStatus
import au.net.maz.privacyai.ui.components.MediaThumb

/**
 * The roleplay's generations that haven't been sent yet, in a strip above the message box.
 * Collapsible (space is tight). Every image starts hidden: tap to show it, then tap to
 * attach / un-attach it. Long-press: View, Delete.
 */
@Composable
fun GenerationTray(
    items: List<Media>,
    open: Boolean,
    onToggleOpen: () -> Unit,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    attached: Set<String>,
    onToggleAttach: (Media) -> Unit,
    onView: (Media) -> Unit,
    onDelete: (Media) -> Unit,
) {
    if (items.isEmpty()) return
    val running = items.count { it.status == MediaStatus.PENDING }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleOpen).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("Generated, not sent: ${items.size}")
                    if (running > 0) append(" ($running running)")
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, if (open) "Hide" else "Show")
        }
        if (open) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
            ) {
                items(items, key = { it.id }) { m ->
                    TrayThumb(
                        m,
                        revealed = m.id in revealed,
                        attached = m.id in attached,
                        onTap = {
                            when {
                                m.status != MediaStatus.DONE -> {}
                                m.id !in revealed -> onReveal(m.id)
                                else -> onToggleAttach(m)
                            }
                        },
                        onView = { onView(m) },
                        onDelete = { onDelete(m) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrayThumb(
    m: Media,
    revealed: Boolean,
    attached: Boolean,
    onTap: () -> Unit,
    onView: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    Box {
        Box(
            Modifier.size(76.dp).clip(shape)
                .then(if (attached) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                m.status == MediaStatus.PENDING -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp))
                }
                m.status == MediaStatus.FAILED -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error)
                }
                // Spoilered: nothing of the image is loaded until it's shown.
                !revealed -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap to show", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> MediaThumb(m, Modifier.fillMaxSize())
            }
            if (attached) {
                Icon(
                    Icons.Filled.CheckCircle, "Attached",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.White, CircleShape),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (m.status == MediaStatus.DONE) DropdownMenuItem(text = { Text("View") }, onClick = { menu = false; onView() })
            DropdownMenuItem(
                text = { Text(if (m.status == MediaStatus.PENDING) "Cancel and delete" else "Delete") },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}
