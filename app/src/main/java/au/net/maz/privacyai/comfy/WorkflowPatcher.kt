package au.net.maz.privacyai.comfy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single value write: set `graph[node(title)].inputs[input] = value`. */
data class PatchOp(val nodeTitle: String, val input: String, val value: JsonPrimitive)

class PatchException(message: String) : Exception(message)

/**
 * Pure helpers over a ComfyUI API-format graph (JsonObject keyed by node id;
 * each node has `class_type`, `inputs`, and `_meta.title`). Node addressing is
 * by `_meta.title`, which therefore must be unique among referenced nodes —
 * enforced here and at workflow install time.
 */
object WorkflowPatcher {

    /** Id of the node whose `_meta.title` equals [title]; fails on 0 or >1 matches. */
    fun nodeIdByTitle(graph: JsonObject, title: String): Result<String> {
        val matches = graph.entries.filter { (_, node) ->
            (node as? JsonObject)
                ?.get("_meta")?.let { it as? JsonObject }
                ?.get("title")?.jsonPrimitive?.contentOrNull == title
        }
        return when (matches.size) {
            0 -> Result.failure(PatchException("No node titled '$title'"))
            1 -> Result.success(matches.first().key)
            else -> Result.failure(
                PatchException("Multiple nodes titled '$title' — titles referenced by the config must be unique")
            )
        }
    }

    /**
     * Current primitive value of the node's `inputs[input]`, used to seed form
     * defaults. Null when the node/input is missing or the input is a link
     * (a `[nodeId, slot]` array), which has no editable value.
     */
    fun defaultValue(graph: JsonObject, title: String, input: String): JsonPrimitive? {
        val nodeId = nodeIdByTitle(graph, title).getOrNull() ?: return null
        val inputs = graph[nodeId]?.jsonObject?.get("inputs") as? JsonObject ?: return null
        return inputs[input] as? JsonPrimitive
    }

    /** Copy of [graph] with every op applied, or the first addressing error. */
    fun patch(graph: JsonObject, ops: List<PatchOp>): Result<JsonObject> {
        // nodeId -> (input -> value); grouping lets each node be rebuilt once.
        val byNode = mutableMapOf<String, MutableMap<String, JsonPrimitive>>()
        for (op in ops) {
            val nodeId = nodeIdByTitle(graph, op.nodeTitle).getOrElse { return Result.failure(it) }
            val inputs = graph[nodeId]?.jsonObject?.get("inputs") as? JsonObject
                ?: return Result.failure(PatchException("Node '${op.nodeTitle}' has no inputs object"))
            when (inputs[op.input]) {
                null -> return Result.failure(
                    PatchException("Node '${op.nodeTitle}' has no input '${op.input}'")
                )
                !is JsonPrimitive -> return Result.failure(
                    PatchException("Input '${op.input}' of '${op.nodeTitle}' is a node link, not a value")
                )
                else -> byNode.getOrPut(nodeId) { mutableMapOf() }[op.input] = op.value
            }
        }
        val patched = buildJsonObject {
            for ((nodeId, node) in graph) {
                val writes = byNode[nodeId]
                if (writes == null) {
                    put(nodeId, node)
                    continue
                }
                val nodeObj = node.jsonObject
                put(nodeId, buildJsonObject {
                    for ((key, value) in nodeObj) {
                        if (key != "inputs") {
                            put(key, value)
                            continue
                        }
                        put("inputs", buildJsonObject {
                            for ((inKey, inValue) in value.jsonObject) put(inKey, writes[inKey] ?: inValue)
                        })
                    }
                })
            }
        }
        return Result.success(patched)
    }

    /**
     * Remove the nodes behind empty optional file slots (e.g. reference images 4–10 when
     * only 3 were picked), so ComfyUI doesn't try to load a missing file.
     *
     * ComfyUI only validates and runs nodes that feed an output. So each empty slot's node
     * is deleted, and every link to it is dropped. If a link was a *required* input of the
     * node it fed (per [requiredInputs], from /object_info), that node can't run either and
     * is removed too, and so on down the chain. If an output node would be removed, the
     * workflow can't run without that slot and this fails.
     *
     * [requiredInputs] returning null (unknown node type) treats the input as optional.
     */
    suspend fun pruneEmptySlots(
        graph: JsonObject,
        slotTitles: List<String>,
        outputNodeId: String,
        requiredInputs: suspend (classType: String) -> Set<String>?,
    ): Result<JsonObject> {
        val nodes = graph.entries.associate { (id, n) -> id to (n as JsonObject) }.toMutableMap()
        val removed = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        for (title in slotTitles) {
            val id = nodeIdByTitle(graph, title).getOrElse { return Result.failure(it) }
            if (removed.add(id)) queue.add(id)
        }
        // nodeId -> inputs to drop
        val dropped = mutableMapOf<String, MutableSet<String>>()
        while (queue.isNotEmpty()) {
            val gone = queue.removeFirst()
            for ((id, node) in nodes) {
                if (id in removed) continue
                val inputs = node["inputs"] as? JsonObject ?: continue
                for ((key, value) in inputs) {
                    val link = value as? JsonArray ?: continue
                    if (link.size != 2) continue
                    val from = (link[0] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    if (from != gone) continue
                    val classType = (node["class_type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    val required = classType?.let { requiredInputs(it) }?.contains(key) ?: false
                    if (required) {
                        if (removed.add(id)) queue.add(id)
                    } else {
                        dropped.getOrPut(id) { mutableSetOf() }.add(key)
                    }
                }
            }
        }
        if (outputNodeId in removed) {
            return Result.failure(
                PatchException("This workflow can't run with those image slots empty (it needs: ${slotTitles.joinToString()})"),
            )
        }
        val out = buildJsonObject {
            for ((id, node) in nodes) {
                if (id in removed) continue
                val drop = dropped[id]
                if (drop == null) { put(id, node); continue }
                put(id, buildJsonObject {
                    for ((k, v) in node) {
                        if (k != "inputs") { put(k, v); continue }
                        put("inputs", buildJsonObject {
                            for ((ik, iv) in v.jsonObject) if (ik !in drop) put(ik, iv)
                        })
                    }
                })
            }
        }
        return Result.success(out)
    }
}
