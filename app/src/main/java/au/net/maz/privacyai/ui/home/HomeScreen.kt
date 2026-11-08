package au.net.maz.privacyai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.Settings
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold

private data class Tile(val label: String, val icon: ImageVector, val route: String)

/** Rectangular tiles: Chat, Roleplay (if enabled), Generate image/audio/video, Gallery. */
@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val settings by LocalStore.current.settings.collectAsState(initial = Settings())
    val tiles = buildList {
        add(Tile("Chat", Icons.AutoMirrored.Filled.Chat, Routes.CHATS))
        if (settings.roleplayEnabled) add(Tile("Roleplay", Icons.Filled.TheaterComedy, Routes.ROLEPLAYS))
        add(Tile("Generate image", Icons.Filled.Image, Routes.section(MediaKind.IMAGE)))
        add(Tile("Generate audio", Icons.Filled.AudioFile, Routes.section(MediaKind.AUDIO)))
        add(Tile("Generate video", Icons.Filled.Movie, Routes.section(MediaKind.VIDEO)))
        add(Tile("Gallery", Icons.Filled.PhotoLibrary, Routes.gallery(MediaOwner.GALLERY)))
    }
    AppScaffold(title = "PrivacyAI") { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad),
        ) {
            items(tiles, key = { it.label }) { t ->
                Card(onClick = { nav.navigate(t.route) }, modifier = Modifier.fillMaxWidth().height(110.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Icon(t.icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(t.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
