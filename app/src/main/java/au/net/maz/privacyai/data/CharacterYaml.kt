package au.net.maz.privacyai.data

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Reads the simple character YAML format (from the old app; text-generation-webui style):
 *
 * ```yaml
 * name: Lydia
 * greeting: "..."   # optional
 * context: |        # the persona / system prompt
 *   ...
 * ```
 * Other keys are ignored. `{{char}}` / `{{user}}` placeholders are kept as they are.
 */
object CharacterYaml {

    data class Parsed(val name: String, val greeting: String, val context: String)

    /** Throws with a readable message if the file isn't a usable character. */
    fun parse(text: String): Parsed {
        // SafeConstructor: plain maps, lists and strings only; no Java objects from tags.
        val doc = runCatching { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) }
            .getOrElse { error("Not a valid YAML file") }
        val map = doc as? Map<*, *> ?: error("Not a character file")
        val name = map["name"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) error("The file has no name")
        return Parsed(
            name = name,
            greeting = map["greeting"]?.toString()?.trim().orEmpty(),
            context = map["context"]?.toString()?.trim().orEmpty(),
        )
    }
}
