package io.github.jpicklyk.mcptask.current.domain.model

enum class DependencyType {
    BLOCKS,
    IS_BLOCKED_BY,
    RELATES_TO;

    /**
     * Orients an edge stored as ([from], [to]) with this type into (blocker, blocked), or null for
     * RELATES_TO (no blocking semantics). BLOCKS: `from` blocks `to`. IS_BLOCKED_BY: `from` is
     * blocked by `to`.
     */
    fun <T> orientBlocking(
        from: T,
        to: T
    ): Pair<T, T>? =
        when (this) {
            BLOCKS -> from to to
            IS_BLOCKED_BY -> to to from
            RELATES_TO -> null
        }

    companion object {
        fun fromString(value: String): DependencyType? {
            val normalized = value.uppercase().replace('-', '_')
            return entries.find { it.name == normalized }
        }
    }
}
