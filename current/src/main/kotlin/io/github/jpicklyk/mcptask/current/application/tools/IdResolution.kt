package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.repository.Result
import java.util.UUID

/**
 * Resolves a raw ID string to a [UUID], supporting both full UUIDs and short hex prefixes (4+ chars).
 * Throws [ToolValidationException] on failure (invalid format, not found, ambiguous).
 *
 * This is a standalone utility for handler classes that don't extend [BaseToolDefinition] but still
 * need short-ID resolution (e.g., [items.CreateItemHandler], [items.UpdateItemHandler], [items.DeleteItemHandler]).
 *
 * @param idStr The raw ID string (full UUID or hex prefix, minimum 4 chars)
 * @param context The tool execution context providing repository access
 * @param fieldLabel Human-readable label for error messages (e.g., "'id'", "'parentId'")
 * @return The resolved [UUID]
 * @throws ToolValidationException if the ID is invalid, not found, or ambiguous
 */
suspend fun resolveWorkItemIdString(
    idStr: String,
    context: ToolExecutionContext,
    fieldLabel: String = "id"
): UUID {
    // Fast path: full UUID (36 chars)
    if (idStr.length == 36) {
        return try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("$fieldLabel is not a valid UUID: $idStr")
        }
    }

    // Prefix validation
    if (!idStr.matches(BaseToolDefinition.HEX_PATTERN)) {
        throw ToolValidationException(
            "$fieldLabel must be a UUID or hex prefix (${BaseToolDefinition.MIN_PREFIX_LENGTH}+ chars), got: $idStr"
        )
    }
    if (idStr.length < BaseToolDefinition.MIN_PREFIX_LENGTH) {
        throw ToolValidationException(
            "$fieldLabel prefix too short: minimum ${BaseToolDefinition.MIN_PREFIX_LENGTH} hex characters, got ${idStr.length}"
        )
    }

    // Repository prefix resolution
    return when (val result = context.workItemRepository().findByIdPrefix(idStr)) {
        is Result.Success -> {
            val matches = result.data
            when {
                matches.isEmpty() ->
                    throw ToolValidationException("No WorkItem found matching $fieldLabel prefix: $idStr")
                matches.size > 1 ->
                    throw ToolValidationException("Ambiguous $fieldLabel prefix: $idStr matches ${matches.size} items")
                else -> matches.first().id
            }
        }
        is Result.Error ->
            throw ToolValidationException("Failed to resolve $fieldLabel prefix: ${result.error.message}")
    }
}
