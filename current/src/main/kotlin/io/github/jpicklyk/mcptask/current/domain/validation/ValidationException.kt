package io.github.jpicklyk.mcptask.current.domain.validation

/**
 * Thrown when domain model validation fails.
 *
 * Open so more specific validation failures (e.g. [DuplicateDependencyException]) can be caught
 * either as their own type (for a precise HTTP mapping) or as this base type (existing MCP call
 * sites that catch [ValidationException] generically keep working unchanged).
 */
open class ValidationException(
    message: String
) : RuntimeException(message)
