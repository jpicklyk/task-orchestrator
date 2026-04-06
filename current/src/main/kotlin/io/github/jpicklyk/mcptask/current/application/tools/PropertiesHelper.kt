package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.*

/**
 * Utility for reading and writing structured data from the WorkItem properties JSON bag.
 * Properties is stored as a TEXT column containing a JSON string.
 */
object PropertiesHelper {
    private const val TRAITS_KEY = "traits"

    /**
     * Extract the traits list from a properties JSON string.
     * Returns empty list if properties is null, malformed, or has no "traits" key.
     */
    fun extractTraits(properties: String?): List<String> {
        if (properties.isNullOrBlank()) return emptyList()
        return try {
            val json = Json.parseToJsonElement(properties).jsonObject
            json[TRAITS_KEY]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Merge traits into a properties JSON string, preserving all existing keys.
     * If properties is null or empty, starts with an empty JSON object.
     * Deduplicates trait names, preserving order.
     */
    fun mergeTraits(
        existingProperties: String?,
        traits: List<String>
    ): String {
        val baseObject =
            if (!existingProperties.isNullOrBlank()) {
                try {
                    Json.parseToJsonElement(existingProperties).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
            } else {
                JsonObject(emptyMap())
            }

        val deduplicatedTraits = traits.distinct()
        val traitsArray = JsonArray(deduplicatedTraits.map { JsonPrimitive(it) })

        val merged =
            buildJsonObject {
                // Preserve all existing keys except "traits"
                baseObject.forEach { (key, value) ->
                    if (key != TRAITS_KEY) put(key, value)
                }
                put(TRAITS_KEY, traitsArray)
            }

        return Json.encodeToString(JsonObject.serializer(), merged)
    }

    /**
     * Parse a comma-separated traits string and merge into existing properties JSON.
     * Returns [existingProperties] unchanged if [commaSeparatedTraits] is null.
     */
    fun mergeTraitsFromString(
        existingProperties: String?,
        commaSeparatedTraits: String?
    ): String? {
        if (commaSeparatedTraits == null) return existingProperties
        val traitList = commaSeparatedTraits.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return mergeTraits(existingProperties, traitList)
    }
}
