# Roleplay format (draft)

Draft structure for a single roleplay. Nothing here is final.

Tags on each field:
- **[N]** from `notes.md`
- **[O]** seen in the old version's screenshots, not yet confirmed for the new version
- **[P]** proposal, needs a decision

```jsonc
{
  "id": "rp_…",                         // [P] unique id
  "formatVersion": 1,                   // [P] so the structure can change later
  "title": "…",                         // [N] tells roleplays apart in the list
  "yourName": "Mark",                   // [N]
  "myDescription": "…",                 // [N] goes into system prompt (method open)
  "summaryBackground": "…",             // [N] updated when context is squashed (open)

  "model": "Qwen3.6-VL-27B-NR",         // [N]
  "descriptionModel": null,             // [N] null = use "model"; set once user changes it
  "preset": "min_p_v5",                 // [N] reference by name/id [P]
  "overrides": { "temperature": 0.9 },  // [N] per-chat tweaks on top of the preset
  "attachedImagesSent": 3,              // [N] 0 = unlimited

  "characters": [                       // [N] all stored only in this roleplay
    {
      "id": "ch_…",                     // [P]
      "clonedFrom": "main_ch_…",        // [P] optional, for info only; no sync
      "name": "Mara Jensen",            // [O]
      "description": "…",               // [O] short subtitle
      "greeting": "…",                  // [O]
      "systemPrompt": "…",              // [O] context / persona
      "state": "…",                     // [O] "Update with LLM" in old version
      "colour": "#7E57C2",              // [N] random at first, user-changeable
      "referenceImage": "media_…"       // [O] reference to external media [P]
    }
  ],

  "messages": [                         // [N] the conversation
    {
      "id": "msg_…",                    // [P]
      "speaker": "ch_…",                // [P] character id, or "user"
      "text": "…",
      "attachments": [
        {
          "media": "media_…",           // [P] reference to external file
          "description": "…"            // [N] prompt used, or generated description;
                                        //     sent instead of the image on non-VL models
        }
      ],
      "createdAt": "2026-11-07T02:10:00Z" // [P]
    }
  ],

  "images": [                           // [N] this roleplay's own gallery
    {
      "media": "media_…",               // [P] null while pending (placeholder shown) [N]
      "status": "pending | done | failed", // [P]
      "prompt": "…",                    // [N] also serves as the description
      "params": { "width": 1024, "height": 1024, "seed": 123 }, // [O]
      "createdAt": "…"                  // [P]
    }
  ],

  "context": null                       // [N] concept exists; structure left open
}
```

## In the JSON vs stored externally (all [P])

- **In the JSON:** all text and settings: title, names, descriptions, characters, messages, preset reference and overrides, image prompts and parameters.
- **Media [N]:** media items (images, audio, video) are their own JSON structure, separate from the roleplay. The roleplay refers to them by `media_…` id. Structure not yet defined.
- [P] Whatever holds the media's binary data needs the same PIN-based encryption as the database, otherwise a phone backup would expose it.
- **[N]** When a character is cloned into a roleplay, its reference image is copied into the roleplay too.
- **[N]** Deleting a roleplay deletes all media embedded in it (anything already exported or saved outside the app is unaffected).
- **Presets** live outside the roleplay and are referenced by name; only the overrides are stored in the roleplay.

## Open questions
- Presets: if a preset is permanently edited, should roleplays using it change too (reference), or keep a frozen copy (snapshot, like characters)?
- Character fields: which of the [O] fields (description, greeting, system prompt, state, reference image) are wanted, and is anything missing?
- Media: what fields the media JSON structure has, and where the binary data itself lives (inside the media JSON, or a separate file referenced by it).
- Media: is the same media structure used for the main Gallery and a roleplay's own images?
- Context: structure left open.
