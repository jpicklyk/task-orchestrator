package io.github.jpicklyk.mcptask.current.application.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical JSON form of a parsed config document root (the `canonical_json` column of a config version).
 *
 * The canonical form is the SafeConstructor root map serialized as JSON with object keys sorted by
 * [String.compareTo] at every depth and no insignificant whitespace. It is parser-independent: a stored version
 * is re-read by decoding the map and handing it to the config parser, never by re-parsing the YAML.
 * [MODEL_VERSION] is stored beside it so the form can evolve.
 */
object CanonicalConfigJson {
    const val MODEL_VERSION = 1

    /** Encodes [root]; a null or empty root encodes `{}`. */
    fun encode(root: Map<String, Any?>?): String = toElement(root ?: emptyMap<String, Any?>()).toString()

    /**
     * Decodes [json] into the shapes SnakeYAML produces (`LinkedHashMap`, `ArrayList`, `String`, `Boolean`, `Int` when
     * an integer fits else `Long`, `Double` for fractions). Returns null when [json] is not a JSON object.
     */
    fun decode(json: String): Map<String, Any?>? {
        val element =
            try {
                Json.parseToJsonElement(json)
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception
            ) {
                return null
            }
        val obj = element as? JsonObject ?: return null
        @Suppress("UNCHECKED_CAST")
        return fromElement(obj) as Map<String, Any?>
    }

    private fun toElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Map<*, *> ->
                JsonObject(
                    value.entries
                        .map { (k, v) -> k.toString() to v }
                        .sortedBy { it.first }
                        .associate { (k, v) -> k to toElement(v) }
                )
            is Iterable<*> -> JsonArray(value.map { toElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
            is String -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private fun fromElement(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonObject -> LinkedHashMap<String, Any?>().also { map -> element.forEach { (k, v) -> map[k] = fromElement(v) } }
            is JsonArray -> ArrayList<Any?>().also { list -> element.forEach { list.add(fromElement(it)) } }
            is JsonPrimitive -> fromPrimitive(element)
        }

    private fun fromPrimitive(primitive: JsonPrimitive): Any {
        if (primitive.isString) return primitive.content
        val content = primitive.content
        return when {
            content == "true" -> true
            content == "false" -> false
            else -> content.toIntOrNull() ?: content.toLongOrNull() ?: content.toDouble()
        }
    }
}
