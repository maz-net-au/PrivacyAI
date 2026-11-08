# First pass: implementation notes

What I (Claude) decided while building the first pass, where `notes.md` didn't say.
Everything here is open to change; none of it is in `notes.md`.

## Build
- `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew :app:testDebugUnitTest` runs the unit tests (empty-slot pruning, speaker parsing).
- Same toolchain as the old app (Gradle 8.2, AGP 8.2.2, Kotlin 1.9.24, compile/target SDK 34, minSdk 26).
- Not yet run on a device.

## Choices made
- **Encryption:**
  - The database is SQLCipher.
  - Media files use Tink streaming AES-GCM, so videos can be played without a decrypted copy on disk.
  - A random data key is wrapped with the PIN (PBKDF2), then with an Android Keystore key.
  - Without a PIN, the data is still encrypted on the device but opens automatically.
- **Recent-apps preview:** blocked with FLAG_SECURE. This also blocks screenshots of the app.
- **Process death:** the key only lives in memory. After Android kills the app, it returns to PIN entry (or Home without a PIN). Screen state isn't restored.
- **Screen state:** kept in memory per screen (`ui/Kept.kt`), never in Android's saved state. Results passed between screens (a picked image, a written prompt) also stay in memory.
- **Images:**
  - Photos are downscaled to 1024 px JPEG, as the old app did. This also strips location metadata.
  - Coil's disk cache is off.
- **"My description":** sent to the model as its own section, "## \<your name\> (the user)", before "Story so far" and the characters.
- **Several characters:** use the old app's "Name:" turn prefixes. A row of speaker chips above the input chooses "Auto" or a specific character.
- **Description model:** changed in Edit chat details ("Image description model"; the default is the roleplay model).
- **Presets:**
  - Edited in Settings → Edit presets.
  - Roleplays refer to a preset by name, so edits apply everywhere.
  - Renaming a preset breaks those references (they fall back to the first preset).
- **Edit characters:** I added this button in Settings so the utility chat's character can be edited while Roleplay is off.
- **Workflow re-ordering:** "Reorder" in the section's menu shows up/down arrows.
- **Roleplay → generate:**
  - The + menu has "Generate image..." and "Generate video...".
  - String fields on the form get "From context" in a roleplay, or "Write with LLM" outside one. This opens the focus screen.
- **Roleplay "Export conversation":** writes a plain-text transcript. The format is a guess.
- **Deleting a roleplay:** needs a second tap (or a snackbar "Delete" action). It isn't a modal.
- **Character reference images:**
  - In a roleplay, they're the roleplay's gallery images, so removing a character leaves its image in the gallery.
  - Main-list reference images are stored with the main list, not in the Gallery.
- **New Generate buttons:** written prompts for character fields, "My description", and "Summary / background" (from the conversation).

- **Context readout:** uses the token count llama-server reports at the end of each reply, so it includes images. The old app counted the text only, via /tokenize. It updates after each reply, not while typing. The context size comes from /props, as in the old app.
- **Impersonate:** a button next to Send in roleplays. It continues whatever is typed, as the old app did.
- **Continue and Regenerate:** on the last reply (long-press). Regenerate adds a version; switch between versions with the ‹ › arrows under the message.

## Not built yet
- Context management / squashing. The summary is only generated on request; nothing is locked or dropped from the conversation.
- Import conversation (format undecided). The menu item says so.
- Old-app features not brought across yet: voice messages.
- Settings reorganisation.
- Keyboard learning: Compose can't yet ask the keyboard not to learn from text fields (IME "no personalised learning"). That needs a custom text field.

## Needs testing on a device
- Optional image slots: the agreed pruning approach, against a real multi-reference workflow.
- Locking while requests run; resuming image jobs after the app is killed.
- Video and audio playback from encrypted files.
- Foldables and rotation.
