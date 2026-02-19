package io.github.jpicklyk.mcptask.application.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Utility for tracking and reporting update operation efficiency metrics.
 *
 * Helps identify when update operations could be more token-efficient by
 * tracking the number of parameters provided and providing efficiency guidance.
 *
 * Related Feature: AI Update Efficiency Improvements
 * Related Task: Implement Inefficient Update Detection
 */
object UpdateEfficiencyMetrics {
    private val logger = LoggerFactory.getLogger(UpdateEfficiencyMetrics::class.java)

    /**
     * Analyzes update parameters and generates efficiency metrics.
     *
     * @param toolName Name of the update tool (e.g., "update_task")
     * @param params The parameters provided to the update operation
     * @return JsonObject containing efficiency metrics
     */
    fun analyzeUpdate(toolName: String, params: JsonElement): JsonObject {
        val paramsObj = params as? JsonObject ?: return buildJsonObject {
            put("analyzed", false)
            put("reason", "Invalid parameters structure")
        }

        // Count total parameters (excluding id which is required)
        val totalParams = paramsObj.keys.size
        val changedParams = totalParams - 1 // Subtract 'id' which is required

        // Determine efficiency level
        val efficiencyLevel = when {
            changedParams == 0 -> "no_changes" // Only id provided
            changedParams == 1 -> "optimal"    // 1 field changed
            changedParams == 2 -> "good"       // 2 fields changed
            changedParams <= 4 -> "acceptable" // 3-4 fields changed
            else -> "inefficient"              // 5+ fields changed
        }

        // Calculate estimated token usage (rough estimate)
        // Assume ~4 chars per token, estimate field sizes
        val estimatedChars = calculateEstimatedChars(paramsObj)
        val estimatedTokens = estimatedChars / 4

        // Log warning if inefficient pattern detected
        if (efficiencyLevel == "inefficient") {
            logger.warn(
                "Inefficient update detected for $toolName: $changedParams fields changed. " +
                "Consider sending only the fields that are changing. " +
                "Estimated tokens: $estimatedTokens (could be reduced by ~90% with partial updates)"
            )
        } else if (efficiencyLevel == "optimal") {
            logger.debug(
                "Optimal update for $toolName: $changedParams field changed. " +
                "Estimated tokens: $estimatedTokens"
            )
        }

        return buildJsonObject {
            put("analyzed", true)
            put("totalParams", totalParams)
            put("changedParams", changedParams)
            put("efficiencyLevel", efficiencyLevel)
            put("estimatedTokens", estimatedTokens)
            put("estimatedChars", estimatedChars)

            // Add guidance message based on efficiency level
            val guidance = when (efficiencyLevel) {
                "no_changes" -> "No fields provided for update (only id)"
                "optimal" -> "Optimal: 1 field changed"
                "good" -> "Good: 2 fields changed"
                "acceptable" -> "Acceptable: $changedParams fields changed"
                "inefficient" -> "Inefficient: $changedParams fields changed. Consider sending only changed fields for 90%+ token savings"
                else -> "Unknown efficiency level"
            }
            put("guidance", guidance)
        }
    }

    /**
     * Rough estimation of character count for JSON parameters.
     * Used to estimate token usage.
     */
    private fun calculateEstimatedChars(params: JsonObject): Int {
        // Rough estimation based on JSON string length
        // In real scenarios, you'd measure actual bytes/chars
        return params.toString().length
    }

    /**
     * Adds efficiency metrics to response metadata.
     *
     * @param existingMetadata The existing metadata JsonObject
     * @param efficiencyMetrics The efficiency metrics to add
     * @return Updated metadata JsonObject
     */
    fun addToMetadata(existingMetadata: JsonObject, efficiencyMetrics: JsonObject): JsonObject {
        return buildJsonObject {
            // Copy existing metadata
            existingMetadata.forEach { (key, value) ->
                put(key, value)
            }

            // Add efficiency metrics under "updateEfficiency" key
            put("updateEfficiency", efficiencyMetrics)
        }
    }
}
