package io.github.jpicklyk.mcptask.current.domain.model

/** How a config version came to exist. */
enum class ConfigVersionSource(
    val wire: String
) {
    PUSH("push"),
    FILE_IMPORT("file-import"),
    MIGRATION("migration"),
    ROLLBACK("rollback"),
    RESET("reset");

    companion object {
        fun fromWire(wire: String): ConfigVersionSource =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("Unknown config version source '$wire'")
    }
}
