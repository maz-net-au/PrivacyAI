package au.net.maz.privacyai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.Settings
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold

private data class Tile(val label: String, val subtitle: String, val icon: ImageVector, val route: String)

/**
 * Full-width rows: Chat, Roleplay (if enabled), Generate image/audio/video, Gallery.
 * One per row on a phone; two columns on wide screens (unfolded, landscape).
 */
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val settings by LocalStore.current.settings.collectAsState(initial = Settings())
    val tiles = buildList {
        add(Tile("Chat", "Conversations with the assistant", Icons.AutoMirrored.Filled.Chat, Routes.CHATS))
        if (settings.roleplayEnabled) add(Tile("Roleplay", "Stories with your characters", Icons.Filled.TheaterComedy, Routes.ROLEPLAYS))
        add(Tile("Generate image", "Image workflows", Icons.Filled.Image, Routes.section(MediaKind.IMAGE)))
        add(Tile("Generate audio", "Audio workflows", Icons.Filled.AudioFile, Routes.section(MediaKind.AUDIO)))
        add(Tile("Generate video", "Video workflows", Icons.Filled.Movie, Routes.section(MediaKind.VIDEO)))
        add(Tile("Gallery", "Your images, audio and video", Icons.Filled.PhotoLibrary, Routes.gallery(MediaOwner.GALLERY)))
    }
    AppScaffold(title = "PrivacyAI") { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad),
        ) {
            items(tiles, key = { it.label }) { t -> HomeRow(t) { nav.navigate(t.route) } }
        }
    }
}

@Composable
private fun HomeRow(t: Tile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Box(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    t.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
