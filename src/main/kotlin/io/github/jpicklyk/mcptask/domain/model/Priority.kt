package io.github.jpicklyk.mcptask.domain.model

import io.github.jpicklyk.mcptask.domain.validation.ValidationException

/**
 * Enum representing priority levels for tasks and features.
 */
enum class Priority {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromString(value: String): Priority = try {
            valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid priority: $value")
        }
    }
}
