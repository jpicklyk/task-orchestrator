package io.github.jpicklyk.mcptask.current.domain.validation

/**
 * Thrown when a create would insert a dependency edge (`fromItemId`, `toItemId`, `type`) that
 * already exists. A [ValidationException] subtype so existing MCP call sites that catch the base
 * type keep behaving as before; callers that want a precise duplicate
 * outcome catch this type specifically.
 */
class DuplicateDependencyException(
    message: String
) : ValidationException(message)
