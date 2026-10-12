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

    companion object {
        /**
         * Parses a profile name (the `MCP_TOOL_PROFILE` value): null or blank is [ORCHESTRATION]; otherwise the
         * trimmed, case-insensitive [wire] value; anything else is null (an unknown profile).
         */
        fun parse(raw: String?): ToolProfile? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return ORCHESTRATION
            return entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
        }
    }
}
