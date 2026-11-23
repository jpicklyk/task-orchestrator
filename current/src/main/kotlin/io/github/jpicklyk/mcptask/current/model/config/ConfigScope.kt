package io.github.jpicklyk.mcptask.current.model.config

/** Which config a version belongs to: the single server-wide config, or one project root's config. */
enum class ConfigScope(
    val wire: String
) {
    SERVER("server"),
    ROOT("root")
}
