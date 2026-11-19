# PrivacyAI

PrivacyAI is a Kotlin / Jetpack Compose Android app for using AI privately. It talks only to the user's own servers:
- **llama-server:** an OpenAI-compatible LLM server, used for chat and roleplay.
- **ComfyUI:** the image generator, which produces images, audio and video from installed workflows.

There are no cloud services. Data must not leak into the rest of Android. Everything is encrypted at rest, and the PIN is part of the key when one is set. Deliberate export is allowed.

It replaces an older app at `/home/maz/src/LLMApp` (package `net.maz.llamachat`). That app is the reference for behaviour: impersonate, prefill echo handling, scroll pinning, the workflow zip format, and so on. Port only what the user asks for. When something "worked in the old app", read the old code before guessing.

## Working with the user (important)

- **Never run `git commit`** (or amend or reset commits). Leave changes uncommitted. The directory may not even be a git repo.
- **Ask, don't invent.** Build what the user stated. For a genuine design decision (behaviour, where a button goes, what happens to data), ask first, or offer the change rather than making it. Small, obvious implementation choices are fine, but mention them in the reply.
- **Report faithfully.** Verification here is build plus unit tests only. There's no device or emulator on this machine, so say "builds, not tested on a device".
- **End changes with the install command** the user runs themselves:
  `! ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Don't draft feedback or bug reports** to Anthropic.
- **Don't run `adb` against the user's phone** (no `adb devices`, `adb logcat`, etc.). The user declined this. They paste logs and crash traces themselves.

## Build and test

- `./gradlew -q assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew -q testDebugUnitTest` runs the JVM unit tests (`app/src/test/.../LogicTest.kt`).
- Builds often take longer than the default 2-minute tool timeout. Use `timeout: 600000`.
- **Toolchain:** Kotlin 1.9.24, AGP 8.2.2, Compose BOM 2024.06.00, compile/target SDK 34, minSdk 26.
- **Libraries:** Room + SQLCipher, Tink, Coil (disk cache off), OkHttp SSE, kotlinx.serialization, SnakeYAML (SafeConstructor).

## Documents in the repo

| File | What it is | Status |
|---|---|---|
| `notes.md` | **The user's own spec**: screens, behaviour and rules. The most authoritative doc. | User-written. Later chat requests have refined parts of it. |
| `IMPLEMENTATION.md` | Choices Claude made in the first build pass, plus "not built yet" and "needs device testing" lists. | Partly stale. Many later changes, listed below, aren't recorded there. |
| `architecture.md` | Early, technology-free architecture draft. | Planning draft. The code is the truth now. |
| `data-structures.md` | Early draft of the structures and references, tagged [N]/[O]/[P]. | Planning draft. See `data/model/Models.kt`. |
| `roleplay-format.md` | Draft JSON for one roleplay. | Planning draft. The real class is `Roleplay` in `Models.kt`. |
| `flow.drawio`, `screens.drawio` | The user's screen and flow diagrams. | User-made. |

Claude's memory for this project (`~/.claude/projects/-home-maz-src-PrivacyAI/memory/`) also holds the no-commit and ask-first rules.

## Code map (`app/src/main/java/au/net/maz/privacyai/`)

**App, security, data**
- `PrivacyApp.kt`: the app object. Holds `appScope`, `tasks`, `engine` (ChatEngine), `runner` (ComfyRunner), `results`, `health`, `llama`. Its `onUnlocked` / `onLocking` hooks run on unlock and lock.
- `MainActivity.kt`: sets FLAG_SECURE, uses edge-to-edge, and clears ViewModels on lock.
- `security/Session.kt`: lock state, PIN and inactivity lock. Creates the `Store` on unlock and closes it on lock.
- `security/Vault.kt`: key wrapping. A random data key is wrapped with the PIN (PBKDF2) and then with the Android Keystore.
- `data/Store.kt`: **all data access**: settings, presets, characters, chats, roleplays, media, and workflows (in the kv/db tables).
  - Exists only while unlocked.
  - Big objects (chats, roleplays) are JSON blobs, changed with `updateRoleplay { }` / `updateChat { }` under a mutex.
  - Also holds the in-memory `showSpoilers` flag.
- `data/db/Database.kt`: Room entities and DAOs on SQLCipher.
- `data/model/Models.kt`: Settings, Character, Roleplay, Chat, Message (versions), Attachment, Media / MediaInfo, MediaOwner, MediaKind, ContextUsage.
- `data/model/Presets.kt`: sampling presets and overrides.
- `data/media/`:
  - `MediaFiles` stores media as Tink streaming-encrypted files.
  - `MediaImages` is the Coil fetcher (`MediaRef`).
  - `PhotoImport` imports a picked or camera image as a 1024px JPEG with no metadata; video and audio are kept as they are.
- `data/CharacterYaml.kt`: imports a character from YAML (name / greeting / context).

**LLM**
- `net/LlamaClient.kt`, `net/Dto.kt`: llama-server streaming chat (SSE) via `ChatRequest`, which has `stop`, `maxTokens` and `ignoreEos`.
- `net/HealthMonitor.kt`: polls both servers for the status ring and the model list. `refreshNow()` forces a check (used when the status ring is tapped), and `checking` is true while it runs.
- `llm/Prompts.kt`: every prompt.
  - Roleplay requests use the "Name:" turn prefixes. The stop strings are the user's name plus "\nCharacterName:" for each character.
  - Also holds the character-field / my-description / summary / image-prompt generators (guidance = hint or steer) and `parseSpeaker`.
- `llm/ChatEngine.kt`: sending, regenerate / continue (long-press = `ignoreEos` with a 1000-token cap), impersonate, one-shot `complete()`.
  - **Live streaming:** each token goes to the in-memory `engine.live`; the DB is saved every second and at the end. Screens overlay `live` on pending messages.
  - **Prefill echo:** newer llama-servers send the prefilled assistant text back at the start of the stream. `withoutEcho` / `foldOntoPrefill` deal with that, as the old app did.
- `llm/Think.kt`: strips and splits `<think>` reasoning.

**Image generator (ComfyUI)**
- `net/ComfyClient.kt`, `net/ComfyDto.kt`: queue, history, download. Generation time comes from the history execution timestamps.
- `comfy/Workflows.kt`, `WorkflowConfig.kt`, `WorkflowPatcher.kt`: installed workflow zips (same format as the old app: `api_workflow.json` + `workflow_config.json` [+ `base_enums.json`]), their form fields, and patching values into the graph.
- `comfy/ComfyRunner.kt`: runs jobs into placeholder media items and resumes jobs after unlock or a restart. The placeholder's `MediaInfo` (including the spoiler flag) is kept when the result is downloaded. `mediaForJob()` maps a task to its media item.
- `tasks/Tasks.kt`: `TaskManager`, which counts running server requests for the status ring. `idsOldestFirst()` gives the oldest task first. `TaskService` is the foreground service with a generic notification and an elapsed-time chronometer.

**UI** (`ui/`)
- `AppRoot.kt`: NavHost and `Routes`. Routes carry ids only, never content. Also the CompositionLocals (`LocalApp`, `LocalStore`, `LocalNav`).
- `Kept.kt`: `kept()` / `screenState()`, screen state held in a ViewModel per back-stack entry. It is never put in Android saved state, for privacy.
- `Results.kt`: in-memory results passed between screens (`put` / `take` / `peek`), keyed by a `Routes.newResultKey()`.
- `home/HomeScreen.kt`: one `IconRow` per row.
- `components/`:
  - `TopBar.kt`: `AppScaffold`, the status ring and global menu, and `MenuItem`.
    - Tapping the ring tests the connections. The menu (240dp wide) shows each server with a traffic light: green connected, red failing, grey not set, and a spinner while checking.
    - The "N tasks running" item, below a divider, opens the oldest running task (`routeFor(taskId)` maps the task id prefixes `reply:rp_`, `reply:chat_`, `impersonate`, `job_` to a route).
  - `IconRow.kt`, `Fields.kt` (`GenerateField`, `GuidanceDialog`, `DiscardDialog`, `PrivateText`), `Markdown.kt`, `MediaViews.kt` (`MediaThumb`, `MediaFull`, encrypted player), `ZoomableImage.kt`, `ColourPicker.kt`.
- `chat/ChatParts.kt`: shared chat pieces.
  - `MessageBubble`: tap shows the action chips. There is no long-press menu.
    - Chips are icon-only (`ActionChip`, 48x40dp, the label is the content description) so a row doesn't wrap. Some chips have a long-press action too.
    - Only the **last** message gets Delete, because deleting an earlier one breaks the turn order the server needs. Earlier messages get Copy (+ Edit in roleplay).
    - Attachments can be hidden as spoilers (`hiddenAttachments` / `onReveal`). Roleplay message media is always spoilered until tapped.
  - `ChatInput`, `rememberPhotoSources` (device photo picker / file browser / camera), and `FollowNewest` (auto-scroll that follows the newest text, plus the keyboard).
- `chat/ChatScreens.kt`: the utility chat list and chat.
- `roleplay/`:
  - `RoleplayScreens.kt`: the list and setup screen.
  - `RoleplayChat.kt`: the chat. Speaker chips, impersonate, in-place edit, plus menu.
  - `GenerationTray.kt`: unsent generations. Spoilered at first; tap reveals, tap again opens; swipe up dismisses (`Roleplay.trayDismissed`); long-press offers View / Delete.
- `characters/CharacterScreens.kt`: character list and editor, used both for the main list and inside a roleplay. Includes YAML import and reference images.
- `generate/`:
  - `SectionScreen.kt`: the workflow list per kind.
  - `WorkflowFormScreen.kt`: the form, with a floating Generate button when the real one is off screen.
  - `FocusScreen.kt`: writes or refines a prompt with the LLM.
  - `InputPickerScreen.kt`: Roleplay / Device / Gallery tabs, a file browser and the camera.
- `gallery/GalleryScreens.kt`: the gallery grid with All / Images / Audio / Video tabs, and the viewer, which swipes through the gallery.
  - The main gallery hides spoiler / NSFW items unless its eye toggle is on. The toggle resets on opening and on lock.
  - Pickers have their own per-pick eye.
  - Viewer details: Delete (says **Cancel** while a generation is in progress; it still cancels and deletes), Export, Regenerate; then the prompt, the spoiler switch, settings, and the generation time.
  - **Pager gotcha:** the `HorizontalPager` key lambda can run with a stale page count while the list shrinks (a generation finishing or being cancelled). Always use `list.getOrNull(i)` in the key and the page content, never `list[i]`. Indexing directly crashed the app with IndexOutOfBounds.
- `settings/`: General / Roleplay tabs (servers, security, chat, token limits) and the preset editor.
- `lock/LockScreens.kt`: PIN setup and entry.
- `theme/`: the theme and per-character bubble colours.

## Privacy conventions to keep

- **No content in Android saved state, nav arguments, notifications or logs.** Use `kept` / `Results` (memory only) instead.
- **Text fields use `PrivateText` keyboard options.**
- **The foreground notification stays generic** ("N tasks running").
- **Media is only ever on disk encrypted.** The camera's temporary file is deleted straight after import.
- **Imports are copied into the app.** Nothing keeps access to the original file.

## Open threads

- **Character model in roleplay setup:** the character editor opened from a roleplay's setup screen uses the roleplay's *saved* model. For a new roleplay that is the default chat model, because the setup can't be saved without starting the roleplay. The user was offered a fix (use the setup screen's unsaved model) and hasn't answered.
- **Attach thumbnail:** "Choose from roleplay gallery" attached the image but showed no thumbnail. A cancellation race in the result handling was fixed, but it is unconfirmed whether that was the cause.
- **Viewer crash** (stacking Regenerate → Generate from an in-progress viewer): fixed with the pager guards above. The user hasn't confirmed it on the device yet.
- **Offered but not built** (wait for the user to ask):
  - Roleplay gallery delete warnings for reference images.
  - Empty send in the utility chat.
  - A two-part Impersonate button.
  - Fixing a race in `ComfyRunner.ensureLoop` (unsynchronised; it could submit a job twice).
  - Edit for utility chat messages (Copy only for now).
  - Limiting when "Describe images" shows.
  - Cancel keeping a "Stopped" item instead of deleting it.
  - Status-menu routes for field-generation tasks (they currently don't navigate).
