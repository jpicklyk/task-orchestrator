package io.github.jpicklyk.mcptask.domain.validation

/**
 * Exception thrown when a validation rule is violated.
 */
class ValidationException(message: String) : RuntimeException(message)
