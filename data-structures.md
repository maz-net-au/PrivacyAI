# Data structures (draft)

Which structures exist, what references what, and where each is stored.

Tags:
- **[N]** from the user / `notes.md`
- **[O]** seen in the old version's screenshots, not yet confirmed
- **[P]** proposal, needs a decision

See also: `roleplay-format.md` for the roleplay structure in detail.

## Structures

| Structure | Holds | Where stored |
|---|---|---|
| **Settings** | inactivity minutes, server addresses, utility chat model/character/preset, Roleplay enabled **[N]** | database **[N]** |
| **Preset** | named parameter set, permanently editable **[N]**; the parameters themselves **[O]** | database **[N]** |
| **Main character** | name, colour **[N]**; description, greeting, system prompt, state, reference image **[O]** | database **[N]** |
| **Utility chat** | messages, title or last-message snippet **[N]** | database **[N]** |
| **Roleplay** | see `roleplay-format.md`, including its own characters and media **[N]** | database **[N]** |
| **Media** | its own JSON structure **[N]**: type (image/audio/video), status, prompt, model, parameters **[O]** | metadata in database; binary data must be encrypted at rest **[N]**, as individually encrypted files **[P]** |
| **Request / task** | active or pending server requests, counted on the status ring **[N]** | open |
| **Context** | open | open |

## References

```
Settings ──► Preset              (utility chat preset)      [N]
Settings ──► Main character      (utility chat character, a reference so edits apply) [N]
Utility chat ──► Media           (attached photos, e.g. "translate this photo") [N]

Roleplay ──► Preset              (by name/id) + overrides   [N]
Roleplay ──► Media               (attachments + its own gallery) [N]
Roleplay contains Characters     (copies, not references)   [N]
   Character ──► Media           (reference image, owned by the roleplay) [N]
   Character.clonedFrom ──► Main character (info only, no sync) [P]

Main character ──► Media         (reference image)          [O]
Main Gallery = Media not owned by a roleplay                [P]
Media.owner ──► Roleplay or "main gallery"                  [P]
Request ──► Media                (fills in the placeholder when done) [N]
```

## Rules

- **Cloning a character into a roleplay [N]:** the character and its reference image are both copied into the roleplay. The roleplay never points at media owned by the main list.
- **Deleting a roleplay [N]:** deletes all media embedded in it. Anything already exported or saved outside the app is unaffected.
- **Requests [N]:** continue in the background. If the inactivity timeout locks the app while a request is pending, the request is cancelled or fails.
- **Background notification [N]:** while requests run in the background, the notification is generic (e.g. "PrivacyAI: 2 tasks running") and reveals no content.
- **Utility chat character [N]:** chosen from the main character list. Settings stores a reference to it (not a copy), so editing that character updates the utility chats.

## Open questions
- Media binary data: confirm individually encrypted files (see notes below).
- Context: structure left open.

## Notes on media storage [P]
Individually encrypted files make sense:
- Videos and images can be large; keeping them out of the database keeps it small and fast.
- Large files can be read in pieces (e.g. video playback) without loading the whole thing.
- Deleting a roleplay or media item means deleting its files.

Things to handle:
- File names should not reveal anything (random ids, not prompts or titles).
- File sizes and counts are still visible to anyone with filesystem access, even though contents are not.
- If the app is killed mid-write, leftover files need cleaning up.
