package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

/**
 * A tool profile: the set of selects and ops a server exposes (charter D19).
 *
 * [ORCHESTRATION] includes everything [KNOWLEDGE] does, so a select or op declared [KNOWLEDGE] is
 * visible in both profiles and one declared [ORCHESTRATION] only in [ORCHESTRATION].
 */
enum class ToolProfile(
    val wire: String
) {
    KNOWLEDGE("knowledge"),
    ORCHESTRATION("orchestration");

    /** True when a select or op declared with profile [p] is visible in this profile. */
    fun includes(p: ToolProfile): Boolean = this == ORCHESTRATION || p == KNOWLEDGE
}
