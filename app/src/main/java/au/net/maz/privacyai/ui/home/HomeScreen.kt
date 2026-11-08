package au.net.maz.privacyai.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.data.model.Settings
import au.net.maz.privacyai.ui.LocalNav
import au.net.maz.privacyai.ui.LocalStore
import au.net.maz.privacyai.ui.Routes
import au.net.maz.privacyai.ui.components.AppScaffold
import au.net.maz.privacyai.ui.components.IconRow

private data class Tile(val label: String, val subtitle: String, val icon: ImageVector, val route: String)

/**
 * One row each, separated by dividers: Chat, Roleplay (if enabled),
 * Generate image/audio/video, Gallery.
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
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(tiles, key = { it.label }) { t ->
                IconRow(t.icon, t.label, t.subtitle, onClick = { nav.navigate(t.route) })
                HorizontalDivider()
            }
        }
    }
}
