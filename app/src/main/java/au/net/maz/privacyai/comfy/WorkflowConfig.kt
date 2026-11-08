package au.net.maz.privacyai.comfy

import kotlinx.serialization.Serializable

/**
 * From the old app, unchanged so existing workflow zips still install.
 *
 * `workflow_config.json` — maps a ComfyUI API workflow (arbitrary node graph in
 * `api_workflow.json`) to the small set of fields the user edits in the native
 * form. Both files arrive together in an uploaded zip. Parsed with
 * `ignoreUnknownKeys` so configs may carry extra keys for other tools.
 */
@Serializable
data class WorkflowConfig(
    val version: Int = 1,
    /** Display name shown in the workflow picker; prefills the install dialog. */
    val name: String,
    val description: String? = null,
    val fields: List<WorkflowField> = emptyList(),
    val output: WorkflowOutput,
)

/** One user-editable input, addressed by node `_meta.title` + `inputs` key. */
@Serializable
data class WorkflowField(
    /** Must match exactly one node's `_meta.title` in the graph. */
    val nodeTitle: String,
    /** Key inside that node's `inputs` object. */
    val input: String,
    /** One of [FieldType.wire] — float, int, string, bool, seed, enum, file. */
    val type: String,
    /** Display label; falls back to [input] when null. */
    val label: String? = null,
    val mandatory: Boolean = false,
    /** Shown as supporting text under the form control. */
    val description: String? = null,
    /** Inline enum options — when present, overrides [optionsRef]. */
    val options: List<String>? = null,
    /** Name of a list in the installed base enums file (e.g. "samplers"). */
    val optionsRef: String? = null,
    /** For type=file: image | audio | video — selects the picker. */
    val fileKind: String = "image",
) {
    val displayLabel: String get() = label ?: input

    /** Inline options win; else the referenced base list; else empty (form error). */
    fun resolveOptions(base: Map<String, List<String>>): List<String> =
        options ?: optionsRef?.let { base[it] } ?: emptyList()
}

/** Which node's history output entry holds the generated file references. */
@Serializable
data class WorkflowOutput(
    /** `_meta.title` of the output node (e.g. a SaveImage node). */
    val nodeTitle: String,
    /** Key in the node's history `outputs` entry: "images", "audio", "gifs"… */
    val field: String = "images",
)

/** The data types a [WorkflowField] may declare. */
enum class FieldType(val wire: String) {
    FLOAT("float"), INT("int"), STRING("string"), BOOL("bool"),
    /** Whole number with a "randomize" action; seeded fresh when the form opens. */
    SEED("seed"),
    ENUM("enum"), FILE("file");

    companion object {
        fun fromWire(wire: String): FieldType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `base_enums.json` — user-uploaded named option lists (e.g. "samplers") shared
 * across workflow configs via [WorkflowField.optionsRef].
 */
@Serializable
data class BaseEnums(
    val version: Int = 1,
    val enums: Map<String, List<String>> = emptyMap(),
)
