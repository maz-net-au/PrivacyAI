package au.net.maz.privacyai.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.net.maz.privacyai.PrivacyApp
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.MediaKind
import au.net.maz.privacyai.data.model.MediaOwner
import au.net.maz.privacyai.security.LockState
import au.net.maz.privacyai.ui.characters.CharacterEditScreen
import au.net.maz.privacyai.ui.characters.CharacterImportScreen
import au.net.maz.privacyai.ui.characters.CharacterListScreen
import au.net.maz.privacyai.ui.chat.ChatListScreen
import au.net.maz.privacyai.ui.chat.ChatScreen
import au.net.maz.privacyai.ui.gallery.MediaGridScreen
import au.net.maz.privacyai.ui.gallery.ViewerScreen
import au.net.maz.privacyai.ui.generate.FocusScreen
import au.net.maz.privacyai.ui.generate.InputPickerScreen
import au.net.maz.privacyai.ui.generate.SectionScreen
import au.net.maz.privacyai.ui.generate.WorkflowFormScreen
import au.net.maz.privacyai.ui.home.HomeScreen
import au.net.maz.privacyai.ui.lock.CreateDatabaseScreen
import au.net.maz.privacyai.ui.lock.PinScreen
import au.net.maz.privacyai.ui.roleplay.RoleplayChatScreen
import au.net.maz.privacyai.ui.roleplay.RoleplayListScreen
import au.net.maz.privacyai.ui.roleplay.RoleplaySetupScreen
import au.net.maz.privacyai.ui.settings.PresetsScreen
import au.net.maz.privacyai.ui.settings.SettingsScreen

val LocalApp = staticCompositionLocalOf<PrivacyApp> { error("no app") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("no nav") }
val LocalStore = staticCompositionLocalOf<Store> { error("locked") }

/**
 * Routes carry only ids, never content: Android may keep navigation state outside the app.
 * Content passes between screens through [Results] (memory only).
 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PRESETS = "presets"
    const val CHATS = "chats"
    const val CHAT = "chat/{id}"
    const val ROLEPLAYS = "roleplays"
    const val RP_SETUP = "rp/setup/{id}"
    const val RP_CHAT = "rp/chat/{id}"
    const val CHARACTERS = "characters"
    const val CHARACTER = "character/{owner}/{id}"
    const val CHARACTER_IMPORT = "character-import/{rp}"
    const val SECTION = "section/{section}?owner={owner}&result={result}"
    const val FORM = "form/{workflow}?owner={owner}&from={from}&result={result}&popTo={popTo}"
    const val FOCUS = "focus?owner={owner}&result={result}&ctx={ctx}"
    const val GALLERY = "gallery/{owner}?result={result}"
    const val VIEWER = "viewer/{id}?owner={owner}&kind={kind}"
    const val INPUT_PICKER = "input/{kind}?owner={owner}&result={result}"

    private fun e(s: String) = Uri.encode(s)

    fun chat(id: String) = "chat/${e(id)}"
    fun rpSetup(id: String) = "rp/setup/${e(id)}"
    fun rpChat(id: String) = "rp/chat/${e(id)}"
    /** owner: "main" for the main list, else a roleplay id. id: "new" to create. */
    fun character(owner: String, id: String) = "character/${e(owner)}/${e(id)}"
    fun characterImport(rp: String) = "character-import/${e(rp)}"
    fun section(section: MediaKind, owner: String = MediaOwner.GALLERY, result: String = "") =
        "section/${section.key}?owner=${e(owner)}&result=${e(result)}"
    fun form(workflow: String, owner: String, from: String = "", result: String = "", popTo: String = "") =
        "form/${e(workflow)}?owner=${e(owner)}&from=${e(from)}&result=${e(result)}&popTo=${e(popTo)}"
    /** [ctx]: a Results key holding a Character to use as the context instead of the roleplay. */
    fun focus(owner: String, result: String, ctx: String = "") = "focus?owner=${e(owner)}&result=${e(result)}&ctx=${e(ctx)}"

    /** Results key for the character a reference image is being generated for. */
    fun characterContextKey(resultKey: String) = "charctx:$resultKey"
    /** A media grid. With [result], tapping an item picks it. */
    fun gallery(owner: String, result: String = "") = "gallery/${e(owner)}?result=${e(result)}"
    /**
     * View one item. With [owner], swiping moves through that gallery (only [kind] if
     * given, matching the gallery's tab); without, just this item.
     */
    fun viewer(id: String, owner: String = "", kind: MediaKind? = null) =
        "viewer/${e(id)}?owner=${e(owner)}&kind=${kind?.key.orEmpty()}"
    /** Choose a file for a workflow input (Roleplay / Device / Gallery tabs). */
    fun inputPicker(kind: MediaKind, owner: String, result: String) =
        "input/${kind.key}?owner=${e(owner)}&result=${e(result)}"

    fun newResultKey() = Ids.new("r_")
}

@Composable
fun AppRoot(app: PrivacyApp, onLocked: () -> Unit) {
    val state by app.session.state.collectAsState()
    CompositionLocalProvider(LocalApp provides app) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = state) {
                LockState.Starting -> Box(Modifier.fillMaxSize())
                LockState.NoDatabase -> CreateDatabaseScreen()
                LockState.Locked -> {
                    // Throw away every screen's state from before the lock.
                    LaunchedEffect(Unit) { onLocked() }
                    PinScreen()
                }
                is LockState.Unlocked -> key(s.epoch) {
                    CompositionLocalProvider(LocalStore provides s.store) { MainNav() }
                }
            }
        }
    }
}

@Composable
private fun MainNav() {
    val nav = rememberNavController()
    fun str(name: String) = navArgument(name) { type = NavType.StringType; defaultValue = "" }
    CompositionLocalProvider(LocalNav provides nav) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.PRESETS) { PresetsScreen() }
            composable(Routes.CHATS) { ChatListScreen() }
            composable(Routes.CHAT, listOf(str("id"))) { ChatScreen(it.arg("id")) }
            composable(Routes.ROLEPLAYS) { RoleplayListScreen() }
            composable(Routes.RP_SETUP, listOf(str("id"))) { RoleplaySetupScreen(it.arg("id")) }
            composable(Routes.RP_CHAT, listOf(str("id"))) { RoleplayChatScreen(it.arg("id")) }
            composable(Routes.CHARACTERS) { CharacterListScreen() }
            composable(Routes.CHARACTER, listOf(str("owner"), str("id"))) {
                CharacterEditScreen(it.arg("owner"), it.arg("id"))
            }
            composable(Routes.CHARACTER_IMPORT, listOf(str("rp"))) { CharacterImportScreen(it.arg("rp")) }
            composable(Routes.SECTION, listOf(str("section"), str("owner"), str("result"))) {
                SectionScreen(MediaKind.fromKey(it.arg("section")), it.arg("owner").ifBlank { MediaOwner.GALLERY }, it.arg("result"))
            }
            composable(Routes.FORM, listOf(str("workflow"), str("owner"), str("from"), str("result"), str("popTo"))) {
                WorkflowFormScreen(
                    workflowId = it.arg("workflow"),
                    owner = it.arg("owner").ifBlank { MediaOwner.GALLERY },
                    fromMediaId = it.arg("from"),
                    resultKey = it.arg("result"),
                    popTo = it.arg("popTo"),
                )
            }
            composable(Routes.FOCUS, listOf(str("owner"), str("result"), str("ctx"))) {
                FocusScreen(it.arg("owner"), it.arg("result"), it.arg("ctx"))
            }
            composable(Routes.GALLERY, listOf(str("owner"), str("result"))) {
                MediaGridScreen(it.arg("owner").ifBlank { MediaOwner.GALLERY }, it.arg("result"))
            }
            composable(Routes.VIEWER, listOf(str("id"), str("owner"), str("kind"))) {
                ViewerScreen(it.arg("id"), it.arg("owner"), it.arg("kind").ifBlank { null }?.let(MediaKind::fromKey))
            }
            composable(Routes.INPUT_PICKER, listOf(str("kind"), str("owner"), str("result"))) {
                InputPickerScreen(it.arg("owner").ifBlank { MediaOwner.GALLERY }, MediaKind.fromKey(it.arg("kind")), it.arg("result"))
            }
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(name: String): String =
    arguments?.getString(name).orEmpty()
