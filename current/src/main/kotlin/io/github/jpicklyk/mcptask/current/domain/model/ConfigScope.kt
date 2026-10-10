package io.github.jpicklyk.mcptask.current.domain.model

/** Which config a version belongs to: the single server-wide config, or one project root's config. */
enum class ConfigScope(
    val wire: String
) {
    SERVER("server"),
    ROOT("root")
}
