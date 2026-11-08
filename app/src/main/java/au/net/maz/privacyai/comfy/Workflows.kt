package au.net.maz.privacyai.comfy

import android.content.Context
import android.net.Uri
import au.net.maz.privacyai.data.Store
import au.net.maz.privacyai.data.db.WorkflowEntity
import au.net.maz.privacyai.data.model.Ids
import au.net.maz.privacyai.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.zip.ZipInputStream

/** An installed workflow, as listed in a section. */
data class Workflow(
    val id: String,
    val name: String,
    val section: MediaKind,
    val sortOrder: Int,
)

/** A workflow loaded for use: its config and graph. */
data class LoadedWorkflow(
    val workflow: Workflow,
    val config: WorkflowConfig,
    val graph: JsonObject,
)

/**
 * Installed ComfyUI workflow packages, stored in the encrypted database.
 * Same zip format as the old app (api_workflow.json + workflow_config.json, optionally
 * base_enums.json). Imported from inside a section; the name comes from the config.
 */
class Workflows(private val context: Context, private val store: Store) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observe(section: MediaKind): Flow<List<Workflow>> =
        store.db.workflows().observeSection(section.key).map { list -> list.map { it.toWorkflow() } }

    suspend fun list(section: MediaKind): List<Workflow> =
        store.db.workflows().section(section.key).map { it.toWorkflow() }

    suspend fun load(id: String): Result<LoadedWorkflow> = runCatching {
        val e = store.db.workflows().get(id) ?: error("Workflow not found")
        LoadedWorkflow(
            e.toWorkflow(),
            json.decodeFromString<WorkflowConfig>(e.configJson),
            json.parseToJsonElement(e.graphJson).jsonObject,
        )
    }

    /** Import a workflow zip into [section]. Returns the installed name. */
    suspend fun import(uri: Uri, section: MediaKind): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var graphText: String? = null
            var configText: String? = null
            var enumsText: String? = null
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when (entry.name.substringAfterLast('/')) {
                            GRAPH_ENTRY -> graphText = zip.readEntry()
                            CONFIG_ENTRY -> configText = zip.readEntry()
                            ENUMS_ENTRY -> enumsText = zip.readEntry()
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Could not open the selected file")
            val graphStr = graphText ?: error("Zip is missing $GRAPH_ENTRY")
            val configStr = configText ?: error("Zip is missing $CONFIG_ENTRY")
            val config = runCatching { json.decodeFromString<WorkflowConfig>(configStr) }
                .getOrElse { error("$CONFIG_ENTRY is not valid: ${it.message?.take(200)}") }
            val graph = runCatching { json.parseToJsonElement(graphStr).jsonObject }
                .getOrElse { error("$GRAPH_ENTRY is not a JSON object. Export the workflow in API format.") }
            validate(config, graph)
            enumsText?.let { importEnumsText(it) }
            val order = (store.db.workflows().section(section.key).maxOfOrNull { it.sortOrder } ?: -1) + 1
            store.db.workflows().put(
                WorkflowEntity(
                    id = Ids.new("wf_"), name = config.name, section = section.key, sortOrder = order,
                    configJson = configStr, graphJson = graphStr, installedAt = System.currentTimeMillis(),
                ),
            )
            config.name
        }
    }

    /** Import a separate base_enums.json (shared dropdown option lists). Returns the list count. */
    suspend fun importEnums(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open the selected file")
            importEnumsText(text)
        }
    }

    private suspend fun importEnumsText(text: String): Int {
        val parsed = runCatching { json.decodeFromString<BaseEnums>(text) }
            .getOrElse { error("Not a valid option list file: ${it.message?.take(200)}") }
        store.mergeBaseEnums(parsed.enums)
        return parsed.enums.size
    }

    suspend fun rename(id: String, name: String) = store.db.workflows().rename(id, name.trim())

    suspend fun delete(id: String) = store.db.workflows().delete(id)

    /** Save a new order for a section's list. */
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { i, id -> store.db.workflows().setOrder(id, i) }
    }

    private fun validate(config: WorkflowConfig, graph: JsonObject) {
        graph.forEach { (nodeId, node) ->
            val obj = node as? JsonObject
            if (obj == null || "class_type" !in obj || "inputs" !in obj) {
                error("Node '$nodeId' has no class_type/inputs. Export the workflow in API format.")
            }
        }
        for (field in config.fields) {
            val type = FieldType.fromWire(field.type)
                ?: error("Field '${field.displayLabel}': unknown type '${field.type}'")
            val nodeId = WorkflowPatcher.nodeIdByTitle(graph, field.nodeTitle)
                .getOrElse { error("Field '${field.displayLabel}': ${it.message}") }
            val inputs = graph[nodeId]?.jsonObject?.get("inputs")?.jsonObject
            when (inputs?.get(field.input)) {
                null -> error("Field '${field.displayLabel}': node '${field.nodeTitle}' has no input '${field.input}'")
                !is JsonPrimitive -> error("Field '${field.displayLabel}': input '${field.input}' is a node link, not a value")
                else -> Unit
            }
            if (type == FieldType.ENUM && field.options == null && field.optionsRef == null) {
                error("Field '${field.displayLabel}': enum needs 'options' or 'optionsRef'")
            }
        }
        WorkflowPatcher.nodeIdByTitle(graph, config.output.nodeTitle).getOrElse { error("Output: ${it.message}") }
    }

    private fun ZipInputStream.readEntry(): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) error("Zip entry too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun WorkflowEntity.toWorkflow() = Workflow(id, name, MediaKind.fromKey(section), sortOrder)

    private companion object {
        const val GRAPH_ENTRY = "api_workflow.json"
        const val CONFIG_ENTRY = "workflow_config.json"
        const val ENUMS_ENTRY = "base_enums.json"
        const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    }
}
