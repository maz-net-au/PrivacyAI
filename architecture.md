# PrivacyAI architecture (draft)

This describes the app's parts and how they connect, based only on what is in `notes.md` and `flow.drawio`.
No technology or library choices have been made. Anything not yet decided is listed under "Open questions".

## Overview

```
                       ┌────────────────────────────┐
                       │  Lock / unlock              │
                       │  (PIN, inactivity timer)    │
                       └─────────────┬──────────────┘
                                     │ opens / closes
                       ┌─────────────▼──────────────┐
                       │  Database (single, local)   │
                       │  encrypted with PIN if set  │
                       └─────────────┬──────────────┘
                                     │ everything below is stored here
  ┌──────────────┬──────────────┬────┴─────────┬──────────────┬──────────────┐
  │ Settings     │ Utility chats│ Roleplays    │ Main         │ Gallery      │
  │              │              │ (each self-  │ character    │ items        │
  │              │              │  contained)  │ list         │              │
  └──────┬───────┴──────┬───────┴──────┬───────┴──────┬───────┴──────┬───────┘
         │              │              │              │              │
         │       ┌──────▼──────────────▼──────┐       │       ┌──────▼───────┐
         │       │  LLM server connection     │       │       │ Image gen    │
         │       │  (text/image/audio in,     │       │       │ server conn. │
         │       │   text out)                │       │       │ (text/images │
         │       └────────────┬───────────────┘       │       │  in; image/  │
         │                    │                       │       │  audio/video │
         └────────────────────┼───────────────────────┘       │  out)        │
                              │                               └──────┬───────┘
                       ┌──────▼───────────────────────────────────────▼──────┐
                       │  Status / global menu (ring icon in title bar)       │
                       │  connection status + count of tasks in progress      │
                       └──────────────────────────────────────────────────────┘
```

## Components

### 1. Lock / unlock
- Decides the first screen: create database (if none exists), PIN entry (if a PIN is set), or straight to Home.
- Locks on demand (Lock button in the status menu) and after an inactivity timeout set in Settings.
- Does nothing if no PIN is set.
- Purpose: stop accidental access, not a determined attacker.

### 2. Database
- One database, created on first run, with an optional PIN.
- If a PIN is set, data at rest is encrypted with the PIN as part of the key, so a whole-phone backup leaks nothing unintentionally.
- PIN can be added or changed later (not removed, for now).
- Holds all app data: settings, utility chats, roleplays, the main character list, gallery items, presets.
- Data only leaves through deliberate export.

### 3. Server connections
- **LLM connection:** one address (with port). Takes text, image and audio; returns text.
- **Image generator connection:** one address (with port). Takes text and images; returns images, audio or video.
- Each reports its own status (connected / not connected / no address set).

### 4. Status / global menu
- Combines both connection statuses into one colour (green / yellow / red; unset addresses excluded; red if none set).
- Shows external tasks in progress (spinner with count).
- Popup: per-server status, Settings button, Lock button (if PIN set).
- Shown in the title bar on every screen.

### 5. Settings
- Global settings (see the Settings list in `notes.md`).
- Includes the defaults for utility chats (model, character, preset) and whether Roleplay is enabled.

### 6. Utility chats
- Simple, always saved, long-press to delete.
- Use the global model / character / preset from Settings.
- Talk to the LLM connection.

### 7. Roleplays
Each roleplay is **self-contained**: everything about it is stored only inside it, and everything in it is reached by first opening the roleplay, then using a menu item or interacting with the conversation view.

A roleplay holds:
- Title, your name, my description, summary / background
- Its own list of characters (created there, or cloned from the main list as a snapshot)
- Model, preset, per-chat overrides
- Attached-images-sent limit
- The model used for generating image descriptions (defaults to the roleplay's model)
- The conversation itself
- Its own images: images generated in a roleplay stay in that roleplay and are shown in a separate gallery view that only shows that roleplay's images (not in the main Gallery)

The setup screen edits all of the above, for both a new roleplay and "Edit chat details".

### 8. Characters
- **Main character list:** global, managed from the Roleplay list's menu.
- **Roleplay characters:** live inside one roleplay (see 7).
- Both use the same character screens; only the storage location differs.
- Importing from the main list into a roleplay makes a copy; the two can then drift apart.
- Each character has a colour (random to start, user-changeable), used for its message backgrounds.

### 9. Presets
- Selected per roleplay (and for utility chats in Settings).
- Can be overridden per chat.
- The user wants to be able to permanently edit and save presets.

### 10. Generation and Gallery
- Generate image / audio / video screens send requests to the image generator connection.
- A request immediately creates a Gallery item at the top, with a placeholder and all other details filled in, so it can be regenerated before the first one finishes.
- Roleplay chats can also send a description (generated with the LLM) to the image generator. Utility chats do not generate images; the option is hidden there.
- Images generated inside a roleplay do not go to the main Gallery; they stay in the roleplay (see 7).

### Task count (status ring)
- "External tasks" means active or pending requests to the servers (LLM and image generator).
- The status ring shows how many there are.

### 11. Image descriptions
- For a generated image, its description is the prompt used to create it.
- Any attached image can have a description generated for it.
- When a roleplay's model is not a "-VL-" model, descriptions are sent instead of images.

### 12. Context (open)
- Some "context" needs to be passed between parts (e.g. filling in a new character's details from the conversation; squashing a long conversation into the summary / background).
- How this works is deliberately left open for now.

## How the parts connect (examples)

- **Starting a roleplay:** Roleplay list → setup (characters copied in or created) → roleplay chat → LLM connection.
- **Adding a character mid-roleplay:** roleplay chat → Edit chat details → setup → character screens (saving into this roleplay) → back to chat.
- **Image from a roleplay:** roleplay chat → LLM writes a description → image generator → image saved in the roleplay, shown in its own gallery view → status ring counts the requests while they are active or pending.

## Open questions
- The overview diagram's connecting lines are approximate.
- How context is built, passed between parts, and squashed (and how summary / background is updated).
- How "my description" is combined with the characters in the system prompt.
- Where and how presets are permanently edited and saved.
- Where the user changes the image-description model for a roleplay.
- How locking interacts with Android killing / restoring the app in the background.
- Whether there's an option to delete or export gallery items, chats, roleplays (export of conversations existed in the old version).
- What triggers "Import conversation" and what format it reads.
