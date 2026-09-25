package io.github.jpicklyk.mcptask.current.domain.validation

/**
 * Thrown when a create would insert a dependency edge (`fromItemId`, `toItemId`, `type`) that
 * already exists. A [ValidationException] subtype so existing MCP call sites that catch the base
 * type keep behaving as before; REST routes that want a precise 409 instead of a generic 400 catch
 * this type specifically (see `DependencyWriteRoutes.kt`).
 */
class DuplicateDependencyException(
    message: String
) : ValidationException(message)
