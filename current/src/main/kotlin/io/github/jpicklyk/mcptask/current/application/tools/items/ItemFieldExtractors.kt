package io.github.jpicklyk.mcptask.current.application.tools.items

import kotlinx.serialization.json.*

/**
 * JSON field extraction helpers shared across item operation handlers.
 *
 * These functions safely extract typed values from [JsonObject] item payloads,
 * handling absent fields, type mismatches, and blank strings uniformly.
 */

/**
 * Extracts a string field from a JsonObject item. Returns null if absent, not a string, or blank.
 */
internal fun extractItemString(obj: JsonObject, name: String): String? {
    val value = obj[name] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    val content = value.content
    return if (content.isBlank()) null else content
}

/**
 * Extracts a string field that can be explicitly set to null via JSON null.
 * Returns [existing] if the field is absent from the object.
 * Returns null if the field is JSON null.
 * Returns the string value if present and non-blank.
 */
internal fun extractItemStringAllowNull(obj: JsonObject, name: String, existing: String?): String? {
    if (!obj.containsKey(name)) return existing
    val element = obj[name]
    if (element is JsonNull) return null
    val value = element as? JsonPrimitive ?: return existing
    if (!value.isString) return existing
    val content = value.content
    return if (content.isBlank()) null else content
}

/**
 * Extracts an integer field from a JsonObject item. Returns null if absent or not parseable.
 */
internal fun extractItemInt(obj: JsonObject, name: String): Int? {
    val value = obj[name] as? JsonPrimitive ?: return null
    return try {
        value.content.toInt()
    } catch (_: NumberFormatException) {
        null
    }
}
