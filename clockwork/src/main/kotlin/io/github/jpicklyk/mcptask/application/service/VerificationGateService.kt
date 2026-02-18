package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.Result
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Service for checking verification gate conditions on entities.
 *
 * When an entity has `requiresVerification=true`, this service validates that:
 * 1. A section titled "Verification" (case-insensitive) exists
 * 2. The section content is not blank
 * 3. The content is valid JSON array of criteria objects
 * 4. At least one criterion is defined
 * 5. All criteria have `pass: true`
 *
 * This enforces structured acceptance criteria before completion.
 */
object VerificationGateService {

    /**
     * Result of a verification gate check.
     */
    sealed class VerificationCheckResult {
        /** All verification criteria passed. */
        object Passed : VerificationCheckResult()

        /** Verification failed with a reason and optional list of failing criteria descriptions. */
        data class Failed(
            val reason: String,
            val failingCriteria: List<String> = emptyList()
        ) : VerificationCheckResult()
    }

    /**
     * Represents a single verification criterion parsed from the JSON content.
     */
    data class VerificationCriterion(val criteria: String, val pass: Boolean)

    /**
     * Checks whether the verification gate passes for the given entity.
     *
     * @param entityId The UUID of the entity to check
     * @param containerType The container type string ("task", "feature")
     * @param context The tool execution context providing repository access
     * @return VerificationCheckResult.Passed if all criteria pass, or Failed with reason
     */
    suspend fun checkVerificationSection(
        entityId: UUID,
        containerType: String,
        context: ToolExecutionContext
    ): VerificationCheckResult {
        val entityType = when (containerType) {
            "task" -> EntityType.TASK
            "feature" -> EntityType.FEATURE
            else -> return VerificationCheckResult.Passed
        }

        val sectionsResult = context.sectionRepository().getSectionsForEntity(entityType, entityId)
        val verificationSection = when (sectionsResult) {
            is Result.Success -> sectionsResult.data.find {
                it.title.equals("Verification", ignoreCase = true)
            }
            is Result.Error -> null
        }

        // Gate 1: Section must exist
        if (verificationSection == null) {
            return VerificationCheckResult.Failed(
                "No section titled 'Verification' found on this $containerType. " +
                    "Add a Verification section with JSON acceptance criteria."
            )
        }

        // Gate 2: Content must not be blank
        if (verificationSection.content.isBlank()) {
            return VerificationCheckResult.Failed(
                "Verification section exists but has no content. " +
                    "Define acceptance criteria as JSON: [{\"criteria\": \"description\", \"pass\": false}, ...]"
            )
        }

        // Gate 3: Content must be valid JSON array of acceptance criteria
        val criteria: List<VerificationCriterion>
        try {
            criteria = parseVerificationCriteria(verificationSection.content)
        } catch (e: Exception) {
            return VerificationCheckResult.Failed(
                "Verification section content is not valid JSON. " +
                    "Expected format: [{\"criteria\": \"description\", \"pass\": true/false}, ...]"
            )
        }

        // Gate 4: Must have at least one criterion
        if (criteria.isEmpty()) {
            return VerificationCheckResult.Failed(
                "Verification section contains no criteria. " +
                    "Add at least one: [{\"criteria\": \"description\", \"pass\": false}]"
            )
        }

        // Gate 5: All criteria must pass
        val failing = criteria.filter { !it.pass }
        if (failing.isNotEmpty()) {
            return VerificationCheckResult.Failed(
                reason = "${failing.size} of ${criteria.size} acceptance criteria have not passed",
                failingCriteria = failing.map { it.criteria }
            )
        }

        return VerificationCheckResult.Passed
    }

    /**
     * Parses verification criteria from JSON content.
     *
     * Expected format: `[{"criteria": "description", "pass": true/false}, ...]`
     *
     * @param content The JSON string to parse
     * @return List of VerificationCriterion objects
     * @throws IllegalArgumentException if the JSON is malformed or missing required fields
     */
    fun parseVerificationCriteria(content: String): List<VerificationCriterion> {
        val jsonArray = Json.parseToJsonElement(content).jsonArray
        return jsonArray.map { element ->
            val obj = element.jsonObject
            VerificationCriterion(
                criteria = obj["criteria"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Each entry must have a 'criteria' field"),
                pass = obj["pass"]?.jsonPrimitive?.boolean
                    ?: throw IllegalArgumentException("Each entry must have a 'pass' field")
            )
        }
    }
}
