# PrivacyAI notes

## All screens
- Support rotation.
- Support dark and light themes.
- Support unusual resolutions (e.g. Samsung folding phones).
- Avoid popup modal dialogs, especially ones with text input (the old version's cramped text boxes in modals were annoying). Menus can be small pop-out elements. A modal is fine for things like the colour picker.
- Most large text fields and images should have an option to generate them (LLM for text, image generator for images).

## Home screen
- Options are rectangular tiles, in this order: Chat, Roleplay, Generate image, Generate audio, Generate video, Gallery.
- The Roleplay tile is only shown if enabled in Settings (off by default).
- No Queue screen.

## Title bar
- The connection status dot is always visible in the title bar.
- Sometimes there is a hamburger menu to the right of it.

## Connection status dot
- Shows connection status to the server(s): one for the LLM, one for the image generator.
- Green = both connected, yellow = one connected, red = neither connected.
- If no address is set for one of them, its status is not included in the colour.
- If no addresses are set at all, it shows red (so the user taps it).
- It is also the global menu.
- It is also the indicator for external tasks in progress (e.g. a spinner with a number in the middle). "External tasks" means active or pending requests to the servers.
- Icon: a ring. Its colour shows connection status (green / yellow / red). When tasks are running it animates as a spinner with the task count in the middle. When idle it is a plain coloured ring.
- Tapping it opens a popup menu showing the LLM status and the image generator status.
- The popup has a button to go to Settings. This is how the user gets to Settings.
- The popup also has the Lock button (hidden if no PIN is set). Lock returns to the PIN entry screen.

## Servers
- Separate addresses for the LLM server and the image generator server. The port is part of the address field (e.g. `192.168.1.50:7800`).
- LLM: takes text, image and audio as input; generates text.
- Image generator: takes text and images as input; outputs images, audio or video.

## Chat (utility)
- Separate item on the home screen from Roleplay.
- The standard "Assistant" role LLM with basic default settings. Meant for quick questions (often "translate this photo").
- Tapping Chat goes to a simple list of utility chats. Each item shows some of the last message (or a generated title).
- Utility chats are always saved.
- Long-press a chat in the list to get the option to delete it.
- Its model, character and preset are set in Settings. The character is one from the main character list, so it can be edited there.
- A + button on the Chat list starts a new utility chat.
- Tapping an existing chat reopens the conversation where it left off.
- Utility chats do not generate images; the image option is hidden there.

## Roleplay
- Separate item on the home screen from Chat. Only shown if enabled in Settings (off by default).
- A cast of characters, described with pictures, used to build large and complex roleplays.
- Tapping Roleplay goes to a list of roleplays. This side has all the extra complexity.
- The roleplay list has a hamburger menu containing "Import conversation" and "Manage characters".
- Model, character and preset are per-chat settings (not global).
- A + button on the Roleplay list starts a new roleplay, which goes through a setup screen first.
  - Old version of the setup screen: screenshots/Screenshot_20261107-021030.png. It needs work.
- Tapping an existing roleplay reopens the conversation where it left off.
- A text chat needs some way to generate a description (using the LLM) that can then be passed to the image generator.
- Generating an image from a roleplay opens the normal image generation flow (not a modal like the old "Scene image" dialog), with the conversation as context, and an option to generate the prompt from that context if the user chooses.
- Generated images are not automatically part of the conversation. They can be added to it using "attach from gallery".
- Using the LLM to write the image prompt takes some of the conversation plus a focus request from the user.
  - What is sent, same as the old app: a system prompt (the "visual director" instruction), then the story background (persona, summary / background, each character's persona and state), all messages not yet squashed into the summary (thinking text left out), then the focus ("the current scene" if blank) and a closing instruction.
  - Prompt writing uses fixed sampling, separate from the roleplay's preset, as the old app did (temperature 0.7, top-p 0.9, top-k 40, max 500 tokens).
  - The prompt-writing system prompt is editable in Settings (Roleplay section).
  - The user can choose which LLM model writes the prompt. It defaults to the roleplay's current model if possible, because switching models takes extra time.
  - Once the prompt is generated, the user can edit it and can also refine it with the LLM.
  - Focus and refine use the same focus input. It is either a large modal covering most of the screen, or a screen of its own (not a small popup).
- After tapping Generate, the app goes back to the chat as soon as generation starts.
- To queue more, the user taps "Regenerate" on the in-progress item. This opens its image generation form with all the previous details filled in, so another can be queued.
  - Regenerate works as if making a new image, except the details are already filled in. From a roleplay it stays tied to that roleplay: same conversation context, result saved to the roleplay's gallery.
  - In the old app, for image-to-image flows the input image usually had to be selected again.
  - New version: Regenerate tries to pre-fill the input image(s) from the previous request. Some image-to-image flows take several reference images, so all of them must be remembered.
  - If an input image can't be pre-filled (e.g. it was deleted), the worst case is that the picker opens on the same gallery.
- Image-to-image and image-to-video flows started from a roleplay should allow selecting an input image from that roleplay's gallery.
- When generating from a roleplay, the workflow has to be selected (e.g. sometimes a video with a reference image).
- Characters' reference images should be in the roleplay's gallery, so they can be picked like any other image there.
- Within a roleplay, everything is saved only in that roleplay.
- Everything inside a roleplay is reached by first opening the roleplay, then either a menu item or interacting with the conversation view.
- Images generated in a roleplay stay in that roleplay. There is a separate gallery view that shows only that roleplay's images.
- A roleplay usually starts with a single character. Others are added during the session, with their details filled in from the existing conversation.
- **Context:** there needs to be a concept of a "context" that is passed to the different sections (e.g. character creation filling in details from the conversation so far). Open: how context management works (left open for now).
- Adding characters mid-session: "Edit chat details" goes back to the setup screen for the whole roleplay, where characters are managed the same way as when creating a new roleplay.
- "Edit chat details" uses the same screen as new-roleplay setup; only the button text changes.

### Roleplay setup screen
- **Title:** used to tell roleplays apart on the Roleplay list.
- **Your name:** so the user can be referred to by name in the chat.
- **My description:** a description of the user, included with the characters as part of the system prompt (exact method open).
- **Characters:** replaces the old single "Character" dropdown. A list of characters, with options to create a character or import one.
  - Long-press a character to edit or remove it.
  - A character imported from the main list is cloned into the roleplay as a snapshot. After that it can drift out of sync with the main list.
  - Characters created here are stored only in this roleplay.
- **Summary / background:** a text box. It is updated later when the context is squashed. Part of the open context questions.
- **Model:** stays.
- **Preset selection:** stays. The user wants to be able to permanently change and save presets (this was never built in the old version). Open: how/where.
- **Overrides for this chat:** stay.
- **Attached images sent:** stays, for all models. For models without "-VL-" in the name, descriptions are sent instead of images. The limit exists because requests were reaching hundreds of MB.
  - For a generated image, its description is the prompt that was used to generate it.
  - There should be an option to generate a description for any attached image.
  - The model used to generate descriptions defaults to the roleplay's current model. If the user changes it, that choice is kept for that roleplay.

## Characters
- **Main list:** characters available to all roleplays. Managed from "Manage characters" in the Roleplay list's hamburger menu.
- **A roleplay's characters:** stored only in that roleplay, either created there or imported (cloned) from the main list. Managed in the roleplay setup screen (also reached from inside the roleplay via "Edit chat details"). There is no "Manage characters" item in the roleplay chat's hamburger menu.
- Once a character is in a roleplay there is no real distinction between "main" and "minor" characters. "Main" mostly just means the first one, as a starting point.
- The same character creation screens and lists are used for both; only where the data is stored differs.
- Each character has a colour. It is picked randomly at first; the user can change it.
- Colour choice: a full colour picker, with the colour adjusted algorithmically for light and dark themes (keep the hue, set lightness per theme, adjust until text contrast is readable). The picker and light/dark message previews can be in a modal.
- In a chat, each character's messages use that character's colour as the message background, with the character's name in a small font at the top of the message, so speakers are easy to tell apart.

## Locking
- The database can be locked from any point, returning to the PIN entry screen.
- The app also locks after some minutes with no interaction. The number of minutes is set in Settings.
- All locking is disabled when no PIN is set.
- Open: how this interacts with Android killing/restoring the app in the background.

## Privacy
- Block the recent-apps preview (Android shows a blank instead of app content).
- Requests continue in the background. The background notification is generic (e.g. "PrivacyAI: 2 tasks running") and reveals no content.
- If the inactivity timeout locks the app while a request is pending, the request is cancelled or fails.

## Settings
- Re-work the old Settings screen (screenshots/Screenshot_20261106-223840.png) to be more organised.
- The rest of the old Settings screen will be revisited later.
- The PIN does not need to be removable at this stage.

### Settings list (to organise later)
Every time something is made a setting, it is added here.
- Inactivity lock time (minutes)
- Add / change PIN
- LLM server address (including port)
- Image generator server address (including port)
- Chat (utility): model
- Chat (utility): character
- Chat (utility): preset
- Enable Roleplay (off by default)
- Roleplay section: a whole section of Settings that only appears when Roleplay is enabled. Contains:
  - Image prompt-writing system prompt (the "visual director" instruction)

## Image generator workflows
- Bring across the old app's ComfyUI field mapping and form generation. These should be modular pieces.
- Keep the old zip format (`api_workflow.json` + `workflow_config.json`, plus the shared `base_enums.json`) so existing workflow packages still install.
- Workflows are imported from within a section (e.g. "Generate image") and take their name from the `name` field in `workflow_config.json`. This replaces the old install dialog (which asked for name and flow type), so some modals are skipped.
- Workflows are not split into text-to-X and image-to-X. Each section (image, audio, video) has one list. Workflows that take an input image are named with "Edit" at the end, so it's obvious.
- Long-press a workflow in the list to rename or remove it.
- It would be nice to be able to re-order workflows in the list. Open: how.
- Optional reference images: a workflow can show e.g. 10 reference-image slots without the user having to fill them all.
  - Approach (agreed, not yet tested): before submitting, the app removes each unfilled slot's Load Image node and its link from the graph. ComfyUI only validates and runs nodes that feed an output, so the removed node is ignored.
  - This only works if the receiving node's input is optional; if it's required, the job fails. Needs testing on a real multi-reference workflow.
  - The old app did not do this: an empty optional file field was left unpatched, so the workflow's saved filename was used (or the job failed if that file was missing on the server).

## Gallery
- An in-progress generation request creates a gallery item at the top straight away.
- That item shows a placeholder image; the rest of its details are filled in.
- This lets the user use "Regenerate" on it without waiting for the first generation to finish.
