package io.github.jpicklyk.mcptask.application.tools.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Utility class for handling partial text updates.
 * This supports the context-efficient text update patterns.
 */
object TextUpdateUtil {

    /**
     * A data class representing a text replacement operation.
     * @property oldText The text to be replaced
     * @property newText The replacement text
     */
    data class TextReplacement(val oldText: String, val newText: String)

    /**
     * Extracts text replacements from a JSON object.
     * @param jsonElement The JSON object containing text updates
     * @return A list of TextReplacement objects or null if none found
     */
    fun extractTextReplacements(jsonElement: JsonElement): List<TextReplacement>? {
        val jsonObject = jsonElement as? JsonObject ?: return null

        val textUpdatesArray = jsonObject["textUpdates"] as? JsonArray ?: return null
        if (textUpdatesArray.isEmpty()) return null

        val replacements = mutableListOf<TextReplacement>()

        for (updateElement in textUpdatesArray) {
            val updateObj = updateElement as? JsonObject ?: continue

            val oldText = updateObj["oldText"]?.jsonPrimitive?.content ?: continue
            val newText = updateObj["newText"]?.jsonPrimitive?.content ?: continue

            replacements.add(TextReplacement(oldText, newText))
        }

        return replacements.takeIf { it.isNotEmpty() }
    }

    /**
     * Applies a list of text replacements to a content string.
     * @param content The original content
     * @param replacements The list of replacements to apply
     * @return A pair containing the updated content and a boolean indicating success
     */
    fun applyReplacements(content: String, replacements: List<TextReplacement>): Pair<String, Boolean> {
        // If no replacements, just return the original content with success
        if (replacements.isEmpty()) {
            return Pair(content, true)
        }

        var updatedContent = content

        // First, check if all replacements can be applied
        for (replacement in replacements) {
            if (!content.contains(replacement.oldText)) {
                // If any replacement text is not found, return original content with failure
                return Pair(content, false)
            }
        }

        // If all replacements are valid, apply them
        for (replacement in replacements) {
            updatedContent = updatedContent.replace(replacement.oldText, replacement.newText)
        }

        return Pair(updatedContent, true)
    }

    /**
     * Truncates content to a specified length and adds an ellipsis if needed.
     * @param content The content to truncate
     * @param maxLength The maximum length
     * @return The truncated content
     */
    fun truncateContent(content: String, maxLength: Int): String {
        return if (content.length <= maxLength) {
            content
        } else {
            // Take exactly maxLength characters and add ellipsis
            content.substring(0, maxLength) + "..."
        }
    }
}