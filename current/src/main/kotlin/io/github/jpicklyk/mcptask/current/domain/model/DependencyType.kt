package io.github.jpicklyk.mcptask.current.domain.model

enum class DependencyType {
    BLOCKS, IS_BLOCKED_BY, RELATES_TO;

    companion object {
        fun fromString(value: String): DependencyType? {
            val normalized = value.uppercase().replace('-', '_')
            return entries.find { it.name == normalized }
        }
    }
}
