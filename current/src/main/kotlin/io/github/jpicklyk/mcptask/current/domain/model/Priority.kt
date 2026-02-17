package io.github.jpicklyk.mcptask.current.domain.model

enum class Priority {
    HIGH, MEDIUM, LOW;

    companion object {
        fun fromString(value: String): Priority? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}
